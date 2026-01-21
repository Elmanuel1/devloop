package com.tosspaper.mapper

import com.tosspaper.generated.model.ReceivedMessage
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.paging.Paginated
import com.tosspaper.models.paging.Pagination
import spock.lang.Specification

import java.time.OffsetDateTime

class ReceivedMessageMapperSpec extends Specification {

    ReceivedMessageMapper mapper

    def setup() {
        mapper = new ReceivedMessageMapper()
    }

    // ==================== toApiReceivedMessageList ====================

    def "toApiReceivedMessageList returns null when input is null"() {
        when: "mapping null paginated"
            def result = mapper.toApiReceivedMessageList(null)

        then: "result is null"
            result == null
    }

    def "toApiReceivedMessageList maps empty paginated list"() {
        given: "empty paginated list"
            def pagination = new Pagination(1, 20, 0, 0)
            def paginated = new Paginated<EmailMessage>([], pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "result contains empty data with pagination"
            result != null
            result.data != null
            result.data.isEmpty()
            result.pagination != null
            result.pagination.page == 1
            result.pagination.pageSize == 20
            result.pagination.totalPages == 0
            result.pagination.totalItems == 0
    }

    def "toApiReceivedMessageList maps single message"() {
        given: "paginated list with one message"
            def message = createEmailMessage(
                id: UUID.fromString("00000000-0000-0000-0000-000000000001"),
                fromAddress: "vendor@supplier.com",
                subject: "Invoice #123"
            )
            def pagination = new Pagination(1, 20, 1, 1)
            def paginated = new Paginated<EmailMessage>([message], pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "message is mapped correctly"
            result.data.size() == 1
            result.data[0].id == UUID.fromString("00000000-0000-0000-0000-000000000001")
            result.data[0].from == "vendor@supplier.com"
            result.data[0].subject == "Invoice #123"
    }

    def "toApiReceivedMessageList maps multiple messages"() {
        given: "paginated list with multiple messages"
            def messages = [
                createEmailMessage(fromAddress: "vendor1@supplier.com", subject: "Invoice 1"),
                createEmailMessage(fromAddress: "vendor2@supplier.com", subject: "Invoice 2"),
                createEmailMessage(fromAddress: "vendor3@supplier.com", subject: "Invoice 3")
            ]
            def pagination = new Pagination(1, 20, 1, 3)
            def paginated = new Paginated<EmailMessage>(messages, pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "all messages are mapped"
            result.data.size() == 3
            result.data*.from == ["vendor1@supplier.com", "vendor2@supplier.com", "vendor3@supplier.com"]
    }

    def "toApiReceivedMessageList maps pagination correctly"() {
        given: "paginated list with specific pagination"
            def messages = [createEmailMessage()]
            def pagination = new Pagination(3, 10, 15, 142)
            def paginated = new Paginated<EmailMessage>(messages, pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "pagination is correctly mapped"
            result.pagination.page == 3
            result.pagination.pageSize == 10
            result.pagination.totalPages == 15
            result.pagination.totalItems == 142
    }

    def "toApiReceivedMessageList preserves message order"() {
        given: "messages in specific order"
            def messages = [
                createEmailMessage(subject: "Third"),
                createEmailMessage(subject: "First"),
                createEmailMessage(subject: "Second")
            ]
            def pagination = new Pagination(1, 20, 1, 3)
            def paginated = new Paginated<EmailMessage>(messages, pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "order is preserved"
            result.data*.subject == ["Third", "First", "Second"]
    }

    // ==================== toApiReceivedMessage ====================

    def "toApiReceivedMessage returns null when input is null"() {
        when: "mapping null message"
            def result = mapper.toApiReceivedMessage(null)

        then: "result is null"
            result == null
    }

    def "toApiReceivedMessage maps all fields correctly"() {
        given: "a complete email message"
            def messageId = UUID.randomUUID()
            def timestamp = OffsetDateTime.now()
            def message = EmailMessage.builder()
                .id(messageId)
                .fromAddress("vendor@supplier.com")
                .toAddress("buyer@company.com")
                .subject("Invoice #12345")
                .bodyHtml("<html><body>Invoice content</body></html>")
                .providerTimestamp(timestamp)
                .attachmentsCount(3)
                .build()

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "all fields are mapped correctly"
            result != null
            result.id == messageId
            result.from == "vendor@supplier.com"
            result.to == "buyer@company.com"
            result.subject == "Invoice #12345"
            result.bodyHtml == "<html><body>Invoice content</body></html>"
            result.source == ReceivedMessage.SourceEnum.EMAIL
            result.dateReceived == timestamp
            result.attachmentsCount == 3
    }

    def "toApiReceivedMessage always sets source to EMAIL"() {
        given: "an email message"
            def message = createEmailMessage()

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "source is always EMAIL"
            result.source == ReceivedMessage.SourceEnum.EMAIL
    }

    def "toApiReceivedMessage handles zero attachments"() {
        given: "message with no attachments"
            def message = createEmailMessage(attachmentsCount: 0)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "attachments count is zero"
            result.attachmentsCount == 0
    }

    def "toApiReceivedMessage handles many attachments"() {
        given: "message with many attachments"
            def message = createEmailMessage(attachmentsCount: 25)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "high attachment count is preserved"
            result.attachmentsCount == 25
    }

    def "toApiReceivedMessage handles null optional fields"() {
        given: "message with minimal fields"
            def message = EmailMessage.builder()
                .id(UUID.randomUUID())
                .fromAddress("sender@example.com")
                .toAddress("recipient@example.com")
                .subject(null)
                .bodyHtml(null)
                .providerTimestamp(OffsetDateTime.now())
                .attachmentsCount(0)
                .build()

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "null fields are preserved"
            result.id != null
            result.from == "sender@example.com"
            result.to == "recipient@example.com"
            result.subject == null
            result.bodyHtml == null
            result.dateReceived != null
    }

    def "toApiReceivedMessage handles empty subject"() {
        given: "message with empty subject"
            def message = createEmailMessage(subject: "")

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "empty subject is preserved"
            result.subject == ""
    }

    def "toApiReceivedMessage handles empty body HTML"() {
        given: "message with empty body"
            def message = createEmailMessage(bodyHtml: "")

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "empty body is preserved"
            result.bodyHtml == ""
    }

    def "toApiReceivedMessage handles complex HTML content"() {
        given: "message with complex HTML"
            def complexHtml = """
                <html>
                    <head><style>body { font-family: Arial; }</style></head>
                    <body>
                        <h1>Invoice</h1>
                        <table><tr><td>Item</td><td>Price</td></tr></table>
                        <script>alert('test');</script>
                    </body>
                </html>
            """.trim()
            def message = createEmailMessage(bodyHtml: complexHtml)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "complex HTML is preserved"
            result.bodyHtml == complexHtml
    }

    def "toApiReceivedMessage handles multiple recipients in to address"() {
        given: "message with multiple to addresses"
            def message = createEmailMessage(toAddress: "user1@example.com,user2@example.com,user3@example.com")

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "comma-separated addresses are preserved"
            result.to == "user1@example.com,user2@example.com,user3@example.com"
    }

    def "toApiReceivedMessage handles special characters in email addresses"() {
        given: "message with special characters"
            def message = createEmailMessage(
                fromAddress: "first.last+tag@example.co.uk",
                toAddress: "user_name@mail.example.com"
            )

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "special characters are preserved"
            result.from == "first.last+tag@example.co.uk"
            result.to == "user_name@mail.example.com"
    }

    def "toApiReceivedMessage handles special characters in subject"() {
        given: "message with special characters in subject"
            def message = createEmailMessage(
                subject: "RE: Invoice #12345 - Süpplier Co., Ltd. (50% discount!)"
            )

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "special characters are preserved"
            result.subject == "RE: Invoice #12345 - Süpplier Co., Ltd. (50% discount!)"
    }

    def "toApiReceivedMessage handles long subject lines"() {
        given: "message with very long subject"
            def longSubject = "a" * 500
            def message = createEmailMessage(subject: longSubject)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "long subject is preserved"
            result.subject == longSubject
    }

    def "toApiReceivedMessage handles timestamp precision"() {
        given: "message with precise timestamp"
            def timestamp = OffsetDateTime.parse("2024-01-15T14:30:45.123456789Z")
            def message = createEmailMessage(providerTimestamp: timestamp)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessage(message)

        then: "timestamp precision is preserved"
            result.dateReceived == timestamp
    }

    def "toApiReceivedMessage creates new instance each time"() {
        given: "an email message"
            def message = createEmailMessage()

        when: "mapping twice"
            def result1 = mapper.toApiReceivedMessage(message)
            def result2 = mapper.toApiReceivedMessage(message)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toApiReceivedMessageList handles first page"() {
        given: "first page of results"
            def messages = [createEmailMessage()]
            def pagination = new Pagination(1, 20, 5, 95)
            def paginated = new Paginated<EmailMessage>(messages, pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "pagination indicates first page"
            result.pagination.page == 1
    }

    def "toApiReceivedMessageList handles last page"() {
        given: "last page of results"
            def messages = [createEmailMessage()]
            def pagination = new Pagination(5, 20, 5, 95)
            def paginated = new Paginated<EmailMessage>(messages, pagination)

        when: "mapping to API"
            def result = mapper.toApiReceivedMessageList(paginated)

        then: "pagination indicates last page"
            result.pagination.page == 5
            result.pagination.totalPages == 5
    }

    // ==================== Helper Methods ====================

    private EmailMessage createEmailMessage(Map overrides = [:]) {
        def defaults = [
            id: UUID.randomUUID(),
            threadId: UUID.randomUUID(),
            companyId: 1L,
            provider: "postmark",
            providerMessageId: "msg-123",
            fromAddress: "vendor@supplier.com",
            toAddress: "buyer@company.com",
            subject: "Test Email Subject",
            bodyHtml: "<html><body>Test content</body></html>",
            providerTimestamp: OffsetDateTime.now(),
            attachmentsCount: 0
        ]

        def merged = defaults + overrides

        return EmailMessage.builder()
            .id(merged.id)
            .threadId(merged.threadId)
            .companyId(merged.companyId)
            .provider(merged.provider)
            .providerMessageId(merged.providerMessageId)
            .fromAddress(merged.fromAddress)
            .toAddress(merged.toAddress)
            .subject(merged.subject)
            .bodyHtml(merged.bodyHtml)
            .providerTimestamp(merged.providerTimestamp)
            .attachmentsCount(merged.attachmentsCount)
            .build()
    }
}
