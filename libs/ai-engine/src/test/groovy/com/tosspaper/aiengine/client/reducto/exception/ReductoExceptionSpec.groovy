package com.tosspaper.aiengine.client.reducto.exception

import spock.lang.Specification

class ReductoExceptionSpec extends Specification {

    def "ReductoException message-only constructor"() {
        when:
        def ex = new ReductoException("Upload failed")

        then:
        ex.message == "Upload failed"
        ex.cause == null
    }

    def "ReductoException message-cause constructor"() {
        given:
        def cause = new IOException("Network error")

        when:
        def ex = new ReductoException("Upload failed", cause)

        then:
        ex.message == "Upload failed"
        ex.cause == cause
    }

    def "ReductoTaskException message-only constructor"() {
        when:
        def ex = new ReductoTaskException("Task creation failed")

        then:
        ex.message == "Task creation failed"
        ex instanceof ReductoException
    }

    def "ReductoTaskException message-cause constructor"() {
        given:
        def cause = new RuntimeException("API error")

        when:
        def ex = new ReductoTaskException("Task failed", cause)

        then:
        ex.message == "Task failed"
        ex.cause == cause
    }

    def "ReductoUploadException message-only constructor"() {
        when:
        def ex = new ReductoUploadException("Upload timed out")

        then:
        ex.message == "Upload timed out"
        ex instanceof ReductoException
    }

    def "ReductoUploadException message-cause constructor"() {
        given:
        def cause = new IOException("Connection reset")

        when:
        def ex = new ReductoUploadException("Upload failed", cause)

        then:
        ex.message == "Upload failed"
        ex.cause == cause
    }
}
