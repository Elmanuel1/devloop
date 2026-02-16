package com.tosspaper.invoices

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.generated.model.Invoice
import com.tosspaper.generated.model.InvoiceDetails
import com.tosspaper.generated.model.InvoiceStatus
import com.tosspaper.models.domain.LineItem
import com.tosspaper.models.jooq.tables.records.InvoicesRecord
import org.jooq.JSONB
import spock.lang.Specification
import spock.lang.Unroll

import java.time.LocalDate
import java.time.OffsetDateTime

class InvoiceMapperSpec extends Specification {

    InvoiceMapper mapper
    ObjectMapper objectMapper

    def setup() {
        objectMapper = new ObjectMapper()
        objectMapper.findAndRegisterModules()
        mapper = new InvoiceMapper(objectMapper)
    }

    def "toDto should map all basic fields from InvoicesRecord to Invoice"() {
        given: "an InvoicesRecord with all basic fields"
        def record = new InvoicesRecord(
                id: "inv-123",
                extractionTaskId: "task-456",
                companyId: 1L,
                documentNumber: "INV-001",
                documentDate: LocalDate.of(2024, 1, 15),
                projectId: "proj-1",
                projectName: "Test Project",
                poNumber: "PO-001",
                orderTicketNumber: "TKT-001",
                receivedAt: OffsetDateTime.now(),
                createdAt: OffsetDateTime.now(),
                createdBy: "user@test.com",
                status: "pending",
                purchaseOrderId: "po-123"
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "all basic fields are mapped correctly"
        result.id == "inv-123"
        result.extractionTaskId == "task-456"
        result.companyId == 1L
        result.documentNumber == "INV-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.projectId == "proj-1"
        result.projectName == "Test Project"
        result.poNumber == "PO-001"
        result.orderTicketNumber == "TKT-001"
        result.receivedAt != null
        result.createdAt != null
        result.createdBy == "user@test.com"
        result.status == InvoiceStatus.PENDING
        result.purchaseOrderId == "po-123"
    }

    def "toDto should handle null status"() {
        given: "a record with null status"
        def record = new InvoicesRecord(id: "inv-1", companyId: 1L, status: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "status is null"
        result.status == null
    }

    def "toDto should parse invoiceDetails JSONB correctly"() {
        given: "a record with invoiceDetails"
        def invoiceDetailsJson = """
        {
            "currencyCode": "CAD",
            "paymentTerms": "Net 30",
            "dueDate": "2024-02-15"
        }
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                invoiceDetails: JSONB.valueOf(invoiceDetailsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "invoiceDetails are parsed correctly"
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == "CAD"
        result.invoiceDetails.paymentTerms == "Net 30"
    }

    def "toDto should handle null invoiceDetails"() {
        given: "a record with null invoiceDetails"
        def record = new InvoicesRecord(id: "inv-1", companyId: 1L, invoiceDetails: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "invoiceDetails is null"
        result.invoiceDetails == null
    }

    def "toDto should parse party JSONB fields to Map"() {
        given: "a record with party JSONB fields"
        // Party extraction DTO: role must match Party.Role enum values: "Seller", "Buyer", "Ship To", "Bill To"
        def partyJson = """
        {
            "role": "Seller",
            "name": "Test Company"
        }
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                sellerInfo: JSONB.valueOf(partyJson),
                buyerInfo: JSONB.valueOf(partyJson),
                shipToInfo: JSONB.valueOf(partyJson),
                billToInfo: JSONB.valueOf(partyJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "all party fields are mapped to Maps"
        result.sellerInfo instanceof Map
        result.sellerInfo.name == "Test Company"
        result.buyerInfo instanceof Map
        result.buyerInfo.name == "Test Company"
        result.shipToInfo instanceof Map
        result.shipToInfo.name == "Test Company"
        result.billToInfo instanceof Map
        result.billToInfo.name == "Test Company"
    }

    @Unroll
    def "toDto should handle null party field: #fieldName"() {
        given: "a record with null party field"
        def record = new InvoicesRecord(id: "inv-1", companyId: 1L)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "party field is null or empty map (ObjectMapper behavior)"
        result."${fieldName}" == null || result."${fieldName}" == [:]

        where:
        fieldName << ["sellerInfo", "buyerInfo", "shipToInfo", "billToInfo"]
    }

    def "toDto should parse lineItems JSONB and convert to OpenAPI LineItems"() {
        given: "a record with lineItems"
        def lineItemsJson = """
        [
            {
                "lineNumber": "1",
                "itemCode": "ITEM-001",
                "description": "Test Item 1",
                "unitOfMeasure": "EA",
                "quantity": 10.0,
                "unitPrice": 50.00,
                "total": 500.00,
                "ticketNumber": "TKT-001",
                "shipDate": "2024-01-15"
            },
            {
                "lineNumber": "2",
                "itemCode": "ITEM-002",
                "description": "Test Item 2",
                "unitOfMeasure": "BOX",
                "quantity": 5.0,
                "unitPrice": 100.00,
                "total": 500.00
            }
        ]
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf(lineItemsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItems are converted correctly"
        result.lineItems != null
        result.lineItems.size() == 2

        and: "first line item is mapped correctly"
        result.lineItems[0].itemNo == "1"
        result.lineItems[0].itemCode == "ITEM-001"
        result.lineItems[0].description == "Test Item 1"
        result.lineItems[0].unit == "EA"
        result.lineItems[0].quantity == 10.0
        result.lineItems[0].unitCost == 50.00
        result.lineItems[0].total == 500.00
        result.lineItems[0].ticketNumber == "TKT-001"
        result.lineItems[0].shipDate == LocalDate.of(2024, 1, 15)

        and: "second line item is mapped correctly"
        result.lineItems[1].itemNo == "2"
        result.lineItems[1].itemCode == "ITEM-002"
        result.lineItems[1].shipDate == null
    }

    def "toDto should handle null lineItems"() {
        given: "a record with null lineItems"
        def record = new InvoicesRecord(id: "inv-1", companyId: 1L, lineItems: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItems is null or empty list"
        result.lineItems == null || result.lineItems == []
    }

    def "toDto should handle invalid ship date in lineItems gracefully"() {
        given: "a record with invalid ship date"
        def lineItemsJson = """
        [
            {
                "lineNumber": "1",
                "itemCode": "ITEM-001",
                "description": "Test Item",
                "shipDate": "invalid-date"
            }
        ]
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf(lineItemsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItem is still mapped but shipDate is null"
        result.lineItems != null
        result.lineItems.size() == 1
        result.lineItems[0].shipDate == null
    }

    def "toDtoList should map multiple records"() {
        given: "multiple InvoicesRecords"
        def records = [
                new InvoicesRecord(id: "inv-1", companyId: 1L, documentNumber: "INV-001"),
                new InvoicesRecord(id: "inv-2", companyId: 1L, documentNumber: "INV-002"),
                new InvoicesRecord(id: "inv-3", companyId: 1L, documentNumber: "INV-003")
        ]

        when: "mapping to DTO list"
        def result = mapper.toDtoList(records)

        then: "all records are mapped"
        result.size() == 3
        result[0].id == "inv-1"
        result[0].documentNumber == "INV-001"
        result[1].id == "inv-2"
        result[1].documentNumber == "INV-002"
        result[2].id == "inv-3"
        result[2].documentNumber == "INV-003"
    }

    def "toDtoList should handle empty list"() {
        given: "an empty list"
        def records = []

        when: "mapping to DTO list"
        def result = mapper.toDtoList(records)

        then: "result is empty"
        result.isEmpty()
    }

    def "toDomain should map InvoicesRecord to domain Invoice"() {
        given: "an InvoicesRecord with all fields"
        // Party.Role enum values: "Seller", "Buyer", "Ship To", "Bill To"
        def partyJson = JSONB.valueOf('{"role": "Seller", "name": "Test Company"}')
        def lineItemsJson = JSONB.valueOf('[{"lineNumber": "1", "itemCode": "ITEM-001", "description": "Test"}]')
        def invoiceDetailsJson = JSONB.valueOf('{"currencyCode": "CAD", "paymentTerms": "Net 30"}')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                projectId: "proj-1",
                extractionTaskId: "task-1",
                documentNumber: "INV-001",
                documentDate: LocalDate.of(2024, 1, 15),
                poNumber: "PO-001",
                sellerInfo: partyJson,
                buyerInfo: partyJson,
                shipToInfo: partyJson,
                billToInfo: partyJson,
                lineItems: lineItemsJson,
                invoiceDetails: invoiceDetailsJson,
                status: "pending",
                lastSyncAt: OffsetDateTime.now()
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "all fields are mapped correctly"
        result.companyId == 1L
        result.projectId == "proj-1"
        result.assignedId == "task-1"
        result.documentNumber == "INV-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.poNumber == "PO-001"
        result.sellerInfo != null
        result.buyerInfo != null
        result.shipToInfo != null
        result.billToInfo != null
        result.lineItems != null
        result.lineItems.size() == 1
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == "CAD"
        result.status != null
    }

    def "toDomain should handle null record"() {
        when: "mapping null record to domain"
        def result = mapper.toDomain(null)

        then: "result is null"
        result == null
    }

    def "toDto from domain Invoice should map all fields"() {
        given: "a domain Invoice"
        def party = new com.tosspaper.models.extraction.dto.Party()
        party.name = "Test Company"

        def lineItem = new LineItem(
                lineNumber: "1",
                itemCode: "ITEM-001",
                description: "Test Item",
                unitOfMeasure: "EA",
                quantity: 10.0,
                unitPrice: 50.00,
                total: 500.00
        )
        def invoiceDetails = new com.tosspaper.models.extraction.dto.InvoiceDetails()
        invoiceDetails.currencyCode = "CAD"
        invoiceDetails.paymentTerms = "Net 30"

        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .projectId("proj-1")
                .assignedId("task-1")
                .documentNumber("INV-001")
                .documentDate(LocalDate.of(2024, 1, 15))
                .poNumber("PO-001")
                .jobNumber("JOB-001")
                .sellerInfo(party)
                .buyerInfo(party)
                .shipToInfo(party)
                .billToInfo(party)
                .lineItems([lineItem])
                .invoiceDetails(invoiceDetails)
                .status(com.tosspaper.models.domain.Invoice.Status.PENDING)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "all fields are mapped correctly"
        result.companyId == 1L
        result.projectId == "proj-1"
        result.extractionTaskId == "task-1"
        result.documentNumber == "INV-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.poNumber == "PO-001"
        result.orderTicketNumber == "JOB-001"
        result.sellerInfo != null
        result.sellerInfo instanceof Map
        result.buyerInfo != null
        result.shipToInfo != null
        result.billToInfo != null
        result.lineItems != null
        result.lineItems.size() == 1
        result.invoiceDetails != null
        result.status == InvoiceStatus.PENDING
    }

    def "toDto from domain should handle null domain"() {
        when: "mapping null domain to DTO"
        def result = mapper.toDto((com.tosspaper.models.domain.Invoice) null)

        then: "result is null"
        result == null
    }

    def "toDto from domain should handle ACCEPTED status mapping to PENDING"() {
        given: "a domain Invoice with ACCEPTED status"
        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-001")
                .status(com.tosspaper.models.domain.Invoice.Status.ACCEPTED)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "ACCEPTED status is mapped to PENDING"
        result.status == InvoiceStatus.PENDING
    }

    def "toDto from domain should handle null nested objects"() {
        given: "a domain Invoice with null nested objects"
        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-001")
                .sellerInfo(null)
                .buyerInfo(null)
                .shipToInfo(null)
                .billToInfo(null)
                .lineItems(null)
                .invoiceDetails(null)
                .status(null)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "null fields remain null or empty (ObjectMapper behavior)"
        (result.sellerInfo == null || result.sellerInfo == [:])
        (result.buyerInfo == null || result.buyerInfo == [:])
        (result.shipToInfo == null || result.shipToInfo == [:])
        (result.billToInfo == null || result.billToInfo == [:])
        (result.lineItems == null || result.lineItems == [])
        result.invoiceDetails == null
        result.status == null
    }

    def "toInvoiceDetail should serialize InvoiceDetails to JSON string"() {
        given: "an InvoiceDetails object"
        def invoiceDetails = new com.tosspaper.models.extraction.dto.InvoiceDetails()
        invoiceDetails.currencyCode = "CAD"
        invoiceDetails.paymentTerms = "Net 30"

        when: "converting to JSON string"
        def result = mapper.toInvoiceDetail(invoiceDetails)

        then: "result is valid JSON string"
        result != null
        result.contains('"currencyCode"')
        result.contains('CAD')
    }

    def "toDto should handle malformed JSONB gracefully"() {
        given: "a record with malformed invoiceDetails JSONB"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                invoiceDetails: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but invoiceDetails is null"
        result != null
        result.id == "inv-1"
        result.invoiceDetails == null
    }

    def "toDto should handle malformed party JSONB gracefully"() {
        given: "a record with malformed sellerInfo JSONB"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                sellerInfo: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but sellerInfo is null"
        result != null
        result.id == "inv-1"
        result.sellerInfo == null
    }

    def "toDto should handle malformed lineItems JSONB gracefully"() {
        given: "a record with malformed lineItems JSONB"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but lineItems is null"
        result != null
        result.id == "inv-1"
        result.lineItems == null
    }

    def "convertLineItem should handle null lineItem"() {
        given: "the mapper is initialized"
        def mapper = new InvoiceMapper(objectMapper)

        when: "converting null lineItem via toDto with null lineItems list"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf('[null]')
        )
        def result = mapper.toDto(record)

        then: "result contains null item in list"
        result.lineItems != null
        result.lineItems.size() == 1
        result.lineItems[0] == null
    }

    def "toDomain should handle null status value"() {
        given: "a record with null status"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                status: null
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "status is null"
        result.status == null
    }

    def "toDomain should handle invalid status value"() {
        given: "a record with invalid status value"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                status: "invalid_status"
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "status is null (not found)"
        result.status == null
    }

    def "toDomain should correctly find status by value"() {
        given: "a record with valid status values"
        def pendingRecord = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                status: "pending"
        )
        def acceptedRecord = new InvoicesRecord(
                id: "inv-2",
                companyId: 1L,
                status: "accepted"
        )

        when: "mapping to domain"
        def pendingResult = mapper.toDomain(pendingRecord)
        def acceptedResult = mapper.toDomain(acceptedRecord)

        then: "statuses are mapped correctly"
        pendingResult.status == com.tosspaper.models.domain.Invoice.Status.PENDING
        acceptedResult.status == com.tosspaper.models.domain.Invoice.Status.ACCEPTED
    }

    // ==================== Additional Coverage Tests ====================

    def "toDto should parse invoiceDetails with dueDate correctly"() {
        given: "a record with invoiceDetails including dueDate"
        def invoiceDetailsJson = """
        {
            "currencyCode": "USD",
            "paymentTerms": "Net 60",
            "dueDate": "2024-03-15"
        }
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                invoiceDetails: JSONB.valueOf(invoiceDetailsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "invoiceDetails including dueDate are parsed correctly"
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == "USD"
        result.invoiceDetails.paymentTerms == "Net 60"
        result.invoiceDetails.dueDate == LocalDate.parse("2024-03-15")
    }

    def "toDto should parse complete party with address and contactInfo"() {
        given: "a record with complete party information"
        def partyJson = """
        {
            "role": "Seller",
            "name": "Acme Corporation",
            "referenceNumber": "REF-12345",
            "address": {
                "street": "123 Main St",
                "city": "Vancouver",
                "provinceOrState": "BC",
                "postalCode": "V6B 1A1",
                "country": "Canada"
            },
            "contactInfo": {
                "phone": "604-555-1234",
                "email": "sales@acme.com"
            }
        }
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                sellerInfo: JSONB.valueOf(partyJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "complete party info is mapped to Map"
        result.sellerInfo instanceof Map
        result.sellerInfo.name == "Acme Corporation"
        result.sellerInfo.referenceNumber == "REF-12345"
        result.sellerInfo.address != null
        result.sellerInfo.address.city == "Vancouver"
        result.sellerInfo.contactInfo != null
        result.sellerInfo.contactInfo.email == "sales@acme.com"
    }

    def "toDto should parse lineItems with all optional fields including weight"() {
        given: "a record with lineItems containing all fields"
        def lineItemsJson = """
        [
            {
                "lineNumber": "1",
                "itemCode": "GRAVEL-001",
                "description": "Premium Gravel 3/4 inch",
                "unitOfMeasure": "TON",
                "quantity": 25.5,
                "unitPrice": 45.00,
                "weight": 25500.0,
                "total": 1147.50,
                "ticketNumber": "TKT-2024-001",
                "shipDate": "2024-01-20",
                "externalItemId": "qb-item-123",
                "externalAccountId": "qb-acct-456"
            }
        ]
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf(lineItemsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "all lineItem fields are converted correctly"
        result.lineItems != null
        result.lineItems.size() == 1
        with(result.lineItems[0]) {
            itemNo == "1"
            itemCode == "GRAVEL-001"
            description == "Premium Gravel 3/4 inch"
            unit == "TON"
            quantity == 25.5
            unitCost == 45.00
            total == 1147.50
            ticketNumber == "TKT-2024-001"
            shipDate == LocalDate.of(2024, 1, 20)
        }
    }

    def "toDto should handle empty lineItems array"() {
        given: "a record with empty lineItems array"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf('[]')
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItems is empty list"
        result.lineItems != null
        result.lineItems.isEmpty()
    }

    def "toDto should handle empty invoiceDetails object"() {
        given: "a record with empty invoiceDetails object"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                invoiceDetails: JSONB.valueOf('{}')
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "invoiceDetails is parsed but fields are null"
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == null
        result.invoiceDetails.paymentTerms == null
    }

    def "toDto from domain should map lineItems with all fields"() {
        given: "a domain Invoice with complete lineItems"
        def lineItem = new LineItem(
                lineNumber: "1",
                itemCode: "SAND-001",
                description: "Fine Sand",
                unitOfMeasure: "CY",
                quantity: 100.0,
                unitPrice: 35.00,
                weight: 150000.0,
                total: 3500.00,
                ticketNumber: "TKT-2024-002",
                shipDate: "2024-02-10",
                externalItemId: "ext-item-1",
                externalAccountId: "ext-acct-1"
        )

        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-002")
                .lineItems([lineItem])
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "lineItems are converted with all fields"
        result.lineItems != null
        result.lineItems.size() == 1
        with(result.lineItems[0]) {
            itemNo == "1"
            itemCode == "SAND-001"
            description == "Fine Sand"
            unit == "CY"
            quantity == 100.0
            unitCost == 35.00
            total == 3500.00
            ticketNumber == "TKT-2024-002"
            shipDate == LocalDate.of(2024, 2, 10)
        }
    }

    def "toDto from domain should handle empty lineItems list"() {
        given: "a domain Invoice with empty lineItems list"
        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-003")
                .lineItems([])
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "lineItems is empty list"
        result.lineItems != null
        result.lineItems.isEmpty()
    }

    def "toDto from domain should handle complete party with address"() {
        given: "a domain Invoice with complete party information"
        def address = new com.tosspaper.models.extraction.dto.Address()
        address.city = "Toronto"
        address.provinceOrState = "ON"

        def contactInfo = new com.tosspaper.models.extraction.dto.ContactInfo()
        contactInfo.email = "info@company.com"

        def party = new com.tosspaper.models.extraction.dto.Party()
        party.role = com.tosspaper.models.extraction.dto.Party.Role.SELLER
        party.name = "Complete Company Ltd"
        party.referenceNumber = "REF-999"
        party.address = address
        party.contactInfo = contactInfo

        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-004")
                .sellerInfo(party)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "complete party is converted to Map with nested objects"
        result.sellerInfo instanceof Map
        result.sellerInfo.name == "Complete Company Ltd"
        result.sellerInfo.referenceNumber == "REF-999"
        result.sellerInfo.address != null
        result.sellerInfo.address.city == "Toronto"
        result.sellerInfo.contactInfo != null
        result.sellerInfo.contactInfo.email == "info@company.com"
    }

    def "toDto from domain should handle invoiceDetails with all fields"() {
        given: "a domain Invoice with complete invoiceDetails"
        def invoiceDetails = new com.tosspaper.models.extraction.dto.InvoiceDetails()
        invoiceDetails.currencyCode = "EUR"
        invoiceDetails.paymentTerms = "Net 45"
        invoiceDetails.dueDate = "2024-04-30"

        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-005")
                .invoiceDetails(invoiceDetails)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "invoiceDetails is fully converted"
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == "EUR"
        result.invoiceDetails.paymentTerms == "Net 45"
        result.invoiceDetails.dueDate == LocalDate.parse("2024-04-30")
    }

    def "toDomain should parse complete party with nested objects"() {
        given: "a record with complete party including address and contactInfo"
        def partyJson = JSONB.valueOf('''
        {
            "role": "Buyer",
            "name": "Buyer Corp",
            "referenceNumber": "BUY-001",
            "address": {
                "street": "456 Commerce Ave",
                "city": "Calgary",
                "provinceOrState": "AB",
                "postalCode": "T2P 3M9"
            },
            "contactInfo": {
                "phone": "403-555-9999",
                "email": "purchasing@buyer.com"
            }
        }
        ''')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                buyerInfo: partyJson
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "party with nested objects is parsed correctly"
        result.buyerInfo != null
        result.buyerInfo.name == "Buyer Corp"
        result.buyerInfo.role == com.tosspaper.models.extraction.dto.Party.Role.BUYER
        result.buyerInfo.referenceNumber == "BUY-001"
        result.buyerInfo.address != null
        result.buyerInfo.address.city == "Calgary"
        result.buyerInfo.contactInfo != null
        result.buyerInfo.contactInfo.email == "purchasing@buyer.com"
    }

    def "toDomain should parse invoiceDetails with all fields"() {
        given: "a record with complete invoiceDetails"
        def invoiceDetailsJson = JSONB.valueOf('''
        {
            "currencyCode": "GBP",
            "paymentTerms": "Due on Receipt",
            "dueDate": "2024-05-01"
        }
        ''')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                invoiceDetails: invoiceDetailsJson
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "invoiceDetails with all fields is parsed correctly"
        result.invoiceDetails != null
        result.invoiceDetails.currencyCode == "GBP"
        result.invoiceDetails.paymentTerms == "Due on Receipt"
        result.invoiceDetails.dueDate == "2024-05-01"
    }

    def "toDomain should parse lineItems with all optional fields"() {
        given: "a record with lineItems containing all fields"
        def lineItemsJson = JSONB.valueOf('''
        [
            {
                "lineNumber": "1",
                "itemCode": "CONCRETE-MIX",
                "description": "Ready Mix Concrete 30MPa",
                "unitOfMeasure": "M3",
                "quantity": 50.0,
                "unitPrice": 175.00,
                "weight": 120000.0,
                "total": 8750.00,
                "ticketNumber": "BATCH-001",
                "shipDate": "2024-03-01",
                "externalItemId": "qb-concrete",
                "externalAccountId": "qb-materials"
            }
        ]
        ''')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: lineItemsJson
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "lineItems with all fields are parsed correctly"
        result.lineItems != null
        result.lineItems.size() == 1
        with(result.lineItems[0]) {
            lineNumber == "1"
            itemCode == "CONCRETE-MIX"
            description == "Ready Mix Concrete 30MPa"
            unitOfMeasure == "M3"
            quantity == 50.0
            unitPrice == 175.00
            weight == 120000.0
            total == 8750.00
            ticketNumber == "BATCH-001"
            shipDate == "2024-03-01"
            externalItemId == "qb-concrete"
            externalAccountId == "qb-materials"
        }
    }

    def "toInvoiceDetail should handle null values in InvoiceDetails"() {
        given: "an InvoiceDetails with only currencyCode set"
        def invoiceDetails = new com.tosspaper.models.extraction.dto.InvoiceDetails()
        invoiceDetails.currencyCode = "JPY"
        // paymentTerms and dueDate remain null

        when: "converting to JSON string"
        def result = mapper.toInvoiceDetail(invoiceDetails)

        then: "result contains currencyCode but not null fields (due to NON_NULL)"
        result != null
        result.contains('"currencyCode"')
        result.contains('JPY')
        // NON_NULL means null fields are omitted
    }

    def "toInvoiceDetail should serialize complete InvoiceDetails"() {
        given: "an InvoiceDetails with all fields"
        def invoiceDetails = new com.tosspaper.models.extraction.dto.InvoiceDetails()
        invoiceDetails.currencyCode = "AUD"
        invoiceDetails.paymentTerms = "2/10 Net 30"
        invoiceDetails.dueDate = "2024-06-15"

        when: "converting to JSON string"
        def result = mapper.toInvoiceDetail(invoiceDetails)

        then: "all fields are serialized"
        result != null
        result.contains('"currencyCode"')
        result.contains('AUD')
        result.contains('"paymentTerms"')
        result.contains('2/10 Net 30')
        result.contains('"dueDate"')
        result.contains('2024-06-15')
    }

    def "toDtoList should preserve order of records"() {
        given: "InvoicesRecords in specific order"
        def records = [
                new InvoicesRecord(id: "inv-c", companyId: 1L, documentNumber: "INV-C"),
                new InvoicesRecord(id: "inv-a", companyId: 1L, documentNumber: "INV-A"),
                new InvoicesRecord(id: "inv-b", companyId: 1L, documentNumber: "INV-B")
        ]

        when: "mapping to DTO list"
        def result = mapper.toDtoList(records)

        then: "order is preserved"
        result.size() == 3
        result[0].id == "inv-c"
        result[1].id == "inv-a"
        result[2].id == "inv-b"
    }

    def "toDto should handle lineItem with null shipDate string"() {
        given: "a record with lineItem where shipDate is explicitly null in JSON"
        def lineItemsJson = """
        [
            {
                "lineNumber": "1",
                "itemCode": "ITEM-001",
                "description": "Test Item",
                "shipDate": null
            }
        ]
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf(lineItemsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItem is mapped with null shipDate"
        result.lineItems != null
        result.lineItems.size() == 1
        result.lineItems[0].shipDate == null
    }

    def "toDto should map receivedAt and createdAt timestamps correctly"() {
        given: "a record with specific timestamps"
        def receivedAt = OffsetDateTime.parse("2024-01-15T10:30:00Z")
        def createdAt = OffsetDateTime.parse("2024-01-15T10:35:00Z")

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                receivedAt: receivedAt,
                createdAt: createdAt
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "timestamps are mapped correctly"
        result.receivedAt == receivedAt
        result.createdAt == createdAt
    }

    def "toDto should handle null receivedAt and createdAt"() {
        given: "a record with null timestamps"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                receivedAt: null,
                createdAt: null
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "timestamps are null"
        result.receivedAt == null
        result.createdAt == null
    }

    def "toDomain should map lastSyncAt correctly"() {
        given: "a record with lastSyncAt timestamp"
        def lastSyncAt = OffsetDateTime.parse("2024-02-01T15:00:00Z")

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lastSyncAt: lastSyncAt
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "lastSyncAt is mapped correctly"
        result.lastSyncAt == lastSyncAt
    }

    def "toDomain should handle null lastSyncAt"() {
        given: "a record with null lastSyncAt"
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lastSyncAt: null
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "lastSyncAt is null"
        result.lastSyncAt == null
    }

    def "toDto should map multiple lineItems preserving order"() {
        given: "a record with multiple lineItems"
        def lineItemsJson = """
        [
            {"lineNumber": "3", "itemCode": "C", "description": "Third"},
            {"lineNumber": "1", "itemCode": "A", "description": "First"},
            {"lineNumber": "2", "itemCode": "B", "description": "Second"}
        ]
        """
        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                lineItems: JSONB.valueOf(lineItemsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItems order is preserved"
        result.lineItems.size() == 3
        result.lineItems[0].itemNo == "3"
        result.lineItems[1].itemNo == "1"
        result.lineItems[2].itemNo == "2"
    }

    def "toDto from domain with null status should result in null status"() {
        given: "a domain Invoice with null status"
        def domainInvoice = com.tosspaper.models.domain.Invoice.builder()
                .companyId(1L)
                .documentNumber("INV-006")
                .status(null)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainInvoice)

        then: "status is null"
        result.status == null
    }

    def "toDto should map all party types correctly"() {
        given: "a record with all four party types"
        def sellerJson = JSONB.valueOf('{"role": "Seller", "name": "Seller Corp"}')
        def buyerJson = JSONB.valueOf('{"role": "Buyer", "name": "Buyer Corp"}')
        def shipToJson = JSONB.valueOf('{"role": "Buyer", "name": "Warehouse A"}')
        def billToJson = JSONB.valueOf('{"role": "Bill To", "name": "Accounts Dept"}')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                sellerInfo: sellerJson,
                buyerInfo: buyerJson,
                shipToInfo: shipToJson,
                billToInfo: billToJson
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "all four party types are mapped"
        result.sellerInfo.name == "Seller Corp"
        result.buyerInfo.name == "Buyer Corp"
        result.shipToInfo.name == "Warehouse A"
        result.billToInfo.name == "Accounts Dept"
    }

    def "toDomain should map all party types correctly"() {
        given: "a record with all four party types"
        def sellerJson = JSONB.valueOf('{"role": "Seller", "name": "Seller Inc"}')
        def buyerJson = JSONB.valueOf('{"role": "Buyer", "name": "Buyer Inc"}')
        def shipToJson = JSONB.valueOf('{"role": "Buyer", "name": "Dock B"}')
        def billToJson = JSONB.valueOf('{"role": "Bill To", "name": "Finance Dept"}')

        def record = new InvoicesRecord(
                id: "inv-1",
                companyId: 1L,
                sellerInfo: sellerJson,
                buyerInfo: buyerJson,
                shipToInfo: shipToJson,
                billToInfo: billToJson
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "all four party types are mapped"
        result.sellerInfo.name == "Seller Inc"
        result.buyerInfo.name == "Buyer Inc"
        result.shipToInfo.name == "Dock B"
        result.billToInfo.name == "Finance Dept"
    }
}
