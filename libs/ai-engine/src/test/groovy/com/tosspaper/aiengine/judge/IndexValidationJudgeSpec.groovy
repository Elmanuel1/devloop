package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.context.JudgmentContext
import org.springaicommunity.agents.judge.result.JudgmentStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class IndexValidationJudgeSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    ComparisonResultsReader reader

    @Subject
    IndexValidationJudge judge

    def "should pass when all indices are sequential 0..N-1"() {
        given: "results with sequential indices"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 2, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "poIndex": 0, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 2, "poIndex": null, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 3)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
        judgment.reasoning().contains("3 line items have valid indices")
    }

    def "should pass when indices are out of order but complete"() {
        given: "results with out-of-order but complete indices"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 2, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 3)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
    }

    def "should fail when there is a gap in sequence"() {
        given: "results with gap (missing index 1)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 2, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 3, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 3)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.status() == JudgmentStatus.FAIL
        judgment.reasoning().contains("Missing extractedIndex 1")
    }

    def "should fail when there are duplicate indices"() {
        given: "results with duplicate index"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 3)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("Duplicate extractedIndex: 1")
    }

    def "should fail when line item count doesn't match expected"() {
        given: "fewer line items than expected"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 5) // expect 5, have 2

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("Expected 5 line items, found 2")
    }

    def "should fail when extractedIndex is negative"() {
        given: "results with negative index"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": -1, "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 1)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("cannot be negative")
    }

    def "should allow null poIndex"() {
        given: "results with null poIndex (unmatched item)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "poIndex": null, "reasons": ["No match"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 1)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
    }

    def "should allow missing poIndex"() {
        given: "results with no poIndex field"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["No match"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 1)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
    }

    def "should fail when poIndex is not integer"() {
        given: "results with string poIndex"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "poIndex": "invalid", "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 1)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        !judgment.pass()
        judgment.reasoning().contains("poIndex must be null or integer")
    }

    def "should pass with zero expected items"() {
        given: "no line items expected (contacts only)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["test"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new IndexValidationJudge(reader, 0)

        and: "judgment context"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.pass()
    }
}
