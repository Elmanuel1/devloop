package com.tosspaper.document_approval


import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.MatchType
import com.tosspaper.models.domain.DocumentApproval
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.models.service.DocumentApprovalService
import spock.lang.Specification

import java.time.OffsetDateTime

class DocumentApprovalDetailServiceSpec extends Specification {

    DocumentApprovalService documentApprovalService
    ExtractionTaskRepository extractionTaskRepository
    DocumentApprovalDetailService service

    def setup() {
        documentApprovalService = Mock()
        extractionTaskRepository = Mock()
        service = new DocumentApprovalDetailService(
            documentApprovalService,
            extractionTaskRepository
        )
    }

    // ==================== getApprovalDetail ====================

    def "getApprovalDetail returns approval detail when company matches"() {
        given: "an existing approval"
            def companyId = 1L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, companyId)

        when: "fetching approval detail"
            def result = service.getApprovalDetail(companyId, approvalId)

        then: "approval is fetched"
            1 * documentApprovalService.findById(approvalId) >> approval

        and: "result contains approval fields"
            with(result) {
                approvalId == "approval-123"
                it.companyId == companyId
                assignedId == "assigned-123"
                documentType == "INVOICE"
                fromEmail == "sender@test.com"
            }
    }

    def "getApprovalDetail throws ForbiddenException when company does not match"() {
        given: "an approval from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, differentCompanyId)

        when: "fetching approval detail"
            service.getApprovalDetail(companyId, approvalId)

        then: "approval is fetched"
            1 * documentApprovalService.findById(approvalId) >> approval

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("do not have permission")
    }

    def "getApprovalDetail returns all document fields"() {
        given: "an approval with all fields populated"
            def companyId = 1L
            def approvalId = "approval-full"
            def now = OffsetDateTime.now()
            def approval = DocumentApproval.builder()
                .id(approvalId)
                .companyId(companyId)
                .assignedId("assigned-full")
                .documentType("INVOICE")
                .externalDocumentNumber("DOC-001")
                .poNumber("PO-001")
                .fromEmail("sender@test.com")
                .createdAt(now.minusDays(1))
                .projectId("proj-123")
                .approvedAt(now)  // Setting approvedAt = APPROVED status
                .reviewedBy("reviewer@test.com")
                .reviewNotes("Looks good")
                .documentSummary("Invoice for supplies")
                .storageKey("uploads/doc.pdf")
                .build()

        when: "fetching approval detail"
            def result = service.getApprovalDetail(companyId, approvalId)

        then: "approval is fetched"
            1 * documentApprovalService.findById(approvalId) >> approval

        and: "all fields are mapped"
            with(result) {
                it.approvalId == approvalId
                assignedId == "assigned-full"
                documentType == "INVOICE"
                externalDocumentNumber == "DOC-001"
                poNumber == "PO-001"
                fromEmail == "sender@test.com"
                projectId == "proj-123"
                approvedAt == now
                reviewedBy == "reviewer@test.com"
                reviewNotes == "Looks good"
                documentSummary == "Invoice for supplies"
                storageKey == "uploads/doc.pdf"
            }
    }

    // ==================== getExtractionResult ====================

    def "getExtractionResult returns extraction data when company matches"() {
        given: "an approval with extraction task"
            def companyId = 1L
            def approvalId = "approval-123"
            def assignedId = "assigned-123"
            def approval = createApproval(approvalId, companyId)
            def extractionTask = createExtractionTask(assignedId)

        when: "fetching extraction result"
            def result = service.getExtractionResult(companyId, approvalId)

        then: "approval is fetched"
            1 * documentApprovalService.findById(approvalId) >> approval

        and: "extraction task is fetched"
            1 * extractionTaskRepository.findByAssignedId(assignedId) >> extractionTask

        and: "result contains extraction fields"
            with(result) {
                it.assignedId == assignedId
                fromEmail == "sender@external.com"
                toEmail == "inbox@company.com"
                documentType == "INVOICE"
                matchType == "direct"  // MatchType.DIRECT.getValue()
                poNumber == "PO-001"
            }
    }

    def "getExtractionResult throws ForbiddenException when company does not match"() {
        given: "an approval from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, differentCompanyId)

        when: "fetching extraction result"
            service.getExtractionResult(companyId, approvalId)

        then: "approval is fetched"
            1 * documentApprovalService.findById(approvalId) >> approval

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("do not have permission")

        and: "extraction task not fetched"
            0 * extractionTaskRepository.findByAssignedId(_)
    }

    def "getExtractionResult maps all extraction task fields"() {
        given: "an approval with complete extraction task"
            def companyId = 1L
            def approvalId = "approval-full"
            def assignedId = "assigned-full"
            def approval = createApproval(approvalId, companyId, assignedId)
            def extractionTask = ExtractionTask.builder()
                .assignedId(assignedId)
                .fromAddress("vendor@supplier.com")
                .toAddress("docs@company.com")
                .storageKey("s3://bucket/path/doc.pdf")
                .matchType(MatchType.AI_MATCH)  // Valid enum value
                .matchReport("AI match on PO number")
                .poNumber("PO-999")
                .projectId("proj-999")
                .purchaseOrderId("po-uuid-999")
                .extractTaskResults("{\"items\": []}")
                .build()

        when: "fetching extraction result"
            def result = service.getExtractionResult(companyId, approvalId)

        then: "mocks"
            1 * documentApprovalService.findById(approvalId) >> approval
            1 * extractionTaskRepository.findByAssignedId(assignedId) >> extractionTask

        and: "all fields mapped"
            with(result) {
                it.assignedId == assignedId
                fromEmail == "vendor@supplier.com"
                toEmail == "docs@company.com"
                storageUrl == "s3://bucket/path/doc.pdf"
                matchType == "ai_match"  // MatchType.AI_MATCH.getValue()
                matchReport == "AI match on PO number"
                poNumber == "PO-999"
                projectId == "proj-999"
                poId == "po-uuid-999"
                extractionResult == "{\"items\": []}"
            }
    }

    // ==================== Helper Methods ====================

    private DocumentApproval createApproval(String id, Long companyId, String assignedId = "assigned-123") {
        DocumentApproval.builder()
            .id(id)
            .companyId(companyId)
            .assignedId(assignedId)
            .documentType("INVOICE")
            .fromEmail("sender@test.com")
            .createdAt(OffsetDateTime.now())
            // No approvedAt or rejectedAt = PENDING status
            .build()
    }

    private ExtractionTask createExtractionTask(String assignedId) {
        ExtractionTask.builder()
            .assignedId(assignedId)
            .fromAddress("sender@external.com")
            .toAddress("inbox@company.com")
            .storageKey("uploads/doc.pdf")
            .matchType(MatchType.DIRECT)  // Use valid enum value
            .poNumber("PO-001")
            .projectId("proj-123")
            .purchaseOrderId("po-uuid-123")
            .extractTaskResults("{}")
            .build()
    }
}
