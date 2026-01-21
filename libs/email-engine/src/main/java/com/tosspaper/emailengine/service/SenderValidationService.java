package com.tosspaper.emailengine.service;

import com.tosspaper.emailengine.service.dto.ValidationResult;

/**
 * Service for validating email senders against approval rules.
 * Checks sender approval status and scheduled deletion times.
 */
public interface SenderValidationService {
    
    /**
     * Validate sender before storing email.
     * Checks if sender is approved, rejected (with grace period), or pending.
     * 
     * @param fromAddress Sender email address
     * @param toAddress Recipient email address (to determine company)
     * @return ValidationResult with action to take (APPROVE, REJECT_BLOCK, REJECT_GRACE_PERIOD, PENDING)
     */
    ValidationResult validateSender(String fromAddress, String toAddress);
}

