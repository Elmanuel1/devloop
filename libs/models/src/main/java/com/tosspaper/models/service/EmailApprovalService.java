package com.tosspaper.models.service;

import com.tosspaper.models.enums.EmailApprovalStatus;

public interface EmailApprovalService {
    
    /**
     * Approve or reject a sender (email or domain) and trigger extraction
     *
     * @param companyId         Company ID from auth header
     * @param senderIdentifier  Email address (e.g., "user@example.com") or domain (e.g., "example.com")
     * @param approvalStatus    EmailApprovalStatus enum (APPROVED or REJECTED)
     * @param userId            User ID who is approving/rejecting
     */
    void approveSender(Long companyId, String senderIdentifier, EmailApprovalStatus approvalStatus, String userId);
}

