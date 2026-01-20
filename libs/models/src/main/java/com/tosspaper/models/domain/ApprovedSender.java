package com.tosspaper.models.domain;

import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.enums.SenderApprovalStatus;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class ApprovedSender {
    private String id;
    private Long companyId;
    private String senderIdentifier;
    private EmailWhitelistValue whitelistType;
    private SenderApprovalStatus status;
    private String approvedBy;
    private OffsetDateTime approvedAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime createdAt;
    
    /** Timestamp when all threads from this sender should be deleted (set when sender is rejected) */
    private OffsetDateTime scheduledDeletionAt;
}

