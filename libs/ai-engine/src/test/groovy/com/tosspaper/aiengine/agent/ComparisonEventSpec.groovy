package com.tosspaper.aiengine.agent

import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.Result
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for ComparisonEvent sealed interface and its implementations.
 */
class ComparisonEventSpec extends Specification {

    // ==================== ACTIVITY TESTS ====================

    def "Activity.reviewing should create correct activity"() {
        when: "creating reviewing activity"
        def activity = ComparisonEvent.Activity.reviewing("purchase order", "PO-123")

        then: "activity is correct"
        activity.icon() == "📋"
        activity.message() == "Reviewing purchase order PO-123..."
    }

    def "Activity.analyzing should create correct activity"() {
        when: "creating analyzing activity"
        def activity = ComparisonEvent.Activity.analyzing("invoice")

        then: "activity is correct"
        activity.icon() == "📄"
        activity.message() == "Analyzing invoice..."
    }

    def "Activity.comparing should create correct activity"() {
        when: "creating comparing activity"
        def activity = ComparisonEvent.Activity.comparing("vendor information")

        then: "activity is correct"
        activity.icon() == "🔍"
        activity.message() == "Comparing vendor information..."
    }

    def "Activity.searching should create correct activity"() {
        when: "creating searching activity"
        def activity = ComparisonEvent.Activity.searching("documents")

        then: "activity is correct"
        activity.icon() == "🔍"
        activity.message() == "Searching documents..."
    }

    def "Activity.saving should create correct activity"() {
        when: "creating saving activity"
        def activity = ComparisonEvent.Activity.saving("results")

        then: "activity is correct"
        activity.icon() == "💾"
        activity.message() == "Saving results..."
    }

    def "Activity.processing should create correct activity"() {
        when: "creating processing activity"
        def activity = ComparisonEvent.Activity.processing()

        then: "activity is correct"
        activity.icon() == "⚙️"
        activity.message() == "Processing..."
    }

    @Unroll
    def "Activity.reviewing should use correct icon for #documentType"() {
        when: "creating reviewing activity"
        def activity = ComparisonEvent.Activity.reviewing(documentType, "ID-123")

        then: "icon is correct"
        activity.icon() == expectedIcon

        where:
        documentType      | expectedIcon
        "purchase order"  | "📋"
        "PO"              | "📋"
        "invoice"         | "📄"
        "delivery slip"   | "📦"
        "delivery_slip"   | "📦"
        "delivery note"   | "📝"
        "delivery_note"   | "📝"
        "unknown"         | "📄"
    }

    // ==================== THINKING TESTS ====================

    def "Thinking.of should create thinking event"() {
        when: "creating thinking event"
        def thinking = ComparisonEvent.Thinking.of("Analyzing the vendor name...")

        then: "content is set"
        thinking.content() == "Analyzing the vendor name..."
    }

    def "Thinking record should work with constructor"() {
        when: "creating via constructor"
        def thinking = new ComparisonEvent.Thinking("reasoning text")

        then: "content is set"
        thinking.content() == "reasoning text"
    }

    // ==================== FINDING TESTS ====================

    def "Finding.match should create match finding"() {
        when: "creating match finding"
        def finding = ComparisonEvent.Finding.match("Vendor name", "exact match")

        then: "finding is correct"
        finding.icon() == "✓"
        finding.item() == "Vendor name"
        finding.status() == "matched"
        finding.detail() == "exact match"
    }

    def "Finding.partial should create partial finding"() {
        when: "creating partial finding"
        def finding = ComparisonEvent.Finding.partial("Address", "postal code differs")

        then: "finding is correct"
        finding.icon() == "⚠"
        finding.item() == "Address"
        finding.status() == "partial"
        finding.detail() == "postal code differs"
    }

    def "Finding.mismatch should create mismatch finding"() {
        when: "creating mismatch finding"
        def finding = ComparisonEvent.Finding.mismatch("Widget B", "price mismatch (\$50 vs \$55)")

        then: "finding is correct"
        finding.icon() == "✗"
        finding.item() == "Widget B"
        finding.status() == "unmatched"
        finding.detail() == "price mismatch (\$50 vs \$55)"
    }

    // ==================== COMPLETE TESTS ====================

    def "Complete.of should calculate summary from results"() {
        given: "comparison with mixed results"
        def comparison = new Comparison()
        comparison.setDocumentId("doc-123")
        comparison.setPoId("PO-456")

        def matched = new Result()
        matched.setStatus(Result.Status.MATCHED)

        def partial = new Result()
        partial.setStatus(Result.Status.PARTIAL)

        def unmatched = new Result()
        unmatched.setStatus(Result.Status.UNMATCHED)

        comparison.setResults([matched, matched, partial, unmatched])

        when: "creating complete event"
        def complete = ComparisonEvent.Complete.of(comparison)

        then: "summary is correct"
        complete.result() == comparison
        complete.summary().matches() == 2
        complete.summary().discrepancies() == 2
        complete.summary().total() == 4
    }

    def "Complete.of should handle null results"() {
        given: "comparison with null results"
        def comparison = new Comparison()
        comparison.setDocumentId("doc-123")
        comparison.setResults(null)

        when: "creating complete event"
        def complete = ComparisonEvent.Complete.of(comparison)

        then: "summary shows zeros"
        complete.summary().matches() == 0
        complete.summary().discrepancies() == 0
        complete.summary().total() == 0
    }

    def "Complete.of should handle empty results"() {
        given: "comparison with empty results"
        def comparison = new Comparison()
        comparison.setResults([])

        when: "creating complete event"
        def complete = ComparisonEvent.Complete.of(comparison)

        then: "summary shows zeros"
        complete.summary().total() == 0
    }

    // ==================== ERROR TESTS ====================

    def "Error.of should create error with message only"() {
        when: "creating error"
        def error = ComparisonEvent.Error.of("Something went wrong")

        then: "error is set"
        error.message() == "Something went wrong"
        error.code() == null
    }

    def "Error.of should create error with code"() {
        when: "creating error with code"
        def error = ComparisonEvent.Error.of("Not found", "STREAM_NOT_FOUND")

        then: "error and code are set"
        error.message() == "Not found"
        error.code() == "STREAM_NOT_FOUND"
    }

    def "Error constructor should work with message only"() {
        when: "creating via single-arg constructor"
        def error = new ComparisonEvent.Error("error message")

        then: "error is set"
        error.message() == "error message"
        error.code() == null
    }

    // ==================== SEALED INTERFACE TESTS ====================

    def "all event types should implement ComparisonEvent"() {
        expect: "all types implement interface"
        new ComparisonEvent.Activity("📄", "test") instanceof ComparisonEvent
        new ComparisonEvent.Thinking("test") instanceof ComparisonEvent
        new ComparisonEvent.Finding("✓", "item", "status", "detail") instanceof ComparisonEvent
        ComparisonEvent.Complete.of(new Comparison()) instanceof ComparisonEvent
        ComparisonEvent.Error.of("error") instanceof ComparisonEvent
    }
}
