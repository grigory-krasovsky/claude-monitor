package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import com.example.claudeusagemonitor.telegram.TelegramResult;
import com.example.claudeusagemonitor.usage.TokenProvider;
import com.example.claudeusagemonitor.usage.TokenProvider.TokenException;
import com.example.claudeusagemonitor.usage.TokenProvider.TokenGrant;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageClient.UsageException;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Подключение и отключение аккаунта Claude: /token и /forget.
 *
 * <p>Права (личка, статус APPROVED) проверяет {@code CommandService}; здесь — только
 * сама операция. Изменение базы и реестра сессий идёт под блокировкой chat_id, чтобы
 * плановый опрос не вклинился между ними.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final AccountSessions sessions;
    private final TokenProvider tokenProvider;
    private final UsageClient usageClient;
    private final AlertService alertService;
    private final TelegramClient telegramClient;

    public AccountService(AccountRepository repository, AccountSessions sessions, TokenProvider tokenProvider,
                          UsageClient usageClient, AlertService alertService, TelegramClient telegramClient) {
        this.repository = repository;
        this.sessions = sessions;
        this.tokenProvider = tokenProvider;
        this.usageClient = usageClient;
        this.alertService = alertService;
        this.telegramClient = telegramClient;
    }

    /**
     * Подключает аккаунт по refresh token: обмен, пробный запрос лимитов, сохранение,
     * запуск мониторинга. Ответ пользователю отправляется отсюда же — статусное сообщение
     * должно встать уже под ним.
     */
    public void connect(Account account, String refreshToken) {
        long chatId = account.chatId();
        String token = refreshToken == null ? "" : refreshToken.trim();
        if (!StringUtils.hasText(token)) {
            telegramClient.sendMessage(chatId, MessageFormatter.TOKEN_HOWTO);
            return;
        }
        if (token.startsWith("sk-ant-oat")) {
            // Access token (в том числе из setup-token) живёт часы, обновить его нечем
            telegramClient.sendMessage(chatId, """
                    ❌ Это access token (sk-ant-oat…), а нужен refresh token (sk-ant-ort01-…).

                    """ + MessageFormatter.TOKEN_HOWTO);
            return;
        }

        Long progressId = telegramClient.sendMessage(chatId, "⏳ Проверяю токен…").messageId();
        Optional<Connected> connected = Optional.empty();
        String reply;
        TokenGrant grant = null;
        try {
            grant = tokenProvider.exchange(token);
            // Refresh token уже погашен обменом: с этого момента действует только grant
            UsageSnapshot snapshot = usageClient.fetch(grant.accessToken());
            connected = Optional.of(new Connected(grant, snapshot));
            reply = "✅ <b>Аккаунт подключён.</b> Статус ниже обновляется сам; /forget — стереть токены.";
        } catch (TokenException e) {
            log.warn("Обмен токена для {} не удался: {}", account, e.getMessage());
            reply = describeExchangeFailure(e);
        } catch (UsageException e) {
            log.warn("Пробный запрос лимитов для {} не удался: {}", account, e.getMessage());
            if (isTransient(e)) {
                // Токен годный, Anthropic просто не ответил сейчас. Выбросив grant, мы потеряли бы
                // аккаунт: присланный refresh уже погашен, а новый нигде больше не записан
                connected = Optional.of(new Connected(grant, null));
                reply = "✅ <b>Аккаунт подключён</b>, но Anthropic пока не отдал данные о лимитах ("
                        + (e.status() == 0 ? "нет связи" : "HTTP " + e.status())
                        + "). Они появятся после ближайшего опроса; /forget — стереть токены.";
            } else {
                reply = describeProbeFailure(e)
                        + "\n\nПрисланный токен уже израсходован обменом — для новой попытки понадобится новый логин.";
            }
        }

        if (connected.isPresent()) {
            Connected c = connected.get();
            boolean saved = sessions.locked(chatId, () -> {
                Optional<Account> current = repository.findByChatId(chatId).filter(Account::isApproved);
                if (current.isEmpty()) {
                    // Пока шёл обмен, доступ отозвали — токены не сохраняем
                    return false;
                }
                // Прежняя сессия (повторный /token) отцепляется до записи: иначе её запоздалая
                // ротация перезаписала бы в базе только что присланные токены
                sessions.detach(chatId);
                repository.saveTokens(current.get().id(), chatId, c.grant.accessToken(), c.grant.refreshToken(),
                        c.grant.expiresAt());
                return true;
            });
            if (!saved) {
                reply = "⛔ Доступ к боту отозван.";
                connected = Optional.empty();
            }
        }
        reply(chatId, progressId, reply);

        connected.ifPresent(c -> sessions.locked(chatId, () -> repository.findByChatId(chatId)
                .filter(Account::isMonitored)
                .ifPresent(fresh -> alertService.start(fresh, c.snapshot))));
    }

    /**
     * /forget: стирает токены и сообщения. Обычный пользователь удаляется целиком и при
     * желании подаёт заявку заново; у администратора роль остаётся — иначе первым
     * /register админом стал бы кто угодно.
     */
    public String forget(Account account) {
        sessions.locked(account.chatId(), () -> {
            alertService.stop(account);
            if (account.isAdmin()) {
                repository.forget(account.id());
            } else {
                repository.delete(account.id());
            }
        });
        return account.isAdmin()
                ? "🗑 Токены и сообщения стёрты. Роль администратора сохранена."
                : "🗑 Ваши данные удалены. Чтобы снова пользоваться ботом, отправьте /register.";
    }

    private void reply(long chatId, Long progressId, String text) {
        if (progressId != null) {
            TelegramResult edited = telegramClient.editMessage(chatId, progressId, text);
            if (edited.ok()) {
                return;
            }
        }
        telegramClient.sendMessage(chatId, text);
    }

    /**
     * Временный сбой пробы: сеть, 429, 5xx. Окончательные ответы (401, 403 про scope или
     * регион) говорят, что с этим токеном мониторинг всё равно не заработает.
     */
    static boolean isTransient(UsageException e) {
        return e.status() == 0 || e.status() == 429 || e.status() >= 500;
    }

    static String describeExchangeFailure(TokenException e) {
        String body = e.body().toLowerCase(Locale.ROOT);
        return switch (e.status()) {
            case 0 -> "❌ Не удалось связаться с Anthropic: " + MessageFormatter.escape(e.getMessage())
                    + ". Попробуйте позже.";
            case 400, 401 -> """
                    ❌ Refresh token недействителен: истёк, отозван или уже использован \
                    (Anthropic выдаёт новый при каждом обмене). Сделайте новый логин и пришлите свежий токен.""";
            case 403 -> body.contains("request not allowed") ? geoBlocked() : "❌ Anthropic отказал в обмене токена (HTTP 403).";
            case 429 -> "⏳ Anthropic просит подождать (HTTP 429). Попробуйте через несколько минут.";
            default -> "❌ Обмен токена не удался: HTTP " + e.status() + ".";
        };
    }

    static String describeProbeFailure(UsageException e) {
        String body = e.body().toLowerCase(Locale.ROOT);
        return switch (e.status()) {
            case 0 -> "❌ Не удалось связаться с Anthropic: " + MessageFormatter.escape(e.getMessage()) + ".";
            case 401 -> "❌ Токен недействителен (HTTP 401).";
            case 403 -> {
                if (body.contains("user:profile") || body.contains("scope")) {
                    yield """
                            ❌ У токена нет scope <code>user:profile</code>, без которого Anthropic не отдаёт \
                            данные о лимитах. Так бывает с токеном из <code>claude setup-token</code> — \
                            нужен refresh token из отдельного логина (см. /help).""";
                }
                yield body.contains("request not allowed") ? geoBlocked() : "❌ Anthropic отказал в доступе (HTTP 403).";
            }
            case 429 -> "⏳ Anthropic просит подождать (HTTP 429). Попробуйте через несколько минут.";
            default -> "❌ Запрос лимитов не удался: HTTP " + e.status() + ".";
        };
    }

    private static String geoBlocked() {
        return """
                ❌ Anthropic отвечает 403 «Request not allowed»: запросы с сервера бота заблокированы \
                по региону. Это чинится на стороне бота (HTTPS_PROXY) — напишите администратору.""";
    }

    /** @param snapshot срез пробного запроса; {@code null}, если проба не прошла по временной причине */
    private record Connected(TokenGrant grant, UsageSnapshot snapshot) {
    }
}
