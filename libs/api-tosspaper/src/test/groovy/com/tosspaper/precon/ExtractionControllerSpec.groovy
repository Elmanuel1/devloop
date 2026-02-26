package com.tosspaper.precon

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

/**
 * Integration tests for ExtractionController.
 *
 * Covers TC-C-CR01..CR12, TC-C-LE01..LE05, TC-C-GE01..GE06,
 * TC-C-XE01..XE07, TC-C-LF01..LF07, TC-C-BU01..BU09.
 *
 * TC-C-XE08, TC-C-LF08, TC-C-BU09 (entity/tender not-found re-validation
 * during cancel/list/bulk-update) are omitted — the service layer does not
 * currently call the adapter for those operations.
 */
class ExtractionControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    DSLContext dsl

    Long companyId
    String tenderId

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev-clientdocs.useassetiq.com")
            .onDuplicateKeyIgnore()
            .execute()

        tenderId = insertTender(companyId.toString(), "Bridge RFP ${UUID.randomUUID()}", "pending")
    }

    def cleanup() {
        dsl.execute("DELETE FROM extraction_fields WHERE extraction_id IN (SELECT id FROM extractions WHERE company_id = ?)", companyId.toString())
        dsl.execute("DELETE FROM extractions WHERE company_id = ?", companyId.toString())
        // Also clean up extractions inserted for company "999"
        dsl.execute("DELETE FROM extraction_fields WHERE extraction_id IN (SELECT id FROM extractions WHERE company_id = '999')")
        dsl.execute("DELETE FROM extractions WHERE company_id = '999'")
        dsl.execute("DELETE FROM tender_documents WHERE company_id = ?", companyId.toString())
        dsl.execute("DELETE FROM tender_documents WHERE company_id = '999'")
        dsl.execute("DELETE FROM tenders WHERE company_id = ?", companyId.toString())
        dsl.execute("DELETE FROM tenders WHERE company_id = '999'")
    }

    // ==================== POST /v1/extractions ====================

    def "TC-C-CR01: POST /v1/extractions returns 201 with Location and ETag v0"() {
        given: "a tender with one ready document"
            def docId = insertDocument(tenderId, companyId.toString(), "spec.pdf", "ready")

        and: "auth headers with content type"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // Explicitly null optional fields so @Size(min=1) is not triggered on empty defaults
            def body = [entity_id: tenderId, document_ids: null, fields: null]

        when: "creating extraction"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "201 with Location header and ETag v0"
            response.statusCode == HttpStatus.CREATED
            response.headers.getFirst("Location") =~ /\/v1\/extractions\/.+/
            response.headers.getFirst("ETag") == '"v0"'
            response.body.id != null
    }

    def "TC-C-CR02: POST /v1/extractions returns 201 with explicit documentIds"() {
        given: "a tender with two ready documents"
            def docId1 = insertDocument(tenderId, companyId.toString(), "vol1.pdf", "ready")
            def docId2 = insertDocument(tenderId, companyId.toString(), "vol2.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // fields=null so @Size(min=1) is not triggered on the empty default
            def body = [entity_id: tenderId, document_ids: [docId1, docId2], fields: null]

        when: "creating extraction with explicit document IDs"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "201 returned"
            response.statusCode == HttpStatus.CREATED
            response.body.id != null
    }

    def "TC-C-CR03: POST /v1/extractions returns 201 with explicit field names"() {
        given: "a tender with one ready document"
            insertDocument(tenderId, companyId.toString(), "spec.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // document_ids=null so @Size(min=1) is not triggered on the empty default
            def body = [entity_id: tenderId, document_ids: null, fields: ["closing_date", "currency"]]

        when: "creating extraction with explicit field names"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "201 returned"
            response.statusCode == HttpStatus.CREATED
            response.body.id != null
    }

    def "TC-C-CR04: POST /v1/extractions returns 404 when tender not found"() {
        given: "auth headers with a non-existent tender ID"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def nonExistentId = UUID.randomUUID().toString()
            def body = [entity_id: nonExistentId, document_ids: null, fields: null]

        when: "creating extraction for non-existent tender"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    def "TC-C-CR05: POST /v1/extractions returns 404 when tender belongs to different company"() {
        given: "a tender from another company with a ready document"
            def otherTenderId = insertTender("999", "Other Co Tender ${UUID.randomUUID()}", "pending")
            insertDocument(otherTenderId, "999", "doc.pdf", "ready")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [entity_id: otherTenderId, document_ids: null, fields: null]

        when: "creating extraction for tender owned by different company"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
    }

    def "TC-C-CR06: POST /v1/extractions returns 400 when no ready documents"() {
        given: "a tender with no documents"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [entity_id: tenderId, document_ids: null, fields: null]

        when: "creating extraction when no ready documents exist"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 with noReadyDocuments error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.noReadyDocuments"
            response.body.message != null
    }

    def "TC-C-CR07: POST /v1/extractions returns 400 when explicit document is not ready"() {
        given: "a document in processing status"
            def docId = insertDocument(tenderId, companyId.toString(), "uploading.pdf", "processing")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // fields=null so @Size(min=1) is not triggered on the empty default
            def body = [entity_id: tenderId, document_ids: [docId], fields: null]

        when: "creating extraction with non-ready document"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 with documentNotReady error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.documentNotReady"
            response.body.message != null
    }

    def "TC-C-CR08: POST /v1/extractions returns 400 when document belongs to different tender"() {
        given: "another tender with a ready document"
            def otherTenderId = insertTender(companyId.toString(), "Other Tender ${UUID.randomUUID()}", "pending")
            def otherDocId = insertDocument(otherTenderId, companyId.toString(), "other.pdf", "ready")

        and: "auth headers pointing to our tender but using other tender's doc"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // fields=null so @Size(min=1) is not triggered on the empty default
            def body = [entity_id: tenderId, document_ids: [otherDocId], fields: null]

        when: "creating extraction with document from different tender"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 with documentNotOwned error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.documentNotOwned"
            response.body.message != null
    }

    def "TC-C-CR09: POST /v1/extractions returns 400 when invalid field name"() {
        given: "a tender with a ready document"
            insertDocument(tenderId, companyId.toString(), "spec.pdf", "ready")

        and: "auth headers with an invalid field name"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            // document_ids=null so @Size(min=1) is not triggered on the empty default
            def body = [entity_id: tenderId, document_ids: null, fields: ["not_a_real_field"]]

        when: "creating extraction with invalid field name"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 with invalidField error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.invalidField"
            response.body.message != null
    }

    def "TC-C-CR10: POST /v1/extractions returns 400 when X-Context-Id is missing"() {
        given: "auth headers without X-Context-Id"
            def headers = buildAuthHeaders()
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [entity_id: tenderId, document_ids: null, fields: null]

        when: "creating extraction without company context header"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 returned"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "TC-C-CR11: POST /v1/extractions returns 403 when X-Context-Id is non-numeric"() {
        given: "auth headers with invalid non-numeric X-Context-Id"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", "not-a-number")
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [entity_id: tenderId, document_ids: null, fields: null]

        when: "creating extraction — PreAuthorize rejects because permission evaluator cannot parse non-numeric ID"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "403 returned"
            response.statusCode == HttpStatus.FORBIDDEN
    }

    def "TC-C-CR12: POST /v1/extractions returns 400 when tender is in terminal status (cancelled)"() {
        given: "a tender in cancelled status with a ready document"
            def cancelledTenderId = insertTender(companyId.toString(), "Cancelled Tender ${UUID.randomUUID()}", "cancelled")
            insertDocument(cancelledTenderId, companyId.toString(), "doc.pdf", "ready")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [entity_id: cancelledTenderId, document_ids: null, fields: null]

        when: "creating extraction for a cancelled tender"
            def response = restTemplate.exchange(
                "/v1/extractions",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 with tenderNotActive error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.tenderNotActive"
            response.body.message != null
    }

    // ==================== GET /v1/extractions ====================

    def "TC-C-LE01: GET /v1/extractions returns 200 with paginated list and non-null cursor"() {
        given: "three extractions for the tender"
            def entityId = UUID.randomUUID().toString()
            insertTender(companyId.toString(), "Paged Tender ${entityId}", "pending", entityId)
            insertExtraction(companyId.toString(), entityId, "pending")
            insertExtraction(companyId.toString(), entityId, "pending")
            insertExtraction(companyId.toString(), entityId, "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing extractions with page size 2"
            def response = restTemplate.exchange(
                "/v1/extractions?entity_id=${entityId}&limit=2",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with 2 items and a non-null cursor for next page"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination != null
            response.body.pagination.cursor != null
    }

    def "TC-C-LE02: GET /v1/extractions returns 200 with empty list when no extractions"() {
        given: "a tender with no extractions"
            def emptyEntityId = UUID.randomUUID().toString()
            insertTender(companyId.toString(), "Empty Tender ${emptyEntityId}", "pending", emptyEntityId)

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing extractions for entity with no extractions"
            def response = restTemplate.exchange(
                "/v1/extractions?entity_id=${emptyEntityId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with empty data"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
            response.body.pagination != null
    }

    def "TC-C-LE03: GET /v1/extractions returns 200 with status filter applied"() {
        given: "two extractions with different statuses for the same entity"
            def entityId = UUID.randomUUID().toString()
            insertTender(companyId.toString(), "Status Filter Tender ${entityId}", "pending", entityId)
            insertExtraction(companyId.toString(), entityId, "pending")
            insertExtraction(companyId.toString(), entityId, "completed")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "filtering by status=pending"
            def response = restTemplate.exchange(
                "/v1/extractions?entity_id=${entityId}&status=pending",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with only pending extractions"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].status == "pending"
    }

    def "TC-C-LE04: GET /v1/extractions returns 200 with empty data for cross-tenant entity"() {
        given: "an extraction for a different company's entity"
            def otherEntityId = UUID.randomUUID().toString()
            insertTender("999", "Other Company Tender ${UUID.randomUUID()}", "pending", otherEntityId)
            insertExtractionForCompany("999", otherEntityId, "pending")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing extractions for other company's entity using our company ID"
            def response = restTemplate.exchange(
                "/v1/extractions?entity_id=${otherEntityId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with empty data — repository filters by company ID so cross-tenant returns empty"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
    }

    def "TC-C-LE05: GET /v1/extractions returns 200 with null cursor when all extractions fit on one page"() {
        given: "exactly 2 extractions for the entity"
            def entityId = UUID.randomUUID().toString()
            insertTender(companyId.toString(), "Single Page Tender ${entityId}", "pending", entityId)
            insertExtraction(companyId.toString(), entityId, "pending")
            insertExtraction(companyId.toString(), entityId, "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing all extractions with limit larger than count"
            def response = restTemplate.exchange(
                "/v1/extractions?entity_id=${entityId}&limit=10",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with data and null cursor (no next page)"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination.cursor == null
    }

    // ==================== GET /v1/extractions/{id} ====================

    def "TC-C-GE01: GET /v1/extractions/{id} returns 200 with full body and ETag v0"() {
        given: "an extraction in the database"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting the extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with full body and ETag header"
            response.statusCode == HttpStatus.OK
            response.headers.getFirst("ETag") == '"v0"'
            with(response.body) {
                id == extractionId
                status == "pending"
                entity_type == "tender"
                errors != null
            }
    }

    def "TC-C-GE02: GET /v1/extractions/{id} returns 304 when If-None-Match matches current ETag"() {
        given: "an extraction at version 0"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")

        and: "auth headers with matching If-None-Match"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-None-Match", '"v0"')

        when: "getting extraction with matching ETag"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "304 returned with ETag header and no body"
            response.statusCode == HttpStatus.NOT_MODIFIED
            response.headers.getFirst("ETag") != null
            response.body == null
    }

    def "TC-C-GE03: GET /v1/extractions/{id} returns 200 when If-None-Match is stale"() {
        given: "an extraction with version bumped to 1"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")
            dsl.execute("UPDATE extractions SET version = 1 WHERE id = ?", extractionId)

        and: "auth headers with old ETag"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-None-Match", '"v0"')

        when: "getting extraction with stale ETag"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with updated ETag"
            response.statusCode == HttpStatus.OK
            response.headers.getFirst("ETag") == '"v1"'
            response.body.id == extractionId
    }

    def "TC-C-GE04: GET /v1/extractions/{id} returns 404 when not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting non-existent extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/00000000-0000-0000-0000-000000000099",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-GE05: GET /v1/extractions/{id} returns 404 when extraction belongs to different company"() {
        given: "an extraction from another company"
            def otherTenderId = insertTender("999", "Other Co GE05 ${UUID.randomUUID()}", "pending")
            def otherExtractionId = insertExtractionForCompany("999", otherTenderId, "pending")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting extraction owned by different company"
            def response = restTemplate.exchange(
                "/v1/extractions/${otherExtractionId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-GE06: GET /v1/extractions/{id} returns 404 when soft-deleted"() {
        given: "a soft-deleted extraction"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")
            dsl.execute("UPDATE extractions SET deleted_at = NOW() WHERE id = ?", extractionId)

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting the soft-deleted extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    // ==================== DELETE /v1/extractions/{id} — returns 202 ====================

    def "TC-C-XE01: DELETE /v1/extractions/{id} returns 202 when cancelling a pending extraction"() {
        given: "a pending extraction with a field"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")
            insertExtractionField(extractionId, "closing_date")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "cancelling the pending extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "202 returned"
            response.statusCode == HttpStatus.ACCEPTED

        and: "status is updated to cancelled in database"
            def record = dsl.selectFrom(Tables.EXTRACTIONS)
                .where(Tables.EXTRACTIONS.ID.eq(extractionId))
                .fetchSingle()
            record.getStatus() == "cancelled"

        and: "extraction fields are deleted"
            def fieldCount = dsl.fetchCount(
                dsl.selectFrom(Tables.EXTRACTION_FIELDS)
                    .where(Tables.EXTRACTION_FIELDS.EXTRACTION_ID.eq(extractionId)))
            fieldCount == 0
    }

    def "TC-C-XE02: DELETE /v1/extractions/{id} returns 202 when cancelling a processing extraction"() {
        given: "a processing extraction"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "processing")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "cancelling the processing extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "202 returned"
            response.statusCode == HttpStatus.ACCEPTED
    }

    def "TC-C-XE03: DELETE /v1/extractions/{id} returns 400 when extraction already cancelled"() {
        given: "an already-cancelled extraction"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "cancelled")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "cancelling an already-cancelled extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "400 returned with cannotCancel error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.cannotCancel"
            response.body.message != null
    }

    def "TC-C-XE04: DELETE /v1/extractions/{id} returns 400 when extraction is completed"() {
        given: "a completed extraction"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "attempting to cancel a completed extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "400 returned with cannotCancel error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.cannotCancel"
            response.body.message != null
    }

    def "TC-C-XE05: DELETE /v1/extractions/{id} returns 400 when extraction has failed status"() {
        given: "a failed extraction"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "failed")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "attempting to cancel a failed extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "400 returned with cannotCancel error"
            response.statusCode == HttpStatus.BAD_REQUEST
            response.body.code == "api.extraction.cannotCancel"
            response.body.message != null
    }

    def "TC-C-XE06: DELETE /v1/extractions/{id} returns 404 when not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "cancelling a non-existent extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/00000000-0000-0000-0000-000000000099",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-XE07: DELETE /v1/extractions/{id} returns 404 when extraction belongs to different company"() {
        given: "an extraction from another company"
            def otherTenderId = insertTender("999", "Other Co XE07 ${UUID.randomUUID()}", "pending")
            def otherExtractionId = insertExtractionForCompany("999", otherTenderId, "pending")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "cancelling extraction from different company"
            def response = restTemplate.exchange(
                "/v1/extractions/${otherExtractionId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    // ==================== GET /v1/extractions/{id}/fields ====================

    def "TC-C-LF01: GET /v1/extractions/{id}/fields returns 200 with extraction fields"() {
        given: "an extraction with two fields"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            insertExtractionField(extractionId, "closing_date")
            insertExtractionField(extractionId, "currency")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields for extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with field data"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination != null

        and: "first field has required properties"
            def firstField = response.body.data[0]
            firstField.id != null
            firstField.extraction_id == extractionId
            firstField.field_name != null
            firstField.entity_type == "tender"
    }

    def "TC-C-LF02: GET /v1/extractions/{id}/fields returns 200 with fieldName filter"() {
        given: "an extraction with multiple fields of different names"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            insertExtractionField(extractionId, "closing_date")
            insertExtractionField(extractionId, "currency")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields filtered by field_name=closing_date"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields?field_name=closing_date",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with only matching fields"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].field_name == "closing_date"
    }

    def "TC-C-LF03: GET /v1/extractions/{id}/fields returns 200 with documentId filter"() {
        given: "an extraction with fields citing different documents"
            def docId1 = insertDocument(tenderId, companyId.toString(), "doc1.pdf", "ready")
            def docId2 = insertDocument(tenderId, companyId.toString(), "doc2.pdf", "ready")
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            insertExtractionFieldWithCitation(extractionId, "closing_date", docId1)
            insertExtractionFieldWithCitation(extractionId, "currency", docId2)

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields filtered by document_id"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields?document_id=${docId1}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with only fields for that document"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].field_name == "closing_date"
    }

    def "TC-C-LF04: GET /v1/extractions/{id}/fields returns 200 with cursor when multiple pages"() {
        given: "an extraction with 3 fields"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            insertExtractionField(extractionId, "closing_date")
            insertExtractionField(extractionId, "currency")
            insertExtractionField(extractionId, "location")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields with limit=2"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields?limit=2",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with 2 fields and non-null cursor"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination.cursor != null
    }

    def "TC-C-LF05: GET /v1/extractions/{id}/fields returns 200 with empty data when no fields"() {
        given: "an extraction with no fields"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields for extraction with no fields"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with empty data"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
            response.body.pagination.cursor == null
    }

    def "TC-C-LF06: GET /v1/extractions/{id}/fields returns 404 when extraction not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields for non-existent extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/00000000-0000-0000-0000-000000000099/fields",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-LF07: GET /v1/extractions/{id}/fields returns 404 when extraction belongs to different company"() {
        given: "an extraction from another company"
            def otherTenderId = insertTender("999", "Other Co LF07 ${UUID.randomUUID()}", "pending")
            def otherExtractionId = insertExtractionForCompany("999", otherTenderId, "completed")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing fields for extraction from different company"
            def response = restTemplate.exchange(
                "/v1/extractions/${otherExtractionId}/fields",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    // ==================== PATCH /v1/extractions/{id}/fields ====================

    def "TC-C-BU01: PATCH /v1/extractions/{id}/fields returns 200 when If-Match matches"() {
        given: "an extraction at version 0 with a field"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            def fieldId = insertExtractionField(extractionId, "closing_date")

        and: "auth headers with correct If-Match v0"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: fieldId, edited_value: "2027-01-01"]]]

        when: "updating fields with matching ETag"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "200 returned with updated field data"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            // Response ExtractionField uses 'id' not 'field_id'
            response.body.data[0].id == fieldId
    }

    def "TC-C-BU02: PATCH /v1/extractions/{id}/fields returns 400 when If-Match is missing"() {
        given: "an extraction with a field"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            def fieldId = insertExtractionField(extractionId, "closing_date")

        and: "auth headers WITHOUT If-Match (If-Match is required by the API spec)"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: fieldId, edited_value: "2027-01-01"]]]

        when: "updating fields without required If-Match header"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 returned — Spring rejects missing required header before service code runs"
            response.statusCode == HttpStatus.BAD_REQUEST
            // GlobalExceptionHandler maps MissingRequestHeaderException to api.validation.error
            response.body.code == "api.validation.error"
    }

    def "TC-C-BU03: PATCH /v1/extractions/{id}/fields returns 412 when If-Match is stale"() {
        given: "an extraction with version already bumped to 1"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            def fieldId = insertExtractionField(extractionId, "closing_date")
            dsl.execute("UPDATE extractions SET version = 1 WHERE id = ?", extractionId)

        and: "auth headers with stale If-Match v0"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: fieldId, edited_value: "2027-01-01"]]]

        when: "updating with stale ETag"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "412 returned with staleVersion error"
            response.statusCode == HttpStatus.PRECONDITION_FAILED
            response.body.code == "api.extraction.staleVersion"
            response.body.message != null
    }

    def "TC-C-BU04: PATCH /v1/extractions/{id}/fields returns 404 when field belongs to different extraction"() {
        given: "two extractions each with their own field"
            def extractionId1 = insertExtraction(companyId.toString(), tenderId, "completed")
            def extractionId2 = insertExtraction(companyId.toString(), tenderId, "completed")
            insertExtractionField(extractionId1, "closing_date")
            def fieldId2 = insertExtractionField(extractionId2, "currency")

        and: "auth headers — patching extraction1 with a field from extraction2"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: fieldId2, edited_value: "some value"]]]

        when: "patching extraction1 with a field belonging to extraction2"
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId1}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned — validateFieldsOwnedByExtraction rejects mismatched field"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.message != null
    }

    def "TC-C-BU05: PATCH /v1/extractions/{id}/fields returns 404 when extraction not found"() {
        given: "auth headers for non-existent extraction"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: UUID.randomUUID().toString(), edited_value: "value"]]]

        when: "patching non-existent extraction"
            def response = restTemplate.exchange(
                "/v1/extractions/00000000-0000-0000-0000-000000000099/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-BU06: PATCH /v1/extractions/{id}/fields returns 404 when extraction belongs to different company"() {
        given: "an extraction from another company with a field"
            def otherTenderId = insertTender("999", "Other Co BU06 ${UUID.randomUUID()}", "pending")
            def otherExtractionId = insertExtractionForCompany("999", otherTenderId, "completed")
            def fieldId = insertExtractionField(otherExtractionId, "closing_date")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [updates: [[field_id: fieldId, edited_value: "value"]]]

        when: "patching extraction from different company"
            def response = restTemplate.exchange(
                "/v1/extractions/${otherExtractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.extraction.notFound"
            response.body.message != null
    }

    def "TC-C-BU07: PATCH /v1/extractions/{id}/fields increments version — subsequent GET returns ETag v1"() {
        given: "an extraction at version 0 with a field"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            def fieldId = insertExtractionField(extractionId, "closing_date")

        and: "PATCH with If-Match v0 succeeds"
            def patchHeaders = buildAuthHeaders()
            patchHeaders.add("X-Context-Id", companyId.toString())
            patchHeaders.add("If-Match", '"v0"')
            patchHeaders.setContentType(MediaType.APPLICATION_JSON)
            def patchBody = [updates: [[field_id: fieldId, edited_value: "2027-06-30"]]]
            restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(patchBody, patchHeaders),
                Map)

        when: "getting the extraction after the update"
            def getHeaders = buildAuthHeaders()
            getHeaders.add("X-Context-Id", companyId.toString())
            def response = restTemplate.exchange(
                "/v1/extractions/${extractionId}",
                HttpMethod.GET,
                new HttpEntity<>(getHeaders),
                Map)

        then: "ETag is now v1 after the successful update"
            response.statusCode == HttpStatus.OK
            response.headers.getFirst("ETag") == '"v1"'
    }

    def "TC-C-BU08: PATCH concurrent update — second update with stale ETag fails with 412"() {
        given: "an extraction at version 0 with two fields"
            def extractionId = insertExtraction(companyId.toString(), tenderId, "completed")
            def fieldId1 = insertExtractionField(extractionId, "closing_date")
            def fieldId2 = insertExtractionField(extractionId, "currency")

        and: "first PATCH succeeds, bumping version to 1"
            def firstHeaders = buildAuthHeaders()
            firstHeaders.add("X-Context-Id", companyId.toString())
            firstHeaders.add("If-Match", '"v0"')
            firstHeaders.setContentType(MediaType.APPLICATION_JSON)
            def firstBody = [updates: [[field_id: fieldId1, edited_value: "2027-01-01"]]]
            def firstResponse = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(firstBody, firstHeaders),
                Map)

        when: "second PATCH uses same stale If-Match v0"
            def secondHeaders = buildAuthHeaders()
            secondHeaders.add("X-Context-Id", companyId.toString())
            secondHeaders.add("If-Match", '"v0"')
            secondHeaders.setContentType(MediaType.APPLICATION_JSON)
            def secondBody = [updates: [[field_id: fieldId2, edited_value: "CAD"]]]
            def secondResponse = restTemplate.exchange(
                "/v1/extractions/${extractionId}/fields",
                HttpMethod.PATCH,
                new HttpEntity<>(secondBody, secondHeaders),
                Map)

        then: "first request succeeded"
            firstResponse.statusCode == HttpStatus.OK

        and: "second request fails with 412 Precondition Failed"
            secondResponse.statusCode == HttpStatus.PRECONDITION_FAILED
            secondResponse.body.code == "api.extraction.staleVersion"
    }

    // ==================== Helper Methods ====================

    /**
     * Inserts a tender directly via raw SQL. Uses String.format to avoid Groovy GString
     * binding issues with jOOQ.
     */
    private String insertTender(String companyIdStr, String name, String status, String id = null) {
        def tendId = id ?: UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO tenders (id, company_id, name, status, created_by) VALUES (?, ?, ?, ?, ?)",
            tendId, companyIdStr, name, status, "test-user")
        return tendId
    }

    /**
     * Inserts a tender document directly via raw SQL. Converts Groovy GString s3_key
     * to a plain Java String to avoid jOOQ GStringImpl type errors.
     */
    private String insertDocument(String tId, String companyIdStr, String fileName, String status) {
        def id = UUID.randomUUID().toString()
        def s3Key = ("tender-uploads/" + companyIdStr + "/" + tId + "/" + id + "/" + fileName)
        dsl.execute(
            "INSERT INTO tender_documents (id, tender_id, company_id, file_name, content_type, file_size, s3_key, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, tId, companyIdStr, fileName, "application/pdf", 1024L, s3Key, status)
        return id
    }

    /**
     * Inserts an extraction directly for the test company.
     */
    private String insertExtraction(String companyIdStr, String entityId, String status) {
        def id = UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO extractions (id, company_id, entity_type, entity_id, status, document_ids, version, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, 0, ?)",
            id, companyIdStr, "tender", entityId, status, '["doc-1"]', companyIdStr)
        return id
    }

    /**
     * Inserts an extraction for a specific company (may differ from test company).
     */
    private String insertExtractionForCompany(String companyIdStr, String entityId, String status) {
        def id = UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO extractions (id, company_id, entity_type, entity_id, status, document_ids, version, created_by) VALUES (?, ?, ?, ?, ?, ?::jsonb, 0, ?)",
            id, companyIdStr, "tender", entityId, status, '["doc-1"]', companyIdStr)
        return id
    }

    /**
     * Inserts an extraction field for the given extraction.
     */
    private String insertExtractionField(String extractionId, String fieldName = "closing_date") {
        def id = UUID.randomUUID().toString()
        dsl.execute(
            "INSERT INTO extraction_fields (id, extraction_id, field_name, field_type) VALUES (?, ?, ?, ?)",
            id, extractionId, fieldName, "date")
        return id
    }

    /**
     * Inserts an extraction field with a citation pointing to a specific document.
     * The citations column is a JSONB array used by the documentId filter.
     */
    private String insertExtractionFieldWithCitation(String extractionId, String fieldName, String documentId) {
        def id = UUID.randomUUID().toString()
        def citations = '[{"document_id": "' + documentId + '", "page": 1}]'
        dsl.execute(
            "INSERT INTO extraction_fields (id, extraction_id, field_name, field_type, citations) VALUES (?, ?, ?, ?, ?::jsonb)",
            id, extractionId, fieldName, "date", citations)
        return id
    }

    private def buildAuthHeaders() {
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        return headers
    }
}
