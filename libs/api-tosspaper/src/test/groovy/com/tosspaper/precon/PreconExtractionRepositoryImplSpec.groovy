package com.tosspaper.precon

import com.fasterxml.jackson.databind.node.NullNode
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

    // ==================== claimNextBatch ====================

    def "TC-PR-CNB01: should claim pending extractions and transition them to processing"() {
        given: "two pending extractions with different document lists"
            def pending1 = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1","doc-2"]')
            def pending2 = insertExtraction(companyIdStr, tenderId, "pending", '["doc-3"]')

        when: "claiming the next batch"
            def results = preconExtractionRepository.claimNextBatch(50)

        then: "two ExtractionWithDocs are returned"
            results.size() == 2
            results*.id.containsAll([pending1.id, pending2.id])

        and: "both rows are now PROCESSING in the database"
            results.every {
                dsl.selectFrom(Tables.EXTRACTIONS)
                    .where(Tables.EXTRACTIONS.ID.eq(it.id))
                    .fetchSingle()
                    .status == "processing"
            }
    }

    def "TC-PR-CNB02: should return empty list when no pending extractions exist"() {
        given: "only a completed extraction in the database"
            insertExtraction(companyIdStr, tenderId, "completed", '["doc-1"]')

        when: "claiming the next batch"
            def results = preconExtractionRepository.claimNextBatch(50)

        then: "empty list is returned"
            results.isEmpty()
    }

    def "TC-PR-CNB03: should exclude soft-deleted pending extractions"() {
        given: "a pending extraction that has been soft-deleted"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "claiming the next batch"
            def results = preconExtractionRepository.claimNextBatch(50)

        then: "soft-deleted extraction is not claimed"
            results.isEmpty()
    }

    def "TC-PR-CNB04: should honour the limit and claim at most that many rows"() {
        given: "five pending extractions in the database"
            5.times { insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]') }

        when: "claiming with a limit of 3"
            def results = preconExtractionRepository.claimNextBatch(3)

        then: "at most 3 records are claimed"
            results.size() == 3
    }

    def "TC-PR-CNB05: should parse document IDs from claimed extractions"() {
        given: "a pending extraction with known document IDs"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-A","doc-B"]')

        when: "claiming the next batch"
            def results = preconExtractionRepository.claimNextBatch(50)

        then: "document IDs are correctly parsed"
            results.size() == 1
            results[0].id == extraction.id
            results[0].documentIds == ["doc-A", "doc-B"]
    }

    def "TC-PR-CNB06: should not claim extractions with non-pending statuses"() {
        given: "extractions in various statuses"
            insertExtraction(companyIdStr, tenderId, "processing", '[]')
            insertExtraction(companyIdStr, tenderId, "completed", '[]')
            insertExtraction(companyIdStr, tenderId, "failed", '[]')
            def pending = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')

        when: "claiming the next batch"
            def results = preconExtractionRepository.claimNextBatch(50)

        then: "only the pending extraction is claimed"
            results.size() == 1
            results[0].id == pending.id
    }

    def "TC-PR-CNB07: claimed rows have incremented version"() {
        given: "a pending extraction at version 0"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')

        when: "claiming the next batch"
            preconExtractionRepository.claimNextBatch(50)

        then: "version is incremented to 1"
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
                .version == 1
    }

    // ==================== reapStaleExtractions ====================

    def "TC-PR-RSE01: should reset stale processing extractions back to pending"() {
        given: "a processing extraction with updated_at more than 10 minutes ago"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.UPDATED_AT, OffsetDateTime.now().minusMinutes(15))
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "reaping stale extractions with a 10-minute threshold"
            def rowsReset = preconExtractionRepository.reapStaleExtractions(10)

        then: "one row was reset"
            rowsReset == 1

        and: "extraction is back to PENDING"
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
                .status == "pending"
    }

    def "TC-PR-RSE02: should not reap processing extractions that are still within threshold"() {
        given: "a processing extraction updated 2 minutes ago"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.UPDATED_AT, OffsetDateTime.now().minusMinutes(2))
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when: "reaping with a 10-minute threshold"
            def rowsReset = preconExtractionRepository.reapStaleExtractions(10)

        then: "no rows reset"
            rowsReset == 0

        and: "extraction is still PROCESSING"
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
                .status == "processing"
    }

    def "TC-PR-RSE03: should not reap pending or completed extractions"() {
        given: "pending and completed extractions with old updated_at"
            def pending = insertExtraction(companyIdStr, tenderId, "pending", '[]')
            def completed = insertExtraction(companyIdStr, tenderId, "completed", '[]')
            [pending, completed].each { ext ->
                dsl.update(Tables.EXTRACTIONS)
                    .set(Tables.EXTRACTIONS.UPDATED_AT, OffsetDateTime.now().minusMinutes(60))
                    .where(Tables.EXTRACTIONS.ID.eq(ext.id))
                    .execute()
            }

        when: "reaping with a 10-minute threshold"
            def rowsReset = preconExtractionRepository.reapStaleExtractions(10)

        then: "no rows reset — only PROCESSING rows qualify"
            rowsReset == 0
    }

    def "TC-PR-RSE04: should return 0 when there are no stale extractions"() {
        when: "reaping with no extractions in the database"
            def rowsReset = preconExtractionRepository.reapStaleExtractions(10)

        then: "zero rows reset"
            rowsReset == 0
    }

    // ==================== markAsCompleted ====================

    def "TC-PR-MAC01: should transition processing extraction to completed with a result"() {
        given: "an extraction in processing state"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            def result = new PipelineExtractionResult(extraction.id, NullNode.getInstance())

        when: "marking as completed"
            def rowsUpdated = preconExtractionRepository.markAsCompleted(extraction.id, result)

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
        given: "an empty result"
            def result = new PipelineExtractionResult("nonexistent-id", NullNode.getInstance())

        when:
            def rowsUpdated = preconExtractionRepository.markAsCompleted("nonexistent-id", result)

        then:
            rowsUpdated == 0
    }

    // ==================== markAsFailed ====================

    def "TC-PR-MAF01: should transition processing extraction to failed and store error reason"() {
        given: "an extraction in processing state"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')

        when: "marking as failed with an error reason"
            def rowsUpdated = preconExtractionRepository.markAsFailed(extraction.id, "network timeout")

        then: "one row updated"
            rowsUpdated == 1

        and: "status is failed with incremented version and error reason stored"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
            updated.status == "failed"
            updated.version == 1
            updated.errorReason == "network timeout"
    }

    def "TC-PR-MAF02: should return 0 when marking non-existent extraction as failed"() {
        when:
            def rowsUpdated = preconExtractionRepository.markAsFailed("nonexistent-id", "some error")

        then:
            rowsUpdated == 0
    }

    def "TC-PR-MAC03: markAsCompleted on a non-processing extraction returns 0 — FROM-state guard"() {
        given: "a pending extraction (not yet claimed)"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')
            def result = new PipelineExtractionResult(extraction.id, NullNode.getInstance())

        when: "attempting to mark it completed without first claiming it"
            def rowsUpdated = preconExtractionRepository.markAsCompleted(extraction.id, result)

        then: "the FROM-state guard rejects the update — row must be PROCESSING first"
            rowsUpdated == 0

        and: "status is unchanged"
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
                .status == "pending"
    }

    def "TC-PR-MAF03: markAsFailed on a non-processing extraction returns 0 — FROM-state guard"() {
        given: "a pending extraction (not yet claimed)"
            def extraction = insertExtraction(companyIdStr, tenderId, "pending", '["doc-1"]')

        when: "attempting to mark it failed without first claiming it"
            def rowsUpdated = preconExtractionRepository.markAsFailed(extraction.id, "some error")

        then: "the FROM-state guard rejects the update — row must be PROCESSING first"
            rowsUpdated == 0

        and: "status is unchanged"
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .fetchSingle()
                .status == "pending"
    }

    // ==================== putDocumentExternalId ====================

    def "TC-PR-PDEI01: stores ExternalId entry for a document in the JSONB map"() {
        given: "a pending extraction and an ExternalId to record"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            def externalId = new ExternalId("task-abc", "file-xyz")

        when: "storing the external ID for doc-1"
            def rowsUpdated = preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-1", externalId)

        then: "one row updated"
            rowsUpdated == 1

        and: "the JSONB map contains the expected entry"
            def raw = dsl.fetch(
                "SELECT document_external_ids->>'doc-1' AS entry FROM extractions WHERE id = ?",
                extraction.id)
            def entry = raw[0].getValue("entry") as String
            entry.contains("task-abc")
            entry.contains("file-xyz")
    }

    def "TC-PR-PDEI02: adding a second document does not overwrite the first"() {
        given: "a processing extraction with one document already recorded"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1","doc-2"]')
            preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-1", new ExternalId("task-first", "file-first"))

        when: "storing an ExternalId for a second document"
            preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-2", new ExternalId("task-second", "file-second"))

        then: "both entries are present in the map"
            def raw = dsl.fetch(
                "SELECT document_external_ids FROM extractions WHERE id = ?",
                extraction.id)
            def mapJson = raw[0].getValue("document_external_ids") as String
            mapJson.contains("task-first")
            mapJson.contains("task-second")
    }

    def "TC-PR-PDEI03: returns 0 for a non-existent extraction"() {
        when:
            def rowsUpdated = preconExtractionRepository.putDocumentExternalId(
                    "nonexistent-id", "doc-1", new ExternalId("task-x", "file-x"))

        then:
            rowsUpdated == 0
    }

    def "TC-PR-PDEI04: returns 0 for a soft-deleted extraction"() {
        given: "a soft-deleted extraction"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when:
            def rowsUpdated = preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-1", new ExternalId("task-x", "file-x"))

        then:
            rowsUpdated == 0
    }

    // ==================== findByDocumentExternalTaskId ====================

    def "TC-PR-FBDET01: returns extraction when the task ID is in the document_external_ids map"() {
        given: "an extraction with an ExternalId recorded for doc-1"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1","doc-2"]')
            preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-1", new ExternalId("task-find-me", "file-xyz"))

        when: "looking up by task ID"
            def result = preconExtractionRepository.findByDocumentExternalTaskId("task-find-me")

        then: "the extraction is found"
            result.isPresent()
            result.get().id == extraction.id
    }

    def "TC-PR-FBDET02: returns empty Optional when no extraction owns the task ID"() {
        when:
            def result = preconExtractionRepository.findByDocumentExternalTaskId("unknown-task-xyz")

        then:
            result.isEmpty()
    }

    def "TC-PR-FBDET03: returns empty Optional for a soft-deleted extraction"() {
        given: "a soft-deleted extraction with an ExternalId recorded"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-1"]')
            preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-1", new ExternalId("task-deleted", "file-deleted"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(extraction.id))
                .execute()

        when:
            def result = preconExtractionRepository.findByDocumentExternalTaskId("task-deleted")

        then: "soft-deleted extraction is excluded"
            result.isEmpty()
    }

    def "TC-PR-FBDET04: returned ExtractionWithDocs contains parsed document IDs"() {
        given: "an extraction with known document IDs"
            def extraction = insertExtraction(companyIdStr, tenderId, "processing", '["doc-A","doc-B"]')
            preconExtractionRepository.putDocumentExternalId(
                    extraction.id, "doc-A", new ExternalId("task-doc-a", "file-doc-a"))

        when:
            def result = preconExtractionRepository.findByDocumentExternalTaskId("task-doc-a")

        then: "document IDs are correctly parsed"
            result.isPresent()
            result.get().documentIds == ["doc-A", "doc-B"]
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
