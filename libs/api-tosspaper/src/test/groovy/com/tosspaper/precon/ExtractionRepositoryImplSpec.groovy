package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

class ExtractionRepositoryImplSpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    ExtractionRepository extractionRepository

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

    // ==================== insert ====================

    def "TC-R-I01: should insert extraction with all fields and return persisted record"() {
        given: "a fully populated extraction record"
            def record = buildExtractionRecord(companyIdStr, tenderId, "user-1")
            record.setFieldNames(JSONB.valueOf('["closing_date","name"]'))
            record.setErrorReason("some reason")

        when: "inserting the record"
            def inserted = extractionRepository.insert(record)

        then: "record is fully persisted with version=0 and timestamps"
            inserted != null
            inserted.id == record.id
            inserted.companyId == companyIdStr
            inserted.entityType == "tender"
            inserted.entityId == tenderId
            inserted.status == "pending"
            inserted.documentIds == record.documentIds
            inserted.fieldNames == JSONB.valueOf('["closing_date","name"]')
            inserted.version == 0
            inserted.errorReason == "some reason"
            inserted.createdBy == "user-1"
            inserted.createdAt != null
            inserted.updatedAt != null
            inserted.deletedAt == null
    }

    def "TC-R-I02: should insert extraction with null fieldNames (optional column)"() {
        given: "an extraction record with no field names"
            def record = buildExtractionRecord(companyIdStr, tenderId, "user-1")
            // fieldNames left null by default

        when: "inserting the record"
            def inserted = extractionRepository.insert(record)

        then: "null persisted for the optional field_names column"
            inserted != null
            inserted.fieldNames == null
    }

    def "TC-R-I03: should return the new record via RETURNING clause"() {
        given: "an extraction record"
            def record = buildExtractionRecord(companyIdStr, tenderId, "user-1")

        when: "inserting"
            def returned = extractionRepository.insert(record)

        then: "the returned record has the same ID as the input"
            returned.id == record.id

        and: "the record is also findable in the database"
            def fetched = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(record.id))
                .fetchSingle()
            fetched.id == record.id
    }

    // ==================== findById ====================

    def "TC-R-FB01: should find existing extraction by id with all fields matching"() {
        given: "an extraction exists in the database"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "finding by ID"
            def result = extractionRepository.findById(inserted.id)

        then: "record is returned with correct fields"
            result != null
            result.id == inserted.id
            result.companyId == companyIdStr
            result.entityId == tenderId
            result.entityType == "tender"
            result.status == "pending"
            result.version == 0
    }

    def "TC-R-FB02: should throw NotFoundException when ID does not exist"() {
        when: "finding a non-existent ID"
            extractionRepository.findById("nonexistent-id")

        then: "NotFoundException is thrown with the correct code"
            def ex = thrown(NotFoundException)
            ex.code == "api.extraction.notFound"
    }

    def "TC-R-FB03: should throw NotFoundException for a soft-deleted extraction"() {
        given: "an extraction that is soft-deleted"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .execute()

        when: "finding by ID"
            extractionRepository.findById(inserted.id)

        then: "NotFoundException is thrown (deleted_at IS NULL filter)"
            thrown(NotFoundException)
    }

    def "TC-R-FB04: should find live extraction but not soft-deleted one when both exist"() {
        given: "one live and one soft-deleted extraction"
            def live = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            def deleted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(deleted.id))
                .execute()

        when: "finding the live extraction"
            def result = extractionRepository.findById(live.id)

        then: "live extraction is returned"
            result.id == live.id

        when: "finding the deleted extraction"
            extractionRepository.findById(deleted.id)

        then: "NotFoundException is thrown"
            thrown(NotFoundException)
    }

    // ==================== findByEntityId ====================

    def "TC-R-FE01: should return only matching company and entity extractions"() {
        given: "extractions for two different companies and entities"
            extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            extractionRepository.insert(buildExtractionRecord("other-company", tenderId, "user-1"))
            extractionRepository.insert(buildExtractionRecord(companyIdStr, "other-entity", "user-1"))

        when: "listing for the test company and entity"
            def query = ExtractionQuery.builder().limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "only the matching extraction is returned"
            results.size() == 1
            results[0].companyId == companyIdStr
            results[0].entityId == tenderId
    }

    def "TC-R-FE02: should exclude soft-deleted extractions"() {
        given: "one active and one soft-deleted extraction for the same entity"
            def active = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            def deleted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(deleted.id))
                .execute()

        when: "listing extractions"
            def query = ExtractionQuery.builder().limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "only the active extraction is returned"
            results.size() == 1
            results[0].id == active.id
    }

    def "TC-R-FE03: should filter by status returning only PENDING extractions"() {
        given: "extractions with different statuses"
            def pending = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            def processing = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.STATUS, "processing")
                .where(Tables.EXTRACTIONS.ID.eq(processing.id))
                .execute()

        when: "filtering with status=pending"
            def query = ExtractionQuery.builder().status("pending").limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "only pending extraction is returned"
            results.size() == 1
            results[0].id == pending.id
            results[0].status == "pending"
    }

    def "TC-R-FE04: should return all statuses when status filter is null"() {
        given: "extractions with different statuses"
            def e1 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            def e2 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.STATUS, "completed")
                .where(Tables.EXTRACTIONS.ID.eq(e2.id))
                .execute()

        when: "querying with null status"
            def query = ExtractionQuery.builder().limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "all non-deleted extractions are returned"
            results.size() == 2
    }

    def "TC-R-FE05: should return limit+1 for has_more detection"() {
        given: "4 extractions for the entity"
            4.times {
                extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            }

        when: "querying with limit=3"
            def query = ExtractionQuery.builder().limit(3).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "limit+1 = 4 records returned for has_more detection"
            results.size() == 4
    }

    def "TC-R-FE06: should return empty list when no extractions exist"() {
        when: "listing for an entity with no extractions"
            def query = ExtractionQuery.builder().limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "empty list is returned"
            results.isEmpty()
    }

    def "TC-R-FE07: should order by created_at DESC and id DESC (newest first)"() {
        given: "three extractions inserted with time gaps"
            def e1 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            Thread.sleep(50)
            def e2 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            Thread.sleep(50)
            def e3 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "listing extractions"
            def query = ExtractionQuery.builder().limit(20).build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "results are in newest-first order"
            results.size() == 3
            results[0].id == e3.id
            results[1].id == e2.id
            results[2].id == e1.id
    }

    def "TC-R-FE08: should return correct second page via cursor pagination"() {
        given: "three extractions inserted with time gaps"
            def e1 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            Thread.sleep(50)
            def e2 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            Thread.sleep(50)
            def e3 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        and: "first page with limit=1"
            def page1Query = ExtractionQuery.builder().limit(1).build()
            def page1 = extractionRepository.findByEntityId(companyIdStr, tenderId, page1Query)
            def lastOnPage1 = page1[0] // e3 (newest)

        when: "fetching second page using cursor from last record on page 1"
            def page2Query = ExtractionQuery.builder()
                .limit(1)
                .cursorCreatedAt(lastOnPage1.createdAt)
                .cursorId(lastOnPage1.id)
                .build()
            def page2 = extractionRepository.findByEntityId(companyIdStr, tenderId, page2Query)

        then: "second page contains the record after the cursor"
            page2[0].id == e2.id
    }

    def "TC-R-FE09: should return empty list when cursor is past the last record"() {
        given: "a single extraction"
            def e1 = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "fetching with a cursor pointing to that single record"
            def query = ExtractionQuery.builder()
                .limit(20)
                .cursorCreatedAt(e1.createdAt)
                .cursorId(e1.id)
                .build()
            def results = extractionRepository.findByEntityId(companyIdStr, tenderId, query)

        then: "empty list is returned (nothing before the cursor)"
            results.isEmpty()
    }

    // ==================== updateStatus ====================

    def "TC-R-US01: should update status and auto-increment version"() {
        given: "an existing extraction at version 0"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "updating status to processing"
            def rowsUpdated = extractionRepository.updateStatus(inserted.id, "processing")

        then: "1 row is updated"
            rowsUpdated == 1

        and: "status and version are changed in the database"
            def updated = extractionRepository.findById(inserted.id)
            updated.status == "processing"
            updated.version == 1
    }

    def "TC-R-US02: should return 0 for non-existent extraction ID"() {
        when: "updating status for a non-existent ID"
            def rowsUpdated = extractionRepository.updateStatus("nonexistent-id", "processing")

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "TC-R-US03: should return 0 for soft-deleted extraction (does not update)"() {
        given: "a soft-deleted extraction"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .execute()

        when: "trying to update status"
            def rowsUpdated = extractionRepository.updateStatus(inserted.id, "processing")

        then: "0 rows updated because deleted_at IS NULL filter excludes it"
            rowsUpdated == 0
    }

    // ==================== updateVersion (optimistic locking) ====================

    def "TC-R-IV01: should increment version when expected version matches (0 to 1)"() {
        given: "an extraction at version 0"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            inserted.version == 0

        when: "calling updateVersion with expected version 0"
            def rowsUpdated = extractionRepository.updateVersion(inserted.id, 0)

        then: "1 row updated"
            rowsUpdated == 1

        and: "version is now 1"
            def updated = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle()
            updated.version == 1
    }

    def "TC-R-IV02: should return 0 when expected version does not match (optimistic lock)"() {
        given: "an extraction at version 0"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "calling updateVersion with wrong expected version (stale)"
            def rowsUpdated = extractionRepository.updateVersion(inserted.id, 99)

        then: "0 rows updated"
            rowsUpdated == 0

        and: "version is unchanged in the database"
            def unchanged = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle()
            unchanged.version == 0
    }

    def "TC-R-IV03: should return 0 for non-existent extraction ID"() {
        when: "calling updateVersion for a non-existent ID"
            def rowsUpdated = extractionRepository.updateVersion("nonexistent-id", 0)

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "TC-R-IV04: should return 0 for soft-deleted extraction"() {
        given: "a soft-deleted extraction"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            dsl.update(Tables.EXTRACTIONS)
                .set(Tables.EXTRACTIONS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .execute()

        when: "calling updateVersion with the correct expected version"
            def rowsUpdated = extractionRepository.updateVersion(inserted.id, 0)

        then: "0 rows updated because deleted_at IS NULL filter excludes it"
            rowsUpdated == 0
    }

    def "TC-R-IV05: should correctly handle sequential version increments (0 to 1 to 2)"() {
        given: "an extraction at version 0"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "first increment with version 0"
            def first = extractionRepository.updateVersion(inserted.id, 0)

        then: "succeeds and version is 1"
            first == 1
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle().version == 1

        when: "second increment with version 1"
            def second = extractionRepository.updateVersion(inserted.id, 1)

        then: "succeeds and version is 2"
            second == 1
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle().version == 2

        when: "attempting increment with stale version 0"
            def stale = extractionRepository.updateVersion(inserted.id, 0)

        then: "fails — version is still 2"
            stale == 0
            dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle().version == 2
    }

    // ==================== softDelete ====================

    def "TC-R-SD01: should set deleted_at and increment version on soft-delete"() {
        given: "an existing extraction at version 0"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))

        when: "soft-deleting the extraction"
            def rowsUpdated = extractionRepository.softDelete(inserted.id)

        then: "1 row updated"
            rowsUpdated == 1

        and: "deleted_at is set and version is incremented in the database"
            def dbRecord = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(inserted.id))
                .fetchSingle()
            dbRecord.deletedAt != null
            dbRecord.version == 1
    }

    def "TC-R-SD02: should return 0 when already soft-deleted (idempotent)"() {
        given: "an already soft-deleted extraction"
            def inserted = extractionRepository.insert(buildExtractionRecord(companyIdStr, tenderId, "user-1"))
            extractionRepository.softDelete(inserted.id)

        when: "soft-deleting again"
            def rowsUpdated = extractionRepository.softDelete(inserted.id)

        then: "0 rows updated because deleted_at IS NULL filter excludes it"
            rowsUpdated == 0
    }

    def "TC-R-SD03: should return 0 for non-existent extraction ID"() {
        when: "soft-deleting a non-existent ID"
            def rowsUpdated = extractionRepository.softDelete("nonexistent-id")

        then: "0 rows updated"
            rowsUpdated == 0
    }

    // ==================== Helper Methods ====================

    private ExtractionsRecord buildExtractionRecord(String companyId, String entityId, String createdBy) {
        def record = new ExtractionsRecord()
        record.setId(UUID.randomUUID().toString())
        record.setCompanyId(companyId)
        record.setEntityType("tender")
        record.setEntityId(entityId)
        record.setStatus("pending")
        record.setDocumentIds(JSONB.valueOf('["doc-1","doc-2"]'))
        record.setVersion(0)
        record.setCreatedBy(createdBy)
        return record
    }
}
