package com.example.claudeusagemonitor.alert;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.telegram.StatusBoard;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.TokenProvider;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import com.example.claudeusagemonitor.usage.WindowKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Опрашивает лимиты и ведёт статусное сообщение в чате.
 *
 * <p>Опрос и отрисовка разведены намеренно. За данными ходим раз в три минуты — это TTL
 * эндпоинта Anthropic, чаще нельзя. А статусное сообщение переписываем раз в пятнадцать
 * секунд из последнего среза: обратный отсчёт и шкала прошедшего времени при этом идут
 * непрерывно, хотя проценты токенов обновляются реже.
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
    private final TokenProvider tokenProvider;

    /** Состояние алертов по каждому окну: ключ сброса и максимальный отправленный порог. */
    private final Map<WindowKind, WindowState> states = new EnumMap<>(WindowKind.class);

    private volatile UsageSnapshot lastSnapshot;
    private int consecutiveFailures;

    public AlertService(MonitorProperties properties, UsageClient usageClient, TelegramClient telegramClient,
                        StatusBoard board, MessageFormatter formatter, TokenProvider tokenProvider) {
        this.properties = properties;
        this.usageClient = usageClient;
        this.telegramClient = telegramClient;
        this.board = board;
        this.formatter = formatter;
        this.tokenProvider = tokenProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        poll();
    }

    @Scheduled(initialDelayString = "${monitor.poll-interval}", fixedDelayString = "${monitor.poll-interval}")
    public void poll() {
        if (!ready()) {
            return;
        }
        UsageSnapshot snapshot;
        try {
            snapshot = usageClient.fetch();
        } catch (RuntimeException e) {
            onFailure(e.getMessage());
            return;
        }
        lastSnapshot = snapshot;
        consecutiveFailures = 0;
        board.clear(StatusBoard.AlertKind.FAILURE);

        List<String> triggered = new ArrayList<>();
        collect(WindowKind.FIVE_HOUR, snapshot.fiveHour(), triggered);
        collect(WindowKind.SEVEN_DAY, snapshot.sevenDay(), triggered);

        if (triggered.isEmpty()) {
            board.render(formatter.status(snapshot));
        } else {
            // Оба окна могут пробить порог одним опросом — тогда это один алерт, а не два
            board.raise(StatusBoard.AlertKind.THRESHOLD,
                    String.join("\n\n", triggered), formatter.status(snapshot));
        }
    }

    /** Перерисовка статуса между опросами: двигает обратный отсчёт и шкалу времени. */
    @Scheduled(initialDelayString = "${monitor.render-interval}", fixedDelayString = "${monitor.render-interval}")
    public void render() {
        UsageSnapshot snapshot = lastSnapshot;
        if (ready() && snapshot != null) {
            board.render(formatter.status(snapshot));
        }
    }

    /** Пересоздаёт статусное сообщение последним в чате — реакция на /status. */
    public void repostStatus() {
        board.repost(statusText());
    }

    /** Текст статуса из последнего среза. Своего запроса к API не делает. */
    public String statusText() {
        UsageSnapshot snapshot = lastSnapshot;
        return snapshot == null
                ? "⏳ Данных пока нет — первый опрос ещё не прошёл."
                : formatter.status(snapshot);
    }

    private void collect(WindowKind kind, LimitWindow window, List<String> triggered) {
        if (window == null) {
            return;
        }
        WindowState state = states.get(kind);
        if (state == null || !state.resetKey.equals(window.resetKey())) {
            boolean windowWasUsed = state != null && state.maxNotified > 0;
            states.put(kind, state = new WindowState(window.resetKey()));
            if (windowWasUsed) {
                log.info("{} сброшено, новое окно до {}", kind.title(), window.resetsAt());
                board.clear(StatusBoard.AlertKind.THRESHOLD);
            }
        }

        int reached = highestThresholdReached(window.percent());
        if (reached > state.maxNotified) {
            log.info("{}: {}% — порог {}%", kind.title(), Math.round(window.percent()), reached);
            triggered.add(formatter.thresholdAlert(kind, window, reached));
            state.maxNotified = reached;
        }
    }

    /** Наибольший настроенный порог, который уже достигнут; 0, если ни один. */
    public int highestThresholdReached(double percent) {
        return properties.getThresholds().stream()
                .filter(threshold -> percent >= threshold)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private void onFailure(String message) {
        log.error("Опрос лимитов не удался: {}", message);
        consecutiveFailures++;
        // Одиночный сетевой сбой не повод будить пользователя — ждём подтверждения
        if (consecutiveFailures != FAILURES_BEFORE_ALERT) {
            return;
        }
        String alert = "⚠️ <b>Монитор не может получить данные</b>\n" + message;
        UsageSnapshot snapshot = lastSnapshot;
        if (snapshot == null) {
            board.raise(StatusBoard.AlertKind.FAILURE, alert, "Данных пока нет.");
        } else {
            board.raise(StatusBoard.AlertKind.FAILURE, alert, formatter.status(snapshot));
        }
    }

    private boolean ready() {
        return tokenProvider.isConfigured() && telegramClient.isConfigured();
    }

    /** Изменяемое состояние одного окна. */
    private static final class WindowState {
        private final String resetKey;
        private int maxNotified;

        private WindowState(String resetKey) {
            this.resetKey = resetKey;
        }
    }
}
