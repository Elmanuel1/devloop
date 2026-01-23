package com.tosspaper.integrations.quickbooks.item

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.ItemTypeEnum
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import spock.lang.Specification
import spock.lang.Subject

class ItemMapperSpec extends Specification {

    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())

    @Subject
    ItemMapper itemMapper = new ItemMapper(objectMapper)

    // ==================== toDomain Tests ====================

    def "should map full Inventory Item to domain Item"() {
        given:
        def qboItem = QBOTestFixtures.loadItem()

        when:
        def item = itemMapper.toDomain(qboItem)

        then:
        item != null

        and: "basic fields are mapped"
        item.id == "21"
        item.name == "Industrial Valve Model X500"
        item.code == "VALVE-X500-001"
        item.description == "High-pressure industrial valve for manufacturing applications. NSF certified."

        and: "type and flags are mapped"
        item.type == "Inventory"
        item.active == true
        item.taxable == true

        and: "pricing is mapped"
        item.unitPrice == 299.99
        item.purchaseCost == 150.00

        and: "inventory tracking is mapped"
        item.quantityOnHand == 150

        and: "provider tracking fields are mapped"
        item.provider == IntegrationProvider.QUICKBOOKS.value
        item.externalId == "21"
        item.providerVersion == "4"
        item.providerCreatedAt != null
        item.providerLastUpdatedAt != null

        and: "external metadata contains original QBO entity that can be deserialized"
        item.externalMetadata != null
        item.externalMetadata.containsKey('qboEntity')
        
        and: "the stored QBO entity can be deserialized back to Item"
        def storedJson = item.externalMetadata.get('qboEntity') as String
        def deserializedItem = objectMapper.readValue(storedJson, com.intuit.ipp.data.Item)
        deserializedItem.id == "21"
        deserializedItem.syncToken == "4"
        deserializedItem.name == "Industrial Valve Model X500"
        
        and: "QBO-only fields are preserved in stored entity"
        deserializedItem.fullyQualifiedName == "Equipment:Valves:Industrial Valve Model X500"
        deserializedItem.salesTaxIncluded == false
        deserializedItem.purchaseTaxIncluded == false
        deserializedItem.trackQtyOnHand == true
        deserializedItem.reorderPoint == 20
        deserializedItem.invStartDate != null
        deserializedItem.level == 2
        deserializedItem.subItem == true
        
        and: "account references are preserved"
        deserializedItem.incomeAccountRef?.value == "79"
        deserializedItem.incomeAccountRef?.name == "Sales of Product Income"
        deserializedItem.expenseAccountRef?.value == "80"
        deserializedItem.expenseAccountRef?.name == "Cost of Goods Sold"
        deserializedItem.assetAccountRef?.value == "81"
        deserializedItem.assetAccountRef?.name == "Inventory Asset"
    }

    def "should map Service Item to domain Item"() {
        given:
        def qboItem = QBOTestFixtures.loadItemService()

        when:
        def item = itemMapper.toDomain(qboItem)

        then:
        item != null
        item.id == "35"
        item.name == "Installation Service"
        item.code == null  // Service items often don't have SKU
        item.type == "Service"
        item.taxable == false
        item.unitPrice == 150.00
        item.purchaseCost == null  // Service item doesn't have purchase cost
    }

    def "should map NonInventory Item to domain Item"() {
        given:
        def qboItem = QBOTestFixtures.loadItemNonInventory()

        when:
        def item = itemMapper.toDomain(qboItem)

        then:
        item != null
        item.id == "45"
        item.name == "Custom Fabrication Materials"
        item.code == "CUSTOM-FAB-001"
        item.type == "NonInventory"
        item.taxable == true
        item.unitPrice == BigDecimal.ZERO
        item.purchaseCost == BigDecimal.ZERO
    }

    def "should return null for Category items"() {
        given: "a Category type item that cannot be used in transactions"
        def qboItem = QBOTestFixtures.loadItemCategory()

        when:
        def item = itemMapper.toDomain(qboItem)

        then: "Category items are filtered out"
        item == null
    }

    def "should return null when item is null"() {
        expect:
        itemMapper.toDomain(null) == null
    }

    // ==================== toQboItem Tests ====================

    def "should convert Item to QBO Item for CREATE"() {
        given:
        def item = Item.builder()
                .name("New Test Item")
                .code("NEW-ITEM-001")
                .description("Test item description")
                .type("Inventory")
                .unitPrice(new BigDecimal("99.99"))
                .purchaseCost(new BigDecimal("50.00"))
                .active(true)
                .taxable(true)
                .build()

        when:
        def qboItem = itemMapper.toQboItem(item)

        then:
        qboItem != null
        qboItem.name == "New Test Item"
        qboItem.sku == "NEW-ITEM-001"
        qboItem.description == "Test item description"
        qboItem.type == ItemTypeEnum.INVENTORY
        qboItem.unitPrice == 99.99
        qboItem.purchaseCost == 50.00
        qboItem.active == true
        qboItem.taxable == true

        and: "no Id or SyncToken for CREATE"
        qboItem.id == null
        qboItem.syncToken == null
    }

    def "should convert Item to QBO Item for UPDATE"() {
        given:
        def item = Item.builder()
                .name("Updated Item Name")
                .code("UPD-ITEM-001")
                .description("Updated description")
                .type("Service")
                .unitPrice(new BigDecimal("150.00"))
                .active(true)
                .build()
        item.externalId = "1001"
        item.providerVersion = "4"

        when:
        def qboItem = itemMapper.toQboItem(item)

        then:
        qboItem != null
        qboItem.name == "Updated Item Name"
        qboItem.sku == "UPD-ITEM-001"
        qboItem.type == ItemTypeEnum.SERVICE

        and: "Id and SyncToken are set for UPDATE"
        qboItem.id == "1001"
        qboItem.syncToken == "4"
    }

    def "should preserve stored QBO entity fields during UPDATE - only override what we set"() {
        given: "an item that was previously synced from QBO"
        def originalQboItem = QBOTestFixtures.loadItem()
        def item = itemMapper.toDomain(originalQboItem)

        and: "only specific fields are modified"
        item.name = "Modified Item Name"
        item.unitPrice = new BigDecimal("500.00")
        // Note: we are NOT modifying code, description, type, etc.

        when:
        def qboItem = itemMapper.toQboItem(item)

        then: "modified fields are applied"
        qboItem.name == "Modified Item Name"
        qboItem.unitPrice == 500.00

        and: "Id and SyncToken are preserved"
        qboItem.id == "21"
        qboItem.syncToken == "4"

        and: "QBO-only fields that we don't map to domain are preserved"
        qboItem.fullyQualifiedName == "Equipment:Valves:Industrial Valve Model X500"
        qboItem.trackQtyOnHand == true
        qboItem.reorderPoint == 20
        qboItem.level == 2
        qboItem.subItem == true

        and: "account references are preserved"
        qboItem.incomeAccountRef?.value == "79"
        qboItem.incomeAccountRef?.name == "Sales of Product Income"
        qboItem.expenseAccountRef?.value == "80"
        qboItem.expenseAccountRef?.name == "Cost of Goods Sold"
        qboItem.assetAccountRef?.value == "81"
        qboItem.assetAccountRef?.name == "Inventory Asset"

        and: "inventory tracking fields are preserved (we don't manage quantityOnHand)"
        qboItem.qtyOnHand == 150
    }

    def "should return null when item is null"() {
        expect:
        itemMapper.toQboItem(null) == null
    }

    // ==================== Round-trip Tests ====================

    def "should preserve all fields in complete round-trip (QBO -> Domain -> QBO)"() {
        given: "an item loaded from QBO with all fields"
        def originalQboItem = QBOTestFixtures.loadItem()

        when: "convert to domain and back without modifications"
        def domainItem = itemMapper.toDomain(originalQboItem)
        def roundTripQboItem = itemMapper.toQboItem(domainItem)

        then: "all critical fields are preserved"
        roundTripQboItem.id == originalQboItem.id
        roundTripQboItem.syncToken == originalQboItem.syncToken
        roundTripQboItem.name == originalQboItem.name
        roundTripQboItem.sku == originalQboItem.sku
        roundTripQboItem.description == originalQboItem.description
        roundTripQboItem.type == originalQboItem.type
        roundTripQboItem.active == originalQboItem.active
        roundTripQboItem.taxable == originalQboItem.taxable
        roundTripQboItem.unitPrice == originalQboItem.unitPrice
        roundTripQboItem.purchaseCost == originalQboItem.purchaseCost

        and: "QBO-managed fields are preserved"
        roundTripQboItem.fullyQualifiedName == originalQboItem.fullyQualifiedName
        roundTripQboItem.trackQtyOnHand == originalQboItem.trackQtyOnHand
        roundTripQboItem.qtyOnHand == originalQboItem.qtyOnHand
        roundTripQboItem.reorderPoint == originalQboItem.reorderPoint
        roundTripQboItem.level == originalQboItem.level
        roundTripQboItem.subItem == originalQboItem.subItem

        and: "all account references are preserved"
        roundTripQboItem.incomeAccountRef?.value == originalQboItem.incomeAccountRef?.value
        roundTripQboItem.incomeAccountRef?.name == originalQboItem.incomeAccountRef?.name
        roundTripQboItem.expenseAccountRef?.value == originalQboItem.expenseAccountRef?.value
        roundTripQboItem.expenseAccountRef?.name == originalQboItem.expenseAccountRef?.name
        roundTripQboItem.assetAccountRef?.value == originalQboItem.assetAccountRef?.value
        roundTripQboItem.assetAccountRef?.name == originalQboItem.assetAccountRef?.name
    }

    def "should only override domain-mapped fields when updating"() {
        given: "an item synced from QBO"
        def originalQboItem = QBOTestFixtures.loadItem()
        def item = itemMapper.toDomain(originalQboItem)

        and: "we update only specific domain fields"
        def newName = "Completely Different Item Name"
        def newCode = "NEW-SKU-999"
        def newDescription = "This is a completely new description"
        def newUnitPrice = new BigDecimal("999.99")
        
        item.name = newName
        item.code = newCode
        item.description = newDescription
        item.unitPrice = newUnitPrice

        when:
        def qboItem = itemMapper.toQboItem(item)

        then: "updated fields are changed"
        qboItem.name == newName
        qboItem.sku == newCode
        qboItem.description == newDescription
        qboItem.unitPrice == 999.99

        and: "but QBO-managed fields remain unchanged"
        qboItem.fullyQualifiedName == "Equipment:Valves:Industrial Valve Model X500"
        qboItem.trackQtyOnHand == true
        qboItem.qtyOnHand == 150
        qboItem.reorderPoint == 20
        qboItem.level == 2
        qboItem.subItem == true

        and: "account references are preserved (we don't change accounts)"
        qboItem.incomeAccountRef?.value == "79"
        qboItem.expenseAccountRef?.value == "80"
        qboItem.assetAccountRef?.value == "81"
    }
}
