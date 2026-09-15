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

    /** Слать ли сообщение о старте монитора. */
    private boolean notifyOnStart = true;

    /** Слать ли сообщение о сбросе пятичасового окна. */
    private boolean notifyOnReset = true;

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

    public boolean isNotifyOnStart() {
        return notifyOnStart;
    }

    public void setNotifyOnStart(boolean notifyOnStart) {
        this.notifyOnStart = notifyOnStart;
    }

    public boolean isNotifyOnReset() {
        return notifyOnReset;
    }

    public void setNotifyOnReset(boolean notifyOnReset) {
        this.notifyOnReset = notifyOnReset;
    }

    /** Доступ к недокументированному эндпоинту Anthropic /api/oauth/usage. */
    public static class Anthropic {

        /** OAuth access token (sk-ant-oat01-...). Проще всего получить через `claude setup-token`. */
        private String accessToken = "";

        /** Необязательный refresh token (sk-ant-ort01-...) для самостоятельного обновления access token. */
        private String refreshToken = "";

        /** Файл, куда сохраняются обновлённые токены (должен лежать на volume). */
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

        /** Чат, куда шлются алерты. Если пусто — алерты не отправляются, но команды работают. */
        private String chatId = "";

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
    }
}
