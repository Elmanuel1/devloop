package com.tosspaper.integrations.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.jakarta.xmlbind.JakartaXmlBindAnnotationModule
import com.intuit.ipp.data.*

/**
 * Helper class for loading QBO test fixtures from JSON files.
 * Uses Jackson ObjectMapper to deserialize QBO SDK objects.
 * 
 * The QBO SDK classes use JAXB @XmlElement annotations with PascalCase names
 * matching the QuickBooks API (e.g., "DisplayName", "SyncToken", "Id").
 * We register the JaxbAnnotationModule so Jackson uses these XML element names
 * for JSON property mapping.
 */
class QBOTestFixtures {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new JakartaXmlBindAnnotationModule())

    // ==================== Vendor Fixtures ====================

    static Vendor loadVendor() {
        return loadVendor('qbo/vendor.json')
    }

    static Vendor loadVendorMinimal() {
        return loadVendor('qbo/vendor-minimal.json')
    }

    static Vendor loadVendorInactive() {
        return loadVendor('qbo/vendor-inactive.json')
    }

    static Vendor loadVendor(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Vendor'), Vendor)
    }

    // ==================== Customer Fixtures ====================

    static Customer loadCustomer() {
        return loadCustomer('qbo/customer.json')
    }

    static Customer loadCustomerNotJobLocation() {
        return loadCustomer('qbo/customer-not-job-location.json')
    }

    static Customer loadCustomerMinimal() {
        return loadCustomer('qbo/customer-minimal.json')
    }

    static Customer loadCustomer(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Customer'), Customer)
    }

    // ==================== Item Fixtures ====================

    static Item loadItem() {
        return loadItem('qbo/item.json')
    }

    static Item loadItemService() {
        return loadItem('qbo/item-service.json')
    }

    static Item loadItemNonInventory() {
        return loadItem('qbo/item-noninventory.json')
    }

    static Item loadItemCategory() {
        return loadItem('qbo/item-category.json')
    }

    static Item loadItem(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Item'), Item)
    }

    // ==================== PurchaseOrder Fixtures ====================

    static PurchaseOrder loadPurchaseOrder() {
        return loadPurchaseOrder('qbo/purchase-order.json')
    }

    static PurchaseOrder loadPurchaseOrderClosed() {
        return loadPurchaseOrder('qbo/purchase-order-closed.json')
    }

    static PurchaseOrder loadPurchaseOrderMinimal() {
        return loadPurchaseOrder('qbo/purchase-order-minimal.json')
    }

    static PurchaseOrder loadPurchaseOrderAccountBased() {
        return loadPurchaseOrder('qbo/purchase-order-account-based.json')
    }

    static PurchaseOrder loadPurchaseOrder(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('PurchaseOrder'), PurchaseOrder)
    }

    // ==================== Bill Fixtures ====================

    static Bill loadBill() {
        return loadBill('qbo/bill.json')
    }

    static Bill loadBillMinimal() {
        return loadBill('qbo/bill-minimal.json')
    }

    static Bill loadBill(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Bill'), Bill)
    }

    // ==================== Account Fixtures ====================

    static Account loadAccount() {
        return loadAccount('qbo/account.json')
    }

    static List<Account> loadAccountsList() {
        def json = loadResource('qbo/accounts-list.json')
        def node = OBJECT_MAPPER.readTree(json)
        def accountsNode = node.get('QueryResponse').get('Account')
        return OBJECT_MAPPER.readerForListOf(Account).readValue(accountsNode)
    }

    static Account loadAccount(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Account'), Account)
    }

    // ==================== Term Fixtures ====================

    static Term loadTerm() {
        return loadTerm('qbo/term.json')
    }

    static List<Term> loadTermsList() {
        def json = loadResource('qbo/terms-list.json')
        def node = OBJECT_MAPPER.readTree(json)
        def termsNode = node.get('QueryResponse').get('Term')
        return OBJECT_MAPPER.readerForListOf(Term).readValue(termsNode)
    }

    static Term loadTerm(String resourcePath) {
        def json = loadResource(resourcePath)
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Term'), Term)
    }

    // ==================== Preferences Fixtures ====================

    static Preferences loadPreferences() {
        def json = loadResource('qbo/preferences.json')
        def node = OBJECT_MAPPER.readTree(json)
        return OBJECT_MAPPER.treeToValue(node.get('Preferences'), Preferences)
    }

    // ==================== CDC Fixtures ====================

    static JsonNode loadCDCResponse() {
        def json = loadResource('qbo/cdc-response.json')
        return OBJECT_MAPPER.readTree(json)
    }

    // ==================== Batch Response Fixtures ====================

    static JsonNode loadBatchResponse() {
        def json = loadResource('qbo/batch-response.json')
        return OBJECT_MAPPER.readTree(json)
    }

    // ==================== Raw JSON Loading ====================

    static String loadResourceAsString(String resourcePath) {
        return loadResource(resourcePath)
    }

    static JsonNode loadResourceAsJsonNode(String resourcePath) {
        def json = loadResource(resourcePath)
        return OBJECT_MAPPER.readTree(json)
    }

    // ==================== Helper Methods ====================

    private static String loadResource(String resourcePath) {
        def stream = QBOTestFixtures.class.getClassLoader().getResourceAsStream(resourcePath)
        if (stream == null) {
            throw new IllegalArgumentException("Resource not found: ${resourcePath}")
        }
        return stream.text
    }

    static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER
    }
}
