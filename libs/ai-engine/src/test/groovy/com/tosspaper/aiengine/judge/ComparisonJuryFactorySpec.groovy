package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.result.JudgmentStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ComparisonJuryFactorySpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ComparisonJuryFactory factory

    Path schemaPath

    def setup() {
        factory = new ComparisonJuryFactory(objectMapper)
        // Create a minimal valid schema for tests
        schemaPath = tempDir.resolve("schema.json")
        Files.writeString(schemaPath, '''
        {
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["documentId", "poId", "results"],
            "properties": {
                "documentId": {"type": "string"},
                "poId": {"type": "string"},
                "results": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "required": ["type", "reasons", "discrepancies"],
                        "properties": {
                            "type": {"enum": ["vendor", "ship_to", "line_item"]},
                            "extractedIndex": {"type": "integer"},
                            "poIndex": {"type": ["integer", "null"]},
                            "reasons": {"type": "array"},
                            "discrepancies": {"type": "object"}
                        }
                    }
                }
            }
        }
        ''')
    }

    def "should create jury with 6 judges"() {
        when:
        def jury = factory.createJury(tempDir.resolve("results.json"), schemaPath, 2)

        then:
        jury != null
        jury.judges.size() == 6
    }

    def "should pass verification for valid results"() {
        given: "valid comparison results with wrapper object"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor matches"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 1, "reasons": ["Item matches"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "poIndex": null, "reasons": ["No match"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then:
        verdict.aggregated().pass()
        verdict.aggregated().status() == JudgmentStatus.PASS
        verdict.individualByName().size() == 6
        verdict.individualByName().every { name, judgment -> judgment.pass() }
    }

    def "should fail verification when file not found"() {
        when:
        def verdict = factory.runVerification(tempDir.resolve("missing.json"), schemaPath, 2, tempDir)

        then:
        !verdict.aggregated().pass()
        verdict.aggregated().status() == JudgmentStatus.FAIL
        verdict.individualByName()["json-object"].status() == JudgmentStatus.FAIL
        verdict.individualByName()["json-object"].reasoning().contains("not found")
    }

    def "should fail verification when JSON invalid"() {
        given: "invalid JSON"
        Files.writeString(tempDir.resolve("results.json"), "{broken json")

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then:
        !verdict.aggregated().pass()
        verdict.individualByName()["json-object"].status() == JudgmentStatus.FAIL
    }

    def "should fail verification when required fields missing"() {
        given: "results missing required fields"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor"},
                {"type": "ship_to", "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 0, tempDir)

        then: "required-fields judge fails (weighted average may still pass)"
        verdict.individualByName()["required-fields"].status() == JudgmentStatus.FAIL
        verdict.individualByName()["required-fields"].reasoning().contains("missing")
    }

    def "should fail verification when indices invalid"() {
        given: "results with invalid indices (gap)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Test"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 2, "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then: "index-validation judge fails (weighted average may still pass)"
        verdict.individualByName()["index-validation"].status() == JudgmentStatus.FAIL
        verdict.individualByName()["index-validation"].reasoning().contains("Missing")
    }

    def "should fail verification when contacts missing"() {
        given: "results without contacts"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 1, tempDir)

        then: "contact-coverage judge fails (weighted average may still pass)"
        verdict.individualByName()["contact-coverage"].status() == JudgmentStatus.FAIL
        verdict.individualByName()["contact-coverage"].reasoning().contains("Missing")
    }

    def "should fail verification when duplicate poIndex found"() {
        given: "results with duplicate poIndex values"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Test"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 1, "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "poIndex": 1, "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then: "po-index-uniqueness judge fails"
        verdict.individualByName()["po-index-uniqueness"].status() == JudgmentStatus.FAIL
        verdict.individualByName()["po-index-uniqueness"].reasoning().contains("Duplicate")
    }

    def "should throw exception on verification failure"() {
        given: "invalid results (missing wrapper fields)"
        Files.writeString(tempDir.resolve("results.json"), '{"results": []}')

        when:
        factory.verifyOrThrow(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then:
        def ex = thrown(ComparisonVerificationException)
        ex.verdict != null
        ex.verdict.aggregated().status() == JudgmentStatus.FAIL
        ex.individualJudgments.size() == 6
    }

    def "should not throw when verification passes"() {
        given: "valid results"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor matches"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        factory.verifyOrThrow(tempDir.resolve("results.json"), schemaPath, 0, tempDir)

        then:
        noExceptionThrown()
    }

    def "should collect all failures in verdict reasoning"() {
        given: "results with multiple issues"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 2, "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        when:
        def verdict = factory.runVerification(tempDir.resolve("results.json"), schemaPath, 2, tempDir)

        then: "multiple judges fail (index-validation and contact-coverage)"
        def failedJudges = verdict.individualByName().findAll { name, judgment -> !judgment.pass() }
        failedJudges.size() >= 2
        failedJudges.containsKey("index-validation")
        failedJudges.containsKey("contact-coverage")
    }
}
