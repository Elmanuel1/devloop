package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

class ExtractionFieldRepositoryImplSpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    ExtractionFieldRepository extractionFieldRepository

    Long companyId
    String companyIdStr
    String tenderId
    String extractionId

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

        extractionId = UUID.randomUUID().toString()
        dsl.insertInto(Tables.EXTRACTIONS)
            .set(Tables.EXTRACTIONS.ID, extractionId)
            .set(Tables.EXTRACTIONS.COMPANY_ID, companyIdStr)
            .set(Tables.EXTRACTIONS.ENTITY_TYPE, "tender")
            .set(Tables.EXTRACTIONS.ENTITY_ID, tenderId)
            .set(Tables.EXTRACTIONS.STATUS, "pending")
            .set(Tables.EXTRACTIONS.DOCUMENT_IDS, JSONB.valueOf('["doc-1"]'))
            .set(Tables.EXTRACTIONS.VERSION, 0)
            .set(Tables.EXTRACTIONS.CREATED_BY, "user-1")
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.EXTRACTION_FIELDS).execute()
        dsl.deleteFrom(Tables.EXTRACTIONS).execute()
        dsl.deleteFrom(Tables.TENDERS).where(Tables.TENDERS.ID.eq(tenderId)).execute()
    }

    // ==================== findByExtractionId ====================

    def "TC-RF-FE01: should return all fields scoped to the extraction"() {
        given: "three extraction fields for this extraction and one for a different extraction"
            def otherExtractionId = UUID.randomUUID().toString()
            insertExtractionRow(otherExtractionId, companyIdStr, tenderId)

            def f1 = insertFieldRecord(extractionId, "name", "text")
            def f2 = insertFieldRecord(extractionId, "closing_date", "date")
            def f3 = insertFieldRecord(extractionId, "location", "text")
            insertFieldRecord(otherExtractionId, "name", "text")

        when: "finding fields for this extraction"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(20)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "only the 3 fields for this extraction are returned"
            results.size() == 3
            results.every { it.extractionId == extractionId }
    }

    def "TC-RF-FE02: should filter by fieldName returning only matching fields"() {
        given: "fields with different names"
            insertFieldRecord(extractionId, "name", "text")
            insertFieldRecord(extractionId, "closing_date", "date")
            insertFieldRecord(extractionId, "location", "text")

        when: "filtering by fieldName='closing_date'"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .fieldName("closing_date")
                .limit(20)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "only the closing_date field is returned"
            results.size() == 1
            results[0].fieldName == "closing_date"
    }

    def "TC-RF-FE03: should filter by documentId via JSONB containment on citations"() {
        given: "fields with citations referencing different documents"
            def docId = UUID.randomUUID().toString()
            def otherDocId = UUID.randomUUID().toString()

            def fieldWithDoc = new ExtractionFieldsRecord()
            fieldWithDoc.setId(UUID.randomUUID().toString())
            fieldWithDoc.setExtractionId(extractionId)
            fieldWithDoc.setFieldName("name")
            fieldWithDoc.setFieldType("text")
            fieldWithDoc.setCitations(JSONB.valueOf("""[{"document_id": "${docId}", "page": 1}]"""))
            dsl.insertInto(Tables.EXTRACTION_FIELDS).set(fieldWithDoc).execute()

            def fieldWithOtherDoc = new ExtractionFieldsRecord()
            fieldWithOtherDoc.setId(UUID.randomUUID().toString())
            fieldWithOtherDoc.setExtractionId(extractionId)
            fieldWithOtherDoc.setFieldName("location")
            fieldWithOtherDoc.setFieldType("text")
            fieldWithOtherDoc.setCitations(JSONB.valueOf("""[{"document_id": "${otherDocId}", "page": 2}]"""))
            dsl.insertInto(Tables.EXTRACTION_FIELDS).set(fieldWithOtherDoc).execute()

            // A field with no citations
            insertFieldRecord(extractionId, "closing_date", "date")

        when: "filtering by documentId"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .documentId(docId)
                .limit(20)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "only the field citing that document is returned"
            results.size() == 1
            results[0].fieldName == "name"
    }

    def "TC-RF-FE04: should return limit+1 for has_more detection"() {
        given: "3 fields for the extraction"
            insertFieldRecord(extractionId, "name", "text")
            Thread.sleep(10)
            insertFieldRecord(extractionId, "location", "text")
            Thread.sleep(10)
            insertFieldRecord(extractionId, "closing_date", "date")

        when: "querying with limit=2"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(2)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "limit+1 = 3 results returned for has_more detection"
            results.size() == 3
    }

    def "TC-RF-FE05: should return correct second page via cursor pagination"() {
        given: "three fields inserted with time gaps"
            def f1 = insertFieldRecord(extractionId, "name", "text")
            Thread.sleep(50)
            def f2 = insertFieldRecord(extractionId, "location", "text")
            Thread.sleep(50)
            def f3 = insertFieldRecord(extractionId, "closing_date", "date")

        and: "first page with limit=1 (returns newest field first)"
            def page1Query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(1)
                .build()
            def page1 = extractionFieldRepository.findByExtractionId(page1Query)
            def lastOnPage1 = page1[0] // f3 is newest

        when: "fetching second page using cursor"
            def page2Query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(1)
                .cursorCreatedAt(lastOnPage1.createdAt)
                .cursorId(lastOnPage1.id)
                .build()
            def page2 = extractionFieldRepository.findByExtractionId(page2Query)

        then: "second page contains the record after the cursor"
            page2[0].id == f2.id
    }

    def "TC-RF-FE06: should return empty list when no fields exist"() {
        when: "querying for extraction with no fields"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(20)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "empty list is returned"
            results.isEmpty()
    }

    def "TC-RF-FE07: should order by created_at DESC and id DESC (newest first)"() {
        given: "three fields inserted with time gaps"
            def f1 = insertFieldRecord(extractionId, "name", "text")
            Thread.sleep(50)
            def f2 = insertFieldRecord(extractionId, "location", "text")
            Thread.sleep(50)
            def f3 = insertFieldRecord(extractionId, "closing_date", "date")

        when: "listing fields"
            def query = ExtractionFieldQuery.builder()
                .extractionId(extractionId)
                .limit(20)
                .build()
            def results = extractionFieldRepository.findByExtractionId(query)

        then: "fields are returned newest first"
            results.size() == 3
            results[0].id == f3.id
            results[1].id == f2.id
            results[2].id == f1.id
    }

    // ==================== findById ====================

    def "TC-RF-FB01: should find existing extraction field by ID"() {
        given: "an extraction field in the database"
            def inserted = insertFieldRecord(extractionId, "closing_date", "date")

        when: "finding by ID"
            def result = extractionFieldRepository.findById(inserted.id)

        then: "correct record is returned"
            result != null
            result.id == inserted.id
            result.extractionId == extractionId
            result.fieldName == "closing_date"
            result.fieldType == "date"
    }

    def "TC-RF-FB02: should throw NotFoundException for non-existent field ID"() {
        when: "finding a non-existent field ID"
            extractionFieldRepository.findById("nonexistent-field-id")

        then: "NotFoundException is thrown with the correct code"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.field.notFound"
    }

    // ==================== findAllByIds ====================

    def "TC-RF-FA01: should return all fields for given IDs"() {
        given: "two extraction fields"
            def f1 = insertFieldRecord(extractionId, "name", "text")
            def f2 = insertFieldRecord(extractionId, "closing_date", "date")

        when: "finding all by their IDs"
            def results = extractionFieldRepository.findAllByIds([f1.id, f2.id])

        then: "both fields are returned"
            results.size() == 2
            results.any { it.id == f1.id }
            results.any { it.id == f2.id }
    }

    def "TC-RF-FA02: should return empty list for empty ID list"() {
        when: "finding with empty list"
            def results = extractionFieldRepository.findAllByIds([])

        then: "empty list is returned without querying the database"
            results.isEmpty()
    }

    def "TC-RF-FA03: should return empty list for null ID list"() {
        when: "finding with null list"
            def results = extractionFieldRepository.findAllByIds(null)

        then: "empty list is returned without querying the database"
            results.isEmpty()
    }

    def "TC-RF-FA04: should return only found records when some IDs do not exist"() {
        given: "one real field and one fake ID"
            def real = insertFieldRecord(extractionId, "name", "text")
            def fakeId = "nonexistent-field-id"

        when: "finding by both IDs"
            def results = extractionFieldRepository.findAllByIds([real.id, fakeId])

        then: "only the found record is returned"
            results.size() == 1
            results[0].id == real.id
    }

    // ==================== deleteByExtractionId ====================

    def "TC-RF-DB01: should delete all fields for an extraction leaving other extractions untouched"() {
        given: "a second extraction with its own fields"
            def otherExtractionId = UUID.randomUUID().toString()
            insertExtractionRow(otherExtractionId, companyIdStr, tenderId)

            insertFieldRecord(extractionId, "name", "text")
            insertFieldRecord(extractionId, "location", "text")
            insertFieldRecord(extractionId, "closing_date", "date")
            insertFieldRecord(otherExtractionId, "name", "text")

        when: "deleting all fields for the first extraction"
            def deletedCount = extractionFieldRepository.deleteByExtractionId(extractionId)

        then: "3 fields are deleted"
            deletedCount == 3

        and: "the other extraction's fields are untouched"
            def remaining = dsl.selectFrom(Tables.EXTRACTION_FIELDS)
                .where(Tables.EXTRACTION_FIELDS.EXTRACTION_ID.eq(otherExtractionId))
                .fetch()
            remaining.size() == 1
    }

    def "TC-RF-DB02: should return 0 when no fields exist for the extraction"() {
        when: "deleting fields for an extraction that has none"
            def deletedCount = extractionFieldRepository.deleteByExtractionId(extractionId)

        then: "0 rows deleted"
            deletedCount == 0
    }

    def "TC-RF-DB03: should cascade-delete fields when parent extraction is hard-deleted"() {
        given: "extraction fields for the extraction"
            insertFieldRecord(extractionId, "name", "text")
            insertFieldRecord(extractionId, "closing_date", "date")

        when: "hard-deleting the parent extraction (ON DELETE CASCADE)"
            dsl.deleteFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extractionId))
                .execute()

        then: "the extraction fields are also deleted via cascade"
            def remaining = dsl.selectFrom(Tables.EXTRACTION_FIELDS)
                .where(Tables.EXTRACTION_FIELDS.EXTRACTION_ID.eq(extractionId))
                .fetch()
            remaining.isEmpty()
    }

    // ==================== Helper Methods ====================

    private ExtractionFieldsRecord insertFieldRecord(String extractionId, String fieldName, String fieldType) {
        def record = new ExtractionFieldsRecord()
        record.setId(UUID.randomUUID().toString())
        record.setExtractionId(extractionId)
        record.setFieldName(fieldName)
        record.setFieldType(fieldType)
        record.setHasConflict(false)
        record.setStatus("extracted")
        dsl.insertInto(Tables.EXTRACTION_FIELDS).set(record).returning().fetchSingle()
    }

    private void insertExtractionRow(String id, String companyId, String entityId) {
        dsl.insertInto(Tables.EXTRACTIONS)
            .set(Tables.EXTRACTIONS.ID, id)
            .set(Tables.EXTRACTIONS.COMPANY_ID, companyId)
            .set(Tables.EXTRACTIONS.ENTITY_TYPE, "tender")
            .set(Tables.EXTRACTIONS.ENTITY_ID, entityId)
            .set(Tables.EXTRACTIONS.STATUS, "pending")
            .set(Tables.EXTRACTIONS.DOCUMENT_IDS, JSONB.valueOf('["doc-1"]'))
            .set(Tables.EXTRACTIONS.VERSION, 0)
            .set(Tables.EXTRACTIONS.CREATED_BY, "user-1")
            .execute()
    }
}
