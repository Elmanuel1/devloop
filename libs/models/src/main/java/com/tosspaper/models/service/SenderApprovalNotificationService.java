package com.tosspaper.models.service;

/**
 * Service for sending email notifications related to sender approvals.
 */
public interface SenderApprovalNotificationService {
    
    /**
     * Send email notification to company owner when a new sender creates a pending approval.
     * 
     * @param senderEmail the email address of the sender requiring approval
     * @param companyId the company ID that owns this sender approval
     */
    void sendPendingSenderApprovalNotification(String senderEmail, Long companyId);
}

