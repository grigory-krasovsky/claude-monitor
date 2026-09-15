package com.example.claudeusagemonitor.usage;

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
     * Запрашивает текущие лимиты.
     *
     * @return срез лимитов
     * @throws UsageException если запрос не удался
     */
    public UsageSnapshot fetch() {
        if (!tokenProvider.isConfigured()) {
            throw new UsageException("Токен Anthropic не настроен");
        }
        HttpResponse<String> response = send(tokenProvider.accessToken());
        if (response.statusCode() == 401 && tokenProvider.forceRefresh()) {
            log.info("Получен 401, повторяю запрос с обновлённым токеном");
            response = send(tokenProvider.accessToken());
        }
        if (response.statusCode() != 200) {
            throw new UsageException("API вернул HTTP " + response.statusCode() + ": " + shorten(response.body()));
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            return new UsageSnapshot(window(json.get("five_hour")), window(json.get("seven_day")), Instant.now());
        } catch (JacksonException e) {
            throw new UsageException("Не удалось разобрать ответ API: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(String token) {
        if (!StringUtils.hasText(token)) {
            throw new UsageException("Пустой access token");
        }
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
            throw new UsageException("Сеть недоступна: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UsageException("Запрос прерван");
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

    /** Ошибка обращения к API лимитов. */
    public static class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }
}
