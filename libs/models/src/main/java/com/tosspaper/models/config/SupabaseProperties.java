package com.tosspaper.models.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for Supabase integration.
 */
@Configuration
@ConfigurationProperties(prefix = "supabase")
@Validated
@Data
public class SupabaseProperties {

    /**
     * Supabase project URL (e.g., https://yourproject.supabase.co).
     * Required for API calls.
     */
    @NotBlank(message = "Supabase URL is required")
    private String url;

    /**
     * Supabase service role key for admin operations.
     * Keep this secret! Never expose in frontend.
     */
    @NotBlank(message = "Supabase service role key is required")
    private String serviceRoleKey;

    /**
     * Webhook configuration for Supabase auth hooks.
     */
    private Webhook webhook = new Webhook();

    @Data
    public static class Webhook {
        /**
         * Webhook secret for verifying Supabase webhook signatures.
         * Used with Standard Webhooks (Svix) HMAC verification.
         */
        private String secret;
    }
}
