package com.example.claudeusagemonitor.telegram;

import java.time.Duration;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Ответ Bot API, разобранный до того, что важно вызывающему.
 *
 * <p>Telegram отвечает JSON-телом при любом HTTP-статусе, и раньше любая ошибка правки
 * трактовалась как «сообщения нет» — на 429 бот пересоздавал статус и плодил дубли
 * ровно тогда, когда его просили притормозить. Теперь причина различается явно.
 *
 * @param ok          запрос выполнен
 * @param errorCode   {@code error_code} из ответа (или HTTP-статус, если тело не JSON);
 *                    0 — до Telegram не достучались вовсе
 * @param description текст ошибки
 * @param retryAfter  {@code parameters.retry_after} для 429; {@code null}, если не задан
 * @param result      поле {@code result} успешного ответа
 */
public record TelegramResult(boolean ok, int errorCode, String description, Duration retryAfter, JsonNode result) {

    public static TelegramResult of(JsonNode response) {
        boolean ok = response.path("ok").asBoolean(false);
        long retry = response.path("parameters").path("retry_after").asLong(0);
        return new TelegramResult(ok,
                response.path("error_code").asInt(0),
                response.path("description").asString(""),
                retry > 0 ? Duration.ofSeconds(retry) : null,
                response.path("result"));
    }

    public static TelegramResult success(JsonNode result) {
        return new TelegramResult(true, 0, "", null, result);
    }

    /** Ответ пришёл, но это не JSON Bot API — например, страница ошибки прокси. */
    public static TelegramResult httpFailure(int status, String description) {
        return new TelegramResult(false, status, description, null, null);
    }

    /** Сеть, таймаут: сообщение, скорее всего, на месте. */
    public static TelegramResult transportFailure(String description) {
        return new TelegramResult(false, 0, description, null, null);
    }

    public boolean isTransportFailure() {
        return !ok && errorCode == 0;
    }

    public boolean isRateLimited() {
        return errorCode == 429;
    }

    /** Правка, не меняющая текст: для нас это просто «нечего делать». */
    public boolean isNotModified() {
        return lower().contains("message is not modified");
    }

    /** Сообщения больше нет (удалено вручную, чат очищен) — его можно создавать заново. */
    public boolean isMessageMissing() {
        String d = lower();
        return d.contains("message to edit not found")
                || d.contains("message to delete not found")
                || d.contains("message_id_invalid")
                || d.contains("message can't be edited");
    }

    /** Писать в чат бессмысленно: пользователь заблокировал бота или чата нет. */
    public boolean isChatUnavailable() {
        String d = lower();
        return errorCode == 403
                || d.contains("bot was blocked")
                || d.contains("user is deactivated")
                || d.contains("chat not found");
    }

    /** {@code message_id} отправленного сообщения или {@code null}. */
    public Long messageId() {
        if (!ok || result == null || !result.path("message_id").isIntegralNumber()) {
            return null;
        }
        return result.path("message_id").asLong();
    }

    private String lower() {
        return description == null ? "" : description.toLowerCase(Locale.ROOT);
    }
}
