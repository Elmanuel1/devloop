package com.tosspaper.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.document_approval.DocumentApprovalApiService
import com.tosspaper.document_approval.DocumentApprovalDetailService
import com.tosspaper.models.domain.ExtractionResult
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class DocumentApprovalsControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @SpringBean
    DocumentApprovalApiService documentApprovalService = Mock()

    @SpringBean
    DocumentApprovalDetailService documentApprovalDetailService = Mock()

    // DocumentApprovalServiceImpl implements both DocumentApprovalApiService and
    // com.tosspaper.models.service.DocumentApprovalService. Mocking the API interface
    // replaces the impl bean, so we also need a mock for the models interface to
    // satisfy other beans that depend on it (e.g. DocumentApprovalEmailProcessingServiceImpl).
    @SpringBean
    com.tosspaper.models.service.DocumentApprovalService modelsDocumentApprovalService = Mock()

    // ==================== listDocumentApprovals ====================

    def "listDocumentApprovals returns OK with approval list"() {
        given: "valid context and query parameters"
            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse(
                [createDomainApproval("approval-1"), createDomainApproval("approval-2")],
                "nextCursor123"
            )
            documentApprovalService.listDocumentApprovalsFromApi(
                123L, 20, "abc123", "pending", "invoice",
                "sender@example.com", null, null, "proj-1"
            ) >> serviceResponse

        and: "auth headers with X-Context-Id"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listDocumentApprovals"
            def uri = "/v1/document-approvals?pageSize=20&cursor=abc123&status=pending&documentType=invoice&fromEmail=sender@example.com&projectId=proj-1"
            def response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains approvals"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
            body.pagination.cursor == "nextCursor123"
    }

    def "listDocumentApprovals handles default and null optional parameters"() {
        given: "only required context parameter - pageSize defaults to 20 via API spec"
            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse([], null)
            documentApprovalService.listDocumentApprovalsFromApi(
                456L, 20, null, null, null, null, null, null, null
            ) >> serviceResponse

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "456")

        when: "calling listDocumentApprovals with no optional parameters"
            def response = restTemplate.exchange("/v1/document-approvals", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== reviewExtraction ====================

    def "reviewExtraction returns NO_CONTENT when approved without documentData"() {
        given: "valid context, approvalId, and request without documentData"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON)

            def requestBody = [approved: true, notes: "Looks good", documentData: null]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling reviewExtraction"
            def response = restTemplate.exchange("/v1/document-approvals/approval-789/extractions", HttpMethod.POST, entity, String)

        then: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "service is called with correct parameters"
            1 * documentApprovalService.reviewExtraction(
                123L, "approval-789", true,
                TestSecurityConfiguration.TEST_USER_EMAIL, "Looks good", null
            )
    }

    def "reviewExtraction returns NO_CONTENT when rejected"() {
        given: "valid context, approvalId, and rejection request"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON)

            def requestBody = [approved: false, notes: "Invalid data", documentData: null]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling reviewExtraction"
            def response = restTemplate.exchange("/v1/document-approvals/approval-789/extractions", HttpMethod.POST, entity, String)

        then: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "service is called with correct parameters"
            1 * documentApprovalService.reviewExtraction(
                123L, "approval-789", false,
                TestSecurityConfiguration.TEST_USER_EMAIL, "Invalid data", null
            )
    }

    def "reviewExtraction converts documentData when provided"() {
        given: "valid context, approvalId, and request with documentData"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON)

            def requestBody = [
                approved: true,
                notes: "With edits",
                documentData: [documentType: "invoice", documentNumber: "INV-001"]
            ]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling reviewExtraction"
            def response = restTemplate.exchange("/v1/document-approvals/approval-789/extractions", HttpMethod.POST, entity, String)

        then: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "service is called with converted extraction object"
            1 * documentApprovalService.reviewExtraction(
                123L, "approval-789", true,
                TestSecurityConfiguration.TEST_USER_EMAIL, "With edits",
                _ as com.tosspaper.models.extraction.dto.Extraction
            )
    }

    // ==================== getDocumentApprovalDetail ====================

    def "getDocumentApprovalDetail returns OK with detail"() {
        given: "valid context and approvalId"
            def domainDetail = createDomainApprovalDetail("approval-123")
            documentApprovalDetailService.getApprovalDetail(123L, "approval-123") >> domainDetail

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling getDocumentApprovalDetail"
            def response = restTemplate.exchange("/v1/document-approvals/approval-123", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains detail"
            def body = objectMapper.readValue(response.body, Map)
            body.approvalId == "approval-123"
    }

    // ==================== getDocumentApprovalExtraction ====================

    def "getDocumentApprovalExtraction returns OK with extraction result"() {
        given: "valid context and approvalId"
            def extractionResult = createExtractionResult("task-456")
            documentApprovalDetailService.getExtractionResult(123L, "approval-123") >> extractionResult

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling getDocumentApprovalExtraction"
            def response = restTemplate.exchange("/v1/document-approvals/approval-123/extraction", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains extraction"
            def body = objectMapper.readValue(response.body, Map)
            body.assignedId == "task-456"
    }

    // ==================== Helper Methods ====================

    private static com.tosspaper.models.domain.DocumentApproval createDomainApproval(String id) {
        return com.tosspaper.models.domain.DocumentApproval.builder()
            .id(id)
            .assignedId("task-" + id)
            .companyId(123L)
            .build()
    }

    private static com.tosspaper.models.domain.DocumentApprovalDetail createDomainApprovalDetail(String approvalId) {
        return com.tosspaper.models.domain.DocumentApprovalDetail.builder()
            .approvalId(approvalId)
            .assignedId("task-" + approvalId)
            .companyId(123L)
            .build()
    }

    private static ExtractionResult createExtractionResult(String assignedId) {
        return ExtractionResult.builder()
            .assignedId(assignedId)
            .matchType("pending")
            .build()
    }
}
