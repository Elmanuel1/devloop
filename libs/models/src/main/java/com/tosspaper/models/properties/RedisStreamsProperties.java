package com.tosspaper.models.properties;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "redis.streams")
@Data
@Validated
@Slf4j
public class RedisStreamsProperties {

    @Valid
    private Map<String, Map<String, GroupConfig>> config = new HashMap<>();

    @PostConstruct
    public void logConfig() {
        log.info("=== Redis Streams Configuration ===");
        log.info("Loaded streams: {}", config);
    }

    // Add convenience method so you can still call getStreams()
    public Map<String, Map<String, GroupConfig>> getStreams() {
        return config;
    }

    @Data
    @Validated
    public static class GroupConfig {
        @NotEmpty
        private String listener;

        @NotEmpty
        private List<String> consumers;
    }
}