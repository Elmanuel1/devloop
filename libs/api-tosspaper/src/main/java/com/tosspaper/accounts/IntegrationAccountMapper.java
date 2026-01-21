package com.tosspaper.accounts;

import org.springframework.stereotype.Component;

import java.time.ZoneOffset;

/**
 * Mapper for converting IntegrationAccount domain models to API models.
 */
@Component
public class IntegrationAccountMapper {

    public com.tosspaper.generated.model.IntegrationAccount toApi(
            com.tosspaper.models.domain.integration.IntegrationAccount domain) {
        if (domain == null) {
            return null;
        }

        com.tosspaper.generated.model.IntegrationAccount api =
                new com.tosspaper.generated.model.IntegrationAccount();

        api.setId(domain.getId());
        api.setConnectionId(domain.getConnectionId());
        api.setExternalId(domain.getExternalId());
        api.setName(domain.getName());
        api.setAccountType(domain.getAccountType());
        api.setAccountSubType(domain.getAccountSubType());
        api.setClassification(domain.getClassification());
        api.setActive(domain.getActive());
        api.setCurrentBalance(domain.getCurrentBalance());

        if (domain.getCreatedAt() != null) {
            api.setCreatedAt(domain.getCreatedAt());
        }

        return api;
    }
}
