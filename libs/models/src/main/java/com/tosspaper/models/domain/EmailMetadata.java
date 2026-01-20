package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Email metadata for extraction tasks.
 */
@Data
@Builder
public class EmailMetadata {
    
    /**
     * Company ID that owns this email.
     */
    private Long companyId;
    
    /**
     * Email sender address.
     */
    private String fromAddress;
    
    /**
     * Email recipient address.
     */
    private String toAddress;

    /**
     * Email subject line.
     */
    private String subject;

    /**
     * Email received timestamp.
     */
    private OffsetDateTime receivedAt;

    /**
     * Unique identifier of the email message record.
     */
    private UUID emailMessageId;

    /**
     * Unique identifier of the email thread.
     */
    private UUID emailThreadId;
}

