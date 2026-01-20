package com.tosspaper.models.service;

import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.domain.PendingSenderApproval;
import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.enums.SenderApprovalStatus;

import java.util.List;

public interface ApprovedSendersManagementService {

    /**
     * List approved senders for a company filtered by status with pagination
     * 
     * @param companyId Company ID
     * @param status Sender approval status filter
     * @param page Page number (1-indexed)
     * @param pageSize Number of items per page
     * @return Paginated list of approved senders with the specified status
     */
    Paginated<ApprovedSender> listApprovedSenders(Long companyId, SenderApprovalStatus status, int page, int pageSize);

    /**
     * List pending documents grouped by sender email
     * 
     * @param companyId Company ID
     * @return List of pending sender approvals with their attachments
     */
    List<PendingSenderApproval> listPendingDocuments(Long companyId);

    /**
     * Update an approved sender's email/domain and whitelist type
     * 
     * @param companyId Company ID
     * @param senderId Approved sender ID
     * @param email Email address
     * @param whitelistValue Whitelist type (email or domain)
     * @return Updated approved sender
     */
    ApprovedSender updateApprovedSender(Long companyId, String senderId, String email, EmailWhitelistValue whitelistValue);

    /**
     * Remove sender approval
     * 
     * @param companyId Company ID
     * @param senderId Approved sender ID
     */
    void removeApprovedSender(Long companyId, String senderId);
}
