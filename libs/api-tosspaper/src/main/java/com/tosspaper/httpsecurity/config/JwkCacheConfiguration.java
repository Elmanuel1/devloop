package com.tosspaper.httpsecurity.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tosspaper.models.properties.JwkCacheProperties;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwkCacheConfiguration {

    @Bean
    public org.springframework.cache.Cache jwkCache(JwkCacheProperties jwkCacheProperties) {
        Cache<Object, Object> caffeineCache = Caffeine.newBuilder()
                .expireAfterWrite(jwkCacheProperties.getExpireAfterWrite())
                .maximumSize(jwkCacheProperties.getMaximumSize())
                .build();

        return new CaffeineCache("jwks", caffeineCache);
    }
}

