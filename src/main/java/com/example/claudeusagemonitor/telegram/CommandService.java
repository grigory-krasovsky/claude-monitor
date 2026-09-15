package com.example.claudeusagemonitor.telegram;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.usage.UsageClient;
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

            /status — текущая утилизация лимитов
            /chatid — идентификатор этого чата
            /help — эта справка

            Алерты приходят автоматически при пересечении порогов утилизации.""";

    private final MonitorProperties properties;
    private final UsageClient usageClient;
    private final TelegramClient telegramClient;
    private final MessageFormatter formatter;

    public CommandService(MonitorProperties properties, UsageClient usageClient,
                          TelegramClient telegramClient, MessageFormatter formatter) {
        this.properties = properties;
        this.usageClient = usageClient;
        this.telegramClient = telegramClient;
        this.formatter = formatter;
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
            case "/status" -> telegramClient.sendMessage(chatId, status());
            case "/chatid" -> telegramClient.sendMessage(chatId, chatIdReply(chatId));
            case "/start", "/help" -> telegramClient.sendMessage(chatId, HELP);
            default -> { /* прочие сообщения игнорируем */ }
        }
    }

    private String status() {
        try {
            return formatter.status(usageClient.fetch());
        } catch (RuntimeException e) {
            log.error("Не удалось получить лимиты: {}", e.toString());
            return "⚠️ Не удалось получить данные: " + e.getMessage();
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
