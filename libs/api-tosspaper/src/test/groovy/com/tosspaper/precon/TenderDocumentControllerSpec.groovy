package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

import java.time.OffsetDateTime

class TenderDocumentControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    Long companyId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev-clientdocs.useassetiq.com")
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.TENDER_DOCUMENTS)
            .where(Tables.TENDER_DOCUMENTS.COMPANY_ID.eq(companyId.toString()))
            .execute()
        dsl.deleteFrom(Tables.TENDERS)
            .where(Tables.TENDERS.COMPANY_ID.eq(companyId.toString()))
            .execute()
    }

    // ==================== POST /v1/tenders/{tenderId}/documents/presigned-urls ====================

    def "POST presigned-urls returns 201 with Location header"() {
        given: "a tender in DB"
            def tenderId = insertTender(companyId.toString(), "Upload Test Tender", "draft")

        and: "auth headers and valid body"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "doc.pdf", content_type: "application/pdf", file_size: 1024]

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then:
            response.statusCode == HttpStatus.CREATED
            response.headers.getFirst("Location") != null
            response.headers.getFirst("Location").contains("/v1/tenders/${tenderId}/documents/")
            response.body.document_id != null
            response.body.presigned_url != null
            response.body.expiration != null
    }

    def "POST presigned-urls returns 400 for invalid content_type"() {
        given: "a tender in DB"
            def tenderId = insertTender(companyId.toString(), "Invalid CT Tender", "draft")

        and: "auth headers and invalid content type"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "doc.zip", content_type: "application/zip", file_size: 1024]

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then:
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "POST presigned-urls returns 400 when file_size exceeds 200MB"() {
        given: "a tender in DB"
            def tenderId = insertTender(companyId.toString(), "Big File Tender", "draft")

        and: "auth headers and oversized file"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "big.pdf", content_type: "application/pdf", file_size: 209715201]

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then:
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "POST presigned-urls returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [file_name: "doc.pdf", content_type: "application/pdf", file_size: 1024]

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents/presigned-urls",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== GET /v1/tenders/{tenderId}/documents ====================

    def "GET documents returns 200 with paginated list"() {
        given: "a tender with documents"
            def tenderId = insertTender(companyId.toString(), "List Docs Tender", "draft")
            insertDocument(tenderId, companyId.toString(), "doc1.pdf", "ready")
            insertDocument(tenderId, companyId.toString(), "doc2.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
    }

    def "GET documents returns 200 with status filter"() {
        given: "a tender with mixed status documents"
            def tenderId = insertTender(companyId.toString(), "Filter Docs Tender", "draft")
            insertDocument(tenderId, companyId.toString(), "ready.pdf", "ready")
            insertDocument(tenderId, companyId.toString(), "uploading.pdf", "uploading")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents?status=ready",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
    }

    def "GET documents returns 200 empty when no docs"() {
        given: "a tender with no documents"
            def tenderId = insertTender(companyId.toString(), "Empty Docs Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
    }

    def "GET documents returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== DELETE /v1/tenders/{tenderId}/documents/{documentId} ====================

    def "DELETE document returns 204"() {
        given: "a tender with a document"
            def tenderId = insertTender(companyId.toString(), "Delete Doc Tender", "draft")
            def documentId = insertDocument(tenderId, companyId.toString(), "delete-me.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NO_CONTENT
    }

    def "DELETE document returns 404 when not found"() {
        given: "a tender with no documents"
            def tenderId = insertTender(companyId.toString(), "No Doc Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/00000000-0000-0000-0000-000000000099",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.document.notFound"
            response.body.message != null
    }

    def "DELETE document returns 404 when already soft-deleted"() {
        given: "a soft-deleted document"
            def tenderId = insertTender(companyId.toString(), "Soft Del Tender", "draft")
            def documentId = insertDocument(tenderId, companyId.toString(), "soft-del.pdf", "ready")
            dsl.update(Tables.TENDER_DOCUMENTS)
                .set(Tables.TENDER_DOCUMENTS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.TENDER_DOCUMENTS.ID.eq(documentId))
                .execute()

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.document.notFound"
            response.body.message != null
    }

    def "DELETE document returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents/00000000-0000-0000-0000-000000000099",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== GET /v1/tenders/{tenderId}/documents/{documentId}/presigned-urls ====================

    def "GET download presigned-url returns 200 for ready document"() {
        given: "a tender with a ready document"
            def tenderId = insertTender(companyId.toString(), "Download Tender", "draft")
            def documentId = insertDocument(tenderId, companyId.toString(), "download.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.OK
            response.body.url != null
            response.body.expiration != null
    }

    def "GET download presigned-url returns 409 when document not ready"() {
        given: "a tender with an uploading document"
            def tenderId = insertTender(companyId.toString(), "Not Ready Tender", "draft")
            def documentId = insertDocument(tenderId, companyId.toString(), "uploading.pdf", "uploading")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/${documentId}/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.document.notReady"
            response.body.message != null
    }

    def "GET download presigned-url returns 404 when document not found"() {
        given: "a tender with no documents"
            def tenderId = insertTender(companyId.toString(), "No Doc DL Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}/documents/00000000-0000-0000-0000-000000000099/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.document.notFound"
            response.body.message != null
    }

    def "GET download presigned-url returns 404 when tender not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when:
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099/documents/00000000-0000-0000-0000-000000000099/presigned-urls",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then:
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
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

    private String insertDocument(String tenderId, String companyIdStr, String fileName, String status) {
        def id = UUID.randomUUID().toString()
        def s3Key = "tender-uploads/${companyIdStr}/${tenderId}/${id}/${fileName}"
        dsl.insertInto(Tables.TENDER_DOCUMENTS)
            .set(Tables.TENDER_DOCUMENTS.ID, id)
            .set(Tables.TENDER_DOCUMENTS.TENDER_ID, tenderId)
            .set(Tables.TENDER_DOCUMENTS.COMPANY_ID, companyIdStr)
            .set(Tables.TENDER_DOCUMENTS.FILE_NAME, fileName)
            .set(Tables.TENDER_DOCUMENTS.CONTENT_TYPE, "application/pdf")
            .set(Tables.TENDER_DOCUMENTS.FILE_SIZE, 1024L)
            .set(Tables.TENDER_DOCUMENTS.S3_KEY, s3Key)
            .set(Tables.TENDER_DOCUMENTS.STATUS, status)
            .execute()
        return id
    }

    private def buildAuthHeaders() {
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        return headers
    }
}
