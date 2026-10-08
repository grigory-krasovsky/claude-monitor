package com.example.claudeusagemonitor.usage;

import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.config.MonitorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * OAuth-токены аккаунтов для эндпоинта /api/oauth/usage.
 *
 * <p>Рабочий источник — refresh token из отдельного логина {@code claude} (свой
 * {@code CLAUDE_CONFIG_DIR}): access token живёт 8 часов, и провайдер сам его обновляет.
 * Anthropic ротирует refresh token при каждом обмене, поэтому новое значение сразу
 * пишется в базу — иначе после рестарта обновление перестало бы работать. Токен из
 * {@code claude setup-token} не годится: у него только scope {@code user:inference},
 * а эндпоинт лимитов требует {@code user:profile} и отвечает 403.
 *
 * <p>Состояние хранится в {@link AccountSession.Tokens}, и обмен идёт под его монитором:
 * блокировка на аккаунт, а не общая, чтобы медленный обмен одного пользователя
 * не задерживал опрос другого.
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    /**
     * Только этот хост обслуживает обмен токена. Варианты на {@code claude.ai} и
     * {@code console.anthropic.com}, которые попадаются в чужих проектах, отвечают
     * 429 rate_limit_error на любой запрос, включая заведомо недействительный токен,
     * — то есть не являются рабочим эндпоинтом вовсе, а 429 там сбивает с толку,
     * маскируясь под временное ограничение.
     */
    private static final String TOKEN_URL = "https://api.anthropic.com/v1/oauth/token";
    private static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";

    /** Обновляем заранее, чтобы не поймать 401 в середине опроса. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /**
     * Пауза после неудачного обновления. Эндпоинт отвечает 429 при слишком частых
     * обменах, а опрос лимитов идёт раз в три минуты — без паузы протухший токен
     * означал бы два десятка запросов в час к тому, кто уже попросил подождать.
     */
    private static final Duration REFRESH_BACKOFF = Duration.ofMinutes(15);

    private final MonitorProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AccountRepository repository;

    public TokenProvider(MonitorProperties properties, HttpClient httpClient, ObjectMapper objectMapper,
                         AccountRepository repository) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    /** Актуальный access token аккаунта; при необходимости и возможности обновляется. */
    public String accessToken(AccountSession session) {
        AccountSession.Tokens tokens = session.tokens();
        synchronized (tokens) {
            retryPersist(session);
            Instant expiresAt = tokens.expiresAt();
            boolean expired = expiresAt != null && Instant.now().isAfter(expiresAt.minus(EXPIRY_MARGIN));
            if ((!StringUtils.hasText(tokens.accessToken()) || expired)
                    && StringUtils.hasText(tokens.refreshToken())) {
                refresh(session);
            }
            return tokens.accessToken();
        }
    }

    /**
     * Принудительно обновляет токен (вызывается после 401 от API).
     *
     * @return {@code true}, если токен удалось обновить
     */
    public boolean forceRefresh(AccountSession session) {
        AccountSession.Tokens tokens = session.tokens();
        synchronized (tokens) {
            retryPersist(session);
            return StringUtils.hasText(tokens.refreshToken()) && refresh(session);
        }
    }

    /** Вызывается под монитором токенов. */
    private boolean refresh(AccountSession session) {
        AccountSession.Tokens tokens = session.tokens();
        if (session.isDetached()) {
            return false;
        }
        Instant blockedUntil = tokens.refreshBlockedUntil();
        if (blockedUntil != null && Instant.now().isBefore(blockedUntil)) {
            log.debug("Обновление токена {} отложено до {}", session, blockedUntil);
            return false;
        }
        try {
            TokenGrant grant = exchange(tokens.refreshToken());
            tokens.update(grant.accessToken(), grant.refreshToken(), grant.expiresAt());
            tokens.setRefreshBlockedUntil(null);
            tokens.setRefreshError(null);
            persist(session);
            log.info("Access token {} обновлён, истекает {}", session, grant.expiresAt());
            return true;
        } catch (TokenException e) {
            tokens.setRefreshBlockedUntil(Instant.now().plus(REFRESH_BACKOFF));
            tokens.setRefreshError(e.getMessage());
            log.warn("Обновление токена {} не удалось ({}), следующая попытка не раньше {}",
                    session, e.getMessage(), tokens.refreshBlockedUntil());
            return false;
        }
    }

    /**
     * Ротированный refresh token существует только здесь: если не сохранить его, после
     * рестарта аккаунт окажется с уже погашенным токеном. Поэтому сбой базы не только
     * громко логируется, но и помечает токены несохранёнными — запись повторяется при
     * каждом следующем обращении, а не ждёт очередного обмена через восемь часов.
     * Вызывается под монитором токенов.
     */
    private void persist(AccountSession session) {
        AccountSession.Tokens tokens = session.tokens();
        try {
            repository.saveTokens(session.accountId(), session.chatId(),
                    tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt());
            if (tokens.isUnsaved()) {
                log.info("Отложенная запись токенов {} прошла", session);
            }
            tokens.setUnsaved(false);
        } catch (DataAccessException e) {
            tokens.setUnsaved(true);
            log.error("Не удалось сохранить обновлённые токены {}, повторю при следующем обращении: {}",
                    session, e.toString());
        }
    }

    /** Дописывает в базу токены, которые не удалось сохранить сразу после обмена. */
    private void retryPersist(AccountSession session) {
        // Отцепленная сессия строкой больше не владеет: /forget или новый /token уже решили её судьбу
        if (session.tokens().isUnsaved() && !session.isDetached()) {
            persist(session);
        }
    }

    /**
     * Обменивает refresh token на пару токенов. Сам ничего не запоминает — это делает
     * вызывающий: сессия при плановом обновлении, /token при подключении аккаунта.
     *
     * @throws TokenException с HTTP-статусом, если обмен не удался
     */
    public TokenGrant exchange(String refreshToken) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("grant_type", "refresh_token");
            body.put("client_id", CLIENT_ID);
            body.put("refresh_token", refreshToken);

            HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", properties.getAnthropic().getUserAgent())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new TokenException(response.statusCode(), response.body());
            }

            JsonNode json = objectMapper.readTree(response.body());
            String newAccess = json.path("access_token").asString("");
            if (!StringUtils.hasText(newAccess)) {
                throw new TokenException(200, "ответ без access_token");
            }
            // Если ротации почему-то не было, прежний refresh token остаётся в силе
            String rotated = json.path("refresh_token").asString("");
            long expiresIn = json.path("expires_in").asLong(0);
            return new TokenGrant(newAccess,
                    StringUtils.hasText(rotated) ? rotated : refreshToken,
                    expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null);
        } catch (IOException e) {
            throw new TokenException(0, "сеть недоступна: " + e.getMessage());
        } catch (JacksonException e) {
            throw new TokenException(200, "не удалось разобрать ответ: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenException(0, "запрос прерван");
        }
    }

    /** Результат обмена refresh token. {@code toString} без значений — токены не для логов. */
    public record TokenGrant(String accessToken, String refreshToken, Instant expiresAt) {
        @Override
        public String toString() {
            return "TokenGrant[expiresAt=" + expiresAt + "]";
        }
    }

    /** Обмен токена не удался. */
    public static class TokenException extends RuntimeException {
        private final int status;
        private final String body;

        public TokenException(int status, String body) {
            super(status == 0 ? body : "HTTP " + status + ": " + shorten(body));
            this.status = status;
            this.body = body == null ? "" : body;
        }

        /** HTTP-статус; 0 — до Anthropic не достучались. */
        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        private static String shorten(String body) {
            if (body == null) {
                return "";
            }
            return body.length() > 300 ? body.substring(0, 300) + "…" : body;
        }
    }
}
