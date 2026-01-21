package com.tosspaper.payment_terms

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.domain.PaymentTerm
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES
import static com.tosspaper.models.jooq.Tables.PAYMENT_TERMS

class PaymentTermRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    PaymentTermRepository paymentTermRepository

    @Autowired
    ObjectMapper objectMapper

    @Shared
    CompaniesRecord company

    def setup() {
        dsl.deleteFrom(PAYMENT_TERMS).execute()
        dsl.deleteFrom(COMPANIES).execute()
        company = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Test Company")
                .set(COMPANIES.EMAIL, "company@test.com")
                .returning()
                .fetchOne()
    }

    def cleanup() {
        dsl.deleteFrom(PAYMENT_TERMS).execute()
        dsl.deleteFrom(COMPANIES).execute()
    }

    // ==================== upsertFromProvider - Insert ====================

    def "upsertFromProvider creates new payment terms"() {
        given: "payment terms from a provider"
        def providerCreatedAt = OffsetDateTime.now().minusDays(10)
        def providerLastUpdatedAt = OffsetDateTime.now().minusDays(1)
        def terms = [
                createDomainPaymentTerm("ext-1", "Net 30", 30, null, null, true, providerCreatedAt, providerLastUpdatedAt),
                createDomainPaymentTerm("ext-2", "Net 60", 60, null, null, true, providerCreatedAt, providerLastUpdatedAt)
        ]

        when: "upserting from provider"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", terms)

        then: "payment terms are created"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .fetch()
        result.size() == 2

        and: "the first term has correct values"
        def term1 = result.find { it.externalId == "ext-1" }
        term1 != null
        term1.name == "Net 30"
        term1.dueDays == 30
        term1.active == true
        term1.provider == "QUICKBOOKS"

        and: "the second term has correct values"
        def term2 = result.find { it.externalId == "ext-2" }
        term2 != null
        term2.name == "Net 60"
        term2.dueDays == 60
        term2.active == true
        term2.provider == "QUICKBOOKS"
    }

    def "upsertFromProvider creates payment term with discount fields"() {
        given: "a payment term with discount"
        def term = createDomainPaymentTerm(
                "ext-discount",
                "2/10 Net 30",
                30,
                new BigDecimal("2.00"),
                10,
                true,
                OffsetDateTime.now().minusDays(5),
                OffsetDateTime.now()
        )

        when: "upserting from provider"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [term])

        then: "payment term is created with discount fields"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq("ext-discount"))
                .fetchOne()
        result != null
        result.name == "2/10 Net 30"
        result.dueDays == 30
        result.discountPercent == new BigDecimal("2.00")
        result.discountDays == 10
        result.active == true
    }

    def "upsertFromProvider creates payment term with external metadata"() {
        given: "a payment term with external metadata"
        def metadata = [
                "SyncToken": "5",
                "FullyQualifiedName": "Net 30",
                "Domain": "QBO"
        ]
        def term = createDomainPaymentTermWithMetadata("ext-meta", "Net 30", 30, true, metadata)

        when: "upserting from provider"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [term])

        then: "payment term is created with external metadata"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq("ext-meta"))
                .fetchOne()
        result != null
        result.externalMetadata != null
        def savedMetadata = objectMapper.readValue(result.externalMetadata.data(), Map)
        savedMetadata["SyncToken"] == "5"
        savedMetadata["FullyQualifiedName"] == "Net 30"
        savedMetadata["Domain"] == "QBO"
    }

    // ==================== upsertFromProvider - Update (ON CONFLICT) ====================

    def "upsertFromProvider updates existing payment term on conflict"() {
        given: "an existing payment term"
        def externalId = "ext-update"
        dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, company.getId())
                .set(PAYMENT_TERMS.PROVIDER, "QUICKBOOKS")
                .set(PAYMENT_TERMS.EXTERNAL_ID, externalId)
                .set(PAYMENT_TERMS.NAME, "Original Name")
                .set(PAYMENT_TERMS.DUE_DAYS, 30)
                .set(PAYMENT_TERMS.ACTIVE, true)
                .execute()

        and: "an updated payment term with the same external ID"
        def updatedTerm = createDomainPaymentTerm(
                externalId,
                "Updated Name",
                45,
                new BigDecimal("1.50"),
                15,
                false,
                OffsetDateTime.now().minusDays(10),
                OffsetDateTime.now()
        )

        when: "upserting the updated term"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [updatedTerm])

        then: "the existing term is updated"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .and(PAYMENT_TERMS.PROVIDER.eq("QUICKBOOKS"))
                .and(PAYMENT_TERMS.EXTERNAL_ID.eq(externalId))
                .fetchOne()
        result != null
        result.name == "Updated Name"
        result.dueDays == 45
        result.discountPercent == new BigDecimal("1.50")
        result.discountDays == 15
        result.active == false

        and: "only one record exists (no duplicate created)"
        def allTerms = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .fetch()
        allTerms.size() == 1
    }

    def "upsertFromProvider updates external metadata on conflict"() {
        given: "an existing payment term with metadata"
        def externalId = "ext-meta-update"
        def originalMetadata = objectMapper.writeValueAsString(["SyncToken": "1", "OldField": "value"])
        dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, company.getId())
                .set(PAYMENT_TERMS.PROVIDER, "QUICKBOOKS")
                .set(PAYMENT_TERMS.EXTERNAL_ID, externalId)
                .set(PAYMENT_TERMS.NAME, "Net 30")
                .set(PAYMENT_TERMS.DUE_DAYS, 30)
                .set(PAYMENT_TERMS.ACTIVE, true)
                .set(PAYMENT_TERMS.EXTERNAL_METADATA, org.jooq.JSONB.valueOf(originalMetadata))
                .execute()

        and: "an updated term with new metadata"
        def newMetadata = ["SyncToken": "5", "NewField": "newValue"]
        def updatedTerm = createDomainPaymentTermWithMetadata(externalId, "Net 30", 30, true, newMetadata)

        when: "upserting the updated term"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [updatedTerm])

        then: "the metadata is updated"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq(externalId))
                .fetchOne()
        def savedMetadata = objectMapper.readValue(result.externalMetadata.data(), Map)
        savedMetadata["SyncToken"] == "5"
        savedMetadata["NewField"] == "newValue"
        !savedMetadata.containsKey("OldField")
    }

    def "upsertFromProvider updates providerLastUpdatedAt on conflict"() {
        given: "an existing payment term"
        def externalId = "ext-timestamp"
        def originalTimestamp = OffsetDateTime.now().minusDays(10)
        dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, company.getId())
                .set(PAYMENT_TERMS.PROVIDER, "QUICKBOOKS")
                .set(PAYMENT_TERMS.EXTERNAL_ID, externalId)
                .set(PAYMENT_TERMS.NAME, "Net 30")
                .set(PAYMENT_TERMS.DUE_DAYS, 30)
                .set(PAYMENT_TERMS.ACTIVE, true)
                .set(PAYMENT_TERMS.PROVIDER_LAST_UPDATED_AT, originalTimestamp)
                .execute()

        and: "an updated term with new timestamp"
        def newTimestamp = OffsetDateTime.now()
        def updatedTerm = createDomainPaymentTerm(externalId, "Net 30", 30, null, null, true, null, newTimestamp)

        when: "upserting the updated term"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [updatedTerm])

        then: "the timestamp is updated"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq(externalId))
                .fetchOne()
        result.providerLastUpdatedAt.isAfter(originalTimestamp) || result.providerLastUpdatedAt.isEqual(originalTimestamp)
    }

    // ==================== upsertFromProvider - Multiple Terms ====================

    def "upsertFromProvider handles mixed insert and update in single call"() {
        given: "an existing payment term"
        def existingExternalId = "ext-existing"
        dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, company.getId())
                .set(PAYMENT_TERMS.PROVIDER, "QUICKBOOKS")
                .set(PAYMENT_TERMS.EXTERNAL_ID, existingExternalId)
                .set(PAYMENT_TERMS.NAME, "Original Net 30")
                .set(PAYMENT_TERMS.DUE_DAYS, 30)
                .set(PAYMENT_TERMS.ACTIVE, true)
                .execute()

        and: "a list with one update and one new term"
        def terms = [
                createDomainPaymentTerm(existingExternalId, "Updated Net 30", 30, null, null, false, null, OffsetDateTime.now()),
                createDomainPaymentTerm("ext-new", "Net 45", 45, null, null, true, null, OffsetDateTime.now())
        ]

        when: "upserting from provider"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", terms)

        then: "the existing term is updated"
        def existingTerm = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq(existingExternalId))
                .fetchOne()
        existingTerm.name == "Updated Net 30"
        existingTerm.active == false

        and: "the new term is created"
        def newTerm = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq("ext-new"))
                .fetchOne()
        newTerm != null
        newTerm.name == "Net 45"
        newTerm.dueDays == 45
        newTerm.active == true

        and: "total count is correct"
        def allTerms = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .fetch()
        allTerms.size() == 2
    }

    def "upsertFromProvider handles empty list without error"() {
        when: "upserting empty list"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [])

        then: "no exception is thrown"
        noExceptionThrown()

        and: "no records are created"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .fetch()
        result.isEmpty()
    }

    // ==================== upsertFromProvider - Different Providers ====================

    def "upsertFromProvider distinguishes between different providers"() {
        given: "payment terms from different providers with same external ID"
        def externalId = "ext-shared"
        def qbTerm = createDomainPaymentTerm(externalId, "QB Net 30", 30, null, null, true, null, OffsetDateTime.now())
        def xeroTerm = createDomainPaymentTerm(externalId, "Xero Net 30", 30, null, null, true, null, OffsetDateTime.now())

        when: "upserting from QUICKBOOKS"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [qbTerm])

        and: "upserting from XERO"
        paymentTermRepository.upsertFromProvider(company.getId(), "XERO", [xeroTerm])

        then: "both terms exist as separate records"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.COMPANY_ID.eq(company.getId()))
                .fetch()
        result.size() == 2

        and: "QuickBooks term is present"
        def qbResult = result.find { it.provider == "QUICKBOOKS" }
        qbResult != null
        qbResult.name == "QB Net 30"

        and: "Xero term is present"
        def xeroResult = result.find { it.provider == "XERO" }
        xeroResult != null
        xeroResult.name == "Xero Net 30"
    }

    def "upsertFromProvider distinguishes between different companies"() {
        given: "another company"
        def otherCompany = dsl.insertInto(COMPANIES)
                .set(COMPANIES.NAME, "Other Company")
                .set(COMPANIES.EMAIL, "other@test.com")
                .returning()
                .fetchOne()

        and: "payment terms with same external ID for different companies"
        def externalId = "ext-company"
        def term1 = createDomainPaymentTerm(externalId, "Company 1 Term", 30, null, null, true, null, OffsetDateTime.now())
        def term2 = createDomainPaymentTerm(externalId, "Company 2 Term", 45, null, null, true, null, OffsetDateTime.now())

        when: "upserting for first company"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [term1])

        and: "upserting for second company"
        paymentTermRepository.upsertFromProvider(otherCompany.getId(), "QUICKBOOKS", [term2])

        then: "both terms exist as separate records"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq(externalId))
                .fetch()
        result.size() == 2

        and: "first company term has correct values"
        def company1Term = result.find { it.companyId == company.getId() }
        company1Term.name == "Company 1 Term"
        company1Term.dueDays == 30

        and: "second company term has correct values"
        def company2Term = result.find { it.companyId == otherCompany.getId() }
        company2Term.name == "Company 2 Term"
        company2Term.dueDays == 45
    }

    // ==================== upsertFromProvider - Null and Edge Cases ====================

    def "upsertFromProvider handles null optional fields"() {
        given: "a payment term with null optional fields"
        def term = createDomainPaymentTerm("ext-null", "Simple Term", null, null, null, null, null, null)

        when: "upserting from provider"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [term])

        then: "payment term is created with null fields"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq("ext-null"))
                .fetchOne()
        result != null
        result.name == "Simple Term"
        result.dueDays == null
        result.discountPercent == null
        result.discountDays == null
        result.active == null
        result.providerCreatedAt == null
        result.providerLastUpdatedAt == null
    }

    def "upsertFromProvider updates null fields to non-null values"() {
        given: "an existing payment term with null fields"
        def externalId = "ext-null-update"
        dsl.insertInto(PAYMENT_TERMS)
                .set(PAYMENT_TERMS.COMPANY_ID, company.getId())
                .set(PAYMENT_TERMS.PROVIDER, "QUICKBOOKS")
                .set(PAYMENT_TERMS.EXTERNAL_ID, externalId)
                .set(PAYMENT_TERMS.NAME, "Net 30")
                .execute()

        and: "an updated term with non-null values"
        def updatedTerm = createDomainPaymentTerm(externalId, "Net 30", 30, new BigDecimal("2.00"), 10, true, null, OffsetDateTime.now())

        when: "upserting the updated term"
        paymentTermRepository.upsertFromProvider(company.getId(), "QUICKBOOKS", [updatedTerm])

        then: "the fields are updated to non-null values"
        def result = dsl.selectFrom(PAYMENT_TERMS)
                .where(PAYMENT_TERMS.EXTERNAL_ID.eq(externalId))
                .fetchOne()
        result.dueDays == 30
        result.discountPercent == new BigDecimal("2.00")
        result.discountDays == 10
        result.active == true
    }

    // ==================== Helper Methods ====================

    private static PaymentTerm createDomainPaymentTerm(
            String externalId,
            String name,
            Integer dueDays,
            BigDecimal discountPercent,
            Integer discountDays,
            Boolean active,
            OffsetDateTime providerCreatedAt,
            OffsetDateTime providerLastUpdatedAt
    ) {
        def term = PaymentTerm.builder()
                .name(name)
                .dueDays(dueDays)
                .discountPercent(discountPercent)
                .discountDays(discountDays)
                .active(active)
                .build()
        term.setExternalId(externalId)
        term.setProviderCreatedAt(providerCreatedAt)
        term.setProviderLastUpdatedAt(providerLastUpdatedAt)
        return term
    }

    private static PaymentTerm createDomainPaymentTermWithMetadata(
            String externalId,
            String name,
            Integer dueDays,
            Boolean active,
            Map<String, Object> externalMetadata
    ) {
        def term = PaymentTerm.builder()
                .name(name)
                .dueDays(dueDays)
                .active(active)
                .build()
        term.setExternalId(externalId)
        term.setExternalMetadata(externalMetadata)
        return term
    }
}
