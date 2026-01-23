package com.tosspaper.integrations.quickbooks.term;

import com.intuit.ipp.data.Term;
import com.tosspaper.integrations.utils.ProviderTrackingUtil;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Date;

@Component
public class PaymentTermMapper {

    public PaymentTerm toDomain(Term qboTerm) {
        if (qboTerm == null) {
            return null;
        }

        PaymentTerm paymentTerm = PaymentTerm.builder()
                .name(qboTerm.getName())
                .dueDays(qboTerm.getDueDays())
                .discountPercent(qboTerm.getDiscountPercent() != null ? qboTerm.getDiscountPercent() : null)
                .discountDays(qboTerm.getDiscountDays())
                .active(qboTerm.isActive())
                .build();

        // Provider tracking (generic utility) - convert Date to OffsetDateTime
        Date createTime = qboTerm.getMetaData() != null ? qboTerm.getMetaData().getCreateTime() : null;
        Date lastUpdatedTime = qboTerm.getMetaData() != null ? qboTerm.getMetaData().getLastUpdatedTime() : null;
        
        ProviderTrackingUtil.populateProviderFields(
                paymentTerm,
                IntegrationProvider.QUICKBOOKS.getValue(),
                qboTerm.getId(),
                createTime != null ? OffsetDateTime.ofInstant(createTime.toInstant(), java.time.ZoneId.systemDefault()) : null,
                lastUpdatedTime != null ? OffsetDateTime.ofInstant(lastUpdatedTime.toInstant(), java.time.ZoneId.systemDefault()) : null
        );

        return paymentTerm;
    }
}

