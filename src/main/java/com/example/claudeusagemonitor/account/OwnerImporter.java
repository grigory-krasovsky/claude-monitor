package com.example.claudeusagemonitor.account;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.telegram.StatusBoard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Однократный перенос владельца из однопользовательской конфигурации в базу.
 *
 * <p>Если задан {@code TELEGRAM_CHAT_ID}, а этот chat_id ещё не импортировался, он
 * заводится как USER + APPROVED с токенами из окружения и файлов на volume — бот
 * продолжает править то же статусное сообщение, что и до перехода на базу. Роль
 * ADMIN импорт не даёт: её даёт только первый {@code /register}, и если владелец
 * сделает его сам, запись повысится с сохранением токенов.
 *
 * <p>Импорт отмечается в {@code legacy_import} и больше не повторяется: refresh token
 * ротируется, и через сутки значение из окружения уже погашено. База — источник правды,
 * переменные {@code TELEGRAM_CHAT_ID} и {@code ANTHROPIC_*} после миграции не нужны.
 *
 * <p>{@link ApplicationRunner} выполняется до {@code ApplicationReadyEvent}, так что
 * первый опрос уже видит импортированный аккаунт.
 */
@Component
public class OwnerImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerImporter.class);

    private final MonitorProperties properties;
    private final AccountRepository repository;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;

    public OwnerImporter(MonitorProperties properties, AccountRepository repository,
                         TransactionTemplate transaction, ObjectMapper objectMapper) {
        this.properties = properties;
        this.repository = repository;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        importOwner();
    }

    /** @return {@code true}, если аккаунт был создан */
    public boolean importOwner() {
        String configured = properties.getTelegram().getChatId();
        if (!StringUtils.hasText(configured)) {
            return false;
        }
        long chatId;
        try {
            chatId = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            log.warn("TELEGRAM_CHAT_ID='{}' не число — импорт владельца пропущен", configured);
            return false;
        }
        Boolean created = transaction.execute(status -> {
            if (repository.isLegacyImported(chatId)) {
                return false;
            }
            repository.markLegacyImported(chatId);
            if (repository.findByChatId(chatId).isPresent()) {
                log.info("Аккаунт {} уже есть в базе — токены из окружения игнорируются", chatId);
                return false;
            }
            LegacyTokens tokens = legacyTokens();
            LegacyBoard board = legacyBoard();
            return repository.importApproved(chatId, tokens.access, tokens.refresh, tokens.expiresAt,
                    board.statusMessageId, board.alertMessageId, board.alertKind);
        });
        if (Boolean.TRUE.equals(created)) {
            log.info("Владелец {} импортирован из прежней конфигурации", chatId);
            if (chatId < 0) {
                log.warn("Чат {} — группа: мониторинг в ней продолжится, но команды бот принимает только в личке",
                        chatId);
            }
        }
        return Boolean.TRUE.equals(created);
    }

    /** Токены как раньше в TokenProvider: файл на volume свежее окружения. */
    private LegacyTokens legacyTokens() {
        MonitorProperties.Anthropic cfg = properties.getAnthropic();
        LegacyTokens tokens = new LegacyTokens(cfg.getAccessToken().trim(), cfg.getRefreshToken().trim(), null);
        JsonNode json = readJson(cfg.getTokenFile());
        if (json == null) {
            return tokens;
        }
        String storedAccess = json.path("accessToken").asString("");
        String storedRefresh = json.path("refreshToken").asString("");
        long epoch = json.path("expiresAt").asLong(0);
        return new LegacyTokens(
                StringUtils.hasText(storedAccess) ? storedAccess : tokens.access,
                StringUtils.hasText(storedRefresh) ? storedRefresh : tokens.refresh,
                epoch > 0 ? Instant.ofEpochSecond(epoch) : null);
    }

    private LegacyBoard legacyBoard() {
        JsonNode json = readJson(properties.getTelegram().getStateFile());
        if (json == null) {
            return new LegacyBoard(null, null, null);
        }
        Long status = positive(json.path("statusMessageId").asLong(0));
        Long alert = positive(json.path("alertMessageId").asLong(0));
        StatusBoard.AlertKind kind = null;
        try {
            String value = json.path("alertKind").asString("");
            kind = StringUtils.hasText(value) ? StatusBoard.AlertKind.valueOf(value) : null;
        } catch (IllegalArgumentException e) {
            log.debug("Неизвестный alertKind в board.json");
        }
        return new LegacyBoard(status, alert, kind);
    }

    private JsonNode readJson(String file) {
        if (!StringUtils.hasText(file)) {
            return null;
        }
        Path path = Path.of(file);
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            return objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException | JacksonException e) {
            log.warn("Не удалось прочитать {}: {}", path, e.toString());
            return null;
        }
    }

    private static Long positive(long value) {
        return value > 0 ? value : null;
    }

    private record LegacyTokens(String access, String refresh, Instant expiresAt) {
    }

    private record LegacyBoard(Long statusMessageId, Long alertMessageId, StatusBoard.AlertKind alertKind) {
    }
}
