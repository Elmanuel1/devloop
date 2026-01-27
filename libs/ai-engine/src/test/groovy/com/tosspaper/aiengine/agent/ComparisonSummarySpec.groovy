package com.tosspaper.aiengine.agent

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for ComparisonSummary record.
 */
class ComparisonSummarySpec extends Specification {

    def "should create summary with counts"() {
        when: "creating summary"
        def summary = new ComparisonSummary(5, 2, 7)

        then: "counts are set"
        summary.matches() == 5
        summary.discrepancies() == 2
        summary.total() == 7
    }

    @Unroll
    def "matchPercentage should return #expected for #matches/#total"() {
        given: "summary"
        def summary = new ComparisonSummary(matches, discrepancies, total)

        expect: "percentage is correct"
        Math.abs(summary.matchPercentage() - expected) < 0.01

        where:
        matches | discrepancies | total | expected
        5       | 0             | 5     | 100.0
        0       | 5             | 5     | 0.0
        3       | 2             | 5     | 60.0
        1       | 1             | 2     | 50.0
        0       | 0             | 0     | 0.0  // edge case: no items
    }

    def "isFullMatch should return true when all match"() {
        given: "all matches"
        def summary = new ComparisonSummary(5, 0, 5)

        expect: "is full match"
        summary.isFullMatch()
    }

    def "isFullMatch should return false with any discrepancy"() {
        given: "one discrepancy"
        def summary = new ComparisonSummary(4, 1, 5)

        expect: "not full match"
        !summary.isFullMatch()
    }

    def "isFullMatch should return false when empty"() {
        given: "empty summary"
        def summary = new ComparisonSummary(0, 0, 0)

        expect: "not full match (no items)"
        !summary.isFullMatch()
    }

    def "toDisplayString should format correctly"() {
        given: "summary"
        def summary = new ComparisonSummary(3, 2, 5)

        expect: "display string is correct"
        summary.toDisplayString() == "3 matches, 2 discrepancies"
    }

    def "toDisplayString should handle zero values"() {
        given: "empty summary"
        def summary = new ComparisonSummary(0, 0, 0)

        expect: "display string handles zeros"
        summary.toDisplayString() == "0 matches, 0 discrepancies"
    }
}
