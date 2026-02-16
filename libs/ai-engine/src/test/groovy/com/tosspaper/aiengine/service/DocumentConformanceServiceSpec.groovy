package com.tosspaper.aiengine.service

import com.tosspaper.aiengine.factory.DocumentResourceFactory
import com.tosspaper.aiengine.workflow.ConformanceEvaluatorOptimizer
import com.tosspaper.aiengine.workflow.ConformanceEvaluatorOptimizer.RefinedConformanceResult
import com.tosspaper.aiengine.workflow.ConformanceEvaluatorOptimizer.IterationAttempt
import com.tosspaper.models.domain.DocumentType
import spock.lang.Specification
import spock.lang.Subject

class DocumentConformanceServiceSpec extends Specification {

    DocumentResourceFactory resourceFactory = Mock()
    ConformanceEvaluatorOptimizer evaluatorOptimizer = Mock()

    @Subject
    DocumentConformanceService service = new DocumentConformanceService(resourceFactory, evaluatorOptimizer)

    def "conformDocument should return success result when optimizer succeeds"() {
        given: "resources are loaded"
            resourceFactory.loadSchema(DocumentType.INVOICE) >> '{"type": "object"}'
            resourceFactory.loadGenerationPrompt(DocumentType.INVOICE) >> "Generate {rawData} using {schema}"
            resourceFactory.loadEvaluationPrompt(DocumentType.INVOICE) >> "Evaluate against {schema}"

        and: "optimizer returns success"
            def optimizerResult = RefinedConformanceResult.success(
                '{"documentNumber": "INV-001"}', 0.98, 1, [], null
            )
            evaluatorOptimizer.conform(_, _, _, _) >> optimizerResult

        when:
            def result = service.conformDocument('{"raw": "data"}', DocumentType.INVOICE)

        then: "result is success"
            result != null
            result.getConformedJson() == '{"documentNumber": "INV-001"}'
            result.getQualityScore() == 0.98
            result.getAttemptCount() == 1
            !result.isNeedsReview()
    }

    def "conformDocument should return needsReview result when optimizer needs review"() {
        given: "resources are loaded"
            resourceFactory.loadSchema(DocumentType.DELIVERY_SLIP) >> '{"type": "object"}'
            resourceFactory.loadGenerationPrompt(DocumentType.DELIVERY_SLIP) >> "Generate"
            resourceFactory.loadEvaluationPrompt(DocumentType.DELIVERY_SLIP) >> "Evaluate"

        and: "optimizer returns needsReview with iteration history"
            def issues = [
                new IterationAttempt(1, 0.5, ["documentNumber: Missing"])
            ]
            def optimizerResult = RefinedConformanceResult.needsReview(
                '{"incomplete": true}', 0.5, 3, issues, null
            )
            evaluatorOptimizer.conform(_, _, _, _) >> optimizerResult

        when:
            def result = service.conformDocument('{"raw": "data"}', DocumentType.DELIVERY_SLIP)

        then: "result needs review"
            result != null
            result.isNeedsReview()
            result.getQualityScore() == 0.5
            result.getAttemptCount() == 3
    }

    def "conformDocument should throw RuntimeException when optimizer fails"() {
        given: "resources are loaded"
            resourceFactory.loadSchema(DocumentType.INVOICE) >> '{"type": "object"}'
            resourceFactory.loadGenerationPrompt(DocumentType.INVOICE) >> "Generate"
            resourceFactory.loadEvaluationPrompt(DocumentType.INVOICE) >> "Evaluate"

        and: "optimizer throws"
            evaluatorOptimizer.conform(_, _, _, _) >> { throw new RuntimeException("AI model error") }

        when:
            service.conformDocument('{"raw": "data"}', DocumentType.INVOICE)

        then:
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to conform document")
    }

    def "conformDocument should throw RuntimeException when resource loading fails"() {
        given: "schema loading fails"
            resourceFactory.loadSchema(DocumentType.INVOICE) >> { throw new RuntimeException("Schema not found") }

        when:
            service.conformDocument('{"raw": "data"}', DocumentType.INVOICE)

        then:
            def ex = thrown(RuntimeException)
            ex.message.contains("Failed to conform document")
    }

    def "conformDocument should pass correct arguments to optimizer"() {
        given: "resources"
            resourceFactory.loadSchema(DocumentType.INVOICE) >> '{"schema": true}'
            resourceFactory.loadGenerationPrompt(DocumentType.INVOICE) >> "gen prompt"
            resourceFactory.loadEvaluationPrompt(DocumentType.INVOICE) >> "eval prompt"

        and:
            def optimizerResult = RefinedConformanceResult.success('{}', 0.99, 1, [], null)

        when:
            service.conformDocument('raw extraction data', DocumentType.INVOICE)

        then:
            1 * evaluatorOptimizer.conform('raw extraction data', '{"schema": true}', 'gen prompt', 'eval prompt') >> optimizerResult
    }
}
