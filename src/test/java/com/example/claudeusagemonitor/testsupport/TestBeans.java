package com.example.claudeusagemonitor.testsupport;

import com.example.claudeusagemonitor.config.MonitorProperties;
import com.example.claudeusagemonitor.usage.LimitWindow;
import com.example.claudeusagemonitor.usage.TokenProvider;
import com.example.claudeusagemonitor.usage.UsageClient;
import com.example.claudeusagemonitor.usage.UsageSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Общая конфигурация интеграционных тестов: настоящий PostgreSQL в контейнере и фейки
 * вместо всего, что ходит в сеть (Telegram, обмен токена, API лимитов).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    /** Тестовый ключ шифрования токенов: base64 от 32 байт «0123456789abcdef» ×2. Только для тестов. */
    public static final String TOKEN_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @Primary
    FakeTelegramClient fakeTelegramClient() {
        return new FakeTelegramClient();
    }

    @Bean
    @Primary
    FakeTokenProvider fakeTokenProvider() {
        return new FakeTokenProvider();
    }

    @Bean
    @Primary
    FakeUsageClient fakeUsageClient() {
        return new FakeUsageClient();
    }

    /** Обмен refresh token без сети; поведение задаёт тест. */
    public static class FakeTokenProvider extends TokenProvider {
        public final List<String> exchanged = new ArrayList<>();
        public volatile Function<String, TokenGrant> behaviour =
                token -> new TokenGrant("access-" + token, "rotated-" + token, Instant.now().plus(Duration.ofHours(8)));

        public FakeTokenProvider() {
            super(new MonitorProperties(), null, null, null);
        }

        @Override
        public synchronized TokenGrant exchange(String refreshToken) {
            exchanged.add(refreshToken);
            return behaviour.apply(refreshToken);
        }
    }

    /** Пробный запрос лимитов без сети. */
    public static class FakeUsageClient extends UsageClient {
        public volatile Function<String, UsageSnapshot> behaviour = token -> snapshot(42);

        public FakeUsageClient() {
            super(new MonitorProperties(), null, null, null);
        }

        @Override
        public UsageSnapshot fetch(String accessToken) {
            return behaviour.apply(accessToken);
        }

        @Override
        public UsageSnapshot fetch(com.example.claudeusagemonitor.account.AccountSession session) {
            return behaviour.apply(session.tokens().accessToken());
        }

        public static UsageSnapshot snapshot(double percent) {
            return new UsageSnapshot(new LimitWindow(percent, Instant.now().plus(Duration.ofHours(2)), null),
                    null, Instant.now());
        }
    }
}
