package com.tosspaper.integrations.common

import com.tosspaper.models.domain.Address
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

/**
 * Comprehensive tests for PurchaseOrderContactEnricher.
 * Tests all contact enrichment scenarios including vendor, ship-to, and fallback lookups.
 */
class PurchaseOrderContactEnricherSpec extends Specification {

    ContactSyncService contactSyncService = Mock()
    PurchaseOrderSyncService purchaseOrderSyncService = Mock()

    @Subject
    PurchaseOrderContactEnricher enricher = new PurchaseOrderContactEnricher(
        contactSyncService,
        purchaseOrderSyncService
    )

    private static Party buildPartyWithExternalId(Map args) {
        def party = Party.builder()
        if (args.id) party.id(args.id)
        if (args.name != null) party.name(args.name)
        if (args.companyId) party.companyId(args.companyId)
        if (args.phone) party.phone(args.phone)
        if (args.email) party.email(args.email)
        if (args.address) party.address(Address.builder().address(args.address).build())
        def built = party.build()
        if (args.externalId) built.setExternalId(args.externalId)
        return built
    }

    private static PurchaseOrder buildPOWithExternalId(Map args) {
        def builder = PurchaseOrder.builder()
        if (args.displayId) builder.displayId(args.displayId)
        if (args.vendorContact) builder.vendorContact(args.vendorContact)
        if (args.shipToContact) builder.shipToContact(args.shipToContact)
        if (args.items) builder.items(args.items)
        def built = builder.build()
        if (args.externalId) built.setExternalId(args.externalId)
        return built
    }

    def "enrichContacts should do nothing when purchase orders list is null"() {
        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, null)

        then:
        0 * contactSyncService._
        0 * purchaseOrderSyncService._
    }

    def "enrichContacts should do nothing when purchase orders list is empty"() {
        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [])

        then:
        0 * contactSyncService._
        0 * purchaseOrderSyncService._
    }

    def "enrichContacts should do nothing when no contacts to enrich"() {
        given:
        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001")

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        0 * contactSyncService._
    }

    def "enrichContacts should enrich vendor contact with full details"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "QB Vendor Name")

        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: poVendor)

        def dbVendor = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-1", name: "DB Vendor Name",
            companyId: 100L, phone: "555-1234", email: "vendor@example.com"
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        po.vendorContact.id == "vendor-123"
        po.vendorContact.phone == "555-1234"
        po.vendorContact.email == "vendor@example.com"
        po.vendorContact.name == "QB Vendor Name" // Preserved from QB
    }

    def "enrichContacts should enrich ship-to contact by external ID"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = buildPartyWithExternalId(externalId: "customer-ext-1", name: "QB Customer Name")

        def po = buildPOWithExternalId(
            externalId: "po-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        def dbCustomer = buildPartyWithExternalId(
            id: "customer-123", externalId: "customer-ext-1",
            name: "DB Customer Name", phone: "555-5678"
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1", "customer-ext-1"]) >> [dbVendor, dbCustomer]
        po.shipToContact.id == "customer-123"
        po.shipToContact.phone == "555-5678"
        po.shipToContact.name == "QB Customer Name" // Preserved from QB
    }

    def "enrichContacts should use database name when QB name is blank"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "")

        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: poVendor)

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Fallback Vendor Name")

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        po.vendorContact.name == "Fallback Vendor Name"
    }

    def "enrichContacts should use database address when QB address is missing"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: poVendor)

        def dbVendor = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-1",
            name: "Vendor", address: "123 Main St"
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        po.vendorContact.address.address == "123 Main St"
    }

    def "enrichContacts should fallback to existing PO lookup for ship-to without external ID"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build() // No external ID

        def po = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        def existingShipTo = Party.builder()
            .id("customer-789")
            .name("Existing Ship To")
            .phone("555-9999")
            .build()

        def existingPO = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            shipToContact: existingShipTo
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        1 * purchaseOrderSyncService.findByProviderAndExternalId(100L, "quickbooks", "po-ext-1") >> existingPO
        1 * contactSyncService.findById("customer-789") >> existingShipTo
        po.shipToContact.id == "customer-789"
        po.shipToContact.phone == "555-9999"
    }

    def "enrichContacts should fallback to displayId lookup when external ID lookup fails"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build()

        def po = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        def existingShipTo = Party.builder()
            .id("customer-999")
            .name("Ship To From DisplayId")
            .email("shipto@example.com")
            .build()

        def existingPO = PurchaseOrder.builder()
            .displayId("PO-001")
            .shipToContact(existingShipTo)
            .build()

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        1 * purchaseOrderSyncService.findByProviderAndExternalId(100L, "quickbooks", "po-ext-1") >> null
        1 * purchaseOrderSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [existingPO]
        1 * contactSyncService.findById("customer-999") >> existingShipTo
        po.shipToContact.id == "customer-999"
        po.shipToContact.email == "shipto@example.com"
    }

    def "enrichContacts should skip ship-to enrichment when PO has no externalId and no displayId"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(poVendor)
            .shipToContact(poShipTo)
            .build() // No externalId

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        0 * purchaseOrderSyncService.findByProviderAndExternalId(_, _, _)
        po.shipToContact.id == null
    }

    def "enrichContacts should skip ship-to enrichment when existing PO not found"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build()

        def po = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        1 * purchaseOrderSyncService.findByProviderAndExternalId(100L, "quickbooks", "po-ext-1") >> null
        1 * purchaseOrderSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> []
        po.shipToContact.id == null
    }

    def "enrichContacts should skip ship-to enrichment when existing PO has no ship-to"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build()

        def po = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        def existingPO = buildPOWithExternalId(externalId: "po-ext-1", displayId: "PO-001")
        // No shipToContact

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        1 * purchaseOrderSyncService.findByProviderAndExternalId(100L, "quickbooks", "po-ext-1") >> existingPO
        1 * purchaseOrderSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> []
        po.shipToContact.id == null
    }

    def "enrichContacts should enrich multiple POs in batch"() {
        given:
        def po1Vendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor 1")
        def po2Vendor = buildPartyWithExternalId(externalId: "vendor-ext-2", name: "Vendor 2")

        def po1 = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: po1Vendor)
        def po2 = buildPOWithExternalId(externalId: "po-2", displayId: "PO-002", vendorContact: po2Vendor)

        def dbVendor1 = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-1",
            name: "Vendor 1", phone: "111-1111"
        )

        def dbVendor2 = buildPartyWithExternalId(
            id: "vendor-456", externalId: "vendor-ext-2",
            name: "Vendor 2", phone: "222-2222"
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po1, po2])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", _) >> [dbVendor1, dbVendor2]
        po1.vendorContact.id == "vendor-123"
        po1.vendorContact.phone == "111-1111"
        po2.vendorContact.id == "vendor-456"
        po2.vendorContact.phone == "222-2222"
    }

    def "enrichContacts should deduplicate contacts across multiple POs"() {
        given:
        def po1Vendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Same Vendor")
        def po2Vendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Same Vendor")

        def po1 = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: po1Vendor)
        def po2 = buildPOWithExternalId(externalId: "po-2", displayId: "PO-002", vendorContact: po2Vendor)

        def dbVendor = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-1",
            name: "Same Vendor", email: "vendor@example.com"
        )

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po1, po2])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        po1.vendorContact.email == "vendor@example.com"
        po2.vendorContact.email == "vendor@example.com"
    }

    def "enrichContacts should handle vendor not found in database gracefully"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-missing", name: "Missing Vendor")

        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: poVendor)

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-missing"]) >> []
        po.vendorContact.id == null
        po.vendorContact.name == "Missing Vendor" // Preserved
    }

    def "enrichContacts should handle existing ship-to with no contact ID"() {
        given:
        def poVendor = buildPartyWithExternalId(externalId: "vendor-ext-1", name: "Vendor")

        def poShipTo = Party.builder()
            .name("Ship To")
            .build()

        def po = buildPOWithExternalId(
            externalId: "po-ext-1", displayId: "PO-001",
            vendorContact: poVendor, shipToContact: poShipTo
        )

        def dbVendor = buildPartyWithExternalId(id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor")

        def existingShipTo = Party.builder()
            .name("Existing Ship To")
            .address(Address.builder().address("456 Oak St").build())
            .build() // No ID

        def existingPO = buildPOWithExternalId(externalId: "po-ext-1", shipToContact: existingShipTo)

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        1 * purchaseOrderSyncService.findByProviderAndExternalId(100L, "quickbooks", "po-ext-1") >> existingPO
        0 * contactSyncService.findById(_)
        po.shipToContact.address.address == "456 Oak St"
    }

    def "enrichContacts should preserve QB address when database has no address"() {
        given:
        def poVendor = buildPartyWithExternalId(
            externalId: "vendor-ext-1", name: "Vendor", address: "QB Address"
        )

        def po = buildPOWithExternalId(externalId: "po-1", displayId: "PO-001", vendorContact: poVendor)

        def dbVendor = buildPartyWithExternalId(
            id: "vendor-123", externalId: "vendor-ext-1", name: "Vendor"
        )
        // No address

        when:
        enricher.enrichContacts(100L, IntegrationProvider.QUICKBOOKS, [po])

        then:
        1 * contactSyncService.findByProviderAndExternalIds(100L, "quickbooks", ["vendor-ext-1"]) >> [dbVendor]
        po.vendorContact.address.address == "QB Address" // Preserved from QB
    }
}
