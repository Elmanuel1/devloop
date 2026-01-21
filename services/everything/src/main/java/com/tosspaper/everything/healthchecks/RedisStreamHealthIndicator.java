package com.tosspaper.everything.healthchecks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Health indicator for Redis Stream listeners.
 * Reports status of asynchronous listener initialization.
 */
@Slf4j
@Component("redisStreamListeners")
@RequiredArgsConstructor
public class RedisStreamHealthIndicator implements HealthIndicator {

    private final RedisTemplate<String, String> redisTemplate;
    private final StreamHealthService healthService;

    @Override
    public Health health() {
        try {
            // Check if Redis is available
            var connectionFactory = redisTemplate.getConnectionFactory();
            if (connectionFactory == null) {
                return Health.down()
                    .withDetail("error", "Redis connection factory unavailable")
                    .build();
            }
            try (var connection = connectionFactory.getConnection()) {
                connection.ping();
            }
            
            // Get status from the health service (no proxy issues)
            boolean initialized = healthService.isInitialized();
            
            // Get status from the health service (no proxy issues)
            boolean initialized = healthService.isInitialized();
            int totalConsumers = healthService.getTotalConsumers();
            int registeredConsumers = healthService.getRegisteredConsumers();
            
            if (!initialized) {
                return Health.up()
                    .withDetail("status", "initializing")
                    .withDetail("message", "Stream consumers are initializing asynchronously")
                    .withDetail("registered", registeredConsumers)
                    .withDetail("total", totalConsumers)
                    .build();
            }
            
            if (registeredConsumers < totalConsumers) {
                return Health.down()
                    .withDetail("status", "partial")
                    .withDetail("registered", registeredConsumers)
                    .withDetail("total", totalConsumers)
                    .withDetail("message", "Some consumers failed to register")
                    .build();
            }
            
            return Health.up()
                .withDetail("status", "ready")
                .withDetail("consumers", registeredConsumers)
                .withDetail("message", "All stream consumers active")
                .build();
                
        } catch (Exception e) {
            return Health.down()
                .withDetail("error", "Redis unavailable")
                .withDetail("initialized", healthService.isInitialized())
                .withDetail("registered", healthService.getRegisteredConsumers())
                .withDetail("total", healthService.getTotalConsumers())
                .build();
        }
    }
}