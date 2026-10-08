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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Тонкая обёртка над Telegram Bot API.
 *
 * <p>Методы не бросают исключений и возвращают {@link TelegramResult}: что делать с ошибкой
 * (пересоздать сообщение, подождать, замолчать), решает вызывающий — у статуса и у ответа
 * на команду реакции разные.
 *
 * <p>Класс не final намеренно: тесты подменяют его фейком и в сеть не ходят.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private static final String API_BASE = "https://api.telegram.org/bot";

    /** Сколько секунд Telegram держит запрос getUpdates открытым. */
    static final int LONG_POLL_SECONDS = 30;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Команды для всех. Админское меню включает их же: scope чата заменяет общий список, а не дополняет. */
    private static final Map<String, String> COMMANDS = commands(
            "status", "Пересоздать статусное сообщение",
            "register", "Подать заявку на подключение",
            "token", "Подключить аккаунт Claude: /token <refresh token>",
            "forget", "Стереть мои токены и сообщения",
            "chatid", "ID этого чата",
            "help", "Справка");

    private static final Map<String, String> ADMIN_COMMANDS = commands(
            "users", "Список аккаунтов",
            "revoke", "Отозвать доступ: /revoke <chat id>");

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
        body.set("commands", commandArray(COMMANDS, Map.of()));
        logRegistration("общие", call("setMyCommands", body, TIMEOUT));
    }

    /** Отдельное меню для чата администратора: только он видит /users и /revoke. */
    public void registerAdminCommands(long adminChatId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("commands", commandArray(COMMANDS, ADMIN_COMMANDS));
        ObjectNode scope = body.putObject("scope");
        scope.put("type", "chat");
        scope.put("chat_id", adminChatId);
        logRegistration("админские", call("setMyCommands", body, TIMEOUT));
    }

    private void logRegistration(String kind, TelegramResult result) {
        if (result.ok()) {
            log.info("Команды бота ({}) зарегистрированы", kind);
        } else {
            log.warn("Не удалось зарегистрировать команды бота ({}): {}", kind, result.description());
        }
    }

    /** Отправляет сообщение в HTML-разметке. */
    public TelegramResult sendMessage(long chatId, String text) {
        return sendMessage(chatId, text, null);
    }

    /**
     * Отправляет сообщение с inline-клавиатурой.
     *
     * @param buttons пары «текст кнопки → callback_data» одной строкой; {@code null} — без клавиатуры
     */
    public TelegramResult sendMessage(long chatId, String text, Map<String, String> buttons) {
        ObjectNode body = textBody(chatId, text);
        if (buttons != null && !buttons.isEmpty()) {
            body.set("reply_markup", keyboard(buttons));
        }
        TelegramResult result = call("sendMessage", body, TIMEOUT);
        if (!result.ok()) {
            log.warn("sendMessage в {} отклонён: {} {}", chatId, result.errorCode(), result.description());
        }
        return result;
    }

    /**
     * Переписывает текст ранее отправленного сообщения. Inline-клавиатура при этом
     * снимается явно: заявка после решения не должна оставаться с живыми кнопками.
     */
    public TelegramResult editMessage(long chatId, long messageId, String text) {
        ObjectNode body = textBody(chatId, text);
        body.put("message_id", messageId);
        body.set("reply_markup", keyboard(Map.of()));
        TelegramResult result = call("editMessageText", body, TIMEOUT);
        if (!result.ok() && !result.isNotModified()) {
            log.debug("editMessageText в {} отклонён: {} {}", chatId, result.errorCode(), result.description());
        }
        return result;
    }

    /** Удаляет сообщение. Бот может удалять свои сообщения в течение 48 часов. */
    public TelegramResult deleteMessage(long chatId, long messageId) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        TelegramResult result = call("deleteMessage", body, TIMEOUT);
        if (!result.ok()) {
            log.debug("deleteMessage в {} отклонён: {}", chatId, result.description());
        }
        return result;
    }

    /** Ответ на нажатие inline-кнопки: без него у пользователя бесконечно крутятся «часики». */
    public TelegramResult answerCallbackQuery(String callbackQueryId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("callback_query_id", callbackQueryId);
        if (StringUtils.hasText(text)) {
            body.put("text", text);
        }
        return call("answerCallbackQuery", body, TIMEOUT);
    }

    /**
     * Забирает новые апдейты, блокируясь до {@link #LONG_POLL_SECONDS} секунд.
     *
     * @param offset идентификатор первого нужного апдейта (последний обработанный + 1)
     * @return список апдейтов; пустой, если ничего не пришло
     * @throws IllegalStateException если Telegram ответил ошибкой — вызывающий делает паузу.
     *         Раньше ошибка превращалась в пустой список, и на мгновенных отказах
     *         (409 от второго экземпляра бота) цикл опроса крутился вхолостую
     */
    public List<JsonNode> getUpdates(long offset) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("offset", offset);
        body.put("timeout", LONG_POLL_SECONDS);
        body.set("allowed_updates", objectMapper.createArrayNode()
                .add("message").add("edited_message").add("callback_query"));

        TelegramResult result = call("getUpdates", body, Duration.ofSeconds(LONG_POLL_SECONDS + 15L));
        if (!result.ok()) {
            throw new IllegalStateException("getUpdates: " + result.errorCode() + " " + result.description());
        }
        List<JsonNode> updates = new ArrayList<>();
        if (result.result() != null && result.result().isArray()) {
            result.result().forEach(updates::add);
        }
        return updates;
    }

    private ObjectNode textBody(long chatId, String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        return body;
    }

    private ObjectNode keyboard(Map<String, String> buttons) {
        ObjectNode markup = objectMapper.createObjectNode();
        ArrayNode rows = markup.putArray("inline_keyboard");
        if (!buttons.isEmpty()) {
            ArrayNode row = rows.addArray();
            buttons.forEach((text, data) -> row.addObject().put("text", text).put("callback_data", data));
        }
        return markup;
    }

    private ArrayNode commandArray(Map<String, String> first, Map<String, String> second) {
        ArrayNode array = objectMapper.createArrayNode();
        first.forEach((name, description) -> array.addObject().put("command", name).put("description", description));
        second.forEach((name, description) -> array.addObject().put("command", name).put("description", description));
        return array;
    }

    private static Map<String, String> commands(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private TelegramResult call(String method, ObjectNode body, Duration timeout) {
        String token = properties.getTelegram().getToken();
        if (!StringUtils.hasText(token)) {
            return TelegramResult.transportFailure("токен Telegram не задан");
        }
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + token + "/" + method))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.warn("Вызов Telegram {} не удался: {}", method, e.toString());
            return TelegramResult.transportFailure(e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TelegramResult.transportFailure("прервано");
        }
        // Тело разбираем при любом статусе: именно в нём лежат description и retry_after
        try {
            return TelegramResult.of(objectMapper.readTree(response.body()));
        } catch (JacksonException e) {
            log.warn("Telegram {} вернул не JSON: HTTP {}", method, response.statusCode());
            return TelegramResult.httpFailure(response.statusCode(), "HTTP " + response.statusCode());
        }
    }
}
