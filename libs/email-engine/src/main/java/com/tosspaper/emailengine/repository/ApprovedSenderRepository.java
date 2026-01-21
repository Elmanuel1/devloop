package com.tosspaper.emailengine.repository;

import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.domain.PendingSenderApproval;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.paging.Paginated;

import java.util.List;

public interface ApprovedSenderRepository {

    /**
     * Find all approved senders for a company (all statuses)
     * @param companyId Company ID
     * @return List of all approved senders for the company
     */
    List<ApprovedSender> findByCompanyId(Long companyId);
    
    /**
     * Find approved senders for a company by status with pagination
     * @param companyId Company ID
     * @param statuses Sender approval statuses (one or more)
     * @param page Page number (1-indexed)
     * @param pageSize Number of items per page
     * @return Paginated list of approved senders with the specified statuses
     */
    Paginated<ApprovedSender> findByCompanyIdAndStatus(Long companyId, int page, int pageSize, SenderApprovalStatus... statuses);
    
    /**
     * Find pending documents grouped by sender email
     * @param companyId Company ID
     * @param assignedEmail Assigned email to filter messages
     * @return List of pending sender approvals with their attachments
     */
    List<PendingSenderApproval> findPendingDocumentsGroupedBySender(Long companyId, String assignedEmail);
    
    /**
     * Upsert approved sender (insert or update if exists)
     * @param approvedSender Approved sender to upsert
     * @return Upserted approved sender with ID
     */
    ApprovedSender upsert(ApprovedSender approvedSender);
    
    /**
     * Insert approved sender only if it doesn't exist (ON CONFLICT DO NOTHING).
     * @param approvedSender Approved sender to insert
     * @return true if the record was inserted (didn't exist), false if it already existed (no insert)
     */
    boolean insertIfNotExists(ApprovedSender approvedSender);
    
    /**
     * Delete an approved sender
     * @param id Approved sender ID
     * @param companyId Company ID (for security validation)
     */
    void delete(String id, Long companyId);
    
    /**
     * Approve a domain and restore all threads from that domain atomically.
     * This operation:
     * 1. Updates all email-level entries from the domain to APPROVED status
     * 2. Clears scheduled_deletion_at for those entries
     * 3. Restores (un-soft-deletes) all threads from senders in that domain
     * 4. Inserts/updates the domain-level approval record
     * 
     * @param companyId Company ID
     * @param domain Domain to approve (e.g., "example.com")
     * @param userId User ID who is approving
     * @return number of email-level records updated
     */
    int approveDomainAndRestoreThreads(Long companyId, String domain, String userId);
    
    /**
     * Approve an individual email sender and restore their threads atomically.
     * This operation:
     * 1. Upserts the email-level approval record with APPROVED status
     * 2. Clears scheduled_deletion_at
     * 3. Restores (un-soft-deletes) all threads from this sender
     * 
     * @param approvedSender Approved sender to upsert
     * @return number of threads restored
     */
    int approveEmailAndRestoreThreads(ApprovedSender approvedSender);
    
    /**
     * Reject an email sender and soft-delete all their threads atomically.
     * This operation:
     * 1. Upserts the sender with REJECTED status and scheduled_deletion_at
     * 2. Finds all threads from this sender
     * 3. Soft-deletes those threads (sets deleted_at)
     * 
     * @param companyId Company ID
     * @param senderEmail Email address to reject
     * @param userId User ID who is rejecting
     * @param scheduledDeletionAt Timestamp when data should be permanently deleted
     * @return number of threads soft-deleted
     */
    int rejectEmailAndSoftDeleteThreads(Long companyId, String senderEmail, String userId, 
                                        java.time.OffsetDateTime scheduledDeletionAt);
    
    /**
     * Reject a domain and soft-delete all threads from that domain atomically.
     * This operation:
     * 1. Upserts the domain with REJECTED status and scheduled_deletion_at
     * 2. Finds all threads from senders in that domain
     * 3. Soft-deletes those threads (sets deleted_at)
     * 
     * @param companyId Company ID
     * @param domain Domain to reject (e.g., "example.com")
     * @param userId User ID who is rejecting
     * @param scheduledDeletionAt Timestamp when data should be permanently deleted
     * @return number of threads soft-deleted
     */
    int rejectDomainAndSoftDeleteThreads(Long companyId, String domain, String userId, 
                                         java.time.OffsetDateTime scheduledDeletionAt);
}

