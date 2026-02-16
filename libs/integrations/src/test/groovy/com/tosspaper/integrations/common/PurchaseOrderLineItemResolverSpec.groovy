package com.tosspaper.integrations.common

import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.PurchaseOrderItem
import com.tosspaper.models.service.IntegrationAccountService
import com.tosspaper.models.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

class PurchaseOrderLineItemResolverSpec extends Specification {

    ItemService itemService = Mock()
    IntegrationAccountService integrationAccountService = Mock()

    @Subject
    PurchaseOrderLineItemResolver resolver = new PurchaseOrderLineItemResolver(itemService, integrationAccountService)

    def "should resolve both item and account references"() {
        given: "a PO with line items that have external IDs"
            def item1 = PurchaseOrderItem.builder()
                .name("Widget")
                .externalItemId("ext-item-1")
                .externalAccountId("ext-acc-1")
                .build()
            def item2 = PurchaseOrderItem.builder()
                .name("Gadget")
                .externalItemId("ext-item-2")
                .externalAccountId("ext-acc-2")
                .build()
            def po = PurchaseOrder.builder()
                .displayId("PO-001")
                .items([item1, item2])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "item service is called with external IDs"
            1 * itemService.findIdsByExternalIdsAndConnection("conn-1", { it.containsAll(["ext-item-1", "ext-item-2"]) }) >> ["ext-item-1": "internal-item-1", "ext-item-2": "internal-item-2"]

        and: "account service is called with external IDs"
            1 * integrationAccountService.findIdsByExternalIdsAndConnection("conn-1", { it.containsAll(["ext-acc-1", "ext-acc-2"]) }) >> ["ext-acc-1": "internal-acc-1", "ext-acc-2": "internal-acc-2"]

        and: "internal IDs are set on line items"
            item1.itemId == "internal-item-1"
            item1.accountId == "internal-acc-1"
            item2.itemId == "internal-item-2"
            item2.accountId == "internal-acc-2"
    }

    def "should handle line items with only item references"() {
        given: "a PO with item-based lines only"
            def item1 = PurchaseOrderItem.builder()
                .name("Widget")
                .externalItemId("ext-item-1")
                .build()
            def po = PurchaseOrder.builder()
                .displayId("PO-002")
                .items([item1])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "item service is called"
            1 * itemService.findIdsByExternalIdsAndConnection("conn-1", ["ext-item-1"]) >> ["ext-item-1": "internal-item-1"]

        and: "account service is not called for empty set"
            0 * integrationAccountService.findIdsByExternalIdsAndConnection(_, _)

        and: "item ID is set"
            item1.itemId == "internal-item-1"
            item1.accountId == null
    }

    def "should handle line items with only account references"() {
        given: "a PO with account-based lines only"
            def item1 = PurchaseOrderItem.builder()
                .name("Expense Line")
                .externalAccountId("ext-acc-1")
                .build()
            def po = PurchaseOrder.builder()
                .displayId("PO-003")
                .items([item1])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "item service is not called for empty set"
            0 * itemService.findIdsByExternalIdsAndConnection(_, _)

        and: "account service is called"
            1 * integrationAccountService.findIdsByExternalIdsAndConnection("conn-1", ["ext-acc-1"]) >> ["ext-acc-1": "internal-acc-1"]

        and: "account ID is set"
            item1.accountId == "internal-acc-1"
            item1.itemId == null
    }

    def "should handle missing external ID mappings gracefully"() {
        given: "a PO with line items whose external IDs are not found"
            def item1 = PurchaseOrderItem.builder()
                .name("Unknown Widget")
                .externalItemId("ext-item-unknown")
                .externalAccountId("ext-acc-unknown")
                .build()
            def po = PurchaseOrder.builder()
                .displayId("PO-004")
                .items([item1])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "services return empty maps"
            1 * itemService.findIdsByExternalIdsAndConnection("conn-1", ["ext-item-unknown"]) >> [:]
            1 * integrationAccountService.findIdsByExternalIdsAndConnection("conn-1", ["ext-acc-unknown"]) >> [:]

        and: "IDs remain null"
            item1.itemId == null
            item1.accountId == null
    }

    def "should handle PO with null items list"() {
        given: "a PO with null items"
            def po = PurchaseOrder.builder()
                .displayId("PO-005")
                .items(null)
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "no services are called"
            0 * itemService.findIdsByExternalIdsAndConnection(_, _)
            0 * integrationAccountService.findIdsByExternalIdsAndConnection(_, _)
    }

    def "should handle empty PO list"() {
        when: "resolving with empty list"
            resolver.resolveLineItemReferences("conn-1", [])

        then: "no services are called"
            0 * itemService.findIdsByExternalIdsAndConnection(_, _)
            0 * integrationAccountService.findIdsByExternalIdsAndConnection(_, _)
    }

    def "should handle line items with no external IDs"() {
        given: "a PO with line items that have no external IDs"
            def item1 = PurchaseOrderItem.builder()
                .name("Manual Line")
                .build()
            def po = PurchaseOrder.builder()
                .displayId("PO-006")
                .items([item1])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po])

        then: "no services are called"
            0 * itemService.findIdsByExternalIdsAndConnection(_, _)
            0 * integrationAccountService.findIdsByExternalIdsAndConnection(_, _)
    }

    def "should handle mix of resolved and unresolved items across multiple POs"() {
        given: "multiple POs with mix of resolvable and unresolvable items"
            def item1 = PurchaseOrderItem.builder()
                .name("Widget")
                .externalItemId("ext-item-1")
                .build()
            def item2 = PurchaseOrderItem.builder()
                .name("Unknown")
                .externalItemId("ext-item-missing")
                .build()
            def item3 = PurchaseOrderItem.builder()
                .name("Account Line")
                .externalAccountId("ext-acc-1")
                .build()

            def po1 = PurchaseOrder.builder()
                .displayId("PO-007")
                .items([item1, item2])
                .build()
            def po2 = PurchaseOrder.builder()
                .displayId("PO-008")
                .items([item3])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po1, po2])

        then: "item service returns partial results"
            1 * itemService.findIdsByExternalIdsAndConnection("conn-1", { it.containsAll(["ext-item-1", "ext-item-missing"]) }) >> ["ext-item-1": "internal-item-1"]

        and: "account service returns result"
            1 * integrationAccountService.findIdsByExternalIdsAndConnection("conn-1", ["ext-acc-1"]) >> ["ext-acc-1": "internal-acc-1"]

        and: "resolved items have IDs set"
            item1.itemId == "internal-item-1"
            item2.itemId == null
            item3.accountId == "internal-acc-1"
    }

    def "should deduplicate external IDs across POs"() {
        given: "two POs referencing the same external item"
            def item1 = PurchaseOrderItem.builder()
                .name("Widget")
                .externalItemId("ext-item-same")
                .build()
            def item2 = PurchaseOrderItem.builder()
                .name("Widget Again")
                .externalItemId("ext-item-same")
                .build()
            def po1 = PurchaseOrder.builder()
                .displayId("PO-009")
                .items([item1])
                .build()
            def po2 = PurchaseOrder.builder()
                .displayId("PO-010")
                .items([item2])
                .build()

        when: "resolving line item references"
            resolver.resolveLineItemReferences("conn-1", [po1, po2])

        then: "item service is called with deduplicated IDs"
            1 * itemService.findIdsByExternalIdsAndConnection("conn-1", ["ext-item-same"]) >> ["ext-item-same": "internal-item-1"]

        and: "both items get the resolved ID"
            item1.itemId == "internal-item-1"
            item2.itemId == "internal-item-1"
    }
}
