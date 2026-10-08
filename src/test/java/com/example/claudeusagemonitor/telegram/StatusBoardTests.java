package com.example.claudeusagemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.testsupport.FakeTelegramClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Реакция доски на ошибки Bot API. Главное — статус пересоздаётся только тогда, когда
 * Telegram прямо говорит, что сообщения нет; на 429 и блокировку бот молчит, а не плодит дубли.
 */
class StatusBoardTests {

    private static final long CHAT = 42;
    private static final long STATUS_ID = 7;

    private final FakeTelegramClient telegram = new FakeTelegramClient();
    private final MutableClock clock = new MutableClock();
    private final StatusBoard board = new StatusBoard(telegram, new NoopRepository(), clock);
    private AccountSession session;

    @BeforeEach
    void existingStatus() {
        session = new AccountSession(1, CHAT, new AccountSession.Tokens("a", "r", null),
                new AccountSession.Board(STATUS_ID, null, null));
    }

    @Test
    @DisplayName("«message to edit not found» — статус создаётся заново")
    void recreatesWhenMissing() {
        telegram.editResults.add(FakeTelegramClient.error(400, "Bad Request: message to edit not found", null));

        board.render(session, "статус");

        assertThat(telegram.sent).singleElement().satisfies(s -> assertThat(s.text()).isEqualTo("статус"));
        assertThat(session.board().statusMessageId()).isEqualTo(telegram.sent.get(0).messageId());
    }

    @Test
    @DisplayName("429 — новое сообщение не создаётся, отрисовка молчит retry_after секунд")
    void respectsRetryAfter() {
        telegram.editResults.add(FakeTelegramClient.error(429, "Too Many Requests: retry after 20", 20));

        board.render(session, "статус 1");
        board.render(session, "статус 2");
        clock.advance(Duration.ofSeconds(19));
        board.render(session, "статус 3");

        assertThat(telegram.sent).isEmpty();
        assertThat(telegram.edits).hasSize(1);
        assertThat(session.board().statusMessageId()).isEqualTo(STATUS_ID);

        clock.advance(Duration.ofSeconds(2));
        board.render(session, "статус 4");

        assertThat(telegram.edits).hasSize(2);
        assertThat(telegram.sent).isEmpty();
    }

    @Test
    @DisplayName("бот заблокирован — ни дублей, ни запросов каждые 15 секунд")
    void blockedBotStaysQuiet() {
        telegram.editResults.add(FakeTelegramClient.error(403, "Forbidden: bot was blocked by the user", null));

        board.render(session, "статус 1");
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(15));
            board.render(session, "статус " + i);
        }

        assertThat(telegram.edits).hasSize(1);
        assertThat(telegram.sent).isEmpty();

        clock.advance(StatusBoard.BLOCKED_PAUSE);
        board.render(session, "статус после паузы");
        assertThat(telegram.edits).hasSize(2);
    }

    @Test
    @DisplayName("сетевой сбой правки — сообщение не пересоздаётся")
    void transportFailureKeepsMessage() {
        telegram.editResults.add(com.example.claudeusagemonitor.telegram.TelegramResult.transportFailure("timeout"));

        board.render(session, "статус");

        assertThat(telegram.sent).isEmpty();
        assertThat(session.board().statusMessageId()).isEqualTo(STATUS_ID);
    }

    @Test
    @DisplayName("неудачная отправка нового статуса повторяется с растущей паузой, а не каждые 15 секунд")
    void sendBackoff() {
        session = new AccountSession(1, CHAT, new AccountSession.Tokens("a", "r", null),
                new AccountSession.Board(null, null, null));
        telegram.sendResults.add(TelegramResult.transportFailure("timeout"));
        telegram.sendResults.add(TelegramResult.transportFailure("timeout"));

        board.render(session, "статус");
        clock.advance(Duration.ofSeconds(15));
        board.render(session, "статус");
        assertThat(telegram.sent).hasSize(1);

        clock.advance(StatusBoard.SEND_BACKOFF);
        board.render(session, "статус");
        assertThat(telegram.sent).hasSize(2);

        // Вторая неудача — пауза уже вдвое длиннее
        clock.advance(StatusBoard.SEND_BACKOFF.plusSeconds(1));
        board.render(session, "статус");
        assertThat(telegram.sent).hasSize(2);

        clock.advance(StatusBoard.SEND_BACKOFF);
        board.render(session, "статус");
        assertThat(telegram.sent).hasSize(3);
        assertThat(session.board().statusMessageId()).isNotNull();
    }

    @Test
    @DisplayName("/status снимает паузу: раз пользователь пишет, бот не заблокирован")
    void repostClearsPause() {
        telegram.editResults.add(FakeTelegramClient.error(403, "Forbidden: bot was blocked by the user", null));
        board.render(session, "статус");

        board.repost(session, "статус");

        assertThat(telegram.deletes).hasSize(1);
        assertThat(telegram.sent).hasSize(1);
        assertThat(session.board().isPaused(clock.instant())).isFalse();
    }

    @Test
    @DisplayName("отцепленная сессия в чат не пишет")
    void detachedSessionIsSilent() {
        session.detach();

        board.render(session, "статус");
        board.raise(session, StatusBoard.AlertKind.FAILURE, "алерт", "статус");

        assertThat(telegram.sent).isEmpty();
        assertThat(telegram.edits).isEmpty();
    }

    @Test
    @DisplayName("разбор ответа Bot API: retry_after, «нет сообщения», блокировка, message_id")
    void parsesTelegramErrors() {
        TelegramResult rateLimited = FakeTelegramClient.error(429, "Too Many Requests: retry after 5", 5);
        assertThat(rateLimited.isRateLimited()).isTrue();
        assertThat(rateLimited.retryAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(rateLimited.isMessageMissing()).isFalse();

        TelegramResult missing = FakeTelegramClient.error(400, "Bad Request: message to edit not found", null);
        assertThat(missing.isMessageMissing()).isTrue();
        assertThat(missing.isChatUnavailable()).isFalse();

        TelegramResult blocked = FakeTelegramClient.error(403, "Forbidden: bot was blocked by the user", null);
        assertThat(blocked.isChatUnavailable()).isTrue();
        assertThat(blocked.isMessageMissing()).isFalse();

        assertThat(FakeTelegramClient.error(400, "Bad Request: message is not modified", null).isNotModified())
                .isTrue();
        assertThat(FakeTelegramClient.ok(77).messageId()).isEqualTo(77L);
        assertThat(missing.messageId()).isNull();
    }

    /** Репозиторий-заглушка: юнит-тесту база не нужна. */
    private static final class NoopRepository extends AccountRepository {
        NoopRepository() {
            super(null, null);
        }

        @Override
        public void saveBoard(long id, Long statusMessageId, Long alertMessageId, StatusBoard.AlertKind alertKind) {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
