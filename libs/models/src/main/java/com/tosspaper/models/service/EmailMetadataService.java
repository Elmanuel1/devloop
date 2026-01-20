package com.tosspaper.models.service;

import com.tosspaper.models.domain.EmailMetadata;

import java.util.Optional;

/**
 * Service for fetching email metadata.
 * Implemented by email-engine module.
 */
public interface EmailMetadataService {
    
    /**
     * Get email metadata by attachment assigned ID.
     * 
     * @param attachmentAssignedId the assigned ID of the email attachment
     * @return Optional containing email metadata with from/to addresses, or empty if not found
     */
    Optional<EmailMetadata> getEmailMetadataByAttachmentId(String attachmentAssignedId);
}

