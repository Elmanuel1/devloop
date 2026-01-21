package com.tosspaper.document_approval

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.repository.DocumentApprovalRepository
import com.tosspaper.delivery_notes.DeliveryNoteRepository
import com.tosspaper.delivery_slips.DeliverySlipRepository
import com.tosspaper.integrations.push.IntegrationPushStreamPublisher
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.invoices.InvoiceMapper
import com.tosspaper.invoices.InvoiceRepository
import com.tosspaper.models.exception.NotFoundException
import com.tosspaper.models.domain.DocumentApproval
import com.tosspaper.models.exception.BadRequestException
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.models.extraction.dto.Extraction
import com.tosspaper.models.mapper.ExtractionToDomainMapper
import com.tosspaper.models.query.DocumentApprovalQuery
import com.tosspaper.models.domain.PurchaseOrderStatus
import com.tosspaper.models.service.PurchaseOrderLookupService
import com.tosspaper.models.messaging.MessagePublisher
import com.tosspaper.purchaseorder.PurchaseOrderRepository
import org.jooq.DSLContext
import spock.lang.Specification

import java.time.OffsetDateTime

class DocumentApprovalServiceSpec extends Specification {

    DocumentApprovalRepository documentApprovalRepository
    MessagePublisher messagePublisher
    DSLContext dslContext
    ExtractionToDomainMapper mapper
    InvoiceRepository invoiceRepository
    DeliveryNoteRepository deliveryNoteRepository
    DeliverySlipRepository deliverySlipRepository
    PurchaseOrderLookupService poLookupService
    PurchaseOrderRepository purchaseOrderRepository
    InvoiceMapper invoiceMapper
    IntegrationConnectionService integrationConnectionService
    IntegrationPushStreamPublisher integrationPushStreamPublisher
    ObjectMapper objectMapper
    DocumentApprovalServiceImpl service

    def setup() {
        documentApprovalRepository = Mock()
        messagePublisher = Mock()
        dslContext = Mock()
        mapper = Mock()
        invoiceRepository = Mock()
        deliveryNoteRepository = Mock()
        deliverySlipRepository = Mock()
        poLookupService = Mock()
        purchaseOrderRepository = Mock()
        invoiceMapper = Mock()
        integrationConnectionService = Mock()
        integrationPushStreamPublisher = Mock()
        objectMapper = new ObjectMapper()
        service = new DocumentApprovalServiceImpl(
            documentApprovalRepository,
            messagePublisher,
            dslContext,
            mapper,
            invoiceRepository,
            deliveryNoteRepository,
            deliverySlipRepository,
            poLookupService,
            purchaseOrderRepository,
            invoiceMapper,
            integrationConnectionService,
            integrationPushStreamPublisher,
            objectMapper
        )
    }

    // ==================== listDocumentApprovalsFromApi ====================

    def "listDocumentApprovalsFromApi returns paginated list with default page size"() {
        given: "approvals exist for company"
            def companyId = 1L
            def approvals = [
                createApproval("approval-1", companyId),
                createApproval("approval-2", companyId)
            ]

        when: "listing approvals without page size"
            def result = service.listDocumentApprovalsFromApi(companyId, null, null, null, null, null, null, null, null)

        then: "repository is called with default page size of 20"
            1 * documentApprovalRepository.findByQuery(_ as DocumentApprovalQuery) >> { DocumentApprovalQuery q ->
                assert q.pageSize == 20
                assert q.companyId == "1"
                return approvals
            }

        and: "result contains approvals"
            with(result) {
                data.size() == 2
                data[0].id == "approval-1"
                data[1].id == "approval-2"
            }
    }

    def "listDocumentApprovalsFromApi uses provided page size"() {
        given: "a specific page size"
            def companyId = 1L
            def pageSize = 5

        when: "listing approvals with page size"
            def result = service.listDocumentApprovalsFromApi(companyId, pageSize, null, null, null, null, null, null, null)

        then: "repository is called with provided page size"
            1 * documentApprovalRepository.findByQuery(_ as DocumentApprovalQuery) >> { DocumentApprovalQuery q ->
                assert q.pageSize == 5
                return []
            }

        and: "result is returned"
            result.data.isEmpty()
    }

    def "listDocumentApprovalsFromApi generates next cursor when results equal page size"() {
        given: "results that fill the page"
            def companyId = 1L
            def pageSize = 2
            def approvals = [
                createApproval("approval-1", companyId),
                createApproval("approval-2", companyId)
            ]

        when: "listing approvals"
            def result = service.listDocumentApprovalsFromApi(companyId, pageSize, null, null, null, null, null, null, null)

        then: "repository returns exactly pageSize results"
            1 * documentApprovalRepository.findByQuery(_) >> approvals

        and: "next cursor is generated"
            result.nextCursor != null
    }

    def "listDocumentApprovalsFromApi returns null cursor when results less than page size"() {
        given: "results that don't fill the page"
            def companyId = 1L
            def pageSize = 10
            def approvals = [createApproval("approval-1", companyId)]

        when: "listing approvals"
            def result = service.listDocumentApprovalsFromApi(companyId, pageSize, null, null, null, null, null, null, null)

        then: "repository returns less than pageSize"
            1 * documentApprovalRepository.findByQuery(_) >> approvals

        and: "no next cursor"
            result.nextCursor == null
    }

    def "listDocumentApprovalsFromApi passes filter parameters to query"() {
        given: "filter parameters"
            def companyId = 1L
            def status = "pending"
            def documentType = "INVOICE"
            def fromEmail = "sender@test.com"
            def projectId = "proj-123"

        when: "listing approvals with filters"
            service.listDocumentApprovalsFromApi(companyId, 20, null, status, documentType, fromEmail, null, null, projectId)

        then: "repository is called with all filters"
            1 * documentApprovalRepository.findByQuery(_ as DocumentApprovalQuery) >> { DocumentApprovalQuery q ->
                assert q.status == status
                assert q.documentType == documentType
                assert q.fromEmail == fromEmail
                assert q.projectId == projectId
                return []
            }
    }

    def "listDocumentApprovalsFromApi passes date filters to query"() {
        given: "date filters"
            def companyId = 1L
            def createdFrom = OffsetDateTime.now().minusDays(7)
            def createdTo = OffsetDateTime.now()

        when: "listing approvals with date filters"
            service.listDocumentApprovalsFromApi(companyId, 20, null, null, null, null, createdFrom, createdTo, null)

        then: "repository is called with date filters"
            1 * documentApprovalRepository.findByQuery(_ as DocumentApprovalQuery) >> { DocumentApprovalQuery q ->
                assert q.createdDateFrom == createdFrom
                assert q.createdDateTo == createdTo
                return []
            }
    }

    def "listDocumentApprovalsFromApi throws IllegalArgumentException for invalid cursor"() {
        given: "an invalid cursor"
            def companyId = 1L
            def invalidCursor = "invalid-cursor-format"

        when: "listing approvals with invalid cursor"
            service.listDocumentApprovalsFromApi(companyId, 20, invalidCursor, null, null, null, null, null, null)

        then: "IllegalArgumentException is thrown"
            thrown(IllegalArgumentException)
    }

    // ==================== reviewExtraction - Approve ====================

    def "reviewExtraction approves document and creates invoice"() {
        given: "a pending approval for invoice"
            def companyId = 1L
            def approvalId = "approval-123"
            def reviewedBy = "reviewer@test.com"
            def approval = createApproval(approvalId, companyId)
            approval.assignedId = "assigned-123"
            approval.documentType = "invoice"
            def extraction = createExtraction("PO-001", Extraction.DocumentType.INVOICE)
            def poInfo = new PurchaseOrderLookupService.PurchaseOrderBasicInfo("po-id", companyId, PurchaseOrderStatus.PENDING, "proj-id", "PO-001")
            def mockInvoice = com.tosspaper.models.domain.Invoice.builder().assignedId("inv-1").build()

            // Stub the repository calls that happen inside the transaction
            documentApprovalRepository.approve(_, approvalId, "proj-id", reviewedBy, "Approved") >> approval
            mapper.toDomainModel(_, _) >> mockInvoice
            invoiceRepository.create(_, _) >> Mock(com.tosspaper.models.jooq.tables.records.InvoicesRecord)
            invoiceMapper.toDomain(_) >> mockInvoice
            purchaseOrderRepository.updateStatusToInProgressIfPending(_, "po-id", companyId) >> _

        when: "reviewing with approval"
            service.reviewExtraction(companyId, approvalId, true, reviewedBy, "Approved", extraction)

        then: "approval is fetched"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "PO is looked up"
            1 * poLookupService.findByCompanyIdAndDisplayId(companyId, "PO-001") >> Optional.of(poInfo)

        and: "transaction is executed"
            1 * dslContext.transaction(_) >> { args ->
                // Execute the transaction - TransactionalRunnable.run() takes Configuration
                def transactional = args[0]
                def mockConfig = Mock(org.jooq.Configuration) {
                    dsl() >> dslContext
                }
                transactional.run(mockConfig)
            }

        and: "document approved event is published"
            1 * messagePublisher.publish("document-approved-events", _)
    }

    def "reviewExtraction throws ForbiddenException when company does not match"() {
        given: "an approval from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, differentCompanyId)
            def extraction = createExtraction("PO-001", Extraction.DocumentType.INVOICE)

        when: "reviewing approval"
            service.reviewExtraction(companyId, approvalId, true, "reviewer@test.com", null, extraction)

        then: "approval is fetched"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "ForbiddenException is thrown"
            def ex = thrown(ForbiddenException)
            ex.message.contains("does not belong to this company")

        and: "no further processing"
            0 * poLookupService.findByCompanyIdAndDisplayId(_, _)
            0 * dslContext.transaction(_)
    }

    def "reviewExtraction throws BadRequestException when approval already reviewed"() {
        given: "an already approved document"
            def companyId = 1L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, companyId, true, false)  // approved=true
            def extraction = createExtraction("PO-001", Extraction.DocumentType.INVOICE)

        when: "trying to review again"
            service.reviewExtraction(companyId, approvalId, true, "reviewer@test.com", null, extraction)

        then: "approval is fetched"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("already been approved")

        and: "no further processing"
            0 * poLookupService.findByCompanyIdAndDisplayId(_, _)
    }

    def "reviewExtraction throws BadRequestException when PO not found"() {
        given: "an approval with non-existent PO"
            def companyId = 1L
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, companyId)
            def extraction = createExtraction("INVALID-PO", Extraction.DocumentType.INVOICE)

        when: "approving with invalid PO"
            service.reviewExtraction(companyId, approvalId, true, "reviewer@test.com", null, extraction)

        then: "approval is fetched"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "PO lookup returns empty"
            1 * poLookupService.findByCompanyIdAndDisplayId(companyId, "INVALID-PO") >> Optional.empty()

        and: "BadRequestException is thrown"
            def ex = thrown(BadRequestException)
            ex.message.contains("PO provided is not found")
    }

    def "reviewExtraction throws NotFoundException when approval does not exist"() {
        given: "a non-existent approval ID"
            def companyId = 1L
            def approvalId = "non-existent"
            def extraction = createExtraction("PO-001", Extraction.DocumentType.INVOICE)
            documentApprovalRepository.findById(approvalId) >> { throw new NotFoundException("Document approval not found") }

        when: "reviewing extraction"
            service.reviewExtraction(companyId, approvalId, true, "reviewer", "notes", extraction)

        then: "NotFoundException is thrown"
            thrown(NotFoundException)
    }

    // ==================== reviewExtraction - Reject ====================

    def "reviewExtraction rejects document"() {
        given: "a pending approval"
            def companyId = 1L
            def approvalId = "approval-123"
            def reviewedBy = "reviewer@test.com"
            def reviewNotes = "Document unclear"
            def approval = createApproval(approvalId, companyId)
            def extraction = createExtraction("PO-001", Extraction.DocumentType.INVOICE)

        when: "rejecting approval"
            service.reviewExtraction(companyId, approvalId, false, reviewedBy, reviewNotes, extraction)

        then: "approval is fetched"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "approval is rejected in repository"
            1 * documentApprovalRepository.reject(approvalId, reviewedBy, reviewNotes)

        and: "document approved event is published"
            1 * messagePublisher.publish("document-approved-events", _)

        and: "no document creation"
            0 * invoiceRepository.create(_, _)
            0 * deliveryNoteRepository.create(_, _)
            0 * deliverySlipRepository.create(_, _)
    }

    // ==================== findById ====================

    def "findById returns document approval"() {
        given: "an existing approval"
            def approvalId = "approval-123"
            def approval = createApproval(approvalId, 1L)

        when: "finding by ID"
            def result = service.findById(approvalId)

        then: "repository is called"
            1 * documentApprovalRepository.findById(approvalId) >> approval

        and: "result is returned"
            result.id == approvalId
    }

    def "findById throws NotFoundException when not found"() {
        given: "a non-existent ID"
            def approvalId = "non-existent"
            documentApprovalRepository.findById(approvalId) >> { throw new NotFoundException("Document approval not found") }

        when: "finding by ID"
            service.findById(approvalId)

        then: "NotFoundException is thrown"
            thrown(NotFoundException)
    }

    // ==================== findByQuery ====================

    def "findByQuery returns matching approvals"() {
        given: "a query"
            def query = DocumentApprovalQuery.builder()
                .companyId("1")
                .status("pending")
                .pageSize(10)
                .build()
            def approvals = [createApproval("approval-1", 1L)]

        when: "finding by query"
            def result = service.findByQuery(query)

        then: "repository is called"
            1 * documentApprovalRepository.findByQuery(query) >> approvals

        and: "results are returned"
            result.size() == 1
    }

    // ==================== findByAssignedId ====================

    def "findByAssignedId returns approval when found"() {
        given: "an assigned ID"
            def assignedId = "assigned-123"
            def approval = createApproval("approval-1", 1L)

        when: "finding by assigned ID"
            def result = service.findByAssignedId(assignedId)

        then: "repository is called"
            1 * documentApprovalRepository.findByAssignedId(assignedId) >> Optional.of(approval)

        and: "result is present"
            result.isPresent()
            result.get().id == "approval-1"
    }

    def "findByAssignedId returns empty when not found"() {
        given: "a non-existent assigned ID"
            def assignedId = "non-existent"

        when: "finding by assigned ID"
            def result = service.findByAssignedId(assignedId)

        then: "repository is called"
            1 * documentApprovalRepository.findByAssignedId(assignedId) >> Optional.empty()

        and: "result is empty"
            result.isEmpty()
    }

    // ==================== Helper Methods ====================

    private DocumentApproval createApproval(String id, Long companyId, boolean approved = false, boolean rejected = false) {
        def builder = DocumentApproval.builder()
            .id(id)
            .companyId(companyId)
            .assignedId("assigned-${id}")
            .createdAt(OffsetDateTime.now())

        if (approved) {
            builder.approvedAt(OffsetDateTime.now())
        }
        if (rejected) {
            builder.rejectedAt(OffsetDateTime.now())
        }

        return builder.build()
    }

    private Extraction createExtraction(String poNumber, Extraction.DocumentType documentType) {
        def extraction = new Extraction()
        extraction.customerPONumber = poNumber
        extraction.documentType = documentType
        return extraction
    }
}
