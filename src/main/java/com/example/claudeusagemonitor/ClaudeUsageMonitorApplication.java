package com.example.claudeusagemonitor;

import com.example.claudeusagemonitor.config.MonitorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MonitorProperties.class)
public class ClaudeUsageMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClaudeUsageMonitorApplication.class, args);
    }

}
