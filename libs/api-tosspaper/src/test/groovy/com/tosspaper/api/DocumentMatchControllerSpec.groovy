package com.tosspaper.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.service.DocumentMatchService
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.generated.model.LinkPoRequest
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class DocumentMatchControllerSpec extends BaseIntegrationTest {

    @SpringBean
    DocumentMatchService documentMatchService = Mock()

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    // ==================== linkPurchaseOrder ====================

    def "linkPurchaseOrder returns ACCEPTED when successful"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "request body"
            def linkPoRequest = new LinkPoRequest()
            linkPoRequest.poNumber = "PO-001"
            def entity = new HttpEntity<>(linkPoRequest, headers)

        when: "calling linkPurchaseOrder"
            def response = restTemplate.exchange(
                "/v1/document-matches/task-456/link-po",
                HttpMethod.POST, entity, String)

        then: "response status is ACCEPTED"
            response.statusCode == HttpStatus.ACCEPTED

        and: "service is called with correct parameters"
            1 * documentMatchService.initiateManualLink(1L, "task-456", "PO-001")
    }

    def "linkPurchaseOrder returns 500 when service fails"() {
        given: "service will fail"
            documentMatchService.initiateManualLink(1L, "task-456", "PO-001") >> {
                throw new IllegalStateException("Failed to link")
            }

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "1")

        and: "request body"
            def linkPoRequest = new LinkPoRequest()
            linkPoRequest.poNumber = "PO-001"
            def entity = new HttpEntity<>(linkPoRequest, headers)

        when: "calling linkPurchaseOrder"
            def response = restTemplate.exchange(
                "/v1/document-matches/task-456/link-po",
                HttpMethod.POST, entity, String)

        then: "response status is 500 Internal Server Error"
            response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
    }

    // ==================== rematch ====================

    def "rematch returns ACCEPTED when successful"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            def entity = new HttpEntity<>(headers)

        when: "calling rematch"
            def response = restTemplate.exchange(
                "/v1/document-matches/task-789/rematch",
                HttpMethod.POST, entity, String)

        then: "response status is ACCEPTED"
            response.statusCode == HttpStatus.ACCEPTED

        and: "service is called"
            1 * documentMatchService.initiateAutoMatch("task-789")
    }

    def "rematch returns 500 when service fails"() {
        given: "service will fail"
            documentMatchService.initiateAutoMatch("task-789") >> {
                throw new IllegalStateException("Failed to rematch")
            }

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            def entity = new HttpEntity<>(headers)

        when: "calling rematch"
            def response = restTemplate.exchange(
                "/v1/document-matches/task-789/rematch",
                HttpMethod.POST, entity, String)

        then: "response status is 500 Internal Server Error"
            response.statusCode == HttpStatus.INTERNAL_SERVER_ERROR
    }
}
