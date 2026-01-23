package com.tosspaper.integrations.quickbooks.bill

import com.intuit.ipp.data.LineDetailTypeEnum
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.domain.LineItem
import com.tosspaper.models.extraction.dto.InvoiceDetails
import spock.lang.Specification
import spock.lang.Subject

import java.time.LocalDate

class BillMapperSpec extends Specification {

    @Subject
    BillMapper mapper = new BillMapper()

    def "should map Invoice to Bill with all fields"() {
        given:
        def invoice = createFullInvoice()
        def request = createRequest(invoice, "PO-2024-001")

        when:
        def bill = mapper.mapToBill(request, "56")

        then:
        bill != null

        and: "vendor ref is set"
        bill.vendorRef != null
        bill.vendorRef.value == "56"

        and: "dates are mapped"
        bill.txnDate != null
        bill.dueDate != null

        and: "document number is mapped"
        bill.docNumber == "INV-2024-001"

        and: "memo contains PO number"
        bill.privateNote == "PO: PO-2024-001"

        and: "lines are mapped"
        bill.line != null
        bill.line.size() == 2
    }

    def "should map item-based line items correctly"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-001")
                .lineItems([
                        createLineItem("Test Product", 5.0, 100.0, 500.0, "21", null)
                ])
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill.line.size() == 1
        def line = bill.line[0]

        and: "line type is item-based"
        line.detailType == LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL
        line.itemBasedExpenseLineDetail != null

        and: "item ref is set"
        line.itemBasedExpenseLineDetail.itemRef?.value == "21"

        and: "quantity and unit price are set"
        line.itemBasedExpenseLineDetail.qty == 5.0
        line.itemBasedExpenseLineDetail.unitPrice == 100.0

        and: "amount is set from total"
        line.amount == 500.0

        and: "description is set"
        line.description == "Test Product"
    }

    def "should map account-based line items correctly"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-002")
                .lineItems([
                        createLineItem("Consulting Services", null, null, 1500.0, null, "95")
                ])
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill.line.size() == 1
        def line = bill.line[0]

        and: "line type is account-based"
        line.detailType == LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL
        line.accountBasedExpenseLineDetail != null

        and: "account ref is set"
        line.accountBasedExpenseLineDetail.accountRef?.value == "95"

        and: "amount is set"
        line.amount == 1500.0
    }

    def "should calculate amount from unit price and quantity when total is null"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-003")
                .lineItems([
                        createLineItem("Product with calculated total", 3.0, 200.0, null, "30", null)
                ])
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        def line = bill.line[0]
        line.amount == 600.0  // 3 * 200
    }

    def "should throw exception when line item has no external ID"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-004")
                .lineItems([
                        createLineItem("Item without external ID", null, null, 100.0, null, null)
                ])
                .build()
        def request = createRequest(invoice, null)

        when:
        mapper.mapToBill(request, "99")

        then:
        def ex = thrown(IllegalStateException)
        ex.message.contains("missing required external ID")
        ex.message.contains("Item without external ID")
    }

    def "should handle invoice without line items"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-005")
                .documentDate(LocalDate.of(2024, 12, 1))
                .lineItems(null)
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill != null
        bill.docNumber == "INV-005"
        bill.line == []  // QBO SDK initializes line to empty list by default
    }

    def "should handle invoice without document date"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-006")
                .lineItems([])
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill != null
        bill.txnDate == null
    }

    def "should handle request without PO number"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-007")
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill != null
        bill.privateNote == ""
    }

    def "should map mixed item and account-based lines"() {
        given:
        def invoice = Invoice.builder()
                .documentNumber("INV-008")
                .lineItems([
                        createLineItem("Product", 2.0, 50.0, 100.0, "10", null),
                        createLineItem("Service Fee", null, null, 200.0, null, "85")
                ])
                .build()
        def request = createRequest(invoice, null)

        when:
        def bill = mapper.mapToBill(request, "99")

        then:
        bill.line.size() == 2

        and: "first line is item-based"
        bill.line[0].detailType == LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL
        bill.line[0].itemBasedExpenseLineDetail?.itemRef?.value == "10"

        and: "second line is account-based"
        bill.line[1].detailType == LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL
        bill.line[1].accountBasedExpenseLineDetail?.accountRef?.value == "85"
    }

    // ==================== Helper Methods ====================

    private Invoice createFullInvoice() {
        def invoiceDetails = new InvoiceDetails()
                .withDueDate("2024-12-15")

        return Invoice.builder()
                .documentNumber("INV-2024-001")
                .documentDate(LocalDate.of(2024, 11, 15))
                .invoiceDetails(invoiceDetails)
                .lineItems([
                        createLineItem("Industrial Valve", 10.0, 300.0, 3000.0, "21", null),
                        createLineItem("Shipping Expense", null, null, 150.0, null, "95")
                ])
                .build()
    }

    private DocumentSyncRequest<Invoice> createRequest(Invoice invoice, String poNumber) {
        return DocumentSyncRequest.<Invoice>builder()
                .document(invoice)
                .poNumber(poNumber)
                .build()
    }

    private LineItem createLineItem(String description, Double quantity, Double unitPrice, Double total, String externalItemId, String externalAccountId) {
        return LineItem.builder()
                .description(description)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .total(total)
                .externalItemId(externalItemId)
                .externalAccountId(externalAccountId)
                .build()
    }
}
