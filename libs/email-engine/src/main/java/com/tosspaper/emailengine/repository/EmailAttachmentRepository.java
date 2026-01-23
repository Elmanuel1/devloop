package com.tosspaper.emailengine.repository;

import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.AttachmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailAttachmentRepository {
    
    /**
     * Save multiple attachments for a message
     */
    List<EmailAttachment> saveAll(List<EmailAttachment> attachments);
    
    /**
     * Update attachment status and increment attempts
     */
    EmailAttachment updateStatusToProcessing(String assignedId);
    
    /**
     * Find attachments by status
     */
    List<EmailAttachment> findByStatus(AttachmentStatus status);
    
    /**
     * Find attachment by assigned ID
     */
    Optional<EmailAttachment> findByAssignedId(String assignedId);
    
    /**
     * Update attachment status and lastUpdatedAt
     */
    void updateStatus(EmailAttachment attachment, AttachmentStatus expectedStatus);
    
    /**
     * Find attachments by message ID
     */
    List<EmailAttachment> findByMessageId(UUID messageId);
    
    /**
     * Find all attachments from senders in a specific domain
     * @param domain Domain to match (e.g., "company.com")
     * @return List of attachments from that domain (only non-deleted threads)
     */
    List<EmailAttachment> findByDomain(String domain);
    
    /**
     * Find all attachments from a specific email address
     * @param email Email address to match (e.g., "user@company.com")
     * @return List of attachments from that email (only non-deleted threads)
     */
    List<EmailAttachment> findByEmail(String email);

    /**
     * Find attachment by storage key and company ID.
     * @param storageKey the S3 storage key
     * @param companyId the company ID to verify ownership
     * @return the attachment if found and owned by the company
     */
    Optional<EmailAttachment> findByStorageKeyAndCompanyId(String storageKey, Long companyId);
}
