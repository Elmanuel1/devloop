package com.tosspaper.integrations.common.exception

import spock.lang.Specification

class IntegrationExceptionHierarchySpec extends Specification {

    // ==================== IntegrationException ====================

    def "IntegrationException should store message"() {
        when: "creating with message"
            def ex = new IntegrationException("sync failed")

        then: "message is stored"
            ex.message == "sync failed"
            ex.cause == null
    }

    def "IntegrationException should store message and cause"() {
        given: "a root cause"
            def cause = new RuntimeException("root cause")

        when: "creating with message and cause"
            def ex = new IntegrationException("sync failed", cause)

        then: "both are stored"
            ex.message == "sync failed"
            ex.cause == cause
    }

    // ==================== IntegrationAuthException ====================

    def "IntegrationAuthException should store message only"() {
        when: "creating with message"
            def ex = new IntegrationAuthException("auth failed")

        then: "message stored, errorCode is null"
            ex.message == "auth failed"
            ex.errorCode == null
            ex.cause == null
    }

    def "IntegrationAuthException should store message and errorCode"() {
        when: "creating with message and errorCode"
            def ex = new IntegrationAuthException("auth failed", "TOKEN_EXPIRED")

        then: "both are stored"
            ex.message == "auth failed"
            ex.errorCode == "TOKEN_EXPIRED"
    }

    def "IntegrationAuthException should store message and cause"() {
        given: "a root cause"
            def cause = new RuntimeException("token issue")

        when: "creating with message and cause"
            def ex = new IntegrationAuthException("auth failed", cause)

        then: "stored correctly"
            ex.message == "auth failed"
            ex.cause == cause
            ex.errorCode == null
    }

    def "IntegrationAuthException should store message errorCode and cause"() {
        given: "a root cause"
            def cause = new RuntimeException("token issue")

        when: "creating with all args"
            def ex = new IntegrationAuthException("auth failed", "TOKEN_REVOKED", cause)

        then: "all stored"
            ex.message == "auth failed"
            ex.errorCode == "TOKEN_REVOKED"
            ex.cause == cause
    }

    // ==================== IntegrationConnectionException ====================

    def "IntegrationConnectionException should store message"() {
        when: "creating with message"
            def ex = new IntegrationConnectionException("connection refused")

        then: "message stored"
            ex.message == "connection refused"
            ex.cause == null
    }

    def "IntegrationConnectionException should store message and cause"() {
        given: "a root cause"
            def cause = new java.net.ConnectException("timeout")

        when: "creating with message and cause"
            def ex = new IntegrationConnectionException("connection refused", cause)

        then: "both stored"
            ex.message == "connection refused"
            ex.cause == cause
    }

    // ==================== IntegrationSyncException ====================

    def "IntegrationSyncException with message only defaults to retryable"() {
        when: "creating with message only"
            def ex = new IntegrationSyncException("sync failed")

        then: "retryable defaults to true"
            ex.message == "sync failed"
            ex.retryable == true
    }

    def "IntegrationSyncException with message and retryable false"() {
        when: "creating with non-retryable"
            def ex = new IntegrationSyncException("sync failed", false)

        then: "retryable is false"
            ex.message == "sync failed"
            ex.retryable == false
    }

    def "IntegrationSyncException with message and cause defaults to retryable"() {
        given: "a root cause"
            def cause = new RuntimeException("db error")

        when: "creating with message and cause"
            def ex = new IntegrationSyncException("sync failed", cause)

        then: "retryable defaults to true"
            ex.message == "sync failed"
            ex.cause == cause
            ex.retryable == true
    }

    def "IntegrationSyncException with message cause and retryable false"() {
        given: "a root cause"
            def cause = new RuntimeException("permanent error")

        when: "creating with all args"
            def ex = new IntegrationSyncException("sync failed", cause, false)

        then: "retryable is false"
            ex.message == "sync failed"
            ex.cause == cause
            ex.retryable == false
    }

    // ==================== ProviderVersionConflictException ====================

    def "ProviderVersionConflictException should store message"() {
        when: "creating with message"
            def ex = new ProviderVersionConflictException("stale token")

        then: "message stored"
            ex.message == "stale token"
            ex.cause == null
    }

    def "ProviderVersionConflictException should store message and cause"() {
        given: "a root cause"
            def cause = new RuntimeException("synctoken mismatch")

        when: "creating with message and cause"
            def ex = new ProviderVersionConflictException("stale token", cause)

        then: "both stored"
            ex.message == "stale token"
            ex.cause == cause
    }

    // ==================== Inheritance checks ====================

    def "all exceptions should be subtypes of IntegrationException"() {
        expect: "correct hierarchy"
            IntegrationException.isAssignableFrom(IntegrationAuthException)
            IntegrationException.isAssignableFrom(IntegrationConnectionException)
            IntegrationException.isAssignableFrom(IntegrationSyncException)
            IntegrationException.isAssignableFrom(ProviderVersionConflictException)
            RuntimeException.isAssignableFrom(IntegrationException)
    }
}
