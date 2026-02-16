package com.tosspaper.emailengine.service

import com.tosspaper.models.config.FrontendUrlProperties
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class SenderApprovalEmailContentBuilderSpec extends Specification {

    FrontendUrlProperties frontendUrlProperties = Mock()

    @Subject
    SenderApprovalEmailContentBuilder builder

    def setup() {
        builder = new SenderApprovalEmailContentBuilder(frontendUrlProperties)
        frontendUrlProperties.getBaseUrl() >> "https://app.tosspaper.com"
    }

    def "should build subject with sender email"() {
        when:
        def subject = builder.buildSubject("new@vendor.com")

        then:
        subject == "New Sender Pending Approval - new@vendor.com"
    }

    def "should build body with all details"() {
        given:
        def receivedAt = OffsetDateTime.parse("2024-01-15T10:30:00Z")

        when:
        def body = builder.buildBody("new@vendor.com", "Acme Corp", receivedAt)

        then:
        body.contains("new email sender requires your approval")
        body.contains("new@vendor.com")
        body.contains("January 15, 2024")
        body.contains("https://app.tosspaper.com/dashboard/organization?tab=sender-approvals")
    }

    def "should handle null timestamp"() {
        when:
        def body = builder.buildBody("sender@example.com", "Company", null)

        then:
        body.contains("Just now")
    }

    def "should include review URL in body"() {
        when:
        def body = builder.buildBody("sender@example.com", "Company", OffsetDateTime.now())

        then:
        body.contains("/dashboard/organization?tab=sender-approvals")
    }
}
