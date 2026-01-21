package com.tosspaper.emailengine.api.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Webhook response DTO for returning processing results.
 * Provides feedback on the webhook processing status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookResponse {
    
    /** Whether the webhook was processed successfully */
    private boolean success;
    
    /** Human-readable message about the processing result */
    private String message;
    
    /** ID of the processed email message (if successful) */
    private UUID messageId;
    
    /** ID of the email thread (if successful) */
    private UUID threadId;
    
    /** Provider that was detected/used */
    private String provider;
    
    /** Provider-specific message ID */
    private String providerMessageId;
    
    /** Timestamp when the webhook was processed */
    private OffsetDateTime processedAt;
    
    /** Error code for failed processing (if applicable) */
    private String errorCode;
    
    /**
     * Create a successful response.
     */
    public static WebhookResponse success(UUID messageId, UUID threadId, String provider, String providerMessageId) {
        return WebhookResponse.builder()
                .success(true)
                .message("Email processed successfully")
                .messageId(messageId)
                .threadId(threadId)
                .provider(provider)
                .providerMessageId(providerMessageId)
                .processedAt(OffsetDateTime.now())
                .build();
    }
    
    /**
     * Create an error response.
     */
    public static WebhookResponse error(String message, String errorCode) {
        return WebhookResponse.builder()
                .success(false)
                .message(message)
                .errorCode(errorCode)
                .processedAt(OffsetDateTime.now())
                .build();
    }
    
    /**
     * Create a duplicate response.
     */
    public static WebhookResponse duplicate(String provider, String providerMessageId) {
        return WebhookResponse.builder()
                .success(true)
                .message("Email already processed (duplicate)")
                .provider(provider)
                .providerMessageId(providerMessageId)
                .processedAt(OffsetDateTime.now())
                .build();
    }
}
