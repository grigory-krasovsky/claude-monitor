package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.telegram.StatusBoard;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import com.example.claudeusagemonitor.usage.WindowKind;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.util.StringUtils;

/**
 * Рантайм-состояние одного отслеживаемого аккаунта: токены, последний срез, пороги,
 * счётчик сбоев и сообщения в чате.
 *
 * <p>В базе лежит только то, что должно пережить рестарт (токены и id сообщений).
 * Пороги и счётчики живут здесь: после рестарта их честнее пересчитать заново по
 * свежему ответу API, чем доверять снимку, сделанному неизвестно когда.
 *
 * <p>Блокировок три, и все на аккаунт, а не глобальные — зависший запрос одного
 * пользователя не должен задерживать другого:
 * <ul>
 *   <li>{@link #tokens()} — монитор объекта токенов, держится на время обмена refresh token;</li>
 *   <li>сам объект сессии — монитор для доски сообщений и состояния порогов;</li>
 *   <li>{@link #pollLock()} — не даёт запустить два опроса одного аккаунта параллельно
 *       (планировщик и только что присланный /token).</li>
 * </ul>
 * Порядок захвата всегда «токены → сессия», обратного нигде нет.
 */
public class AccountSession {

    private final long accountId;
    private final long chatId;
    private final Tokens tokens;
    private final Board board;
    private final ReentrantLock pollLock = new ReentrantLock();

    /** Состояние алертов по каждому окну: ключ сброса и максимальный отмеченный порог. */
    private final Map<WindowKind, WindowState> windows = new EnumMap<>(WindowKind.class);

    private volatile UsageSnapshot lastSnapshot;
    private int consecutiveFailures;

    /**
     * Сессия отцеплена (/forget, /revoke, новый /token). Опрос, начатый до этого,
     * может закончиться позже — по флагу он поймёт, что ни писать в чат, ни сохранять
     * токены уже нельзя, иначе воскресил бы только что удалённое.
     */
    private volatile boolean detached;

    public AccountSession(long accountId, long chatId, Tokens tokens, Board board) {
        this.accountId = accountId;
        this.chatId = chatId;
        this.tokens = tokens;
        this.board = board;
    }

    public static AccountSession of(Account account) {
        return new AccountSession(account.id(), account.chatId(),
                new Tokens(account.accessToken(), account.refreshToken(), account.expiresAt()),
                new Board(account.statusMessageId(), account.alertMessageId(), account.alertKind()));
    }

    public long accountId() {
        return accountId;
    }

    public long chatId() {
        return chatId;
    }

    public Tokens tokens() {
        return tokens;
    }

    public Board board() {
        return board;
    }

    public ReentrantLock pollLock() {
        return pollLock;
    }

    public Map<WindowKind, WindowState> windows() {
        return windows;
    }

    public UsageSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public void setLastSnapshot(UsageSnapshot lastSnapshot) {
        this.lastSnapshot = lastSnapshot;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public int incrementFailures() {
        return ++consecutiveFailures;
    }

    public void resetFailures() {
        consecutiveFailures = 0;
    }

    public boolean isDetached() {
        return detached;
    }

    /**
     * Помечает сессию отцепленной. Ждёт завершения идущего обмена токена: иначе тот
     * успел бы записать ротированный refresh token в базу уже после /forget.
     */
    public void detach() {
        synchronized (tokens) {
            synchronized (this) {
                detached = true;
            }
        }
    }

    @Override
    public String toString() {
        return "AccountSession[account=" + accountId + ", chat=" + chatId + "]";
    }

    /** OAuth-токены аккаунта. Изменяются только под монитором самого объекта. */
    public static final class Tokens {
        private String accessToken;
        private String refreshToken;
        private Instant expiresAt;
        private Instant refreshBlockedUntil;
        /** Почему не удалось последнее обновление — попадает в алерт о сбое. */
        private String refreshError;
        /** Ротированные токены не удалось записать в базу — запись повторяется при каждом обращении. */
        private boolean unsaved;

        public Tokens(String accessToken, String refreshToken, Instant expiresAt) {
            this.accessToken = accessToken == null ? "" : accessToken.trim();
            this.refreshToken = refreshToken == null ? "" : refreshToken.trim();
            this.expiresAt = expiresAt;
        }

        public boolean isConfigured() {
            return StringUtils.hasText(accessToken) || StringUtils.hasText(refreshToken);
        }

        public String accessToken() {
            return accessToken;
        }

        public String refreshToken() {
            return refreshToken;
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        public void update(String accessToken, String refreshToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }

        public Instant refreshBlockedUntil() {
            return refreshBlockedUntil;
        }

        public void setRefreshBlockedUntil(Instant refreshBlockedUntil) {
            this.refreshBlockedUntil = refreshBlockedUntil;
        }

        public String refreshError() {
            return refreshError;
        }

        public void setRefreshError(String refreshError) {
            this.refreshError = refreshError;
        }

        public boolean isUnsaved() {
            return unsaved;
        }

        public void setUnsaved(boolean unsaved) {
            this.unsaved = unsaved;
        }

        @Override
        public String toString() {
            return "Tokens[expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * Сообщения бота в чате аккаунта. Изменяются под монитором сессии.
     *
     * <p>{@code pausedUntil} — общий «стоп-кран» для отрисовки: его ставят 429 с
     * retry_after, заблокированный бот и неудачные попытки создать статус. Без него
     * каждые 15 секунд уходил бы запрос, заранее обречённый на тот же отказ.
     */
    public static final class Board {
        private Long statusMessageId;
        private Long alertMessageId;
        private StatusBoard.AlertKind alertKind;
        /** Последний отрисованный текст: если он не изменился, правку в Telegram не шлём. */
        private String renderedStatus;
        private Instant pausedUntil;
        private int sendFailures;

        public Board(Long statusMessageId, Long alertMessageId, StatusBoard.AlertKind alertKind) {
            this.statusMessageId = statusMessageId;
            this.alertMessageId = alertMessageId;
            this.alertKind = alertMessageId == null ? null : alertKind;
        }

        public Long statusMessageId() {
            return statusMessageId;
        }

        public void setStatusMessageId(Long statusMessageId) {
            this.statusMessageId = statusMessageId;
        }

        public Long alertMessageId() {
            return alertMessageId;
        }

        public StatusBoard.AlertKind alertKind() {
            return alertKind;
        }

        public void setAlert(Long alertMessageId, StatusBoard.AlertKind alertKind) {
            this.alertMessageId = alertMessageId;
            this.alertKind = alertMessageId == null ? null : alertKind;
        }

        public String renderedStatus() {
            return renderedStatus;
        }

        public void setRenderedStatus(String renderedStatus) {
            this.renderedStatus = renderedStatus;
        }

        public Instant pausedUntil() {
            return pausedUntil;
        }

        public boolean isPaused(Instant now) {
            return pausedUntil != null && now.isBefore(pausedUntil);
        }

        public void pauseUntil(Instant until) {
            this.pausedUntil = until;
        }

        public int sendFailures() {
            return sendFailures;
        }

        public int incrementSendFailures() {
            return ++sendFailures;
        }

        /** Сообщение дошло — все паузы и счётчики сбоев сбрасываются. */
        public void markDelivered() {
            pausedUntil = null;
            sendFailures = 0;
        }
    }

    /** Изменяемое состояние одного окна лимита. */
    public static final class WindowState {
        private final String resetKey;
        private int maxNotified;

        public WindowState(String resetKey) {
            this.resetKey = resetKey;
        }

        public String resetKey() {
            return resetKey;
        }

        public int maxNotified() {
            return maxNotified;
        }

        public void setMaxNotified(int maxNotified) {
            this.maxNotified = maxNotified;
        }
    }
}
