package com.tosspaper.aiengine.service

import com.tosspaper.aiengine.repository.DocumentMatchRepository
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.MatchType
import com.tosspaper.models.exception.ForbiddenException
import com.tosspaper.models.exception.NotFoundException
import com.tosspaper.models.messaging.MessagePublisher
import com.tosspaper.models.service.PurchaseOrderLookupService
import spock.lang.Specification
import spock.lang.Subject

class DocumentMatchServiceSpec extends Specification {

    DocumentMatchRepository documentMatchRepository = Mock()
    ExtractionTaskRepository extractionTaskRepository = Mock()
    MessagePublisher messagePublisher = Mock()
    PurchaseOrderLookupService purchaseOrderLookupService = Mock()

    @Subject
    DocumentMatchService service = new DocumentMatchService(
        documentMatchRepository,
        extractionTaskRepository,
        messagePublisher,
        purchaseOrderLookupService
    )

    def "initiateManualLink should update PO information"() {
        given: "an extraction task exists"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .documentType(DocumentType.INVOICE)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        and: "PO exists"
            def poInfo = new PurchaseOrderLookupService.PurchaseOrderBasicInfo(
                "po-id-1", 1L, null, "proj-1", "PO-123"
            )
            purchaseOrderLookupService.findByCompanyIdAndDisplayId(1L, "PO-123") >> Optional.of(poInfo)

        when: "initiating manual link"
            service.initiateManualLink(1L, "attach-123", "PO-123")

        then: "task is updated with PO info"
            1 * extractionTaskRepository.updateManualPoInformation({ ExtractionTask t ->
                t.purchaseOrderId == "po-id-1" &&
                t.poNumber == "PO-123" &&
                t.projectId == "proj-1"
            })
    }

    def "initiateManualLink should throw ForbiddenException when company ID mismatch"() {
        given: "task belongs to different company"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(2L)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when: "initiating manual link with wrong company"
            service.initiateManualLink(1L, "attach-123", "PO-123")

        then: "exception is thrown"
            thrown(ForbiddenException)
    }

    def "initiateManualLink should throw NotFoundException when PO not found"() {
        given: "task exists"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        and: "PO does not exist"
            purchaseOrderLookupService.findByCompanyIdAndDisplayId(1L, "PO-MISSING") >> Optional.empty()

        when: "initiating manual link"
            service.initiateManualLink(1L, "attach-123", "PO-MISSING")

        then: "exception is thrown"
            thrown(NotFoundException)
    }

    def "initiateAutoMatch should update status and publish message"() {
        given: "an extraction task exists"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .documentType(DocumentType.INVOICE)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when: "initiating auto match"
            service.initiateAutoMatch("attach-123")

        then: "status updated to in progress"
            1 * documentMatchRepository.updateToInProgress("attach-123", DocumentType.INVOICE)

        and: "message published to Redis"
            1 * messagePublisher.publish("po-match-requests", { Map<String, String> payload ->
                payload.get("assignedId") == "attach-123" &&
                payload.get("documentType") == DocumentType.INVOICE.getFilePrefix() &&
                payload.get("matchType") == "auto"
            })
    }

    def "resetToPending should update status to pending"() {
        given: "an extraction task exists"
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .documentType(DocumentType.INVOICE)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when: "resetting to pending"
            service.resetToPending("attach-123")

        then: "status updated to pending"
            1 * documentMatchRepository.updateToPending("attach-123", DocumentType.INVOICE)
    }
}
