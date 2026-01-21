package com.tosspaper.emailengine.repository;

import com.tosspaper.models.domain.EmailThread;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailThreadRepository {
    
    /**
     * Find thread by ID
     * @throws com.tosspaper.models.exception.NotFoundException if thread not found
     */
    EmailThread findById(UUID id);
    
    /**
     * Find thread by provider and provider thread ID
     */
    Optional<EmailThread> findByProviderThreadId(String provider, String providerThreadId);
    
    /**
     * Delete thread by ID (cascades to all messages and attachments)
     * @param threadId Thread ID to delete
     */
    void delete(UUID threadId);
    
    /**
     * Soft delete thread by setting deleted_at timestamp
     * @param threadId Thread ID to soft delete
     * @param deletedAt Timestamp when thread was deleted
     */
    void softDelete(UUID threadId, OffsetDateTime deletedAt);
}
