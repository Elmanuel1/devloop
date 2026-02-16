package com.tosspaper.aiengine.exception

import spock.lang.Specification

class ConformanceFailedExceptionSpec extends Specification {

    def "constructor should set all fields"() {
        when:
            def ex = new ConformanceFailedException(
                "Conformance failed",
                '{"partial": true}',
                0.65,
                ["Missing documentNumber", "Invalid date"],
                3
            )

        then:
            ex.message == "Conformance failed"
            ex.bestAttemptJson == '{"partial": true}'
            ex.bestScore == 0.65
            ex.allIssues == ["Missing documentNumber", "Invalid date"]
            ex.attemptCount == 3
    }

    def "constructor with cause should set all fields including cause"() {
        given:
            def cause = new RuntimeException("AI model timeout")

        when:
            def ex = new ConformanceFailedException(
                "Conformance failed with cause",
                cause,
                '{"incomplete": true}',
                0.45,
                ["Timeout during evaluation"],
                2
            )

        then:
            ex.message == "Conformance failed with cause"
            ex.cause == cause
            ex.bestAttemptJson == '{"incomplete": true}'
            ex.bestScore == 0.45
            ex.allIssues == ["Timeout during evaluation"]
            ex.attemptCount == 2
    }

    def "constructor should handle empty issues list"() {
        when:
            def ex = new ConformanceFailedException(
                "Failed", '{}', 0.0, [], 1
            )

        then:
            ex.allIssues.isEmpty()
            ex.attemptCount == 1
    }

    def "constructor should handle null best attempt json"() {
        when:
            def ex = new ConformanceFailedException(
                "Failed", null, 0.0, [], 0
            )

        then:
            ex.bestAttemptJson == null
    }
}
