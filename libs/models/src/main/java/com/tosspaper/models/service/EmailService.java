package com.tosspaper.models.service;

import com.tosspaper.models.domain.EmailMessage;

/**
 * Service interface for processing email messages and attachments.
 * Handles email parsing, validation, and storage operations.
 */
public interface EmailService {
    
    /**
     * Process an email webhook with its attachments.
     * 
     * @param emailMessage the email message to process (includes attachments)
     */
    void processWebhook(EmailMessage emailMessage);
    
}
