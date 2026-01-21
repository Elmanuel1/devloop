package com.tosspaper.integrations.dto;

import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@Builder
public class IntegrationConnectionResponse {
    private String id;
    private IntegrationProvider provider;
    private String companyName; // Name of company in external system (realm name)
    private IntegrationConnectionStatus status;
    private OffsetDateTime lastSyncAt;
    private OffsetDateTime connectedAt;
}

