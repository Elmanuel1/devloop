package com.tosspaper.emailengine.provider;

import com.tosspaper.emailengine.api.dto.WebhookPayload;
import com.tosspaper.models.domain.EmailMessage;

/**
 * Interface for adapting different email provider webhook formats
 * to our internal EmailMessage domain model.
 */
public interface ProviderAdapter {
    
    /**
     * Get the provider name this adapter handles.
     * 
     * @return the provider name (e.g., "mailgun")
     */
    String getProviderName();
    
    /**
     * Validate the webhook signature to ensure authenticity.
     * 
     * @param payload the raw webhook payload
     * @param signature the signature header from the webhook
     * @param secret the webhook secret for validation
     * @return true if signature is valid
     */
    boolean validateSignature(String payload, String signature, String secret);
    
    /**
     * Parse the webhook payload into our internal EmailMessage format with attachments.
     * 
     * @param webhookPayload the webhook payload containing JSON and optional multipart files
     * @return parsed EmailMessage with all fields populated and attachments included
     * @throws IllegalArgumentException if payload is invalid or missing required fields
     */
    EmailMessage parse(WebhookPayload webhookPayload);
}
