package com.tosspaper.invoices

import com.tosspaper.generated.model.Invoice
import com.tosspaper.models.jooq.tables.records.InvoicesRecord
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import spock.lang.Specification

import java.time.OffsetDateTime

class InvoiceServiceSpec extends Specification {

    InvoiceRepository invoiceRepository
    InvoiceMapper invoiceMapper
    InvoiceServiceImpl service

    def setup() {
        invoiceRepository = Mock()
        invoiceMapper = Mock()
        service = new InvoiceServiceImpl(invoiceRepository, invoiceMapper)
    }

    // ==================== getInvoices ====================

    def "getInvoices returns paginated list with default limit"() {
        given: "a company ID with no cursor"
            def companyId = 1L
            def records = [createInvoiceRecord("inv-1", companyId), createInvoiceRecord("inv-2", companyId)]
            def invoices = [createInvoice("inv-1"), createInvoice("inv-2")]

        when: "fetching invoices without limit"
            def result = service.getInvoices(companyId, null, null, null, null, null, null, null, null)

        then: "repository is called with default page size of 20"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.pageSize == 20
                return records
            }

        and: "records are mapped"
            1 * invoiceMapper.toDtoList(records) >> invoices

        and: "result contains invoices"
            result.data.size() == 2
            result.data[0].id == "inv-1"
            result.data[1].id == "inv-2"
    }

    def "getInvoices uses provided limit"() {
        given: "a specific limit"
            def companyId = 1L
            def limit = 5
            def records = []

        when: "fetching invoices with limit"
            def result = service.getInvoices(companyId, null, null, null, null, limit, null, null, null)

        then: "repository is called with provided limit"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.pageSize == 5
                return records
            }
            1 * invoiceMapper.toDtoList(records) >> []

        and: "result is returned"
            result.data.isEmpty()
    }

    def "getInvoices generates next cursor when results equal page size"() {
        given: "results that fill the page"
            def companyId = 1L
            def limit = 2
            def record1 = createInvoiceRecord("inv-1", companyId)
            def record2 = createInvoiceRecord("inv-2", companyId)
            def records = [record1, record2]

        when: "fetching invoices"
            def result = service.getInvoices(companyId, null, null, null, null, limit, null, null, null)

        then: "repository returns exactly limit records"
            1 * invoiceRepository.findInvoices(companyId, _) >> records
            1 * invoiceMapper.toDtoList(records) >> [createInvoice("inv-1"), createInvoice("inv-2")]

        and: "next cursor is generated"
            result.pagination.cursor != null
    }

    def "getInvoices returns null cursor when results less than page size"() {
        given: "results that don't fill the page"
            def companyId = 1L
            def limit = 10
            def records = [createInvoiceRecord("inv-1", companyId)]

        when: "fetching invoices"
            def result = service.getInvoices(companyId, null, null, null, null, limit, null, null, null)

        then: "repository returns less than limit"
            1 * invoiceRepository.findInvoices(companyId, _) >> records
            1 * invoiceMapper.toDtoList(records) >> [createInvoice("inv-1")]

        and: "no next cursor"
            result.pagination.cursor == null
    }

    def "getInvoices passes filter parameters to query"() {
        given: "filter parameters"
            def companyId = 1L
            def projectId = "proj-1"
            def purchaseOrderId = "po-1"
            def poNumber = "PO-001"
            def search = "test"

        when: "fetching invoices with filters"
            service.getInvoices(companyId, projectId, purchaseOrderId, poNumber, search, 20, null, null, null)

        then: "repository is called with all filters"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.projectId == projectId
                assert q.purchaseOrderId == purchaseOrderId
                assert q.poNumber == poNumber
                assert q.search == search
                return []
            }
            1 * invoiceMapper.toDtoList([]) >> []
    }

    def "getInvoices passes date range filters to query"() {
        given: "date range filters"
            def companyId = 1L
            def dueDateFrom = java.time.LocalDate.of(2024, 1, 1)
            def dueDateTo = java.time.LocalDate.of(2024, 12, 31)

        when: "fetching invoices with date range"
            service.getInvoices(companyId, null, null, null, null, 20, null, dueDateFrom, dueDateTo)

        then: "repository is called with date filters"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.dueDateFrom == dueDateFrom
                assert q.dueDateTo == dueDateTo
                return []
            }
            1 * invoiceMapper.toDtoList([]) >> []
    }

    def "getInvoices throws IllegalArgumentException for invalid cursor format"() {
        given: "an invalid cursor"
            def companyId = 1L
            def invalidCursor = "not-a-valid-cursor"

        when: "fetching invoices with invalid cursor"
            service.getInvoices(companyId, null, null, null, null, 20, invalidCursor, null, null)

        then: "IllegalArgumentException is thrown"
            def ex = thrown(IllegalArgumentException)
            ex.message.contains("Invalid cursor format")
    }

    def "getInvoices decodes valid cursor and passes to query"() {
        given: "a valid cursor"
            def companyId = 1L
            def createdAt = OffsetDateTime.now()
            def cursorId = "inv-cursor"
            def validCursor = com.tosspaper.models.utils.CursorUtils.encodeCursor(createdAt, cursorId)

        when: "fetching invoices with valid cursor"
            def result = service.getInvoices(companyId, null, null, null, null, 20, validCursor, null, null)

        then: "repository is called with decoded cursor values"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.cursorId == cursorId
                assert q.cursorCreatedAt != null
                return []
            }
            1 * invoiceMapper.toDtoList([]) >> []

        and: "result is returned"
            result.data.isEmpty()
    }

    def "getInvoices handles negative or zero limit by using default"() {
        given: "a zero limit"
            def companyId = 1L

        when: "fetching invoices with zero limit"
            def result = service.getInvoices(companyId, null, null, null, null, 0, null, null, null)

        then: "repository is called with default page size of 20"
            1 * invoiceRepository.findInvoices(companyId, _ as InvoiceQuery) >> { Long cId, InvoiceQuery q ->
                assert q.pageSize == 20
                return []
            }
            1 * invoiceMapper.toDtoList([]) >> []

        and: "result is returned"
            result.data.isEmpty()
    }

    // ==================== getInvoiceById ====================

    def "getInvoiceById returns invoice when found and company matches"() {
        given: "an existing invoice"
            def companyId = 1L
            def invoiceId = "inv-123"
            def record = createInvoiceRecord(invoiceId, companyId)
            def invoice = createInvoice(invoiceId)

        when: "fetching invoice by ID"
            def result = service.getInvoiceById(companyId, invoiceId)

        then: "repository returns record"
            1 * invoiceRepository.findById(invoiceId) >> record

        and: "record is mapped"
            1 * invoiceMapper.toDto(record) >> invoice

        and: "result has correct ID"
            result.id == invoiceId
    }

    def "getInvoiceById throws 404 when invoice not found"() {
        given: "non-existent invoice ID"
            def companyId = 1L
            def invoiceId = "non-existent"

        when: "fetching invoice by ID"
            service.getInvoiceById(companyId, invoiceId)

        then: "repository returns null"
            1 * invoiceRepository.findById(invoiceId) >> null

        and: "ResponseStatusException with 404 is thrown"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Invoice not found")
    }

    def "getInvoiceById throws 404 when company does not match"() {
        given: "an invoice from different company"
            def companyId = 1L
            def differentCompanyId = 2L
            def invoiceId = "inv-123"
            def record = createInvoiceRecord(invoiceId, differentCompanyId)

        when: "fetching invoice by ID"
            service.getInvoiceById(companyId, invoiceId)

        then: "repository returns record from different company"
            1 * invoiceRepository.findById(invoiceId) >> record

        and: "ResponseStatusException with 404 is thrown (security - don't reveal it exists)"
            def ex = thrown(ResponseStatusException)
            ex.statusCode == HttpStatus.NOT_FOUND
            ex.reason.contains("Invoice not found")

        and: "no mapping occurs"
            0 * invoiceMapper.toDto(_)
    }

    // ==================== Helper Methods ====================

    private InvoicesRecord createInvoiceRecord(String id, Long companyId) {
        def record = new InvoicesRecord()
        record.id = id
        record.companyId = companyId
        record.createdAt = OffsetDateTime.now()
        record.documentNumber = "DOC-${id}"
        return record
    }

    private static Invoice createInvoice(String id) {
        def invoice = new Invoice()
        invoice.id = id
        invoice.documentNumber = "DOC-${id}"
        return invoice
    }
}
