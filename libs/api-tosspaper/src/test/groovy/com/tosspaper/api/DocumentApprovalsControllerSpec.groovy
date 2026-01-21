package com.tosspaper.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.common.security.SecurityUtils
import com.tosspaper.document_approval.DocumentApprovalApiService
import com.tosspaper.document_approval.DocumentApprovalDetailService
import com.tosspaper.generated.model.DocumentApproval
import com.tosspaper.generated.model.DocumentApprovalDetail
import com.tosspaper.generated.model.DocumentApprovalList
import com.tosspaper.generated.model.ExtractionResultResponse
import com.tosspaper.generated.model.MatchType
import com.tosspaper.generated.model.Pagination
import com.tosspaper.generated.model.ReviewExtractionRequest
import com.tosspaper.models.domain.ExtractionResult
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import spock.lang.Specification

import java.time.OffsetDateTime

class DocumentApprovalsControllerSpec extends Specification {

    DocumentApprovalApiService documentApprovalService
    DocumentApprovalDetailService documentApprovalDetailService
    ObjectMapper objectMapper
    DocumentApprovalsController controller
    MockedStatic<SecurityUtils> securityUtilsMock

    def setup() {
        documentApprovalService = Mock()
        documentApprovalDetailService = Mock()
        objectMapper = new ObjectMapper()
        controller = new DocumentApprovalsController(documentApprovalService, documentApprovalDetailService, objectMapper)

        // Mock SecurityUtils static method
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class)
        securityUtilsMock.when({ SecurityUtils.getSubjectFromJwt() }).thenReturn("test-user-id")
    }

    def cleanup() {
        securityUtilsMock?.close()
    }

    // ==================== listDocumentApprovals ====================

    def "listDocumentApprovals returns OK with approval list"() {
        given: "valid context and query parameters"
            def xContextId = "123"
            def pageSize = 20
            def cursor = "abc123"
            def status = "pending"
            def documentType = "invoice"
            def fromEmail = "sender@example.com"
            def createdDateFrom = OffsetDateTime.now().minusDays(7)
            def createdDateTo = OffsetDateTime.now()
            def projectId = "proj-1"

            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse(
                [createDomainApproval("approval-1"), createDomainApproval("approval-2")],
                "nextCursor123"
            )

        when: "calling listDocumentApprovals"
            def response = controller.listDocumentApprovals(xContextId, pageSize, cursor, status, documentType, fromEmail, createdDateFrom, createdDateTo, projectId)

        then: "service is called with correct parameters"
            1 * documentApprovalService.listDocumentApprovalsFromApi(123L, pageSize, cursor, status, documentType, fromEmail, createdDateFrom, createdDateTo, projectId) >> serviceResponse

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains approvals"
            with(response.body) {
                data.size() == 2
                pagination.cursor == "nextCursor123"
            }
    }

    def "listDocumentApprovals handles null optional parameters"() {
        given: "only required context parameter"
            def xContextId = "456"
            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse([], null)

        when: "calling listDocumentApprovals with null optional parameters"
            def response = controller.listDocumentApprovals(xContextId, null, null, null, null, null, null, null, null)

        then: "service is called with null parameters"
            1 * documentApprovalService.listDocumentApprovalsFromApi(456L, null, null, null, null, null, null, null, null) >> serviceResponse

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== reviewExtraction ====================

    def "reviewExtraction returns NO_CONTENT when approved without documentData"() {
        given: "valid context, approvalId, and request without documentData"
            def xContextId = "123"
            def approvalId = "approval-789"
            def request = new ReviewExtractionRequest()
            request.approved = true
            request.notes = "Looks good"
            request.documentData = null

        when: "calling reviewExtraction"
            def response = controller.reviewExtraction(xContextId, approvalId, request)

        then: "service is called with correct parameters (using test-user-id from setup mock)"
            1 * documentApprovalService.reviewExtraction(123L, approvalId, true, "test-user-id", "Looks good", null)

        and: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT
    }

    def "reviewExtraction returns NO_CONTENT when rejected"() {
        given: "valid context, approvalId, and rejection request"
            def xContextId = "123"
            def approvalId = "approval-789"
            def request = new ReviewExtractionRequest()
            request.approved = false
            request.notes = "Invalid data"
            request.documentData = null

        when: "calling reviewExtraction"
            def response = controller.reviewExtraction(xContextId, approvalId, request)

        then: "service is called with correct parameters (using test-user-id from setup mock)"
            1 * documentApprovalService.reviewExtraction(123L, approvalId, false, "test-user-id", "Invalid data", null)

        and: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT
    }

    def "reviewExtraction converts documentData when provided"() {
        given: "valid context, approvalId, and request with documentData"
            def xContextId = "123"
            def approvalId = "approval-789"
            def documentData = [documentType: "invoice", documentNumber: "INV-001"]
            def request = new ReviewExtractionRequest()
            request.approved = true
            request.notes = "With edits"
            request.documentData = documentData

        when: "calling reviewExtraction"
            def response = controller.reviewExtraction(xContextId, approvalId, request)

        then: "service is called with converted extraction object (using test-user-id from setup mock)"
            1 * documentApprovalService.reviewExtraction(123L, approvalId, true, "test-user-id", "With edits", _ as com.tosspaper.models.extraction.dto.Extraction)

        and: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT
    }

    // ==================== getDocumentApprovalDetail ====================

    def "getDocumentApprovalDetail returns OK with detail"() {
        given: "valid context and approvalId"
            def xContextId = "123"
            def approvalId = "approval-123"
            def domainDetail = createDomainApprovalDetail(approvalId)

        when: "calling getDocumentApprovalDetail"
            def response = controller.getDocumentApprovalDetail(xContextId, approvalId)

        then: "service returns detail"
            1 * documentApprovalDetailService.getApprovalDetail(123L, approvalId) >> domainDetail

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains detail"
            response.body.approvalId == approvalId
    }

    // ==================== getDocumentApprovalExtraction ====================

    def "getDocumentApprovalExtraction returns OK with extraction result"() {
        given: "valid context and approvalId"
            def xContextId = "123"
            def approvalId = "approval-123"
            def extractionResult = createExtractionResult("task-456")

        when: "calling getDocumentApprovalExtraction"
            def response = controller.getDocumentApprovalExtraction(xContextId, approvalId)

        then: "service returns extraction result"
            1 * documentApprovalDetailService.getExtractionResult(123L, approvalId) >> extractionResult

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains extraction"
            response.body.assignedId == "task-456"
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
