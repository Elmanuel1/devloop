package com.tosspaper.aiengine.agent

import com.tosspaper.models.extraction.dto.Comparison
import spock.lang.Specification

class ComparisonEventSpec extends Specification {

    // ==================== Finding TESTS ====================

    def "Finding.match should create finding with matched status"() {
        when:
        def finding = ComparisonEvent.Finding.match("vendor", "Names match exactly")

        then:
        finding.icon() == "\u2713"
        finding.item() == "vendor"
        finding.status() == "matched"
        finding.detail() == "Names match exactly"
        finding.eventType() == "finding"
    }

    def "Finding.partial should create finding with partial status"() {
        when:
        def finding = ComparisonEvent.Finding.partial("address", "Postal code differs")

        then:
        finding.icon() == "\u26A0"
        finding.item() == "address"
        finding.status() == "partial"
        finding.detail() == "Postal code differs"
    }

    def "Finding.mismatch should create finding with unmatched status"() {
        when:
        def finding = ComparisonEvent.Finding.mismatch("price", "50 vs 55")

        then:
        finding.icon() == "\u2717"
        finding.item() == "price"
        finding.status() == "unmatched"
        finding.detail() == "50 vs 55"
    }

    // ==================== Error TESTS ====================

    def "Error single-arg constructor should set message and null code"() {
        when:
        def error = new ComparisonEvent.Error("Something went wrong")

        then:
        error.message() == "Something went wrong"
        error.code() == null
        error.eventType() == "error"
    }

    def "Error.of with message should set null code"() {
        when:
        def error = ComparisonEvent.Error.of("Timeout")

        then:
        error.message() == "Timeout"
        error.code() == null
    }

    def "Error.of with message and code should set both"() {
        when:
        def error = ComparisonEvent.Error.of("Not found", "EXTRACTION_NOT_FOUND")

        then:
        error.message() == "Not found"
        error.code() == "EXTRACTION_NOT_FOUND"
    }

    // ==================== Thinking TESTS ====================

    def "Thinking.of should create thinking event"() {
        when:
        def thinking = ComparisonEvent.Thinking.of("Analyzing vendor details...")

        then:
        thinking.content() == "Analyzing vendor details..."
        thinking.eventType() == "thinking"
    }

    def "Thinking constructor should set content"() {
        when:
        def thinking = new ComparisonEvent.Thinking("some text")

        then:
        thinking.content() == "some text"
    }

    // ==================== Activity TESTS ====================

    def "Activity.reviewing with PO should use clipboard icon"() {
        when:
        def activity = ComparisonEvent.Activity.reviewing("purchase order", "PO-100")

        then:
        activity.icon() == "\uD83D\uDCCB"
        activity.message().contains("Reviewing")
        activity.message().contains("purchase order")
        activity.message().contains("PO-100")
        activity.eventType() == "activity"
    }

    def "Activity.reviewing with invoice should use document icon"() {
        when:
        def activity = ComparisonEvent.Activity.reviewing("invoice", "INV-200")

        then:
        activity.icon() == "\uD83D\uDCC4"
        activity.message().contains("invoice")
    }

    def "Activity.reviewing with delivery slip should use package icon"() {
        when:
        def activity = ComparisonEvent.Activity.reviewing("delivery slip", "DS-300")

        then:
        activity.icon() == "\uD83D\uDCE6"
    }

    def "Activity.reviewing with delivery note should use memo icon"() {
        when:
        def activity = ComparisonEvent.Activity.reviewing("delivery note", "DN-400")

        then:
        activity.icon() == "\uD83D\uDCDD"
    }

    def "Activity.reviewing with unknown type should use document icon"() {
        when:
        def activity = ComparisonEvent.Activity.reviewing("unknown", "X-500")

        then:
        activity.icon() == "\uD83D\uDCC4"
    }

    def "Activity.analyzing should show document type"() {
        when:
        def activity = ComparisonEvent.Activity.analyzing("invoice")

        then:
        activity.icon() == "\uD83D\uDCC4"
        activity.message().contains("Analyzing")
        activity.message().contains("invoice")
    }

    def "Activity.comparing should show aspect"() {
        when:
        def activity = ComparisonEvent.Activity.comparing("vendor information")

        then:
        activity.icon() == "\uD83D\uDD0D"
        activity.message().contains("Comparing")
        activity.message().contains("vendor information")
    }

    def "Activity.searching should show target"() {
        when:
        def activity = ComparisonEvent.Activity.searching("line items")

        then:
        activity.message().contains("Searching")
        activity.message().contains("line items")
    }

    def "Activity.saving should show target"() {
        when:
        def activity = ComparisonEvent.Activity.saving("results")

        then:
        activity.icon() == "\uD83D\uDCBE"
        activity.message().contains("Saving")
        activity.message().contains("results")
    }

    def "Activity.processing should show processing"() {
        when:
        def activity = ComparisonEvent.Activity.processing()

        then:
        activity.icon() == "\u2699\uFE0F"
        activity.message().contains("Processing")
    }

    // ==================== Complete TESTS ====================

    def "Complete.of with null results should have zero counts"() {
        given:
        def comparison = new Comparison()
        comparison.setResults(null)

        when:
        def complete = ComparisonEvent.Complete.of(comparison, "session-1")

        then:
        complete.summary().matches() == 0
        complete.summary().discrepancies() == 0
        complete.summary().total() == 0
        complete.comparisonId() == "session-1"
        complete.eventType() == "complete"
    }
}
