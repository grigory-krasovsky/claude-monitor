package com.example.claudeusagemonitor.config;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки монитора. Значения по умолчанию заданы в application.properties,
 * в проде переопределяются переменными окружения (см. .env.example).
 */
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    private Anthropic anthropic = new Anthropic();
    private Telegram telegram = new Telegram();

    /** Пороги утилизации в процентах, на которых шлётся алерт. */
    private List<Integer> thresholds = List.of(50, 75, 90, 95);

    /** Часовой пояс для отображения времени сброса окна. */
    private ZoneId timezone = ZoneId.of("Europe/Moscow");

    /**
     * Ключ AES-256 для токенов в базе: base64 от ровно 32 байт (см. {@code TokenCipher}).
     * Значения по умолчанию нет намеренно — без ключа приложение не стартует. Не выводится
     * ни в логи, ни в сообщения об ошибках.
     */
    private String tokenEncryptionKey = "";

    public Anthropic getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(Anthropic anthropic) {
        this.anthropic = anthropic;
    }

    public Telegram getTelegram() {
        return telegram;
    }

    public void setTelegram(Telegram telegram) {
        this.telegram = telegram;
    }

    public List<Integer> getThresholds() {
        return thresholds;
    }

    public void setThresholds(List<Integer> thresholds) {
        this.thresholds = thresholds;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public void setTimezone(ZoneId timezone) {
        this.timezone = timezone;
    }

    public String getTokenEncryptionKey() {
        return tokenEncryptionKey;
    }

    public void setTokenEncryptionKey(String tokenEncryptionKey) {
        this.tokenEncryptionKey = tokenEncryptionKey;
    }

    /**
     * Доступ к недокументированному эндпоинту Anthropic /api/oauth/usage.
     *
     * <p>Токены аккаунтов теперь живут в базе и приходят через /token. Поля ниже — прежняя
     * однопользовательская конфигурация: из неё один раз импортируется владелец.
     */
    public static class Anthropic {

        /**
         * OAuth access token (sk-ant-oat01-...) владельца — только для импорта. Токен из
         * {@code claude setup-token} сюда не годится: у него лишь scope user:inference,
         * и эндпоинт лимитов отвечает ему 403 «does not meet scope requirement user:profile».
         */
        private String accessToken = "";

        /**
         * Refresh token (sk-ant-ort01-...) владельца — только для импорта. Брать из отдельного
         * логина ({@code CLAUDE_CONFIG_DIR=~/.claude-monitor claude login}, файл
         * {@code .credentials.json} в этом каталоге): бот ротирует токен при каждом обмене,
         * и копия из основного ~/.claude разлогинила бы локальный Claude Code.
         */
        private String refreshToken = "";

        /** Прежний файл с ротированными токенами на volume — свежее окружения, читается при импорте. */
        private String tokenFile = "/data/tokens.json";

        /** User-Agent обязателен, иначе эндпоинт быстро упирается в rate limit. */
        private String userAgent = "claude-code/2.1.220";

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    /** Настройки бота. */
    public static class Telegram {

        /** Токен бота от @BotFather. */
        private String token = "";

        /**
         * Чат владельца из однопользовательской версии. Нужен только для однократного
         * импорта в базу (см. {@code OwnerImporter}); без него приложение тоже стартует.
         */
        private String chatId = "";

        /**
         * Прежний файл с идентификаторами статусного сообщения и алерта. Читается при
         * импорте владельца, чтобы бот продолжил править то же сообщение, а не бросил
         * его в чате мёртвым, создав рядом новое. Дальше id сообщений хранятся в базе.
         */
        private String stateFile = "/data/board.json";

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getChatId() {
            return chatId;
        }

        public void setChatId(String chatId) {
            this.chatId = chatId;
        }

        public String getStateFile() {
            return stateFile;
        }

        public void setStateFile(String stateFile) {
            this.stateFile = stateFile;
        }
    }
}
