package com.tosspaper.precon;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound to {@code reducto.webhook.*} — holds the Svix signing secret. */
@Getter
@Setter
@ConfigurationProperties(prefix = "reducto.webhook")
public class ReductoWebhookProperties {

    /** Svix webhook signing secret. When blank, signature verification is skipped. */
    private String svixSecret = "";
}
