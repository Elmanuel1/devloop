package com.tosspaper.integrations.quickbooks.customer

import com.intuit.ipp.data.Customer
import com.intuit.ipp.data.ModificationMetaData
import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification

class CustomerPushProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    CustomerMapper customerMapper = Mock()
    QuickBooksProperties properties = Mock()
    CustomerPushProvider provider

    def setup() {
        provider = new CustomerPushProvider(apiClient, customerMapper, properties)
    }

    def "should return correct provider ID"() {
        when: "getting provider ID"
            def providerId = provider.getProviderId()

        then: "returns QUICKBOOKS"
            providerId == IntegrationProvider.QUICKBOOKS
    }

    def "should return correct entity type"() {
        when: "getting entity type"
            def entityType = provider.getEntityType()

        then: "returns JOB_LOCATION"
            entityType == IntegrationEntityType.JOB_LOCATION
    }

    def "should throw UnsupportedOperationException when getting document type"() {
        when: "getting document type"
            provider.getDocumentType()

        then: "throws exception"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("customers are not documents")
    }

    def "should return enabled status from properties when true"() {
        given: "properties enabled flag is true"
            properties.isEnabled() >> true

        when: "checking if enabled"
            def enabled = provider.isEnabled()

        then: "returns true"
            enabled
    }

    def "should return enabled status from properties when false"() {
        given: "properties enabled flag is false"
            properties.isEnabled() >> false

        when: "checking if enabled"
            def enabled = provider.isEnabled()

        then: "returns false"
            !enabled
    }

    // ==================== push(connection, customer) Tests ====================

    def "should successfully push new customer to QuickBooks"() {
        given: "a customer without external ID (CREATE)"
            def customer = Party.builder().name("Job Site Alpha").build()
            customer.id = "cust-1"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboCustomer = new Customer()
            def resultCustomer = new Customer()
            resultCustomer.id = "qb-100"
            resultCustomer.displayName = "Job Site Alpha"
            resultCustomer.syncToken = "0"
            def metaData = new ModificationMetaData()
            metaData.lastUpdatedTime = new Date()
            resultCustomer.metaData = metaData

        when: "pushing customer"
            def result = provider.push(connection, customer)

        then: "mapper is called and API saves"
            1 * customerMapper.toQboCustomer(customer) >> qboCustomer
            1 * apiClient.save(connection, qboCustomer) >> resultCustomer

        and: "result is success"
            result.success
            result.externalId == "qb-100"
            result.providerVersion == "0"
            result.providerLastUpdatedAt != null
    }

    def "should return conflict on ProviderVersionConflictException"() {
        given: "a customer with external ID (UPDATE)"
            def customer = Party.builder().name("Stale Customer").build()
            customer.id = "cust-2"
            customer.externalId = "qb-100"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboCustomer = new Customer()

        when: "pushing customer"
            def result = provider.push(connection, customer)

        then: "API throws version conflict"
            1 * customerMapper.toQboCustomer(customer) >> qboCustomer
            1 * apiClient.save(connection, qboCustomer) >> { throw new ProviderVersionConflictException("stale") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return retryable failure on generic exception"() {
        given: "a customer"
            def customer = Party.builder().name("Error Customer").build()
            customer.id = "cust-3"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboCustomer = new Customer()

        when: "pushing customer"
            def result = provider.push(connection, customer)

        then: "API throws generic exception"
            1 * customerMapper.toQboCustomer(customer) >> qboCustomer
            1 * apiClient.save(connection, qboCustomer) >> { throw new RuntimeException("Network error") }

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Failed to push customer")
    }

    def "should push via DocumentSyncRequest"() {
        given: "a document sync request wrapping a customer"
            def customer = Party.builder().name("Req Customer").build()
            customer.id = "cust-4"
            def request = DocumentSyncRequest.fromVendor(customer)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboCustomer = new Customer()
            def resultCustomer = new Customer()
            resultCustomer.id = "qb-200"
            resultCustomer.displayName = "Req Customer"
            resultCustomer.syncToken = "0"

        when: "pushing via request"
            def result = provider.push(connection, request)

        then:
            1 * customerMapper.toQboCustomer(customer) >> qboCustomer
            1 * apiClient.save(connection, qboCustomer) >> resultCustomer

        and: "result is success"
            result.success
            result.externalId == "qb-200"
    }

    // ==================== pushBatch Tests ====================

    def "should push batch of customers successfully"() {
        given: "batch of customers"
            def c1 = Party.builder().name("C1").build()
            c1.id = "c1"
            def c2 = Party.builder().name("C2").build()
            c2.id = "c2"
            def req1 = DocumentSyncRequest.fromVendor(c1)
            def req2 = DocumentSyncRequest.fromVendor(c2)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboC1 = new Customer()
            def qboC2 = new Customer()
            def rC1 = new Customer()
            rC1.id = "qb-1"
            rC1.displayName = "C1"
            rC1.syncToken = "0"
            def rC2 = new Customer()
            rC2.id = "qb-2"
            rC2.displayName = "C2"
            rC2.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then:
            1 * customerMapper.toQboCustomer(c1) >> qboC1
            1 * customerMapper.toQboCustomer(c2) >> qboC2
            1 * apiClient.saveBatch(connection, [qboC1, qboC2]) >> [
                QuickBooksApiClient.BatchResult.success(rC1),
                QuickBooksApiClient.BatchResult.success(rC2)
            ]

        and: "both succeed"
            results["c1"].success
            results["c2"].success
    }

    def "should handle batch with stale error"() {
        given: "batch with stale customer"
            def c1 = Party.builder().name("Stale").build()
            c1.id = "c1"
            def req = DocumentSyncRequest.fromVendor(c1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboC = new Customer()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * customerMapper.toQboCustomer(c1) >> qboC
            1 * apiClient.saveBatch(connection, [qboC]) >> [
                QuickBooksApiClient.BatchResult.failure("SyncToken mismatch - stale object")
            ]

        and: "result is conflict"
            results["c1"].conflictDetected
    }

    def "should handle batch exception returning error for all"() {
        given: "batch causing exception"
            def c1 = Party.builder().name("Error").build()
            c1.id = "c1"
            def req = DocumentSyncRequest.fromVendor(c1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * customerMapper.toQboCustomer(_) >> { throw new RuntimeException("Batch error") }

        and: "all results are failures"
            results["c1"].errorMessage.contains("Batch push error")
    }

    def "should handle batch result with missing result for document"() {
        given: "batch with fewer results than requests"
            def c1 = Party.builder().name("C").build()
            c1.id = "c1"
            def req = DocumentSyncRequest.fromVendor(c1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboC = new Customer()

        when: "pushing batch with empty results"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * customerMapper.toQboCustomer(c1) >> qboC
            1 * apiClient.saveBatch(connection, [qboC]) >> []

        and: "result indicates no result returned"
            !results["c1"].success
            results["c1"].errorMessage.contains("No result returned")
    }
}
