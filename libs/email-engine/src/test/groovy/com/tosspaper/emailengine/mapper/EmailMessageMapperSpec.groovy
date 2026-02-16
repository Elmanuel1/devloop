package com.tosspaper.emailengine.mapper

import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus
import com.tosspaper.models.jooq.tables.records.EmailMessageRecord
import org.jooq.JSONB
import spock.lang.Specification

import java.time.OffsetDateTime

/**
 * Tests for EmailMessageMapper to ensure correct mapping between
 * JOOQ records and domain models.
 */
class EmailMessageMapperSpec extends Specification {

    // ==================== toDomain Tests ====================

    def "should return null when record is null"() {
        when:
        def result = EmailMessageMapper.toDomain(null)

        then:
        result == null
    }

    def "should map all fields from record to domain"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.threadId = UUID.randomUUID()
        record.provider = "mailgun"
        record.providerMessageId = "<msg@example.com>"
        record.inReplyTo = "<original@example.com>"
        record.fromAddress = "sender@example.com"
        record.toAddress = "recipient@company.com"
        record.cc = "cc@example.com"
        record.bcc = "bcc@example.com"
        record.bodyText = "Email body text"
        record.bodyHtml = "<p>Email body html</p>"
        record.headers = JSONB.jsonb('{"header": "value"}')
        record.direction = "incoming"
        record.status = "received"
        record.providerTimestamp = OffsetDateTime.now()
        record.createdAt = OffsetDateTime.now()
        record.attachmentsCount = 3

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.id == record.id
        domain.threadId == record.threadId
        domain.provider == "mailgun"
        domain.providerMessageId == "<msg@example.com>"
        domain.inReplyTo == "<original@example.com>"
        domain.fromAddress == "sender@example.com"
        domain.toAddress == "recipient@company.com"
        domain.cc == "cc@example.com"
        domain.bcc == "bcc@example.com"
        domain.bodyText == "Email body text"
        domain.bodyHtml == "<p>Email body html</p>"
        domain.headers == '{"header": "value"}'
        domain.direction == MessageDirection.INCOMING
        domain.status == MessageStatus.RECEIVED
        domain.providerTimestamp == record.providerTimestamp
        domain.createdAt == record.createdAt
        domain.attachmentsCount == 3
    }

    def "should handle null headers field"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.headers = null

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.headers == null
    }

    def "should handle null direction"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.direction = null

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.direction == null
    }

    def "should handle null status"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.status = null

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.status == null
    }

    def "should convert direction to uppercase enum"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.direction = "outgoing"

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.direction == MessageDirection.OUTGOING
    }

    def "should convert status to uppercase enum"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.status = "processed"

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.status == MessageStatus.PROCESSED
    }

    def "should default attachmentsCount to 0 when null"() {
        given:
        def record = new EmailMessageRecord()
        record.id = UUID.randomUUID()
        record.fromAddress = "sender@example.com"
        record.attachmentsCount = null

        when:
        def domain = EmailMessageMapper.toDomain(record)

        then:
        domain.attachmentsCount == 0
    }

    // ==================== updateRecord Tests ====================

    def "should do nothing when record is null"() {
        given:
        def domain = EmailMessage.builder().build()

        when:
        EmailMessageMapper.updateRecord(null, domain)

        then:
        noExceptionThrown()
    }

    def "should do nothing when domain is null"() {
        given:
        def record = new EmailMessageRecord()

        when:
        EmailMessageMapper.updateRecord(record, null)

        then:
        noExceptionThrown()
    }

    def "should update all non-null fields from domain to record"() {
        given:
        def record = new EmailMessageRecord()
        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .threadId(UUID.randomUUID())
            .provider("cloudflare")
            .providerMessageId("<msg@example.com>")
            .inReplyTo("<original@example.com>")
            .fromAddress("sender@example.com")
            .toAddress("recipient@company.com")
            .cc("cc@example.com")
            .bcc("bcc@example.com")
            .bodyText("Body text")
            .bodyHtml("<p>Body html</p>")
            .headers('{"key": "value"}')
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .providerTimestamp(OffsetDateTime.now())
            .createdAt(OffsetDateTime.now())
            .attachmentsCount(5)
            .build()

        when:
        EmailMessageMapper.updateRecord(record, domain)

        then:
        record.id == domain.id
        record.threadId == domain.threadId
        record.provider == "cloudflare"
        record.providerMessageId == "<msg@example.com>"
        record.inReplyTo == "<original@example.com>"
        record.fromAddress == "sender@example.com"
        record.toAddress == "recipient@company.com"
        record.cc == "cc@example.com"
        record.bcc == "bcc@example.com"
        record.bodyText == "Body text"
        record.bodyHtml == "<p>Body html</p>"
        record.headers.data() == '{"key": "value"}'
        record.direction == "incoming"
        record.status == "received"
        record.attachmentsCount == 5
    }

    def "should skip null fields when updating record"() {
        given:
        def record = new EmailMessageRecord()
        record.fromAddress = "original@example.com"

        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .fromAddress("new@example.com")
            .toAddress(null)
            .build()

        when:
        EmailMessageMapper.updateRecord(record, domain)

        then:
        record.id == domain.id
        record.fromAddress == "new@example.com"
        record.toAddress == null
    }

    def "should convert direction to lowercase when updating"() {
        given:
        def record = new EmailMessageRecord()
        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .direction(MessageDirection.OUTGOING)
            .build()

        when:
        EmailMessageMapper.updateRecord(record, domain)

        then:
        record.direction == "outgoing"
    }

    def "should convert status to lowercase when updating"() {
        given:
        def record = new EmailMessageRecord()
        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .status(MessageStatus.PROCESSED)
            .build()

        when:
        EmailMessageMapper.updateRecord(record, domain)

        then:
        record.status == "processed"
    }

    def "should not update attachmentsCount when 0"() {
        given:
        def record = new EmailMessageRecord()
        record.attachmentsCount = 5

        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .attachmentsCount(0)
            .build()

        when:
        EmailMessageMapper.updateRecord(record, domain)

        then:
        record.attachmentsCount == 5
    }

    // ==================== toRecord Tests ====================

    def "should return null when domain is null"() {
        when:
        def result = EmailMessageMapper.toRecord(null)

        then:
        result == null
    }

    def "should create new record from domain"() {
        given:
        def domain = EmailMessage.builder()
            .id(UUID.randomUUID())
            .provider("mailgun")
            .fromAddress("sender@example.com")
            .toAddress("recipient@company.com")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .build()

        when:
        def record = EmailMessageMapper.toRecord(domain)

        then:
        record != null
        record.id == domain.id
        record.provider == "mailgun"
        record.fromAddress == "sender@example.com"
        record.toAddress == "recipient@company.com"
        record.direction == "incoming"
        record.status == "received"
    }
}
