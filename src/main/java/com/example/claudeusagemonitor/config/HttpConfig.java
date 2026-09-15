package com.example.claudeusagemonitor.config;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/** Один общий HTTP-клиент и один ObjectMapper на всё приложение. */
@Configuration
public class HttpConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpConfig.class);

    /**
     * HTTP-прокси вида {@code http://host:port}. Пусто — идём напрямую.
     * Java, в отличие от curl, переменную HTTPS_PROXY сама не читает, поэтому
     * значение прокидывается явно через настройку.
     */
    @Value("${monitor.proxy:}")
    private String proxy;

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public HttpClient httpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL);
        proxySelector().ifPresent(builder::proxy);
        return builder.build();
    }

    private Optional<ProxySelector> proxySelector() {
        if (!StringUtils.hasText(proxy)) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(proxy.trim());
            int port = uri.getPort() > 0 ? uri.getPort() : 3128;
            log.info("Исходящие запросы идут через прокси {}:{}", uri.getHost(), port);
            return Optional.of(ProxySelector.of(new InetSocketAddress(uri.getHost(), port)));
        } catch (RuntimeException e) {
            log.error("Не удалось разобрать monitor.proxy='{}', иду напрямую: {}", proxy, e.toString());
            return Optional.empty();
        }
    }
}
