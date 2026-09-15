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
        commands.add(command("status", "Текущая утилизация лимитов"));
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

    /** Отправляет сообщение в чат, заданный в настройках. Без chat-id молча ничего не делает. */
    public void sendToConfiguredChat(String text) {
        String chatId = properties.getTelegram().getChatId();
        if (!StringUtils.hasText(chatId)) {
            log.warn("chat-id не задан, сообщение не отправлено: {}", text.lines().findFirst().orElse(""));
            return;
        }
        sendMessage(chatId, text);
    }

    /** Отправляет сообщение в произвольный чат. Ошибки логируются, наружу не пробрасываются. */
    public void sendMessage(String chatId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        try {
            JsonNode response = call("sendMessage", body, Duration.ofSeconds(20));
            if (response != null && !response.path("ok").asBoolean(false)) {
                log.error("sendMessage отклонён: {}", response);
            }
        } catch (RuntimeException e) {
            log.error("Не удалось отправить сообщение: {}", e.toString());
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
