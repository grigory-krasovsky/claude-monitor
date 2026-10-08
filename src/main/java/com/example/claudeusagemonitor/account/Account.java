package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.telegram.StatusBoard;
import java.time.Instant;
import org.springframework.util.StringUtils;

/**
 * Строка таблицы {@code account}: пользователь Telegram и его аккаунт Claude.
 *
 * <p>{@link #toString()} переопределён: запись нет-нет да и попадёт в лог, а токены
 * в логах — это готовый доступ к чужому аккаунту.
 *
 * <p>Токены здесь уже расшифрованы ({@link AccountRepository} — единственное место, где
 * они шифруются и расшифровываются). Если значение в базе не читается (сменился ключ,
 * запись подменена), оба токена — {@code null}, а {@code tokensUnreadable} поднят: для
 * остального кода аккаунт просто не подключён, но пользователю можно объяснить почему.
 */
public record Account(
        long id,
        long chatId,
        String username,
        String displayName,
        AccountRole role,
        AccountStatus status,
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        Long statusMessageId,
        Long alertMessageId,
        StatusBoard.AlertKind alertKind,
        Long requestMessageId,
        Instant createdAt,
        Instant updatedAt,
        boolean tokensUnreadable) {

    public boolean isAdmin() {
        return role == AccountRole.ADMIN;
    }

    public boolean isApproved() {
        return status == AccountStatus.APPROVED;
    }

    /** Есть ли чем ходить в API лимитов. */
    public boolean hasToken() {
        return StringUtils.hasText(accessToken) || StringUtils.hasText(refreshToken);
    }

    /** Аккаунт, который опрашивает планировщик: одобрен и с читаемыми токенами. */
    public boolean isMonitored() {
        return isApproved() && hasToken();
    }

    /** Имя для людей: «Иван Петров (@ivan, 123)». */
    public String label() {
        StringBuilder sb = new StringBuilder(StringUtils.hasText(displayName) ? displayName : "без имени");
        sb.append(" (");
        if (StringUtils.hasText(username)) {
            sb.append('@').append(username).append(", ");
        }
        return sb.append(chatId).append(')').toString();
    }

    @Override
    public String toString() {
        return "Account[id=%d, chatId=%d, role=%s, status=%s, token=%s]"
                .formatted(id, chatId, role, status,
                        tokensUnreadable ? "не читается" : hasToken() ? "есть" : "нет");
    }
}
