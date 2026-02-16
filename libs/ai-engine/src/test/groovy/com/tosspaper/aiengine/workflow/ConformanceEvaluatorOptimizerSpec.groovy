package com.tosspaper.aiengine.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.dto.conformance.EvaluationResponse
import com.tosspaper.aiengine.dto.conformance.ValidationIssue
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import spock.lang.Specification
import spock.lang.Subject

class ConformanceEvaluatorOptimizerSpec extends Specification {

    ChatModel generatorModel = Mock()
    ChatModel evaluatorModel = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ConformanceEvaluatorOptimizer optimizer

    def setup() {
        optimizer = new ConformanceEvaluatorOptimizer(generatorModel, evaluatorModel, objectMapper)
        // Set threshold and max iterations via reflection
        def thresholdField = ConformanceEvaluatorOptimizer.getDeclaredField("successThreshold")
        thresholdField.setAccessible(true)
        thresholdField.set(optimizer, 0.95d)
        def maxIterField = ConformanceEvaluatorOptimizer.getDeclaredField("maxIterations")
        maxIterField.setAccessible(true)
        maxIterField.set(optimizer, 3)
    }

    def "conform should succeed on first iteration when score meets threshold"() {
        given: "generation prompt and evaluation prompt"
            def rawExtraction = '{"field": "value"}'
            def jsonSchema = '{"type": "object"}'
            def generationPrompt = "Convert {rawData} to {schema}"
            def evaluationPrompt = "Evaluate against {schema}"

        and: "generator returns valid JSON"
            def generatedJson = '{"documentNumber": "INV-001"}'
            def generatorResponse = Mock(ChatResponse)
            def generation = Mock(Generation)
            def assistantMsg = new AssistantMessage('```json\n' + generatedJson + '\n```')
            generation.getOutput() >> assistantMsg
            generatorResponse.getResult() >> generation
            generatorModel.call(_) >> generatorResponse

        and: "evaluator returns high score - use raw JSON to avoid severityEnum serialization"
            def evaluationJson = '{"isSchemaValid":true,"score":0.98,"issues":[],"suggestions":"Looks good"}'
            def evalResponse = Mock(ChatResponse)
            def evalGeneration = Mock(Generation)
            def evalMsg = new AssistantMessage(evaluationJson)
            evalGeneration.getOutput() >> evalMsg
            evalResponse.getResult() >> evalGeneration
            evaluatorModel.call(_) >> evalResponse

        when: "conforming"
            def result = optimizer.conform(rawExtraction, jsonSchema, generationPrompt, evaluationPrompt)

        then: "result is successful"
            !result.needsReview()
            result.qualityScore() >= 0.95
            result.attemptCount() == 1
            result.history().size() == 1
    }

    def "conform should iterate and return needsReview when threshold not met"() {
        given: "prompts"
            def rawExtraction = '{"field": "value"}'
            def jsonSchema = '{"type": "object"}'
            def generationPrompt = "Convert {rawData} to {schema}"
            def evaluationPrompt = "Evaluate against {schema}"

        and: "generator always returns JSON"
            def generatorResponse = Mock(ChatResponse)
            def generation = Mock(Generation)
            def assistantMsg = new AssistantMessage('```json\n{"incomplete": true}\n```')
            generation.getOutput() >> assistantMsg
            generatorResponse.getResult() >> generation
            generatorModel.call(_) >> generatorResponse

        and: "evaluator always returns low score - use raw JSON to avoid severityEnum serialization"
            def evaluationJson = '{"isSchemaValid":false,"score":0.5,"issues":[{"field":"documentNumber","problem":"Missing","severity":"critical"}],"suggestions":"Add missing fields"}'
            def evalResponse = Mock(ChatResponse)
            def evalGeneration = Mock(Generation)
            def evalMsg = new AssistantMessage(evaluationJson)
            evalGeneration.getOutput() >> evalMsg
            evalResponse.getResult() >> evalGeneration
            evaluatorModel.call(_) >> evalResponse

        when: "conforming"
            def result = optimizer.conform(rawExtraction, jsonSchema, generationPrompt, evaluationPrompt)

        then: "result needs review after max iterations"
            result.needsReview()
            result.attemptCount() == 3
            result.history().size() == 3
    }

    def "conform should succeed after improvement on second iteration"() {
        given: "prompts"
            def rawExtraction = '{"field": "value"}'
            def jsonSchema = '{"type": "object"}'
            def generationPrompt = "Convert {rawData} to {schema}"
            def evaluationPrompt = "Evaluate against {schema}"

        and: "generator returns JSON"
            def generatorResponse = Mock(ChatResponse)
            def generation = Mock(Generation)
            def assistantMsg = new AssistantMessage('{"documentNumber": "INV-001"}')
            generation.getOutput() >> assistantMsg
            generatorResponse.getResult() >> generation
            generatorModel.call(_) >> generatorResponse

        and: "evaluator returns low score first, then high - use raw JSON to avoid severityEnum"
            def lowScoreJson = '{"isSchemaValid":true,"score":0.7,"issues":[{"field":"date","problem":"Missing","severity":"warning"}],"suggestions":"Add date"}'
            def highScoreJson = '{"isSchemaValid":true,"score":0.98,"issues":[],"suggestions":"Perfect"}'

            def evalResponse1 = Mock(ChatResponse)
            def evalGeneration1 = Mock(Generation)
            evalGeneration1.getOutput() >> new AssistantMessage(lowScoreJson)
            evalResponse1.getResult() >> evalGeneration1

            def evalResponse2 = Mock(ChatResponse)
            def evalGeneration2 = Mock(Generation)
            evalGeneration2.getOutput() >> new AssistantMessage(highScoreJson)
            evalResponse2.getResult() >> evalGeneration2

            evaluatorModel.call(_) >>> [evalResponse1, evalResponse2]

        when: "conforming"
            def result = optimizer.conform(rawExtraction, jsonSchema, generationPrompt, evaluationPrompt)

        then: "result succeeds on second iteration"
            !result.needsReview()
            result.attemptCount() == 2
            result.qualityScore() >= 0.95
    }

    def "RefinedConformanceResult success factory should set needsReview false"() {
        when: "creating success result"
            def result = ConformanceEvaluatorOptimizer.RefinedConformanceResult.success(
                '{"json": true}', 0.98, 1, [], null
            )

        then:
            !result.needsReview()
            result.qualityScore() == 0.98
    }

    def "RefinedConformanceResult needsReview factory should set needsReview true"() {
        when: "creating needs review result"
            def result = ConformanceEvaluatorOptimizer.RefinedConformanceResult.needsReview(
                '{"json": true}', 0.5, 3, [], null
            )

        then:
            result.needsReview()
            result.qualityScore() == 0.5
    }
}
