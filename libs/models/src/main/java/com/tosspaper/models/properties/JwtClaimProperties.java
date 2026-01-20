package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "http.security.jwt.claims")
public class JwtClaimProperties {
    private String emailVerified = "email_verified";
    private EmailVerification emailVerification = new EmailVerification();

    @Data
    public static class EmailVerification {
        private boolean enabled = false;
    }
} 