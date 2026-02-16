package com.tosspaper.aiengine.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.domain.ComparisonContext
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.MatchType
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.exception.BadRequestException
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import spock.lang.Specification
import spock.lang.Subject

class AbstractDocumentPartComparisonServiceSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper()
    DocumentPartComparisonRepository repository = Mock()
    ExtractionTaskRepository extractionTaskRepository = Mock()
    DSLContext dslContext = Mock()
    PurchaseOrderLookupService poLookupService = Mock()

    @Subject
    TestableDocumentPartComparisonService service

    def setup() {
        service = new TestableDocumentPartComparisonService(
            objectMapper, repository, extractionTaskRepository, dslContext, poLookupService
        )
    }

    def "getComparisonByAssignedId should return comparison when found"() {
        given:
            def comparison = Mock(Comparison)
            repository.findByAssignedId("attach-123", 1L) >> Optional.of(comparison)

        when:
            def result = service.getComparisonByAssignedId("attach-123", 1L)

        then:
            result.isPresent()
            result.get() == comparison
    }

    def "getComparisonByAssignedId should return empty when not found"() {
        given:
            repository.findByAssignedId("attach-missing", 1L) >> Optional.empty()

        when:
            def result = service.getComparisonByAssignedId("attach-missing", 1L)

        then:
            !result.isPresent()
    }

    def "manuallyTriggerComparison should throw when task not found"() {
        given:
            extractionTaskRepository.findByAssignedId("attach-123") >> null

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "EXTRACTION_NOT_FOUND"
    }

    def "manuallyTriggerComparison should throw when company mismatch"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(2L)
                .purchaseOrderId("po-1")
                .conformedJson('{"json": true}')
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "COMPANY_MISMATCH"
    }

    def "manuallyTriggerComparison should throw when no PO linked"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .conformedJson('{"json": true}')
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "NO_PO_LINKED"
    }

    def "manuallyTriggerComparison should throw when no conformed JSON"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .purchaseOrderId("po-1")
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "NOT_CONFORMED"
    }

    def "manuallyTriggerComparison should throw when PO linked with blank string"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .purchaseOrderId("  ")
                .conformedJson('{"json": true}')
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "NO_PO_LINKED"
    }

    def "manuallyTriggerComparison should throw when conformed JSON is blank"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .purchaseOrderId("po-1")
                .conformedJson("  ")
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            def ex = thrown(BadRequestException)
            ex.code == "NOT_CONFORMED"
    }

    def "manuallyTriggerComparison should run comparison and save result"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .purchaseOrderId("po-1")
                .poNumber("PO-100")
                .conformedJson('{"documentNumber": "INV-001"}')
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task

        and: "PO lookup returns PO"
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-100")
                .build()
            poLookupService.getPoWithItemsByPoNumber(1L, "PO-100") >> Optional.of(po)

        and: "transaction is executed inline"
            dslContext.transaction(_) >> { args ->
                def callable = args[0]
                def config = Mock(Configuration)
                config.dsl() >> dslContext
                callable.run(config)
            }

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then: "comparison result is upserted"
            1 * repository.upsert(_, "attach-123", _)
    }

    def "manuallyTriggerComparison should throw RuntimeException on failure"() {
        given:
            def task = ExtractionTask.builder()
                .assignedId("attach-123")
                .companyId(1L)
                .purchaseOrderId("po-1")
                .poNumber("PO-100")
                .conformedJson('{"json": true}')
                .matchType(MatchType.MANUAL)
                .build()
            extractionTaskRepository.findByAssignedId("attach-123") >> task
            poLookupService.getPoWithItemsByPoNumber(1L, "PO-100") >> Optional.empty()

        when:
            service.manuallyTriggerComparison("attach-123", 1L)

        then:
            thrown(RuntimeException)
    }

    /**
     * Concrete test implementation of AbstractDocumentPartComparisonService
     */
    static class TestableDocumentPartComparisonService extends AbstractDocumentPartComparisonService {

        TestableDocumentPartComparisonService(
                ObjectMapper objectMapper,
                DocumentPartComparisonRepository repository,
                ExtractionTaskRepository extractionTaskRepository,
                DSLContext dslContext,
                PurchaseOrderLookupService poLookupService) {
            super(objectMapper, repository, extractionTaskRepository, dslContext, poLookupService)
        }

        @Override
        Comparison compareDocumentParts(ComparisonContext context) {
            return new Comparison()
        }
    }
}
