package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.PurchaseOrderItem
import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

class PurchaseOrderDependencyStrategySpec extends Specification {

    def customerService = Mock(CustomerDependencyPushService)
    def vendorService = Mock(VendorDependencyPushService)
    def itemService = Mock(ItemDependencyPushService)
    def accountService = Mock(AccountDependencyService)
    def contactSyncService = Mock(ContactSyncService)
    def itemServiceForLookup = Mock(ItemService)
    def integrationAccountService = Mock(IntegrationAccountService)

    def connection = Mock(IntegrationConnection) {
        getId() >> "conn-123"
    }

    @Subject
    PurchaseOrderDependencyStrategy strategy

    def setup() {
        strategy = new PurchaseOrderDependencyStrategy(
                customerService,
                vendorService,
                itemService,
                accountService,
                contactSyncService,
                itemServiceForLookup,
                integrationAccountService
        )
    }

    def "should support PURCHASE_ORDER entity type"() {
        expect:
        strategy.supports(IntegrationEntityType.PURCHASE_ORDER)
    }

    def "should not support other entity types"() {
        expect:
        !strategy.supports(IntegrationEntityType.VENDOR)
        !strategy.supports(IntegrationEntityType.ITEM)
    }

    def "should return success when PO has no dependencies"() {
        given:
        def po = createPurchaseOrder(null, null, [])

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        result.success
        0 * customerService._
        0 * vendorService._
        0 * itemService._
        0 * accountService._
    }

    def "should ensure ship-to dependencies first"() {
        given:
        def shipTo = createParty("shipto-1")
        def po = createPurchaseOrder(shipTo, null, [])

        contactSyncService.findByIds(["shipto-1"]) >> [shipTo]
        customerService.ensureHaveExternalIds(connection, [shipTo]) >> DependencyPushResult.success()

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        result.success
    }

    def "should fail fast when ship-to push fails"() {
        given:
        def shipTo = createParty("shipto-1")
        def vendor = createParty("vendor-1")
        def po = createPurchaseOrder(shipTo, vendor, [])

        contactSyncService.findByIds(["shipto-1"]) >> [shipTo]
        contactSyncService.findByIds(["vendor-1"]) >> [vendor]
        customerService.ensureHaveExternalIds(connection, [shipTo]) >> DependencyPushResult.failure("Ship-to push failed")

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        !result.success
        result.message == "Ship-to push failed"
        0 * vendorService._  // Vendor check should not happen
    }

    def "should ensure vendor dependencies after ship-tos"() {
        given:
        def vendor = createParty("vendor-1")
        def po = createPurchaseOrder(null, vendor, [])

        contactSyncService.findByIds(["vendor-1"]) >> [vendor]
        vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        result.success
    }

    def "should fail fast when vendor push fails"() {
        given:
        def vendor = createParty("vendor-1")
        def po = createPurchaseOrder(null, vendor, [createLineItem("item-1", null)])

        contactSyncService.findByIds(["vendor-1"]) >> [vendor]
        vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.failure("Vendor push failed")

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        !result.success
        result.message == "Vendor push failed"
        0 * itemService._  // Item check should not happen
    }

    def "should validate accounts before items"() {
        given:
        def lineItem = createLineItem(null, "account-1")
        def po = createPurchaseOrder(null, null, [lineItem])
        def account = Mock(IntegrationAccount)

        integrationAccountService.findByIds("conn-123", ["account-1"]) >> [account]
        accountService.validateHaveExternalIds(connection, [account]) >> DependencyPushResult.success()

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        result.success
    }

    def "should ensure item dependencies"() {
        given:
        def lineItem = createLineItem("item-1", null)
        def po = createPurchaseOrder(null, null, [lineItem])
        def item = Mock(Item)

        itemServiceForLookup.findByIds(["item-1"]) >> [item]
        itemService.ensureHaveExternalIds(connection, [item]) >> DependencyPushResult.success()

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then:
        result.success
    }

    def "should process all dependencies in correct order"() {
        given:
        def shipTo = createParty("shipto-1")
        def vendor = createParty("vendor-1")
        def itemLine = createLineItem("item-1", null)
        def accountLine = createLineItem(null, "account-1")
        def po = createPurchaseOrder(shipTo, vendor, [itemLine, accountLine])

        def item = Mock(Item)
        def account = Mock(IntegrationAccount)

        contactSyncService.findByIds(["shipto-1"]) >> [shipTo]
        contactSyncService.findByIds(["vendor-1"]) >> [vendor]
        itemServiceForLookup.findByIds(["item-1"]) >> [item]
        integrationAccountService.findByIds("conn-123", ["account-1"]) >> [account]

        when:
        def result = strategy.ensureDependencies(connection, [po])

        then: "ship-tos are processed first"
        1 * customerService.ensureHaveExternalIds(connection, [shipTo]) >> DependencyPushResult.success()

        then: "vendors are processed second"
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()

        then: "accounts are validated third"
        1 * accountService.validateHaveExternalIds(connection, [account]) >> DependencyPushResult.success()

        then: "items are processed last"
        1 * itemService.ensureHaveExternalIds(connection, [item]) >> DependencyPushResult.success()

        and:
        result.success
    }

    def "should deduplicate dependencies across multiple POs"() {
        given:
        def vendor = createParty("vendor-1")
        def po1 = createPurchaseOrder(null, vendor, [])
        def po2 = createPurchaseOrder(null, vendor, [])

        contactSyncService.findByIds(["vendor-1"]) >> [vendor]

        when:
        def result = strategy.ensureDependencies(connection, [po1, po2])

        then:
        result.success
        // Vendor should only be processed once even though 2 POs reference it
        1 * vendorService.ensureHaveExternalIds(connection, { List vendors ->
            vendors.size() == 1 && vendors[0].id == "vendor-1"
        }) >> DependencyPushResult.success()
    }

    // ==================== Helper Methods ====================

    private Party createParty(String id) {
        def party = new Party()
        party.setId(id)
        return party
    }

    private PurchaseOrderItem createLineItem(String itemId, String accountId) {
        return PurchaseOrderItem.builder()
                .itemId(itemId)
                .accountId(accountId)
                .build()
    }

    private PurchaseOrder createPurchaseOrder(Party shipTo, Party vendor, List<PurchaseOrderItem> items) {
        return PurchaseOrder.builder()
                .shipToContact(shipTo)
                .vendorContact(vendor)
                .items(items)
                .build()
    }
}
