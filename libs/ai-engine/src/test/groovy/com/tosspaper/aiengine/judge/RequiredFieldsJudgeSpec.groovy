package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.context.JudgmentContext
import org.springaicommunity.agents.judge.result.JudgmentStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class RequiredFieldsJudgeSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    RequiredFieldsJudge judge

    ComparisonResultsReader reader

    def setup() {
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new RequiredFieldsJudge(reader)
    }

    def "should pass when all entries have required fields"() {
        given: "valid entries with all required fields"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "vendor",
                    "reasons": ["Name matches"],
                    "discrepancies": {}
                },
                {
                    "type": "line_item",
                    "extractedIndex": 0,
                    "reasons": ["Item code matches"],
                    "discrepancies": {"quantity": {"document": 100, "po": 120}}
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
        judgment.reasoning().contains("2 entries have required fields")
    }

    def "should fail when entry missing type"() {
        given: "entry missing type"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "reasons": ["Test"],
                    "discrepancies": {}
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("missing required field: type")
    }

    def "should fail when entry missing reasons"() {
        given: "entry missing reasons"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "vendor",
                    "discrepancies": {}
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("missing required field: reasons")
    }

    def "should fail when entry missing discrepancies"() {
        given: "entry missing discrepancies"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "vendor",
                    "reasons": ["Test"]
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("missing required field: discrepancies")
    }

    def "should fail when line_item missing extractedIndex"() {
        given: "line_item entry missing extractedIndex"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "line_item",
                    "reasons": ["Test"],
                    "discrepancies": {}
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("Line item at index 0 missing required field: extractedIndex")
    }

    def "should not require extractedIndex for vendor"() {
        given: "vendor without extractedIndex (valid)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "vendor",
                    "reasons": ["Test"],
                    "discrepancies": {}
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
    }

    def "should report multiple missing fields"() {
        given: "multiple entries with missing fields"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {
                    "type": "vendor"
                },
                {
                    "type": "line_item",
                    "reasons": ["Test"]
                }
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        // First entry missing reasons and discrepancies
        judgment.reasoning().contains("Entry 0 missing required field: reasons")
        judgment.reasoning().contains("Entry 0 missing required field: discrepancies")
        // Second entry missing discrepancies and extractedIndex
        judgment.reasoning().contains("Entry 1 missing required field: discrepancies")
        judgment.reasoning().contains("Line item at index 1 missing required field: extractedIndex")
    }
}
