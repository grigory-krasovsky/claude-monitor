package com.example.claudeusagemonitor.testsupport;

import com.example.claudeusagemonitor.account.AccountRepository;
import com.example.claudeusagemonitor.account.AccountSession;
import com.example.claudeusagemonitor.account.AccountSessions;
import com.example.claudeusagemonitor.telegram.CommandService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** База интеграционных тестов: чистая таблица, пустой реестр сессий и сброшенные фейки перед каждым тестом. */
@IntegrationTest
public abstract class DatabaseTest {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected AccountRepository repository;

    @Autowired
    protected AccountSessions sessions;

    @Autowired
    protected CommandService commands;

    @Autowired
    protected FakeTelegramClient telegram;

    @Autowired
    protected TestBeans.FakeTokenProvider tokenProvider;

    @Autowired
    protected TestBeans.FakeUsageClient usageClient;

    @BeforeEach
    void cleanState() {
        sessions.all().stream().map(AccountSession::chatId).forEach(sessions::detach);
        jdbc.sql("delete from account").update();
        jdbc.sql("delete from legacy_import").update();
        telegram.reset();
        tokenProvider.exchanged.clear();
        tokenProvider.behaviour = token -> new com.example.claudeusagemonitor.usage.TokenProvider.TokenGrant(
                "access-" + token, "rotated-" + token, java.time.Instant.now().plusSeconds(8 * 3600));
        usageClient.behaviour = token -> TestBeans.FakeUsageClient.snapshot(42);
    }

    /** Отправляет команду от имени пользователя в личке. */
    protected void send(long userId, String text) {
        commands.handle(Updates.privateMessage(userId, text));
    }
}
