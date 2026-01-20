package com.tosspaper.models.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "http.security.jwt")
public class JWTTokenProperties {
    private String issuer;
    private String audience;
    private String jwkSetUri;
    private String type = "at+jwt";
}
