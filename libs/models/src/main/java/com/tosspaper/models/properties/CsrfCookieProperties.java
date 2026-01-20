package com.tosspaper.models.properties;

import lombok.Data;
import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "http.security.csrf.cookie")
@Validated
@Data
public class CsrfCookieProperties {
    private boolean httpOnly = true;
    private boolean secure = true;
    private SameSite sameSite = SameSite.STRICT;
    private Duration maxAge = Duration.ofHours(24);
    private String domain;
    private String path = "/";
    private String cookieName = "X-CSRF-TOKEN";

    @Getter
    public enum SameSite {
        STRICT("Strict"),
        LAX("Lax"),
        NONE("None");

        private final String value;

        SameSite(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
