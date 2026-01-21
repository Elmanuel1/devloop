package com.tosspaper.document_approval

import com.fasterxml.jackson.databind.ObjectMapper
import com.mailgun.api.v3.MailgunMessagesApi
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.config.MailgunProperties
import com.tosspaper.models.domain.DocumentApproval
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.extraction.dto.Extraction
import com.tosspaper.models.mapper.ExtractionToDomainMapper
import com.tosspaper.models.service.DocumentApprovalService
import io.micrometer.observation.ObservationRegistry
import spock.lang.Specification

import java.time.OffsetDateTime

class DocumentApprovalEmailProcessingServiceSpec extends Specification {

    MailgunProperties mailgunProperties
    MailgunMessagesApi mailgunMessagesApi
    ObjectMapper objectMapper
    ExtractionToDomainMapper extractionToDomainMapper
    ExtractionTaskRepository extractionTaskRepository
    DocumentApprovalService documentApprovalService
    ObservationRegistry observationRegistry
    DocumentApprovalEmailProcessingServiceImpl service

    def setup() {
        mailgunProperties = Mock()
        mailgunMessagesApi = Mock()
        objectMapper = new ObjectMapper()
        extractionToDomainMapper = Mock()
        extractionTaskRepository = Mock()
        documentApprovalService = Mock()
        observationRegistry = ObservationRegistry.NOOP
        service = new DocumentApprovalEmailProcessingServiceImpl(
            mailgunProperties,
            mailgunMessagesApi,
            objectMapper,
            extractionToDomainMapper,
            extractionTaskRepository,
            documentApprovalService,
            observationRegistry
        )
    }

    // ==================== processDocumentApproval - Rejected ====================

    def "processDocumentApproval sends rejection email for rejected document"() {
        given: "a rejected document approval"
            def assignedId = "assigned-123"
            def extractionTask = createExtractionTask(assignedId)
            def approval = createApprovalRejected(assignedId)
            mailgunProperties.getFromEmail() >> "noreply@tosspaper.com"
            mailgunProperties.getDomain() >> "tosspaper.com"

        when: "processing document approval"
            service.processDocumentApproval(assignedId)

        then: "extraction task is fetched"
            1 * extractionTaskRepository.findByAssignedId(assignedId) >> extractionTask

        and: "document approval is fetched"
            1 * documentApprovalService.findByAssignedId(assignedId) >> Optional.of(approval)

        and: "rejection email is sent"
            1 * mailgunMessagesApi.sendMessage("tosspaper.com", _) >> _
    }

    // ==================== processDocumentApproval - Pending ====================

    def "processDocumentApproval does not send email for pending document"() {
        given: "a pending document approval"
            def assignedId = "assigned-123"
            def extractionTask = createExtractionTask(assignedId)
            def approval = createApprovalPending(assignedId)

        when: "processing document approval"
            service.processDocumentApproval(assignedId)

        then: "extraction task is fetched"
            1 * extractionTaskRepository.findByAssignedId(assignedId) >> extractionTask

        and: "document approval is fetched"
            1 * documentApprovalService.findByAssignedId(assignedId) >> Optional.of(approval)

        and: "no email is sent (pending documents don't trigger emails)"
            0 * mailgunMessagesApi.sendMessage(_, _)
    }

    // ==================== processDocumentApproval - Approved Invoice ====================

    def "processDocumentApproval sends acceptance email for approved invoice"() {
        given: "an approved invoice"
            def assignedId = "assigned-123"
            def extractionTask = createExtractionTask(assignedId)
            def approval = createApprovalApproved(assignedId, "invoice")
            def invoice = com.tosspaper.models.domain.Invoice.builder()
                .assignedId("inv-123")
                .documentDate(java.time.LocalDate.now())
                .lineItems([])
                .build()
            mailgunProperties.getFromEmail() >> "noreply@tosspaper.com"
            mailgunProperties.getDomain() >> "tosspaper.com"

        when: "processing document approval"
            service.processDocumentApproval(assignedId)

        then: "extraction task is fetched"
            1 * extractionTaskRepository.findByAssignedId(assignedId) >> extractionTask

        and: "document approval is fetched"
            1 * documentApprovalService.findByAssignedId(assignedId) >> Optional.of(approval)

        and: "extraction is mapped to invoice"
            1 * extractionToDomainMapper.toInvoice(_, _) >> invoice

        and: "acceptance email is sent"
            1 * mailgunMessagesApi.sendMessage("tosspaper.com", _) >> _
    }

    // ==================== Helper Methods ====================

    private ExtractionTask createExtractionTask(String assignedId) {
        def extraction = new Extraction()
        extraction.documentType = Extraction.DocumentType.INVOICE
        extraction.documentNumber = "INV-001"

        ExtractionTask.builder()
            .assignedId(assignedId)
            .conformedJson(new ObjectMapper().writeValueAsString(extraction))
            .build()
    }

    private DocumentApproval createApprovalPending(String assignedId, String docType = "invoice") {
        DocumentApproval.builder()
            .id("approval-123")
            .companyId(1L)
            .assignedId(assignedId)
            .documentType(docType)
            .fromEmail("vendor@test.com")
            .createdAt(OffsetDateTime.now())
            // No approvedAt or rejectedAt = PENDING status
            .build()
    }

    private DocumentApproval createApprovalApproved(String assignedId, String docType = "invoice") {
        DocumentApproval.builder()
            .id("approval-123")
            .companyId(1L)
            .assignedId(assignedId)
            .documentType(docType)
            .fromEmail("vendor@test.com")
            .createdAt(OffsetDateTime.now())
            .approvedAt(OffsetDateTime.now())  // APPROVED status
            .build()
    }

    private DocumentApproval createApprovalRejected(String assignedId, String docType = "invoice") {
        DocumentApproval.builder()
            .id("approval-123")
            .companyId(1L)
            .assignedId(assignedId)
            .documentType(docType)
            .fromEmail("vendor@test.com")
            .createdAt(OffsetDateTime.now())
            .rejectedAt(OffsetDateTime.now())  // REJECTED status
            .reviewNotes("Document rejected")
            .build()
    }
}
