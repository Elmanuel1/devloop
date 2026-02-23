package com.tosspaper.precon

import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
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
        given: "fields for a tender"
            def fields = [
                name: "Bridge RFP",
                created_by: "user-1",
                currency: "CAD",
                delivery_method: "lump_sum",
                platform: "https://bidsandtenders.ca"
            ]

        when: "inserting"
            def record = tenderRepository.insert(companyIdStr, fields)

        then: "record is persisted"
            record != null
            record.id != null
            record.name == "Bridge RFP"
            record.companyId == companyIdStr
            record.status == "draft"
            record.currency == "CAD"
            record.deliveryMethod == "lump_sum"
            record.platform == "https://bidsandtenders.ca"
            record.createdAt != null
    }

    def "should insert tender with null optional fields"() {
        given: "minimal fields"
            def fields = [
                name: "Minimal Tender",
                created_by: "user-1"
            ]

        when: "inserting"
            def record = tenderRepository.insert(companyIdStr, fields)

        then: "record persisted with nulls"
            record != null
            record.name == "Minimal Tender"
            record.currency == null
            record.bonds == null
            record.conditions == null
    }

    def "should allow duplicate name for different company"() {
        given: "a tender in company 1"
            tenderRepository.insert(companyIdStr, [name: "Bridge RFP", created_by: "user-1"])

        when: "inserting same name for company 999"
            def record = tenderRepository.insert("999", [name: "Bridge RFP", created_by: "user-2"])

        then: "no exception"
            record != null
            record.name == "Bridge RFP"
    }

    // ==================== findById ====================

    def "should find tender by id"() {
        given: "a tender"
            def inserted = tenderRepository.insert(companyIdStr, [name: "Find Me", created_by: "user-1"])

        when: "finding by id"
            def result = tenderRepository.findById(inserted.id)

        then: "record found"
            result.isPresent()
            result.get().name == "Find Me"
    }

    def "should return empty when tender is soft-deleted"() {
        given: "a soft-deleted tender"
            def inserted = tenderRepository.insert(companyIdStr, [name: "Deleted", created_by: "user-1"])
            tenderRepository.softDelete(inserted.id)

        when: "finding by id"
            def result = tenderRepository.findById(inserted.id)

        then: "empty returned"
            !result.isPresent()
    }

    def "should return empty when tender does not exist"() {
        when: "finding nonexistent"
            def result = tenderRepository.findById("nonexistent-id")

        then: "empty returned"
            !result.isPresent()
    }

    // ==================== findByCompanyId ====================

    def "should return tenders scoped to company"() {
        given: "tenders in two companies"
            tenderRepository.insert(companyIdStr, [name: "Company A - 1", created_by: "user-1"])
            tenderRepository.insert(companyIdStr, [name: "Company A - 2", created_by: "user-1"])
            tenderRepository.insert("999", [name: "Company B - 1", created_by: "user-2"])

        and: "a query"
            def query = TenderQuery.builder().limit(20).sortBy("created_at").sortDirection("desc").build()

        when: "listing for company A"
            def results = tenderRepository.findByCompanyId(companyIdStr, query)

        then: "only company A tenders returned"
            results.size() == 2
    }

    def "should exclude soft-deleted tenders"() {
        given: "tenders, one soft-deleted"
            def t1 = tenderRepository.insert(companyIdStr, [name: "Active", created_by: "user-1"])
            def t2 = tenderRepository.insert(companyIdStr, [name: "Deleted", created_by: "user-1"])
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
            tenderRepository.insert(companyIdStr, [name: "Bridge RFP", created_by: "user-1"])
            tenderRepository.insert(companyIdStr, [name: "Road Work", created_by: "user-1"])

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
            tenderRepository.insert(companyIdStr, [name: "Draft", created_by: "user-1"])
            def tender2 = tenderRepository.insert(companyIdStr, [name: "Pending", created_by: "user-1"])
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
                tenderRepository.insert(companyIdStr, [name: "Tender " + it.toString(), created_by: "user-1"])
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
            def inserted = tenderRepository.insert(companyIdStr, [name: "Original", created_by: "user-1"])

        when: "updating with correct version"
            def rowsUpdated = tenderRepository.update(inserted.id, [name: "Updated"], 0)

        then: "1 row updated"
            rowsUpdated == 1

        and: "name changed"
            def updated = tenderRepository.findById(inserted.id).get()
            updated.name == "Updated"
    }

    def "should return 0 rows when version mismatch"() {
        given: "a tender"
            def inserted = tenderRepository.insert(companyIdStr, [name: "Original", created_by: "user-1"])

        when: "updating with wrong version"
            def rowsUpdated = tenderRepository.update(inserted.id, [name: "Conflict"], 99)

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "should return 0 rows when tender not found"() {
        when: "updating nonexistent"
            def rowsUpdated = tenderRepository.update("nonexistent-id", [name: "Ghost"], 0)

        then: "0 rows updated"
            rowsUpdated == 0
    }

    def "should update only provided fields (partial update)"() {
        given: "a tender with name and currency"
            def inserted = tenderRepository.insert(companyIdStr, [name: "Original", currency: "CAD", created_by: "user-1"])

        when: "updating only name"
            tenderRepository.update(inserted.id, [name: "New Name"], 0)

        then: "name changed, currency unchanged"
            def updated = tenderRepository.findById(inserted.id).get()
            updated.name == "New Name"
            updated.currency == "CAD"
    }

    // ==================== softDelete ====================

    def "should soft-delete a tender"() {
        given: "a tender"
            def inserted = tenderRepository.insert(companyIdStr, [name: "To Delete", created_by: "user-1"])

        when: "soft-deleting"
            def rowsUpdated = tenderRepository.softDelete(inserted.id)

        then: "1 row updated"
            rowsUpdated == 1

        and: "findById returns empty"
            !tenderRepository.findById(inserted.id).isPresent()
    }
}
