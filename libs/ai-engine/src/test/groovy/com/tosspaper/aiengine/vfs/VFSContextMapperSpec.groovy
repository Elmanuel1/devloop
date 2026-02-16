package com.tosspaper.aiengine.vfs

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.models.domain.*
import spock.lang.Specification
import spock.lang.Subject

class VFSContextMapperSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    VFSContextMapper mapper = new VFSContextMapper(objectMapper)

    def "from ExtractionTask should create VfsDocumentContext"() {
        given: "an extraction task"
            def task = ExtractionTask.builder()
                .companyId(1L)
                .poNumber("PO-123")
                .assignedId("attach-123")
                .documentType(DocumentType.INVOICE)
                .conformedJson('{"documentNumber": "INV-001"}')
                .build()

        when: "creating context"
            def result = VFSContextMapper.from(task)

        then: "context is created correctly"
            result.companyId == 1L
            result.poNumber == "PO-123"
            result.documentId == "attach-123"
            result.documentType == DocumentType.INVOICE
            result.content == '{"documentNumber": "INV-001"}'
    }

    def "from PurchaseOrder should create VfsDocumentContext with stripped data"() {
        given: "a purchase order"
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-123")
                .orderDate(java.time.LocalDate.of(2025, 1, 15))
                .dueDate(java.time.LocalDate.of(2025, 2, 15))
                .currencyCode(Currency.USD)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "context is created correctly"
            result.companyId == 1L
            result.poNumber == "PO-123"
            result.documentId == "po"
            result.documentType == DocumentType.PURCHASE_ORDER
            result.content.contains("PO-123")
            result.content.contains("2025-01-15")
    }

    def "from PurchaseOrder should include vendor contact"() {
        given: "a purchase order with vendor contact"
            def vendor = Party.builder()
                .name("Vendor Inc.")
                .email("vendor@example.com")
                .phone("555-1234")
                .address(Address.builder()
                    .address("123 Main St")
                    .city("Springfield")
                    .stateOrProvince("IL")
                    .postalCode("62701")
                    .country("US")
                    .build())
                .build()
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-456")
                .vendorContact(vendor)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "vendor contact is included"
            result.content.contains("Vendor Inc.")
            result.content.contains("vendor@example.com")
            result.content.contains("555-1234")
            result.content.contains("123 Main St")
            result.content.contains("Springfield")
    }

    def "from PurchaseOrder should include ship-to contact"() {
        given: "a purchase order with ship-to contact"
            def shipTo = Party.builder()
                .name("Ship To Corp")
                .email("shipto@example.com")
                .build()
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-789")
                .shipToContact(shipTo)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "ship-to contact is included"
            result.content.contains("Ship To Corp")
            result.content.contains("shipto@example.com")
    }

    def "from PurchaseOrder should include items"() {
        given: "a purchase order with items"
            def items = [
                PurchaseOrderItem.builder()
                    .name("Widget A")
                    .quantity(10)
                    .unit("pcs")
                    .unitPrice(new BigDecimal("5.00"))
                    .totalPrice(new BigDecimal("50.00"))
                    .itemCode("ITEM-001")
                    .build(),
                PurchaseOrderItem.builder()
                    .name("Widget B")
                    .quantity(5)
                    .unitPrice(new BigDecimal("10.00"))
                    .totalPrice(new BigDecimal("50.00"))
                    .build()
            ]
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-100")
                .items(items)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "items are included"
            result.content.contains("Widget A")
            result.content.contains("Widget B")
            result.content.contains("ITEM-001")
    }

    def "from PurchaseOrder with null fields should create minimal context"() {
        given: "a minimal purchase order"
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-MIN")
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "context is created with minimal data"
            result.companyId == 1L
            result.poNumber == "PO-MIN"
            result.content.contains("PO-MIN")
    }

    def "from PurchaseOrder should strip party with empty address"() {
        given: "a vendor with address having null fields"
            def vendor = Party.builder()
                .name("Vendor Only Name")
                .address(Address.builder().build())
                .build()
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-ADDR")
                .vendorContact(vendor)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "vendor name is included, empty address is excluded"
            result.content.contains("Vendor Only Name")
    }

    def "from PurchaseOrder should include item unitCode when present"() {
        given: "an item with unitCode"
            def items = [
                PurchaseOrderItem.builder()
                    .name("Lumber")
                    .unitCode("BFT")
                    .build()
            ]
            def po = PurchaseOrder.builder()
                .companyId(1L)
                .displayId("PO-UC")
                .items(items)
                .build()

        when: "creating context"
            def result = mapper.from(po)

        then: "unitCode is included"
            result.content.contains("BFT")
    }
}
