package com.tosspaper.emailengine.service

import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class NoAttachmentEmailContentBuilderSpec extends Specification {

    @Subject
    NoAttachmentEmailContentBuilder builder = new NoAttachmentEmailContentBuilder()

    def "should build correct subject"() {
        when:
        def subject = builder.buildSubject()

        then:
        subject == "No Document Attached"
    }

    def "should build body with details"() {
        given:
        def receivedAt = OffsetDateTime.parse("2024-01-15T10:30:00Z")

        when:
        def body = builder.buildBody("sender@example.com", "inbox@acme.com", "Acme Corp", receivedAt)

        then:
        body.contains("no document attachment was found")
        body.contains("inbox@acme.com")
        body.contains("January 15, 2024")
    }
}
