package com.tosspaper.integrations.common

import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.PurchaseOrderItem
import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for PurchaseOrderLineItemEnricher.
 * Tests line item enrichment with QB metadata from Items and Accounts tables.
 */
class PurchaseOrderLineItemEnricherSpec extends Specification {

    ItemService itemService = Mock()
    IntegrationAccountService integrationAccountService = Mock()
    ContactSyncService contactSyncService = Mock()

    @Subject
    PurchaseOrderLineItemEnricher enricher = new PurchaseOrderLineItemEnricher(
        itemService,
        integrationAccountService,
        contactSyncService
    )

    private static Item buildItemWithExternalId(String id, String externalId) {
        def item = Item.builder().id(id).build()
        if (externalId) item.setExternalId(externalId)
        return item
    }

    private static IntegrationAccount buildAccountWithExternalId(String id, String externalId) {
        def account = IntegrationAccount.builder().id(id).build()
        if (externalId) account.setExternalId(externalId)
        return account
    }

    private static Party buildPartyWithExternalId(Map args) {
        def builder = Party.builder()
        if (args.id) builder.id(args.id)
        if (args.name) builder.name(args.name)
        def built = builder.build()
        if (args.externalId) built.setExternalId(args.externalId)
        if (args.providerVersion) built.setProviderVersion(args.providerVersion)
        return built
    }

    def "enrichLineItems should do nothing when purchase orders list is null"() {
        when:
        enricher.enrichLineItems("conn-1", null)

        then:
        0 * itemService._
        0 * integrationAccountService._
    }

    def "enrichLineItems should do nothing when purchase orders list is empty"() {
        when:
        enricher.enrichLineItems("conn-1", [])

        then:
        0 * itemService._
        0 * integrationAccountService._
    }

    def "enrichLineItems should enrich line items with item external IDs"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .itemId("item-123")
            .name("Widget")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        def item = buildItemWithExternalId("item-123", "item-ext-456")

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * itemService.findByIds(["item-123"]) >> [item]
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem.externalItemId == "item-ext-456"
    }

    def "enrichLineItems should enrich line items with account external IDs"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .accountId("account-123")
            .name("Expense")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        def account = buildAccountWithExternalId("account-123", "account-ext-789")

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        0 * itemService.findByIds(_)
        1 * integrationAccountService.findByIds("conn-1", ["account-123"]) >> [account]
        0 * contactSyncService.findByIds(_)
        lineItem.externalAccountId == "account-ext-789"
    }

    def "enrichLineItems should enrich vendor with external ID"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendor)
            .items([])
            .build()

        def dbVendor = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-999", providerVersion: "5"
        )

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * contactSyncService.findByIds(["vendor-123"]) >> [dbVendor]
        0 * itemService.findByIds(_)
        0 * integrationAccountService.findByIds(_, _)
        po.vendorContact.externalId == "vendor-ext-999"
        po.vendorContact.providerVersion == "5"
    }

    def "enrichLineItems should enrich ship-to contact with external ID"() {
        given:
        def shipTo = Party.builder()
            .id("customer-123")
            .name("Job Site A")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .shipToContact(shipTo)
            .items([])
            .build()

        def dbCustomer = buildPartyWithExternalId(
            id: "customer-123", externalId: "customer-ext-888", providerVersion: "3"
        )

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * contactSyncService.findByIds(["customer-123"]) >> [dbCustomer]
        0 * itemService.findByIds(_)
        0 * integrationAccountService.findByIds(_, _)
        po.shipToContact.externalId == "customer-ext-888"
        po.shipToContact.providerVersion == "3"
    }

    def "enrichLineItems should enrich both item and account IDs"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .itemId("item-123")
            .accountId("account-456")
            .name("Widget")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        def item = buildItemWithExternalId("item-123", "item-ext-111")
        def account = buildAccountWithExternalId("account-456", "account-ext-222")

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * itemService.findByIds(["item-123"]) >> [item]
        1 * integrationAccountService.findByIds("conn-1", ["account-456"]) >> [account]
        0 * contactSyncService.findByIds(_)
        lineItem.externalItemId == "item-ext-111"
        lineItem.externalAccountId == "account-ext-222"
    }

    def "enrichLineItems should batch-fetch for multiple POs"() {
        given:
        def lineItem1 = PurchaseOrderItem.builder()
            .itemId("item-123")
            .build()

        def lineItem2 = PurchaseOrderItem.builder()
            .itemId("item-456")
            .build()

        def po1 = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem1])
            .build()

        def po2 = PurchaseOrder.builder()
            .displayId("PO-002")
            .items([lineItem2])
            .build()

        def item1 = buildItemWithExternalId("item-123", "item-ext-111")
        def item2 = buildItemWithExternalId("item-456", "item-ext-222")

        when:
        enricher.enrichLineItems("conn-1", [po1, po2])

        then:
        1 * itemService.findByIds(_) >> [item1, item2]
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem1.externalItemId == "item-ext-111"
        lineItem2.externalItemId == "item-ext-222"
    }

    def "enrichLineItems should deduplicate item IDs across multiple POs"() {
        given:
        def lineItem1 = PurchaseOrderItem.builder()
            .itemId("item-123")
            .build()

        def lineItem2 = PurchaseOrderItem.builder()
            .itemId("item-123")
            .build()

        def po1 = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem1])
            .build()

        def po2 = PurchaseOrder.builder()
            .displayId("PO-002")
            .items([lineItem2])
            .build()

        def item = buildItemWithExternalId("item-123", "item-ext-999")

        when:
        enricher.enrichLineItems("conn-1", [po1, po2])

        then:
        1 * itemService.findByIds(["item-123"]) >> [item]
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem1.externalItemId == "item-ext-999"
        lineItem2.externalItemId == "item-ext-999"
    }

    def "enrichLineItems should skip line items without itemId or accountId"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .name("Widget")
            .build() // No itemId or accountId

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        0 * itemService.findByIds(_)
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem.externalItemId == null
        lineItem.externalAccountId == null
    }

    def "enrichLineItems should skip enrichment when item not found in DB"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .itemId("item-missing")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * itemService.findByIds(["item-missing"]) >> []
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem.externalItemId == null
    }

    def "enrichLineItems should skip enrichment when account not found in DB"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .accountId("account-missing")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        0 * itemService.findByIds(_)
        1 * integrationAccountService.findByIds("conn-1", ["account-missing"]) >> []
        0 * contactSyncService.findByIds(_)
        lineItem.externalAccountId == null
    }

    def "enrichLineItems should skip enrichment when item has no external ID"() {
        given:
        def lineItem = PurchaseOrderItem.builder()
            .itemId("item-123")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .items([lineItem])
            .build()

        def item = Item.builder()
            .id("item-123")
            .build() // No externalId

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * itemService.findByIds(["item-123"]) >> [item]
        0 * integrationAccountService.findByIds(_, _)
        0 * contactSyncService.findByIds(_)
        lineItem.externalItemId == null
    }

    def "enrichLineItems should skip vendor enrichment when vendor has no ID"() {
        given:
        def vendor = Party.builder()
            .name("No ID Vendor")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendor)
            .items([])
            .build()

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        0 * contactSyncService.findByIds(_)
        0 * itemService.findByIds(_)
        0 * integrationAccountService.findByIds(_, _)
        po.vendorContact.externalId == null
    }

    def "enrichLineItems should enrich vendor and ship-to and line items in single batch"() {
        given:
        def vendor = Party.builder()
            .id("vendor-123")
            .build()

        def shipTo = Party.builder()
            .id("customer-456")
            .build()

        def lineItem = PurchaseOrderItem.builder()
            .itemId("item-789")
            .accountId("account-999")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendor)
            .shipToContact(shipTo)
            .items([lineItem])
            .build()

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1")
        def dbShipTo = buildPartyWithExternalId(id: "customer-456", externalId: "customer-ext-2")
        def dbItem = buildItemWithExternalId("item-789", "item-ext-3")
        def dbAccount = buildAccountWithExternalId("account-999", "account-ext-4")

        when:
        enricher.enrichLineItems("conn-1", [po])

        then:
        1 * contactSyncService.findByIds(["vendor-123"]) >> [dbVendor]
        1 * contactSyncService.findByIds(["customer-456"]) >> [dbShipTo]
        1 * itemService.findByIds(["item-789"]) >> [dbItem]
        1 * integrationAccountService.findByIds("conn-1", ["account-999"]) >> [dbAccount]
        po.vendorContact.externalId == "vendor-ext-1"
        po.shipToContact.externalId == "customer-ext-2"
        lineItem.externalItemId == "item-ext-3"
        lineItem.externalAccountId == "account-ext-4"
    }
}
