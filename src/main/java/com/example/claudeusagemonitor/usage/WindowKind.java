package com.example.claudeusagemonitor.usage;

import java.time.Duration;

/**
 * Вид окна лимита. Длительность нужна, чтобы посчитать, сколько времени окна уже
 * прошло: API отдаёт только момент сброса, а начало окна — это сброс минус длительность.
 */
public enum WindowKind {

    FIVE_HOUR("Пятичасовое окно", Duration.ofHours(5)),
    SEVEN_DAY("Недельный лимит", Duration.ofDays(7));

    private final String title;
    private final Duration duration;

    WindowKind(String title, Duration duration) {
        this.title = title;
        this.duration = duration;
    }

    public String title() {
        return title;
    }

    public Duration duration() {
        return duration;
    }
}
