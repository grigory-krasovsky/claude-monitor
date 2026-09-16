package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.config.MonitorProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Держит в чате ровно два сообщения бота: живой статус и, при необходимости, алерт.
 *
 * <p>Статус переписывается на месте вместо отправки нового сообщения и всегда остаётся
 * последним в чате: когда появляется алерт, статус удаляется и создаётся заново уже
 * под ним. Предыдущий алерт при этом тоже удаляется — так пороги дают уведомление,
 * но не копятся лентой.
 *
 * <p>Все методы синхронизированы: отрисовка идёт из планировщика раз в 15 секунд,
 * а команды бота приходят из потока long polling.
 */
@Component
public class StatusBoard {

    private static final Logger log = LoggerFactory.getLogger(StatusBoard.class);

    private final MonitorProperties properties;
    private final TelegramClient telegramClient;
    private final ObjectMapper objectMapper;

    private Long statusMessageId;
    private Long alertMessageId;
    private AlertKind alertKind;
    /** Последний отрисованный текст: если он не изменился, правку в Telegram не шлём. */
    private String renderedStatus;

    public StatusBoard(MonitorProperties properties, TelegramClient telegramClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.telegramClient = telegramClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        Path path = stateFile();
        if (path == null || !Files.isReadable(path)) {
            return;
        }
        try {
            JsonNode json = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            statusMessageId = json.path("statusMessageId").asLong(0) > 0
                    ? json.path("statusMessageId").asLong() : null;
            alertMessageId = json.path("alertMessageId").asLong(0) > 0
                    ? json.path("alertMessageId").asLong() : null;
            String kind = json.path("alertKind").asString("");
            alertKind = StringUtils.hasText(kind) ? AlertKind.valueOf(kind) : null;
            log.info("Состояние сообщений восстановлено: статус {}, алерт {}", statusMessageId, alertMessageId);
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            log.warn("Не удалось прочитать {}: {}", path, e.toString());
        }
    }

    private void save() {
        Path path = stateFile();
        if (path == null) {
            return;
        }
        try {
            ObjectNode json = objectMapper.createObjectNode();
            json.put("statusMessageId", statusMessageId == null ? 0 : statusMessageId);
            json.put("alertMessageId", alertMessageId == null ? 0 : alertMessageId);
            json.put("alertKind", alertKind == null ? "" : alertKind.name());
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, objectMapper.writeValueAsString(json), StandardCharsets.UTF_8);
        } catch (IOException | JacksonException e) {
            log.warn("Не удалось сохранить {}: {}", path, e.toString());
        }
    }

    private Path stateFile() {
        String file = properties.getTelegram().getStateFile();
        return StringUtils.hasText(file) ? Path.of(file) : null;
    }

    /** Повод для алерта. Нужен, чтобы восстановление после сбоя не стирало алерт о пороге. */
    public enum AlertKind {
        THRESHOLD,
        FAILURE
    }

    /** Обновляет статус на месте, создавая сообщение при первом вызове. */
    public synchronized void render(String statusText) {
        String chatId = chatId();
        if (chatId == null) {
            return;
        }
        if (statusMessageId == null) {
            createStatus(chatId, statusText);
            return;
        }
        if (statusText.equals(renderedStatus)) {
            return;
        }
        if (telegramClient.editMessage(chatId, statusMessageId, statusText)) {
            renderedStatus = statusText;
        } else {
            // Сообщение пропало (удалено вручную, чат очищен) — заводим новое
            log.info("Статусное сообщение недоступно, создаю заново");
            createStatus(chatId, statusText);
        }
    }

    /**
     * Поднимает алерт: убирает предыдущий алерт и статус, публикует новый алерт
     * и сразу под ним — свежий статус.
     */
    public synchronized void raise(AlertKind kind, String alertText, String statusText) {
        String chatId = chatId();
        if (chatId == null) {
            return;
        }
        dropStatus(chatId);
        dropAlert(chatId);

        alertMessageId = telegramClient.sendMessage(chatId, alertText);
        alertKind = alertMessageId == null ? null : kind;
        createStatus(chatId, statusText);
    }

    /** Убирает алерт, если он висит и относится к указанному поводу. */
    public synchronized void clear(AlertKind kind) {
        String chatId = chatId();
        if (chatId == null || alertMessageId == null || alertKind != kind) {
            return;
        }
        log.info("Убираю алерт ({})", kind);
        dropAlert(chatId);
        save();
    }

    /** Пересоздаёт статус, чтобы он снова оказался последним сообщением в чате. */
    public synchronized void repost(String statusText) {
        String chatId = chatId();
        if (chatId == null) {
            return;
        }
        dropStatus(chatId);
        createStatus(chatId, statusText);
    }

    private void createStatus(String chatId, String statusText) {
        statusMessageId = telegramClient.sendMessage(chatId, statusText);
        renderedStatus = statusMessageId == null ? null : statusText;
        save();
    }

    private void dropStatus(String chatId) {
        if (statusMessageId != null) {
            telegramClient.deleteMessage(chatId, statusMessageId);
            statusMessageId = null;
            renderedStatus = null;
        }
    }

    private void dropAlert(String chatId) {
        if (alertMessageId != null) {
            telegramClient.deleteMessage(chatId, alertMessageId);
            alertMessageId = null;
            alertKind = null;
        }
    }

    private String chatId() {
        String chatId = properties.getTelegram().getChatId();
        return StringUtils.hasText(chatId) ? chatId : null;
    }
}
