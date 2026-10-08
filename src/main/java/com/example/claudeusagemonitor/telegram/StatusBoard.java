package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Держит в чате аккаунта ровно два сообщения бота: живой статус и, при необходимости, алерт.
 *
 * <p>Статус переписывается на месте вместо отправки нового сообщения и всегда остаётся
 * последним в чате: когда появляется алерт, статус удаляется и создаётся заново уже
 * под ним. Предыдущий алерт при этом тоже удаляется — так пороги дают уведомление,
 * но не копятся лентой.
 *
 * <p>Новое статусное сообщение создаётся только тогда, когда Telegram прямо говорит,
 * что прежнего нет. На 429 бот замолкает на {@code retry_after}, на «bot was blocked» —
 * на час, а неудачная отправка нового статуса повторяется с растущей паузой: раньше
 * любой отказ правки означал новое сообщение, и под rate limit в чате копились дубли.
 *
 * <p>Состояние лежит в {@link AccountSession.Board}, все методы работают под монитором
 * сессии: отрисовка идёт из планировщика, а команды — из потока long polling.
 */
@Component
public class StatusBoard {

    private static final Logger log = LoggerFactory.getLogger(StatusBoard.class);

    /** Сколько молчать, если Telegram на 429 не сообщил retry_after. */
    static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(30);

    /** Пользователь заблокировал бота: проверяем раз в час, не передумал ли. */
    static final Duration BLOCKED_PAUSE = Duration.ofHours(1);

    /** Пауза после первой неудачной отправки статуса; дальше удваивается до {@link #MAX_SEND_BACKOFF}. */
    static final Duration SEND_BACKOFF = Duration.ofSeconds(30);
    static final Duration MAX_SEND_BACKOFF = Duration.ofMinutes(10);

    private final TelegramClient telegramClient;
    private final AccountRepository repository;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public StatusBoard(TelegramClient telegramClient, AccountRepository repository) {
        this.telegramClient = telegramClient;
        this.repository = repository;
    }

    /** Для тестов: управляемые часы, чтобы проверять паузы без ожидания. */
    StatusBoard(TelegramClient telegramClient, AccountRepository repository, Clock clock) {
        this(telegramClient, repository);
        this.clock = clock;
    }

    /** Повод для алерта. Нужен, чтобы восстановление после сбоя не стирало алерт о пороге. */
    public enum AlertKind {
        THRESHOLD,
        FAILURE
    }

    /** Обновляет статус на месте, создавая сообщение при первом вызове. */
    public void render(AccountSession session, String statusText) {
        synchronized (session) {
            AccountSession.Board board = session.board();
            if (session.isDetached() || board.isPaused(now())) {
                return;
            }
            if (board.statusMessageId() == null) {
                createStatus(session, statusText);
                return;
            }
            if (statusText.equals(board.renderedStatus())) {
                return;
            }
            TelegramResult result = telegramClient.editMessage(session.chatId(), board.statusMessageId(), statusText);
            if (result.ok() || result.isNotModified()) {
                board.setRenderedStatus(statusText);
                board.markDelivered();
            } else if (result.isMessageMissing()) {
                log.info("Статусное сообщение {} пропало ({}), создаю заново", session, result.description());
                board.setStatusMessageId(null);
                createStatus(session, statusText);
            } else {
                // Сообщение, скорее всего, на месте — новое не создаём, иначе получим дубль
                pauseOnFailure(session, "editMessageText", result, false);
            }
        }
    }

    /**
     * Поднимает алерт: убирает предыдущий алерт и статус, публикует новый алерт
     * и сразу под ним — свежий статус.
     */
    public void raise(AccountSession session, AlertKind kind, String alertText, String statusText) {
        synchronized (session) {
            AccountSession.Board board = session.board();
            if (session.isDetached() || board.isPaused(now())) {
                // Под 429 или блокировкой отправка всё равно не пройдёт, а удалив статус,
                // мы оставили бы чат совсем без него
                return;
            }
            dropStatus(session);
            dropAlert(session);

            TelegramResult result = telegramClient.sendMessage(session.chatId(), alertText);
            board.setAlert(result.messageId(), kind);
            if (!result.ok()) {
                pauseOnFailure(session, "sendMessage (алерт)", result, false);
            }
            createStatus(session, statusText);
        }
    }

    /** Убирает алерт, если он висит и относится к указанному поводу. */
    public void clear(AccountSession session, AlertKind kind) {
        synchronized (session) {
            AccountSession.Board board = session.board();
            if (session.isDetached() || board.alertMessageId() == null || board.alertKind() != kind) {
                return;
            }
            log.info("Убираю алерт {} ({})", session, kind);
            dropAlert(session);
            save(session);
        }
    }

    /**
     * Пересоздаёт статус, чтобы он снова оказался последним сообщением в чате.
     * Это ответ на команду пользователя, поэтому паузы снимаются: раз он пишет боту,
     * значит, не заблокировал его.
     */
    public void repost(AccountSession session, String statusText) {
        synchronized (session) {
            if (session.isDetached()) {
                return;
            }
            session.board().markDelivered();
            dropStatus(session);
            createStatus(session, statusText);
        }
    }

    /**
     * Удаляет из чата оба сообщения бота и забывает их. Работает и на отцепленной
     * сессии — ради этого её и отцепляют: /forget и /revoke убирают статус за собой.
     */
    public void dropAll(AccountSession session) {
        synchronized (session) {
            dropStatus(session);
            dropAlert(session);
            save(session);
        }
    }

    private void createStatus(AccountSession session, String statusText) {
        AccountSession.Board board = session.board();
        TelegramResult result = telegramClient.sendMessage(session.chatId(), statusText);
        board.setStatusMessageId(result.messageId());
        if (result.ok()) {
            board.setRenderedStatus(statusText);
            board.markDelivered();
        } else {
            board.setRenderedStatus(null);
            pauseOnFailure(session, "sendMessage (статус)", result, true);
        }
        save(session);
    }

    /**
     * Ставит паузу отрисовки по причине отказа.
     *
     * @param sending отказ при создании нового сообщения: тогда даже на непонятную ошибку
     *                нужна пауза, иначе попытка повторялась бы каждые 15 секунд
     */
    private void pauseOnFailure(AccountSession session, String method, TelegramResult result, boolean sending) {
        AccountSession.Board board = session.board();
        Duration pause;
        if (result.isRateLimited()) {
            pause = result.retryAfter() == null ? DEFAULT_RETRY_AFTER : result.retryAfter();
            log.warn("Telegram просит подождать {} с ({} для {})", pause.toSeconds(), method, session);
        } else if (result.isChatUnavailable()) {
            pause = BLOCKED_PAUSE;
            log.warn("Чат {} недоступен ({}): {}, молчу до {}", session.chatId(), method, result.description(),
                    now().plus(pause));
        } else if (sending) {
            int failures = board.incrementSendFailures();
            long factor = 1L << Math.min(failures - 1, 10);
            pause = SEND_BACKOFF.multipliedBy(factor);
            if (pause.compareTo(MAX_SEND_BACKOFF) > 0) {
                pause = MAX_SEND_BACKOFF;
            }
            log.warn("{} для {} не удался ({}), повтор через {} с", method, session, result.description(),
                    pause.toSeconds());
        } else {
            log.warn("{} для {} отклонён: {}", method, session, result.description());
            return;
        }
        board.pauseUntil(now().plus(pause));
    }

    private void dropStatus(AccountSession session) {
        AccountSession.Board board = session.board();
        if (board.statusMessageId() != null) {
            telegramClient.deleteMessage(session.chatId(), board.statusMessageId());
            board.setStatusMessageId(null);
            board.setRenderedStatus(null);
        }
    }

    private void dropAlert(AccountSession session) {
        AccountSession.Board board = session.board();
        if (board.alertMessageId() != null) {
            telegramClient.deleteMessage(session.chatId(), board.alertMessageId());
            board.setAlert(null, null);
        }
    }

    /** Id сообщений в базе: по ним бот после рестарта продолжает править то же сообщение. */
    private void save(AccountSession session) {
        if (session.isDetached()) {
            // Отцепленная сессия больше не владеет строкой: её судьбу решает команда
            return;
        }
        AccountSession.Board board = session.board();
        try {
            repository.saveBoard(session.accountId(), board.statusMessageId(), board.alertMessageId(),
                    board.alertKind());
        } catch (DataAccessException e) {
            log.error("Не удалось сохранить id сообщений {}: {}", session, e.toString());
        }
    }

    private Instant now() {
        return clock.instant();
    }
}
