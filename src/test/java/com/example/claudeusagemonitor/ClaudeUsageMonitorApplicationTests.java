package com.example.claudeusagemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.claudeusagemonitor.testsupport.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class ClaudeUsageMonitorApplicationTests {

    @Autowired
    private JdbcClient jdbc;

    @Test
    void contextLoads() {
        // Контекст поднялся без TELEGRAM_CHAT_ID и ANTHROPIC_*, Flyway накатил схему
        assertThat(jdbc.sql("select count(*) from account").query(Long.class).single()).isNotNull();
    }

}
