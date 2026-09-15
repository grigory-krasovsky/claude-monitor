package com.example.claudeusagemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Разбор ответа Anthropic и выбор порога алерта. */
class MonitorLogicTests {

    private static final String SAMPLE = """
            {"five_hour": {"utilization": 48.0,
                           "resets_at": "2026-09-15T17:40:00.767450+00:00",
                           "locked_reason": null},
             "seven_day": null}""";

    @Test
    @DisplayName("разбирает окно из ответа API")
    void parsesWindow() {
        var json = new ObjectMapper().readTree(SAMPLE);

        LimitWindow fiveHour = UsageClient.window(json.get("five_hour"));

        assertThat(fiveHour.percent()).isEqualTo(48.0);
        assertThat(fiveHour.resetsAt()).isEqualTo(Instant.parse("2026-09-15T17:40:00.767450Z"));
        assertThat(fiveHour.lockedReason()).isNull();
        assertThat(UsageClient.window(json.get("seven_day"))).isNull();
    }

    @Test
    @DisplayName("ключ окна не меняется от дрожания долей секунды в resets_at")
    void resetKeyIgnoresSubSecondJitter() {
        var first = new LimitWindow(48, Instant.parse("2026-09-15T17:40:00.767450Z"), null);
        var second = new LimitWindow(59, Instant.parse("2026-09-15T17:40:00.771003Z"), null);
        var nextWindow = new LimitWindow(2, Instant.parse("2026-09-15T22:40:00.123456Z"), null);

        assertThat(first.resetKey()).isEqualTo(second.resetKey());
        assertThat(first.resetKey()).isNotEqualTo(nextWindow.resetKey());
    }

    @Test
    @DisplayName("рисует обе шкалы: токены против прошедшего времени окна")
    void rendersCompetingBars() {
        var formatter = new MessageFormatter(new MonitorProperties());
        // До сброса пятичасового окна час, значит прошло 80% времени — ровно столько же, сколько токенов
        var window = new LimitWindow(80, Instant.now().plus(Duration.ofHours(1)), null);

        String text = formatter.status(new UsageSnapshot(window, null, Instant.now()));

        assertThat(text)
                .contains("токены 80%")
                .contains("время 80%")
                .contains("🟨")
                .contains("🟦")
                .contains("вровень со временем");
    }

    @Test
    @DisplayName("выбирает наибольший достигнутый порог")
    void picksHighestReachedThreshold() {
        AlertService service = new AlertService(new MonitorProperties(), null, null, null, null);

        assertThat(service.highestThresholdReached(49)).isZero();
        assertThat(service.highestThresholdReached(50)).isEqualTo(50);
        assertThat(service.highestThresholdReached(91.4)).isEqualTo(90);
        assertThat(service.highestThresholdReached(100)).isEqualTo(95);
    }
}
