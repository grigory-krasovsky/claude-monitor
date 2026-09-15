package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Сборка текстов сообщений для бота. */
@Component
public class MessageFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final MonitorProperties properties;

    public MessageFormatter(MonitorProperties properties) {
        this.properties = properties;
    }

    /** Полный статус по обоим окнам — ответ на /status. */
    public String status(UsageSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append(line("Пятичасовое окно", snapshot.fiveHour(), true));
        if (snapshot.sevenDay() != null) {
            sb.append('\n').append(line("Недельный лимит", snapshot.sevenDay(), false));
        }
        return sb.toString();
    }

    /** Короткое уведомление о пересечении порога. */
    public String thresholdAlert(String windowTitle, LimitWindow window, int threshold) {
        return "%s <b>%s: %.0f%%</b>\nПройден порог %d%%\n%s\n%s".formatted(
                icon(window.percent()), windowTitle, window.percent(), threshold,
                bar(window.percent()), resetLine(window, true));
    }

    /** Уведомление о старте нового пятичасового окна. */
    public String windowReset(LimitWindow window) {
        return "♻️ <b>Пятичасовое окно сброшено</b>\nЛимит снова доступен.\n" + resetLine(window, true);
    }

    private String line(String title, LimitWindow window, boolean withCountdown) {
        if (window == null) {
            return "<b>%s</b>: нет данных".formatted(title);
        }
        String locked = window.lockedReason() == null ? "" : "\n🚫 Заблокировано: " + window.lockedReason();
        return "%s <b>%s</b>: %.0f%%\n%s\n%s%s".formatted(
                icon(window.percent()), title, window.percent(),
                bar(window.percent()), resetLine(window, withCountdown), locked);
    }

    private String resetLine(LimitWindow window, boolean withCountdown) {
        if (window.resetsAt() == null) {
            return "Время сброса неизвестно";
        }
        var local = window.resetsAt().atZone(properties.getTimezone());
        Duration left = Duration.between(Instant.now(), window.resetsAt());
        boolean sameDay = local.toLocalDate().equals(Instant.now().atZone(properties.getTimezone()).toLocalDate());
        String at = local.format(sameDay ? TIME : DATE_TIME);
        if (!withCountdown || left.isNegative()) {
            return "Сброс в " + at;
        }
        return "Сброс в %s (через %s)".formatted(at, humanize(left));
    }

    private static String humanize(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        if (hours > 0) {
            return "%d ч %d мин".formatted(hours, minutes);
        }
        return "%d мин".formatted(Math.max(1, minutes));
    }

    /** Текстовый прогресс-бар из 10 делений. */
    private static String bar(double percent) {
        int filled = (int) Math.round(percent / 10);
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    private static String icon(double percent) {
        if (percent >= 90) {
            return "🔴";
        }
        return percent >= 75 ? "🟠" : "🟢";
    }
}
