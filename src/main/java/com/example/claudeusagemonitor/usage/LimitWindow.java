package com.example.claudeusagemonitor.usage;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Одно окно лимита (пятичасовое или недельное).
 *
 * @param percent    утилизация в процентах, 0..100
 * @param resetsAt   момент сброса окна; {@code null}, если API его не вернул
 * @param lockedReason причина блокировки, если лимит исчерпан
 */
public record LimitWindow(double percent, Instant resetsAt, String lockedReason) {

    /**
     * Ключ окна для хранения состояния алертов: окно считается новым при смене времени сброса.
     *
     * <p>Время округляется до минут намеренно. API отдаёт {@code resets_at} с долями
     * секунды, которые пересчитываются на каждый запрос (17:40:00.767450, затем
     * 17:40:00.771003 и так далее). Без округления каждое обращение выглядело бы как
     * новое окно: состояние алертов обнулялось бы, пороги слались повторно, а следом
     * приходило ложное «окно сброшено».
     */
    public String resetKey() {
        return resetsAt == null ? "unknown" : resetsAt.truncatedTo(ChronoUnit.MINUTES).toString();
    }
}
