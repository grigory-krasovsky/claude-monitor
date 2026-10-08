package com.example.claudeusagemonitor.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Единая конфигурация для всех Spring-тестов: один кешируемый контекст — один контейнер
 * PostgreSQL на весь прогон. Интервалы задраны, чтобы планировщик не вмешивался в тесты.
 * Ключ шифрования токенов — фиксированный тестовый {@link TestBeans#TOKEN_KEY}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "monitor.poll-interval=1h",
        "monitor.render-interval=1h",
        "monitor.telegram.token=",
        "monitor.telegram.chat-id=",
        "monitor.anthropic.access-token=",
        "monitor.anthropic.refresh-token=",
        "monitor.anthropic.token-file=",
        "monitor.telegram.state-file=",
        "monitor.token-encryption-key=" + TestBeans.TOKEN_KEY
})
@Import(TestBeans.class)
public @interface IntegrationTest {
}
