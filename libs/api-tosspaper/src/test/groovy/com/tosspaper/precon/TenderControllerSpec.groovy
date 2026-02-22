package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType

import java.time.OffsetDateTime

class TenderControllerSpec extends BaseIntegrationTest {

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
        dsl.deleteFrom(Tables.TENDERS)
            .where(Tables.TENDERS.COMPANY_ID.eq(companyId.toString()))
            .execute()
    }

    // ==================== POST /v1/tenders ====================

    def "POST /v1/tenders returns 201 with Location and ETag headers"() {
        given: "auth headers and valid body"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Bridge RFP"]

        when: "creating a tender"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "response is 201 with Location and ETag"
            response.statusCode == HttpStatus.CREATED
            response.headers.getFirst("Location") =~ /\/v1\/tenders\/.+/
            response.headers.getFirst("ETag") != null
            with(response.body) {
                id != null
                name == "Bridge RFP"
                status == "draft"
            }
    }

    def "POST /v1/tenders returns 201 with all optional fields persisted"() {
        given: "auth headers and body with all fields"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [
                name: "Full Tender",
                currency: "CAD",
                delivery_method: "lump_sum",
                platform: "https://bidsandtenders.ca",
                closing_date: "2027-12-31T23:59:59Z"
            ]

        when: "creating"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "201 returned with all fields"
            response.statusCode == HttpStatus.CREATED
            with(response.body) {
                name == "Full Tender"
                currency == "CAD"
                delivery_method == "lump_sum"
                status == "draft"
            }
    }

    def "POST /v1/tenders returns 400 when X-Context-Id missing"() {
        given: "auth headers without X-Context-Id"
            def headers = buildAuthHeaders()
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Test"]

        when: "creating"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "400 returned"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "POST /v1/tenders returns 403 when X-Context-Id is non-numeric"() {
        given: "auth headers with invalid X-Context-Id (permission check fails first)"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", "abc")
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Test"]

        when: "creating - PreAuthorize fails because permission evaluator cannot parse abc as company ID"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "403 returned (permission evaluator rejects non-numeric context ID)"
            response.statusCode == HttpStatus.FORBIDDEN
    }

    def "POST /v1/tenders returns 409 when duplicate name exists (case-insensitive)"() {
        given: "an existing tender"
            insertTender(companyId.toString(), "Bridge RFP", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "bridge rfp"]

        when: "creating with same name (different case)"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "409 returned"
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.tender.duplicateName"
            response.body.message != null
    }

    def "POST /v1/tenders returns 201 with null JSONB fields"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Minimal Tender"]

        when: "creating"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map)

        then: "201 returned"
            response.statusCode == HttpStatus.CREATED
    }

    // ==================== GET /v1/tenders ====================

    def "GET /v1/tenders returns 200 with paginated list"() {
        given: "tenders in DB"
            insertTender(companyId.toString(), "Tender A", "draft")
            insertTender(companyId.toString(), "Tender B", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with items"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 2
            response.body.pagination != null
    }

    def "GET /v1/tenders returns 200 with search results"() {
        given: "tenders in DB"
            insertTender(companyId.toString(), "Bridge RFP", "draft")
            insertTender(companyId.toString(), "Road Work", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "searching"
            def response = restTemplate.exchange(
                "/v1/tenders?search=bridge",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with matching results"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].name == "Bridge RFP"
    }

    def "GET /v1/tenders returns 200 with status filter"() {
        given: "tenders with different statuses"
            insertTender(companyId.toString(), "Draft Tender", "draft")
            insertTender(companyId.toString(), "Pending Tender", "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "filtering by status"
            def response = restTemplate.exchange(
                "/v1/tenders?status=draft",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with filtered results"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 1
            response.body.data[0].status == "draft"
    }

    def "GET /v1/tenders returns 200 empty when no tenders"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "listing"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 with empty data"
            response.statusCode == HttpStatus.OK
            response.body.data.size() == 0
    }

    def "GET /v1/tenders returns 400 when X-Context-Id missing"() {
        given: "auth headers without X-Context-Id"
            def headers = buildAuthHeaders()

        when: "listing"
            def response = restTemplate.exchange(
                "/v1/tenders",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "400 returned"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    // ==================== GET /v1/tenders/{id} ====================

    def "GET /v1/tenders/{id} returns 200 with ETag header"() {
        given: "a tender in DB"
            def tenderId = insertTender(companyId.toString(), "My Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting tender"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "200 returned with ETag"
            response.statusCode == HttpStatus.OK
            response.headers.getFirst("ETag") != null
            response.body.name == "My Tender"
    }

    def "GET /v1/tenders/{id} returns 404 when not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting nonexistent tender"
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    def "GET /v1/tenders/{id} returns 404 when belongs to other company"() {
        given: "a tender from another company"
            def tenderId = insertTender("999", "Other Company Tender", "draft")

        and: "auth headers for our company"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting tender"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    def "GET /v1/tenders/{id} returns 404 when soft-deleted"() {
        given: "a soft-deleted tender"
            def tenderId = insertTender(companyId.toString(), "Deleted Tender", "draft")
            dsl.update(Tables.TENDERS)
                .set(Tables.TENDERS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.TENDERS.ID.eq(tenderId))
                .execute()

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "getting tender"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== PATCH /v1/tenders/{id} ====================

    def "PATCH /v1/tenders/{id} returns 200 with updated ETag"() {
        given: "an existing tender"
            def tenderId = insertTender(companyId.toString(), "Original Name", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "New Name"]

        when: "updating"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "200 returned with new ETag"
            response.statusCode == HttpStatus.OK
            response.headers.getFirst("ETag") != null
            response.body.name == "New Name"
    }

    def "PATCH /v1/tenders/{id} returns 412 when ETag stale"() {
        given: "an existing tender"
            def tenderId = insertTender(companyId.toString(), "Original", "draft")
            // Update to bump version
            dsl.execute("UPDATE tenders SET version = 1 WHERE id = ?", tenderId)

        and: "auth headers with old ETag"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Conflict"]

        when: "updating with stale version"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "412 returned"
            response.statusCode == HttpStatus.PRECONDITION_FAILED
            response.body.code == "api.tender.staleVersion"
            response.body.message != null
    }

    def "PATCH /v1/tenders/{id} returns 428 when If-Match missing"() {
        given: "an existing tender"
            def tenderId = insertTender(companyId.toString(), "Test", "draft")

        and: "auth headers without If-Match"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Updated"]

        when: "updating without If-Match"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "428 returned"
            response.statusCode.value() == 428
            response.body.code == "api.validation.ifMatchRequired"
            response.body.message != null
    }

    def "PATCH /v1/tenders/{id} returns 409 when duplicate name"() {
        given: "two tenders"
            insertTender(companyId.toString(), "Bridge", "draft")
            def tenderId = insertTender(companyId.toString(), "Road", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Bridge"]

        when: "updating to duplicate name"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "409 returned"
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.tender.duplicateName"
            response.body.message != null
    }

    def "PATCH /v1/tenders/{id} returns 409 on invalid status transition"() {
        given: "a draft tender"
            def tenderId = insertTender(companyId.toString(), "Draft Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [status: "won"]

        when: "updating status to invalid transition"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "409 returned"
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.tender.invalidStatusTransition"
            response.body.message != null
    }

    def "PATCH /v1/tenders/{id} returns 200 on valid status transition"() {
        given: "a draft tender"
            def tenderId = insertTender(companyId.toString(), "Transition Tender", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [status: "pending"]

        when: "updating status to pending"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "200 returned"
            response.statusCode == HttpStatus.OK
            response.body.status == "pending"
    }

    def "PATCH /v1/tenders/{id} returns 404 when not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())
            headers.add("If-Match", '"v0"')
            headers.setContentType(MediaType.APPLICATION_JSON)
            def body = [name: "Nonexistent"]

        when: "updating nonexistent tender"
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099",
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    // ==================== DELETE /v1/tenders/{id} ====================

    def "DELETE /v1/tenders/{id} returns 204 for draft tender"() {
        given: "a draft tender"
            def tenderId = insertTender(companyId.toString(), "Draft Delete", "draft")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "deleting"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "204 returned"
            response.statusCode == HttpStatus.NO_CONTENT
    }

    def "DELETE /v1/tenders/{id} returns 204 for pending tender"() {
        given: "a pending tender"
            def tenderId = insertTender(companyId.toString(), "Pending Delete", "pending")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "deleting"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "204 returned"
            response.statusCode == HttpStatus.NO_CONTENT
    }

    def "DELETE /v1/tenders/{id} returns 409 for submitted tender"() {
        given: "a submitted tender"
            def tenderId = insertTender(companyId.toString(), "Submitted Tender", "submitted")

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "deleting"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "409 returned"
            response.statusCode == HttpStatus.CONFLICT
            response.body.code == "api.tender.cannotDelete"
            response.body.message != null
    }

    def "DELETE /v1/tenders/{id} returns 404 when not found"() {
        given: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "deleting nonexistent tender"
            def response = restTemplate.exchange(
                "/v1/tenders/00000000-0000-0000-0000-000000000099",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
            response.statusCode == HttpStatus.NOT_FOUND
            response.body.code == "api.tender.notFound"
            response.body.message != null
    }

    def "DELETE /v1/tenders/{id} returns 404 when already soft-deleted"() {
        given: "a soft-deleted tender"
            def tenderId = insertTender(companyId.toString(), "Already Deleted", "draft")
            dsl.update(Tables.TENDERS)
                .set(Tables.TENDERS.DELETED_AT, OffsetDateTime.now())
                .where(Tables.TENDERS.ID.eq(tenderId))
                .execute()

        and: "auth headers"
            def headers = buildAuthHeaders()
            headers.add("X-Context-Id", companyId.toString())

        when: "deleting again"
            def response = restTemplate.exchange(
                "/v1/tenders/${tenderId}",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                Map)

        then: "404 returned"
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

    private def buildAuthHeaders() {
        def headers = new HttpHeaders()
        headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
        return headers
    }
}
