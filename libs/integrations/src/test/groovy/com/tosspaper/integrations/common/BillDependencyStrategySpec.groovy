package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.domain.LineItem
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.PurchaseOrderItem
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.extraction.dto.Comparison
import com.tosspaper.models.extraction.dto.ComparisonResult
import com.tosspaper.models.service.ContactSyncService
import com.tosspaper.models.service.DocumentPartComparisonService
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

import com.tosspaper.models.extraction.dto.Party as ExtractionParty

/**
 * Comprehensive tests for BillDependencyStrategy.
 * Tests all dependency resolution scenarios for bills.
 */
class BillDependencyStrategySpec extends Specification {

    VendorDependencyPushService vendorService = Mock()
    PurchaseOrderDependencyPushService poPushService = Mock()
    ContactSyncService contactService = Mock()
    PurchaseOrderSyncService poSyncService = Mock()
    DocumentPartComparisonService comparisonService = Mock()

    @Subject
    BillDependencyStrategy strategy = new BillDependencyStrategy(
        vendorService,
        poPushService,
        contactService,
        poSyncService,
        comparisonService
    )

    def "supports should return true for BILL entity type"() {
        expect:
        strategy.supports(IntegrationEntityType.BILL)
    }

    def "supports should return false for non-BILL entity types"() {
        expect:
        !strategy.supports(IntegrationEntityType.VENDOR)
        !strategy.supports(IntegrationEntityType.PURCHASE_ORDER)
        !strategy.supports(IntegrationEntityType.ITEM)
    }

    def "ensureDependencies should fail when invoice has no PO number"() {
        given:
        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .build()

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        !result.isSuccess()
        result.getMessage().contains("missing required PO number")
    }

    def "ensureDependencies should fail when PO not found"() {
        given:
        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-999")
            .build()

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-999"]) >> []
        !result.isSuccess()
        result.getMessage().contains("Purchase Order PO-999 not found")
    }

    def "ensureDependencies should fail when PO has no vendor"() {
        given:
        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .build()

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        !result.isSuccess()
        result.getMessage().contains("does not have a linked Vendor")
    }

    def "ensureDependencies should fail when vendor not found in DB"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .build()

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> []
        !result.isSuccess()
        result.getMessage().contains("Vendor vendor-123 not found")
    }

    def "ensureDependencies should ensure vendor has external ID"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()

        def sellerInfo = new ExtractionParty()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(sellerInfo)
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        result.isSuccess()
        invoice.sellerInfo.referenceNumber == "vendor-ext-123"
        invoice.sellerInfo.name == "ACME Corp"
    }

    def "ensureDependencies should fail when vendor external ID push fails"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(new ExtractionParty())
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.failure("Vendor push failed")
        !result.isSuccess()
        result.getMessage() == "Vendor push failed"
    }

    def "ensureDependencies should fail when invoice missing seller info"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        !result.isSuccess()
        result.getMessage().contains("missing seller info")
    }

    def "ensureDependencies should enrich invoice line items from PO"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoiceLineItem = LineItem.builder()
            .description("Widget")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(new ExtractionParty())
            .lineItems([invoiceLineItem])
            .build()

        def poLineItem = PurchaseOrderItem.builder()
            .name("Widget")
            .externalItemId("item-ext-1")
            .externalAccountId("account-ext-1")
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .items([poLineItem])
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME Corp")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .build()

        def comparisonResult = new ComparisonResult()
            .withType(ComparisonResult.Type.LINE_ITEM)
            .withExtractedIndex(0L)
            .withPoIndex(0L)
            .withMatchScore(0.95)

        def comparison = new Comparison()
            .withResults([comparisonResult])

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        1 * comparisonService.getComparisonByAssignedId("inv-1", 100L) >> Optional.of(comparison)
        result.isSuccess()
        invoiceLineItem.externalItemId == "item-ext-1"
        invoiceLineItem.externalAccountId == "account-ext-1"
    }

    def "ensureDependencies should skip enrichment when no comparison found"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoiceLineItem = LineItem.builder()
            .description("Widget")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(new ExtractionParty())
            .lineItems([invoiceLineItem])
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .items([])
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        1 * comparisonService.getComparisonByAssignedId("inv-1", 100L) >> Optional.empty()
        result.isSuccess()
        invoiceLineItem.externalItemId == null
    }

    def "ensureDependencies should handle multiple invoices with same vendor"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoice1 = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(new ExtractionParty())
            .build()

        def invoice2 = Invoice.builder()
            .assignedId("inv-2")
            .documentNumber("INV-002")
            .poNumber("PO-002")
            .sellerInfo(new ExtractionParty())
            .build()

        def po1 = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .build()

        def po2 = PurchaseOrder.builder()
            .displayId("PO-002")
            .vendorContact(vendorContact)
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        when:
        def result = strategy.ensureDependencies(connection, [invoice1, invoice2])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001", "PO-002"]) >> [po1, po2]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        result.isSuccess()
    }

    def "ensureDependencies should skip line item enrichment when indices out of bounds"() {
        given:
        def vendorContact = Party.builder()
            .id("vendor-123")
            .build()

        def invoiceLineItem = LineItem.builder()
            .description("Widget")
            .build()

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .documentNumber("INV-001")
            .poNumber("PO-001")
            .sellerInfo(new ExtractionParty())
            .lineItems([invoiceLineItem])
            .build()

        def po = PurchaseOrder.builder()
            .displayId("PO-001")
            .vendorContact(vendorContact)
            .items([])
            .build()

        def vendor = Party.builder()
            .id("vendor-123")
            .name("ACME")
            .build()
        vendor.setExternalId("vendor-ext-123")

        def connection = IntegrationConnection.builder()
            .companyId(100L)
            .build()

        def comparisonResult = new ComparisonResult()
            .withType(ComparisonResult.Type.LINE_ITEM)
            .withExtractedIndex(0L)
            .withPoIndex(10L) // Out of bounds
            .withMatchScore(0.95)

        def comparison = new Comparison()
            .withResults([comparisonResult])

        when:
        def result = strategy.ensureDependencies(connection, [invoice])

        then:
        1 * poSyncService.findByCompanyIdAndDisplayIds(100L, ["PO-001"]) >> [po]
        1 * contactService.findByIds(["vendor-123"]) >> [vendor]
        1 * vendorService.ensureHaveExternalIds(connection, [vendor]) >> DependencyPushResult.success()
        1 * comparisonService.getComparisonByAssignedId("inv-1", 100L) >> Optional.of(comparison)
        result.isSuccess()
        invoiceLineItem.externalItemId == null
    }
}
