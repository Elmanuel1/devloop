package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.TendersRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

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
        given: "a tender record"
            def record = buildRecord(companyIdStr, "Bridge RFP", "user-1")
            record.setCurrency("CAD")
            record.setDeliveryMethod("lump_sum")
            record.setPlatform("https://bidsandtenders.ca")

        when: "inserting"
            def inserted = tenderRepository.insert(record)

        then: "record is persisted"
            inserted != null
            inserted.id != null
            inserted.name == "Bridge RFP"
            inserted.companyId == companyIdStr
            inserted.status == "draft"
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

    def "should allow duplicate name for different company"() {
        given: "a tender in company 1"
            tenderRepository.insert(buildRecord(companyIdStr, "Bridge RFP", "user-1"))

        when: "inserting same name for company 999"
            def inserted = tenderRepository.insert(buildRecord("999", "Bridge RFP", "user-2"))

        then: "no exception"
            inserted != null
            inserted.name == "Bridge RFP"
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

    // ==================== findByCompanyId ====================

    def "should return tenders scoped to company"() {
        given: "tenders in two companies"
            tenderRepository.insert(buildRecord(companyIdStr, "Company A - 1", "user-1"))
            tenderRepository.insert(buildRecord(companyIdStr, "Company A - 2", "user-1"))
            tenderRepository.insert(buildRecord("999", "Company B - 1", "user-2"))

        and: "a query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing for company A"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only company A tenders returned"
            results.size() == 2
    }

    def "should exclude soft-deleted tenders"() {
        given: "tenders, one soft-deleted"
            def t1 = tenderRepository.insert(buildRecord(companyIdStr, "Active", "user-1"))
            def t2 = tenderRepository.insert(buildRecord(companyIdStr, "Deleted", "user-1"))
            tenderRepository.softDelete(t2.id)

        and: "a query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only active returned"
            results.size() == 1
            results[0].name == "Active"
    }

    def "should search by name case-insensitively"() {
        given: "tenders"
            tenderRepository.insert(buildRecord(companyIdStr, "Bridge RFP", "user-1"))
            tenderRepository.insert(buildRecord(companyIdStr, "Road Work", "user-1"))

        and: "a search query"
            def query = TenderQuery.builder().search("bridge").limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "searching"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only matching returned"
            results.size() == 1
            results[0].name == "Bridge RFP"
    }

    def "should filter by status"() {
        given: "tenders with different statuses"
            tenderRepository.insert(buildRecord(companyIdStr, "Draft", "user-1"))
            def tender2 = tenderRepository.insert(buildRecord(companyIdStr, "Pending", "user-1"))
            dsl.update(Tables.TENDERS).set(Tables.TENDERS.STATUS, "pending")
                .where(Tables.TENDERS.ID.eq(tender2.id)).execute()

        and: "status filter query"
            def query = TenderQuery.builder().status("draft").limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "filtering"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only draft returned"
            results.size() == 1
            results[0].status == "draft"
    }

    def "should respect limit parameter"() {
        given: "5 tenders"
            5.times {
                tenderRepository.insert(buildRecord(companyIdStr, "Tender " + it.toString(), "user-1"))
            }

        and: "query with limit 2 (repo fetches limit+1 for has_more)"
            def query = TenderQuery.builder().limit(2).sortBy("created_at").sortDirection("desc").build()

        when: "listing"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "returns limit+1 for has_more detection"
            results.size() == 3
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
        record.setStatus("draft")
        record.setCreatedBy(createdBy)
        return record
    }
}
