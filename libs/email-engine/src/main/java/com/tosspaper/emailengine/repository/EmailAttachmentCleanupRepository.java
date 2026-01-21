package com.tosspaper.emailengine.repository;

import com.tosspaper.models.domain.EmailAttachment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Repository for email attachment cleanup operations
 */
public interface EmailAttachmentCleanupRepository {
    
    /**
     * Mark attachments for scheduled deletion by sender
     * Updates all attachments from a specific sender that have no extraction task
     * @param toAddress Company's assigned email address (to filter messages)
     * @param fromAddress Sender email address (cleaned)
     * @param scheduledDeletionAt Timestamp when attachments should be deleted
     * @return Number of attachments marked for deletion
     */
    int setScheduledDeletionBySender(String toAddress, String fromAddress, OffsetDateTime scheduledDeletionAt);
    
    /**
     * Cancel scheduled deletion for attachments from a sender
     * Clears scheduled_deletion_at for all attachments from a specific sender
     * @param toAddress Company's assigned email address (to filter messages)
     * @param fromAddress Sender email address (cleaned)
     * @return Number of attachments updated
     */
    int clearScheduledDeletionBySender(String toAddress, String fromAddress);
    
    /**
     * Find attachments scheduled for deletion before given time
     * @param before Timestamp to compare against
     * @param limit Maximum number of attachments to return
     * @return List of attachments scheduled for deletion
     */
    List<EmailAttachment> findScheduledForDeletion(OffsetDateTime before, int limit);
    
    /**
     * Find attachments by message IDs (only uploaded status)
     * @param messageIds List of message IDs
     * @return List of attachments
     */
    List<EmailAttachment> findByMessageIds(List<UUID> messageIds);
    
    /**
     * Delete attachment from database
     * @param attachmentId Attachment ID to delete
     */
    void delete(UUID attachmentId);
}

