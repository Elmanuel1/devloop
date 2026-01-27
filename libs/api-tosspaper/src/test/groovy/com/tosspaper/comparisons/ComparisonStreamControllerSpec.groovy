package com.tosspaper.comparisons

import com.tosspaper.aiengine.agent.ComparisonEvent
import com.tosspaper.aiengine.properties.ComparisonProperties
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.aiengine.service.impl.StreamingDocumentComparisonService
import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.springframework.http.HttpStatus
import reactor.core.publisher.Flux
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration

/**
 * Unit tests for ComparisonStreamController.
 * Tests the SSE streaming endpoints for document comparison.
 */
class ComparisonStreamControllerSpec extends Specification {

    StreamingDocumentComparisonService comparisonService = Mock()
    ExtractionTaskRepository extractionTaskRepository = Mock()
    PurchaseOrderLookupService poLookupService = Mock()
    ComparisonProperties properties = new ComparisonProperties()

    @Subject
    ComparisonStreamController controller

    def setup() {
        controller = new ComparisonStreamController(
            comparisonService,
            extractionTaskRepository,
            poLookupService,
            properties
        )
    }

    // ==================== START STREAMING COMPARISON TESTS ====================

    def "startStreamingComparison should return taskId when valid"() {
        given: "valid context header and extraction task"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .poNumber("PO-456")
            .purchaseOrderId("po-id")
            .conformedJson('{"lineItems": []}')
            .documentType(DocumentType.INVOICE)
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .displayId("PO-456")
            .companyId(123L)
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task
        poLookupService.getPoWithItemsByPoNumber(123L, "PO-456") >> Optional.of(po)
        comparisonService.executeComparisonStream(_) >> Flux.empty()

        when: "starting streaming comparison"
        def response = controller.startStreamingComparison(xContextId, assignedId)

        then: "taskId is returned"
        response.statusCode == HttpStatus.OK
        response.body.containsKey("taskId")
        response.body.get("taskId") != null
    }

    def "startStreamingComparison should return 404 when task not found"() {
        given: "non-existent task"
        def xContextId = "123"
        def assignedId = "non-existent"
        extractionTaskRepository.findByAssignedId(assignedId) >> null

        when: "starting streaming comparison"
        def response = controller.startStreamingComparison(xContextId, assignedId)

        then: "404 is returned"
        response.statusCode == HttpStatus.NOT_FOUND
    }

    def "startStreamingComparison should return 403 when company mismatch"() {
        given: "task from different company"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(999L)  // Different company
            .poNumber("PO-456")
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "starting streaming comparison"
        def response = controller.startStreamingComparison(xContextId, assignedId)

        then: "403 is returned"
        response.statusCode == HttpStatus.FORBIDDEN
    }

    def "startStreamingComparison should return 400 when no PO linked"() {
        given: "task without PO"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId(null)  // No PO
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "starting streaming comparison"
        def response = controller.startStreamingComparison(xContextId, assignedId)

        then: "400 with error is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.get("error") == "NO_PO_LINKED"
    }

    def "startStreamingComparison should return 400 when not conformed"() {
        given: "task without conformed JSON"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId("po-id")
            .poNumber("PO-456")
            .conformedJson(null)  // Not conformed
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "starting streaming comparison"
        def response = controller.startStreamingComparison(xContextId, assignedId)

        then: "400 with error is returned"
        response.statusCode == HttpStatus.BAD_REQUEST
        response.body.get("error") == "NOT_CONFORMED"
    }

    // ==================== STREAM CONNECTION TESTS ====================

    def "streamComparison should return error for unknown taskId"() {
        given: "unknown taskId"
        def taskId = "unknown-task-id"

        when: "connecting to stream"
        def flux = controller.streamComparison(taskId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data() instanceof ComparisonEvent.Error
        (events[0].data() as ComparisonEvent.Error).code() == "STREAM_NOT_FOUND"
    }

    // ==================== STATUS ENDPOINT TESTS ====================

    def "getStreamStatus should return inactive for unknown taskId"() {
        given: "unknown taskId"
        def taskId = "unknown-task-id"

        when: "checking status"
        def response = controller.getStreamStatus(taskId)

        then: "inactive status is returned"
        response.statusCode == HttpStatus.OK
        response.body.get("taskId") == taskId
        response.body.get("active") == false
        response.body.containsKey("activeStreams")
    }
}
