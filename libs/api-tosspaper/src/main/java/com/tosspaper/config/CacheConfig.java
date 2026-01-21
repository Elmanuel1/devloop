package com.tosspaper.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis cache configuration supporting multiple caches with different TTLs.
 * <p>
 * Usage examples:
 * <p>
 * 1. User roles (5-minute TTL for security):
 *    @Cacheable(value = "user-roles", key = "#email + '::' + #companyId")
 * <p>
 * 2. Company data (1-hour TTL):
 *    @Cacheable(value = "company-data", key = "#companyId")
 *
 * 3. Other caches use default 10-minute TTL
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Default cache configuration for unconfigured caches.
     * TTL: 10 minutes
     * Serialization: JSON for complex objects
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))  // Default: 10 minutes
            .disableCachingNullValues()         // Don't cache null values
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new StringRedisSerializer()
                )
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer()
                )
            );
    }

    /**
     * Customize individual caches with specific TTLs and settings.
     * Add new cache configurations here as needed.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return (builder) -> builder
            // User roles cache: 5-minute TTL (short for security - immediate revocation)
            .withCacheConfiguration("user-roles",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(5))
                    .disableCachingNullValues()
                    .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                            new StringRedisSerializer()  // Simple string values (role IDs)
                        )
                    )
            );
    }
}
