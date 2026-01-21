package com.tosspaper.emailengine.service.dto;

/**
 * Action to take after sender validation
 */
public enum ValidationAction {
    /**
     * Sender is approved - process normally
     */
    APPROVE,
    
    /**
     * Sender is rejected and past grace period - block completely
     */
    REJECT_BLOCK,
    
    /**
     * Sender is rejected but within grace period - allow processing
     */
    REJECT_GRACE_PERIOD,
    
    /**
     * Sender is pending approval - block for now
     */
    PENDING
}

