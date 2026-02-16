package com.tosspaper.aiengine.client.common.exception

import spock.lang.Specification

class CommonExceptionSpec extends Specification {

    def "StartTaskException message-only constructor"() {
        when:
        def ex = new StartTaskException("Invalid schema")

        then:
        ex.message == "Invalid schema"
        ex.cause == null
    }

    def "StartTaskException message-cause constructor"() {
        given:
        def cause = new IllegalArgumentException("Bad field")

        when:
        def ex = new StartTaskException("Validation failed", cause)

        then:
        ex.message == "Validation failed"
        ex.cause == cause
    }

    def "TaskNotFoundException message-only constructor"() {
        when:
        def ex = new TaskNotFoundException("Task abc-123 not found")

        then:
        ex.message == "Task abc-123 not found"
        ex.cause == null
    }

    def "TaskNotFoundException message-cause constructor"() {
        given:
        def cause = new RuntimeException("DB error")

        when:
        def ex = new TaskNotFoundException("Task not found", cause)

        then:
        ex.message == "Task not found"
        ex.cause == cause
    }
}
