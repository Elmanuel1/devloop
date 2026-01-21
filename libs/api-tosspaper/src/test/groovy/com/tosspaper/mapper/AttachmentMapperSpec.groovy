package com.tosspaper.mapper

import com.tosspaper.models.domain.AttachmentStatus
import com.tosspaper.models.domain.EmailAttachment
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class AttachmentMapperSpec extends Specification {

    AttachmentMapper mapper

    def setup() {
        mapper = new AttachmentMapper()
    }

    // ==================== toApiAttachmentList ====================

    def "toApiAttachmentList returns null when input is null"() {
        when: "mapping null list"
            def result = mapper.toApiAttachmentList(null)

        then: "result is null"
            result == null
    }

    def "toApiAttachmentList maps empty list"() {
        when: "mapping empty list"
            def result = mapper.toApiAttachmentList([])

        then: "result contains empty data list"
            result != null
            result.data != null
            result.data.isEmpty()
    }

    def "toApiAttachmentList maps single attachment"() {
        given: "a single attachment"
            def attachment = createEmailAttachment(
                assignedId: "msg-123_att-1",
                fileName: "invoice.pdf",
                fileSize: 1024L
            )

        when: "mapping to API list"
            def result = mapper.toApiAttachmentList([attachment])

        then: "result contains one attachment"
            result != null
            result.data.size() == 1
            with(result.data[0]) {
                id == "msg-123_att-1"
                fileName == "invoice.pdf"
                fileSize == 1024L
            }
    }

    def "toApiAttachmentList maps multiple attachments"() {
        given: "multiple attachments"
            def attachments = [
                createEmailAttachment(assignedId: "msg-1_att-1", fileName: "doc1.pdf"),
                createEmailAttachment(assignedId: "msg-1_att-2", fileName: "doc2.xlsx"),
                createEmailAttachment(assignedId: "msg-1_att-3", fileName: "image.png")
            ]

        when: "mapping to API list"
            def result = mapper.toApiAttachmentList(attachments)

        then: "all attachments are mapped"
            result != null
            result.data.size() == 3
            result.data[0].fileName == "doc1.pdf"
            result.data[1].fileName == "doc2.xlsx"
            result.data[2].fileName == "image.png"
    }

    def "toApiAttachmentList preserves order"() {
        given: "attachments in specific order"
            def attachments = [
                createEmailAttachment(assignedId: "att-3", fileName: "third.pdf"),
                createEmailAttachment(assignedId: "att-1", fileName: "first.pdf"),
                createEmailAttachment(assignedId: "att-2", fileName: "second.pdf")
            ]

        when: "mapping to API list"
            def result = mapper.toApiAttachmentList(attachments)

        then: "order is preserved"
            result.data[0].id == "att-3"
            result.data[1].id == "att-1"
            result.data[2].id == "att-2"
    }

    // ==================== toApiAttachment ====================

    def "toApiAttachment returns null when input is null"() {
        when: "mapping null attachment"
            def result = mapper.toApiAttachment(null)

        then: "result is null"
            result == null
    }

    def "toApiAttachment maps all fields correctly"() {
        given: "a complete email attachment"
            def attachment = EmailAttachment.builder()
                .assignedId("msg-123_att-456")
                .fileName("purchase_order.pdf")
                .sizeBytes(2048L)
                .status(AttachmentStatus.uploaded)
                .contentType("application/pdf")
                .storageUrl("s3://bucket/key/file.pdf")
                .build()

        when: "mapping to API attachment"
            def result = mapper.toApiAttachment(attachment)

        then: "all fields are mapped correctly"
            result != null
            result.id == "msg-123_att-456"
            result.fileName == "purchase_order.pdf"
            result.fileSize == 2048L
            result.status == "uploaded"
            result.contentType == "application/pdf"
            result.storageUrl == "s3://bucket/key/file.pdf"
    }

    @Unroll
    def "toApiAttachment maps status #status correctly"() {
        given: "attachment with specific status"
            def attachment = createEmailAttachment(status: status)

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "status value is correctly mapped"
            result.status == expectedValue

        where:
            status                      || expectedValue
            AttachmentStatus.pending    || "pending"
            AttachmentStatus.processing || "processing"
            AttachmentStatus.uploaded   || "uploaded"
            AttachmentStatus.failed     || "failed"
    }

    @Unroll
    def "toApiAttachment maps content type #contentType"() {
        given: "attachment with specific content type"
            def attachment = createEmailAttachment(contentType: contentType)

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "content type is preserved"
            result.contentType == contentType

        where:
            contentType << [
                "application/pdf",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "image/jpeg",
                "image/png",
                "text/csv",
                "application/zip"
            ]
    }

    def "toApiAttachment handles zero file size"() {
        given: "attachment with zero size"
            def attachment = createEmailAttachment(fileSize: 0L)

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "zero size is preserved"
            result.fileSize == 0L
    }

    def "toApiAttachment handles large file size"() {
        given: "attachment with large size"
            def attachment = createEmailAttachment(fileSize: 104857600L) // 100MB

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "large size is preserved"
            result.fileSize == 104857600L
    }

    def "toApiAttachment handles special characters in filename"() {
        given: "attachment with special characters"
            def attachment = createEmailAttachment(
                fileName: "Invoice #123 - Süpplier Co., Ltd. (2024).pdf"
            )

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "filename with special characters is preserved"
            result.fileName == "Invoice #123 - Süpplier Co., Ltd. (2024).pdf"
    }

    def "toApiAttachment handles long filenames"() {
        given: "attachment with very long filename"
            def longName = "a" * 255 + ".pdf"
            def attachment = createEmailAttachment(fileName: longName)

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "long filename is preserved"
            result.fileName == longName
    }

    def "toApiAttachment returns storage key not full URL"() {
        given: "attachment with storage URL"
            def attachment = createEmailAttachment(
                storageUrl: "companies/1/messages/abc-123/file.pdf"
            )

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "storage URL is returned as-is (it's already a key, not full URL)"
            result.storageUrl == "companies/1/messages/abc-123/file.pdf"
    }

    def "toApiAttachment handles null optional fields"() {
        given: "attachment with minimal fields"
            def attachment = EmailAttachment.builder()
                .assignedId("att-123")
                .fileName("file.pdf")
                .sizeBytes(100L)
                .status(AttachmentStatus.pending)
                .contentType(null)
                .storageUrl(null)
                .build()

        when: "mapping to API"
            def result = mapper.toApiAttachment(attachment)

        then: "null fields are preserved"
            result.id == "att-123"
            result.fileName == "file.pdf"
            result.fileSize == 100L
            result.status == "pending"
            result.contentType == null
            result.storageUrl == null
    }

    def "toApiAttachment creates new instance each time"() {
        given: "an email attachment"
            def attachment = createEmailAttachment()

        when: "mapping twice"
            def result1 = mapper.toApiAttachment(attachment)
            def result2 = mapper.toApiAttachment(attachment)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toApiAttachment handles various file extensions"() {
        given: "attachments with different extensions"
            def attachments = [
                createEmailAttachment(fileName: "document.pdf"),
                createEmailAttachment(fileName: "spreadsheet.xlsx"),
                createEmailAttachment(fileName: "image.jpg"),
                createEmailAttachment(fileName: "archive.zip"),
                createEmailAttachment(fileName: "data.csv"),
                createEmailAttachment(fileName: "noextension")
            ]

        when: "mapping all attachments"
            def results = attachments.collect { mapper.toApiAttachment(it) }

        then: "all filenames are preserved"
            results*.fileName == [
                "document.pdf",
                "spreadsheet.xlsx",
                "image.jpg",
                "archive.zip",
                "data.csv",
                "noextension"
            ]
    }

    def "toApiAttachmentList filters out null attachments gracefully"() {
        given: "list with valid attachments"
            def attachments = [
                createEmailAttachment(assignedId: "att-1"),
                createEmailAttachment(assignedId: "att-2")
            ]

        when: "mapping to API list"
            def result = mapper.toApiAttachmentList(attachments)

        then: "all valid attachments are mapped"
            result.data.size() == 2
    }

    // ==================== Helper Methods ====================

    private EmailAttachment createEmailAttachment(Map overrides = [:]) {
        def defaults = [
            id: UUID.randomUUID(),
            messageId: UUID.randomUUID(),
            assignedId: "msg-123_att-456",
            fileName: "document.pdf",
            contentType: "application/pdf",
            fileSize: 1024L,
            storageUrl: "s3://bucket/key/file.pdf",
            status: AttachmentStatus.uploaded,
            attempts: 1,
            region: "us-east-1",
            endpoint: "s3.amazonaws.com",
            createdAt: OffsetDateTime.now()
        ]

        def merged = defaults + overrides

        return EmailAttachment.builder()
            .id(merged.id)
            .messageId(merged.messageId)
            .assignedId(merged.assignedId)
            .fileName(merged.fileName)
            .contentType(merged.contentType)
            .sizeBytes(merged.fileSize)
            .storageUrl(merged.storageUrl)
            .status(merged.status)
            .attempts(merged.attempts)
            .region(merged.region)
            .endpoint(merged.endpoint)
            .createdAt(merged.createdAt)
            .build()
    }
}
