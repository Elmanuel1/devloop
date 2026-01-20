package com.tosspaper.models.service;

/**
 * Request object for sync conflict notification.
 * Contains all information needed to send a notification about an integration sync conflict.
 */
public record SyncConflictNotificationRequest(
        Long companyId,
        String provider,  // Provider display name (e.g., "QuickBooks Online", "Xero")
        String entityType,  // e.g., "Vendor", "Purchase Order"
        String entityName,  // Name/identifier of the conflicted entity
        String errorMessage,  // Error message describing the conflict
        String updatedBy  // Optional: user ID or email who triggered the update (may be null)
) {
  
}

