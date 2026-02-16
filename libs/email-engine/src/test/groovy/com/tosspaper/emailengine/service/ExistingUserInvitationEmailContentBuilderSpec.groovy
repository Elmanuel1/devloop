package com.tosspaper.emailengine.service

import com.tosspaper.models.config.FrontendUrlProperties
import spock.lang.Specification
import spock.lang.Subject

class ExistingUserInvitationEmailContentBuilderSpec extends Specification {

    FrontendUrlProperties frontendUrlProperties = Mock()

    @Subject
    ExistingUserInvitationEmailContentBuilder builder

    def setup() {
        builder = new ExistingUserInvitationEmailContentBuilder(frontendUrlProperties)
        frontendUrlProperties.getBaseUrl() >> "https://app.tosspaper.com"
    }

    def "should build subject with company name"() {
        when:
        def subject = builder.buildSubject("Acme Corp")

        then:
        subject == "You've been invited to join Acme Corp on TossPaper"
    }

    def "should build body with all details"() {
        when:
        def body = builder.buildBody("user@example.com", "Acme Corp", "Admin", 1L)

        then:
        body.contains("invited to join Acme Corp")
        body.contains("as a Admin")
        body.contains("user@example.com")
        body.contains("already have a TossPaper account")
        body.contains("https://app.tosspaper.com/invitations/")
        body.contains("expire in 24 hours")
    }

    def "should generate invitation URL with encoded company ID and email"() {
        when:
        def body = builder.buildBody("test@example.com", "Test Co", "Viewer", 5L)

        then:
        body.contains("/invitations/")
    }

    def "should include role name in body"() {
        when:
        def body = builder.buildBody("user@example.com", "Company", "Manager", 1L)

        then:
        body.contains("as a Manager")
    }
}
