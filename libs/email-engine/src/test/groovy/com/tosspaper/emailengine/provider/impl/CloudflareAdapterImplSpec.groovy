package com.tosspaper.emailengine.provider.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.tosspaper.emailengine.api.dto.WebhookPayload
import com.tosspaper.models.enums.MessageDirection
import com.tosspaper.models.enums.MessageStatus
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Tests for CloudflareAdapterImpl to ensure Cloudflare webhook parsing
 * correctly converts webhook payloads to EmailMessage domain objects.
 */
class CloudflareAdapterImplSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    CloudflareAdapterImpl adapter = new CloudflareAdapterImpl(objectMapper)

    // ==================== Provider Name Tests ====================

    def "should return correct provider name"() {
        expect:
        adapter.getProviderName() == "cloudflare"
    }

    // ==================== Signature Validation Tests ====================

    def "should return true for signature validation (not implemented)"() {
        when:
        def result = adapter.validateSignature("payload", "signature", "secret")

        then:
        result == true
    }

    // ==================== Basic Parsing Tests ====================

    def "should parse basic inbound email without attachments"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg123@example.com>",
                "fromAddress": "John Doe <sender@example.com>",
                "toAddress": "recipient@company.com",
                "subject": "Test Email",
                "bodyText": "This is a test email",
                "bodyHtml": "<p>This is a test email</p>",
                "headers": "Received: from mail.example.com",
                "direction": "INCOMING",
                "status": "RECEIVED",
                "providerTimestamp": "2024-01-15T10:30:00Z"
            },
            "attachments": []
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage != null
        emailMessage.provider == "cloudflare"
        emailMessage.providerMessageId == "<msg123@example.com>"
        emailMessage.fromAddress == "sender@example.com"
        emailMessage.toAddress == "recipient@company.com"
        emailMessage.subject == "Test Email"
        emailMessage.bodyText == "This is a test email"
        emailMessage.bodyHtml == "<p>This is a test email</p>"
        emailMessage.headers == "Received: from mail.example.com"
        emailMessage.direction == MessageDirection.INCOMING
        emailMessage.status == MessageStatus.RECEIVED
        emailMessage.providerTimestamp != null
        emailMessage.attachments.isEmpty()
    }

    def "should clean email address from display name format"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg456@example.com>",
                "fromAddress": "Jane Smith <jane@example.com>",
                "toAddress": "Sales Team <sales@company.com>",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.fromAddress == "jane@example.com"
        emailMessage.toAddress == "sales@company.com"
    }

    def "should parse email with CC and BCC recipients"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg789@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "cc": "cc1@example.com, cc2@example.com",
                "bcc": "bcc@example.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.cc == "cc1@example.com, cc2@example.com"
        emailMessage.bcc == "bcc@example.com"
    }

    def "should parse In-Reply-To header for email replies"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<reply123@example.com>",
                "inReplyTo": "<original@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Re: Original Email",
                "bodyText": "Reply body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.inReplyTo == "<original@example.com>"
    }

    // ==================== Timestamp Parsing Tests ====================

    def "should parse ISO 8601 timestamp correctly"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED",
                "providerTimestamp": "2024-01-15T14:30:00+00:00"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.providerTimestamp != null
        emailMessage.providerTimestamp.year == 2024
        emailMessage.providerTimestamp.monthValue == 1
        emailMessage.providerTimestamp.dayOfMonth == 15
    }

    def "should use current time if timestamp is null"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)
        def beforeParse = OffsetDateTime.now()

        when:
        def emailMessage = adapter.parse(webhookPayload)
        def afterParse = OffsetDateTime.now()

        then:
        emailMessage.providerTimestamp != null
        !emailMessage.providerTimestamp.isBefore(beforeParse.minusSeconds(1))
        !emailMessage.providerTimestamp.isAfter(afterParse.plusSeconds(1))
    }

    def "should use current time if timestamp is invalid"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED",
                "providerTimestamp": "invalid-timestamp"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.providerTimestamp != null
    }

    // ==================== Attachment Parsing Tests ====================

    def "should parse email with no attachments"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments != null
        emailMessage.attachments.isEmpty()
    }

    def "should parse email with single base64-encoded attachment"() {
        given:
        def base64Content = Base64.encoder.encodeToString("PDF Content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "document.pdf",
                    "contentType": "application/pdf",
                    "content": "${base64Content}",
                    "sizeBytes": 11
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.size() == 1
        def attachment = emailMessage.attachments[0]
        attachment.fileName == "document.pdf"
        attachment.contentType == "application/pdf"
        attachment.content == "PDF Content".bytes
        attachment.sizeBytes == 11
    }

    def "should parse email with multiple attachments"() {
        given:
        def pdf1Content = Base64.encoder.encodeToString("PDF1".bytes)
        def pdf2Content = Base64.encoder.encodeToString("PDF2".bytes)
        def pdf3Content = Base64.encoder.encodeToString("PDF3".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "doc1.pdf",
                    "contentType": "application/pdf",
                    "content": "${pdf1Content}",
                    "sizeBytes": 4
                },
                {
                    "fileName": "doc2.pdf",
                    "contentType": "application/pdf",
                    "content": "${pdf2Content}",
                    "sizeBytes": 4
                },
                {
                    "fileName": "doc3.pdf",
                    "contentType": "application/pdf",
                    "content": "${pdf3Content}",
                    "sizeBytes": 4
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.size() == 3
        emailMessage.attachments.collect { it.fileName }.sort() == ["doc1.pdf", "doc2.pdf", "doc3.pdf"]
    }

    def "should generate unique assigned IDs for attachments"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {"fileName": "doc1.pdf", "contentType": "application/pdf", "content": "${content}", "sizeBytes": 7},
                {"fileName": "doc2.pdf", "contentType": "application/pdf", "content": "${content}", "sizeBytes": 7},
                {"fileName": "doc3.pdf", "contentType": "application/pdf", "content": "${content}", "sizeBytes": 7}
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def assignedIds = emailMessage.attachments.collect { it.assignedId }
        assignedIds.every { it.startsWith("cf-") }
        assignedIds.unique().size() == 3
    }

    def "should calculate SHA-256 checksum for attachments"() {
        given:
        def content = Base64.encoder.encodeToString("Test content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "test.pdf",
                    "contentType": "application/pdf",
                    "content": "${content}",
                    "sizeBytes": 12
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.checksum != null
        attachment.checksum.length() == 64 // SHA-256 produces 64 hex characters
    }

    def "should skip attachment with missing fileName"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "contentType": "application/pdf",
                    "content": "${content}",
                    "sizeBytes": 7
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.isEmpty()
    }

    def "should skip attachment with missing contentType"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "doc.pdf",
                    "content": "${content}",
                    "sizeBytes": 7
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.isEmpty()
    }

    def "should skip attachment with missing email addresses for key generation"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "doc.pdf",
                    "contentType": "application/pdf",
                    "content": "${content}",
                    "sizeBytes": 7
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments.isEmpty()
    }

    def "should include metadata in attachment objects"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "doc.pdf",
                    "contentType": "application/pdf",
                    "content": "${content}",
                    "sizeBytes": 7
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.metadata != null
        attachment.metadata["provider-message-id"] == "<msg@example.com>"
        attachment.metadata["from-address"] == "sender@example.com"
        attachment.metadata["to-address"] == "recipient@company.com"
    }

    def "should generate storage key for attachments"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "invoice.pdf",
                    "contentType": "application/pdf",
                    "content": "${content}",
                    "sizeBytes": 7
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.key != null
        // Key format: {cleanToAddress}/{fromAddress}/{assignedId}-{fileName}, then makeUrlSafe
        // makeUrlSafe replaces hyphens with underscores but preserves dots and @ symbols
        attachment.key.contains("recipient@company.com")
        attachment.key.contains("sender@example.com")
        attachment.key.contains("invoice.pdf")
    }

    def "should parse attachment with description and contentId"() {
        given:
        def content = Base64.encoder.encodeToString("content".bytes)
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "image.png",
                    "contentType": "image/png",
                    "content": "${content}",
                    "sizeBytes": 7,
                    "description": "Embedded image",
                    "contentId": "img123@example.com"
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        def attachment = emailMessage.attachments[0]
        attachment.description == "Embedded image"
        attachment.contentId == "img123@example.com"
    }

    def "should handle invalid base64 content gracefully"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": [
                {
                    "fileName": "doc.pdf",
                    "contentType": "application/pdf",
                    "content": "invalid-base64!!!",
                    "sizeBytes": 100
                }
            ]
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        // Attachment with invalid base64 is still included but with null content and null checksum
        emailMessage.attachments.size() == 1
        emailMessage.attachments[0].content == null
        emailMessage.attachments[0].checksum == null
        emailMessage.attachments[0].fileName == "doc.pdf"
    }

    // ==================== Direction Parsing Tests ====================

    def "should parse valid message direction"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "OUTGOING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.direction == MessageDirection.OUTGOING
    }

    def "should default to INCOMING for null direction"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.direction == MessageDirection.INCOMING
    }

    def "should default to INCOMING for invalid direction"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INVALID_DIRECTION",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.direction == MessageDirection.INCOMING
    }

    // ==================== Status Parsing Tests ====================

    def "should parse valid message status"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "PROCESSED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.status == MessageStatus.PROCESSED
    }

    def "should default to RECEIVED for null status"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.status == MessageStatus.RECEIVED
    }

    def "should default to RECEIVED for invalid status"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "INVALID_STATUS"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.status == MessageStatus.RECEIVED
    }

    // ==================== Error Handling Tests ====================

    def "should throw exception for missing emailMessage field"() {
        given:
        def jsonPayload = """
        {
            "attachments": []
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        adapter.parse(webhookPayload)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Missing 'emailMessage' field")
    }

    def "should throw exception for invalid JSON"() {
        given:
        def invalidJson = "{ invalid json }"
        def webhookPayload = WebhookPayload.fromJson(invalidJson)

        when:
        adapter.parse(webhookPayload)

        then:
        thrown(IllegalArgumentException)
    }

    def "should handle null fields gracefully"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "direction": "INCOMING",
                "status": "RECEIVED"
            }
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.subject == null
        emailMessage.bodyText == null
        emailMessage.bodyHtml == null
        emailMessage.headers == null
        emailMessage.cc == null
        emailMessage.bcc == null
        emailMessage.inReplyTo == null
    }

    def "should return empty list for attachments on parse error"() {
        given:
        def jsonPayload = """
        {
            "emailMessage": {
                "provider": "cloudflare",
                "providerMessageId": "<msg@example.com>",
                "fromAddress": "sender@example.com",
                "toAddress": "recipient@company.com",
                "subject": "Subject",
                "bodyText": "Body",
                "direction": "INCOMING",
                "status": "RECEIVED"
            },
            "attachments": "invalid-not-an-array"
        }
        """
        def webhookPayload = WebhookPayload.fromJson(jsonPayload)

        when:
        def emailMessage = adapter.parse(webhookPayload)

        then:
        emailMessage.attachments != null
        emailMessage.attachments.isEmpty()
    }
}
