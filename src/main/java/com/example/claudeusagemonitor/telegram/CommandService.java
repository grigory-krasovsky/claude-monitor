package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.alert.AlertService;
import com.example.claudeusagemonitor.config.MonitorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/** Обработка команд бота. */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private static final String HELP = """
            <b>Claude Usage Monitor</b>

            /status — пересоздать статусное сообщение
            /chatid — идентификатор этого чата
            /help — эта справка

            Статус обновляется сам каждые 15 секунд и всегда остаётся последним
            сообщением в чате. При пересечении порогов утилизации приходит алерт,
            предыдущий при этом убирается.""";

    private final MonitorProperties properties;
    private final AlertService alertService;
    private final TelegramClient telegramClient;

    public CommandService(MonitorProperties properties, AlertService alertService, TelegramClient telegramClient) {
        this.properties = properties;
        this.alertService = alertService;
        this.telegramClient = telegramClient;
    }

    /** Разбирает апдейт Telegram и отвечает на известные команды. */
    public void handle(JsonNode update) {
        JsonNode message = update.path("message");
        String text = message.path("text").asString("").trim();
        String chatId = message.path("chat").path("id").asString("");
        if (!StringUtils.hasText(text) || !StringUtils.hasText(chatId)) {
            return;
        }
        // В группах команды приходят как /status@my_bot — суффикс отбрасываем.
        String command = text.split("\\s+")[0].split("@")[0].toLowerCase();
        log.info("Команда {} из чата {}", command, chatId);

        switch (command) {
            case "/status" -> status(chatId);
            case "/chatid" -> telegramClient.sendMessage(chatId, chatIdReply(chatId));
            case "/start", "/help" -> telegramClient.sendMessage(chatId, HELP);
            default -> { /* прочие сообщения игнорируем */ }
        }
    }

    /**
     * В основном чате команда пересоздаёт живое статусное сообщение, чтобы оно
     * снова оказалось последним. В остальных чатах отвечаем разовым снимком:
     * вести там самообновляющееся сообщение незачем.
     */
    private void status(String chatId) {
        if (chatId.equals(properties.getTelegram().getChatId())) {
            alertService.repostStatus();
        } else {
            telegramClient.sendMessage(chatId, alertService.statusText());
        }
    }

    private String chatIdReply(String chatId) {
        String configured = properties.getTelegram().getChatId();
        String suffix = chatId.equals(configured)
                ? "\n\n✅ Это чат для алертов."
                : "\n\nЧтобы слать алерты сюда, задайте <code>TELEGRAM_CHAT_ID=%s</code> и перезапустите контейнер."
                        .formatted(chatId);
        return "ID чата: <code>%s</code>%s".formatted(chatId, suffix);
    }
}
