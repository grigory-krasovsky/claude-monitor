package com.example.claudeusagemonitor.telegram;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Читает апдейты бота в отдельном потоке (long polling).
 *
 * <p>Поток не-демон, поэтому именно он удерживает приложение живым: веб-сервера
 * в сборке нет. При старте накопившиеся за простой апдейты отбрасываются, чтобы
 * бот не отвечал на команды недельной давности.
 */
@Component
public class BotPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotPoller.class);

    /** Пауза после сбоя, чтобы не спамить API при недоступной сети. */
    private static final long ERROR_BACKOFF_SECONDS = 5;

    private final TelegramClient telegramClient;
    private final CommandService commandService;

    private volatile boolean running;
    private Thread thread;

    public BotPoller(TelegramClient telegramClient, CommandService commandService) {
        this.telegramClient = telegramClient;
        this.commandService = commandService;
    }

    @Override
    public void start() {
        if (!telegramClient.isConfigured()) {
            log.warn("Токен Telegram не задан — опрос апдейтов не запущен");
            return;
        }
        telegramClient.registerCommands();
        running = true;
        thread = new Thread(this::pollLoop, "telegram-poller");
        thread.setDaemon(false);
        thread.start();
        log.info("Опрос Telegram запущен");
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void pollLoop() {
        long offset = skipBacklog();
        while (running) {
            try {
                List<JsonNode> updates = telegramClient.getUpdates(offset);
                for (JsonNode update : updates) {
                    offset = update.path("update_id").asLong() + 1;
                    try {
                        commandService.handle(update);
                    } catch (RuntimeException e) {
                        log.error("Ошибка обработки апдейта: {}", e.toString());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Ошибка опроса Telegram: {}", e.toString());
                if (!sleepQuietly()) {
                    return;
                }
            }
        }
        log.info("Опрос Telegram остановлен");
    }

    /** Возвращает offset, с которого начинать, отбросив всё, что накопилось до старта. */
    private long skipBacklog() {
        try {
            List<JsonNode> pending = telegramClient.getUpdates(-1);
            if (!pending.isEmpty()) {
                return pending.get(pending.size() - 1).path("update_id").asLong() + 1;
            }
        } catch (RuntimeException e) {
            log.warn("Не удалось пропустить старые апдейты: {}", e.toString());
        }
        return 0;
    }

    private boolean sleepQuietly() {
        try {
            TimeUnit.SECONDS.sleep(ERROR_BACKOFF_SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
