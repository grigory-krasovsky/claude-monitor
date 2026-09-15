package com.example.claudeusagemonitor.alert;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.telegram.TelegramClient;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.TokenProvider;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import com.example.claudeusagemonitor.usage.WindowKind;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Периодически опрашивает лимиты и шлёт алерты при пересечении порогов.
 *
 * <p>Каждый порог срабатывает не более одного раза за окно: состояние привязано
 * к времени сброса, и когда Anthropic выдаёт новое {@code resets_at}, счётчик
 * обнуляется. Интервал опроса по умолчанию — 3 минуты, это TTL самого эндпоинта;
 * чаще ходить бессмысленно и чревато rate limit.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final MonitorProperties properties;
    private final UsageClient usageClient;
    private final TelegramClient telegramClient;
    private final MessageFormatter formatter;
    private final TokenProvider tokenProvider;

    /** Состояние алертов по каждому окну: ключ сброса и максимальный отправленный порог. */
    private final Map<WindowKind, WindowState> states = new EnumMap<>(WindowKind.class);

    /** Чтобы не спамить в чат одной и той же ошибкой каждые три минуты. */
    private String lastReportedError;

    public AlertService(MonitorProperties properties, UsageClient usageClient, TelegramClient telegramClient,
                        MessageFormatter formatter, TokenProvider tokenProvider) {
        this.properties = properties;
        this.usageClient = usageClient;
        this.telegramClient = telegramClient;
        this.formatter = formatter;
        this.tokenProvider = tokenProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.isNotifyOnStart() || !ready()) {
            return;
        }
        try {
            telegramClient.sendToConfiguredChat("🚀 <b>Монитор запущен</b>\n\n"
                    + formatter.status(usageClient.fetch()));
        } catch (RuntimeException e) {
            log.error("Стартовое сообщение не отправлено: {}", e.toString());
        }
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
            reportError(e.getMessage());
            return;
        }
        lastReportedError = null;

        check(WindowKind.FIVE_HOUR, snapshot.fiveHour(), properties.isNotifyOnReset());
        check(WindowKind.SEVEN_DAY, snapshot.sevenDay(), false);
    }

    private void check(WindowKind kind, LimitWindow window, boolean notifyReset) {
        if (window == null) {
            return;
        }
        WindowState state = states.get(kind);
        if (state == null || !state.resetKey.equals(window.resetKey())) {
            boolean windowWasUsed = state != null && state.maxNotified > 0;
            state = new WindowState(window.resetKey());
            states.put(kind, state);
            if (windowWasUsed && notifyReset) {
                telegramClient.sendToConfiguredChat(formatter.windowReset(window));
            }
        }

        int reached = highestThresholdReached(window.percent());
        if (reached > state.maxNotified) {
            log.info("{}: {}% — порог {}%", kind.title(), Math.round(window.percent()), reached);
            telegramClient.sendToConfiguredChat(formatter.thresholdAlert(kind, window, reached));
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

    private void reportError(String message) {
        log.error("Опрос лимитов не удался: {}", message);
        if (!message.equals(lastReportedError)) {
            lastReportedError = message;
            telegramClient.sendToConfiguredChat("⚠️ <b>Монитор не может получить данные</b>\n" + message);
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
