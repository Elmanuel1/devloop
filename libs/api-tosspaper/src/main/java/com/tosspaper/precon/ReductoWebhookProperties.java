package com.tosspaper.precon;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Bound to {@code reducto.webhook.*} — holds the Svix signing secret. */
@Getter
@Setter
@ConfigurationProperties(prefix = "reducto.webhook")
@Validated
public class ReductoWebhookProperties {

    @NotBlank
    private String svixSecret;
}
