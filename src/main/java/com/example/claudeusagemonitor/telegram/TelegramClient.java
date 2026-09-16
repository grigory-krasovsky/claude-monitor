package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.config.MonitorProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Тонкая обёртка над Telegram Bot API: sendMessage и long polling getUpdates. */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private static final String API_BASE = "https://api.telegram.org/bot";

    /** Сколько секунд Telegram держит запрос getUpdates открытым. */
    static final int LONG_POLL_SECONDS = 30;

    private final MonitorProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TelegramClient(MonitorProperties properties, HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getTelegram().getToken());
    }

    /**
     * Прописывает команды бота — после этого в чате появляется кнопка «Меню»
     * со списком, а Telegram начинает подсказывать команды при вводе слэша.
     */
    public void registerCommands() {
        ObjectNode body = objectMapper.createObjectNode();
        var commands = objectMapper.createArrayNode();
        commands.add(command("status", "Пересоздать статусное сообщение"));
        commands.add(command("chatid", "ID этого чата"));
        commands.add(command("help", "Справка"));
        body.set("commands", commands);

        JsonNode response = call("setMyCommands", body, Duration.ofSeconds(20));
        if (response != null && response.path("ok").asBoolean(false)) {
            log.info("Команды бота зарегистрированы");
        } else {
            log.warn("Не удалось зарегистрировать команды бота: {}", response);
        }
    }

    private ObjectNode command(String name, String description) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("command", name);
        node.put("description", description);
        return node;
    }

    /**
     * Отправляет сообщение. Ошибки логируются, наружу не пробрасываются.
     *
     * @return идентификатор отправленного сообщения или {@code null}, если не удалось
     */
    public Long sendMessage(String chatId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);

        JsonNode response = call("sendMessage", body, Duration.ofSeconds(20));
        if (response == null || !response.path("ok").asBoolean(false)) {
            log.error("sendMessage отклонён: {}", response);
            return null;
        }
        return response.path("result").path("message_id").asLong();
    }

    /**
     * Переписывает текст ранее отправленного сообщения.
     *
     * @return {@code false}, если сообщения больше нет и его нужно создавать заново
     */
    public boolean editMessage(String chatId, long messageId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);

        JsonNode response = call("editMessageText", body, Duration.ofSeconds(20));
        if (response == null) {
            // Сетевой сбой: сообщение, скорее всего, на месте, пересоздавать его не нужно
            return true;
        }
        if (response.path("ok").asBoolean(false)) {
            return true;
        }
        String description = response.path("description").asString("");
        // Telegram считает ошибкой правку, не меняющую текст, — для нас это просто «нечего делать»
        if (description.contains("message is not modified")) {
            return true;
        }
        log.warn("editMessageText отклонён: {}", description);
        return false;
    }

    /** Удаляет сообщение. Бот может удалять свои сообщения в течение 48 часов. */
    public void deleteMessage(String chatId, long messageId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);

        JsonNode response = call("deleteMessage", body, Duration.ofSeconds(20));
        if (response != null && !response.path("ok").asBoolean(false)) {
            log.debug("deleteMessage отклонён: {}", response.path("description").asString(""));
        }
    }

    /**
     * Забирает новые апдейты, блокируясь до {@link #LONG_POLL_SECONDS} секунд.
     *
     * @param offset идентификатор первого нужного апдейта (последний обработанный + 1)
     * @return список апдейтов; пустой, если ничего не пришло или произошла ошибка
     */
    public List<JsonNode> getUpdates(long offset) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", LONG_POLL_SECONDS);
        body.set("allowed_updates", objectMapper.createArrayNode().add("message"));

        JsonNode response = call("getUpdates", body, Duration.ofSeconds(LONG_POLL_SECONDS + 15L));
        if (response == null || !response.path("ok").asBoolean(false)) {
            return List.of();
        }
        JsonNode result = response.path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<JsonNode> updates = new ArrayList<>();
        result.forEach(updates::add);
        return updates;
    }

    private JsonNode call(String method, ObjectNode body, Duration timeout) {
        String token = properties.getTelegram().getToken();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + token + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return objectMapper.readTree(response.body());
        } catch (IOException | JacksonException e) {
            log.warn("Вызов Telegram {} не удался: {}", method, e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
