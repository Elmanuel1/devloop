package com.tosspaper.emailengine.repository;

import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.EmailThread;
import com.tosspaper.models.enums.MessageStatus;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.query.ReceivedMessageQuery;
import java.util.Optional;
import java.util.UUID;

public interface EmailMessageRepository {
    
    /**
     * Find message by ID
     * @throws com.tosspaper.models.exception.NotFoundException if message not found
     */
    EmailMessage findById(UUID id);
    
    /**
     * Find message by provider and provider message ID
     */
    Optional<EmailMessage> findByProviderMessageId(String provider, String providerMessageId);
    
    /**
     * Save message
     * @throws com.tosspaper.models.exception.DuplicateException if provider + providerMessageId already exists
     */
    EmailMessage save(EmailMessage message);
    
    /**
     * Save thread and message atomically in a single transaction
     * @throws com.tosspaper.models.exception.DuplicateException if provider + providerMessageId already exists
     */
    EmailMessage saveThreadAndMessage(EmailThread thread, EmailMessage message);
    
    /**
     * Find email message by attachment assigned ID
     * @param attachmentAssignedId the assigned ID of the email attachment
     * @return email message or empty if not found
     */
    Optional<EmailMessage> findByAttachmentId(String attachmentAssignedId);
    
    /**
     * Find messages by query with pagination
     */
    Paginated<EmailMessage> findByQuery(ReceivedMessageQuery query);
    
    /**
     * Update the status of a message conditionally
     * @param messageId the message ID
     * @param expectedStatus the expected current status (null to skip check)
     * @param newStatus the new status
     * @return true if the update succeeded, false if the status didn't match
     */
    boolean updateStatus(UUID messageId, MessageStatus expectedStatus, MessageStatus newStatus);
    
    /**
     * Delete message from database
     * @param messageId the message ID to delete
     */
    void delete(UUID messageId);
}
