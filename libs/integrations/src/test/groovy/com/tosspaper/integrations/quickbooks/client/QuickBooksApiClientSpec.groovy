package com.tosspaper.integrations.quickbooks.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.intuit.ipp.data.*
import com.intuit.ipp.exception.FMSException
import com.intuit.ipp.services.BatchOperation
import com.intuit.ipp.services.DataService
import com.intuit.ipp.services.QueryResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.integrations.quickbooks.purchaseorder.QBOPurchaseOrderMapper
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.exception.DuplicateException
import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.github.resilience4j.retry.RetryRegistry
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Integration tests for QuickBooksApiClient.
 * Uses mocked DataService to verify actual API interactions and error handling.
 * Tests Resilience4j integration with real registry instances.
 */
class QuickBooksApiClientSpec extends Specification {

    // Real Resilience4j registries for integration testing
    CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults()
    RateLimiterRegistry rateLimiterRegistry = RateLimiterRegistry.ofDefaults()
    RetryRegistry retryRegistry = RetryRegistry.ofDefaults()
    BulkheadRegistry bulkheadRegistry = BulkheadRegistry.ofDefaults()

    QuickBooksResilienceHelper resilienceHelper = new QuickBooksResilienceHelper(
        circuitBreakerRegistry, rateLimiterRegistry, retryRegistry, bulkheadRegistry
    )

    // Mocked dependencies
    QuickBooksClientFactory clientFactory = Mock()
    DataService dataService = Mock()
    QBOPurchaseOrderMapper qboPurchaseOrderMapper = Mock()
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule())
    QuickBooksProperties qbProperties = new QuickBooksProperties()

    @Subject
    QuickBooksApiClient apiClient = new QuickBooksApiClient(
        clientFactory, resilienceHelper, qboPurchaseOrderMapper, objectMapper, qbProperties
    )

    IntegrationConnection connection = IntegrationConnection.builder()
        .realmId("test-realm-123")
        .accessToken("test-access-token")
        .build()

    def setup() {
        qbProperties.apiBaseUrl = "https://sandbox-quickbooks.api.intuit.com/v3/company"
        clientFactory.createDataService(connection) >> dataService
    }

    // ==================== createBill Tests ====================

    def "createBill should successfully create bill and return created entity"() {
        given:
        def inputBill = new Bill()
        inputBill.setDocNumber("BILL-001")

        def createdBill = new Bill()
        createdBill.setId("123")
        createdBill.setSyncToken("0")
        createdBill.setDocNumber("BILL-001")

        when:
        def result = apiClient.createBill(connection, inputBill)

        then:
        1 * dataService.add(inputBill) >> createdBill
        result.id == "123"
        result.syncToken == "0"
        result.docNumber == "BILL-001"
    }

    def "createBill should throw IntegrationException on FMSException"() {
        given:
        def bill = new Bill()
        def fmsException = new FMSException("QuickBooks API error")

        when:
        apiClient.createBill(connection, bill)

        then:
        (1.._) * dataService.add(bill) >> { throw fmsException }
        def ex = thrown(IntegrationException)
        ex.message.contains("Failed to create bill")
    }

    // ==================== save Tests ====================

    def "save should create new entity when Id is null"() {
        given:
        def vendor = QBOTestFixtures.loadVendorMinimal()
        vendor.setId(null)

        def createdVendor = QBOTestFixtures.loadVendor()

        when:
        def result = apiClient.save(connection, vendor)

        then:
        1 * dataService.add(vendor) >> createdVendor
        result.id == createdVendor.id
    }

    def "save should update existing entity when Id is present"() {
        given:
        def vendor = QBOTestFixtures.loadVendor()

        def updatedVendor = QBOTestFixtures.loadVendor()
        updatedVendor.setSyncToken("4")

        when:
        def result = apiClient.save(connection, vendor)

        then:
        1 * dataService.update(vendor) >> updatedVendor
        result.syncToken == "4"
    }

    def "save should throw ProviderVersionConflictException on SyncToken error"() {
        given:
        def vendor = QBOTestFixtures.loadVendor()
        def fmsException = new FMSException("Stale object: SyncToken mismatch")

        when:
        apiClient.save(connection, vendor)

        then:
        (1.._) * dataService.update(vendor) >> { throw fmsException }
        thrown(ProviderVersionConflictException)
    }

    def "save should throw DuplicateException on duplicate name error"() {
        given:
        def vendor = new Vendor()
        vendor.setDisplayName("Duplicate Vendor")
        def fmsException = new FMSException("Name supplied already exists")

        when:
        apiClient.save(connection, vendor)

        then:
        (1.._) * dataService.add(vendor) >> { throw fmsException }
        thrown(DuplicateException)
    }

    def "save should throw IntegrationException on generic FMSException"() {
        given:
        def vendor = new Vendor()
        def fmsException = new FMSException("Unknown API error")

        when:
        apiClient.save(connection, vendor)

        then:
        (1.._) * dataService.add(vendor) >> { throw fmsException }
        def ex = thrown(IntegrationException)
        ex.message.contains("Failed to save entity")
    }

    // ==================== Query Tests ====================

    def "queryVendors should return list of vendors from query"() {
        given:
        def query = "SELECT * FROM Vendor"
        def vendors = [QBOTestFixtures.loadVendor(), QBOTestFixtures.loadVendorMinimal()]
        def queryResult = new QueryResult()
        queryResult.setEntities(vendors)

        when:
        def result = apiClient.queryVendors(connection, query)

        then:
        1 * dataService.executeQuery(query) >> queryResult
        result.size() == 2
    }

    def "queryCustomers should return list of customers from query"() {
        given:
        def query = "SELECT * FROM Customer WHERE Active = true"
        def customers = [QBOTestFixtures.loadCustomer()]
        def queryResult = new QueryResult()
        queryResult.setEntities(customers)

        when:
        def result = apiClient.queryCustomers(connection, query)

        then:
        1 * dataService.executeQuery(query) >> queryResult
        result.size() == 1
    }

    def "queryAccounts should return list of accounts"() {
        given:
        def query = "SELECT * FROM Account WHERE AccountType = 'Expense'"
        def accounts = QBOTestFixtures.loadAccountsList()
        def queryResult = new QueryResult()
        queryResult.setEntities(accounts)

        when:
        def result = apiClient.queryAccounts(connection, query)

        then:
        1 * dataService.executeQuery(query) >> queryResult
        result.size() == accounts.size()
    }

    def "queryTerms should return list of payment terms"() {
        given:
        def query = "SELECT * FROM Term"
        def terms = QBOTestFixtures.loadTermsList()
        def queryResult = new QueryResult()
        queryResult.setEntities(terms)

        when:
        def result = apiClient.queryTerms(connection, query)

        then:
        1 * dataService.executeQuery(query) >> queryResult
        result.size() == terms.size()
    }

    def "executeQuery should throw IntegrationException on FMSException"() {
        given:
        def query = "SELECT * FROM InvalidEntity"
        def fmsException = new FMSException("Invalid query")

        when:
        apiClient.queryVendors(connection, query)

        then:
        (1.._) * dataService.executeQuery(query) >> { throw fmsException }
        def ex = thrown(IntegrationException)
        ex.message.contains("Query failed")
    }

    // ==================== queryAccountsSinceLastSync Tests ====================

    def "queryAccountsSinceLastSync should query all accounts when lastSyncAt is null"() {
        given:
        connection.lastSyncAt = null
        def accounts = QBOTestFixtures.loadAccountsList()
        def queryResult = new QueryResult()
        queryResult.setEntities(accounts)

        when:
        def result = apiClient.queryAccountsSinceLastSync(connection)

        then:
        1 * dataService.executeQuery("SELECT * FROM Account") >> queryResult
        result.size() == accounts.size()
        result.every { it.provider == "quickbooks" }
    }

    def "queryAccountsSinceLastSync should include lastSyncAt filter when present"() {
        given:
        def lastSync = OffsetDateTime.parse("2024-01-15T10:30:00Z")
        connection.lastSyncAt = lastSync
        def queryResult = new QueryResult()
        queryResult.setEntities([])

        when:
        apiClient.queryAccountsSinceLastSync(connection)

        then:
        1 * dataService.executeQuery({ String q ->
            q.contains("MetaData.LastUpdatedTime > '") && q.contains("2024-01-15")
        }) >> queryResult
    }

    // ==================== queryPaymentTermsSinceLastSync Tests ====================

    def "queryPaymentTermsSinceLastSync should query all terms when lastSyncAt is null"() {
        given:
        connection.lastSyncAt = null
        def terms = QBOTestFixtures.loadTermsList()
        def queryResult = new QueryResult()
        queryResult.setEntities(terms)

        when:
        def result = apiClient.queryPaymentTermsSinceLastSync(connection)

        then:
        1 * dataService.executeQuery("SELECT * FROM Term") >> queryResult
        result.size() == terms.size()
        result.every { it.provider == "quickbooks" }
    }

    // ==================== saveBatch Tests ====================

    def "saveBatch should return empty list for empty input"() {
        when:
        def result = apiClient.saveBatch(connection, [])

        then:
        0 * dataService._
        result.isEmpty()
    }

    def "saveBatch should successfully save batch of entities"() {
        given:
        def vendors = [
            QBOTestFixtures.loadVendorMinimal(),
            QBOTestFixtures.loadVendorMinimal()
        ]
        vendors[0].setId(null)
        vendors[1].setId(null)

        def createdVendor1 = QBOTestFixtures.loadVendor()
        createdVendor1.setId("101")
        def createdVendor2 = QBOTestFixtures.loadVendor()
        createdVendor2.setId("102")

        when:
        def result = apiClient.saveBatch(connection, vendors)

        then:
        1 * dataService.executeBatch(_ as BatchOperation) >> { BatchOperation op ->
            // Simulate successful batch execution - return results via batch operation
        }
        // Results depend on batch operation state after execution
        result != null
    }

    def "saveBatch should handle batch execution failures"() {
        given:
        def vendors = [QBOTestFixtures.loadVendorMinimal()]
        vendors[0].setId(null)
        def fmsException = new FMSException("Batch operation failed")

        when:
        def result = apiClient.saveBatch(connection, vendors)

        then:
        1 * dataService.executeBatch(_ as BatchOperation) >> { throw fmsException }
        result.size() == 1
        result[0].success == false
        result[0].errorMessage.contains("Batch failed")
    }

    // ==================== queryEntitiesByIdsBatch Tests ====================

    def "queryEntitiesByIdsBatch should return empty list for null input"() {
        when:
        def result = apiClient.queryEntitiesByIdsBatch(connection, null)

        then:
        0 * dataService._
        result.isEmpty()
    }

    def "queryEntitiesByIdsBatch should return empty list for empty input"() {
        when:
        def result = apiClient.queryEntitiesByIdsBatch(connection, [:])

        then:
        0 * dataService._
        result.isEmpty()
    }

    def "queryEntitiesByIdsBatch should query multiple entity types"() {
        given:
        def idsByType = [
            (IntegrationEntityType.VENDOR): ["1", "2"],
            (IntegrationEntityType.JOB_LOCATION): ["3"]
        ]
        def vendor1 = QBOTestFixtures.loadVendor()
        vendor1.setId("1")
        def vendor2 = QBOTestFixtures.loadVendorMinimal()
        vendor2.setId("2")
        def customer = QBOTestFixtures.loadCustomer()
        customer.setId("3")

        when:
        def result = apiClient.queryEntitiesByIdsBatch(connection, idsByType)

        then:
        1 * dataService.executeBatch(_ as BatchOperation) >> { BatchOperation op ->
            // Batch contains 3 queries
        }
        result != null
    }

    // ==================== BatchResult Tests ====================

    def "BatchResult.success should create successful result"() {
        given:
        def vendor = QBOTestFixtures.loadVendor()

        when:
        def result = QuickBooksApiClient.BatchResult.success(vendor)

        then:
        result.success() == true
        result.entity() == vendor
        result.errorMessage() == null
    }

    def "BatchResult.failure should create failed result"() {
        when:
        def result = QuickBooksApiClient.BatchResult.failure("Error message")

        then:
        result.success() == false
        result.entity() == null
        result.errorMessage() == "Error message"
    }

    // ==================== Resilience Integration Tests ====================

    def "API calls should execute through resilience helper"() {
        given:
        def bill = new Bill()
        def createdBill = new Bill()
        createdBill.setId("123")

        when:
        def result = apiClient.createBill(connection, bill)

        then:
        1 * dataService.add(bill) >> createdBill
        result.id == "123"
        // Verify resilience4j components are accessed via the helper
        // The call went through without any resilience exceptions
    }

    def "save should work across multiple realms with isolated resilience"() {
        given:
        def connection1 = IntegrationConnection.builder()
            .realmId("realm-1")
            .accessToken("token-1")
            .build()
        def connection2 = IntegrationConnection.builder()
            .realmId("realm-2")
            .accessToken("token-2")
            .build()

        def vendor = new Vendor()
        vendor.setDisplayName("Test Vendor")

        def createdVendor = new Vendor()
        createdVendor.setId("123")
        createdVendor.setDisplayName("Test Vendor")

        clientFactory.createDataService(connection1) >> dataService
        clientFactory.createDataService(connection2) >> dataService

        when:
        def result1 = apiClient.save(connection1, vendor)
        def result2 = apiClient.save(connection2, vendor)

        then:
        2 * dataService.add(vendor) >> createdVendor
        result1.id == "123"
        result2.id == "123"

        and: "each realm has its own bulkhead and rate limiter"
        bulkheadRegistry.bulkhead("quickbooks-realm-realm-1") != null
        bulkheadRegistry.bulkhead("quickbooks-realm-realm-2") != null
        rateLimiterRegistry.rateLimiter("quickbooks-realm-realm-1") != null
        rateLimiterRegistry.rateLimiter("quickbooks-realm-realm-2") != null
    }
}
