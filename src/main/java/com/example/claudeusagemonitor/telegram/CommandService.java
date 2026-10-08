package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.account.Account;
import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountService;
import com.example.claudeusagemonitor.account.RegistrationService;
import com.example.claudeusagemonitor.account.RegistrationService.Applicant;
import com.example.claudeusagemonitor.alert.AlertService;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Разбор апдейтов бота и проверка прав.
 *
 * <p>Бот обслуживает несколько аккаунтов и работает только в личке: chat_id там равен
 * id пользователя и однозначно задаёт, чей это аккаунт. Без одобренной заявки доступны
 * только /register, /chatid и /help — остальное получает короткий отказ без каких-либо
 * данных, чтобы посторонний не узнал даже, есть ли в боте другие пользователи.
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private static final String HELP = """
            <b>Claude Usage Monitor</b>
            Следит за пятичасовым и недельным лимитами вашего аккаунта Claude и держит в чате \
            самообновляющееся статусное сообщение.

            /register — подать заявку на подключение
            /token &lt;refresh token&gt; — подключить аккаунт Claude
            /status — пересоздать статусное сообщение
            /forget — стереть мои токены и сообщения
            /chatid — идентификатор этого чата
            /help — эта справка""";

    private static final String ADMIN_HELP = """


            <b>Администратору</b>
            /users — список аккаунтов
            /revoke &lt;chat id&gt; — отозвать доступ""";

    private static final String DENIED = "⛔ Команда доступна только подключённым пользователям. Подать заявку — /register.";
    private static final String PRIVATE_ONLY = "Бот работает только в личных сообщениях.";

    /** Токен Anthropic в любой обёртке: кавычки, строка из .credentials.json и т.п. */
    private static final Pattern TOKEN = Pattern.compile("sk-ant-[A-Za-z0-9_-]+");

    private final AccountRepository repository;
    private final RegistrationService registration;
    private final AccountService accounts;
    private final AlertService alertService;
    private final TelegramClient telegramClient;

    public CommandService(AccountRepository repository, RegistrationService registration, AccountService accounts,
                          AlertService alertService, TelegramClient telegramClient) {
        this.repository = repository;
        this.registration = registration;
        this.accounts = accounts;
        this.alertService = alertService;
        this.telegramClient = telegramClient;
    }

    /** Разбирает апдейт Telegram: сообщение с командой или нажатие inline-кнопки. */
    public void handle(JsonNode update) {
        if (update.has("callback_query")) {
            handleCallback(update.path("callback_query"));
            return;
        }
        // Правка тоже может принести токен (вписали в старое сообщение) — её смотрим только ради этого
        boolean edited = update.has("edited_message");
        JsonNode message = edited ? update.path("edited_message") : update.path("message");
        String text = message.path("text").asString("").trim();
        JsonNode chat = message.path("chat");
        if (!StringUtils.hasText(text) || !chat.path("id").isIntegralNumber()) {
            return;
        }
        long chatId = chat.path("id").asLong();
        boolean privateChat = "private".equals(chat.path("type").asString(""));
        long messageId = message.path("message_id").asLong();

        // Токен, присланный без команды, — всё равно токен: в чате ему не место
        boolean containsToken = text.contains("sk-ant-");
        if (containsToken && !text.startsWith("/")) {
            text = "/token " + text;
        }
        if (!text.startsWith("/")) {
            return;
        }
        String[] parts = text.split("\\s+", 2);
        // В группах команды приходят как /status@my_bot — суффикс отбрасываем.
        String command = parts[0].split("@")[0].toLowerCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].trim() : "";
        log.info("Команда {} из чата {}", command, chatId);

        if (command.equals("/token") || containsToken) {
            // Удаляем сразу и при любом исходе: даже отказ не повод оставлять токен в истории
            TelegramResult deleted = telegramClient.deleteMessage(chatId, messageId);
            if (!deleted.ok() && containsToken) {
                // Например, в группе без прав админа: молча оставить токен на виду нельзя
                telegramClient.sendMessage(chatId, """
                        ⚠️ Не удалось удалить сообщение с токеном — он виден в чате, удалите его сами. \
                        Если бот токен не принял, считайте его скомпрометированным и сделайте новый логин.""");
            }
        }
        if (edited && !command.equals("/token")) {
            return;
        }
        if (command.equals("/token")) {
            // Из кавычек и прочей обёртки достаём сам токен; не нашли — connect покажет подсказку
            Matcher token = TOKEN.matcher(argument);
            argument = token.find() ? token.group() : containsToken ? "" : argument;
        }

        switch (command) {
            case "/chatid" -> telegramClient.sendMessage(chatId, "ID чата: <code>%d</code>".formatted(chatId));
            case "/start", "/help" -> telegramClient.sendMessage(chatId, help(privateChat ? chatId : null));
            case "/register" -> {
                if (privateChat) {
                    registration.register(applicant(message));
                } else {
                    telegramClient.sendMessage(chatId, "Регистрация — только в личных сообщениях с ботом.");
                }
            }
            default -> {
                if (!privateChat) {
                    telegramClient.sendMessage(chatId, PRIVATE_ONLY);
                    return;
                }
                handleApproved(chatId, command, argument);
            }
        }
    }

    /** Команды, требующие одобренной заявки. */
    private void handleApproved(long chatId, String command, String argument) {
        Optional<Account> found = repository.findByChatId(chatId).filter(Account::isApproved);
        if (found.isEmpty()) {
            if (isKnown(command)) {
                telegramClient.sendMessage(chatId, DENIED);
            }
            return;
        }
        Account account = found.get();
        switch (command) {
            case "/status" -> status(account);
            case "/token" -> accounts.connect(account, argument);
            case "/forget" -> telegramClient.sendMessage(chatId, accounts.forget(account));
            case "/users", "/revoke" -> {
                if (!account.isAdmin()) {
                    telegramClient.sendMessage(chatId, "⛔ Команда только для администратора.");
                } else if (command.equals("/users")) {
                    telegramClient.sendMessage(chatId, registration.users());
                } else {
                    telegramClient.sendMessage(chatId, registration.revoke(account, argument));
                }
            }
            default -> { /* прочие сообщения игнорируем */ }
        }
    }

    private static boolean isKnown(String command) {
        return switch (command) {
            case "/status", "/token", "/forget", "/users", "/revoke" -> true;
            default -> false;
        };
    }

    /** /status пересоздаёт статус именно этого аккаунта, чтобы он снова оказался последним. */
    private void status(Account account) {
        if (alertService.repostStatus(account.chatId())) {
            return;
        }
        String reply;
        if (account.tokensUnreadable()) {
            reply = MessageFormatter.TOKENS_UNREADABLE;
        } else if (!account.hasToken()) {
            reply = "Аккаунт Claude не подключён.\n\n" + MessageFormatter.TOKEN_HOWTO;
        } else {
            // Токены есть, а сессии ещё нет: её поднимет ближайший плановый опрос (например,
            // при старте база была недоступна)
            reply = "⏳ Мониторинг запускается — статус появится после ближайшего опроса.";
        }
        telegramClient.sendMessage(account.chatId(), reply);
    }

    private String help(Long privateChatId) {
        boolean admin = privateChatId != null
                && repository.findByChatId(privateChatId).map(Account::isAdmin).orElse(false);
        return admin ? HELP + ADMIN_HELP : HELP;
    }

    private void handleCallback(JsonNode query) {
        String id = query.path("id").asString("");
        JsonNode from = query.path("from");
        if (!StringUtils.hasText(id) || !from.path("id").isIntegralNumber()) {
            return;
        }
        JsonNode message = query.path("message");
        Long chatId = message.path("chat").path("id").isIntegralNumber() ? message.path("chat").path("id").asLong() : null;
        Long messageId = message.path("message_id").isIntegralNumber() ? message.path("message_id").asLong() : null;
        registration.onCallback(id, from.path("id").asLong(), query.path("data").asString(""), chatId, messageId);
    }

    private static Applicant applicant(JsonNode message) {
        JsonNode from = message.path("from");
        String name = (from.path("first_name").asString("") + " " + from.path("last_name").asString("")).trim();
        String username = from.path("username").asString("");
        return new Applicant(message.path("chat").path("id").asLong(),
                StringUtils.hasText(username) ? username : null,
                StringUtils.hasText(name) ? name : null);
    }
}
