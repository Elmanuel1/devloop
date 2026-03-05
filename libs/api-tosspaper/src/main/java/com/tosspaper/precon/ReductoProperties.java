package com.tosspaper.precon;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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
 *   batch-size: 20
 *   stale-minutes: 15
 * </pre>
 */
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
     * Maximum number of documents claimed from the DB in a single seeder cycle.
     * Hard cap aligned with the 20-document-per-batch limit enforced at creation.
     * Defaults to {@code 20}.
     */
    @Positive
    private int batchSize = 20;

    /**
     * Age threshold (in minutes) for the reaper.
     * Extractions stuck in {@code PROCESSING} longer than this are reset to {@code PENDING}.
     * Defaults to {@code 15}.
     */
    @Positive
    private int staleMinutes = 15;

    // ── Getters & setters ─────────────────────────────────────────────────────

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWebhookBaseUrl() { return webhookBaseUrl; }
    public void setWebhookBaseUrl(String webhookBaseUrl) { this.webhookBaseUrl = webhookBaseUrl; }

    public String getWebhookPath() { return webhookPath; }
    public void setWebhookPath(String webhookPath) { this.webhookPath = webhookPath; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getStaleMinutes() { return staleMinutes; }
    public void setStaleMinutes(int staleMinutes) { this.staleMinutes = staleMinutes; }

    /** Returns the full webhook URL by concatenating base URL and path. */
    public String buildWebhookUrl() {
        return webhookBaseUrl + webhookPath;
    }
}
