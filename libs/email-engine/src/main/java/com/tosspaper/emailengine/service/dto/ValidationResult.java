package com.tosspaper.emailengine.service.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Result of sender validation check.
 * Contains action to take and optional metadata.
 */
@Data
@Builder
public class ValidationResult {
    
    /**
     * Action to take based on validation
     */
    private ValidationAction action;
    
    /**
     * Company ID for the recipient
     */
    private Long companyId;
    
    /**
     * Scheduled deletion time (only for REJECT_GRACE_PERIOD)
     */
    private OffsetDateTime scheduledDeletionAt;
    
    /**
     * Additional context message
     */
    private String message;
}

