package com.tosspaper.payment_terms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.domain.PaymentTerm;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.tosspaper.models.jooq.Tables.PAYMENT_TERMS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PaymentTermRepositoryImpl implements PaymentTermRepository {
    
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    
    @Override
    @SneakyThrows
    public void upsertFromProvider(Long companyId, String provider, List<PaymentTerm> terms) {
        for (PaymentTerm term : terms) {
            dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, companyId)
                .set(PAYMENT_TERMS.PROVIDER, provider)
                .set(PAYMENT_TERMS.EXTERNAL_ID, term.getExternalId())
                .set(PAYMENT_TERMS.NAME, term.getName())
                .set(PAYMENT_TERMS.DUE_DAYS, term.getDueDays())
                .set(PAYMENT_TERMS.DISCOUNT_PERCENT, term.getDiscountPercent())
                .set(PAYMENT_TERMS.DISCOUNT_DAYS, term.getDiscountDays())
                .set(PAYMENT_TERMS.ACTIVE, term.getActive())
                .set(PAYMENT_TERMS.EXTERNAL_METADATA, JSONB.jsonbOrNull(objectMapper.writeValueAsString(term.getExternalMetadata())))
                .set(PAYMENT_TERMS.PROVIDER_CREATED_AT, term.getProviderCreatedAt())
                .set(PAYMENT_TERMS.PROVIDER_LAST_UPDATED_AT, term.getProviderLastUpdatedAt())
                .onConflict(PAYMENT_TERMS.COMPANY_ID, PAYMENT_TERMS.PROVIDER, PAYMENT_TERMS.EXTERNAL_ID)
                .doUpdate()
                .set(PAYMENT_TERMS.NAME, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.NAME))
                .set(PAYMENT_TERMS.DUE_DAYS, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.DUE_DAYS))
                .set(PAYMENT_TERMS.DISCOUNT_PERCENT, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.DISCOUNT_PERCENT))
                .set(PAYMENT_TERMS.DISCOUNT_DAYS, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.DISCOUNT_DAYS))
                .set(PAYMENT_TERMS.ACTIVE, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.ACTIVE))
                .set(PAYMENT_TERMS.EXTERNAL_METADATA, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.EXTERNAL_METADATA))
                .set(PAYMENT_TERMS.PROVIDER_LAST_UPDATED_AT, org.jooq.impl.DSL.excluded(PAYMENT_TERMS.PROVIDER_LAST_UPDATED_AT))
                .execute();
        }
    }
}



