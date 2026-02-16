package com.tosspaper.emailengine.service

import com.mailgun.api.v3.MailgunMessagesApi
import com.mailgun.model.message.Message
import com.mailgun.model.message.MessageResponse
import com.tosspaper.models.config.MailgunProperties
import com.tosspaper.models.service.CompanyLookupService
import feign.FeignException
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Tests for SenderApprovalNotificationServiceImpl to ensure approval
 * notifications are sent correctly to company owners.
 */
class SenderApprovalNotificationServiceImplSpec extends Specification {

    MailgunMessagesApi mailgunMessagesApi = Mock()
    MailgunProperties mailgunProperties = Mock()
    SenderApprovalEmailContentBuilder emailContentBuilder = Mock()
    CompanyLookupService companyLookupService = Mock()

    @Subject
    SenderApprovalNotificationServiceImpl service

    def setup() {
        service = new SenderApprovalNotificationServiceImpl(
            mailgunMessagesApi,
            mailgunProperties,
            emailContentBuilder,
            companyLookupService
        )

        mailgunProperties.getDomain() >> "mg.example.com"
        mailgunProperties.getFromEmail() >> "noreply@example.com"
    }

    def "should send pending sender approval notification successfully"() {
        given:
        def senderEmail = "new@vendor.com"
        def companyId = 1L
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(
            companyId,
            "inbox@acme.com",
            "owner@acme.com",
            "Acme Corp"
        )

        companyLookupService.getCompanyById(companyId) >> companyInfo
        emailContentBuilder.buildSubject(senderEmail) >> "New Sender Pending Approval - new@vendor.com"
        emailContentBuilder.buildBody(senderEmail, "Acme Corp", _) >> "Email body"

        def messageResponse = GroovyMock(MessageResponse)
        messageResponse.getMessage() >> "mailgun-msg-id-123"

        when:
        service.sendPendingSenderApprovalNotification(senderEmail, companyId)

        then:
        1 * mailgunMessagesApi.sendMessage("mg.example.com", { Message msg ->
            msg.from == "noreply@example.com" &&
            msg.to.contains("owner@acme.com") &&
            msg.subject == "New Sender Pending Approval - new@vendor.com" &&
            msg.text == "Email body"
        }) >> messageResponse
    }

    def "should not send notification if owner email is null"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", null, "Company")
        companyLookupService.getCompanyById(1L) >> companyInfo

        when:
        service.sendPendingSenderApprovalNotification("sender@example.com", 1L)

        then:
        0 * mailgunMessagesApi.sendMessage(_, _)
    }

    def "should not send notification if owner email is blank"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "  ", "Company")
        companyLookupService.getCompanyById(1L) >> companyInfo

        when:
        service.sendPendingSenderApprovalNotification("sender@example.com", 1L)

        then:
        0 * mailgunMessagesApi.sendMessage(_, _)
    }

    def "should handle mailgun authentication failure gracefully"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@company.com", "Company")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailContentBuilder.buildSubject(_) >> "Subject"
        emailContentBuilder.buildBody(_, _, _) >> "Body"

        mailgunMessagesApi.sendMessage(_, _) >> { throw Mock(FeignException.Unauthorized) }

        when:
        service.sendPendingSenderApprovalNotification("sender@example.com", 1L)

        then:
        noExceptionThrown()
    }

    def "should handle generic error gracefully"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@company.com", "Company")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailContentBuilder.buildSubject(_) >> "Subject"
        emailContentBuilder.buildBody(_, _, _) >> "Body"

        mailgunMessagesApi.sendMessage(_, _) >> { throw new RuntimeException("Test error") }

        when:
        service.sendPendingSenderApprovalNotification("sender@example.com", 1L)

        then:
        noExceptionThrown()
    }

    def "should pass current timestamp to email content builder"() {
        given:
        def companyInfo = new CompanyLookupService.CompanyBasicInfo(1L, "inbox@company.com", "owner@company.com", "Company")
        companyLookupService.getCompanyById(1L) >> companyInfo
        emailContentBuilder.buildSubject(_) >> "Subject"

        def messageResponse = GroovyMock(MessageResponse)
        mailgunMessagesApi.sendMessage(_, _) >> messageResponse

        OffsetDateTime capturedTimestamp = null

        when:
        service.sendPendingSenderApprovalNotification("sender@example.com", 1L)

        then:
        1 * emailContentBuilder.buildBody(_, _, _) >> { args ->
            capturedTimestamp = args[2] as OffsetDateTime
            "Body"
        }

        and:
        capturedTimestamp != null
        capturedTimestamp.isBefore(OffsetDateTime.now().plusSeconds(1))
        capturedTimestamp.isAfter(OffsetDateTime.now().minusSeconds(1))
    }
}
