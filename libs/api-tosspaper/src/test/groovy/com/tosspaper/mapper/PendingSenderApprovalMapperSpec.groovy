package com.tosspaper.mapper

import com.tosspaper.models.domain.PendingApprovalDocument
import com.tosspaper.models.domain.PendingSenderApproval
import spock.lang.Specification

import java.time.OffsetDateTime

class PendingSenderApprovalMapperSpec extends Specification {

    PendingSenderApprovalMapper mapper

    def setup() {
        mapper = new PendingSenderApprovalMapper()
    }

    // ==================== toApiResponse ====================

    def "toApiResponse maps empty list"() {
        when: "mapping empty domain list"
            def result = mapper.toApiResponse([])

        then: "result contains empty data list"
            result != null
            result.data != null
            result.data.isEmpty()
    }

    def "toApiResponse maps single pending sender"() {
        given: "a single pending sender approval"
            def document = createPendingApprovalDocument(
                attachmentId: "att-123",
                filename: "invoice.pdf"
            )
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("vendor@supplier.com")
                .documentsPending(1)
                .domainAccessAllowed(true)
                .attachments([document])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "all fields are mapped correctly"
            result != null
            result.data.size() == 1
            with(result.data[0]) {
                senderIdentifier == "vendor@supplier.com"
                documentsPending == 1
                domainAccessAllowed == true
                attachments.size() == 1
                attachments[0].attachmentId == "att-123"
                attachments[0].filename == "invoice.pdf"
            }
    }

    def "toApiResponse maps multiple pending senders"() {
        given: "multiple pending sender approvals"
            def approval1 = createPendingSenderApproval(
                senderIdentifier: "vendor1@supplier.com",
                documentsPending: 2
            )
            def approval2 = createPendingSenderApproval(
                senderIdentifier: "vendor2@supplier.com",
                documentsPending: 3
            )
            def approval3 = createPendingSenderApproval(
                senderIdentifier: "vendor3@supplier.com",
                documentsPending: 1
            )

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval1, approval2, approval3])

        then: "all approvals are mapped"
            result.data.size() == 3
            result.data[0].senderIdentifier == "vendor1@supplier.com"
            result.data[1].senderIdentifier == "vendor2@supplier.com"
            result.data[2].senderIdentifier == "vendor3@supplier.com"
    }

    def "toApiResponse maps sender with blocked domain"() {
        given: "sender with blocked domain"
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("user@gmail.com")
                .documentsPending(5)
                .domainAccessAllowed(false)
                .attachments([])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "domainAccessAllowed is false"
            result.data[0].domainAccessAllowed == false
    }

    def "toApiResponse maps sender with multiple documents"() {
        given: "sender with multiple pending documents"
            def now = OffsetDateTime.now()
            def documents = [
                createPendingApprovalDocument(attachmentId: "att-1", filename: "invoice1.pdf", dateReceived: now.minusDays(3)),
                createPendingApprovalDocument(attachmentId: "att-2", filename: "invoice2.pdf", dateReceived: now.minusDays(2)),
                createPendingApprovalDocument(attachmentId: "att-3", filename: "po.xlsx", dateReceived: now.minusDays(1))
            ]
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("vendor@supplier.com")
                .documentsPending(3)
                .domainAccessAllowed(true)
                .attachments(documents)
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "all documents are mapped"
            result.data[0].attachments.size() == 3
            result.data[0].documentsPending == 3
            result.data[0].attachments*.filename == ["invoice1.pdf", "invoice2.pdf", "po.xlsx"]
    }

    def "toApiResponse maps sender with no attachments"() {
        given: "sender with zero attachments"
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("vendor@supplier.com")
                .documentsPending(0)
                .domainAccessAllowed(true)
                .attachments([])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "empty attachments list is mapped"
            result.data[0].attachments.isEmpty()
            result.data[0].documentsPending == 0
    }

    def "toApiResponse preserves document order"() {
        given: "documents in specific order"
            def documents = [
                createPendingApprovalDocument(attachmentId: "att-3"),
                createPendingApprovalDocument(attachmentId: "att-1"),
                createPendingApprovalDocument(attachmentId: "att-2")
            ]
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("vendor@supplier.com")
                .documentsPending(3)
                .domainAccessAllowed(true)
                .attachments(documents)
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "order is preserved"
            result.data[0].attachments[0].attachmentId == "att-3"
            result.data[0].attachments[1].attachmentId == "att-1"
            result.data[0].attachments[2].attachmentId == "att-2"
    }

    def "toApiResponse maps document with all fields"() {
        given: "document with all fields populated"
            def dateReceived = OffsetDateTime.now().minusDays(5)
            def document = PendingApprovalDocument.builder()
                .attachmentId("att-999")
                .filename("complex_invoice.pdf")
                .storageKey("companies/1/messages/msg-123/complex_invoice.pdf")
                .messageId("msg-123")
                .dateReceived(dateReceived)
                .build()
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("vendor@supplier.com")
                .documentsPending(1)
                .domainAccessAllowed(true)
                .attachments([document])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "all document fields are mapped"
            with(result.data[0].attachments[0]) {
                attachmentId == "att-999"
                filename == "complex_invoice.pdf"
                storageKey == "companies/1/messages/msg-123/complex_invoice.pdf"
                messageId == "msg-123"
                it.dateReceived == dateReceived
            }
    }

    def "toApiResponse handles domain sender identifier"() {
        given: "sender with domain identifier"
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("supplier.com")
                .documentsPending(10)
                .domainAccessAllowed(true)
                .attachments([])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "domain identifier is preserved"
            result.data[0].senderIdentifier == "supplier.com"
    }

    def "toApiResponse handles special characters in filename"() {
        given: "document with special characters"
            def document = createPendingApprovalDocument(
                filename: "PO #12345 - Süpplier Co., Ltd. (2024).pdf"
            )
            def approval = createPendingSenderApproval(attachments: [document])

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "special characters are preserved"
            result.data[0].attachments[0].filename == "PO #12345 - Süpplier Co., Ltd. (2024).pdf"
    }

    def "toApiResponse handles high document count"() {
        given: "sender with many pending documents"
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("prolific@vendor.com")
                .documentsPending(999)
                .domainAccessAllowed(true)
                .attachments([])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "high count is preserved"
            result.data[0].documentsPending == 999
    }

    def "toApiResponse handles sender with email containing special characters"() {
        given: "sender with special email format"
            def approval = PendingSenderApproval.builder()
                .senderIdentifier("first.last+tag@mail.example.com")
                .documentsPending(2)
                .domainAccessAllowed(true)
                .attachments([])
                .build()

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "email format is preserved"
            result.data[0].senderIdentifier == "first.last+tag@mail.example.com"
    }

    def "toApiResponse creates new instances for each mapping"() {
        given: "a domain list"
            def approval = createPendingSenderApproval()

        when: "mapping twice"
            def result1 = mapper.toApiResponse([approval])
            def result2 = mapper.toApiResponse([approval])

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
            !result1.data[0].is(result2.data[0])
    }

    def "toApiResponse handles mixed domain access statuses"() {
        given: "multiple senders with different domain access"
            def approvals = [
                createPendingSenderApproval(senderIdentifier: "allowed@business.com", domainAccessAllowed: true),
                createPendingSenderApproval(senderIdentifier: "blocked@gmail.com", domainAccessAllowed: false),
                createPendingSenderApproval(senderIdentifier: "allowed@company.com", domainAccessAllowed: true)
            ]

        when: "mapping to API response"
            def result = mapper.toApiResponse(approvals)

        then: "domain access flags are correctly set"
            result.data[0].domainAccessAllowed == true
            result.data[1].domainAccessAllowed == false
            result.data[2].domainAccessAllowed == true
    }

    def "toApiResponse handles documents with various storage keys"() {
        given: "documents with different storage key formats"
            def documents = [
                createPendingApprovalDocument(storageKey: "companies/1/messages/msg-1/file1.pdf"),
                createPendingApprovalDocument(storageKey: "companies/1/messages/msg-2/subfolder/file2.xlsx"),
                createPendingApprovalDocument(storageKey: "temp/uploads/file3.png")
            ]
            def approval = createPendingSenderApproval(attachments: documents)

        when: "mapping to API response"
            def result = mapper.toApiResponse([approval])

        then: "all storage keys are preserved"
            result.data[0].attachments*.storageKey == [
                "companies/1/messages/msg-1/file1.pdf",
                "companies/1/messages/msg-2/subfolder/file2.xlsx",
                "temp/uploads/file3.png"
            ]
    }

    // ==================== Helper Methods ====================

    private PendingSenderApproval createPendingSenderApproval(Map overrides = [:]) {
        def defaults = [
            senderIdentifier: "vendor@supplier.com",
            documentsPending: 3,
            domainAccessAllowed: true,
            attachments: [createPendingApprovalDocument()]
        ]

        def merged = defaults + overrides

        return PendingSenderApproval.builder()
            .senderIdentifier(merged.senderIdentifier)
            .documentsPending(merged.documentsPending)
            .domainAccessAllowed(merged.domainAccessAllowed)
            .attachments(merged.attachments)
            .build()
    }

    private PendingApprovalDocument createPendingApprovalDocument(Map overrides = [:]) {
        def defaults = [
            attachmentId: "att-123",
            filename: "document.pdf",
            storageKey: "companies/1/messages/msg-123/document.pdf",
            messageId: "msg-123",
            dateReceived: OffsetDateTime.now()
        ]

        def merged = defaults + overrides

        return PendingApprovalDocument.builder()
            .attachmentId(merged.attachmentId)
            .filename(merged.filename)
            .storageKey(merged.storageKey)
            .messageId(merged.messageId)
            .dateReceived(merged.dateReceived)
            .build()
    }
}
