package com.tosspaper.comparisons

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.agent.ComparisonEvent
import com.tosspaper.aiengine.agent.ComparisonSummary
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.aiengine.service.impl.StreamingDocumentComparisonService
import com.tosspaper.generated.model.DocumentComparisonResult
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.service.DocumentPartComparisonService
import com.tosspaper.models.service.PurchaseOrderLookupService
import reactor.core.publisher.Flux
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration

/**
 * Unit tests for DocumentPartComparisonController.
 * Tests SSE streaming for document comparison.
 */
class DocumentPartComparisonControllerSpec extends Specification {

    DocumentPartComparisonService service = Mock()
    DocumentComparisonMapper mapper = Mock()
    ExtractionTaskRepository extractionTaskRepository = Mock()
    PurchaseOrderLookupService poLookupService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    DocumentPartComparisonController controller

    def setup() {
        controller = new DocumentPartComparisonController(
            service,
            mapper,
            extractionTaskRepository,
            poLookupService,
            objectMapper
        )
    }

    // ==================== GET COMPARISONS TESTS ====================

    def "getComparisons should return comparison when found"() {
        given: "existing comparison"
        def xContextId = "123"
        def assignedId = "doc-123"
        def comparison = new Comparison()
        comparison.documentId = assignedId

        service.getComparisonByAssignedId(assignedId, 123L) >> Optional.of(comparison)
        mapper.toDto(comparison) >> new com.tosspaper.generated.model.DocumentComparisonResult()

        when: "getting comparison"
        def response = controller.getComparisons(xContextId, assignedId)

        then: "comparison is returned"
        response.statusCodeValue == 200
        response.body != null
    }

    def "getComparisons should return 404 when not found"() {
        given: "no comparison exists"
        def xContextId = "123"
        def assignedId = "doc-123"

        service.getComparisonByAssignedId(assignedId, 123L) >> Optional.empty()

        when: "getting comparison"
        def response = controller.getComparisons(xContextId, assignedId)

        then: "404 is returned"
        response.statusCodeValue == 404
    }

    // ==================== RUN COMPARISON SSE TESTS ====================

    def "runComparison should return error stream when task not found"() {
        given: "non-existent task"
        def xContextId = "123"
        def assignedId = "non-existent"
        extractionTaskRepository.findByAssignedId(assignedId) >> null

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data().contains("NOT_FOUND")
    }

    def "runComparison should return error stream when company mismatch"() {
        given: "task from different company"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(999L)  // Different company
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data().contains("FORBIDDEN")
    }

    def "runComparison should return error stream when no PO linked"() {
        given: "task without PO"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId(null)
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data().contains("NO_PO_LINKED")
    }

    def "runComparison should return error stream when not conformed"() {
        given: "task without conformed JSON"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId("po-id")
            .poNumber("PO-456")
            .conformedJson(null)
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data().contains("NOT_CONFORMED")
    }

    def "runComparison should return error stream when PO not found"() {
        given: "valid task but PO doesn't exist"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId("po-id")
            .poNumber("PO-456")
            .conformedJson('{"lineItems": []}')
            .documentType(DocumentType.INVOICE)
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task
        poLookupService.getPoWithItemsByPoNumber(123L, "PO-456") >> Optional.empty()

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "error event is returned"
        events.size() == 1
        events[0].event() == "error"
        events[0].data().contains("PO_NOT_FOUND")
    }

    def "runComparison should stream events when using streaming service"() {
        given: "valid task and streaming service"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId("po-id")
            .poNumber("PO-456")
            .conformedJson('{"lineItems": []}')
            .documentType(DocumentType.INVOICE)
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .displayId("PO-456")
            .companyId(123L)
            .build()
        def comparison = new Comparison()
        comparison.documentId = assignedId
        comparison.results = []

        // Use streaming service mock
        def streamingService = Mock(StreamingDocumentComparisonService)
        controller = new DocumentPartComparisonController(
            streamingService,
            mapper,
            extractionTaskRepository,
            poLookupService,
            objectMapper
        )

        extractionTaskRepository.findByAssignedId(assignedId) >> task
        poLookupService.getPoWithItemsByPoNumber(123L, "PO-456") >> Optional.of(po)
        streamingService.executeComparisonStream(_) >> Flux.just(
            new ComparisonEvent.Activity("🔍", "Starting comparison..."),
            new ComparisonEvent.Activity("📄", "Analyzing document..."),
            new ComparisonEvent.Complete(comparison, new ComparisonSummary(3, 1, 4))
        )
        mapper.toDto(_) >> new DocumentComparisonResult()

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "events are streamed"
        events.size() == 4  // 1 initial processing + 3 from stream
        events[0].event() == "activity"
        events[1].event() == "activity"
        events[2].event() == "activity"
        events[3].event() == "complete"
    }

    def "runComparison should fall back to blocking when streaming not available"() {
        given: "valid task but non-streaming service"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(123L)
            .purchaseOrderId("po-id")
            .poNumber("PO-456")
            .conformedJson('{"lineItems": []}')
            .documentType(DocumentType.INVOICE)
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .displayId("PO-456")
            .companyId(123L)
            .build()
        def comparison = new Comparison()
        comparison.documentId = assignedId
        comparison.results = []

        extractionTaskRepository.findByAssignedId(assignedId) >> task
        poLookupService.getPoWithItemsByPoNumber(123L, "PO-456") >> Optional.of(po)
        service.compareDocumentParts(_) >> comparison
        mapper.toDto(_) >> new DocumentComparisonResult()

        when: "running comparison"
        def flux = controller.runComparison(xContextId, assignedId)
        def events = flux.collectList().block(Duration.ofSeconds(5))

        then: "fallback emits processing and complete events"
        events.size() == 2
        events[0].event() == "activity"
        events[1].event() == "complete"
    }
}
