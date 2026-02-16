package com.tosspaper.emailengine.service

import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class DocumentReceiptEmailContentBuilderSpec extends Specification {

    @Subject
    DocumentReceiptEmailContentBuilder builder = new DocumentReceiptEmailContentBuilder()

    def "should build subject with tracking ID"() {
        when:
        def subject = builder.buildSubject("att-123")

        then:
        subject == "Document Received - att-123"
    }

    def "should build body with all details"() {
        given:
        def receivedAt = OffsetDateTime.parse("2024-01-15T10:30:00Z")

        when:
        def body = builder.buildBody("sender@example.com", "att-456", "invoice.pdf", "Acme Corp", receivedAt)

        then:
        body.contains("successfully received")
        body.contains("invoice.pdf")
        body.contains("att-456")
        body.contains("Acme Corp")
        body.contains("January 15, 2024")
    }

    def "should include company name in body"() {
        given:
        def receivedAt = OffsetDateTime.now()

        when:
        def body = builder.buildBody("sender@example.com", "att-789", "doc.pdf", "Test Company", receivedAt)

        then:
        body.contains("Test Company")
    }
}
