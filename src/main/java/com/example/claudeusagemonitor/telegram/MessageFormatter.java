package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import com.example.claudeusagemonitor.usage.WindowKind;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * Сборка текстов сообщений для бота.
 *
 * <p>Шкалы рисуются цветными квадратами-эмодзи: в Telegram нет способа покрасить
 * текст, поэтому цвет несёт сам символ. Шкал две и они соревнуются — расход токенов
 * против доли прошедшего времени окна. Если токены обгоняют время, лимит кончится
 * раньше, чем окно сбросится.
 */
@Component
public class MessageFormatter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private static final int BAR_CELLS = 10;
    private static final String EMPTY_CELL = "⬜";
    private static final String TIME_CELL = "🟦";

    /** Разрыв в процентных пунктах, начиная с которого темп считается отклонившимся. */
    private static final double PACE_TOLERANCE = 10;

    /**
     * Как получить токен для /token. Логин отдельный (свой CLAUDE_CONFIG_DIR) потому, что
     * бот ротирует refresh token при каждом обмене: копия токена основного CLI разлогинила
     * бы его. А {@code claude setup-token} не подходит вовсе — его токену эндпоинт лимитов
     * отвечает 403 «OAuth token does not meet scope requirement user:profile».
     */
    public static final String TOKEN_HOWTO = """
            <b>Как подключить аккаунт Claude</b>
            1. На своём компьютере сделайте отдельный логин — так бот не разлогинит ваш основной Claude Code:
            <code>CLAUDE_CONFIG_DIR=~/.claude-monitor claude login</code>
            2. Возьмите <code>claudeAiOauth.refreshToken</code> (sk-ant-ort01-…) из файла <code>~/.claude-monitor/.credentials.json</code>.
            3. Пришлите сюда: <code>/token sk-ant-ort01-…</code>
            Сообщение с токеном бот сразу удалит. Токен из <code>claude setup-token</code> не подойдёт: у него нет доступа к данным о лимитах.""";

    /**
     * Токены аккаунта в базе есть, но не расшифровываются: сменился ключ шифрования или
     * запись повреждена. Мониторинг такого аккаунта не запускается, пока не придёт новый /token.
     */
    public static final String TOKENS_UNREADABLE = """
            ⚠️ <b>Сохранённые токены не читаются</b> — на сервере сменился ключ шифрования или запись \
            в базе повреждена. Мониторинг остановлен; пришлите /token заново.

            """ + TOKEN_HOWTO;

    private final MonitorProperties properties;

    public MessageFormatter(MonitorProperties properties) {
        this.properties = properties;
    }

    /** Полный статус по обоим окнам. Это же сообщение переписывается на месте раз в 15 секунд. */
    public String status(UsageSnapshot snapshot) {
        StringBuilder sb = new StringBuilder(block(WindowKind.FIVE_HOUR, snapshot.fiveHour()));
        if (snapshot.sevenDay() != null) {
            sb.append("\n\n").append(block(WindowKind.SEVEN_DAY, snapshot.sevenDay()));
        }
        // Шкала времени идёт непрерывно, а проценты токенов тянутся раз в три минуты —
        // возраст данных показываем явно, чтобы разница не выглядела расхождением.
        sb.append("\n\n<i>данные ").append(age(snapshot.capturedAt())).append("</i>");
        return sb.toString();
    }

    /**
     * Экранирует текст для parse_mode=HTML. Имена пользователей и ответы API приходят
     * извне: один символ {@code <} в имени — и Telegram отклонит всё сообщение.
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String age(Instant capturedAt) {
        long seconds = Math.max(0, Duration.between(capturedAt, Instant.now()).toSeconds());
        if (seconds < 60) {
            return seconds + " сек назад";
        }
        return Duration.ofSeconds(seconds).toMinutes() + " мин назад";
    }

    /** Уведомление о пересечении порога. */
    public String thresholdAlert(WindowKind kind, LimitWindow window, int threshold) {
        return "%s <b>Пройден порог %d%%</b>\n\n%s".formatted(icon(window.percent()), threshold, block(kind, window));
    }

    /** Блок из заголовка, двух шкал и вердикта по темпу. */
    private String block(WindowKind kind, LimitWindow window) {
        if (window == null) {
            return "<b>%s</b>: нет данных".formatted(kind.title());
        }
        StringBuilder sb = new StringBuilder("%s <b>%s</b> — %s\n%s токены %.0f%%".formatted(
                icon(window.percent()), kind.title(), resetLine(window, true),
                bar(window.percent(), tokenCell(window.percent())), window.percent()));

        OptionalDouble elapsed = elapsedPercent(kind, window);
        if (elapsed.isPresent()) {
            sb.append("\n%s время %.0f%%".formatted(bar(elapsed.getAsDouble(), TIME_CELL), elapsed.getAsDouble()));
            sb.append('\n').append(pace(window.percent(), elapsed.getAsDouble()));
        }
        if (window.lockedReason() != null) {
            sb.append("\n🚫 Заблокировано: ").append(window.lockedReason());
        }
        return sb.toString();
    }

    /** Какая доля окна уже прошла. Пусто, если API не сообщил время сброса. */
    private OptionalDouble elapsedPercent(WindowKind kind, LimitWindow window) {
        if (window.resetsAt() == null) {
            return OptionalDouble.empty();
        }
        double total = kind.duration().toSeconds();
        double left = Duration.between(Instant.now(), window.resetsAt()).toSeconds();
        return OptionalDouble.of(clamp(100 * (total - left) / total));
    }

    private static String pace(double tokens, double elapsed) {
        double gap = tokens - elapsed;
        if (gap >= PACE_TOLERANCE) {
            return "⚡ расход опережает время на %.0f п.п.".formatted(gap);
        }
        if (gap <= -PACE_TOLERANCE) {
            return "🐢 расход отстаёт от времени на %.0f п.п.".formatted(-gap);
        }
        return "✅ расход идёт вровень со временем";
    }

    private String resetLine(LimitWindow window, boolean withCountdown) {
        if (window.resetsAt() == null) {
            return "время сброса неизвестно";
        }
        var local = window.resetsAt().atZone(properties.getTimezone());
        Duration left = Duration.between(Instant.now(), window.resetsAt());
        boolean sameDay = local.toLocalDate().equals(Instant.now().atZone(properties.getTimezone()).toLocalDate());
        String at = local.format(sameDay ? TIME : DATE_TIME);
        if (!withCountdown || left.isNegative()) {
            return "сброс в " + at;
        }
        return "сброс в %s (через %s)".formatted(at, humanize(left));
    }

    private static String humanize(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return "%d дн %d ч".formatted(days, duration.toHoursPart());
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return "%d ч %d мин".formatted(hours, duration.toMinutesPart());
        }
        return "%d мин".formatted(Math.max(1, duration.toMinutesPart()));
    }

    /** Шкала из десяти клеток; заполненная часть рисуется переданным цветом. */
    private static String bar(double percent, String filledCell) {
        int filled = (int) Math.round(clamp(percent) / 100 * BAR_CELLS);
        return filledCell.repeat(filled) + EMPTY_CELL.repeat(BAR_CELLS - filled);
    }

    private static String tokenCell(double percent) {
        if (percent >= 90) {
            return "🟥";
        }
        return percent >= 75 ? "🟨" : "🟩";
    }

    private static String icon(double percent) {
        if (percent >= 90) {
            return "🔴";
        }
        return percent >= 75 ? "🟠" : "🟢";
    }

    private static double clamp(double percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
