package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@Data
@Validated
@ConfigurationProperties(prefix = "http.security.jwt.cache")
public class JwkCacheProperties {
    
    @NotNull
    private Duration expireAfterWrite = Duration.ofDays(3);
    
    @NotNull
    @Min(1)
    private Long maximumSize = 100L;
}

