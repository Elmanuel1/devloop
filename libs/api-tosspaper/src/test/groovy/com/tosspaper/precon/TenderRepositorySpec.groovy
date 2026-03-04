package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.TendersRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

class TenderRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    TenderRepository tenderRepository

    Long companyId
    String companyIdStr

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID
        companyIdStr = companyId.toString()

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev-clientdocs.useassetiq.com")
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.TENDERS)
            .where(Tables.TENDERS.COMPANY_ID.eq(companyIdStr)
                .or(Tables.TENDERS.COMPANY_ID.eq("999")))
            .execute()
    }

    // ==================== insert ====================

    def "should insert tender with all fields and return record"() {
        given: "a tender record with all fields set"
            def record = buildRecord(companyIdStr, "Bridge RFP", "user-1")
            record.setCurrency("CAD")
            record.setDeliveryMethod("lump_sum")
            record.setPlatform("https://bidsandtenders.ca")

        when: "inserting"
            def inserted = tenderRepository.insert(record)

        then: "record is persisted with all fields"
            inserted != null
            inserted.id != null
            inserted.name == "Bridge RFP"
            inserted.companyId == companyIdStr
            inserted.status == "pending"
            inserted.currency == "CAD"
            inserted.deliveryMethod == "lump_sum"
            inserted.platform == "https://bidsandtenders.ca"
            inserted.createdAt != null
    }

    def "should insert tender with null optional fields"() {
        given: "minimal record"
            def record = buildRecord(companyIdStr, "Minimal Tender", "user-1")

        when: "inserting"
            def inserted = tenderRepository.insert(record)

        then: "record persisted with nulls"
            inserted != null
            inserted.name == "Minimal Tender"
            inserted.currency == null
            inserted.bonds == null
            inserted.conditions == null
    }

    // ==================== null name insert (TOS-45) ====================

    def "should insert tender with null name and persist null"() {
        given: "a tender record with no name"
            def record = buildNullNameRecord(companyIdStr, "user-1")

        when: "inserting"
            def inserted = tenderRepository.insert(record)

        then: "record persisted with name = null"
            inserted != null
            inserted.id != null
            inserted.name == null
            inserted.status == "pending"
            inserted.companyId == companyIdStr
            inserted.createdAt != null
    }

    def "should insert two tenders with null names for same company without constraint violation"() {
        given: "two records with no name for the same company"
            def record1 = buildNullNameRecord(companyIdStr, "user-1")
            def record2 = buildNullNameRecord(companyIdStr, "user-2")

        when: "inserting both"
            def inserted1 = tenderRepository.insert(record1)
            def inserted2 = tenderRepository.insert(record2)

        then: "both records persisted — null names are NULLS DISTINCT in the unique index"
            inserted1 != null
            inserted1.name == null
            inserted2 != null
            inserted2.name == null
            inserted1.id != inserted2.id
    }

    def "should allow duplicate name for different company"() {
        given: "a tender in company 1"
            tenderRepository.insert(buildRecord(companyIdStr, "Bridge RFP", "user-1"))

        when: "inserting same name for company 999"
            def inserted = tenderRepository.insert(buildRecord("999", "Bridge RFP", "user-2"))

        then: "no exception"
            inserted != null
            inserted.name == "Bridge RFP"
    }

    def "should allow a name to be populated later (simulate background job update)"() {
        given: "a nameless tender"
            def record = buildNullNameRecord(companyIdStr, "user-1")
            def inserted = tenderRepository.insert(record)

        when: "updating the name via direct SQL (simulating a background job)"
            dsl.update(Tables.TENDERS)
                .set(Tables.TENDERS.NAME, "Populated by Job")
                .where(Tables.TENDERS.ID.eq(inserted.id))
                .execute()

        then: "name is now populated"
            def updated = tenderRepository.findById(inserted.id)
            updated.name == "Populated by Job"
    }

    // ==================== findById ====================

    def "should find tender by id"() {
        given: "a tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "Find Me", "user-1"))

        when: "finding by id"
            def result = tenderRepository.findById(inserted.id)

        then: "record found"
            result.name == "Find Me"
    }

    def "should throw NotFoundException when tender is soft-deleted"() {
        given: "a soft-deleted tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "Deleted", "user-1"))
            tenderRepository.softDelete(inserted.id)

        when: "finding by id"
            tenderRepository.findById(inserted.id)

        then: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    def "should throw NotFoundException when tender does not exist"() {
        when: "finding nonexistent"
            tenderRepository.findById("nonexistent-id")

        then: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    // ==================== findByCompanyId — pending filter (TOS-45) ====================

    def "should exclude pending tenders from default list (no status filter)"() {
        given: "one pending and one submitted tender for the same company"
            tenderRepository.insert(buildRecord(companyIdStr, "Pending One", "user-1"))
            def submitted = tenderRepository.insert(buildRecord(companyIdStr, "Submitted One", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(submitted.id)).execute()

        and: "a query with no status filter"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing without a status filter"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "pending tender is excluded; only submitted tender is returned"
            results.size() == 1
            results[0].name == "Submitted One"
            results[0].status == "submitted"
    }

    def "should return pending tenders when status=pending is explicitly passed"() {
        given: "one pending and one submitted tender"
            def pending = tenderRepository.insert(buildRecord(companyIdStr, "Pending One", "user-1"))
            def submitted = tenderRepository.insert(buildRecord(companyIdStr, "Submitted One", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(submitted.id)).execute()

        and: "a query with explicit status=pending"
            def query = TenderQuery.builder().status("pending").limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "filtering by status=pending"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only the pending tender is returned"
            results.size() == 1
            results[0].name == "Pending One"
            results[0].status == "pending"
    }

    def "should return empty list when all tenders are pending and no status filter is supplied"() {
        given: "two pending tenders and no non-pending tenders"
            tenderRepository.insert(buildRecord(companyIdStr, "Pending One", "user-1"))
            tenderRepository.insert(buildRecord(companyIdStr, "Pending Two", "user-1"))

        and: "a query with no status filter"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing without a status filter"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "empty list returned — all tenders are pending and hidden by default"
            results.isEmpty()
    }

    def "should handle mixed statuses and only exclude pending from default list"() {
        given: "tenders with all possible statuses"
            tenderRepository.insert(buildRecord(companyIdStr, "Pending", "user-1"))
            def submitted = tenderRepository.insert(buildRecord(companyIdStr, "Submitted", "user-1"))
            def won = tenderRepository.insert(buildRecord(companyIdStr, "Won", "user-1"))
            def lost = tenderRepository.insert(buildRecord(companyIdStr, "Lost", "user-1"))
            def cancelled = tenderRepository.insert(buildRecord(companyIdStr, "Cancelled", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(submitted.id)).execute()
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "won")
                .where(Tables.TENDERS.ID.eq(won.id)).execute()
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "lost")
                .where(Tables.TENDERS.ID.eq(lost.id)).execute()
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "cancelled")
                .where(Tables.TENDERS.ID.eq(cancelled.id)).execute()

        and: "a query with no status filter"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing without a status filter"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "submitted, won, lost, cancelled are returned — pending is excluded"
            results.size() == 4
            results.every { it.status != "pending" }
            results.collect { it.name }.sort() == ["Cancelled", "Lost", "Submitted", "Won"]
    }

    // ==================== findByCompanyId — general ====================

    def "should return tenders scoped to company"() {
        given: "submitted tenders in two companies"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "Company A - 1", "user-1"))
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Company A - 2", "user-1"))
            def t3 = tenderRepository.insert(buildRecord("999", "Company B - 1", "user-2"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(t1.id, t2.id, t3.id)).execute()

        and: "a query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing for company A"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only company A tenders returned"
            results.size() == 2
            results.every { it.companyId == companyIdStr }
    }

    def "should exclude soft-deleted tenders"() {
        given: "tenders — one submitted (active), one soft-deleted"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "Active", "user-1"))
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Deleted", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(t1.id)).execute()
            tenderRepository.softDelete(t2.id)

        and: "a query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only the active submitted tender is returned"
            results.size() == 1
            results[0].name == "Active"
    }

    def "should filter by status=submitted explicitly"() {
        given: "tenders with different statuses"
            tenderRepository.insert(buildRecord(companyIdStr, "Pending One", "user-1"))
            def tender2 = tenderRepository.insert(buildRecord(companyIdStr, "Submitted One", "user-1"))
            def tender3 = tenderRepository.insert(buildRecord(companyIdStr, "Won One", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(tender2.id)).execute()
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "won")
                .where(Tables.TENDERS.ID.eq(tender3.id)).execute()

        and: "status=submitted filter query"
            def query = TenderQuery.builder().status("submitted").limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "filtering"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only submitted tender returned"
            results.size() == 1
            results[0].name == "Submitted One"
            results[0].status == "submitted"
    }

    def "should respect limit parameter"() {
        given: "5 submitted tenders"
            5.times { i ->
                def r = buildRecord(companyIdStr, "Tender ${i}", "user-1")
                def inserted = tenderRepository.insert(r)
                dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                    .where(Tables.TENDERS.ID.eq(inserted.id)).execute()
            }

        and: "query with limit 2 (repo fetches limit+1 for has_more)"
            def query = TenderQuery.builder().limit(2).sortBy("created_at").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "returns limit+1 for has_more detection"
            results.size() == 3
    }

    // ==================== sorting ====================

    def "should sort by created_at desc by default"() {
        given: "submitted tenders inserted in order"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "First", "user-1"))
            Thread.sleep(50)
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Second", "user-1"))
            Thread.sleep(50)
            def t3 = tenderRepository.insert(buildRecord(companyIdStr, "Third", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(t1.id, t2.id, t3.id)).execute()

        and: "default sort query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "newest first"
            results[0].name == "Third"
            results[1].name == "Second"
            results[2].name == "First"
    }

    def "should sort by created_at asc"() {
        given: "submitted tenders inserted in order"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "First", "user-1"))
            Thread.sleep(50)
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Second", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(t1.id, t2.id)).execute()

        and: "asc sort query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("asc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "oldest first"
            results[0].name == "First"
            results[1].name == "Second"
    }

    def "should sort by closing_date desc with nulls last"() {
        given: "submitted tenders with and without closing dates"
            def r1 = buildRecord(companyIdStr, "No Date", "user-1")
            def inserted1 = tenderRepository.insert(r1)

            def r2 = buildRecord(companyIdStr, "Far Future", "user-1")
            r2.setClosingDate(OffsetDateTime.now().plusDays(30))
            def inserted2 = tenderRepository.insert(r2)

            def r3 = buildRecord(companyIdStr, "Soon", "user-1")
            r3.setClosingDate(OffsetDateTime.now().plusDays(5))
            def inserted3 = tenderRepository.insert(r3)

            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(inserted1.id, inserted2.id, inserted3.id)).execute()

        and: "closing_date desc query"
            def query = TenderQuery.builder().limit(20).sortBy("closing_date").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "furthest date first, nulls last"
            results[0].name == "Far Future"
            results[1].name == "Soon"
            results[2].name == "No Date"
    }

    def "should sort by closing_date asc with nulls last"() {
        given: "submitted tenders with and without closing dates"
            def r1 = buildRecord(companyIdStr, "No Date", "user-1")
            def inserted1 = tenderRepository.insert(r1)

            def r2 = buildRecord(companyIdStr, "Far Future", "user-1")
            r2.setClosingDate(OffsetDateTime.now().plusDays(30))
            def inserted2 = tenderRepository.insert(r2)

            def r3 = buildRecord(companyIdStr, "Soon", "user-1")
            r3.setClosingDate(OffsetDateTime.now().plusDays(5))
            def inserted3 = tenderRepository.insert(r3)

            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(inserted1.id, inserted2.id, inserted3.id)).execute()

        and: "closing_date asc query"
            def query = TenderQuery.builder().limit(20).sortBy("closing_date").sortDirection("asc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "soonest first, nulls last"
            results[0].name == "Soon"
            results[1].name == "Far Future"
            results[2].name == "No Date"
    }

    // ==================== cursor pagination (TOS-45: filter works correctly with cursor) ====================

    def "should return next page using cursor with pending filter active"() {
        given: "3 submitted tenders inserted in order"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "First", "user-1"))
            Thread.sleep(50)
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Second", "user-1"))
            Thread.sleep(50)
            def t3 = tenderRepository.insert(buildRecord(companyIdStr, "Third", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.in(t1.id, t2.id, t3.id)).execute()

        and: "first page with limit 1 (no status filter — pending excluded)"
            def page1Query = TenderQuery.builder().limit(1).sortBy("created_at").sortDirection("desc").build()
            def page1 = tenderRepository.findByCompanyId(companyIdStr, page1Query)

        and: "cursor from last record of page 1"
            def lastRecord = page1[0] // "Third" (newest)

        when: "fetching second page using cursor"
            def page2Query = TenderQuery.builder()
                .limit(1)
                .sortBy("created_at")
                .sortDirection("desc")
                .cursorCreatedAt(lastRecord.createdAt)
                .cursorId(lastRecord.id)
                .build()
            def page2 = tenderRepository.findByCompanyId(companyIdStr, page2Query)

        then: "returns next records after cursor — pending tenders do not appear even with cursor"
            page2.size() >= 1
            page2[0].name == "Second"
    }

    def "should return empty when cursor is past last record"() {
        given: "a single submitted tender"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "Only", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "submitted")
                .where(Tables.TENDERS.ID.eq(t1.id)).execute()
            // reload to get current createdAt from DB
            def loaded = tenderRepository.findById(t1.id)

        when: "fetching with cursor past this record"
            def query = TenderQuery.builder()
                .limit(20)
                .sortBy("created_at")
                .sortDirection("desc")
                .cursorCreatedAt(loaded.createdAt)
                .cursorId(loaded.id)
                .build()
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "empty results"
            results.isEmpty()
    }

    // ==================== update ====================

    def "should update tender atomically with version guard"() {
        given: "a tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "Original", "user-1"))

        and: "a record with changed name"
            def updateRecord = new TendersRecord()
            updateRecord.setName("Updated")
            updateRecord.changed(Tables.TENDERS.NAME, true)

        when: "updating with correct version"
            def rowsUpdated = tenderRepository.update(inserted.id, updateRecord, 0)

        then: "1 row updated"
            rowsUpdated == 1

        and: "name changed"
            def updated = tenderRepository.findById(inserted.id)
            updated.name == "Updated"
    }

    def "should return 0 rows when version mismatch"() {
        given: "a tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "Original", "user-1"))

        and: "a record with changed name"
            def updateRecord = new TendersRecord()
            updateRecord.setName("Conflict")
            updateRecord.changed(Tables.TENDERS.NAME, true)

        when: "updating with wrong version"
            def rowsUpdated = tenderRepository.update(inserted.id, updateRecord, 99)

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "should return 0 rows when tender not found"() {
        given: "a record with changed name"
            def updateRecord = new TendersRecord()
            updateRecord.setName("Ghost")
            updateRecord.changed(Tables.TENDERS.NAME, true)

        when: "updating nonexistent"
            def rowsUpdated = tenderRepository.update("nonexistent-id", updateRecord, 0)

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "should update only provided fields (partial update)"() {
        given: "a tender with name and currency"
            def record = buildRecord(companyIdStr, "Original", "user-1")
            record.setCurrency("CAD")
            def inserted = tenderRepository.insert(record)

        and: "a record with only name changed"
            def updateRecord = new TendersRecord()
            updateRecord.setName("New Name")
            updateRecord.changed(Tables.TENDERS.NAME, true)

        when: "updating only name"
            tenderRepository.update(inserted.id, updateRecord, 0)

        then: "name changed, currency unchanged"
            def updated = tenderRepository.findById(inserted.id)
            updated.name == "New Name"
            updated.currency == "CAD"
    }

    // ==================== softDelete ====================

    def "should soft-delete a tender"() {
        given: "a tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "To Delete", "user-1"))

        when: "soft-deleting"
            def rowsUpdated = tenderRepository.softDelete(inserted.id)

        then: "1 row updated"
            rowsUpdated == 1
    }

    def "should not find soft-deleted tender"() {
        given: "a soft-deleted tender"
            def inserted = tenderRepository.insert(buildRecord(companyIdStr, "To Delete", "user-1"))
            tenderRepository.softDelete(inserted.id)

        when: "finding by id"
            tenderRepository.findById(inserted.id)

        then: "NotFoundException thrown"
            thrown(NotFoundException)
    }

    // ==================== Helper Methods ====================

    private static TendersRecord buildRecord(String companyId, String name, String createdBy) {
        def record = new TendersRecord()
        record.setId(UUID.randomUUID().toString())
        record.setCompanyId(companyId)
        record.setName(name)
        record.setStatus("pending")
        record.setCreatedBy(createdBy)
        return record
    }

    private static TendersRecord buildNullNameRecord(String companyId, String createdBy) {
        def record = new TendersRecord()
        record.setId(UUID.randomUUID().toString())
        record.setCompanyId(companyId)
        record.setName(null)
        record.setStatus("pending")
        record.setCreatedBy(createdBy)
        return record
    }
}
