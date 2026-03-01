package com.tosspaper.precon

import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

class PreconExtractionRepositoryImplSpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    PreconExtractionRepository preconExtractionRepository

    Long companyId
    String companyIdStr
    String tenderId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
        companyIdStr = companyId.toString()

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev.test.com")
            .onDuplicateKeyIgnore()
            .execute()

        tenderId = UUID.randomUUID().toString()
        dsl.insertInto(Tables.TENDERS)
            .set(Tables.TENDERS.ID, tenderId)
            .set(Tables.TENDERS.COMPANY_ID, companyIdStr)
            .set(Tables.TENDERS.NAME, "Test Tender")
            .set(Tables.TENDERS.STATUS, "pending")
            .set(Tables.TENDERS.CREATED_BY, "user-1")
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.EXTRACTION_FIELDS).execute()
        dsl.deleteFrom(Tables.EXTRACTIONS).execute()
        dsl.deleteFrom(Tables.TENDERS).where(Tables.TENDERS.ID.eq(tenderId)).execute()
    }

    // ==================== findPendingExtractions ====================

    def "TC-PR-FP01: should return all pending extractions that are not soft-deleted"() {
        given: "three extractions — two pending and one completed"
            def pending1 = insertExtraction(companyIdStr, tenderId, "pending")
            def pending2 = insertExtraction(companyIdStr, tenderId, "pending")
            insertExtraction(companyIdStr, tenderId, "completed")

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions()

        then: "only the two pending extractions are returned"
            results.size() == 2
            results*.id.containsAll([pending1.id, pending2.id])
            results.every { it.status == "pending" }
    }

    def "TC-PR-FP02: should return empty list when no pending extractions exist"() {
        given: "only a completed extraction in the database"
            insertExtraction(companyIdStr, tenderId, "completed")

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions()

        then: "empty list is returned"
            results.isEmpty()
    }

    def "TC-PR-FP03: should exclude soft-deleted pending extractions"() {
        given: "a pending extraction that has been soft-deleted"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending")
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions()

        then: "soft-deleted extraction is not returned"
            results.isEmpty()
    }

    def "TC-PR-FP04: should return pending from multiple companies"() {
        given: "pending extractions for two different companies"
            def e1 = insertExtraction(companyIdStr, tenderId, "pending")
            def e2 = insertExtraction("other-company", tenderId, "pending")

        when: "finding all pending extractions"
            def results = preconExtractionRepository.findPendingExtractions()

        then: "both extractions are returned regardless of company"
            results.size() == 2
            results*.id.containsAll([e1.id, e2.id])
    }

    def "TC-PR-FP05: should not return extractions with other non-pending statuses"() {
        given: "extractions in various terminal and in-progress statuses"
            insertExtraction(companyIdStr, tenderId, "processing")
            insertExtraction(companyIdStr, tenderId, "completed")
            insertExtraction(companyIdStr, tenderId, "failed")
            insertExtraction(companyIdStr, tenderId, "cancelled")
            def pending = insertExtraction(companyIdStr, tenderId, "pending")

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions()

        then: "only the single pending extraction is returned"
            results.size() == 1
            results[0].id == pending.id
            results[0].status == "pending"
    }

    // ==================== updateStatus ====================

    def "TC-PR-US01: should update status and auto-increment version"() {
        given: "an existing extraction at version 0 in pending status"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending")

        when: "updating status to processing"
            def rowsUpdated = preconExtractionRepository.updateStatus(extraction.id, "processing")

        then: "1 row is updated"
            rowsUpdated == 1

        and: "status is changed to processing and version is incremented to 1"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "processing"
            updated.version == 1
            updated.updatedAt != null
    }

    def "TC-PR-US02: should update status from processing to completed"() {
        given: "an extraction in processing status"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing")

        when: "updating status to completed"
            def rowsUpdated = preconExtractionRepository.updateStatus(extraction.id, "completed")

        then: "1 row is updated"
            rowsUpdated == 1

        and: "status is completed and version is incremented"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "completed"
            updated.version == 1
    }

    def "TC-PR-US03: should return 0 for non-existent extraction ID"() {
        when: "updating status for a non-existent ID"
            def rowsUpdated = preconExtractionRepository.updateStatus("nonexistent-id", "processing")

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "TC-PR-US04: should return 0 for soft-deleted extraction"() {
        given: "a soft-deleted extraction"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending")
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "trying to update status of a soft-deleted extraction"
            def rowsUpdated = preconExtractionRepository.updateStatus(extraction.id, "processing")

        then: "0 rows updated because deleted_at IS NULL filter excludes soft-deleted rows"
            rowsUpdated == 0
    }

    def "TC-PR-US05: should increment version on each successive update"() {
        given: "an existing extraction at version 0"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending")

        when: "updating status twice"
            preconExtractionRepository.updateStatus(extraction.id, "processing")
            preconExtractionRepository.updateStatus(extraction.id, "completed")

        then: "version is incremented to 2 after two updates"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.version == 2
            updated.status == "completed"
    }

    // ==================== Helper Methods ====================

    private ExtractionsRecord insertExtraction(String companyId, String entityId, String status) {
        def record = new ExtractionsRecord()
        record.setId(UUID.randomUUID().toString())
        record.setCompanyId(companyId)
        record.setEntityType("tender")
        record.setEntityId(entityId)
        record.setStatus(status)
        record.setDocumentIds(JSONB.valueOf('["doc-1","doc-2"]'))
        record.setVersion(0)
        record.setCreatedBy(companyId)

        return dsl.insertInto(Tables.EXTRACTIONS)
            .set(record)
            .returning()
            .fetchSingle()
    }
}
