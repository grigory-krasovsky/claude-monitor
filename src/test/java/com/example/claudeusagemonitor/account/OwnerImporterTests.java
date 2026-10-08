package com.example.claudeusagemonitor.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.testsupport.DatabaseTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

/** Однократный импорт владельца из прежней однопользовательской конфигурации. */
class OwnerImporterTests extends DatabaseTest {

    private static final long OWNER = 555;

    @Autowired
    private OwnerImporter importer;

    @Autowired
    private MonitorProperties properties;

    @TempDir
    Path dir;

    @AfterEach
    void restoreProperties() {
        properties.getTelegram().setChatId("");
        properties.getTelegram().setStateFile("");
        properties.getAnthropic().setAccessToken("");
        properties.getAnthropic().setRefreshToken("");
        properties.getAnthropic().setTokenFile("");
    }

    private void legacyConfig(boolean withFiles) throws IOException {
        properties.getTelegram().setChatId(String.valueOf(OWNER));
        properties.getAnthropic().setAccessToken("env-access");
        properties.getAnthropic().setRefreshToken("env-refresh");
        if (withFiles) {
            Path tokens = dir.resolve("tokens.json");
            Files.writeString(tokens, """
                    {"accessToken":"file-access","refreshToken":"file-refresh","expiresAt":1900000000}""");
            Path board = dir.resolve("board.json");
            Files.writeString(board, """
                    {"statusMessageId":42,"alertMessageId":0,"alertKind":""}""");
            properties.getAnthropic().setTokenFile(tokens.toString());
            properties.getTelegram().setStateFile(board.toString());
        }
    }

    @Test
    @DisplayName("импортирует владельца как USER + APPROVED: файлы свежее окружения, статус тот же")
    void importsOwner() throws IOException {
        legacyConfig(true);

        assertThat(importer.importOwner()).isTrue();

        Account owner = repository.findByChatId(OWNER).orElseThrow();
        assertThat(owner.role()).isEqualTo(AccountRole.USER);
        assertThat(owner.status()).isEqualTo(AccountStatus.APPROVED);
        assertThat(owner.accessToken()).isEqualTo("file-access");
        assertThat(owner.refreshToken()).isEqualTo("file-refresh");
        assertThat(owner.expiresAt()).isEqualTo(Instant.ofEpochSecond(1900000000));
        assertThat(owner.statusMessageId()).isEqualTo(42L);
        assertThat(owner.alertMessageId()).isNull();
        assertThat(owner.isMonitored()).isTrue();
    }

    @Test
    @DisplayName("без файлов берутся токены из окружения")
    void importsFromEnv() throws IOException {
        legacyConfig(false);

        importer.importOwner();

        Account owner = repository.findByChatId(OWNER).orElseThrow();
        assertThat(owner.accessToken()).isEqualTo("env-access");
        assertThat(owner.refreshToken()).isEqualTo("env-refresh");
        assertThat(owner.statusMessageId()).isNull();
    }

    @Test
    @DisplayName("аккаунт уже есть — окружение игнорируется; после /forget импорт не воскрешает запись")
    void importIsOneShot() throws IOException {
        repository.importApproved(OWNER, "db-access", "db-refresh", null, null, null, null);
        legacyConfig(true);

        assertThat(importer.importOwner()).isFalse();
        assertThat(repository.findByChatId(OWNER).orElseThrow().refreshToken()).isEqualTo("db-refresh");

        repository.delete(repository.findByChatId(OWNER).orElseThrow().id());
        assertThat(importer.importOwner()).isFalse();
        assertThat(repository.findByChatId(OWNER)).isEmpty();
    }

    @Test
    @DisplayName("без TELEGRAM_CHAT_ID импорт ничего не делает")
    void noChatIdNoImport() {
        assertThat(importer.importOwner()).isFalse();
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("импортированный владелец, сделав первый /register, становится админом с сохранением токенов")
    void importedOwnerBecomesAdmin() throws IOException {
        legacyConfig(true);
        importer.importOwner();

        send(OWNER, "/register");

        Account owner = repository.findByChatId(OWNER).orElseThrow();
        assertThat(owner.isAdmin()).isTrue();
        assertThat(owner.refreshToken()).isEqualTo("file-refresh");
        assertThat(owner.statusMessageId()).isEqualTo(42L);
        assertThat(telegram.lastTextTo(OWNER)).contains("уже подключён");
    }
}
