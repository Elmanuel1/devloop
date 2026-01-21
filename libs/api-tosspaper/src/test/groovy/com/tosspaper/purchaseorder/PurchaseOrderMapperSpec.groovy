package com.tosspaper.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.generated.model.Contact
import com.tosspaper.generated.model.PurchaseOrder
import com.tosspaper.generated.model.PurchaseOrderCreate
import com.tosspaper.generated.model.PurchaseOrderItem
import com.tosspaper.generated.model.PurchaseOrderStatus
import com.tosspaper.generated.model.PurchaseOrderUpdate
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.jooq.tables.pojos.PurchaseOrderItems
import com.tosspaper.models.jooq.tables.records.PurchaseOrderFlatItemsRecord
import com.tosspaper.models.jooq.tables.records.PurchaseOrdersRecord
import org.jooq.JSONB
import org.mapstruct.factory.Mappers
import spock.lang.Specification
import spock.lang.Unroll

import java.time.OffsetDateTime

class PurchaseOrderMapperSpec extends Specification {

    PurchaseOrderMapper mapper

    def setup() {
        mapper = Mappers.getMapper(PurchaseOrderMapper)
    }

    // ==================== toDtoFromFlat ====================

    def "toDtoFromFlat maps all fields correctly"() {
        given: "a flat record with all fields populated"
            def flatRecord = new PurchaseOrderFlatItemsRecord()
            flatRecord.purchaseOrderId = "po-123"
            flatRecord.companyId = 1L
            flatRecord.displayId = "PO-001"
            flatRecord.status = "pending"
            flatRecord.currencyCode = "USD"
            flatRecord.poMetadata = JSONB.valueOf('{"key":"value"}')
            flatRecord.vendorContact = createContactJsonb("Vendor Inc", "vendor@test.com")
            flatRecord.shipToContact = createContactJsonb("Ship Location", "ship@test.com")

        when: "mapping to DTO"
            def result = mapper.toDtoFromFlat(flatRecord)

        then: "all fields are mapped"
            with(result) {
                id == "po-123"
                companyId == 1L
                displayId == "PO-001"
                status == PurchaseOrderStatus.PENDING
                currencyCode == "USD"
                metadata != null
                metadata["key"] == "value"
                vendorContact != null
                vendorContact.name == "Vendor Inc"
                vendorContact.email == "vendor@test.com"
                shipToContact != null
                shipToContact.name == "Ship Location"
                shipToContact.email == "ship@test.com"
            }
    }

    def "toDtoFromFlat handles null contacts gracefully"() {
        given: "a flat record with null contacts"
            def flatRecord = new PurchaseOrderFlatItemsRecord()
            flatRecord.purchaseOrderId = "po-123"
            flatRecord.vendorContact = null
            flatRecord.shipToContact = null

        when: "mapping to DTO"
            def result = mapper.toDtoFromFlat(flatRecord)

        then: "contacts are null"
            result.vendorContact == null
            result.shipToContact == null
    }

    def "toDtoFromFlat handles empty JSONB contacts"() {
        given: "a flat record with empty JSONB"
            def flatRecord = new PurchaseOrderFlatItemsRecord()
            flatRecord.purchaseOrderId = "po-123"
            flatRecord.vendorContact = JSONB.valueOf('{}')
            flatRecord.shipToContact = JSONB.valueOf('  ')

        when: "mapping to DTO"
            def result = mapper.toDtoFromFlat(flatRecord)

        then: "empty object is deserialized to empty Contact or null for whitespace"
            result.vendorContact != null  // Empty JSON object deserializes to empty Contact
            result.shipToContact == null  // Whitespace returns null
    }

    // ==================== toItemDtoFromFlat ====================

    def "toItemDtoFromFlat maps all item fields correctly"() {
        given: "a flat record with item data"
            def flatRecord = new PurchaseOrderFlatItemsRecord()
            flatRecord.itemId = "item-1"
            flatRecord.name = "Widget"  // PurchaseOrderFlatItemsRecord has 'name' not 'description'
            flatRecord.quantity = 10
            flatRecord.unitPrice = new BigDecimal("100.50")
            flatRecord.unit = "EA"
            flatRecord.unitCode = "EA"
            flatRecord.lineItemRefId = "ref-123"
            flatRecord.lineAccountRefId = "acct-456"
            flatRecord.itemMetadata = JSONB.valueOf('{"color":"blue"}')
            flatRecord.itemNotes = "Special handling"

        when: "mapping to item DTO"
            def result = mapper.toItemDtoFromFlat(flatRecord)

        then: "all item fields are mapped"
            with(result) {
                id == "item-1"
                name == "Widget"  // PurchaseOrderItem uses 'name' not 'description'
                quantity == 10
                unitPrice == new BigDecimal("100.50")
                unit == "EA"
                unitCode == "EA"
                itemId == "ref-123"
                accountId == "acct-456"
                metadata != null
                metadata["color"] == "blue"
                notes == "Special handling"
            }
    }

    def "toItemDtoFromFlat handles null metadata and notes"() {
        given: "a flat record with null optional fields"
            def flatRecord = new PurchaseOrderFlatItemsRecord()
            flatRecord.itemId = "item-1"
            flatRecord.itemMetadata = null
            flatRecord.itemNotes = null

        when: "mapping to item DTO"
            def result = mapper.toItemDtoFromFlat(flatRecord)

        then: "optional fields are null"
            result.metadata == null
            result.notes == null
    }

    // ==================== mapStatus ====================

    @Unroll
    def "mapStatus converts '#statusString' to #expectedStatus"() {
        when: "mapping status string"
            def result = mapper.mapStatus(statusString)

        then: "correct enum is returned"
            result == expectedStatus

        where:
            statusString  | expectedStatus
            "pending"     | PurchaseOrderStatus.PENDING
            "in_progress" | PurchaseOrderStatus.IN_PROGRESS
            "completed"   | PurchaseOrderStatus.COMPLETED
            "cancelled"   | PurchaseOrderStatus.CANCELLED
            "PENDING"     | PurchaseOrderStatus.PENDING
            "Completed"   | PurchaseOrderStatus.COMPLETED
    }

    @Unroll
    def "mapStatus handles null and blank values: '#statusString'"() {
        when: "mapping null or blank status"
            def result = mapper.mapStatus(statusString)

        then: "null is returned"
            result == null

        where:
            statusString << [null, "", "  ", "\t"]
    }

    // ==================== mapStatusToString ====================

    @Unroll
    def "mapStatusToString converts #status to '#expectedString'"() {
        when: "mapping status enum to string"
            def result = mapper.mapStatusToString(status)

        then: "correct string is returned"
            result == expectedString

        where:
            status                           | expectedString
            PurchaseOrderStatus.PENDING      | "pending"
            PurchaseOrderStatus.IN_PROGRESS  | "in_progress"
            PurchaseOrderStatus.COMPLETED    | "completed"
            PurchaseOrderStatus.CANCELLED    | "cancelled"
            null                             | null
    }

    // ==================== fromFlatRecords ====================

    def "fromFlatRecords groups multiple items into single PO"() {
        given: "flat records with same PO but different items"
            def flatRecords = [
                createFlatRecordWithItem("po-1", 1L, "item-1", "Widget A", 5),
                createFlatRecordWithItem("po-1", 1L, "item-2", "Widget B", 10),
                createFlatRecordWithItem("po-1", 1L, "item-3", "Widget C", 3)
            ]

        when: "converting from flat records"
            def result = mapper.fromFlatRecords(flatRecords)

        then: "single PO with all items"
            result.size() == 1
            with(result[0]) {
                id == "po-1"
                items.size() == 3
                items[0].name == "Widget A"
                items[1].name == "Widget B"
                items[2].name == "Widget C"
            }
    }

    def "fromFlatRecords creates separate POs for different IDs"() {
        given: "flat records for multiple POs"
            def flatRecords = [
                createFlatRecordWithItem("po-1", 1L, "item-1", "Widget A", 5),
                createFlatRecordWithItem("po-2", 1L, "item-2", "Widget B", 10),
                createFlatRecordWithItem("po-3", 1L, "item-3", "Widget C", 3)
            ]

        when: "converting from flat records"
            def result = mapper.fromFlatRecords(flatRecords)

        then: "three separate POs"
            result.size() == 3
            result[0].id == "po-1"
            result[1].id == "po-2"
            result[2].id == "po-3"
            result.each { it.items.size() == 1 }
    }

    def "fromFlatRecords filters out null item IDs"() {
        given: "flat records with some null item IDs"
            def flatRecords = [
                createFlatRecordWithItem("po-1", 1L, null, "Header only", 0),
                createFlatRecordWithItem("po-1", 1L, "item-1", "Widget A", 5),
                createFlatRecordWithItem("po-1", 1L, null, "Another header", 0)
            ]

        when: "converting from flat records"
            def result = mapper.fromFlatRecords(flatRecords)

        then: "only records with item IDs are included"
            result.size() == 1
            result[0].items.size() == 1
            result[0].items[0].name == "Widget A"
    }

    @Unroll
    def "fromFlatRecords handles empty and null inputs: #scenario"() {
        when: "converting from flat records"
            def result = mapper.fromFlatRecords(flatRecords)

        then: "empty list is returned"
            result.isEmpty()

        where:
            scenario        | flatRecords
            "null list"     | null
            "empty list"    | []
    }

    def "fromFlatRecords preserves order via LinkedHashMap"() {
        given: "flat records in specific order"
            def flatRecords = [
                createFlatRecordWithItem("po-3", 1L, "item-3", "Third", 3),
                createFlatRecordWithItem("po-1", 1L, "item-1", "First", 1),
                createFlatRecordWithItem("po-2", 1L, "item-2", "Second", 2)
            ]

        when: "converting from flat records"
            def result = mapper.fromFlatRecords(flatRecords)

        then: "order is preserved based on first appearance"
            result.size() == 3
            result[0].id == "po-3"
            result[1].id == "po-1"
            result[2].id == "po-2"
    }

    // ==================== toDto (with items) ====================

    def "toDto maps record to DTO"() {
        given: "a purchase order record"
            def record = new PurchaseOrdersRecord()
            record.id = "po-123"
            record.companyId = 1L
            record.status = "in_progress"
            record.vendorContact = createContactJsonb("Vendor", "vendor@test.com")
            record.shipToContact = createContactJsonb("Ship", "ship@test.com")

        when: "mapping to DTO"
            def result = mapper.toDto(record, [])

        then: "all fields are mapped"
            with(result) {
                id == "po-123"
                companyId == 1L
                status == PurchaseOrderStatus.IN_PROGRESS
                vendorContact != null
                vendorContact.name == "Vendor"
                shipToContact != null
                shipToContact.name == "Ship"
                items == []  // AfterMapping ensures empty list not null
            }
    }

    // ==================== toDtoWithoutItems ====================

    def "toDtoWithoutItems maps record without items"() {
        given: "a purchase order record"
            def record = new PurchaseOrdersRecord()
            record.id = "po-123"
            record.status = "completed"
            record.vendorContact = createContactJsonb("Vendor", "vendor@test.com")

        when: "mapping without items"
            def result = mapper.toDtoWithoutItems(record)

        then: "fields are mapped"
            with(result) {
                id == "po-123"
                status == PurchaseOrderStatus.COMPLETED
                vendorContact != null
            }
    }

    // ==================== toDtoListWithoutItems ====================

    def "toDtoListWithoutItems maps multiple records"() {
        given: "multiple records"
            def records = [
                createRecord("po-1", 1L, "pending"),
                createRecord("po-2", 1L, "completed"),
                createRecord("po-3", 1L, "cancelled")
            ]

        when: "mapping list"
            def result = mapper.toDtoListWithoutItems(records)

        then: "all records are mapped"
            result.size() == 3
            result[0].id == "po-1"
            result[1].id == "po-2"
            result[2].id == "po-3"
    }

    @Unroll
    def "toDtoListWithoutItems handles empty and null: #scenario"() {
        when: "mapping list"
            def result = mapper.toDtoListWithoutItems(records)

        then: "empty list is returned"
            result.isEmpty()

        where:
            scenario        | records
            "null list"     | null
            "empty list"    | []
    }

    // ==================== toRecord (from create) ====================

    def "toRecord maps create request to record"() {
        given: "a create request"
            def companyId = 1L
            def projectId = "proj-123"
            def createRequest = new PurchaseOrderCreate()
            createRequest.displayId = "PO-001"
            createRequest.notes = "Test notes"
            createRequest.vendorContact = createContactDto("Vendor", "vendor@test.com")
            createRequest.shipToContact = createContactDto("Ship", "ship@test.com")

        when: "mapping to record"
            def result = mapper.toRecord(companyId, projectId, createRequest)

        then: "fields are mapped with defaults"
            with(result) {
                it.companyId == 1L
                it.projectId == "proj-123"
                displayId == "PO-001"
                notes == "Test notes"
                status == "pending"  // constant mapping
                vendorContact != null
                shipToContact != null
            }
    }

    def "toRecord handles null contacts in create request"() {
        given: "a create request with null contacts"
            def createRequest = new PurchaseOrderCreate()
            createRequest.vendorContact = null
            createRequest.shipToContact = null

        when: "mapping to record"
            def result = mapper.toRecord(1L, "proj-1", createRequest)

        then: "contacts are null"
            result.vendorContact == null
            result.shipToContact == null
    }

    // ==================== toRecord (from PurchaseOrder) ====================

    def "toRecord maps PurchaseOrder DTO to record"() {
        given: "a purchase order DTO"
            def po = new PurchaseOrder()
            po.id = "po-123"
            po.displayId = "PO-001"
            po.status = PurchaseOrderStatus.IN_PROGRESS
            po.notes = "Test notes"
            po.vendorContact = createContactDto("Vendor", "vendor@test.com")
            po.shipToContact = createContactDto("Ship", "ship@test.com")

        when: "mapping to record"
            def result = mapper.toRecord(po)

        then: "basic fields are mapped"
            with(result) {
                id == "po-123"
                // displayId is explicitly ignored in the mapper (@Mapping(target = "displayId", ignore = true))
                status == "in_progress"
                notes == "Test notes"
                // Contacts are serialized to JSONB
                vendorContact != null
                shipToContact != null
            }
    }

    // ==================== updateRecordFromDto ====================

    def "updateRecordFromDto updates mutable fields only"() {
        given: "an existing record and update DTO"
            def record = new PurchaseOrdersRecord()
            record.id = "po-123"
            record.companyId = 1L
            record.projectId = "proj-1"
            record.displayId = "PO-001"
            record.status = "pending"
            record.createdAt = OffsetDateTime.now()

            def updateDto = new PurchaseOrderUpdate()
            updateDto.displayId = "PO-002"
            updateDto.notes = "Updated notes"
            updateDto.vendorContact = createContactDto("New Vendor", "newvendor@test.com")
            updateDto.shipToContact = createContactDto("New Ship", "newship@test.com")
            updateDto.currencyCode = Currency.EUR

        when: "updating record from DTO"
            mapper.updateRecordFromDto(updateDto, record)

        then: "mutable fields are updated"
            with(record) {
                displayId == "PO-002"
                notes == "Updated notes"
                vendorContact != null
                shipToContact != null
                currencyCode == "EUR"
                // Immutable fields remain unchanged
                id == "po-123"
                companyId == 1L
                projectId == "proj-1"
                status == "pending"  // status is ignored in update
            }
    }

    def "updateRecordFromDto handles null values with IGNORE strategy"() {
        given: "an existing record and update DTO with nulls"
            def record = new PurchaseOrdersRecord()
            record.displayId = "PO-001"
            record.notes = "Original notes"

            def updateDto = new PurchaseOrderUpdate()
            updateDto.displayId = null
            updateDto.notes = null

        when: "updating record from DTO"
            mapper.updateRecordFromDto(updateDto, record)

        then: "null values are ignored"
            with(record) {
                displayId == "PO-001"  // unchanged
                notes == "Original notes"  // unchanged
            }
    }

    // ==================== JSONB mapping ====================

    def "map converts JSONB to Map"() {
        given: "a JSONB value"
            def jsonb = JSONB.valueOf('{"key1":"value1","key2":123}')

        when: "mapping to Map"
            def result = mapper.map(jsonb)

        then: "Map contains correct values"
            result["key1"] == "value1"
            result["key2"] == 123
    }

    def "map handles null JSONB"() {
        when: "mapping null JSONB"
            def result = mapper.map((JSONB) null)

        then: "null is returned"
            result == null
    }

    def "map handles empty JSONB string"() {
        given: "empty JSONB"
            def jsonb = JSONB.valueOf('  ')

        when: "mapping to Map"
            def result = mapper.map(jsonb)

        then: "null is returned"
            result == null
    }

    def "map converts Map to JSONB"() {
        given: "a Map"
            def map = ["key1": "value1", "key2": 123]

        when: "mapping to JSONB"
            def result = mapper.map(map)

        then: "JSONB contains correct JSON"
            result != null
            result.data().contains("key1")
            result.data().contains("value1")
    }

    def "map handles null Map"() {
        when: "mapping null Map"
            def result = mapper.map((Map<String, Object>) null)

        then: "null is returned"
            result == null
    }

    // ==================== jsonbToContact ====================

    def "jsonbToContact deserializes Contact from JSONB"() {
        given: "a JSONB with contact data"
            def jsonb = createContactJsonb("Test Contact", "test@test.com")

        when: "mapping to Contact"
            def result = mapper.jsonbToContact(jsonb)

        then: "Contact is deserialized"
            result.name == "Test Contact"
            result.email == "test@test.com"
    }

    def "jsonbToContact handles null JSONB"() {
        when: "mapping null JSONB"
            def result = mapper.jsonbToContact(null)

        then: "null is returned"
            result == null
    }

    def "jsonbToContact handles empty JSONB string"() {
        given: "empty JSONB"
            def jsonb = JSONB.valueOf('  ')

        when: "mapping to Contact"
            def result = mapper.jsonbToContact(jsonb)

        then: "null is returned"
            result == null
    }

    // ==================== contactToJsonb ====================

    def "contactToJsonb serializes Contact to JSONB"() {
        given: "a Contact object"
            def contact = createContactDto("Test Contact", "test@test.com")

        when: "mapping to JSONB"
            def result = mapper.contactToJsonb(contact)

        then: "JSONB contains serialized Contact"
            result != null
            result.data().contains("Test Contact")
            result.data().contains("test@test.com")
    }

    def "contactToJsonb handles null Contact"() {
        when: "mapping null Contact"
            def result = mapper.contactToJsonb(null)

        then: "null is returned"
            result == null
    }

    // ==================== stringToCurrency ====================

    @Unroll
    def "stringToCurrency converts '#currencyCode' to #expectedCurrency"() {
        when: "mapping currency code"
            def result = mapper.stringToCurrency(currencyCode)

        then: "correct enum is returned"
            result == expectedCurrency

        where:
            currencyCode | expectedCurrency
            "USD"        | Currency.USD
            "EUR"        | Currency.EUR
            "GBP"        | Currency.GBP
            "CAD"        | Currency.CAD
            "usd"        | Currency.USD  // case insensitive
            "Eur"        | Currency.EUR
    }

    @Unroll
    def "stringToCurrency handles null and blank: '#currencyCode'"() {
        when: "mapping null or blank currency"
            def result = mapper.stringToCurrency(currencyCode)

        then: "null is returned"
            result == null

        where:
            currencyCode << [null, "", "  ", "\t"]
    }

    def "stringToCurrency handles unknown currency code"() {
        when: "mapping unknown code"
            def result = mapper.stringToCurrency("XXX")

        then: "null is returned"
            result == null
    }

    // ==================== currencyToString ====================

    @Unroll
    def "currencyToString converts #currency to '#expectedCode'"() {
        when: "mapping currency to string"
            def result = mapper.currencyToString(currency)

        then: "correct code is returned"
            result == expectedCode

        where:
            currency      | expectedCode
            Currency.USD  | "USD"
            Currency.EUR  | "EUR"
            Currency.GBP  | "GBP"
            Currency.CAD  | "CAD"
            null          | null
    }

    // ==================== Helper Methods ====================

    private PurchaseOrderFlatItemsRecord createFlatRecordWithItem(String poId, Long companyId, String itemId, String description, int quantity) {
        def record = new PurchaseOrderFlatItemsRecord()
        record.purchaseOrderId = poId
        record.companyId = companyId
        record.itemId = itemId
        record.name = description  // PurchaseOrderFlatItemsRecord has 'name' not 'description'
        record.quantity = quantity
        record.status = "pending"
        return record
    }

    private PurchaseOrdersRecord createRecord(String id, Long companyId, String status) {
        def record = new PurchaseOrdersRecord()
        record.id = id
        record.companyId = companyId
        record.status = status
        return record
    }

    private PurchaseOrderItem createItemDto(String id, String description, int quantity) {
        def item = new PurchaseOrderItem()
        item.id = id
        item.description = description
        item.quantity = quantity
        return item
    }

    private Contact createContactDto(String name, String email) {
        def contact = new Contact()
        contact.name = name
        contact.email = email
        return contact
    }

    private JSONB createContactJsonb(String name, String email) {
        def objectMapper = new ObjectMapper()
        def contact = ["name": name, "email": email]
        return JSONB.valueOf(objectMapper.writeValueAsString(contact))
    }
}
