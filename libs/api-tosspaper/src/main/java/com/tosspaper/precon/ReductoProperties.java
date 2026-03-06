package com.tosspaper.precon;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@ConfigurationProperties(prefix = "reducto")
@Validated
public class ReductoProperties {

    @NotBlank
    private String baseUrl;

    @NotBlank
    private String apiKey;

    @NotBlank
    private String webhookBaseUrl;

    private String webhookPath = "/internal/reducto/webhook";

    /** Svix channel name for async webhook delivery — passed as the webhook channel in extract requests. */
    @NotBlank
    private String svixChannel;

    @Positive
    private int taskTimeoutMinutes = 15;

    @Positive
    private int timeoutSeconds = 30;

    /** Returns the full webhook URL by concatenating base URL and path. */
    public String buildWebhookUrl() {
        return webhookBaseUrl + webhookPath;
    }
}
