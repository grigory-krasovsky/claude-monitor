package com.example.claudeusagemonitor.usage;

import java.time.Instant;

/**
 * Срез лимитов аккаунта на момент {@code capturedAt}.
 *
 * @param fiveHour пятичасовое окно — основная цель мониторинга
 * @param sevenDay недельное окно; может быть {@code null}
 */
public record UsageSnapshot(LimitWindow fiveHour, LimitWindow sevenDay, Instant capturedAt) {
}
