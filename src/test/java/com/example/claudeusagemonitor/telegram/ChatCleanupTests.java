package com.example.claudeusagemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.account.AccountStatus;
import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient.Delete;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient.Sent;
import com.example.claudeusagemonitor.testsupport.Updates;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Чат остаётся чистым: команда пользователя удаляется сразу, ответы бота — по сроку,
 * а постоянно в чате живёт только статус.
 */
class ChatCleanupTests extends DatabaseTest {

    private static final long ADMIN = 100;
    private static final long USER = 200;
    private static final long STRANGER = 300;

    @Autowired
    private MessageJanitor janitor;

    @BeforeEach
    void approveUser() {
        send(ADMIN, "/register");
        send(USER, "/register");
        repository.decide(USER, AccountStatus.APPROVED);
        telegram.reset();
        jdbc.sql("delete from temporary_message").update();
    }

    @Test
    @DisplayName("/status: команда удалена, статус пересоздан и без собственного ответа")
    void statusCommandIsDeleted() {
        send(USER, "/token sk-ant-ort01-abc");
        long oldStatus = repository.findByChatId(USER).orElseThrow().statusMessageId();
        telegram.reset();

        var update = Updates.privateMessage(USER, "/status");
        commands.handle(update);

        assertThat(telegram.deletes).contains(new Delete(USER, Updates.messageId(update)),
                new Delete(USER, oldStatus));
        assertThat(telegram.sentTo(USER)).singleElement().satisfies(s -> assertThat(s.text()).contains("токены 42%"));
        assertThat(telegram.silent).containsExactly(telegram.sentTo(USER).get(0).messageId());
    }

    @Test
    @DisplayName("справка и ID чата: команда удалена, ответ исчезает по сроку")
    void repliesExpire() {
        var update = Updates.privateMessage(STRANGER, "/help");
        commands.handle(update);
        send(STRANGER, "/chatid");

        assertThat(telegram.deletes).contains(new Delete(STRANGER, Updates.messageId(update)));
        assertThat(telegram.sentTo(STRANGER)).hasSize(2);

        telegram.deletes.clear();
        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));

        assertThat(telegram.deletes).containsExactlyInAnyOrder(
                telegram.sentTo(STRANGER).stream().map(s -> new Delete(STRANGER, s.messageId())).toArray(Delete[]::new));
    }

    @Test
    @DisplayName("отказ постороннему тоже временный, а его команда удаляется")
    void deniedReplyExpires() {
        var update = Updates.privateMessage(STRANGER, "/status");
        commands.handle(update);

        assertThat(telegram.lastTextTo(STRANGER)).startsWith("⛔");
        assertThat(telegram.deletes).contains(new Delete(STRANGER, Updates.messageId(update)));

        telegram.deletes.clear();
        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));
        assertThat(telegram.deletes).hasSize(1);
    }

    @Test
    @DisplayName("чужие слэш-сообщения и обычный текст бот не трогает, в группе команды не удаляет")
    void unknownMessagesAreLeftAlone() {
        send(USER, "/stop");
        send(USER, "просто текст");
        commands.handle(Updates.message(-500, "group", USER, "/status@bot"));

        assertThat(telegram.deletes).isEmpty();
    }

    @Test
    @DisplayName("решение по заявке: вердикт у админа и уведомление заявителю убираются позже")
    void registrationMessagesAreCleanedUp() {
        send(STRANGER, "/register");
        Sent request = telegram.sentTo(ADMIN).get(0);
        telegram.reset();
        jdbc.sql("delete from temporary_message").update();

        commands.handle(Updates.callback(ADMIN, "approve:" + STRANGER, ADMIN, request.messageId()));

        Sent notice = telegram.sentTo(STRANGER).get(0);
        assertThat(notice.text()).contains("одобрена");

        // Вердикт читают минуты, а заявитель мог заглянуть в чат не сразу — у уведомления срок длиннее
        janitor.sweep(Instant.now().plus(MessageJanitor.REPLY_TTL).plusSeconds(1));
        assertThat(telegram.deletes).containsExactly(new Delete(ADMIN, request.messageId()));

        janitor.sweep(Instant.now().plus(MessageJanitor.NOTICE_TTL).plusSeconds(1));
        assertThat(telegram.deletes).contains(new Delete(STRANGER, notice.messageId()));
    }

    @Test
    @DisplayName("заявка, ещё не решённая, из чата админа не исчезает")
    void pendingRequestStays() {
        send(STRANGER, "/register");

        janitor.sweep(Instant.now().plus(MessageJanitor.NOTICE_TTL).plusSeconds(1));

        Sent request = telegram.sentTo(ADMIN).get(0);
        assertThat(telegram.deletes).doesNotContain(new Delete(ADMIN, request.messageId()));
    }
}
