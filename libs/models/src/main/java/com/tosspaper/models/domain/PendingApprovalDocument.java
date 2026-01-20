package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class PendingApprovalDocument {
    private String attachmentId;
    private String filename;
    private String storageKey;
    private String messageId;
    private OffsetDateTime dateReceived;
}

