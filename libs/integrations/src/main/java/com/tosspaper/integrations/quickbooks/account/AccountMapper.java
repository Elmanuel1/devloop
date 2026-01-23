package com.tosspaper.integrations.quickbooks.account;

import com.intuit.ipp.data.Account;
import com.tosspaper.integrations.utils.ProviderTrackingUtil;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Date;

@Component
public class AccountMapper {

    public IntegrationAccount toDomain(Account qboAccount) {
        if (qboAccount == null) {
            return null;
        }

        IntegrationAccount account = IntegrationAccount.builder()
                .name(qboAccount.getName())
                .accountType(qboAccount.getAccountType() != null ? qboAccount.getAccountType().value() : null)
                .accountSubType(qboAccount.getAccountSubType())
                .classification(qboAccount.getClassification() != null ? qboAccount.getClassification().value() : null)
                .active(qboAccount.isActive())
                .currentBalance(qboAccount.getCurrentBalance())
                .build();

        // Provider tracking (generic utility) - convert Date to OffsetDateTime
        Date createTime = qboAccount.getMetaData() != null ? qboAccount.getMetaData().getCreateTime() : null;
        Date lastUpdatedTime = qboAccount.getMetaData() != null ? qboAccount.getMetaData().getLastUpdatedTime() : null;
        
        ProviderTrackingUtil.populateProviderFields(
                account,
                IntegrationProvider.QUICKBOOKS.getValue(),
                qboAccount.getId(),
                createTime != null ? OffsetDateTime.ofInstant(createTime.toInstant(), java.time.ZoneOffset.UTC) : null,
                lastUpdatedTime != null ? OffsetDateTime.ofInstant(lastUpdatedTime.toInstant(), java.time.ZoneOffset.UTC) : null
        );

        return account;
    }
}

