package com.tosspaper.aiengine.service.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.agent.FileSystemComparisonAgent
import com.tosspaper.aiengine.repository.DocumentPartComparisonRepository
import com.tosspaper.aiengine.repository.ExtractionTaskRepository
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.Result
import com.tosspaper.models.service.PurchaseOrderLookupService
import org.jooq.DSLContext
import spock.lang.Specification
import spock.lang.Subject

/**
 * Unit tests for FileSystemDocumentPartComparisonService.
 * Note: Complex integration tests with ComparisonContext are skipped
 * due to difficulty mocking the extraction task and agent interactions.
 */
class FileSystemDocumentPartComparisonServiceSpec extends Specification {

    ObjectMapper objectMapper
    DocumentPartComparisonRepository repository
    ExtractionTaskRepository extractionTaskRepository
    DSLContext dslContext
    FileSystemComparisonAgent comparisonAgent
    PurchaseOrderLookupService poService

    @Subject
    FileSystemDocumentPartComparisonService service

    def setup() {
        objectMapper = new ObjectMapper()
        repository = Mock()
        extractionTaskRepository = Mock()
        dslContext = Mock()
        comparisonAgent = Mock()
        poService = Mock()
        service = new FileSystemDocumentPartComparisonService(
            objectMapper,
            repository,
            extractionTaskRepository,
            dslContext,
            comparisonAgent,
            poService
        )
    }

    def "should get comparison by assigned ID"() {
        given: "existing comparison"
        def assignedId = "doc-123"
        def companyId = 1L
        def comparison = new Comparison()
        comparison.setDocumentId(assignedId)
        comparison.setPoId("po-456")
        comparison.setResults([
            createResult(Result.Type.VENDOR, 0.9),
            createLineItemResult(0, 0.8)
        ])

        repository.findByAssignedId(assignedId, companyId) >> Optional.of(comparison)

        when: "getting comparison"
        def result = service.getComparisonByAssignedId(assignedId, companyId)

        then: "comparison is returned"
        result.isPresent()
        result.get().documentId == assignedId
        result.get().results.size() == 2
    }

    def "should return empty when no comparison found"() {
        given: "no existing comparison"
        def assignedId = "doc-new"
        def companyId = 1L
        repository.findByAssignedId(assignedId, companyId) >> Optional.empty()

        when: "getting comparison"
        def result = service.getComparisonByAssignedId(assignedId, companyId)

        then: "empty optional is returned"
        result.isEmpty()
    }

    private Result createResult(Result.Type type, double matchScore) {
        def result = new Result()
        result.setType(type)
        result.setMatchScore(matchScore)
        result.setConfidence(0.9)
        result.setStatus(Result.Status.MATCHED)
        result.setSeverity(Result.Severity.INFO)
        result.setReasons(["Match found"])
        return result
    }

    private Result createLineItemResult(int extractedIndex, double matchScore) {
        def result = createResult(Result.Type.LINE_ITEM, matchScore)
        result.setExtractedIndex(extractedIndex)
        result.setPoIndex(extractedIndex)
        return result
    }
}
