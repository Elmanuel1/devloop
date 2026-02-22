package com.tosspaper.precon

import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired

import java.time.OffsetDateTime

class TenderDocumentRepositorySpec extends BaseIntegrationTest {

    @Autowired
    DSLContext dsl

    @Autowired
    TenderDocumentRepository repository

    Long companyId
    String companyIdStr
    String tenderAId
    String tenderBId

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

        tenderAId = insertTender("Tender A")
        tenderBId = insertTender("Tender B")
    }

    def cleanup() {
        dsl.deleteFrom(Tables.TENDER_DOCUMENTS)
            .where(Tables.TENDER_DOCUMENTS.COMPANY_ID.eq(companyIdStr))
            .execute()
        dsl.deleteFrom(Tables.TENDERS)
            .where(Tables.TENDERS.COMPANY_ID.eq(companyIdStr))
            .execute()
    }

    def "should return documents scoped to tender"() {
        given: "Tender A has 2 docs, Tender B has 1"
            insertDocument(tenderAId, "docA1.pdf", "ready")
            insertDocument(tenderAId, "docA2.pdf", "ready")
            insertDocument(tenderBId, "docB1.pdf", "ready")

        when:
            def results = repository.findByTenderId(tenderAId, null, 20, null, null)

        then:
            results.size() == 2
    }

    def "should exclude soft-deleted documents"() {
        given: "2 docs, 1 soft-deleted"
            def doc1 = insertDocument(tenderAId, "active.pdf", "ready")
            def doc2 = insertDocument(tenderAId, "deleted.pdf", "ready")
            dsl.update(Tables.TENDER_DOCUMENTS)
                .set(Tables.TENDER_DOCUMENTS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.TENDER_DOCUMENTS.ID.eq(doc2))
                .execute()

        when:
            def results = repository.findByTenderId(tenderAId, null, 20, null, null)

        then:
            results.size() == 1
    }

    def "should filter by status"() {
        given: "1 uploading, 1 ready"
            insertDocument(tenderAId, "uploading.pdf", "uploading")
            insertDocument(tenderAId, "ready.pdf", "ready")

        when:
            def results = repository.findByTenderId(tenderAId, "ready", 20, null, null)

        then:
            results.size() == 1
            results[0].status == "ready"
    }

    def "should paginate with cursor"() {
        given: "3 docs, limit=2"
            insertDocument(tenderAId, "doc1.pdf", "ready")
            Thread.sleep(10) // ensure different timestamps
            insertDocument(tenderAId, "doc2.pdf", "ready")
            Thread.sleep(10)
            insertDocument(tenderAId, "doc3.pdf", "ready")

        when: "first page"
            def page1 = repository.findByTenderId(tenderAId, null, 2, null, null)

        then: "returns 3 (2 + 1 for has_more check)"
            page1.size() == 3

        when: "take the 2nd item as cursor"
            def cursor = page1[1]
            def page2 = repository.findByTenderId(tenderAId, null, 2,
                cursor.createdAt.toString(), cursor.id)

        then: "returns remaining item(s)"
            page2.size() >= 1
    }

    def "should insert and find by id"() {
        given:
            def docId = UUID.randomUUID().toString()

        when:
            repository.insert(docId, tenderAId, companyIdStr, "test.pdf",
                "application/pdf", 1024L, "s3/key", "uploading")

        then:
            def found = repository.findById(docId)
            found.isPresent()
            found.get().fileName == "test.pdf"
            found.get().status == "uploading"
    }

    def "should soft delete and exclude from findById"() {
        given:
            def docId = insertDocument(tenderAId, "to-delete.pdf", "ready")

        when:
            repository.softDelete(docId)

        then:
            repository.findById(docId).isEmpty()
    }

    // ==================== Helpers ====================

    private String insertTender(String name) {
        def id = UUID.randomUUID().toString()
        dsl.insertInto(Tables.TENDERS)
            .set(Tables.TENDERS.ID, id)
            .set(Tables.TENDERS.COMPANY_ID, companyIdStr)
            .set(Tables.TENDERS.NAME, name)
            .set(Tables.TENDERS.STATUS, "draft")
            .set(Tables.TENDERS.CREATED_BY, "test-user")
            .execute()
        return id
    }

    private String insertDocument(String tenderId, String fileName, String status) {
        def id = UUID.randomUUID().toString()
        dsl.insertInto(Tables.TENDER_DOCUMENTS)
            .set(Tables.TENDER_DOCUMENTS.ID, id)
            .set(Tables.TENDER_DOCUMENTS.TENDER_ID, tenderId)
            .set(Tables.TENDER_DOCUMENTS.COMPANY_ID, companyIdStr)
            .set(Tables.TENDER_DOCUMENTS.FILE_NAME, fileName)
            .set(Tables.TENDER_DOCUMENTS.CONTENT_TYPE, "application/pdf")
            .set(Tables.TENDER_DOCUMENTS.FILE_SIZE, 1024L)
            .set(Tables.TENDER_DOCUMENTS.S3_KEY, "tender-uploads/${companyIdStr}/${tenderId}/${id}/${fileName}")
            .set(Tables.TENDER_DOCUMENTS.STATUS, status)
            .execute()
        return id
    }
}
