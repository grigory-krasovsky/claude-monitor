package com.example.claudeusagemonitor.usage;

import com.example.claudeusagemonitor.config.MonitorProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Хранит OAuth-токен для эндпоинта /api/oauth/usage.
 *
 * <p>Работает в двух режимах. Если задан только access token (например, выданный
 * командой {@code claude setup-token} — он живёт около года), провайдер просто
 * отдаёт его. Если дополнительно задан refresh token, провайдер сам обновляет
 * access token и сохраняет результат в файл на volume: Anthropic ротирует refresh
 * token при каждом обмене, поэтому новое значение обязательно нужно запомнить,
 * иначе после рестарта контейнера обновление перестанет работать.
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    private static final String TOKEN_URL = "https://claude.ai/v1/oauth/token";
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

    private String accessToken = "";
    private String refreshToken = "";
    private Instant expiresAt;
    private Instant refreshBlockedUntil;

    public TokenProvider(MonitorProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        MonitorProperties.Anthropic cfg = properties.getAnthropic();
        this.accessToken = cfg.getAccessToken().trim();
        this.refreshToken = cfg.getRefreshToken().trim();
        loadPersistedTokens();
        if (!StringUtils.hasText(accessToken) && !StringUtils.hasText(refreshToken)) {
            log.warn("Токен Anthropic не задан — опрос лимитов работать не будет");
        }
    }

    /** Есть ли чем ходить в API. */
    public boolean isConfigured() {
        return StringUtils.hasText(accessToken) || StringUtils.hasText(refreshToken);
    }

    /** Актуальный access token; при необходимости и возможности обновляется. */
    public synchronized String accessToken() {
        boolean expired = expiresAt != null && Instant.now().isAfter(expiresAt.minus(EXPIRY_MARGIN));
        if ((!StringUtils.hasText(accessToken) || expired) && StringUtils.hasText(refreshToken)) {
            refresh();
        }
        return accessToken;
    }

    /**
     * Принудительно обновляет токен (вызывается после 401 от API).
     *
     * @return {@code true}, если токен удалось обновить
     */
    public synchronized boolean forceRefresh() {
        if (!StringUtils.hasText(refreshToken)) {
            return false;
        }
        return refresh();
    }

    private boolean refresh() {
        if (refreshBlockedUntil != null && Instant.now().isBefore(refreshBlockedUntil)) {
            log.debug("Обновление токена отложено до {}", refreshBlockedUntil);
            return false;
        }
        if (attemptRefresh()) {
            refreshBlockedUntil = null;
            return true;
        }
        refreshBlockedUntil = Instant.now().plus(REFRESH_BACKOFF);
        log.warn("Следующая попытка обновления токена не раньше {}", refreshBlockedUntil);
        return false;
    }

    private boolean attemptRefresh() {
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
                log.error("Не удалось обновить токен: HTTP {} {}", response.statusCode(), response.body());
                return false;
            }

            JsonNode json = objectMapper.readTree(response.body());
            String newAccess = json.path("access_token").asString("");
            if (!StringUtils.hasText(newAccess)) {
                log.error("Ответ обновления токена без access_token");
                return false;
            }
            accessToken = newAccess;
            String rotated = json.path("refresh_token").asString("");
            if (StringUtils.hasText(rotated)) {
                refreshToken = rotated;
            }
            long expiresIn = json.path("expires_in").asLong(0);
            expiresAt = expiresIn > 0 ? Instant.now().plusSeconds(expiresIn) : null;
            persistTokens();
            log.info("Access token обновлён, истекает {}", expiresAt);
            return true;
        } catch (IOException | JacksonException e) {
            log.error("Ошибка обновления токена: {}", e.toString());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void loadPersistedTokens() {
        Path path = tokenFile();
        if (path == null || !Files.isReadable(path)) {
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            String storedRefresh = json.path("refreshToken").asString("");
            String storedAccess = json.path("accessToken").asString("");
            // Сохранённые токены свежее тех, что пришли из окружения.
            if (StringUtils.hasText(storedRefresh)) {
                refreshToken = storedRefresh;
            }
            if (StringUtils.hasText(storedAccess)) {
                accessToken = storedAccess;
            }
            long epoch = json.path("expiresAt").asLong(0);
            expiresAt = epoch > 0 ? Instant.ofEpochSecond(epoch) : null;
            log.info("Токены загружены из {}", path);
        } catch (IOException | JacksonException e) {
            log.warn("Не удалось прочитать {}: {}", path, e.toString());
        }
    }

    private void persistTokens() {
        Path path = tokenFile();
        if (path == null) {
            return;
        }
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("accessToken", accessToken);
            json.put("refreshToken", refreshToken);
            json.put("expiresAt", expiresAt == null ? 0 : expiresAt.getEpochSecond());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, objectMapper.writeValueAsString(json), StandardCharsets.UTF_8);
        } catch (IOException | JacksonException e) {
            log.warn("Не удалось сохранить токены в {}: {}", path, e.toString());
        }
    }

    private Path tokenFile() {
        String file = properties.getAnthropic().getTokenFile();
        return StringUtils.hasText(file) ? Path.of(file) : null;
    }
}
