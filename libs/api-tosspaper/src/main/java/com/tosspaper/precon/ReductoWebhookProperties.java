package com.tosspaper.precon;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration for the Reducto webhook endpoint.
 *
 * <p>Bound to the {@code reducto.webhook.*} namespace. The Svix signing secret
 * must be present at startup — a blank value will fail context initialisation.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * reducto:
 *   webhook:
 *     svix-secret: "${REDUCTO_WEBHOOK_SVIX_SECRET}"
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "reducto.webhook")
@Validated
public class ReductoWebhookProperties {

    /**
     * Svix signing secret used to verify inbound webhook signatures.
     *
     * <p>Injected from the {@code REDUCTO_WEBHOOK_SVIX_SECRET} environment variable
     * via Spring's property placeholder resolution. Must not be blank.
     */
    @NotBlank
    private String svixSecret;
}
