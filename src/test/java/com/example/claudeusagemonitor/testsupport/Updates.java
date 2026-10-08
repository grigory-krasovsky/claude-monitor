package com.example.claudeusagemonitor.testsupport;

import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Апдейты Telegram в том виде, в каком их отдаёт getUpdates. */
public final class Updates {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong(1);

    private Updates() {
    }

    /** Сообщение в личке: chat.id совпадает с from.id. */
    public static JsonNode privateMessage(long userId, String text) {
        return message(userId, "private", userId, text);
    }

    public static JsonNode message(long chatId, String chatType, long userId, String text) {
        ObjectNode update = MAPPER.createObjectNode().put("update_id", IDS.incrementAndGet());
        ObjectNode message = update.putObject("message");
        message.put("message_id", IDS.incrementAndGet());
        message.put("text", text);
        message.putObject("chat").put("id", chatId).put("type", chatType);
        message.putObject("from").put("id", userId).put("first_name", "User").put("last_name", String.valueOf(userId))
                .put("username", "user" + userId);
        return update;
    }

    public static long messageId(JsonNode update) {
        JsonNode message = update.has("edited_message") ? update.path("edited_message") : update.path("message");
        return message.path("message_id").asLong();
    }

    /** То же сообщение, но пришедшее правкой (edited_message). */
    public static JsonNode edited(JsonNode update) {
        ObjectNode copy = ((ObjectNode) update).deepCopy();
        copy.set("edited_message", copy.remove("message"));
        return copy;
    }

    /** Нажатие inline-кнопки под сообщением {@code messageId} в чате {@code messageChatId}. */
    public static JsonNode callback(long fromId, String data, long messageChatId, long messageId) {
        ObjectNode update = MAPPER.createObjectNode().put("update_id", IDS.incrementAndGet());
        ObjectNode query = update.putObject("callback_query");
        query.put("id", "cb" + IDS.incrementAndGet());
        query.put("data", data);
        query.putObject("from").put("id", fromId).put("first_name", "User");
        ObjectNode message = query.putObject("message");
        message.put("message_id", messageId);
        message.putObject("chat").put("id", messageChatId).put("type", "private");
        return update;
    }
}
