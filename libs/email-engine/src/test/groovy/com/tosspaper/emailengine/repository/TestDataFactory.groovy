package com.tosspaper.emailengine.repository

import com.tosspaper.models.domain.EmailAttachment
import com.tosspaper.models.domain.EmailMessage
import com.tosspaper.models.domain.EmailThread
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus

import java.time.OffsetDateTime
import java.time.ZoneOffset

class TestDataFactory {

    /** Default test company ID used across all repository tests */
    static final Long TEST_COMPANY_ID = 1L

    static EmailThread createTestThread(String provider = "mailgun", String providerThreadId = "thread-123@mailgun.org") {
        return EmailThread.builder()
            .subject("Test Subject")
            .provider(provider)
            .providerThreadId(providerThreadId)
            .messageCount(1)
            .build()
    }

    static EmailMessage createTestMessage(String provider = "mailgun", String providerMessageId = "msg-456@mailgun.org") {
        return EmailMessage.builder()
            .provider(provider)
            .providerMessageId(providerMessageId)
            .companyId(TEST_COMPANY_ID)
            .fromAddress("sender@example.com")
            .toAddress("recipient@example.com")
            .subject("Test Message")
            .bodyText("Test body")
            .direction(MessageDirection.INCOMING)
            .status(MessageStatus.RECEIVED)
            .providerTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
            .build()
    }

    static EmailMessage createReplyMessage(UUID threadId, String providerMessageId = "reply-789@mailgun.org") {
        return EmailMessage.builder()
            .threadId(threadId)
            .provider("mailgun")
            .providerMessageId(providerMessageId)
            .companyId(TEST_COMPANY_ID)
            .inReplyTo("msg-456@mailgun.org")
            .fromAddress("replier@example.com")
            .toAddress("original@example.com")
            .cc("cc@example.com")
            .bcc("bcc@example.com")
            .subject("Re: Test Message")
            .bodyText("Reply body text")
            .bodyHtml("<p>Reply body html</p>")
            .headers('{"X-Custom": "value"}')
            .direction(MessageDirection.OUTGOING)
            .status(MessageStatus.RECEIVED)
            .providerTimestamp(OffsetDateTime.now(ZoneOffset.UTC))
            .build()
    }

    static EmailAttachment createTestAttachment(UUID messageId, String fileName, String contentType, Long sizeBytes) {
        return EmailAttachment.builder()
            .messageId(messageId)
            .assignedId("msg-456_${UUID.randomUUID().toString().take(8)}")
            .fileName(fileName)
            .contentType(contentType)
            .sizeBytes(sizeBytes)
            .storageUrl("s3://bucket/${fileName}")
            .localFilePath("/tmp/test/${fileName}")
            .checksum(fileName == "document.pdf" ? "abc123" :
                     fileName == "image.jpg" ? "def456" : "ghi789")
            .status(com.tosspaper.models.domain.AttachmentStatus.pending)
            .build()
    }
}
