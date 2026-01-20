package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PendingSenderApproval {
    private String senderIdentifier;
    private Integer documentsPending;
    private Boolean domainAccessAllowed;
    private List<PendingApprovalDocument> attachments;
}

