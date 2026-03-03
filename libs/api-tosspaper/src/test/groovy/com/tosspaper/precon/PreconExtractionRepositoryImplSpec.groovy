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

    def "TC-PR-FP01: should return pending extractions with their document IDs"() {
        given: "two pending extractions with different document lists"
            def pending1 = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1","doc-2"]')
            def pending2 = insertExtraction(companyIdStr, tenderId, "pending", '["doc-3"]')

        when: "finding pending extractions with a generous limit"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "two ExtractionWithDocs are returned"
            results.size() == 2
            results*.id.containsAll([pending1.id, pending2.id])
            results.every { it.extraction.status == "pending" }

        and: "document IDs are correctly parsed for the first extraction"
            def withDocs1 = results.find { it.id == pending1.id }
            withDocs1.documentIds == ["doc-1", "doc-2"]

        and: "document IDs are correctly parsed for the second extraction"
            def withDocs2 = results.find { it.id == pending2.id }
            withDocs2.documentIds == ["doc-3"]
    }

    def "TC-PR-FP02: should return empty list when no pending extractions exist"() {
        given: "only a completed extraction in the database"
            insertExtraction(companyIdStr, tenderId, "completed", '["doc-1"]')

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "empty list is returned"
            results.isEmpty()
    }

    def "TC-PR-FP03: should exclude soft-deleted pending extractions"() {
        given: "a pending extraction that has been soft-deleted"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "soft-deleted extraction is not returned"
            results.isEmpty()
    }

    def "TC-PR-FP04: should return pending from multiple companies"() {
        given: "pending extractions for two different companies"
            def e1 = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            def e2 = insertExtraction("other-company", tenderId, "pending", '["doc-2"]')

        when: "finding all pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "both extractions are returned regardless of company"
            results.size() == 2
            results*.id.containsAll([e1.id, e2.id])
    }

    def "TC-PR-FP05: should not return extractions with other non-pending statuses"() {
        given: "extractions in various terminal and in-progress statuses"
            insertExtraction(companyIdStr, tenderId, "processing", '[]')
            insertExtraction(companyIdStr, tenderId, "completed", '[]')
            insertExtraction(companyIdStr, tenderId, "failed", '[]')
            insertExtraction(companyIdStr, tenderId, "cancelled", '[]')
            def pending = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "only the single pending extraction is returned"
            results.size() == 1
            results[0].id == pending.id
            results[0].extraction.status == "pending"
    }

    def "TC-PR-FP06: should honour the limit and return at most that many rows"() {
        given: "five pending extractions in the database"
            5.times { insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]') }

        when: "finding pending extractions with a limit of 3"
            def results = preconExtractionRepository.findPendingExtractions(3)

        then: "at most 3 records are returned"
            results.size() == 3
            results.every { it.extraction.status == "pending" }
    }

    def "TC-PR-FP07: should order results by created_at ascending so oldest work is first"() {
        given: "two pending extractions inserted sequentially"
            def first  = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            def second = insertExtraction(companyIdStr, tenderId, "pending", '["doc-2"]')

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "the earlier extraction comes first"
            results.size() == 2
            results[0].id == first.id
            results[1].id == second.id
    }

    def "TC-PR-FP08: extraction with null document_ids returns empty documentIds list"() {
        given: "a pending extraction with null document_ids"
            def id = UUID.randomUUID().toString()
            dsl.insertInto(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.ID, id)
                .set(Tables.EXTRACTIONS.COMPANY_ID, companyIdStr)
                .set(Tables.EXTRACTIONS.ENTITY_TYPE, "tender")
                .set(Tables.EXTRACTIONS.ENTITY_ID, tenderId)
                .set(Tables.EXTRACTIONS.STATUS, "pending")
                .set(Tables.EXTRACTIONS.DOCUMENT_IDS, JSONB.valueOf('[]'))
                .set(Tables.EXTRACTIONS.VERSION, 0)
                .set(Tables.EXTRACTIONS.CREATED_BY, companyIdStr)
                .execute()

        when: "finding pending extractions"
            def results = preconExtractionRepository.findPendingExtractions(50)

        then: "extraction is returned with an empty documentIds list"
            results.size() == 1
            results[0].id == id
            results[0].documentIds.isEmpty()
    }

    // ==================== markAsProcessing ====================

    def "TC-PR-MAP01: should transition pending extraction to processing and increment version"() {
        given: "a pending extraction at version 0"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')

        when: "marking as processing"
            def rowsUpdated = preconExtractionRepository.markAsProcessing(extraction.id)

        then: "one row was updated"
            rowsUpdated == 1

        and: "status is now processing and version incremented"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "processing"
            updated.version == 1
    }

    def "TC-PR-MAP02: should return 0 when extraction is already processing (not pending)"() {
        given: "an extraction already in processing state"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '[]')

        when: "trying to mark as processing again"
            def rowsUpdated = preconExtractionRepository.markAsProcessing(extraction.id)

        then: "no rows are updated (only pending rows qualify)"
            rowsUpdated == 0
    }

    def "TC-PR-MAP03: should return 0 when extraction ID does not exist"() {
        when: "marking a non-existent extraction as processing"
            def rowsUpdated = preconExtractionRepository.markAsProcessing("nonexistent-id")

        then: "zero rows updated"
            rowsUpdated == 0
    }

    def "TC-PR-MAP04: should return 0 for a soft-deleted extraction"() {
        given: "a soft-deleted pending extraction"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "marking as processing"
            def rowsUpdated = preconExtractionRepository.markAsProcessing(extraction.id)

        then: "no rows updated — soft-deleted rows are excluded"
            rowsUpdated == 0
    }

    // ==================== markAsCompleted ====================

    def "TC-PR-MAC01: should transition processing extraction to completed and increment version"() {
        given: "an extraction in processing state"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')

        when: "marking as completed"
            def rowsUpdated = preconExtractionRepository.markAsCompleted(extraction.id)

        then: "one row updated"
            rowsUpdated == 1

        and: "status is completed with incremented version"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "completed"
            updated.version == 1
    }

    def "TC-PR-MAC02: should return 0 when marking non-existent extraction as completed"() {
        when:
            def rowsUpdated = preconExtractionRepository.markAsCompleted("nonexistent-id")

        then:
            rowsUpdated == 0
    }

    // ==================== markAsFailed ====================

    def "TC-PR-MAF01: should transition processing extraction to failed and increment version"() {
        given: "an extraction in processing state"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')

        when: "marking as failed"
            def rowsUpdated = preconExtractionRepository.markAsFailed(extraction.id)

        then: "one row updated"
            rowsUpdated == 1

        and: "status is failed with incremented version"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "failed"
            updated.version == 1
    }

    def "TC-PR-MAF02: should return 0 when marking non-existent extraction as failed"() {
        when:
            def rowsUpdated = preconExtractionRepository.markAsFailed("nonexistent-id")

        then:
            rowsUpdated == 0
    }

    // ==================== Helper Methods ====================

    private ExtractionsRecord insertExtraction(String companyId, String entityId,
                                               String status, String docIdsJson) {
        def record = new ExtractionsRecord()
        record.setId(UUID.randomUUID().toString())
        record.setCompanyId(companyId)
        record.setEntityType("tender")
        record.setEntityId(entityId)
        record.setStatus(status)
        record.setDocumentIds(JSONB.valueOf(docIdsJson))
        record.setVersion(0)
        record.setCreatedBy(companyId)

        return dsl.insertInto(Tables.EXTRACTIONS)
            .set(record)
            .returning()
            .fetchSingle()
    }
}
