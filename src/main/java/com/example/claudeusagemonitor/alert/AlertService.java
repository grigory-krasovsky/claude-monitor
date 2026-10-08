package com.example.claudeusagemonitor.alert;

import com.example.claudeusagemonitor.account.Account;
import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.account.AccountSessions;
import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.telegram.StatusBoard;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import com.example.claudeusagemonitor.usage.WindowKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Опрашивает лимиты всех подключённых аккаунтов и ведёт их статусные сообщения.
 *
 * <p>Опрос и отрисовка разведены намеренно. За данными ходим раз в три минуты — это TTL
 * эндпоинта Anthropic, чаще нельзя. А статусное сообщение переписываем раз в пятнадцать
 * секунд из последнего среза: обратный отсчёт и шкала прошедшего времени при этом идут
 * непрерывно, хотя проценты токенов обновляются реже. У планировщика два потока
 * ({@code spring.task.scheduling.pool.size}), иначе медленный опрос держал бы и отрисовку.
 *
 * <p>Аккаунты опрашиваются по очереди, но каждый в своём try/catch и под своими
 * блокировками: сбой или таймаут одного (не дольше таймаутов HTTP) другого не ломает.
 *
 * <p>Каждый порог срабатывает не более одного раза за окно: состояние привязано к времени
 * сброса, и когда Anthropic выдаёт новое {@code resets_at}, счётчик обнуляется, а алерт
 * снимается.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Сколько опросов подряд должны провалиться, прежде чем жаловаться в чат. */
    private static final int FAILURES_BEFORE_ALERT = 2;

    private final MonitorProperties properties;
    private final UsageClient usageClient;
    private final TelegramClient telegramClient;
    private final StatusBoard board;
    private final MessageFormatter formatter;
    private final AccountSessions sessions;
    private final AccountRepository repository;

    public AlertService(MonitorProperties properties, UsageClient usageClient, TelegramClient telegramClient,
                        StatusBoard board, MessageFormatter formatter, AccountSessions sessions,
                        AccountRepository repository) {
        this.properties = properties;
        this.usageClient = usageClient;
        this.telegramClient = telegramClient;
        this.board = board;
        this.formatter = formatter;
        this.sessions = sessions;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        poll();
        if (telegramClient.isConfigured()) {
            notifyUnreadable();
        }
    }

    /**
     * Сообщает владельцам аккаунтов, чьи токены в базе не расшифровываются (сменили ключ
     * шифрования, запись повреждена), что нужен новый /token. Такие аккаунты в опрос не
     * попадают, и без этого человек видел бы лишь застывший статус. Застывший статус и
     * алерт убираются из чата — их больше некому обновлять.
     *
     * <p>Вызывается один раз после старта: нечитаемыми токены становятся только со сменой
     * ключа, а она требует рестарта.
     */
    public void notifyUnreadable() {
        List<Account> unreadable;
        try {
            unreadable = repository.findUnreadable();
        } catch (RuntimeException e) {
            log.error("Не удалось проверить аккаунты с нечитаемыми токенами: {}", e.toString());
            return;
        }
        for (Account account : unreadable) {
            try {
                sessions.locked(account.chatId(), () -> {
                    // Пока шёл список, пользователь мог уже прислать /token — перепроверяем строку
                    Optional<Account> current = repository.findByChatId(account.chatId())
                            .filter(a -> a.tokensUnreadable() && a.isApproved());
                    current.ifPresent(a -> {
                        stop(a);
                        telegramClient.sendMessage(a.chatId(), MessageFormatter.TOKENS_UNREADABLE);
                    });
                });
            } catch (RuntimeException e) {
                log.error("Не удалось уведомить {} о нечитаемых токенах: {}", account, e.toString());
            }
        }
    }

    @Scheduled(initialDelayString = "${monitor.poll-interval}", fixedDelayString = "${monitor.poll-interval}")
    public void poll() {
        if (!telegramClient.isConfigured()) {
            return;
        }
        try {
            sessions.sync();
        } catch (RuntimeException e) {
            // База недоступна — опрашиваем тех, кто уже в памяти
            log.error("Не удалось прочитать список аккаунтов: {}", e.toString());
        }
        for (AccountSession session : sessions.all()) {
            try {
                poll(session);
            } catch (RuntimeException e) {
                log.error("Опрос {} упал: {}", session, e.toString());
            }
        }
    }

    /** Опрос одного аккаунта. Если по нему уже идёт опрос, второй не запускается. */
    public void poll(AccountSession session) {
        if (!session.pollLock().tryLock()) {
            return;
        }
        try {
            UsageSnapshot snapshot;
            try {
                // Сетевой запрос — вне монитора сессии, чтобы отрисовка и команды его не ждали
                snapshot = usageClient.fetch(session);
            } catch (RuntimeException e) {
                onFailure(session, e.getMessage());
                return;
            }
            apply(session, snapshot);
        } finally {
            session.pollLock().unlock();
        }
    }

    /** Перерисовка статусов между опросами: двигает обратный отсчёт и шкалу времени. */
    @Scheduled(initialDelayString = "${monitor.render-interval}", fixedDelayString = "${monitor.render-interval}")
    public void render() {
        if (!telegramClient.isConfigured()) {
            return;
        }
        for (AccountSession session : sessions.all()) {
            UsageSnapshot snapshot = session.lastSnapshot();
            if (snapshot == null) {
                continue;
            }
            try {
                board.render(session, formatter.status(snapshot));
            } catch (RuntimeException e) {
                log.error("Отрисовка {} упала: {}", session, e.toString());
            }
        }
    }

    /**
     * Запускает мониторинг только что подключённого аккаунта. Срез от пробного запроса
     * идёт в дело как первый опрос: второй запрос через секунду после первого лишь
     * приблизил бы rate limit, не дав новых цифр.
     *
     * @param firstSnapshot срез пробного запроса; {@code null}, если проба не прошла по
     *                      временной причине — тогда цифры принесёт ближайший плановый опрос
     */
    public void start(Account account, UsageSnapshot firstSnapshot) {
        AccountSession session = sessions.locked(account.chatId(), () -> {
            // Пока пользователю уходил ответ, плановый sync мог поднять сессию из базы и успеть
            // создать ею статус. Новая сессия прочитала строку раньше и может о нём не знать —
            // без этой уборки он остался бы в чате сиротой, которую никто не правит
            sessions.detach(account.chatId()).ifPresent(board::dropAll);
            return sessions.replace(account);
        });
        if (firstSnapshot != null) {
            record(session, firstSnapshot);
        }
        // Статус — сразу последним в чате, под сообщением «подключено»
        board.repost(session, statusText(session));
    }

    /**
     * Снимает аккаунт с мониторинга и убирает из чата его сообщения. Если сессии в памяти
     * нет (аккаунт не отслеживался — например, без токенов), сообщения берутся из строки базы.
     * Вызывается под {@link AccountSessions#locked} вместе с изменением базы.
     */
    public void stop(Account account) {
        AccountSession session = sessions.detach(account.chatId()).orElseGet(() -> {
            AccountSession transientSession = AccountSession.of(account);
            transientSession.detach();
            return transientSession;
        });
        board.dropAll(session);
    }

    /**
     * Пересоздаёт статус аккаунта последним в чате — реакция на /status.
     *
     * @return {@code false}, если аккаунт не отслеживается
     */
    public boolean repostStatus(long chatId) {
        Optional<AccountSession> session = sessions.find(chatId);
        session.ifPresent(s -> board.repost(s, statusText(s)));
        return session.isPresent();
    }

    /** Текст статуса из последнего среза. Своего запроса к API не делает. */
    private String statusText(AccountSession session) {
        UsageSnapshot snapshot = session.lastSnapshot();
        return snapshot == null
                ? "⏳ Данных пока нет — первый опрос ещё не прошёл."
                : formatter.status(snapshot);
    }

    private void apply(AccountSession session, UsageSnapshot snapshot) {
        List<String> triggered = record(session, snapshot);
        if (triggered.isEmpty()) {
            board.render(session, formatter.status(snapshot));
        } else {
            // Оба окна могут пробить порог одним опросом — тогда это один алерт, а не два
            board.raise(session, StatusBoard.AlertKind.THRESHOLD,
                    String.join("\n\n", triggered), formatter.status(snapshot));
        }
    }

    /** Запоминает срез и пересчитывает пороги. Возвращает тексты сработавших алертов. */
    private List<String> record(AccountSession session, UsageSnapshot snapshot) {
        List<String> triggered = new ArrayList<>();
        synchronized (session) {
            session.setLastSnapshot(snapshot);
            session.resetFailures();
            collect(session, WindowKind.FIVE_HOUR, snapshot.fiveHour(), triggered);
            collect(session, WindowKind.SEVEN_DAY, snapshot.sevenDay(), triggered);
        }
        board.clear(session, StatusBoard.AlertKind.FAILURE);
        return triggered;
    }

    private void collect(AccountSession session, WindowKind kind, LimitWindow window, List<String> triggered) {
        if (window == null) {
            return;
        }
        AccountSession.WindowState state = session.windows().get(kind);
        if (state == null || !state.resetKey().equals(window.resetKey())) {
            boolean windowWasUsed = state != null && state.maxNotified() > 0;
            session.windows().put(kind, state = new AccountSession.WindowState(window.resetKey()));
            if (windowWasUsed) {
                log.info("{}: {} сброшено, новое окно до {}", session, kind.title(), window.resetsAt());
                board.clear(session, StatusBoard.AlertKind.THRESHOLD);
            }
        }

        int reached = highestThresholdReached(properties, window.percent());
        if (reached > state.maxNotified()) {
            log.info("{}: {} {}% — порог {}%", session, kind.title(), Math.round(window.percent()), reached);
//            triggered.add(formatter.thresholdAlert(kind, window, reached));
            state.setMaxNotified(reached);
        }
    }

    /** Наибольший настроенный порог, который уже достигнут; 0, если ни один. */
    public static int highestThresholdReached(MonitorProperties properties, double percent) {
        return properties.getThresholds().stream()
                .filter(threshold -> percent >= threshold)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private void onFailure(AccountSession session, String message) {
        log.error("Опрос лимитов {} не удался: {}", session, message);
        boolean alertShown;
        int failures;
        synchronized (session) {
            failures = session.incrementFailures();
            alertShown = session.board().alertKind() == StatusBoard.AlertKind.FAILURE;
        }
        // Одиночный сетевой сбой не повод будить пользователя — ждём подтверждения. Дальше
        // поднимаем, пока алерт не встанет: raise() молчит под паузой Telegram (429, блокировка),
        // и проверка «ровно второй сбой» теряла бы алерт насовсем
        if (failures < FAILURES_BEFORE_ALERT || alertShown) {
            return;
        }
        String alert = "⚠️ <b>Монитор не может получить данные</b>\n" + MessageFormatter.escape(message);
        UsageSnapshot snapshot = session.lastSnapshot();
        board.raise(session, StatusBoard.AlertKind.FAILURE, alert,
                snapshot == null ? "Данных пока нет." : formatter.status(snapshot));
    }
}
