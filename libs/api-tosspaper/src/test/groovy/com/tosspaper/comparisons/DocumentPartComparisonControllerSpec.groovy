package com.tosspaper.comparisons

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.generated.model.DocumentComparisonResult
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.service.DocumentPartComparisonService
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for DocumentPartComparisonController.
 * Tests SSE streaming for document comparison using SseEmitter.
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
        mapper.toDto(comparison) >> new DocumentComparisonResult()

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

    def "runComparison should return SseEmitter when task not found"() {
        given: "non-existent task"
        def xContextId = "123"
        def assignedId = "non-existent"
        extractionTaskRepository.findByAssignedId(assignedId) >> null

        when: "running comparison"
        def emitter = controller.runComparison(xContextId, assignedId)

        then: "SseEmitter is returned (error sent synchronously)"
        emitter != null
        emitter instanceof SseEmitter
    }

    def "runComparison should return SseEmitter when company mismatch"() {
        given: "task from different company"
        def xContextId = "123"
        def assignedId = "doc-123"
        def task = ExtractionTask.builder()
            .assignedId(assignedId)
            .companyId(999L)  // Different company
            .build()

        extractionTaskRepository.findByAssignedId(assignedId) >> task

        when: "running comparison"
        def emitter = controller.runComparison(xContextId, assignedId)

        then: "SseEmitter is returned"
        emitter != null
        emitter instanceof SseEmitter
    }

    def "runComparison should return SseEmitter when no PO linked"() {
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
        def emitter = controller.runComparison(xContextId, assignedId)

        then: "SseEmitter is returned"
        emitter != null
        emitter instanceof SseEmitter
    }

    def "runComparison should return SseEmitter when not conformed"() {
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
        def emitter = controller.runComparison(xContextId, assignedId)

        then: "SseEmitter is returned"
        emitter != null
        emitter instanceof SseEmitter
    }

    def "runComparison should return SseEmitter with 3 minute timeout"() {
        given: "non-existent task"
        def xContextId = "123"
        def assignedId = "non-existent"
        extractionTaskRepository.findByAssignedId(assignedId) >> null

        when: "running comparison"
        def emitter = controller.runComparison(xContextId, assignedId)

        then: "SseEmitter has correct timeout (180 seconds)"
        emitter != null
        emitter.timeout == 180_000L
    }
}
