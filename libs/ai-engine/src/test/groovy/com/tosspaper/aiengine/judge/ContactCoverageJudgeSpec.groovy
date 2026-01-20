package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.context.JudgmentContext
import org.springaicommunity.agents.judge.result.JudgmentStatus
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ContactCoverageJudgeSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    ComparisonResultsReader reader

    @Subject
    ContactCoverageJudge judge

    def setup() {
        reader = new ComparisonResultsReader(tempDir.resolve("results.json"), objectMapper)
        judge = new ContactCoverageJudge(reader)
    }

    def "should pass when both contacts are present with reasons"() {
        given: "results with both contacts"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor name matches"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}}
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
        judgment.reasoning().contains("Both vendor and ship_to present")
    }

    def "should pass when contacts have additional line items"() {
        given: "results with contacts and line items"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor name matches"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Item match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["Item match"], "discrepancies": {}}
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

    def "should fail when vendor is missing"() {
        given: "results without vendor"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Item match"], "discrepancies": {}}
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
        judgment.reasoning().contains("Missing vendor entry")
    }

    def "should fail when ship_to is missing"() {
        given: "results without ship_to"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor name matches"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Item match"], "discrepancies": {}}
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
        judgment.reasoning().contains("Missing ship_to entry")
    }

    def "should fail when both contacts are missing"() {
        given: "results with only line items"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Item match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "reasons": ["Item match"], "discrepancies": {}}
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
        judgment.reasoning().contains("Missing vendor entry")
        judgment.reasoning().contains("Missing ship_to entry")
    }

    def "should fail when vendor missing reasons"() {
        given: "vendor without reasons"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches"], "discrepancies": {}}
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
        judgment.reasoning().contains("vendor missing reasons")
    }

    def "should fail when ship_to missing reasons"() {
        given: "ship_to without reasons"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Vendor matches"], "discrepancies": {}},
                {"type": "ship_to", "discrepancies": {}}
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
        judgment.reasoning().contains("ship_to missing reasons")
    }

    def "should fail with empty results array"() {
        given: "empty results array"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": []
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
        judgment.reasoning().contains("Missing vendor entry")
        judgment.reasoning().contains("Missing ship_to entry")
    }

    def "should accept reasons indicating no match"() {
        given: "contacts with 'no match' reasons (valid - contact was checked)"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["No matching vendor found in PO"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["No matching ship-to found in PO"], "discrepancies": {}}
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

    def "should abstain when results not available"() {
        given: "no results file"
        def context = JudgmentContext.builder()
            .workspace(tempDir)
            .build()

        when:
        def judgment = judge.judge(context)

        then:
        judgment.status() == JudgmentStatus.ABSTAIN
        judgment.reasoning().contains("Skipped")
    }
}
