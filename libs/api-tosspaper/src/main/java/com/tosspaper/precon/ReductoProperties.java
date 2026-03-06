package com.tosspaper.precon;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe, startup-validated configuration for the Reducto HTTP client and
 * the extraction job seeder.
 *
 * <p>Bound to the {@code reducto.*} namespace. Override in tests via
 * {@code @TestPropertySource} or a dedicated {@code application-test.properties}.
 *
 * <p>Example {@code application.yml}:
 * <pre>
 * reducto:
 *   base-url: "https://api.reducto.ai"
 *   api-key: "${REDUCTO_API_KEY}"
 *   webhook-base-url: "https://my-service.example.com"
 *   webhook-path: "/internal/reducto/webhook"
 *   document-cap: 20
 *   task-timeout-minutes: 15
 *   timeout-seconds: 30
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "reducto")
@Validated
public class ReductoProperties {

    /** Reducto API base URL (no trailing slash). */
    @NotBlank
    private String baseUrl;

    /** Reducto API key injected from the environment. */
    @NotBlank
    private String apiKey;

    /**
     * Public base URL of this service, used to build the {@code webhookUrl}
     * sent with each Reducto submission.
     */
    @NotBlank
    private String webhookBaseUrl;

    /**
     * Path segment appended to {@link #webhookBaseUrl} to form the full webhook URL.
     * Defaults to {@code /internal/reducto/webhook}.
     */
    private String webhookPath = "/internal/reducto/webhook";

    /**
     * Maximum number of documents that may be submitted to Reducto per extraction.
     * Hard cap aligned with the 20-document-per-extraction limit enforced at creation.
     * Defaults to {@code 20}.
     */
    @Positive
    private int documentCap = 20;

    /**
     * Age threshold (in minutes) after which a Reducto task is considered timed out.
     * Extractions stuck in {@code PROCESSING} longer than this are reset to {@code PENDING}.
     * Defaults to {@code 15}.
     */
    @Positive
    private int taskTimeoutMinutes = 15;

    /**
     * HTTP request timeout in seconds for each Reducto API call.
     * Defaults to {@code 30}.
     */
    @Positive
    private int timeoutSeconds = 30;

    /** Returns the full webhook URL by concatenating base URL and path. */
    public String buildWebhookUrl() {
        return webhookBaseUrl + webhookPath;
    }
}
