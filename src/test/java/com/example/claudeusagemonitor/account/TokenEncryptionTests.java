package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.MessageFormatter;
import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import com.example.claudeusagemonitor.testsupport.TestBeans;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Токены в базе лежат только зашифрованными — на каждом пути записи, — а нечитаемое
 * значение выключает один аккаунт, не роняя остальных.
 */
class TokenEncryptionTests extends DatabaseTest {

    private static final long ADMIN = 100;
    private static final long USER = 200;
    private static final long OWNER = 555;

    @Autowired
    private AlertService alertService;

    @Autowired
    private OwnerImporter importer;

    @Autowired
    private MonitorProperties properties;

    @BeforeEach
    void approveUser() {
        send(ADMIN, "/register");
        send(USER, "/register");
        repository.decide(USER, AccountStatus.APPROVED);
        telegram.reset();
    }

    @AfterEach
    void restoreProperties() {
        properties.getTelegram().setChatId("");
        properties.getAnthropic().setAccessToken("");
        properties.getAnthropic().setRefreshToken("");
    }

    @Test
    @DisplayName("/token: в колонках v1:-шифротекст без исходных токенов, репозиторий отдаёт открытые")
    void connectStoresCiphertext() {
        send(USER, "/token sk-ant-ort01-abc");

        Map<String, Object> raw = rawTokens(USER);
        assertCiphertext(raw.get("access_token"), "access-sk-ant-ort01-abc");
        assertCiphertext(raw.get("refresh_token"), "rotated-sk-ant-ort01-abc");

        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.accessToken()).isEqualTo("access-sk-ant-ort01-abc");
        assertThat(account.refreshToken()).isEqualTo("rotated-sk-ant-ort01-abc");
        assertThat(account.tokensUnreadable()).isFalse();
        assertThat(sessions.find(USER)).isPresent();
    }

    @Test
    @DisplayName("ротация токенов (saveTokens) тоже пишет только шифротекст")
    void saveTokensStoresCiphertext() {
        Account account = repository.findByChatId(USER).orElseThrow();
        repository.saveTokens(account.id(), USER, "sk-ant-oat01-new", "sk-ant-ort01-new", Instant.now());

        Map<String, Object> raw = rawTokens(USER);
        assertCiphertext(raw.get("access_token"), "sk-ant-oat01-new");
        assertCiphertext(raw.get("refresh_token"), "sk-ant-ort01-new");
        assertThat(repository.findByChatId(USER).orElseThrow().refreshToken()).isEqualTo("sk-ant-ort01-new");
    }

    @Test
    @DisplayName("saveTokens с чужим chat_id не трогает строку: шифротекст не ляжет не туда")
    void saveTokensRequiresMatchingChatId() {
        send(USER, "/token sk-ant-ort01-abc");
        Account account = repository.findByChatId(USER).orElseThrow();

        repository.saveTokens(account.id(), ADMIN, "sk-ant-oat01-x", "sk-ant-ort01-x", null);

        assertThat(repository.findByChatId(USER).orElseThrow().refreshToken()).isEqualTo("rotated-sk-ant-ort01-abc");
    }

    @Test
    @DisplayName("импорт владельца из окружения шифрует токены")
    void ownerImportStoresCiphertext() {
        properties.getTelegram().setChatId(String.valueOf(OWNER));
        properties.getAnthropic().setAccessToken("sk-ant-oat01-env");
        properties.getAnthropic().setRefreshToken("sk-ant-ort01-env");

        assertThat(importer.importOwner()).isTrue();

        Map<String, Object> raw = rawTokens(OWNER);
        assertCiphertext(raw.get("access_token"), "sk-ant-oat01-env");
        assertCiphertext(raw.get("refresh_token"), "sk-ant-ort01-env");
        assertThat(repository.findByChatId(OWNER).orElseThrow().refreshToken()).isEqualTo("sk-ant-ort01-env");
    }

    @Test
    @DisplayName("пустой токен хранится как null, а не как шифротекст пустой строки")
    void blankTokenIsNull() {
        Account account = repository.findByChatId(USER).orElseThrow();
        repository.saveTokens(account.id(), USER, "", "sk-ant-ort01-only", null);

        Map<String, Object> raw = rawTokens(USER);
        assertThat(raw.get("access_token")).isNull();
        assertCiphertext(raw.get("refresh_token"), "sk-ant-ort01-only");
    }

    @Test
    @DisplayName("токены другого ключа: аккаунт выключен, остальные работают, пользователю — просьба прислать /token")
    void foreignKeyDoesNotBreakApp() {
        // Соседний аккаунт с нормальными токенами — его мониторинг не должен пострадать
        send(ADMIN, "/token sk-ant-ort01-admin");
        sessions.detach(ADMIN);
        String otherKey = TokenCipherTests.randomKey();
        TokenCipher foreign = new TokenCipher(otherKey);
        setRawTokens(USER, foreign.encrypt("sk-ant-oat01-x", USER), foreign.encrypt("sk-ant-ort01-x", USER));
        telegram.reset();

        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.tokensUnreadable()).isTrue();
        assertThat(account.accessToken()).isNull();
        assertThat(account.refreshToken()).isNull();
        assertThat(account.isMonitored()).isFalse();
        assertThat(account.toString()).contains("не читается");

        assertThatCode(sessions::sync).doesNotThrowAnyException();
        assertThat(repository.findMonitored()).extracting(Account::chatId).containsExactly(ADMIN);
        assertThat(sessions.find(USER)).isEmpty();
        assertThat(sessions.find(ADMIN)).isPresent();

        // Плановое уведомление после старта и ответ на команды
        alertService.notifyUnreadable();
        assertThat(telegram.lastTextTo(USER)).isEqualTo(MessageFormatter.TOKENS_UNREADABLE);
        assertThat(telegram.sentTo(ADMIN)).isEmpty();
        send(USER, "/status");
        assertThat(telegram.lastTextTo(USER)).contains("не читаются", "/token");
        send(ADMIN, "/users");
        assertThat(telegram.lastTextTo(ADMIN)).contains("не читается");

        // Новый /token чинит аккаунт
        send(USER, "/token sk-ant-ort01-fresh");
        Account fixed = repository.findByChatId(USER).orElseThrow();
        assertThat(fixed.tokensUnreadable()).isFalse();
        assertThat(fixed.refreshToken()).isEqualTo("rotated-sk-ant-ort01-fresh");
        assertThat(sessions.find(USER)).isPresent();
    }

    @Test
    @DisplayName("открытый токен без префикса и шифротекст чужой строки — тоже «не читается», а не ошибка")
    void plaintextAndSwappedRowsAreUnreadable() {
        setRawTokens(USER, "sk-ant-oat01-plain", "sk-ant-ort01-plain");
        assertThat(repository.findByChatId(USER).orElseThrow().tokensUnreadable()).isTrue();

        // Шифротекст, честно зашифрованный нашим ключом, но для другого chat_id
        TokenCipher ours = new TokenCipher(TestBeans.TOKEN_KEY);
        setRawTokens(USER, ours.encrypt("sk-ant-oat01-a", ADMIN), ours.encrypt("sk-ant-ort01-a", ADMIN));
        Account account = repository.findByChatId(USER).orElseThrow();
        assertThat(account.tokensUnreadable()).isTrue();
        assertThat(account.refreshToken()).isNull();
        assertThat(repository.findAll()).hasSize(2);
    }

    private Map<String, Object> rawTokens(long chatId) {
        return jdbc.sql("select access_token, refresh_token from account where chat_id = :chatId")
                .param("chatId", chatId)
                .query()
                .singleRow();
    }

    private void setRawTokens(long chatId, String access, String refresh) {
        jdbc.sql("update account set access_token = :access, refresh_token = :refresh where chat_id = :chatId")
                .param("chatId", chatId)
                .param("access", access)
                .param("refresh", refresh)
                .update();
    }

    private static void assertCiphertext(Object stored, String plaintext) {
        assertThat(stored).isInstanceOf(String.class);
        assertThat((String) stored).startsWith("v1:").doesNotContain(plaintext);
        // Ни одного узнаваемого куска токена — не только целиком
        assertThat((String) stored).doesNotContain("sk-ant");
    }
}
