package com.tosspaper.api

import com.tosspaper.document_approval.DocumentApprovalApiService
import com.tosspaper.generated.model.MatchType
import com.tosspaper.models.domain.DocumentApproval
import com.tosspaper.models.domain.DocumentApprovalDetail
import com.tosspaper.models.domain.ExtractionResult
import spock.lang.Specification

import java.time.OffsetDateTime

class DocumentApprovalMapperSpec extends Specification {

    // ==================== toApi ====================

    def "toApi returns null when domain is null"() {
        when: "mapping null domain"
            def result = DocumentApprovalMapper.toApi(null)

        then: "result is null"
            result == null
    }

    def "toApi maps all fields correctly"() {
        given: "a complete domain document approval"
            def domain = DocumentApproval.builder()
                .id("doc-approval-123")
                .assignedId("task-456")
                .companyId(1L)
                .fromEmail("vendor@supplier.com")
                .documentType("invoice")
                .projectId("proj-789")
                .approvedAt(OffsetDateTime.now().minusDays(1))
                .rejectedAt(null)
                .reviewedBy("admin@company.com")
                .reviewNotes("Approved for payment")
                .documentSummary("Invoice #12345 for office supplies")
                .storageKey("companies/1/docs/invoice.pdf")
                .createdAt(OffsetDateTime.now().minusDays(2))
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toApi(domain)

        then: "all fields are mapped correctly"
            result != null
            result.id == "doc-approval-123"
            result.assignedId == "task-456"
            result.companyId == 1L
            result.fromEmail == "vendor@supplier.com"
            result.documentType == "invoice"
            result.projectId == "proj-789"
            result.approvedAt != null
            result.rejectedAt == null
            result.reviewedBy == "admin@company.com"
            result.reviewNotes == "Approved for payment"
            result.documentSummary == "Invoice #12345 for office supplies"
            result.storageKey == "companies/1/docs/invoice.pdf"
            result.createdAt != null
    }

    def "toApi handles null optional fields"() {
        given: "domain with minimal fields"
            def domain = DocumentApproval.builder()
                .id("doc-123")
                .assignedId("task-456")
                .companyId(1L)
                .fromEmail("vendor@supplier.com")
                .documentType("invoice")
                .projectId(null)
                .approvedAt(null)
                .rejectedAt(null)
                .reviewedBy(null)
                .reviewNotes(null)
                .documentSummary(null)
                .storageKey(null)
                .createdAt(OffsetDateTime.now())
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toApi(domain)

        then: "null fields are preserved"
            result.id == "doc-123"
            result.projectId == null
            result.approvedAt == null
            result.rejectedAt == null
            result.reviewedBy == null
            result.reviewNotes == null
            result.documentSummary == null
            result.storageKey == null
    }

    def "toApi handles rejected document"() {
        given: "a rejected document"
            def domain = DocumentApproval.builder()
                .id("doc-123")
                .assignedId("task-456")
                .companyId(1L)
                .fromEmail("vendor@supplier.com")
                .documentType("delivery_slip")
                .rejectedAt(OffsetDateTime.now())
                .reviewedBy("admin@company.com")
                .reviewNotes("Duplicate submission")
                .createdAt(OffsetDateTime.now().minusDays(1))
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toApi(domain)

        then: "rejection fields are set"
            result.rejectedAt != null
            result.approvedAt == null
            result.reviewNotes == "Duplicate submission"
    }

    // ==================== toApiList ====================

    def "toApiList returns empty list when input is null"() {
        when: "mapping null list"
            def result = DocumentApprovalMapper.toApiList(null)

        then: "result is empty list"
            result != null
            result.isEmpty()
    }

    def "toApiList maps empty list"() {
        when: "mapping empty list"
            def result = DocumentApprovalMapper.toApiList([])

        then: "result is empty"
            result.isEmpty()
    }

    def "toApiList maps single document"() {
        given: "single document"
            def domain = createDocumentApproval(id: "doc-1")

        when: "mapping to API list"
            def result = DocumentApprovalMapper.toApiList([domain])

        then: "list contains one item"
            result.size() == 1
            result[0].id == "doc-1"
    }

    def "toApiList maps multiple documents"() {
        given: "multiple documents"
            def domains = [
                createDocumentApproval(id: "doc-1"),
                createDocumentApproval(id: "doc-2"),
                createDocumentApproval(id: "doc-3")
            ]

        when: "mapping to API list"
            def result = DocumentApprovalMapper.toApiList(domains)

        then: "all documents are mapped"
            result.size() == 3
            result*.id == ["doc-1", "doc-2", "doc-3"]
    }

    // ==================== toApiListWithPagination ====================

    def "toApiListWithPagination maps data and cursor"() {
        given: "service response with cursor"
            def domains = [createDocumentApproval()]
            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse(
                domains,
                "cursor-abc123"
            )

        when: "mapping to API with pagination"
            def result = DocumentApprovalMapper.toApiListWithPagination(serviceResponse)

        then: "data and cursor are mapped"
            result.data.size() == 1
            result.pagination.cursor == "cursor-abc123"
    }

    def "toApiListWithPagination handles null cursor"() {
        given: "service response without cursor (last page)"
            def domains = [createDocumentApproval()]
            def serviceResponse = new DocumentApprovalApiService.DocumentApprovalListResponse(
                domains,
                null
            )

        when: "mapping to API with pagination"
            def result = DocumentApprovalMapper.toApiListWithPagination(serviceResponse)

        then: "cursor is null"
            result.data.size() == 1
            result.pagination.cursor == null
    }

    // ==================== toDetailApi ====================

    def "toDetailApi returns null when domain is null"() {
        when: "mapping null domain"
            def result = DocumentApprovalMapper.toDetailApi(null)

        then: "result is null"
            result == null
    }

    def "toDetailApi maps all fields correctly"() {
        given: "a complete document approval detail"
            def domain = DocumentApprovalDetail.builder()
                .approvalId("approval-123")
                .assignedId("task-456")
                .companyId(1L)
                .documentType("invoice")
                .externalDocumentNumber("INV-12345")
                .poNumber("PO-67890")
                .fromEmail("vendor@supplier.com")
                .createdAt(OffsetDateTime.now().minusDays(2))
                .projectId("proj-789")
                .approvedAt(OffsetDateTime.now().minusDays(1))
                .rejectedAt(null)
                .reviewedBy("admin@company.com")
                .reviewNotes("Looks good")
                .documentSummary("Office supplies invoice")
                .storageKey("companies/1/docs/invoice.pdf")
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toDetailApi(domain)

        then: "all fields are mapped"
            result.approvalId == "approval-123"
            result.assignedId == "task-456"
            result.companyId == 1L
            result.documentType == "invoice"
            result.externalDocumentNumber == "INV-12345"
            result.poNumber == "PO-67890"
            result.fromEmail == "vendor@supplier.com"
            result.createdAt != null
            result.projectId == "proj-789"
            result.approvedAt != null
            result.rejectedAt == null
            result.reviewedBy == "admin@company.com"
            result.reviewNotes == "Looks good"
            result.documentSummary == "Office supplies invoice"
            result.storageKey == "companies/1/docs/invoice.pdf"
    }

    // ==================== toExtractionResultApi ====================

    def "toExtractionResultApi returns null when domain is null"() {
        when: "mapping null domain"
            def result = DocumentApprovalMapper.toExtractionResultApi(null)

        then: "result is null"
            result == null
    }

    def "toExtractionResultApi maps all fields correctly"() {
        given: "a complete extraction result"
            def domain = ExtractionResult.builder()
                .assignedId("task-123")
                .fromEmail("vendor@supplier.com")
                .toEmail("buyer@company.com")
                .documentType("invoice")
                .extractionResult('{"vendor": "ACME Corp"}')
                .storageUrl("s3://bucket/doc.pdf")
                .poNumber("PO-12345")
                .projectId("proj-789")
                .poId("po-id-456")
                .matchType("ai_match")
                .matchReport(null) // Test with null to avoid JSON parsing complexity
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toExtractionResultApi(domain)

        then: "all fields are mapped"
            result.assignedId == "task-123"
            result.fromEmail == "vendor@supplier.com"
            result.toEmail == "buyer@company.com"
            result.documentType == "invoice"
            result.extractionResult == '{"vendor": "ACME Corp"}'
            result.storageUrl == "s3://bucket/doc.pdf"
            result.poNumber == "PO-12345"
            result.projectId == "proj-789"
            result.poId == "po-id-456"
            result.matchType == MatchType.AI_MATCH
            result.matchReport == []
    }

    def "toExtractionResultApi handles null matchType with default PENDING"() {
        given: "extraction result with null matchType"
            def domain = ExtractionResult.builder()
                .assignedId("task-123")
                .fromEmail("vendor@supplier.com")
                .toEmail("buyer@company.com")
                .documentType("invoice")
                .extractionResult('{}')
                .storageUrl("s3://bucket/doc.pdf")
                .matchType(null)
                .matchReport(null)
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toExtractionResultApi(domain)

        then: "matchType defaults to PENDING"
            result.matchType == MatchType.PENDING
    }

    def "toExtractionResultApi handles null matchReport"() {
        given: "extraction result with null matchReport"
            def domain = ExtractionResult.builder()
                .assignedId("task-123")
                .fromEmail("vendor@supplier.com")
                .toEmail("buyer@company.com")
                .documentType("invoice")
                .extractionResult('{}')
                .matchType("pending")
                .matchReport(null)
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toExtractionResultApi(domain)

        then: "matchReport is empty list (generated model initializes with empty ArrayList)"
            result.matchReport == []
    }

    def "toExtractionResultApi parses matchReport JSON when provided"() {
        given: "extraction result with valid matchReport JSON array of CandidateComparison"
            def matchReportJson = '[{"poId": "po-123", "poNumber": "PO-001", "projectId": "proj-1", "comparison": {"score": 0.95, "summary": "Good match"}}]'
            def domain = ExtractionResult.builder()
                .assignedId("task-123")
                .fromEmail("vendor@supplier.com")
                .toEmail("buyer@company.com")
                .documentType("invoice")
                .extractionResult('{}')
                .matchType("ai_match")
                .matchReport(matchReportJson)
                .build()

        when: "mapping to API"
            def result = DocumentApprovalMapper.toExtractionResultApi(domain)

        then: "matchReport is parsed as List of CandidateComparison"
            result.matchReport != null
            result.matchReport instanceof List
            result.matchReport.size() == 1
            result.matchReport[0].poId == "po-123"
            result.matchReport[0].poNumber == "PO-001"
    }


    def "toExtractionResultApi handles various matchType values"() {
        given: "extraction results with different match types"
            def matchTypes = ["pending", "in_progress", "direct", "ai_match", "no_match", "manual", "no_po_required"]

        when: "mapping all types"
            def results = matchTypes.collect { type ->
                def domain = ExtractionResult.builder()
                    .assignedId("task-123")
                    .fromEmail("vendor@supplier.com")
                    .toEmail("buyer@company.com")
                    .documentType("invoice")
                    .extractionResult('{}')
                    .matchType(type)
                    .build()
                DocumentApprovalMapper.toExtractionResultApi(domain)
            }

        then: "all match types are correctly mapped"
            results*.matchType*.value == matchTypes
    }

    // ==================== Helper Methods ====================

    private DocumentApproval createDocumentApproval(Map overrides = [:]) {
        def now = OffsetDateTime.now()
        def defaults = [
            id: "doc-123",
            assignedId: "task-456",
            companyId: 1L,
            fromEmail: "vendor@supplier.com",
            documentType: "invoice",
            projectId: null,
            approvedAt: null,
            rejectedAt: null,
            reviewedBy: null,
            reviewNotes: null,
            documentSummary: null,
            storageKey: null,
            createdAt: now
        ]

        def merged = defaults + overrides

        return DocumentApproval.builder()
            .id(merged.id)
            .assignedId(merged.assignedId)
            .companyId(merged.companyId)
            .fromEmail(merged.fromEmail)
            .documentType(merged.documentType)
            .projectId(merged.projectId)
            .approvedAt(merged.approvedAt)
            .rejectedAt(merged.rejectedAt)
            .reviewedBy(merged.reviewedBy)
            .reviewNotes(merged.reviewNotes)
            .documentSummary(merged.documentSummary)
            .storageKey(merged.storageKey)
            .createdAt(merged.createdAt)
            .build()
    }
}
