package com.tosspaper.delivery_notes

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.generated.model.DeliveryAcknowledgement
import com.tosspaper.generated.model.DeliveryNote
import com.tosspaper.generated.model.DeliveryNoteStatus
import com.tosspaper.generated.model.ShipmentDetails
import com.tosspaper.models.domain.LineItem
import com.tosspaper.models.jooq.tables.records.DeliveryNotesRecord
import org.jooq.JSONB
import spock.lang.Specification
import spock.lang.Unroll

import java.time.LocalDate
import java.time.OffsetDateTime

class DeliveryNoteMapperSpec extends Specification {

    DeliveryNoteMapper mapper
    ObjectMapper objectMapper

    def setup() {
        objectMapper = new ObjectMapper()
        objectMapper.findAndRegisterModules()
        mapper = new DeliveryNoteMapper(objectMapper)
    }

    def "toDto should map all basic fields from DeliveryNotesRecord to DeliveryNote"() {
        given: "a DeliveryNotesRecord with all basic fields"
        def record = new DeliveryNotesRecord(
                id: "dn-123",
                extractionTaskId: "task-456",
                companyId: 1L,
                documentNumber: "DN-001",
                documentDate: LocalDate.of(2024, 1, 15),
                projectId: "proj-1",
                projectName: "Test Project",
                jobNumber: "JOB-001",
                poNumber: "PO-001",
                deliveryMethodNote: "Air Freight",
                createdAt: OffsetDateTime.now(),
                createdBy: "user@test.com",
                status: "draft",
                purchaseOrderId: "po-123"
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "all basic fields are mapped correctly"
        result.id == "dn-123"
        result.extractionTaskId == "task-456"
        result.companyId == 1L
        result.documentNumber == "DN-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.projectId == "proj-1"
        result.projectName == "Test Project"
        result.jobNumber == "JOB-001"
        result.poNumber == "PO-001"
        result.deliveryMethodNote == "Air Freight"
        result.createdAt != null
        result.createdBy == "user@test.com"
        result.status == DeliveryNoteStatus.DRAFT
        result.purchaseOrderId == "po-123"
    }

    def "toDto should handle null status"() {
        given: "a record with null status"
        def record = new DeliveryNotesRecord(id: "dn-1", companyId: 1L, status: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "status is null"
        result.status == null
    }

    def "toDto should parse party JSONB fields to Map"() {
        given: "a record with party JSONB fields"
        // Party extraction DTO has: role, name, address (Address object), referenceNumber, contactInfo
        def partyJson = """
        {
            "name": "Test Company",
            "address": {
                "street": "123 Main St",
                "provinceOrState": "BC",
                "countryISO": "CA"
            }
        }
        """
        def record = new DeliveryNotesRecord(
                id: "dn-1",
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
        def record = new DeliveryNotesRecord(id: "dn-1", companyId: 1L)
        record."${fieldName}" = null

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "party field is not set when null in record"
        // When party field is null in record, it's not set on the DTO (remains uninitialized)
        // The mapper skips setting when getXxxInfo() returns null
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
        def record = new DeliveryNotesRecord(
                id: "dn-1",
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
        def record = new DeliveryNotesRecord(id: "dn-1", companyId: 1L, lineItems: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "lineItems is not set when null in record (remains null or empty)"
        result.lineItems == null || result.lineItems == []
    }

    def "toDto should parse shipmentDetails JSONB correctly"() {
        given: "a record with shipmentDetails"
        // OpenAPI ShipmentDetails has: warehouseLocation, driverId, vehicleId, deliveryDate, notes
        def shipmentDetailsJson = """
        {
            "warehouseLocation": "Warehouse A",
            "driverId": "DRV-001",
            "vehicleId": "VEH-001"
        }
        """
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                shipmentDetails: JSONB.valueOf(shipmentDetailsJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "shipmentDetails are parsed correctly"
        result.shipmentDetails != null
        result.shipmentDetails.warehouseLocation == "Warehouse A"
    }

    def "toDto should handle null shipmentDetails"() {
        given: "a record with null shipmentDetails"
        def record = new DeliveryNotesRecord(id: "dn-1", companyId: 1L, shipmentDetails: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "shipmentDetails is null"
        result.shipmentDetails == null
    }

    def "toDto should parse deliveryAcknowledgement JSONB correctly"() {
        given: "a record with deliveryAcknowledgement"
        // OpenAPI DeliveryAcknowledgement has: acknowledgedDate, ticketId, recipientName, recipientSignature, status
        def deliveryAckJson = """
        {
            "recipientName": "Jane Smith",
            "ticketId": "TKT-001",
            "status": "delivered"
        }
        """
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                deliveryAcknowledgement: JSONB.valueOf(deliveryAckJson)
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "deliveryAcknowledgement is parsed correctly"
        result.deliveryAcknowledgement != null
        result.deliveryAcknowledgement.recipientName == "Jane Smith"
    }

    def "toDto should handle null deliveryAcknowledgement"() {
        given: "a record with null deliveryAcknowledgement"
        def record = new DeliveryNotesRecord(id: "dn-1", companyId: 1L, deliveryAcknowledgement: null)

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "deliveryAcknowledgement is null"
        result.deliveryAcknowledgement == null
    }

    def "toDtoList should map multiple records"() {
        given: "multiple DeliveryNotesRecords"
        def records = [
                new DeliveryNotesRecord(id: "dn-1", companyId: 1L, documentNumber: "DN-001"),
                new DeliveryNotesRecord(id: "dn-2", companyId: 1L, documentNumber: "DN-002"),
                new DeliveryNotesRecord(id: "dn-3", companyId: 1L, documentNumber: "DN-003")
        ]

        when: "mapping to DTO list"
        def result = mapper.toDtoList(records)

        then: "all records are mapped"
        result.size() == 3
        result[0].id == "dn-1"
        result[0].documentNumber == "DN-001"
        result[1].id == "dn-2"
        result[1].documentNumber == "DN-002"
        result[2].id == "dn-3"
        result[2].documentNumber == "DN-003"
    }

    def "toDtoList should handle empty list"() {
        given: "an empty list"
        def records = []

        when: "mapping to DTO list"
        def result = mapper.toDtoList(records)

        then: "result is empty"
        result.isEmpty()
    }

    def "toDomain should map DeliveryNotesRecord to domain DeliveryNote"() {
        given: "a DeliveryNotesRecord with all fields"
        def partyJson = JSONB.valueOf('{"name": "Test Company"}')
        def lineItemsJson = JSONB.valueOf('[{"lineNumber": "1", "itemCode": "ITEM-001", "description": "Test"}]')
        // ShipmentDetails extraction DTO has: warehouseLocation, driverId, vehicleId, deliveryDate, notes
        def shipmentDetailsJson = JSONB.valueOf('{"warehouseLocation": "Warehouse A", "driverId": "DRV-001"}')
        // DeliveryAcknowledgement extraction DTO has: acknowledgedDate, ticketId, recipientName, recipientSignature, status, notes
        def deliveryAckJson = JSONB.valueOf('{"recipientName": "Jane Smith", "ticketId": "TKT-001"}')

        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                projectId: "proj-1",
                extractionTaskId: "task-1",
                documentNumber: "DN-001",
                documentDate: LocalDate.of(2024, 1, 15),
                poNumber: "PO-001",
                jobNumber: "JOB-001",
                sellerInfo: partyJson,
                buyerInfo: partyJson,
                shipToInfo: partyJson,
                billToInfo: partyJson,
                lineItems: lineItemsJson,
                shipmentDetails: shipmentDetailsJson,
                deliveryAcknowledgement: deliveryAckJson,
                status: "draft"
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "all fields are mapped correctly"
        result.companyId == 1L
        result.projectId == "proj-1"
        result.assignedId == "task-1"
        result.documentNumber == "DN-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.poNumber == "PO-001"
        result.jobNumber == "JOB-001"
        result.sellerInfo != null
        result.buyerInfo != null
        result.shipToInfo != null
        result.billToInfo != null
        result.lineItems != null
        result.lineItems.size() == 1
        result.shipmentDetails != null
        result.deliveryAcknowledgement != null
        result.status != null
    }

    def "toDomain should handle null record"() {
        when: "mapping null record to domain"
        def result = mapper.toDomain(null)

        then: "result is null"
        result == null
    }

    def "toDto from domain DeliveryNote should map all fields"() {
        given: "a domain DeliveryNote"
        // Address extraction DTO has: street, postalCode, provinceOrState, countryISO
        def addressObj = new com.tosspaper.models.extraction.dto.Address(
                street: "123 Main St",
                provinceOrState: "BC",
                countryISO: "CA"
        )
        def party = new com.tosspaper.models.extraction.dto.Party(
                name: "Test Company",
                address: addressObj
        )
        def lineItem = new LineItem(
                lineNumber: "1",
                itemCode: "ITEM-001",
                description: "Test Item",
                unitOfMeasure: "EA",
                quantity: 10.0,
                unitPrice: 50.00,
                total: 500.00
        )
        // ShipmentDetails extraction DTO has: warehouseLocation, driverId, vehicleId, deliveryDate, notes
        def shipmentDetails = new com.tosspaper.models.extraction.dto.ShipmentDetails(
                warehouseLocation: "Warehouse A",
                driverId: "DRV-001"
        )
        // DeliveryAcknowledgement extraction DTO has: acknowledgedDate, ticketId, recipientName, recipientSignature, status, notes
        def deliveryAck = new com.tosspaper.models.extraction.dto.DeliveryAcknowledgement(
                recipientName: "Jane Smith",
                ticketId: "TKT-001"
        )

        def domainNote = com.tosspaper.models.domain.DeliveryNote.builder()
                .companyId(1L)
                .projectId("proj-1")
                .assignedId("task-1")
                .documentNumber("DN-001")
                .documentDate(LocalDate.of(2024, 1, 15))
                .poNumber("PO-001")
                .jobNumber("JOB-001")
                .projectName("Test Project")
                .deliveryMethodNote("Air Freight")
                .sellerInfo(party)
                .buyerInfo(party)
                .shipToInfo(party)
                .billToInfo(party)
                .lineItems([lineItem])
                .shipmentDetails(shipmentDetails)
                .deliveryAcknowledgement(deliveryAck)
                .status(com.tosspaper.models.domain.DeliveryNote.Status.DRAFT)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainNote)

        then: "all fields are mapped correctly"
        result.companyId == 1L
        result.projectId == "proj-1"
        result.extractionTaskId == "task-1"
        result.documentNumber == "DN-001"
        result.documentDate == LocalDate.of(2024, 1, 15)
        result.poNumber == "PO-001"
        result.jobNumber == "JOB-001"
        result.projectName == "Test Project"
        result.deliveryMethodNote == "Air Freight"
        result.sellerInfo != null
        result.sellerInfo instanceof Map
        result.buyerInfo != null
        result.shipToInfo != null
        result.billToInfo != null
        result.lineItems != null
        result.lineItems.size() == 1
        result.shipmentDetails != null
        result.deliveryAcknowledgement != null
        result.status == DeliveryNoteStatus.DRAFT
    }

    def "toDto from domain should handle null domain"() {
        when: "mapping null domain to DTO"
        def result = mapper.toDto((com.tosspaper.models.domain.DeliveryNote) null)

        then: "result is null"
        result == null
    }

    def "toDto from domain should handle ACCEPTED status mapping to DELIVERED"() {
        given: "a domain DeliveryNote with ACCEPTED status"
        def domainNote = com.tosspaper.models.domain.DeliveryNote.builder()
                .companyId(1L)
                .documentNumber("DN-001")
                .status(com.tosspaper.models.domain.DeliveryNote.Status.ACCEPTED)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainNote)

        then: "ACCEPTED status is mapped to DELIVERED"
        result.status == DeliveryNoteStatus.DELIVERED
    }

    def "toDto from domain should handle null nested objects"() {
        given: "a domain DeliveryNote with null nested objects"
        def domainNote = com.tosspaper.models.domain.DeliveryNote.builder()
                .companyId(1L)
                .documentNumber("DN-001")
                .sellerInfo(null)
                .buyerInfo(null)
                .shipToInfo(null)
                .billToInfo(null)
                .lineItems(null)
                .shipmentDetails(null)
                .deliveryAcknowledgement(null)
                .status(null)
                .build()

        when: "mapping to DTO"
        def result = mapper.toDto(domainNote)

        then: "null fields are not set (remain null or initialized to empty)"
        // When domain fields are null, mapper doesn't set them (with null checks)
        (result.sellerInfo == null || result.sellerInfo == [:])
        (result.buyerInfo == null || result.buyerInfo == [:])
        (result.shipToInfo == null || result.shipToInfo == [:])
        (result.billToInfo == null || result.billToInfo == [:])
        (result.lineItems == null || result.lineItems == [])
        result.shipmentDetails == null
        result.deliveryAcknowledgement == null
        result.status == null
    }

    def "toDto should handle malformed JSONB gracefully"() {
        given: "a record with malformed shipmentDetails JSONB"
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                shipmentDetails: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but shipmentDetails is null"
        result != null
        result.id == "dn-1"
        result.shipmentDetails == null
    }

    def "toDto should handle malformed party JSONB gracefully"() {
        given: "a record with malformed sellerInfo JSONB"
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                sellerInfo: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but sellerInfo is null"
        result != null
        result.id == "dn-1"
        result.sellerInfo == null
    }

    def "toDto should handle malformed lineItems JSONB gracefully"() {
        given: "a record with malformed lineItems JSONB"
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                lineItems: JSONB.valueOf("{invalid json")
        )

        when: "mapping to DTO"
        def result = mapper.toDto(record)

        then: "mapping succeeds but lineItems is null"
        result != null
        result.id == "dn-1"
        result.lineItems == null
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
        def record = new DeliveryNotesRecord(
                id: "dn-1",
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

    def "toDomain should handle null status value"() {
        given: "a record with null status"
        def record = new DeliveryNotesRecord(
                id: "dn-1",
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
        def record = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                status: "invalid_status"
        )

        when: "mapping to domain"
        def result = mapper.toDomain(record)

        then: "status is null (not found)"
        result.status == null
    }

    def "toDomain should correctly find status by value"() {
        given: "records with valid status values"
        def draftRecord = new DeliveryNotesRecord(
                id: "dn-1",
                companyId: 1L,
                status: "draft"
        )
        // Note: Status.ACCEPTED has value "delivered", not "accepted"
        def deliveredRecord = new DeliveryNotesRecord(
                id: "dn-2",
                companyId: 1L,
                status: "delivered"
        )

        when: "mapping to domain"
        def draftResult = mapper.toDomain(draftRecord)
        def deliveredResult = mapper.toDomain(deliveredRecord)

        then: "statuses are mapped correctly"
        draftResult.status == com.tosspaper.models.domain.DeliveryNote.Status.DRAFT
        deliveredResult.status == com.tosspaper.models.domain.DeliveryNote.Status.ACCEPTED
    }
}
