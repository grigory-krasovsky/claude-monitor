package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.telegram.StatusBoard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Таблица {@code account} через {@link JdbcClient}.
 *
 * <p>Переходы статуса заявки делаются одним условным UPDATE (... where status = 'PENDING'):
 * так повторное нажатие кнопки или гонка двух нажатий меняют запись не больше одного раза,
 * и для этого не нужны ни явные транзакции, ни блокировки.
 *
 * <p>Токены в колонках {@code access_token} и {@code refresh_token} зашифрованы
 * ({@link TokenCipher}, AAD — chat_id строки), и это единственный класс, который их
 * шифрует и расшифровывает: любая запись токенов проходит через {@link #token}, любое
 * чтение — через {@link #map}. Остальной код видит только открытые значения.
 *
 * <p>Значение, которое не расшифровывается (другой ключ, подмена, строка без префикса),
 * не роняет ни запрос, ни приложение: оба токена аккаунта считаются отсутствующими, а
 * в {@link Account#tokensUnreadable()} остаётся отметка, чтобы бот попросил прислать
 * /token заново.
 */
@Repository
public class AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(AccountRepository.class);

    private static final String COLUMNS = """
            id, chat_id, username, display_name, role, status, access_token, refresh_token, expires_at,
            status_message_id, alert_message_id, alert_kind, request_message_id, created_at, updated_at""";

    private final JdbcClient jdbc;
    private final TokenCipher cipher;

    /**
     * chat_id, о нечитаемых токенах которых уже сказано в лог. Строки читаются при каждом
     * опросе, и без этого одна и та же ошибка повторялась бы раз в три минуты. Отметка
     * снимается при записи токенов — следующая поломка снова будет громкой.
     */
    private final Set<Long> reportedUnreadable = ConcurrentHashMap.newKeySet();

    public AccountRepository(JdbcClient jdbc, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    public Optional<Account> findByChatId(long chatId) {
        return jdbc.sql("select " + COLUMNS + " from account where chat_id = :chatId")
                .param("chatId", chatId)
                .query(this::map)
                .optional();
    }

    public Optional<Account> findById(long id) {
        return jdbc.sql("select " + COLUMNS + " from account where id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public Optional<Account> findAdmin() {
        return jdbc.sql("select " + COLUMNS + " from account where role = 'ADMIN'")
                .query(this::map)
                .optional();
    }

    public List<Account> findAll() {
        return jdbc.sql("select " + COLUMNS + " from account order by id")
                .query(this::map)
                .list();
    }

    /**
     * Аккаунты, которые опрашивает планировщик: одобренные и с токеном.
     * Повторный фильтр в Java отсекает строки, чьи токены в базе есть, но не расшифровываются.
     */
    public List<Account> findMonitored() {
        return jdbc.sql("select " + COLUMNS + """
                         from account
                        where status = 'APPROVED'
                          and (access_token is not null or refresh_token is not null)
                        order by id""")
                .query(this::map)
                .list()
                .stream()
                .filter(Account::isMonitored)
                .toList();
    }

    /** Одобренные аккаунты, чьи токены в базе есть, но не читаются. */
    public List<Account> findUnreadable() {
        return findAll().stream()
                .filter(Account::tokensUnreadable)
                .filter(Account::isApproved)
                .toList();
    }

    /**
     * Делает пользователя администратором, создавая запись при необходимости.
     * Токены и сообщения существующей записи сохраняются — так владелец, импортированный
     * из окружения, становится админом, не теряя мониторинга.
     *
     * <p>Один оператор атомарен сам по себе, отдельная транзакция не нужна.
     *
     * @throws org.springframework.dao.DuplicateKeyException если администратор уже есть
     */
    public Account promoteToAdmin(long chatId, String username, String displayName) {
        return jdbc.sql("""
                        insert into account (chat_id, username, display_name, role, status)
                        values (:chatId, :username, :displayName, 'ADMIN', 'APPROVED')
                        on conflict (chat_id) do update
                           set role = 'ADMIN', status = 'APPROVED',
                               username = excluded.username, display_name = excluded.display_name,
                               updated_at = now()
                        returning\s""" + COLUMNS)
                .param("chatId", chatId)
                .param("username", text(username))
                .param("displayName", text(displayName))
                .query(this::map)
                .single();
    }

    /**
     * Заводит заявку.
     *
     * @return {@code false}, если запись с таким chat_id уже есть (например, два /register подряд)
     */
    public boolean createPending(long chatId, String username, String displayName) {
        return jdbc.sql("""
                        insert into account (chat_id, username, display_name, role, status)
                        values (:chatId, :username, :displayName, 'USER', 'PENDING')
                        on conflict (chat_id) do nothing""")
                .param("chatId", chatId)
                .param("username", text(username))
                .param("displayName", text(displayName))
                .update() == 1;
    }

    public void updateProfile(long chatId, String username, String displayName) {
        jdbc.sql("""
                        update account set username = :username, display_name = :displayName, updated_at = now()
                         where chat_id = :chatId""")
                .param("chatId", chatId)
                .param("username", text(username))
                .param("displayName", text(displayName))
                .update();
    }

    public void setRequestMessageId(long chatId, Long messageId) {
        jdbc.sql("update account set request_message_id = :messageId, updated_at = now() where chat_id = :chatId")
                .param("chatId", chatId)
                .param("messageId", bigint(messageId))
                .update();
    }

    /**
     * Решение по заявке. Применяется только к записи в статусе PENDING.
     *
     * @return обновлённая запись или пусто, если заявки нет либо она уже обработана
     */
    public Optional<Account> decide(long chatId, AccountStatus decision) {
        return jdbc.sql("""
                        update account set status = :status, updated_at = now()
                         where chat_id = :chatId and status = 'PENDING'
                        returning\s""" + COLUMNS)
                .param("chatId", chatId)
                .param("status", decision.name())
                .query(this::map)
                .optional();
    }

    /**
     * Отзывает доступ. Токены стираются вместе со статусом: держать учётные данные
     * человека, которому бот больше не служит, незачем.
     */
    public void revoke(long chatId) {
        reportedUnreadable.remove(chatId);
        jdbc.sql("""
                        update account
                           set status = 'REJECTED',
                               access_token = null, refresh_token = null, expires_at = null,
                               status_message_id = null, alert_message_id = null, alert_kind = null,
                               request_message_id = null, updated_at = now()
                         where chat_id = :chatId""")
                .param("chatId", chatId)
                .update();
    }

    /**
     * Сохраняет ротацию токенов. Вызывается после каждого обмена refresh token.
     *
     * <p>chat_id нужен шифрованию как AAD. Условие по нему же в WHERE гарантирует, что
     * шифротекст ляжет именно в ту строку, к которой привязан, даже если вызывающий
     * перепутал пару id/chat_id.
     */
    public void saveTokens(long id, long chatId, String accessToken, String refreshToken, Instant expiresAt) {
        reportedUnreadable.remove(chatId);
        jdbc.sql("""
                        update account
                           set access_token = :access, refresh_token = :refresh, expires_at = :expiresAt,
                               updated_at = now()
                         where id = :id and chat_id = :chatId""")
                .param("id", id)
                .param("chatId", chatId)
                .param("access", token(accessToken, chatId))
                .param("refresh", token(refreshToken, chatId))
                .param("expiresAt", timestamp(expiresAt))
                .update();
    }

    /** Идентификаторы сообщений: по ним бот после рестарта продолжает править то же сообщение. */
    public void saveBoard(long id, Long statusMessageId, Long alertMessageId, StatusBoard.AlertKind alertKind) {
        jdbc.sql("""
                        update account
                           set status_message_id = :statusId, alert_message_id = :alertId, alert_kind = :kind,
                               updated_at = now()
                         where id = :id""")
                .param("id", id)
                .param("statusId", bigint(statusMessageId))
                .param("alertId", bigint(alertMessageId))
                .param("kind", text(alertKind == null ? null : alertKind.name()))
                .update();
    }

    /** Стирает токены и ссылки на сообщения, роль и статус не трогает. */
    public void forget(long id) {
        jdbc.sql("""
                        update account
                           set access_token = null, refresh_token = null, expires_at = null,
                               status_message_id = null, alert_message_id = null, alert_kind = null,
                               updated_at = now()
                         where id = :id""")
                .param("id", id)
                .update();
    }

    public void delete(long id) {
        jdbc.sql("delete from account where id = :id").param("id", id).update();
    }

    /**
     * Импорт владельца из прежней однопользовательской конфигурации.
     *
     * @return {@code false}, если аккаунт с таким chat_id уже есть — тогда база главнее окружения
     */
    public boolean importApproved(long chatId, String accessToken, String refreshToken, Instant expiresAt,
                                  Long statusMessageId, Long alertMessageId, StatusBoard.AlertKind alertKind) {
        return jdbc.sql("""
                        insert into account (chat_id, role, status, access_token, refresh_token, expires_at,
                                             status_message_id, alert_message_id, alert_kind)
                        values (:chatId, 'USER', 'APPROVED', :access, :refresh, :expiresAt,
                                :statusId, :alertId, :kind)
                        on conflict (chat_id) do nothing""")
                .param("chatId", chatId)
                .param("access", token(accessToken, chatId))
                .param("refresh", token(refreshToken, chatId))
                .param("expiresAt", timestamp(expiresAt))
                .param("statusId", bigint(statusMessageId))
                .param("alertId", bigint(alertMessageId))
                .param("kind", text(alertKind == null ? null : alertKind.name()))
                .update() == 1;
    }

    /** Импортировался ли уже этот chat_id из окружения. */
    public boolean isLegacyImported(long chatId) {
        return jdbc.sql("select count(*) from legacy_import where chat_id = :chatId")
                .param("chatId", chatId)
                .query(Long.class)
                .single() > 0;
    }

    public void markLegacyImported(long chatId) {
        jdbc.sql("insert into legacy_import (chat_id) values (:chatId) on conflict (chat_id) do nothing")
                .param("chatId", chatId)
                .update();
    }

    private Account map(ResultSet rs, int rowNum) throws SQLException {
        long chatId = rs.getLong("chat_id");
        String accessToken;
        String refreshToken;
        boolean unreadable = false;
        try {
            accessToken = cipher.decrypt(rs.getString("access_token"), chatId);
            refreshToken = cipher.decrypt(rs.getString("refresh_token"), chatId);
        } catch (TokenCipher.UnreadableTokenException e) {
            // Половину пары не берём: если одна колонка подменена, доверять второй тоже не с чего
            accessToken = null;
            refreshToken = null;
            unreadable = true;
            if (reportedUnreadable.add(chatId)) {
                log.error("Токены аккаунта chat_id={} в базе не читаются ({}) — считаю их отсутствующими, "
                        + "мониторинг аккаунта не запустится до нового /token", chatId, e.getMessage());
            }
        }
        return new Account(
                rs.getLong("id"),
                chatId,
                rs.getString("username"),
                rs.getString("display_name"),
                AccountRole.valueOf(rs.getString("role")),
                AccountStatus.valueOf(rs.getString("status")),
                accessToken,
                refreshToken,
                instant(rs, "expires_at"),
                rs.getObject("status_message_id", Long.class),
                rs.getObject("alert_message_id", Long.class),
                alertKind(rs.getString("alert_kind")),
                rs.getObject("request_message_id", Long.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                unreadable);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static StatusBoard.AlertKind alertKind(String value) {
        if (value == null) {
            return null;
        }
        try {
            return StatusBoard.AlertKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Типы null-параметров задаём явно: иначе драйверу пришлось бы угадывать их по метаданным

    private static SqlParameterValue text(String value) {
        return new SqlParameterValue(Types.VARCHAR, value == null || value.isBlank() ? null : value);
    }

    /** Токен для записи в базу: зашифрованный под chat_id строки, пустой — {@code null}. */
    private SqlParameterValue token(String value, long chatId) {
        return new SqlParameterValue(Types.VARCHAR, cipher.encrypt(value, chatId));
    }

    private static SqlParameterValue bigint(Long value) {
        return new SqlParameterValue(Types.BIGINT, value);
    }

    /** pgjdbc не принимает {@link Instant} напрямую, а {@link OffsetDateTime} — да. */
    private static SqlParameterValue timestamp(Instant value) {
        return new SqlParameterValue(Types.TIMESTAMP_WITH_TIMEZONE,
                value == null ? null : value.atOffset(ZoneOffset.UTC));
    }
}
