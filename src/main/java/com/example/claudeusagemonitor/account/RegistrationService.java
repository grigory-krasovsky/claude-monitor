package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Закрытая регистрация: первый {@code /register} даёт роль администратора, остальные
 * становятся заявками, которые админ одобряет кнопками под сообщением.
 *
 * <p>Гонку двух «первых» решает база: уникальный частичный индекс допускает ровно одну
 * строку с ролью ADMIN, и проигравший получает {@link DuplicateKeyException} — это
 * значит «админ уже есть», и он уходит по обычной ветке заявки.
 *
 * <p>Владелец, импортированный из однопользовательской версии, — обычная одобренная
 * запись: если первым /register сделает он, она повысится до ADMIN с сохранением
 * токенов, а если админ уже есть — он просто «уже подключён».
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    static final String APPROVE = "approve:";
    static final String REJECT = "reject:";

    private static final String REQUEST_SENT =
            "📨 Заявка отправлена администратору. Я напишу, когда он её рассмотрит.";

    private final AccountRepository repository;
    private final AccountSessions sessions;
    private final AlertService alertService;
    private final TelegramClient telegramClient;

    public RegistrationService(AccountRepository repository, AccountSessions sessions, AlertService alertService,
                               TelegramClient telegramClient) {
        this.repository = repository;
        this.sessions = sessions;
        this.alertService = alertService;
        this.telegramClient = telegramClient;
    }

    /** Отправитель команды: в личке его id совпадает с chat_id. */
    public record Applicant(long chatId, String username, String displayName) {
    }

    /** Отдельное меню администратора — при старте, если он уже есть. */
    public void registerAdminCommands() {
        repository.findAdmin().ifPresent(admin -> telegramClient.registerAdminCommands(admin.chatId()));
    }

    public void register(Applicant applicant) {
        long chatId = applicant.chatId();
        Optional<Account> existing = repository.findByChatId(chatId);
        existing.ifPresent(a -> repository.updateProfile(chatId, applicant.username(), applicant.displayName()));

        boolean rejected = existing.map(a -> a.status() == AccountStatus.REJECTED).orElse(false);
        if (!rejected && existing.map(a -> !a.isAdmin()).orElse(true) && repository.findAdmin().isEmpty()) {
            try {
                Account admin = repository.promoteToAdmin(chatId, applicant.username(), applicant.displayName());
                log.info("Администратор назначен: {}", admin);
                telegramClient.registerAdminCommands(chatId);
                String tokenHint = admin.hasToken()
                        ? "Аккаунт Claude уже подключён, мониторинг продолжается."
                        : MessageFormatter.TOKEN_HOWTO;
                telegramClient.sendMessage(chatId, """
                        👑 <b>Вы администратор бота.</b>
                        Заявки других пользователей будут приходить сюда с кнопками «Одобрить» и «Отклонить»; \
                        /users — список аккаунтов, /revoke — отозвать доступ.

                        """ + tokenHint);
                return;
            } catch (DuplicateKeyException e) {
                // Кто-то стал админом между проверкой и вставкой — значит, этот пользователь второй
                log.info("Администратор появился параллельно, {} идёт заявкой", chatId);
                existing = repository.findByChatId(chatId);
            }
        }

        if (existing.isPresent()) {
            Account account = existing.get();
            // Прошлая отправка заявки не удалась (429, сеть): без повтора заявка так и висела
            // бы без кнопок, и одобрить её было бы нечем
            if (account.status() == AccountStatus.PENDING && account.requestMessageId() == null
                    && notifyAdmin(account)) {
                telegramClient.sendMessage(chatId, REQUEST_SENT);
                return;
            }
            telegramClient.sendMessage(chatId, statusReply(account));
            return;
        }
        if (!repository.createPending(chatId, applicant.username(), applicant.displayName())) {
            // Два /register подряд: запись уже создал параллельный запрос
            repository.findByChatId(chatId).ifPresent(a -> telegramClient.sendMessage(chatId, statusReply(a)));
            return;
        }
        Account pending = repository.findByChatId(chatId).orElseThrow();
        notifyAdmin(pending);
        telegramClient.sendMessage(chatId, REQUEST_SENT);
    }

    private static String statusReply(Account account) {
        return switch (account.status()) {
            case PENDING -> "⏳ Заявка уже на рассмотрении у администратора.";
            case REJECTED -> "❌ Заявка отклонена.";
            case APPROVED -> account.isAdmin()
                    ? "👑 Вы администратор бота и уже подключены."
                    : "✅ Вы уже подключены." + (account.hasToken() ? "" : "\n\n" + MessageFormatter.TOKEN_HOWTO);
        };
    }

    /** @return {@code true}, если заявка ушла администратору */
    private boolean notifyAdmin(Account pending) {
        Optional<Account> admin = repository.findAdmin();
        if (admin.isEmpty()) {
            log.warn("Заявка {} создана, но администратора нет", pending);
            return false;
        }
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("✅ Одобрить", APPROVE + pending.chatId());
        buttons.put("❌ Отклонить", REJECT + pending.chatId());
        Long messageId = telegramClient.sendMessage(admin.get().chatId(), requestText(pending), buttons).messageId();
        if (messageId == null) {
            log.warn("Не удалось отправить заявку {} администратору", pending);
            return false;
        }
        repository.setRequestMessageId(pending.chatId(), messageId);
        return true;
    }

    private static String requestText(Account account) {
        return "📝 <b>Заявка на подключение</b>\nот " + MessageFormatter.escape(account.label());
    }

    /**
     * Нажатие кнопки под заявкой. Решать может только администратор; решение применяется
     * лишь к записи в статусе PENDING, так что повторное нажатие ничего не меняет.
     *
     * @param messageChatId чат и id сообщения с кнопками; {@code null}, если Telegram их не прислал
     */
    public void onCallback(String callbackId, long fromId, String data, Long messageChatId, Long messageId) {
        Optional<Account> admin = repository.findAdmin();
        if (admin.isEmpty() || admin.get().chatId() != fromId) {
            log.warn("Нажатие кнопки заявки не от администратора: {}", fromId);
            telegramClient.answerCallbackQuery(callbackId, "Решать заявки может только администратор");
            return;
        }
        AccountStatus decision;
        long target;
        try {
            if (data.startsWith(APPROVE)) {
                decision = AccountStatus.APPROVED;
                target = Long.parseLong(data.substring(APPROVE.length()));
            } else if (data.startsWith(REJECT)) {
                decision = AccountStatus.REJECTED;
                target = Long.parseLong(data.substring(REJECT.length()));
            } else {
                telegramClient.answerCallbackQuery(callbackId, "Неизвестная кнопка");
                return;
            }
        } catch (NumberFormatException e) {
            telegramClient.answerCallbackQuery(callbackId, "Неизвестная кнопка");
            return;
        }

        Optional<Account> decided = repository.decide(target, decision);
        if (decided.isEmpty()) {
            telegramClient.answerCallbackQuery(callbackId, "Заявка уже обработана");
            // Кнопки могли остаться от прошлого раза — снимаем, показав текущее состояние
            repository.findByChatId(target).ifPresent(a -> editRequest(admin.get(), a, messageChatId, messageId));
            return;
        }
        Account account = decided.get();
        log.info("Заявка {} {}", account, decision == AccountStatus.APPROVED ? "одобрена" : "отклонена");
        telegramClient.answerCallbackQuery(callbackId,
                decision == AccountStatus.APPROVED ? "Одобрено" : "Отклонено");
        editRequest(admin.get(), account, messageChatId, messageId);
        telegramClient.sendMessage(target, decision == AccountStatus.APPROVED
                ? "🎉 Заявка одобрена!\n\n" + MessageFormatter.TOKEN_HOWTO
                : "❌ Заявка отклонена администратором.");
    }

    private void editRequest(Account admin, Account account, Long messageChatId, Long messageId) {
        long chat = messageChatId != null ? messageChatId : admin.chatId();
        Long id = messageId != null ? messageId : account.requestMessageId();
        if (id == null) {
            return;
        }
        String verdict = switch (account.status()) {
            case APPROVED -> "✅ одобрено";
            case REJECTED -> "❌ отклонено";
            case PENDING -> "⏳ на рассмотрении";
        };
        telegramClient.editMessage(chat, id, requestText(account) + "\n\n" + verdict);
    }

    /** /users: кто есть в боте. Токены не показываются — только факт их наличия. */
    public String users() {
        List<Account> accounts = repository.findAll();
        if (accounts.isEmpty()) {
            return "Аккаунтов нет.";
        }
        StringBuilder sb = new StringBuilder("<b>Аккаунты</b>");
        for (Account a : accounts) {
            sb.append("\n\n").append(a.isAdmin() ? "👑 " : "• ").append(MessageFormatter.escape(a.label()))
                    .append("\n    ").append(statusLabel(a.status()))
                    .append(", токен: ")
                    .append(a.tokensUnreadable() ? "не читается" : a.hasToken() ? "подключён" : "нет");
        }
        return sb.toString();
    }

    private static String statusLabel(AccountStatus status) {
        return switch (status) {
            case PENDING -> "заявка";
            case APPROVED -> "одобрен";
            case REJECTED -> "отклонён";
        };
    }

    /** /revoke: перевод в REJECTED с остановкой мониторинга и удалением статуса из чата. */
    public String revoke(Account admin, String argument) {
        long target;
        try {
            target = Long.parseLong(argument.trim());
        } catch (NumberFormatException e) {
            return "Использование: <code>/revoke &lt;chat id&gt;</code> — id есть в /users.";
        }
        if (target == admin.chatId()) {
            return "Нельзя отозвать доступ у самого себя.";
        }
        Optional<Account> account = repository.findByChatId(target);
        if (account.isEmpty()) {
            return "Аккаунт " + target + " не найден.";
        }
        sessions.locked(target, () -> {
            alertService.stop(account.get());
            repository.revoke(target);
        });
        log.info("Доступ {} отозван", account.get());
        telegramClient.sendMessage(target, "⛔ Доступ к боту отозван администратором.");
        return "Доступ " + MessageFormatter.escape(account.get().label()) + " отозван.";
    }
}
