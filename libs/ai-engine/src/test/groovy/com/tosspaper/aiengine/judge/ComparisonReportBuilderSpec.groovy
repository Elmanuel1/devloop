package com.tosspaper.aiengine.judge

import com.fasterxml.jackson.databind.ObjectMapper
import org.springaicommunity.agents.judge.jury.Verdict
import org.springaicommunity.agents.judge.result.Judgment
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ComparisonReportBuilderSpec extends Specification {

    @TempDir
    Path tempDir

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ComparisonReportBuilder builder

    def setup() {
        builder = new ComparisonReportBuilder(objectMapper)
    }

    def "should build complete report from valid results"() {
        given: "valid comparison results"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "matchScore": 0.95, "reasons": ["Vendor name matches"], "discrepancies": {"phone": {"extracted": "555-1234", "po": "555-5678", "difference": "differs"}}},
                {"type": "ship_to", "matchScore": 1.0, "reasons": ["Exact match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 2, "matchScore": 0.85, "reasons": ["Item code matches"], "discrepancies": {"quantity": {"extracted": 100, "po": 120, "difference": -20}}},
                {"type": "line_item", "extractedIndex": 1, "poIndex": null, "matchScore": null, "reasons": ["No match found"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "passing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("All checks passed"))
            .individualByName(["json-object": Judgment.pass("Valid JSON"), "required-fields": Judgment.pass("All fields present")])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        report.structureValid
        report.status == "COMPLETE"
        report.timestamp != null

        and: "summary counts are correct"
        report.totalDocumentItems == 2
        report.matchedItems == 1
        report.unmatchedItems == 1
        report.itemsWithDiscrepancies == 1

        and: "vendor contact is parsed"
        report.vendorContact != null
        report.vendorContact.matched
        report.vendorContact.matchScore == 0.95
        report.vendorContact.matchReasons == "Vendor name matches"
        report.vendorContact.discrepancies.size() == 1
        report.vendorContact.discrepancies["phone"].documentValue == "555-1234"
        report.vendorContact.discrepancies["phone"].poValue == "555-5678"

        and: "ship to contact is parsed"
        report.shipToContact != null
        report.shipToContact.matched
        report.shipToContact.matchScore == 1.0
        report.shipToContact.discrepancies.isEmpty()

        and: "line items are parsed with index linking"
        report.lineItems.size() == 2
        report.lineItems[0].documentIndex == 0
        report.lineItems[0].poIndex == 2
        report.lineItems[0].matched
        report.lineItems[0].discrepancies["quantity"].documentValue == 100
        report.lineItems[0].discrepancies["quantity"].poValue == 120
        report.lineItems[0].discrepancies["quantity"].difference == -20

        report.lineItems[1].documentIndex == 1
        report.lineItems[1].poIndex == null
        !report.lineItems[1].matched

        and: "verification checks are included"
        report.checks.size() == 2
        report.checks.any { it.name == "json-object" && it.passed }
    }

    def "should return error report when structure invalid"() {
        given: "invalid JSON"
        Files.writeString(tempDir.resolve("results.json"), "{broken")

        and: "failing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.fail("Verification failed"))
            .individualByName(["json-object": Judgment.fail("Invalid JSON")])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        !report.structureValid
        report.status == "ERROR"
        report.checks.size() == 1
        !report.checks[0].passed
    }

    def "should handle missing optional fields"() {
        given: "results with minimal fields"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Test"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "reasons": ["Test"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "passing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("OK"))
            .individualByName(["json-object": Judgment.pass("OK")])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        report.structureValid
        report.vendorContact.matchScore == null
        report.lineItems[0].poIndex == null
        !report.lineItems[0].matched
    }

    def "should count items correctly"() {
        given: "results with mixed matches"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Match"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 0, "reasons": ["Match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 1, "poIndex": 1, "reasons": ["Match"], "discrepancies": {"price": {"extracted": 10, "po": 12}}},
                {"type": "line_item", "extractedIndex": 2, "poIndex": null, "reasons": ["No match"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 3, "poIndex": null, "reasons": ["No match"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "passing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("OK"))
            .individualByName([:])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        report.totalDocumentItems == 4
        report.matchedItems == 2
        report.unmatchedItems == 2
        report.itemsWithDiscrepancies == 1
    }

    def "should parse numeric discrepancy values correctly"() {
        given: "results with numeric discrepancies"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["Test"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Test"], "discrepancies": {}},
                {"type": "line_item", "extractedIndex": 0, "poIndex": 0, "reasons": ["Match"], "discrepancies": {"quantity": {"extracted": 100, "po": 120, "difference": -20}, "price": {"extracted": 15.50, "po": 15.00, "difference": 0.50}}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "passing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("OK"))
            .individualByName([:])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        def discrepancies = report.lineItems[0].discrepancies
        discrepancies["quantity"].documentValue == 100
        discrepancies["quantity"].poValue == 120
        discrepancies["quantity"].difference == -20
        discrepancies["price"].documentValue == 15.50
        discrepancies["price"].poValue == 15.00
        discrepancies["price"].difference == 0.50
    }

    def "should handle file not found with failing verdict"() {
        given: "no file exists"
        def verdict = Verdict.builder()
            .aggregated(Judgment.fail("File not found"))
            .individualByName(["json-object": Judgment.fail("File not found")])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("missing.json"), verdict)

        then:
        !report.structureValid
        report.status == "ERROR"
    }

    def "should determine contact match status from reasons"() {
        given: "contact with 'no match' in reasons"
        def json = '''
        {
            "documentId": "doc-123",
            "poId": "PO-456",
            "results": [
                {"type": "vendor", "reasons": ["No matching vendor found"], "discrepancies": {}},
                {"type": "ship_to", "reasons": ["Address matches exactly"], "discrepancies": {}}
            ]
        }
        '''
        Files.writeString(tempDir.resolve("results.json"), json)

        and: "passing verdict"
        def verdict = Verdict.builder()
            .aggregated(Judgment.pass("OK"))
            .individualByName([:])
            .build()

        when:
        def report = builder.buildReport(tempDir.resolve("results.json"), verdict)

        then:
        !report.vendorContact.matched
        report.shipToContact.matched
    }
}
