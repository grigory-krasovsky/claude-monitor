package com.example.claudeusagemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient.Delete;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Отложенное удаление ответов бота: сроки, повтор при 429 и сети, окончательные отказы. */
class MessageJanitorTests extends DatabaseTest {

    private static final long CHAT = 42;

    @Autowired
    private MessageJanitor janitor;

    @Test
    @DisplayName("ответ на команду удаляется только по истечении срока")
    void replyIsDeletedAfterTtl() {
        long id = janitor.reply(CHAT, "ответ").messageId();

        janitor.sweep(Instant.now());
        assertThat(telegram.deletes).isEmpty();
        assertThat(pending()).isEqualTo(1);

        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));
        assertThat(telegram.deletes).containsExactly(new Delete(CHAT, id));
        assertThat(pending()).isZero();
    }

    @Test
    @DisplayName("уведомление без команды живёт дольше ответа")
    void noticeOutlivesReply() {
        long reply = janitor.reply(CHAT, "ответ").messageId();
        long notice = janitor.notice(CHAT, "уведомление").messageId();

        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));
        assertThat(telegram.deletes).containsExactly(new Delete(CHAT, reply));

        janitor.sweep(Instant.now().plus(MessageJanitor.NOTICE_TTL).plusSeconds(1));
        assertThat(telegram.deletes).contains(new Delete(CHAT, notice));
        assertThat(pending()).isZero();
    }

    @Test
    @DisplayName("429 и сеть — запись остаётся и удаляется на следующем прогоне")
    void transientFailureIsRetried() {
        long id = janitor.reply(CHAT, "ответ").messageId();
        Instant due = Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1);

        telegram.deleteResults.add(FakeTelegramClient.error(429, "Too Many Requests: retry after 5", 5));
        janitor.sweep(due);
        assertThat(pending()).isEqualTo(1);

        telegram.deleteResults.add(TelegramResult.transportFailure("timeout"));
        janitor.sweep(due);
        assertThat(pending()).isEqualTo(1);

        janitor.sweep(due);
        assertThat(telegram.deletes).hasSize(3).last().isEqualTo(new Delete(CHAT, id));
        assertThat(pending()).isZero();
    }

    @Test
    @DisplayName("сообщение, которое уже не удалить (старше 48 часов, чата нет), из очереди снимается")
    void permanentFailureDropsRecord() {
        janitor.reply(CHAT, "ответ");
        telegram.deleteResults.add(FakeTelegramClient.error(400, "Bad Request: message can't be deleted", null));

        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));

        assertThat(telegram.deletes).hasSize(1);
        assertThat(pending()).isZero();
    }

    @Test
    @DisplayName("уже отправленное сообщение ставится на удаление, повторный вызов переносит срок")
    void expireExistingMessage() {
        janitor.expire(CHAT, 7L, Duration.ofMinutes(1));
        janitor.expire(CHAT, 7L, Duration.ofHours(2));
        janitor.expire(CHAT, null, Duration.ZERO);

        assertThat(pending()).isEqualTo(1);
        janitor.sweep(Instant.now().plus(Duration.ofHours(1)));
        assertThat(telegram.deletes).isEmpty();

        janitor.sweep(Instant.now().plus(Duration.ofHours(3)));
        assertThat(telegram.deletes).containsExactly(new Delete(CHAT, 7L));
    }

    @Test
    @DisplayName("не отправленное сообщение (сбой Telegram) на удаление не ставится")
    void failedSendIsNotScheduled() {
        telegram.sendResults.add(TelegramResult.transportFailure("timeout"));

        janitor.reply(CHAT, "ответ");

        assertThat(pending()).isZero();
    }

    private long pending() {
        return jdbc.sql("select count(*) from temporary_message").query(Long.class).single();
    }
}
