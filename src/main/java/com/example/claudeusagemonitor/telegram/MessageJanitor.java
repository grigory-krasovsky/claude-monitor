package com.example.claudeusagemonitor.telegram;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Убирает из чата всё, кроме статуса: ответы бота на команды и уведомления живут ограниченное
 * время и удаляются сами.
 *
 * <p>Сроки лежат в таблице {@code temporary_message}, а не в памяти: редеплой перезапускает
 * контейнер, и таймеры в памяти оставили бы в чате всё, что было отправлено перед ним.
 * Заодно сюда попадают сообщения, которые не удалось удалить сразу (429, сеть) — их id
 * иначе были бы просто забыты.
 *
 * <p>Telegram удаляет сообщения бота только в первые 48 часов, поэтому сроки заметно короче.
 * Если срок вышел, а сообщение уже не удалить (старше 48 часов, чат недоступен), запись всё
 * равно снимается: повторять бессмысленно.
 */
@Component
public class MessageJanitor {

    private static final Logger log = LoggerFactory.getLogger(MessageJanitor.class);

    /** Ответ на команду живёт столько, чтобы его успели прочитать и выполнить, что в нём написано. */
    public static final Duration REPLY_TTL = Duration.ofMinutes(10);

    /**
     * Уведомление, которого пользователь не ждал (заявку одобрили, доступ отозвали), ждёт его
     * дольше: человек может заглянуть в чат не сразу.
     */
    public static final Duration NOTICE_TTL = Duration.ofHours(24);

    /** За один прогон — не больше, чтобы после долгой недоступности Telegram не упереться в лимиты. */
    private static final int BATCH = 50;

    private final TelegramClient telegramClient;
    private final JdbcClient jdbc;

    public MessageJanitor(TelegramClient telegramClient, JdbcClient jdbc) {
        this.telegramClient = telegramClient;
        this.jdbc = jdbc;
    }

    /** Отправляет ответ на команду и ставит его на удаление через {@link #REPLY_TTL}. */
    public TelegramResult reply(long chatId, String text) {
        return send(chatId, text, REPLY_TTL);
    }

    /** Отправляет уведомление и ставит его на удаление через {@link #NOTICE_TTL}. */
    public TelegramResult notice(long chatId, String text) {
        return send(chatId, text, NOTICE_TTL);
    }

    private TelegramResult send(long chatId, String text, Duration ttl) {
        TelegramResult result = telegramClient.sendMessage(chatId, text);
        expire(chatId, result.messageId(), ttl);
        return result;
    }

    /**
     * Ставит уже существующее сообщение на удаление через {@code ttl}; повторный вызов
     * переносит срок. Сбой базы не должен ронять обработку команды — сообщение просто останется.
     *
     * @param messageId {@code null} (отправка не удалась) — ничего не делает
     */
    public void expire(long chatId, Long messageId, Duration ttl) {
        if (messageId == null) {
            return;
        }
        try {
            jdbc.sql("""
                            insert into temporary_message (chat_id, message_id, delete_at)
                            values (:chatId, :messageId, :deleteAt)
                            on conflict (chat_id, message_id) do update set delete_at = excluded.delete_at""")
                    .param("chatId", chatId)
                    .param("messageId", messageId)
                    .param("deleteAt", timestamp(Instant.now().plus(ttl)))
                    .update();
        } catch (DataAccessException e) {
            log.error("Не удалось запланировать удаление сообщения {} в чате {}: {}", messageId, chatId, e.toString());
        }
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT30S")
    public void sweep() {
        if (!telegramClient.isConfigured()) {
            return;
        }
        try {
            sweep(Instant.now());
        } catch (RuntimeException e) {
            log.error("Уборка сообщений не удалась: {}", e.toString());
        }
    }

    /** Удаляет всё, чей срок вышел к моменту {@code now}. Отдельно от расписания — ради тестов. */
    void sweep(Instant now) {
        List<Expired> due = jdbc.sql("""
                        select chat_id, message_id from temporary_message
                         where delete_at <= :now
                         order by delete_at
                         limit :limit""")
                .param("now", timestamp(now))
                .param("limit", BATCH)
                .query((rs, row) -> new Expired(rs.getLong("chat_id"), rs.getLong("message_id")))
                .list();
        for (Expired message : due) {
            TelegramResult result = telegramClient.deleteMessage(message.chatId(), message.messageId());
            if (result.isRateLimited() || result.isTransportFailure()) {
                // Сообщение, скорее всего, на месте — запись остаётся до следующего прогона
                log.warn("Удаление сообщения {} в чате {} отложено: {}", message.messageId(), message.chatId(),
                        result.description());
                return;
            }
            jdbc.sql("delete from temporary_message where chat_id = :chatId and message_id = :messageId")
                    .param("chatId", message.chatId())
                    .param("messageId", message.messageId())
                    .update();
        }
    }

    /** pgjdbc не принимает {@link Instant} напрямую, а {@link OffsetDateTime} — да. */
    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(java.sql.Types.TIMESTAMP_WITH_TIMEZONE, value.atOffset(ZoneOffset.UTC));
    }

    private record Expired(long chatId, long messageId) {
    }
}
