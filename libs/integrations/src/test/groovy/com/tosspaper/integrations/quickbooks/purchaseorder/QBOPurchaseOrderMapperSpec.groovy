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
}
