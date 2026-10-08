package com.example.claudeusagemonitor.testsupport;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import com.example.claudeusagemonitor.telegram.TelegramResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Telegram без сети: запоминает вызовы и отвечает заготовленными результатами.
 *
 * <p>{@link #isConfigured()} возвращает {@code false}, поэтому в Spring-тестах не стартуют
 * ни long polling, ни плановый опрос — тест сам дёргает нужные методы.
 */
public class FakeTelegramClient extends TelegramClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Sent(long chatId, String text, Map<String, String> buttons, Long messageId) {
    }

    public record Edit(long chatId, long messageId, String text) {
    }

    public record Delete(long chatId, long messageId) {
    }

    public record Answer(String callbackId, String text) {
    }

    public final List<Sent> sent = new ArrayList<>();
    public final List<Edit> edits = new ArrayList<>();
    public final List<Delete> deletes = new ArrayList<>();
    public final List<Answer> answers = new ArrayList<>();
    public final List<Long> adminCommandChats = new ArrayList<>();

    /** Ответы на следующие вызовы; когда очередь пуста — успех. */
    public final Deque<TelegramResult> sendResults = new ArrayDeque<>();
    public final Deque<TelegramResult> editResults = new ArrayDeque<>();
    public final Deque<TelegramResult> deleteResults = new ArrayDeque<>();

    private final AtomicLong nextMessageId = new AtomicLong(1000);

    public FakeTelegramClient() {
        super(new MonitorProperties(), null, null);
    }

    public synchronized void reset() {
        sent.clear();
        edits.clear();
        deletes.clear();
        answers.clear();
        adminCommandChats.clear();
        sendResults.clear();
        editResults.clear();
        deleteResults.clear();
    }

    @Override
    public boolean isConfigured() {
        return false;
    }

    @Override
    public void registerCommands() {
    }

    @Override
    public synchronized void registerAdminCommands(long adminChatId) {
        adminCommandChats.add(adminChatId);
    }

    @Override
    public synchronized TelegramResult sendMessage(long chatId, String text, Map<String, String> buttons) {
        TelegramResult result = sendResults.isEmpty() ? ok(nextMessageId.incrementAndGet()) : sendResults.poll();
        sent.add(new Sent(chatId, text, buttons, result.messageId()));
        return result;
    }

    @Override
    public synchronized TelegramResult editMessage(long chatId, long messageId, String text) {
        edits.add(new Edit(chatId, messageId, text));
        return editResults.isEmpty() ? TelegramResult.success(MAPPER.createObjectNode()) : editResults.poll();
    }

    @Override
    public synchronized TelegramResult deleteMessage(long chatId, long messageId) {
        deletes.add(new Delete(chatId, messageId));
        return deleteResults.isEmpty() ? TelegramResult.success(MAPPER.createObjectNode()) : deleteResults.poll();
    }

    @Override
    public synchronized TelegramResult answerCallbackQuery(String callbackQueryId, String text) {
        answers.add(new Answer(callbackQueryId, text));
        return TelegramResult.success(MAPPER.createObjectNode());
    }

    @Override
    public List<JsonNode> getUpdates(long offset) {
        return List.of();
    }

    public synchronized List<Sent> sentTo(long chatId) {
        return sent.stream().filter(s -> s.chatId() == chatId).toList();
    }

    public synchronized String lastTextTo(long chatId) {
        List<Sent> list = sentTo(chatId);
        return list.isEmpty() ? null : list.get(list.size() - 1).text();
    }

    public static TelegramResult ok(long messageId) {
        return TelegramResult.success(MAPPER.createObjectNode().put("message_id", messageId));
    }

    /** Ответ с ошибкой в том виде, в каком его присылает Bot API. */
    public static TelegramResult error(int code, String description, Integer retryAfter) {
        var node = MAPPER.createObjectNode().put("ok", false).put("error_code", code).put("description", description);
        if (retryAfter != null) {
            node.putObject("parameters").put("retry_after", retryAfter);
        }
        return TelegramResult.of(node);
    }
}
