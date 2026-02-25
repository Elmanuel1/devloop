package com.tosspaper.precon

import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

class TenderDocumentControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    DSLContext dsl

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

        tenderId = insertTender(companyIdStr, "Test Tender", "pending")
    }

    def cleanup() {
        dsl.deleteFrom(Tables.TENDER_DOCUMENTS)
            .where(Tables.TENDER_DOCUMENTS.TENDER_ID.eq(tenderId))
            .execute()
        dsl.deleteFrom(Tables.TENDERS)
            .where(Tables.TENDERS.COMPANY_ID.eq(companyIdStr))
            .execute()
    }

    // ==================== POST /v1/tenders/{tenderId}/documents/presigned-urls ====================

    def "POST /v1/tenders/{tenderId}/documents/presigned-urls returns 200 with presignedUrl, documentId, and expiration"() {
        given: "auth headers with content type"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "test.pdf", content_type: "application/pdf", file_size: 1024]

        when: "requesting upload presigned URL"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "returns 200 with presignedUrl, documentId, and expiration"
            response.statusCode == HttpStatus.OK
            response.body.presigned_url != null
            response.body.document_id != null
            response.body.expiration != null
    }

    def "POST /v1/tenders/{tenderId}/documents/presigned-urls returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "test.pdf", content_type: "application/pdf", file_size: 1024]

        when: "requesting upload presigned URL for non-existent tender"
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    def "POST /v1/tenders/{tenderId}/documents/presigned-urls returns 404 when tender belongs to other company"() {
        given: "a tender belonging to a different company"
            def otherTenderId = insertTender("999", "Other Company Tender", "pending")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "test.pdf", content_type: "application/pdf", file_size: 1024]

        when: "requesting upload presigned URL for other company's tender"
            def response = restTemplate.exchange(
                "/v1/tenders/${otherTenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null

        cleanup:
            dsl.deleteFrom(Tables.TENDERS)
                .where(Tables.TENDERS.ID.eq(otherTenderId))
                .execute()
    }

    def "POST /v1/tenders/{tenderId}/documents/presigned-urls returns 400 when X-Context-Id missing"() {
        given: "auth headers without X-Context-Id"
            def headers = buildAuthHeaders()
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "test.pdf", content_type: "application/pdf", file_size: 1024]

        when: "requesting upload presigned URL"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "returns 400"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    // ==================== GET /v1/tenders/{tenderId}/documents ====================

    def "GET /v1/tenders/{tenderId}/documents returns 200 with list of documents"() {
        given: "two documents for the tender"
            insertDocument(tenderId, companyIdStr, "report.pdf", "uploading")
            insertDocument(tenderId, companyIdStr, "drawing.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "listing documents"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 200 with both documents"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination != null
    }

    def "GET /v1/tenders/{tenderId}/documents returns 200 with status filter"() {
        given: "documents with different statuses"
            insertDocument(tenderId, companyIdStr, "uploading-doc.pdf", "uploading")
            insertDocument(tenderId, companyIdStr, "ready-doc.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "listing documents filtered by status ready"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents?status=ready",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 200 with only the ready document"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].status == "ready"
    }

    def "GET /v1/tenders/{tenderId}/documents returns 200 empty when no documents"() {
        given: "auth headers (no documents inserted)"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "listing documents"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 200 with empty data list"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
            response.body.pagination != null
    }

    def "GET /v1/tenders/{tenderId}/documents returns 404 when tender belongs to other company"() {
        given: "a tender belonging to a different company"
            def otherTenderId = insertTender("999", "Other Tender For List", "pending")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "listing documents for other company's tender"
            def response = restTemplate.exchange(
                "/v1/tenders/${otherTenderId}/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null

        cleanup:
            dsl.deleteFrom(Tables.TENDERS)
                .where(Tables.TENDERS.ID.eq(otherTenderId))
                .execute()
    }

    // ==================== DELETE /v1/tenders/{tenderId}/documents/{documentId} ====================

    def "DELETE /v1/tenders/{tenderId}/documents/{documentId} returns 204 on successful delete"() {
        given: "an existing document"
            def documentId = insertDocument(tenderId, companyIdStr, "delete-me.pdf", "uploading")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "deleting the document"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "returns 204 no content"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "document is soft-deleted in database"
            def deleted = dsl.selectFrom(Tables.TENDER_DOCUMENTS)
                .where(Tables.TENDER_DOCUMENTS.ID.eq(documentId))
                .fetchOne()
            deleted.deletedAt != null
    }

    def "DELETE /v1/tenders/{tenderId}/documents/{documentId} returns 404 when document not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "deleting a non-existent document"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/00000000-0000-0000-0000-000000000099",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tenderDocument.notFound"
            response.body.message != null
    }

    def "DELETE /v1/tenders/{tenderId}/documents/{documentId} returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "deleting a document for a non-existent tender"
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents/00000000-0000-0000-0000-000000000001",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== GET /v1/tenders/{tenderId}/documents/{documentId}/presigned-urls ====================

    def "GET /v1/tenders/{tenderId}/documents/{documentId}/presigned-urls returns 200 with presigned URL for ready document"() {
        given: "a document with status ready"
            def documentId = insertDocument(tenderId, companyIdStr, "ready-file.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "requesting download presigned URL"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 200 with url and expiration"
            response.statusCode == HttpStatus.OK
            response.body.url != null
            response.body.expiration != null
    }

    def "GET /v1/tenders/{tenderId}/documents/{documentId}/presigned-urls returns 409 when document status is not ready"() {
        given: "a document with status uploading"
            def documentId = insertDocument(tenderId, companyIdStr, "in-progress.pdf", "uploading")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "requesting download presigned URL for non-ready document"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 409 conflict"
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.tenderDocument.notReady"
            response.body.message != null
    }

    def "GET /v1/tenders/{tenderId}/documents/{documentId}/presigned-urls returns 404 when document not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyIdStr)

        when: "requesting download presigned URL for non-existent document"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/00000000-0000-0000-0000-000000000099/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "returns 404"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tenderDocument.notFound"
            response.body.message != null
    }

    // ==================== Helper Methods ====================

    private String insertTender(String companyIdStr, String name, String status) {
        def id = UUID.randomUUID().toString()
        dsl.insertInto(Tables.TENDERS)
            .set(Tables.TENDERS.ID, id)
            .set(Tables.TENDERS.COMPANY_ID, companyIdStr)
            .set(Tables.TENDERS.NAME, name)
            .set(Tables.TENDERS.STATUS, status)
            .set(Tables.TENDERS.CREATED_BY, "test-user")
            .execute()
        return id
    }

    private String insertDocument(String tenderId, String companyId, String fileName, String status) {
        def id = UUID.randomUUID().toString()
        def record = new TenderDocumentsRecord()
        record.setId(id)
        record.setTenderId(tenderId)
        record.setCompanyId(companyId)
        record.setFileName(fileName)
        record.setContentType("application/pdf")
        record.setFileSize(1024L)
        record.setS3Key("tenders/${companyId}/${tenderId}/${id}/${fileName}")
        record.setStatus(status)
        dsl.insertInto(Tables.TENDER_DOCUMENTS).set(record).execute()
        return id
    }

    private HttpHeaders buildAuthHeaders() {
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        return headers
    }
}
