package com.tosspaper.integrations.quickbooks.purchaseorder

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.PurchaseOrderStatusEnum
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.*
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import java.time.LocalDate

class QBOPurchaseOrderMapperSpec extends Specification {

    @Subject
    QBOPurchaseOrderMapper mapper = new QBOPurchaseOrderMapper()

    static final String CONNECTION_ID = "conn-123"

    // ==================== toDomain Tests ====================

    def "should map full PurchaseOrder to domain with all fields"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrder()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        po != null

        and: "basic fields are mapped"
        po.displayId == "PO-2024-1001"
        po.status == PurchaseOrderStatus.OPEN
        po.orderDate == LocalDate.of(2024, 11, 1)
        po.dueDate == LocalDate.of(2024, 12, 1)
        po.notes.contains("Rush order for Q4 production")
        po.notes.contains("Purchase Order for industrial valves")

        and: "provider tracking fields are mapped"
        po.externalId == "1001"
        po.providerVersion == "3"
        po.provider == IntegrationProvider.QUICKBOOKS.value
        po.providerCreatedAt != null
        po.providerLastUpdatedAt != null

        and: "currency is mapped"
        po.currencyCode == Currency.USD

        and: "vendor contact is mapped"
        po.vendorContact != null
        po.vendorContact.externalId == "56"
        po.vendorContact.name == "Thompson Industrial Supplies"
        po.vendorContact.tag == PartyTag.SUPPLIER

        and: "ship-to contact is mapped (Job Location prefix is stripped)"
        po.shipToContact != null
        po.shipToContact.externalId == "123"
        po.shipToContact.name == "Downtown Office Complex"
        po.shipToContact.tag == PartyTag.SHIP_TO
        po.shipToContact.address != null

        and: "line items are mapped"
        po.items != null
        po.items.size() == 2
        po.itemsCount == 2

        and: "external metadata contains original QBO entity that can be deserialized"
        po.externalMetadata != null
        po.externalMetadata.containsKey('qboEntity')
        
        and: "the stored QBO entity can be deserialized back to PurchaseOrder"
        // Use plain ObjectMapper (same as mapper uses internally for storage)
        def objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
        def storedJson = po.externalMetadata.get('qboEntity') as String
        def deserializedPo = objectMapper.readValue(storedJson, com.intuit.ipp.data.PurchaseOrder)
        deserializedPo.id == "1001"
        deserializedPo.syncToken == "3"
        deserializedPo.docNumber == "PO-2024-1001"
        
        and: "QBO-only fields are preserved in stored entity"
        deserializedPo.totalAmt == 4500.00
        deserializedPo.exchangeRate == 1
        deserializedPo.emailStatus != null
        deserializedPo.POEmail?.address == "purchasing@ourcompany.com"
        
        and: "references are preserved"
        deserializedPo.APAccountRef?.value == "33"
        deserializedPo.APAccountRef?.name == "Accounts Payable"
        deserializedPo.salesTermRef?.value == "3"
        deserializedPo.salesTermRef?.name == "Net 30"
        deserializedPo.shipMethodRef?.value == "1"
        deserializedPo.shipMethodRef?.name == "Ground Shipping"
        deserializedPo.classRef?.value == "100000000000005"
        deserializedPo.classRef?.name == "Manufacturing"
        
        and: "custom fields are preserved"
        deserializedPo.customField != null
        deserializedPo.customField.size() == 1
        
        and: "linked transactions are preserved"
        deserializedPo.linkedTxn != null
        deserializedPo.linkedTxn.size() == 1
        deserializedPo.linkedTxn[0].txnId == "5001"
        deserializedPo.linkedTxn[0].txnType == "Bill"

        and: "metadata contains custom fields"
        po.metadata != null
        po.metadata.containsKey('customFields')
    }

    def "should map closed PurchaseOrder with CLOSED status"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderClosed()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        po != null
        po.status == PurchaseOrderStatus.CLOSED
        po.displayId == "PO-2024-1002"
        po.vendorContact.externalId == "42"
        po.vendorContact.name == "Old Supplies Co"
    }

    def "should map minimal PurchaseOrder"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderMinimal()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        po != null
        po.displayId == "PO-2024-1003"
        po.status == PurchaseOrderStatus.OPEN
        po.vendorContact.externalId == "99"
        po.items.size() == 1
    }

    def "should map account-based PurchaseOrder with CAD currency"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderAccountBased()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        po != null
        po.displayId == "PO-2024-1004"
        po.currencyCode == Currency.CAD

        and: "account-based line items are mapped (2 account-based + 1 item-based)"
        po.items.size() == 3
        po.items.every { it.externalAccountId != null || it.externalItemId != null }
    }

    def "should use default currency when PO has no CurrencyRef"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderMinimal()
        qboPo.currencyRef = null

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.CAD)

        then:
        po.currencyCode == Currency.CAD
    }

    def "should default to USD when no currency available"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderMinimal()
        qboPo.currencyRef = null

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, null)

        then:
        po.currencyCode == Currency.USD
    }

    def "should return null when qboPo is null"() {
        expect:
        mapper.toDomain(null, CONNECTION_ID, Currency.USD) == null
    }

    // ==================== Line Item Mapping Tests ====================

    def "should map ItemBasedExpenseLineDetail correctly"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrder()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        def itemLine = po.items.find { it.id == "1" }
        itemLine != null
        itemLine.name == "Industrial Valve Model X500 - High-pressure valve for manufacturing"
        itemLine.totalPrice == 3000.00
        itemLine.unitPrice == 300.00
        itemLine.quantity == 10
        itemLine.externalItemId == "21"
        itemLine.taxable == true  // TaxCodeRef value is "TAX"

        and: "metadata contains qboLine for round-trip"
        itemLine.metadata != null
        itemLine.metadata.containsKey('qboLine')
    }

    def "should map AccountBasedExpenseLineDetail correctly"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrderAccountBased()  // Use fixture with account-based lines

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then:
        def accountLine = po.items.find { it.id == "1" }
        accountLine != null
        accountLine.name == "Consulting services for equipment installation planning"
        accountLine.totalPrice == 2000.00
        accountLine.externalAccountId == "95"
        accountLine.quantity == 0
        accountLine.unitPrice == BigDecimal.ZERO
    }

    def "should map taxable flag based on TaxCodeRef"() {
        given:
        def qboPo = QBOTestFixtures.loadPurchaseOrder()

        when:
        def po = mapper.toDomain(qboPo, CONNECTION_ID, Currency.USD)

        then: "first line has TaxCodeRef 'TAX' -> taxable = true"
        def taxableLine = po.items.find { it.id == "1" }
        taxableLine.taxable == true

        and: "second line has no TaxCodeRef -> defaults to taxable = false"
        def nonTaxableLine = po.items.find { it.id == "2" }
        nonTaxableLine.taxable == false
    }

    // ==================== toQboPurchaseOrder Tests ====================

    def "should convert domain PO to QBO PurchaseOrder for CREATE"() {
        given:
        def vendorContact = Party.builder()
                .name("Test Vendor")
                .tag(PartyTag.SUPPLIER)
                .build()
        vendorContact.externalId = "56"

        def shipToContact = Party.builder()
                .name("Test Ship To")
                .tag(PartyTag.SHIP_TO)
                .address(Address.builder()
                        .address("123 Ship Street")
                        .city("Ship City")
                        .stateOrProvince("CA")
                        .postalCode("12345")
                        .build())
                .build()
        shipToContact.externalId = "123"

        def items = [
                PurchaseOrderItem.builder()
                        .name("Test Item")
                        .quantity(5)
                        .unitPrice(new BigDecimal("100.00"))
                        .totalPrice(new BigDecimal("500.00"))
                        .externalItemId("1001")
                        .metadata(['qboLine': '{}'])
                        .build()
        ]

        def po = PurchaseOrder.builder()
                .displayId("PO-NEW-001")
                .orderDate(LocalDate.of(2024, 12, 20))
                .dueDate(LocalDate.of(2025, 1, 20))
                .notes("Test notes")
                .status(PurchaseOrderStatus.OPEN)
                .currencyCode(Currency.USD)
                .vendorContact(vendorContact)
                .shipToContact(shipToContact)
                .items(items)
                .build()

        when:
        def qboPo = mapper.toQboPurchaseOrder(po)

        then:
        qboPo != null
        qboPo.docNumber == "PO-NEW-001"
        qboPo.privateNote == "Test notes"

        and: "vendor ref is set"
        qboPo.vendorRef?.value == "56"

        and: "ship-to is set"
        qboPo.shipTo?.value == "123"
        qboPo.shipAddr != null

        and: "currency is set"
        qboPo.currencyRef?.value == "USD"

        and: "status is mapped"
        qboPo.POStatus == PurchaseOrderStatusEnum.OPEN

        and: "lines are mapped"
        qboPo.line != null
        qboPo.line.size() == 1
    }

    def "should convert domain PO to QBO PurchaseOrder for UPDATE"() {
        given:
        def vendorContact = Party.builder()
                .name("Test Vendor")
                .build()
        vendorContact.externalId = "56"

        def po = PurchaseOrder.builder()
                .displayId("PO-UPD-001")
                .vendorContact(vendorContact)
                .items([])
                .build()
        po.externalId = "148"
        po.providerVersion = "5"

        when:
        def qboPo = mapper.toQboPurchaseOrder(po)

        then:
        qboPo.id == "148"
        qboPo.syncToken == "5"
    }

    def "should throw when vendorContact is null"() {
        given:
        def po = PurchaseOrder.builder()
                .displayId("PO-TEST")
                .build()

        when:
        mapper.toQboPurchaseOrder(po)

        then:
        thrown(IllegalStateException)
    }

    def "should throw when vendorContact has no externalId"() {
        given:
        def vendorContact = Party.builder().name("No External ID").build()
        def po = PurchaseOrder.builder()
                .displayId("PO-TEST")
                .vendorContact(vendorContact)
                .build()

        when:
        mapper.toQboPurchaseOrder(po)

        then:
        thrown(IllegalStateException)
    }

    // ==================== Status Mapping Tests ====================

    @Unroll
    def "should map domain status #domainStatus to QBO status #expectedQboStatus"() {
        expect:
        mapper.mapDomainStatusToQbo(domainStatus) == expectedQboStatus

        where:
        domainStatus                  | expectedQboStatus
        PurchaseOrderStatus.OPEN      | PurchaseOrderStatusEnum.OPEN
        PurchaseOrderStatus.PENDING   | PurchaseOrderStatusEnum.OPEN
        PurchaseOrderStatus.IN_PROGRESS | PurchaseOrderStatusEnum.OPEN
        PurchaseOrderStatus.CLOSED    | PurchaseOrderStatusEnum.CLOSED
        PurchaseOrderStatus.COMPLETED | PurchaseOrderStatusEnum.CLOSED
        PurchaseOrderStatus.CANCELLED | PurchaseOrderStatusEnum.CLOSED
        null                          | null
    }

    @Unroll
    def "should map QBO status #qboStatus to domain status #expectedDomainStatus"() {
        expect:
        mapper.mapStatus(qboStatus) == expectedDomainStatus

        where:
        qboStatus                     | expectedDomainStatus
        PurchaseOrderStatusEnum.OPEN  | PurchaseOrderStatus.OPEN
        PurchaseOrderStatusEnum.CLOSED | PurchaseOrderStatus.CLOSED
        null                          | PurchaseOrderStatus.OPEN
    }

    // ==================== Round-trip Tests ====================

    def "should preserve all fields in complete round-trip (QBO -> Domain -> QBO)"() {
        given: "a PO loaded from QBO with all fields"
        def originalQboPo = QBOTestFixtures.loadPurchaseOrder()

        when: "convert to domain and back without modifications"
        def domainPo = mapper.toDomain(originalQboPo, CONNECTION_ID, Currency.USD)
        def roundTripQboPo = mapper.toQboPurchaseOrder(domainPo)

        then: "all critical fields are preserved"
        roundTripQboPo.id == originalQboPo.id
        roundTripQboPo.syncToken == originalQboPo.syncToken
        roundTripQboPo.docNumber == originalQboPo.docNumber

        and: "references are preserved"
        roundTripQboPo.vendorRef?.value == originalQboPo.vendorRef?.value
        roundTripQboPo.shipTo?.value == originalQboPo.shipTo?.value
        roundTripQboPo.currencyRef?.value == originalQboPo.currencyRef?.value
        roundTripQboPo.APAccountRef?.value == originalQboPo.APAccountRef?.value
        roundTripQboPo.salesTermRef?.value == originalQboPo.salesTermRef?.value
        roundTripQboPo.shipMethodRef?.value == originalQboPo.shipMethodRef?.value
        roundTripQboPo.classRef?.value == originalQboPo.classRef?.value

        and: "QBO-only fields are preserved"
        roundTripQboPo.totalAmt == originalQboPo.totalAmt
        roundTripQboPo.exchangeRate == originalQboPo.exchangeRate
        roundTripQboPo.POEmail?.address == originalQboPo.POEmail?.address

        and: "custom fields are preserved"
        roundTripQboPo.customField?.size() == originalQboPo.customField?.size()

        and: "linked transactions are preserved"
        roundTripQboPo.linkedTxn?.size() == originalQboPo.linkedTxn?.size()
        if (originalQboPo.linkedTxn) {
            roundTripQboPo.linkedTxn[0].txnId == originalQboPo.linkedTxn[0].txnId
            roundTripQboPo.linkedTxn[0].txnType == originalQboPo.linkedTxn[0].txnType
        }

        and: "addresses are preserved"
        roundTripQboPo.vendorAddr?.line1 == originalQboPo.vendorAddr?.line1
        roundTripQboPo.vendorAddr?.city == originalQboPo.vendorAddr?.city
    }

    def "should preserve QBO-only fields when updating domain fields"() {
        given: "a PO synced from QBO"
        def originalQboPo = QBOTestFixtures.loadPurchaseOrder()
        def domainPo = mapper.toDomain(originalQboPo, CONNECTION_ID, Currency.USD)

        and: "we update only specific domain fields"
        domainPo.displayId = "PO-MODIFIED-001"
        domainPo.notes = "Modified notes only"
        domainPo.status = PurchaseOrderStatus.CLOSED

        when:
        def roundTripQboPo = mapper.toQboPurchaseOrder(domainPo)

        then: "updated fields are changed"
        roundTripQboPo.docNumber == "PO-MODIFIED-001"
        roundTripQboPo.privateNote == "Modified notes only"
        roundTripQboPo.POStatus == PurchaseOrderStatusEnum.CLOSED

        and: "but QBO-managed fields remain unchanged"
        roundTripQboPo.totalAmt == originalQboPo.totalAmt
        roundTripQboPo.exchangeRate == originalQboPo.exchangeRate

        and: "references that we don't modify are preserved"
        roundTripQboPo.APAccountRef?.value == originalQboPo.APAccountRef?.value
        roundTripQboPo.APAccountRef?.name == originalQboPo.APAccountRef?.name
        roundTripQboPo.salesTermRef?.value == originalQboPo.salesTermRef?.value
        roundTripQboPo.shipMethodRef?.value == originalQboPo.shipMethodRef?.value
        roundTripQboPo.classRef?.value == originalQboPo.classRef?.value

        and: "custom fields are preserved"
        roundTripQboPo.customField?.size() == originalQboPo.customField?.size()
        roundTripQboPo.customField?.find { it.definitionId == "1" }?.stringValue == "PRJ-2024-XYZ"

        and: "linked transactions are preserved"
        roundTripQboPo.linkedTxn?.size() == originalQboPo.linkedTxn?.size()

        and: "POEmail is preserved"
        roundTripQboPo.POEmail?.address == originalQboPo.POEmail?.address
    }

    def "should preserve line item QBO fields in round-trip"() {
        given: "a PO with complex line items"
        def originalQboPo = QBOTestFixtures.loadPurchaseOrder()
        def domainPo = mapper.toDomain(originalQboPo, CONNECTION_ID, Currency.USD)

        and: "we modify one line item's domain fields"
        def firstItem = domainPo.items.find { it.id == "1" }
        firstItem.name = "Modified description"
        firstItem.quantity = 20
        firstItem.unitPrice = new BigDecimal("550.00")

        when:
        def roundTripQboPo = mapper.toQboPurchaseOrder(domainPo)
        def roundTripFirstLine = roundTripQboPo.line.find { it.id == "1" }

        then: "modified fields are changed"
        roundTripFirstLine.description == "Modified description"
        roundTripFirstLine.itemBasedExpenseLineDetail?.qty == 20
        roundTripFirstLine.itemBasedExpenseLineDetail?.unitPrice == 550.00

        and: "but QBO-only line fields are preserved"
        roundTripFirstLine.lineNum == originalQboPo.line.find { it.id == "1" }.lineNum
        roundTripFirstLine.itemBasedExpenseLineDetail?.itemRef?.value == "21"
        roundTripFirstLine.itemBasedExpenseLineDetail?.itemRef?.name == "Industrial Valve Model X500"
        roundTripFirstLine.itemBasedExpenseLineDetail?.taxCodeRef?.value == "TAX"
        roundTripFirstLine.itemBasedExpenseLineDetail?.customerRef?.value == "123"
        roundTripFirstLine.itemBasedExpenseLineDetail?.billableStatus != null
    }

    // ==================== Helper Method Tests ====================

    def "should combine notes from privateNote and memo"() {
        expect:
        mapper.combineNotes("Private", "Memo") == "Private\nMemo"
        mapper.combineNotes("Private", null) == "Private"
        mapper.combineNotes(null, "Memo") == "Memo"
        mapper.combineNotes(null, null) == null
    }

    // ==================== mapReference Tests ====================

    def "should map reference with value and name"() {
        given:
            def ref = new com.intuit.ipp.data.ReferenceType()
            ref.value = "42"
            ref.name = "Test Ref"

        when:
            def result = mapper.mapReference(ref)

        then:
            result != null
            result.value == "42"
            result.name == "Test Ref"
    }

    def "should map reference with value only"() {
        given:
            def ref = new com.intuit.ipp.data.ReferenceType()
            ref.value = "42"

        when:
            def result = mapper.mapReference(ref)

        then:
            result != null
            result.value == "42"
            !result.containsKey("name")
    }

    def "should return null for null reference"() {
        expect:
            mapper.mapReference(null) == null
    }

    def "should return null for empty reference"() {
        given:
            def ref = new com.intuit.ipp.data.ReferenceType()

        expect:
            mapper.mapReference(ref) == null
    }

    // ==================== dateToLocalDate / dateToOffsetDateTime Tests ====================

    def "should convert Date to OffsetDateTime"() {
        given:
            def date = new Date(1704067200000L) // 2024-01-01T00:00:00Z

        when:
            def result = mapper.dateToLocalDate(date)

        then:
            result != null
    }

    def "should return null for null Date in dateToLocalDate"() {
        expect:
            mapper.dateToLocalDate(null) == null
    }

    def "should convert Date to LocalDate"() {
        given:
            def date = new Date(1704067200000L)

        when:
            def result = mapper.dateToOffsetDateTime(date)

        then:
            result != null
            result == LocalDate.of(2024, 1, 1)
    }

    def "should return null for null Date in dateToOffsetDateTime"() {
        expect:
            mapper.dateToOffsetDateTime(null) == null
    }

    // ==================== mapPhysicalAddressToAddress Tests ====================

    def "should map PhysicalAddress with all fields"() {
        given:
            def physAddr = new com.intuit.ipp.data.PhysicalAddress()
            physAddr.line1 = "123 Main St"
            physAddr.line2 = "Suite 100"
            physAddr.city = "Austin"
            physAddr.countrySubDivisionCode = "TX"
            physAddr.postalCode = "78701"
            physAddr.country = "US"

        when:
            def result = mapper.mapPhysicalAddressToAddress(physAddr)

        then:
            result != null
            result.address == "123 Main St, Suite 100"
            result.city == "Austin"
            result.stateOrProvince == "TX"
            result.postalCode == "78701"
            result.country == "US"
    }

    def "should map PhysicalAddress with only Line1"() {
        given:
            def physAddr = new com.intuit.ipp.data.PhysicalAddress()
            physAddr.line1 = "123 Main St"

        when:
            def result = mapper.mapPhysicalAddressToAddress(physAddr)

        then:
            result != null
            result.address == "123 Main St"
    }

    def "should return null for null PhysicalAddress"() {
        expect:
            mapper.mapPhysicalAddressToAddress(null) == null
    }

    // ==================== mapAddressToPhysicalAddress Tests ====================

    def "should map Address to PhysicalAddress"() {
        given:
            def address = Address.builder()
                .address("456 Oak Ave")
                .city("Dallas")
                .stateOrProvince("TX")
                .postalCode("75201")
                .country("US")
                .build()

        when:
            def result = mapper.mapAddressToPhysicalAddress(address)

        then:
            result != null
            result.line1 == "456 Oak Ave"
            result.city == "Dallas"
            result.countrySubDivisionCode == "TX"
            result.postalCode == "75201"
            result.country == "US"
    }

    def "should return null for null Address"() {
        expect:
            mapper.mapAddressToPhysicalAddress(null) == null
    }

    // ==================== toQboPurchaseOrder - null input ====================

    def "should return null when domain PO is null"() {
        expect:
            mapper.toQboPurchaseOrder(null) == null
    }

    // ==================== deserializeStoredQboPurchaseOrder Tests ====================

    def "should return empty PO when externalMetadata is null"() {
        given:
            def po = PurchaseOrder.builder().build()

        when:
            def result = mapper.deserializeStoredQboPurchaseOrder(po)

        then:
            result != null
            result.id == null
    }

    def "should return empty PO when qboEntity key is missing"() {
        given:
            def po = PurchaseOrder.builder().build()
            po.externalMetadata = [someOtherKey: "value"]

        when:
            def result = mapper.deserializeStoredQboPurchaseOrder(po)

        then:
            result != null
            result.id == null
    }

    def "should return empty PO when qboEntity is invalid JSON"() {
        given:
            def po = PurchaseOrder.builder().build()
            po.externalMetadata = [qboEntity: "not valid json"]

        when:
            def result = mapper.deserializeStoredQboPurchaseOrder(po)

        then:
            result != null
            result.id == null
    }

    // ==================== deserializeStoredQboLine Tests ====================

    def "should return empty Line when metadata is null"() {
        given:
            def item = PurchaseOrderItem.builder().build()

        when:
            def result = mapper.deserializeStoredQboLine(item)

        then:
            result != null
    }

    def "should throw when metadata has no qboLine and no external references"() {
        given:
            def item = PurchaseOrderItem.builder()
                .metadata([someKey: "value"])
                .build()

        when:
            mapper.deserializeStoredQboLine(item)

        then:
            thrown(IllegalStateException)
    }

    def "should restore line from externalItemId when qboLine is missing"() {
        given:
            def item = PurchaseOrderItem.builder()
                .externalItemId("item-42")
                .metadata([someKey: "value"])
                .build()

        when:
            def result = mapper.deserializeStoredQboLine(item)

        then:
            result != null
            result.detailType == com.intuit.ipp.data.LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL
            result.itemBasedExpenseLineDetail != null
            result.itemBasedExpenseLineDetail.itemRef.value == "item-42"
    }

    def "should restore line from externalAccountId when qboLine is missing"() {
        given:
            def item = PurchaseOrderItem.builder()
                .externalAccountId("acct-99")
                .metadata([someKey: "value"])
                .build()

        when:
            def result = mapper.deserializeStoredQboLine(item)

        then:
            result != null
            result.detailType == com.intuit.ipp.data.LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL
            result.accountBasedExpenseLineDetail != null
            result.accountBasedExpenseLineDetail.accountRef.value == "acct-99"
    }

    def "should return empty Line when qboLine is invalid JSON"() {
        given:
            def item = PurchaseOrderItem.builder()
                .externalItemId("item-42")
                .metadata([qboLine: "invalid json"])
                .build()

        when:
            def result = mapper.deserializeStoredQboLine(item)

        then:
            result != null
            result.detailType == com.intuit.ipp.data.LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL
    }

    // ==================== mapToQboLine Tests ====================

    def "should map PurchaseOrderItem to line with totalPrice"() {
        given:
            def item = PurchaseOrderItem.builder()
                .name("Test Item")
                .totalPrice(new BigDecimal("500.00"))
                .externalItemId("item-1")
                .metadata([someKey: "value"])
                .build()

        when:
            def result = mapper.mapToQboLine(item)

        then:
            result != null
            result.description == "Test Item"
            result.amount == new BigDecimal("500.00")
    }

    def "should compute amount from unitPrice and quantity when totalPrice is null"() {
        given:
            def item = PurchaseOrderItem.builder()
                .name("Calculated Item")
                .unitPrice(new BigDecimal("25.00"))
                .quantity(4)
                .externalItemId("item-2")
                .metadata([someKey: "value"])
                .build()

        when:
            def result = mapper.mapToQboLine(item)

        then:
            result != null
            result.amount == new BigDecimal("100.00")
    }

    // ==================== mapToQboLines Tests ====================

    def "should map list of PurchaseOrderItems to lines"() {
        given:
            def items = [
                PurchaseOrderItem.builder()
                    .name("Item A")
                    .totalPrice(new BigDecimal("100.00"))
                    .externalItemId("a")
                    .metadata([someKey: "value"])
                    .build(),
                PurchaseOrderItem.builder()
                    .name("Item B")
                    .totalPrice(new BigDecimal("200.00"))
                    .externalItemId("b")
                    .metadata([someKey: "value"])
                    .build()
            ]

        when:
            def result = mapper.mapToQboLines(items)

        then:
            result.size() == 2
            result[0].description == "Item A"
            result[1].description == "Item B"
    }

    // ==================== mapCustomFieldsToMetadata Tests ====================

    def "should map custom fields with name and string value"() {
        given:
            def cf = new com.intuit.ipp.data.CustomField()
            cf.definitionId = "1"
            cf.name = "Project"
            cf.stringValue = "PRJ-001"

        when:
            def result = mapper.mapCustomFieldsToMetadata([cf])

        then:
            result != null
            result.Project == "PRJ-001"
    }

    def "should use field_id as key when name is null"() {
        given:
            def cf = new com.intuit.ipp.data.CustomField()
            cf.definitionId = "2"
            cf.stringValue = "Some Value"

        when:
            def result = mapper.mapCustomFieldsToMetadata([cf])

        then:
            result != null
            result["field_2"] == "Some Value"
    }

    def "should return null for empty custom fields list"() {
        expect:
            mapper.mapCustomFieldsToMetadata([]) == null
    }

    def "should return null for null custom fields list"() {
        expect:
            mapper.mapCustomFieldsToMetadata(null) == null
    }

    def "should skip custom fields without definitionId"() {
        given:
            def cf = new com.intuit.ipp.data.CustomField()
            cf.stringValue = "value"

        when:
            def result = mapper.mapCustomFieldsToMetadata([cf])

        then:
            result == null
    }

    // ==================== buildMetadata Tests ====================

    def "should build metadata from QBO PO with all ref fields"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()
            qboPo.totalAmt = new BigDecimal("5000.00")
            qboPo.syncToken = "7"

            def apRef = new com.intuit.ipp.data.ReferenceType()
            apRef.value = "33"
            apRef.name = "AP"
            qboPo.APAccountRef = apRef

            def currRef = new com.intuit.ipp.data.ReferenceType()
            currRef.value = "USD"
            qboPo.currencyRef = currRef

            def salesTermRef = new com.intuit.ipp.data.ReferenceType()
            salesTermRef.value = "3"
            qboPo.salesTermRef = salesTermRef

            def shipMethodRef = new com.intuit.ipp.data.ReferenceType()
            shipMethodRef.value = "1"
            qboPo.shipMethodRef = shipMethodRef

            def classRef = new com.intuit.ipp.data.ReferenceType()
            classRef.value = "5"
            qboPo.classRef = classRef

            def email = new com.intuit.ipp.data.EmailAddress()
            email.address = "test@test.com"
            qboPo.POEmail = email

            qboPo.docNumber = "PO-100"

        when:
            def metadata = mapper.buildMetadata(qboPo)

        then:
            metadata != null
            metadata.totalAmount == new BigDecimal("5000.00")
            metadata.syncToken == "7"
            metadata.apAccountRef != null
            metadata.currencyRef != null
            metadata.salesTermRef != null
            metadata.shipMethodRef != null
            metadata.classRef != null
            metadata.poEmail == "test@test.com"
            metadata.docNumber == "PO-100"
    }

    def "should return null metadata for empty QBO PO"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()

        when:
            def metadata = mapper.buildMetadata(qboPo)

        then:
            metadata == null
    }

    // ==================== toDeletedPurchaseOrder Tests ====================

    def "should create deleted purchase order"() {
        given:
            def deletedAt = java.time.OffsetDateTime.now()

        when:
            def po = mapper.toDeletedPurchaseOrder("ext-999", deletedAt)

        then:
            po != null
            po.externalId == "ext-999"
            po.provider == IntegrationProvider.QUICKBOOKS.value
            po.status == PurchaseOrderStatus.CANCELLED
            po.deletedAt == deletedAt
    }

    // ==================== mapVendorContact Tests ====================

    def "should return null when vendorRef is null"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()

        expect:
            mapper.mapVendorContact(qboPo) == null
    }

    def "should map vendor contact with address"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()
            def vendorRef = new com.intuit.ipp.data.ReferenceType()
            vendorRef.value = "56"
            vendorRef.name = "Test Vendor"
            qboPo.vendorRef = vendorRef

            def vendorAddr = new com.intuit.ipp.data.PhysicalAddress()
            vendorAddr.line1 = "123 Vendor St"
            vendorAddr.city = "Houston"
            qboPo.vendorAddr = vendorAddr

        when:
            def result = mapper.mapVendorContact(qboPo)

        then:
            result != null
            result.externalId == "56"
            result.name == "Test Vendor"
            result.tag == PartyTag.SUPPLIER
            result.address != null
            result.address.city == "Houston"
    }

    // ==================== mapShipToContact Tests ====================

    def "should return null when shipTo and shipAddr are null"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()

        expect:
            mapper.mapShipToContact(qboPo) == null
    }

    def "should strip Job Location prefix from ship-to name"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()
            def shipTo = new com.intuit.ipp.data.ReferenceType()
            shipTo.value = "123"
            shipTo.name = "[Job Location] Downtown Office"
            qboPo.shipTo = shipTo

        when:
            def result = mapper.mapShipToContact(qboPo)

        then:
            result != null
            result.name == "Downtown Office"
            result.tag == PartyTag.SHIP_TO
            result.externalId == "123"
    }

    def "should map ship-to with address only (no shipTo ref)"() {
        given:
            def qboPo = new com.intuit.ipp.data.PurchaseOrder()
            def shipAddr = new com.intuit.ipp.data.PhysicalAddress()
            shipAddr.line1 = "789 Ship St"
            shipAddr.city = "Phoenix"
            qboPo.shipAddr = shipAddr

        when:
            def result = mapper.mapShipToContact(qboPo)

        then:
            result != null
            result.tag == PartyTag.SHIP_TO
            result.address != null
            result.address.city == "Phoenix"
    }

    // ==================== mapLineItems Tests ====================

    def "should return null for null lines"() {
        expect:
            mapper.mapLineItems(null, CONNECTION_ID) == null
    }

    def "should return null for empty lines"() {
        expect:
            mapper.mapLineItems([], CONNECTION_ID) == null
    }

    def "should filter out null results from line mapping"() {
        given:
            def line = new com.intuit.ipp.data.Line()
            line.id = "1"
            line.description = "Test Line"
            line.amount = new BigDecimal("100.00")
            line.detailType = com.intuit.ipp.data.LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL
            def detail = new com.intuit.ipp.data.ItemBasedExpenseLineDetail()
            def itemRef = new com.intuit.ipp.data.ReferenceType()
            itemRef.value = "21"
            detail.itemRef = itemRef
            detail.qty = new BigDecimal("5")
            detail.unitPrice = new BigDecimal("20.00")
            line.itemBasedExpenseLineDetail = detail

        when:
            def result = mapper.mapLineItems([line, null], CONNECTION_ID)

        then:
            result != null
            result.size() == 1
    }

    // ==================== mapLineToPurchaseOrderItem Tests ====================

    def "should return null for null line"() {
        expect:
            mapper.mapLineToPurchaseOrderItem(null, CONNECTION_ID) == null
    }

    def "should map ItemBasedExpenseLineDetail with taxable TaxCodeRef"() {
        given:
            def line = new com.intuit.ipp.data.Line()
            line.id = "1"
            line.description = "Taxable Item"
            line.amount = new BigDecimal("500.00")
            line.detailType = com.intuit.ipp.data.LineDetailTypeEnum.ITEM_BASED_EXPENSE_LINE_DETAIL

            def detail = new com.intuit.ipp.data.ItemBasedExpenseLineDetail()
            detail.qty = new BigDecimal("10")
            detail.unitPrice = new BigDecimal("50.00")

            def itemRef = new com.intuit.ipp.data.ReferenceType()
            itemRef.value = "21"
            detail.itemRef = itemRef

            def taxRef = new com.intuit.ipp.data.ReferenceType()
            taxRef.value = "TAX"
            detail.taxCodeRef = taxRef

            line.itemBasedExpenseLineDetail = detail

        when:
            def item = mapper.mapLineToPurchaseOrderItem(line, CONNECTION_ID)

        then:
            item != null
            item.id == "1"
            item.name == "Taxable Item"
            item.totalPrice == new BigDecimal("500.00")
            item.quantity == 10
            item.unitPrice == new BigDecimal("50.00")
            item.externalItemId == "21"
            item.taxable == true
    }

    def "should map AccountBasedExpenseLineDetail with NON TaxCodeRef"() {
        given:
            def line = new com.intuit.ipp.data.Line()
            line.id = "2"
            line.description = "Account Line"
            line.amount = new BigDecimal("1000.00")
            line.detailType = com.intuit.ipp.data.LineDetailTypeEnum.ACCOUNT_BASED_EXPENSE_LINE_DETAIL

            def detail = new com.intuit.ipp.data.AccountBasedExpenseLineDetail()
            def acctRef = new com.intuit.ipp.data.ReferenceType()
            acctRef.value = "95"
            detail.accountRef = acctRef

            def taxRef = new com.intuit.ipp.data.ReferenceType()
            taxRef.value = "NON"
            detail.taxCodeRef = taxRef

            line.accountBasedExpenseLineDetail = detail

        when:
            def item = mapper.mapLineToPurchaseOrderItem(line, CONNECTION_ID)

        then:
            item != null
            item.quantity == 0
            item.unitPrice == BigDecimal.ZERO
            item.externalAccountId == "95"
            item.taxable == false
    }

    // ==================== JSON (CDC) Mapping Tests ====================

    def "should map JSON status correctly"() {
        expect:
            mapper.mapJsonStatus(null) == PurchaseOrderStatus.OPEN
            mapper.mapJsonStatus("Open") == PurchaseOrderStatus.OPEN
            mapper.mapJsonStatus("Closed") == PurchaseOrderStatus.CLOSED
            mapper.mapJsonStatus("closed") == PurchaseOrderStatus.CLOSED
            mapper.mapJsonStatus("Other") == PurchaseOrderStatus.OPEN
    }

    def "should parse JSON date correctly"() {
        when:
            def result = mapper.parseJsonDate("2025-11-07")

        then:
            result != null
            result.year == 2025
            result.monthValue == 11
            result.dayOfMonth == 7
    }

    def "should return null for null date string"() {
        expect:
            mapper.parseJsonDate(null) == null
    }

    def "should return null for invalid date string"() {
        expect:
            mapper.parseJsonDate("not-a-date") == null
    }

    def "should parse JSON datetime correctly"() {
        when:
            def result = mapper.parseJsonDateTime("2025-11-07T13:10:14-08:00")

        then:
            result != null
            result.year == 2025
    }

    def "should return null for null datetime string"() {
        expect:
            mapper.parseJsonDateTime(null) == null
    }

    def "should return null for invalid datetime string"() {
        expect:
            mapper.parseJsonDateTime("bad-datetime") == null
    }

    // ==================== getTextOrNull Tests ====================

    def "should return null for null node"() {
        expect:
            mapper.getTextOrNull(null, "anyField") == null
    }

    def "should return text for existing field"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{"name": "Test"}')

        when:
            def result = mapper.getTextOrNull(node, "name")

        then:
            result == "Test"
    }

    def "should return null for missing field"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{"name": "Test"}')

        when:
            def result = mapper.getTextOrNull(node, "nonExistent")

        then:
            result == null
    }

    // ==================== addRefToMetadata Tests ====================

    def "should add reference to metadata map"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{"value": "42", "name": "Test"}')
            def metadata = [:]

        when:
            mapper.addRefToMetadata(metadata, "testRef", node)

        then:
            metadata.testRef != null
            metadata.testRef.value == "42"
            metadata.testRef.name == "Test"
    }

    def "should skip adding null ref node to metadata"() {
        given:
            def metadata = [:]

        when:
            mapper.addRefToMetadata(metadata, "testRef", null)

        then:
            metadata.isEmpty()
    }

    // ==================== mapJsonVendorContact Tests ====================

    def "should map JSON vendor contact"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            {
                "VendorRef": {"value": "56", "name": "ACME"},
                "VendorAddr": {"Line1": "123 Main", "City": "Austin"}
            }
            ''')

        when:
            def result = mapper.mapJsonVendorContact(node)

        then:
            result != null
            result.externalId == "56"
            result.name == "ACME"
            result.tag == PartyTag.VENDOR
            result.address != null
            result.address.city == "Austin"
    }

    def "should return null when VendorRef is missing"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{}')

        expect:
            mapper.mapJsonVendorContact(node) == null
    }

    // ==================== mapJsonShipToContact Tests ====================

    def "should map JSON ship-to contact with Job Location prefix"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            {
                "ShipTo": {"value": "123", "name": "[Job Location] Office"},
                "ShipAddr": {"Line1": "456 Ship St", "City": "Dallas"}
            }
            ''')

        when:
            def result = mapper.mapJsonShipToContact(node)

        then:
            result != null
            result.externalId == "123"
            result.name == "Office"
            result.tag == PartyTag.SHIP_TO
            result.address != null
    }

    def "should return null when ShipTo and ShipAddr are both missing in JSON"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{}')

        expect:
            mapper.mapJsonShipToContact(node) == null
    }

    // ==================== mapJsonAddress Tests ====================

    def "should map JSON address with multiple lines"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            {
                "Line1": "123 Main",
                "Line2": "Suite 200",
                "City": "Austin",
                "CountrySubDivisionCode": "TX",
                "PostalCode": "78701",
                "Country": "US"
            }
            ''')

        when:
            def result = mapper.mapJsonAddress(node)

        then:
            result != null
            result.address == "123 Main, Suite 200"
            result.city == "Austin"
            result.stateOrProvince == "TX"
            result.postalCode == "78701"
            result.country == "US"
    }

    def "should return null for null JSON address"() {
        expect:
            mapper.mapJsonAddress(null) == null
    }

    // ==================== mapJsonLineItems Tests ====================

    def "should return null for null JSON lines node"() {
        expect:
            mapper.mapJsonLineItems(null) == null
    }

    def "should return null for non-array JSON lines node"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('"not an array"')

        expect:
            mapper.mapJsonLineItems(node) == null
    }

    def "should map JSON line items with ItemBasedExpenseLineDetail"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            [
                {
                    "Id": "1",
                    "Description": "Widget",
                    "Amount": "500.00",
                    "ItemBasedExpenseLineDetail": {
                        "Qty": "10",
                        "UnitPrice": "50.00",
                        "ItemRef": {"value": "21", "name": "Widget"},
                        "TaxCodeRef": {"value": "TAX"},
                        "BillableStatus": "NotBillable"
                    }
                }
            ]
            ''')

        when:
            def result = mapper.mapJsonLineItems(node)

        then:
            result != null
            result.size() == 1
            result[0].id == "1"
            result[0].name == "Widget"
            result[0].totalPrice == new BigDecimal("500.00")
            result[0].quantity == 10
            result[0].unitPrice == new BigDecimal("50.00")
            result[0].externalItemId == "21"
            result[0].taxable == true
    }

    def "should map JSON line items with AccountBasedExpenseLineDetail"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            [
                {
                    "Id": "2",
                    "Description": "Consulting",
                    "Amount": "2000.00",
                    "AccountBasedExpenseLineDetail": {
                        "AccountRef": {"value": "95", "name": "Consulting"},
                        "TaxCodeRef": {"value": "NON"},
                        "BillableStatus": "Billable"
                    }
                }
            ]
            ''')

        when:
            def result = mapper.mapJsonLineItems(node)

        then:
            result != null
            result.size() == 1
            result[0].id == "2"
            result[0].name == "Consulting"
            result[0].quantity == 0
            result[0].unitPrice == BigDecimal.ZERO
            result[0].externalAccountId == "95"
            result[0].taxable == false
    }

    // ==================== mapJsonLineItem null handling ====================

    def "should return null for null JSON line item"() {
        expect:
            mapper.mapJsonLineItem(null) == null
    }

    // ==================== buildJsonMetadata Tests ====================

    def "should build JSON metadata with all fields"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            {
                "TotalAmt": "5000.00",
                "SyncToken": "7",
                "APAccountRef": {"value": "33", "name": "AP"},
                "CurrencyRef": {"value": "USD"},
                "SalesTermRef": {"value": "3"},
                "ShipMethodRef": {"value": "1"},
                "ClassRef": {"value": "5"},
                "POEmail": {"Address": "test@test.com"},
                "EmailStatus": "NeedToSend",
                "CustomField": [
                    {"DefinitionId": "1", "Name": "Project", "StringValue": "PRJ-001"}
                ],
                "LinkedTxn": [
                    {"TxnId": "5001", "TxnType": "Bill"}
                ]
            }
            ''')

        when:
            def metadata = mapper.buildJsonMetadata(node)

        then:
            metadata != null
            metadata.totalAmount == new BigDecimal("5000.00")
            metadata.syncToken == "7"
            metadata.apAccountRef != null
            metadata.currencyRef != null
            metadata.salesTermRef != null
            metadata.shipMethodRef != null
            metadata.classRef != null
            metadata.poEmail == "test@test.com"
            metadata.emailStatus == "NeedToSend"
            metadata.customFields != null
            metadata.customFields.Project == "PRJ-001"
            metadata.linkedTxn != null
            metadata.linkedTxn.size() == 1
            metadata.linkedTxn[0].txnId == "5001"
    }

    def "should return null metadata for empty JSON node"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('{}')

        when:
            def metadata = mapper.buildJsonMetadata(node)

        then:
            metadata == null
    }

    def "should handle custom field without name using field_id fallback"() {
        given:
            def objectMapper = new ObjectMapper()
            def node = objectMapper.readTree('''
            {
                "CustomField": [
                    {"DefinitionId": "2", "StringValue": "SomeVal"}
                ]
            }
            ''')

        when:
            def metadata = mapper.buildJsonMetadata(node)

        then:
            metadata != null
            metadata.customFields["field_2"] == "SomeVal"
    }
}
