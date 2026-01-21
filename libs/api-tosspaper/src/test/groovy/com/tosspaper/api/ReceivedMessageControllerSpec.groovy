package com.tosspaper.api

import com.tosspaper.company.CompanyService
import com.tosspaper.generated.model.AttachmentList
import com.tosspaper.generated.model.ReceivedMessageList
import com.tosspaper.generated.model.Pagination as ApiPagination
import com.tosspaper.generated.model.Attachment
import com.tosspaper.generated.model.ReceivedMessage
import com.tosspaper.mapper.AttachmentMapper
import com.tosspaper.mapper.ReceivedMessageMapper
import com.tosspaper.generated.model.Company
import com.tosspaper.models.paging.Paginated
import com.tosspaper.models.paging.Pagination
import com.tosspaper.models.query.ReceivedMessageQuery
import com.tosspaper.models.service.ReceivedMessageService
import org.springframework.http.HttpStatus
import spock.lang.Specification

import java.time.OffsetDateTime

class ReceivedMessageControllerSpec extends Specification {

    ReceivedMessageService receivedMessageService
    ReceivedMessageMapper receivedMessageMapper
    AttachmentMapper attachmentMapper
    CompanyService companyService
    ReceivedMessageController controller

    def setup() {
        receivedMessageService = Mock()
        receivedMessageMapper = Mock()
        attachmentMapper = Mock()
        companyService = Mock()
        controller = new ReceivedMessageController(receivedMessageService, receivedMessageMapper, attachmentMapper, companyService)
    }

    // ==================== listReceivedMessages ====================

    def "listReceivedMessages returns OK with message list"() {
        given: "valid context and query parameters"
            def xContextId = "123"
            def page = 1
            def pageSize = 20
            def status = "pending"
            def search = "invoice"
            def createdDateFrom = OffsetDateTime.now().minusDays(7)
            def createdDateTo = OffsetDateTime.now()
            def fromEmail = "sender@example.com"

            def company = new Company().assignedEmail("inbox@company.com")
            def serviceResult = new Paginated([], new Pagination(1, 20, 1, 0))
            def messageList = new ReceivedMessageList()
            messageList.setData([createReceivedMessage("msg-1"), createReceivedMessage("msg-2")])
            messageList.setPagination(new ApiPagination())

        when: "calling listReceivedMessages"
            def response = controller.listReceivedMessages(xContextId, page, pageSize, status, search, createdDateFrom, createdDateTo, fromEmail)

        then: "company service returns assigned email"
            1 * companyService.getCompanyById(123L) >> company

        and: "service is called with correct query"
            1 * receivedMessageService.listReceivedMessages(_ as ReceivedMessageQuery) >> { ReceivedMessageQuery q ->
                assert q.page == page
                assert q.pageSize == pageSize
                assert q.status == status
                assert q.search == search
                assert q.createdDateFrom == createdDateFrom
                assert q.createdDateTo == createdDateTo
                assert q.assignedEmail == "inbox@company.com"
                assert q.fromEmail == fromEmail
                return serviceResult
            }

        and: "mapper converts result"
            1 * receivedMessageMapper.toApiReceivedMessageList(serviceResult) >> messageList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains messages"
            with(response.body) {
                data.size() == 2
            }
    }

    def "listReceivedMessages uses default page and pageSize when null"() {
        given: "context with null pagination parameters"
            def xContextId = "456"
            def company = new Company().assignedEmail("inbox@company.com")
            def serviceResult = new Paginated([], new Pagination(1, 20, 1, 0))
            def messageList = new ReceivedMessageList()
            messageList.setData([])
            messageList.setPagination(new ApiPagination())

        when: "calling listReceivedMessages with null page/pageSize"
            def response = controller.listReceivedMessages(xContextId, null, null, null, null, null, null, null)

        then: "company service returns assigned email"
            1 * companyService.getCompanyById(456L) >> company

        and: "service is called with default page=1 and pageSize=20"
            1 * receivedMessageService.listReceivedMessages(_ as ReceivedMessageQuery) >> { ReceivedMessageQuery q ->
                assert q.page == 1
                assert q.pageSize == 20
                return serviceResult
            }

        and: "mapper converts result"
            1 * receivedMessageMapper.toApiReceivedMessageList(serviceResult) >> messageList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== getAttachments ====================

    def "getAttachments returns OK with attachment list"() {
        given: "valid message ID"
            def messageId = UUID.randomUUID()
            def attachments = [createDomainAttachment("att-1"), createDomainAttachment("att-2")]
            def attachmentList = new AttachmentList()
            attachmentList.setData([createAttachment("att-1"), createAttachment("att-2")])

        when: "calling getAttachments"
            def response = controller.getAttachments(messageId)

        then: "service returns attachments"
            1 * receivedMessageService.getAttachmentsByMessageId(messageId) >> attachments

        and: "mapper converts result"
            1 * attachmentMapper.toApiAttachmentList(attachments) >> attachmentList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains attachments"
            with(response.body) {
                data.size() == 2
            }
    }

    def "getAttachments returns empty list when no attachments found"() {
        given: "message ID with no attachments"
            def messageId = UUID.randomUUID()
            def attachmentList = new AttachmentList()
            attachmentList.setData([])

        when: "calling getAttachments"
            def response = controller.getAttachments(messageId)

        then: "service returns empty list"
            1 * receivedMessageService.getAttachmentsByMessageId(messageId) >> []

        and: "mapper converts empty result"
            1 * attachmentMapper.toApiAttachmentList([]) >> attachmentList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response contains empty list"
            response.body.data.isEmpty()
    }

    // ==================== Helper Methods ====================

    private static ReceivedMessage createReceivedMessage(String id) {
        def message = new ReceivedMessage()
        message.id = UUID.randomUUID()
        return message
    }

    private static Attachment createAttachment(String id) {
        def attachment = new Attachment()
        attachment.id = id
        return attachment
    }

    private static Object createDomainAttachment(String id) {
        return [id: id]
    }
}
