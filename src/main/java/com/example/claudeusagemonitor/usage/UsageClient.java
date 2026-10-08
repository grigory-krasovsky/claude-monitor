package com.example.claudeusagemonitor.usage;

import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.config.MonitorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Клиент недокументированного эндпоинта Anthropic {@code /api/oauth/usage}.
 *
 * <p>Эндпоинт отдаёт серверные данные по лимитам, поэтому учитывается активность
 * со всех устройств — в отличие от разбора локальных jsonl-логов Claude Code.
 * Заголовок {@code User-Agent} вида {@code claude-code/<версия>} обязателен:
 * без него запросы быстро упираются в rate limit.
 */
@Component
public class UsageClient {

    private static final Logger log = LoggerFactory.getLogger(UsageClient.class);

    private static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    private static final String BETA_HEADER = "oauth-2025-04-20";

    private final MonitorProperties properties;
    private final TokenProvider tokenProvider;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public UsageClient(MonitorProperties properties, TokenProvider tokenProvider,
                       HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Запрашивает текущие лимиты аккаунта, обновляя токен при необходимости.
     *
     * @throws UsageException если запрос не удался
     */
    public UsageSnapshot fetch(AccountSession session) {
        if (!session.tokens().isConfigured()) {
            throw new UsageException(0, "Токен Anthropic не настроен");
        }
        String token = tokenProvider.accessToken(session);
        if (!StringUtils.hasText(token)) {
            // Access token нет, а обновить не вышло — причина важнее, чем «пустой токен»
            String reason = session.tokens().refreshError();
            throw new UsageException(0, reason == null ? "Пустой access token" : "Не удалось обновить токен: " + reason);
        }
        HttpResponse<String> response = send(token);
        if (response.statusCode() == 401 && tokenProvider.forceRefresh(session)) {
            log.info("Получен 401 для {}, повторяю запрос с обновлённым токеном", session);
            response = send(tokenProvider.accessToken(session));
        }
        return parse(response);
    }

    /**
     * Пробный запрос с конкретным access token, без обновления. Нужен при подключении
     * аккаунта: токен должен доказать, что открывает именно эндпоинт лимитов, прежде
     * чем попасть в базу.
     */
    public UsageSnapshot fetch(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new UsageException(0, "Пустой access token");
        }
        return parse(send(accessToken));
    }

    private UsageSnapshot parse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new UsageException(response.statusCode(), response.body());
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            return new UsageSnapshot(window(json.get("five_hour")), window(json.get("seven_day")), Instant.now());
        } catch (JacksonException e) {
            throw new UsageException(0, "Не удалось разобрать ответ API: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(USAGE_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("anthropic-beta", BETA_HEADER)
                .header("User-Agent", properties.getAnthropic().getUserAgent())
                .header("Content-Type", "application/json")
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UsageException(0, "Сеть недоступна: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsageException(0, "Запрос прерван");
        }
    }

    /** Разбирает узел вида {@code {"utilization": 48.0, "resets_at": "...", "locked_reason": null}}. */
    public static LimitWindow window(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        double percent = Math.max(0, Math.min(100, node.path("utilization").asDouble(0)));
        return new LimitWindow(percent, parseInstant(text(node, "resets_at")), text(node, "locked_reason"));
    }

    /** Строковое поле или {@code null}, если его нет либо оно равно JSON null. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString(null);
    }

    private static Instant parseInstant(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            log.warn("Не удалось разобрать resets_at: {}", value);
            return null;
        }
    }

    private static String shorten(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /**
     * Ошибка обращения к API лимитов. Статус и тело сохраняются отдельно: по ним /token
     * объясняет пользователю, что не так — scope, гео-блок или мёртвый токен.
     */
    public static class UsageException extends RuntimeException {
        private final int status;
        private final String body;

        /**
         * @param status HTTP-статус; 0 — ответа от API не было, тогда {@code body} — описание сбоя
         */
        public UsageException(int status, String body) {
            super(status == 0 ? body : "API вернул HTTP " + status + ": " + shorten(body));
            this.status = status;
            this.body = body == null ? "" : body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }
}
