package com.tosspaper.aiengine.config;

import com.tosspaper.aiengine.properties.HttpProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration for HTTP clients.
 * Provides reusable HTTP client configurations for external API integrations.
 */
@Slf4j
@Configuration
public class HttpClientConfig {
    
    /**
     * Create a configured OkHttpClient for AI API.
     * 
     * @param properties HTTP configuration properties
     * @return configured OkHttpClient
     */
    @Bean("httpClient")
    public OkHttpClient httpClient(HttpProperties properties) {
        log.info("Creating HTTP client with timeouts: connect={}s, read={}s, write={}s",
                properties.getConnectTimeoutSeconds(),
                properties.getReadTimeoutSeconds(),
                properties.getWriteTimeoutSeconds());
        
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
            .connectTimeout(properties.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
            .readTimeout(properties.getReadTimeoutSeconds(), TimeUnit.SECONDS)
            .writeTimeout(properties.getWriteTimeoutSeconds(), TimeUnit.SECONDS);
        
        if (properties.getEnableLogging()) {
            HttpLoggingInterceptor.Level level = parseLoggingLevel(properties.getLoggingLevel());
            builder.addInterceptor(new HttpLoggingInterceptor(log::debug).setLevel(level));
            log.info("HTTP logging enabled with level: {}", level);
        }
        
        return builder.build();
    }
    
    /**
     * Create a default HTTP client with standard timeouts.
     * 
     * @return configured OkHttpClient with default settings
     */
    @Bean("defaultHttpClient")
    public OkHttpClient defaultHttpClient() {
        log.info("Creating default HTTP client with standard timeouts");
        
        return new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(new HttpLoggingInterceptor(log::debug).setLevel(HttpLoggingInterceptor.Level.BASIC))
            .build();
    }
    
    /**
     * Parse logging level string to HttpLoggingInterceptor.Level.
     * 
     * @param level the logging level string
     * @return corresponding HttpLoggingInterceptor.Level
     */
    private HttpLoggingInterceptor.Level parseLoggingLevel(String level) {
        return switch (level.toUpperCase()) {
            case "NONE" -> HttpLoggingInterceptor.Level.NONE;
            case "BASIC" -> HttpLoggingInterceptor.Level.BASIC;
            case "HEADERS" -> HttpLoggingInterceptor.Level.HEADERS;
            case "BODY" -> HttpLoggingInterceptor.Level.BODY;
            default -> {
                log.warn("Unknown logging level: {}, using BODY", level);
                yield HttpLoggingInterceptor.Level.NONE;
            }
        };
    }
}
