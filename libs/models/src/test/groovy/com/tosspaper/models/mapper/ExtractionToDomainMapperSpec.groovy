package com.tosspaper.models.mapper

import com.tosspaper.models.domain.*
import com.tosspaper.models.extraction.dto.Address
import com.tosspaper.models.extraction.dto.Charge
import com.tosspaper.models.extraction.dto.DeliveryAcknowledgement
import com.tosspaper.models.extraction.dto.DeliveryTransaction
import com.tosspaper.models.extraction.dto.Extraction
import com.tosspaper.models.extraction.dto.InvoiceDetails
import com.tosspaper.models.extraction.dto.Party
import com.tosspaper.models.extraction.dto.ShipmentDetails
import spock.lang.Specification
import spock.lang.Subject

/**
 * Tests for ExtractionToDomainMapper.
 * Verifies mapping from Extraction DTOs to domain models.
 */
class ExtractionToDomainMapperSpec extends Specification {

    @Subject
    ExtractionToDomainMapper mapper = new ExtractionToDomainMapper()

    def "toInvoice should map extraction to Invoice domain model"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.documentNumber == "INV-12345"
        invoice.documentDate.toString() == "2024-01-15"
        invoice.poNumber == "PO-999"
        invoice.jobNumber == "JOB-123"
        invoice.companyId == 100L
        invoice.projectId == "200"
        invoice.assignedId == "assigned-123"
        invoice.sellerInfo != null
        invoice.sellerInfo.name == "Acme Corp"
        invoice.buyerInfo != null
        invoice.buyerInfo.name == "Buyer Inc"
        invoice.billToInfo != null
        invoice.billToInfo.name == "BillTo LLC"
        invoice.lineItems.size() == 2
        invoice.invoiceDetails != null
        invoice.invoiceDetails.currencyCode == "USD"
    }

    def "toDeliverySlip should map extraction to DeliverySlip domain model"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.DELIVERY_SLIP)
        def documentApproval = createDocumentApproval()

        when:
        def deliverySlip = mapper.toDeliverySlip(extraction, documentApproval)

        then:
        deliverySlip.documentNumber == "INV-12345"
        deliverySlip.documentDate.toString() == "2024-01-15"
        deliverySlip.poNumber == "PO-999"
        deliverySlip.jobNumber == "JOB-123"
        deliverySlip.companyId == 100L
        deliverySlip.projectId == "200"
        deliverySlip.assignedId == "assigned-123"
        deliverySlip.sellerInfo != null
        deliverySlip.buyerInfo != null
        deliverySlip.billToInfo != null
        deliverySlip.lineItems.size() == 2
        deliverySlip.shipmentDetails != null
        deliverySlip.shipmentDetails.warehouseLocation == "Warehouse A"
        deliverySlip.deliveryAcknowledgement != null
        deliverySlip.deliveryAcknowledgement.recipientName == "John Doe"
    }

    def "toDeliveryNote should map extraction to DeliveryNote domain model"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.DELIVERY_NOTE)
        def documentApproval = createDocumentApproval()

        when:
        def deliveryNote = mapper.toDeliveryNote(extraction, documentApproval)

        then:
        deliveryNote.documentNumber == "INV-12345"
        deliveryNote.documentDate.toString() == "2024-01-15"
        deliveryNote.poNumber == "PO-999"
        deliveryNote.jobNumber == "JOB-123"
        deliveryNote.companyId == 100L
        deliveryNote.projectId == "200"
        deliveryNote.assignedId == "assigned-123"
        deliveryNote.sellerInfo != null
        deliveryNote.buyerInfo != null
        deliveryNote.billToInfo != null
        deliveryNote.lineItems.size() == 2
        deliveryNote.shipmentDetails != null
        deliveryNote.shipmentDetails.warehouseLocation == "Warehouse A"
        deliveryNote.deliveryAcknowledgement != null
        deliveryNote.deliveryAcknowledgement.recipientName == "John Doe"
    }

    def "toDomainModel should route to correct mapper based on document type"() {
        given:
        def documentApproval = createDocumentApproval()

        when: "document type is INVOICE"
        def extraction1 = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def result1 = mapper.toDomainModel(extraction1, documentApproval)

        then:
        result1 instanceof Invoice

        when: "document type is DELIVERY_SLIP"
        def extraction2 = createSampleExtraction(Extraction.DocumentType.DELIVERY_SLIP)
        def result2 = mapper.toDomainModel(extraction2, documentApproval)

        then:
        result2 instanceof DeliverySlip

        when: "document type is DELIVERY_NOTE"
        def extraction3 = createSampleExtraction(Extraction.DocumentType.DELIVERY_NOTE)
        def result3 = mapper.toDomainModel(extraction3, documentApproval)

        then:
        result3 instanceof DeliveryNote
    }

    def "toDomainModel should throw exception for unsupported document type"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.UNKNOWN)
        def documentApproval = createDocumentApproval()

        when:
        mapper.toDomainModel(extraction, documentApproval)

        then:
        thrown(IllegalArgumentException)
    }

    def "should extract party by role correctly"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.sellerInfo.role == Party.Role.SELLER
        invoice.buyerInfo.role == Party.Role.BUYER
        invoice.billToInfo.role == Party.Role.BILL_TO
    }

    def "should handle null parties list"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.parties = null
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.sellerInfo == null
        invoice.buyerInfo == null
        invoice.billToInfo == null
    }

    def "should handle empty parties list"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.parties = []
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.sellerInfo == null
        invoice.buyerInfo == null
        invoice.billToInfo == null
    }

    def "should return null when party role not found"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        // Only include seller, no buyer or billTo
        extraction.parties = [createParty(Party.Role.SELLER)]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.sellerInfo != null
        invoice.buyerInfo == null
        invoice.billToInfo == null
    }

    def "transformToLineItems should flatten charges from delivery transactions"() {
        given:
        def transactions = [
            createDeliveryTransaction("TICKET-1", "2024-01-15", 2),
            createDeliveryTransaction("TICKET-2", "2024-01-16", 1)
        ]
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = transactions
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems.size() == 3
        invoice.lineItems[0].ticketNumber == "TICKET-1"
        invoice.lineItems[1].ticketNumber == "TICKET-1"
        invoice.lineItems[2].ticketNumber == "TICKET-2"
    }

    def "transformToLineItems should calculate total from unitPrice and quantity"() {
        given:
        def charge = new Charge(
            lineNumber: "1",
            itemCode: "ITEM-001",
            description: "Test Item",
            unitOfMeasure: "EA",
            quantity: 5.0,
            unitPrice: 10.0,
            weight: 50.0
        )
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-1",
            deliveryDate: "2024-01-15",
            charges: [charge]
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems.size() == 1
        invoice.lineItems[0].total == 50.0
        invoice.lineItems[0].quantity == 5.0
        invoice.lineItems[0].unitPrice == 10.0
    }

    def "transformToLineItems should handle null unitPrice or quantity"() {
        given:
        def charge1 = new Charge(
            lineNumber: "1",
            quantity: 5.0,
            unitPrice: null
        )
        def charge2 = new Charge(
            lineNumber: "2",
            quantity: null,
            unitPrice: 10.0
        )
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-1",
            deliveryDate: "2024-01-15",
            charges: [charge1, charge2]
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems.size() == 2
        invoice.lineItems[0].total == null
        invoice.lineItems[1].total == null
    }

    def "transformToLineItems should handle null or empty transactions"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def documentApproval = createDocumentApproval()

        when: "transactions are null"
        extraction.deliveryTransactions = null
        def invoice1 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice1.lineItems.isEmpty()

        when: "transactions are empty"
        extraction.deliveryTransactions = []
        def invoice2 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice2.lineItems.isEmpty()
    }

    def "transformToLineItems should handle null charges in transaction"() {
        given:
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-1",
            deliveryDate: "2024-01-15",
            charges: null
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems.isEmpty()
    }

    def "transformToLineItems should include shipDate from delivery transaction"() {
        given:
        def charge = new Charge(lineNumber: "1")
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-1",
            deliveryDate: "2024-01-15",
            charges: [charge]
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems[0].shipDate == "2024-01-15"
    }

    def "should parse ISO date format correctly"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.documentDate = "2024-03-25"
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.documentDate.toString() == "2024-03-25"
    }

    def "should handle various date formats with fallback"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def documentApproval = createDocumentApproval()

        when: "date is in M/d/yyyy format"
        extraction.documentDate = "3/25/2024"
        def invoice1 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice1.documentDate.toString() == "2024-03-25"

        when: "date is in d/M/yyyy format"
        extraction.documentDate = "25/3/2024"
        def invoice2 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice2.documentDate.toString() == "2024-03-25"

        when: "date is in yyyy/MM/dd format"
        extraction.documentDate = "2024/03/25"
        def invoice3 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice3.documentDate.toString() == "2024-03-25"
    }

    def "should return null for null or blank date strings"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        def documentApproval = createDocumentApproval()

        when: "date is null"
        extraction.documentDate = null
        def invoice1 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice1.documentDate == null

        when: "date is blank"
        extraction.documentDate = "   "
        def invoice2 = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice2.documentDate == null
    }

    def "should return null for unparseable date strings"() {
        given:
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.documentDate = "invalid-date-format"
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.documentDate == null
    }

    def "should handle null delivery date in transaction"() {
        given:
        def charge = new Charge(lineNumber: "1")
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-1",
            deliveryDate: null,
            charges: [charge]
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)

        then:
        invoice.lineItems[0].shipDate == null
    }

    def "should map all charge fields to line item"() {
        given:
        def charge = new Charge(
            lineNumber: "5",
            itemCode: "ITEM-999",
            description: "Premium Widget",
            unitOfMeasure: "LBS",
            quantity: 12.5,
            unitPrice: 25.99,
            weight: 150.0
        )
        def transaction = new DeliveryTransaction(
            ticketId: "TICKET-X",
            deliveryDate: "2024-02-20",
            charges: [charge]
        )
        def extraction = createSampleExtraction(Extraction.DocumentType.INVOICE)
        extraction.deliveryTransactions = [transaction]
        def documentApproval = createDocumentApproval()

        when:
        def invoice = mapper.toInvoice(extraction, documentApproval)
        def lineItem = invoice.lineItems[0]

        then:
        lineItem.lineNumber == "5"
        lineItem.itemCode == "ITEM-999"
        lineItem.description == "Premium Widget"
        lineItem.unitOfMeasure == "LBS"
        lineItem.quantity == 12.5
        lineItem.unitPrice == 25.99
        lineItem.weight == 150.0
        lineItem.ticketNumber == "TICKET-X"
        lineItem.shipDate == "2024-02-20"
        lineItem.total == 12.5 * 25.99
    }

    // Helper methods

    private Extraction createSampleExtraction(Extraction.DocumentType docType) {
        def extraction = new Extraction()
        extraction.documentType = docType
        extraction.documentNumber = "INV-12345"
        extraction.documentDate = "2024-01-15"
        extraction.customerPONumber = "PO-999"
        extraction.jobOrderNumber = "JOB-123"
        extraction.parties = [
            createParty(Party.Role.SELLER),
            createParty(Party.Role.BUYER),
            createParty(Party.Role.BILL_TO)
        ]
        extraction.deliveryTransactions = [
            createDeliveryTransaction("T1", "2024-01-15", 2)
        ]
        def invoiceDetails = new InvoiceDetails()
        invoiceDetails.currencyCode = "USD"
        invoiceDetails.paymentTerms = "Net 30"
        extraction.invoiceDetails = invoiceDetails

        def shipmentDetails = new ShipmentDetails()
        shipmentDetails.warehouseLocation = "Warehouse A"
        shipmentDetails.notes = "Shipment details"
        extraction.shipmentDetails = shipmentDetails

        def deliveryAck = new DeliveryAcknowledgement()
        deliveryAck.recipientName = "John Doe"
        deliveryAck.notes = "Acknowledged"
        extraction.deliveryAcknowledgement = deliveryAck
        return extraction
    }

    private Party createParty(Party.Role role) {
        def party = new Party()
        party.role = role
        party.name = role == Party.Role.SELLER ? "Acme Corp" :
                     role == Party.Role.BUYER ? "Buyer Inc" : "BillTo LLC"
        def address = new Address()
        address.street = "123 Main St"
        address.city = "Anytown"
        party.address = address
        return party
    }

    private DeliveryTransaction createDeliveryTransaction(String ticketId, String date, int chargeCount) {
        def charges = (1..chargeCount).collect { i ->
            new Charge(
                lineNumber: "${i}",
                itemCode: "ITEM-${i}",
                description: "Item ${i}",
                unitOfMeasure: "EA",
                quantity: i * 2.0,
                unitPrice: i * 10.0,
                weight: i * 5.0
            )
        }
        return new DeliveryTransaction(
            ticketId: ticketId,
            deliveryDate: date,
            charges: charges
        )
    }

    private DocumentApproval createDocumentApproval() {
        return DocumentApproval.builder()
            .companyId(100L)
            .projectId("200")
            .assignedId("assigned-123")
            .documentType(Extraction.DocumentType.INVOICE.value())
            .build()
    }
}
