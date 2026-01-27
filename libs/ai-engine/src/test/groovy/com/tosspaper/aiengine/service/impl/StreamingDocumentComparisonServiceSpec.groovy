package com.tosspaper.aiengine.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.agent.ComparisonEvent
import com.tosspaper.aiengine.agent.StreamingComparisonAgent
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.jooq.DSLContext
import reactor.core.publisher.Flux
import spock.lang.Specification
import spock.lang.Subject

import java.time.Duration

/**
 * Unit tests for StreamingDocumentComparisonService.
 * Tests the streaming implementation of document comparison.
 */
class StreamingDocumentComparisonServiceSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper()
    DocumentPartComparisonRepository repository = Mock()
    ExtractionTaskRepository extractionTaskRepository = Mock()
    DSLContext dslContext = Mock()
    StreamingComparisonAgent streamingAgent = Mock()
    PurchaseOrderLookupService poService = Mock()

    @Subject
    StreamingDocumentComparisonService service

    def setup() {
        service = new StreamingDocumentComparisonService(
            objectMapper,
            repository,
            extractionTaskRepository,
            dslContext,
            streamingAgent,
            poService
        )
    }

    // ==================== STREAMING TESTS ====================

    def "executeComparisonStream should return flux of events"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .conformedJson('{}')
            .documentType(DocumentType.INVOICE)
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .companyId(1L)
            .build()
        def context = new ComparisonContext(po, task)

        and: "agent returns events"
        def comparison = new Comparison()
        comparison.setDocumentId("doc-123")
        comparison.setPoId("PO-456")
        comparison.setResults([])

        def events = [
            new ComparisonEvent.Activity("📄", "Processing..."),
            new ComparisonEvent.Thinking("Analyzing..."),
            ComparisonEvent.Complete.of(comparison)
        ]
        streamingAgent.executeComparison(context) >> Flux.fromIterable(events)

        when: "streaming comparison"
        def result = service.executeComparisonStream(context)
            .collectList()
            .block(Duration.ofSeconds(5))

        then: "events are returned"
        result.size() == 3
        result[0] instanceof ComparisonEvent.Activity
        result[1] instanceof ComparisonEvent.Thinking
        result[2] instanceof ComparisonEvent.Complete
    }

    def "executeComparisonStream should handle errors"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder().id("po-id").companyId(1L).build()
        def context = new ComparisonContext(po, task)

        and: "agent returns error"
        streamingAgent.executeComparison(context) >> Flux.error(new RuntimeException("Agent error"))

        when: "streaming comparison"
        service.executeComparisonStream(context)
            .collectList()
            .block(Duration.ofSeconds(5))

        then: "error is propagated"
        thrown(RuntimeException)
    }

    // ==================== BLOCKING WRAPPER TESTS ====================

    def "compareDocumentParts should return comparison from stream"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .conformedJson('{}')
            .documentType(DocumentType.INVOICE)
            .build()
        def po = PurchaseOrder.builder()
            .id("po-id")
            .companyId(1L)
            .build()
        def context = new ComparisonContext(po, task)

        and: "agent returns events with Complete"
        def comparison = new Comparison()
        comparison.setDocumentId("doc-123")
        comparison.setPoId("PO-456")
        comparison.setResults([])

        def events = [
            new ComparisonEvent.Activity("📄", "Processing..."),
            ComparisonEvent.Complete.of(comparison)
        ]
        streamingAgent.executeComparison(context) >> Flux.fromIterable(events)

        when: "comparing documents (blocking)"
        def result = service.compareDocumentParts(context)

        then: "comparison is returned"
        result.documentId == "doc-123"
        result.poId == "PO-456"
    }

    def "compareDocumentParts should return null if no Complete event"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder().id("po-id").companyId(1L).build()
        def context = new ComparisonContext(po, task)

        and: "agent returns events without Complete"
        def events = [
            new ComparisonEvent.Activity("📄", "Processing..."),
            ComparisonEvent.Error.of("Something failed")
        ]
        streamingAgent.executeComparison(context) >> Flux.fromIterable(events)

        when: "comparing documents (blocking)"
        def result = service.compareDocumentParts(context)

        then: "null is returned"
        result == null
    }

    // ==================== DELEGATE TESTS ====================

    def "should delegate to streaming agent"() {
        given: "comparison context"
        def task = ExtractionTask.builder()
            .assignedId("doc-123")
            .companyId(1L)
            .poNumber("PO-456")
            .build()
        def po = PurchaseOrder.builder().id("po-id").companyId(1L).build()
        def context = new ComparisonContext(po, task)

        and: "agent returns empty flux"
        streamingAgent.executeComparison(context) >> Flux.empty()

        when: "streaming comparison"
        service.executeComparisonStream(context).collectList().block(Duration.ofSeconds(5))

        then: "agent is called"
        1 * streamingAgent.executeComparison(context) >> Flux.empty()
    }

    // ==================== INHERITED METHOD TESTS ====================

    def "getComparisonByAssignedId should delegate to repository"() {
        given: "repository returns comparison"
        def comparison = new Comparison()
        comparison.setDocumentId("doc-123")
        repository.findByAssignedId("doc-123", 1L) >> Optional.of(comparison)

        when: "getting comparison"
        def result = service.getComparisonByAssignedId("doc-123", 1L)

        then: "comparison is returned"
        result.isPresent()
        result.get().documentId == "doc-123"
    }

    def "getComparisonByAssignedId should return empty when not found"() {
        given: "repository returns empty"
        repository.findByAssignedId("missing", 1L) >> Optional.empty()

        when: "getting comparison"
        def result = service.getComparisonByAssignedId("missing", 1L)

        then: "empty is returned"
        result.isEmpty()
    }
}
