package com.tosspaper.integrations.quickbooks.vendor

import com.intuit.ipp.data.ModificationMetaData
import com.intuit.ipp.data.Vendor
import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.exception.DuplicateException
import spock.lang.Specification

class VendorPushProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    VendorMapper vendorMapper = Mock()
    QuickBooksProperties properties = Mock()
    VendorPushProvider provider

    def setup() {
        provider = new VendorPushProvider(apiClient, vendorMapper, properties)
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

        then: "returns VENDOR"
            entityType == IntegrationEntityType.VENDOR
    }

    def "should throw UnsupportedOperationException when getting document type"() {
        when: "getting document type"
            provider.getDocumentType()

        then: "throws exception"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("vendors are not documents")
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

    // ==================== push(connection, vendor) Tests ====================

    def "should successfully push new vendor to QuickBooks"() {
        given: "a vendor without external ID (CREATE)"
            def vendor = Party.builder().name("ACME Corp").build()
            vendor.id = "vendor-1"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboVendor = new Vendor()
            def resultVendor = new Vendor()
            resultVendor.id = "qb-42"
            resultVendor.displayName = "ACME Corp"
            resultVendor.syncToken = "0"
            def metaData = new ModificationMetaData()
            metaData.lastUpdatedTime = new Date()
            resultVendor.metaData = metaData

        when: "pushing vendor"
            def result = provider.push(connection, vendor)

        then: "mapper is called and API saves the vendor"
            1 * vendorMapper.toQboVendor(vendor) >> qboVendor
            1 * apiClient.save(connection, qboVendor) >> resultVendor

        and: "result is success with external ID"
            result.success
            result.externalId == "qb-42"
            result.providerVersion == "0"
            result.providerLastUpdatedAt != null
    }

    def "should return conflict on ProviderVersionConflictException"() {
        given: "a vendor with external ID (UPDATE)"
            def vendor = Party.builder().name("Stale Vendor").build()
            vendor.id = "vendor-2"
            vendor.externalId = "qb-42"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboVendor = new Vendor()

        when: "pushing vendor"
            def result = provider.push(connection, vendor)

        then: "API throws version conflict"
            1 * vendorMapper.toQboVendor(vendor) >> qboVendor
            1 * apiClient.save(connection, qboVendor) >> { throw new ProviderVersionConflictException("stale") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return conflict on DuplicateException"() {
        given: "a vendor that already exists"
            def vendor = Party.builder().name("Duplicate Vendor").build()
            vendor.id = "vendor-3"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboVendor = new Vendor()

        when: "pushing vendor"
            def result = provider.push(connection, vendor)

        then: "API throws duplicate exception"
            1 * vendorMapper.toQboVendor(vendor) >> qboVendor
            1 * apiClient.save(connection, qboVendor) >> { throw new DuplicateException("duplicate name") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return retryable failure on generic exception"() {
        given: "a vendor"
            def vendor = Party.builder().name("Error Vendor").build()
            vendor.id = "vendor-4"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboVendor = new Vendor()

        when: "pushing vendor"
            def result = provider.push(connection, vendor)

        then: "API throws generic exception"
            1 * vendorMapper.toQboVendor(vendor) >> qboVendor
            1 * apiClient.save(connection, qboVendor) >> { throw new RuntimeException("Network error") }

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Failed to push vendor")
    }

    def "should push via DocumentSyncRequest"() {
        given: "a document sync request wrapping a vendor"
            def vendor = Party.builder().name("Request Vendor").build()
            vendor.id = "vendor-5"
            def request = DocumentSyncRequest.fromVendor(vendor)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboVendor = new Vendor()
            def resultVendor = new Vendor()
            resultVendor.id = "qb-55"
            resultVendor.displayName = "Request Vendor"
            resultVendor.syncToken = "0"

        when: "pushing via request"
            def result = provider.push(connection, request)

        then: "mapper and API are called"
            1 * vendorMapper.toQboVendor(vendor) >> qboVendor
            1 * apiClient.save(connection, qboVendor) >> resultVendor

        and: "result is success"
            result.success
            result.externalId == "qb-55"
    }

    // ==================== pushBatch Tests ====================

    def "should push batch of vendors successfully"() {
        given: "batch of vendors"
            def vendor1 = Party.builder().name("Vendor 1").build()
            vendor1.id = "v1"
            def vendor2 = Party.builder().name("Vendor 2").build()
            vendor2.id = "v2"

            def req1 = DocumentSyncRequest.fromVendor(vendor1)
            def req2 = DocumentSyncRequest.fromVendor(vendor2)

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboV1 = new Vendor()
            def qboV2 = new Vendor()

            def resultV1 = new Vendor()
            resultV1.id = "qb-1"
            resultV1.displayName = "Vendor 1"
            resultV1.syncToken = "0"

            def resultV2 = new Vendor()
            resultV2.id = "qb-2"
            resultV2.displayName = "Vendor 2"
            resultV2.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then: "mapper converts each vendor"
            1 * vendorMapper.toQboVendor(vendor1) >> qboV1
            1 * vendorMapper.toQboVendor(vendor2) >> qboV2

        and: "API batch save is called"
            1 * apiClient.saveBatch(connection, [qboV1, qboV2]) >> [
                QuickBooksApiClient.BatchResult.success(resultV1),
                QuickBooksApiClient.BatchResult.success(resultV2)
            ]

        and: "both results are success"
            results.size() == 2
            results["v1"].success
            results["v1"].externalId == "qb-1"
            results["v2"].success
            results["v2"].externalId == "qb-2"
    }

    def "should handle batch with stale error"() {
        given: "a batch with one stale vendor"
            def vendor = Party.builder().name("Stale").build()
            vendor.id = "v1"
            def req = DocumentSyncRequest.fromVendor(vendor)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboV = new Vendor()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * vendorMapper.toQboVendor(vendor) >> qboV
            1 * apiClient.saveBatch(connection, [qboV]) >> [
                QuickBooksApiClient.BatchResult.failure("Stale object error: SyncToken mismatch")
            ]

        and: "result is conflict"
            results["v1"].conflictDetected
    }

    def "should handle batch with duplicate name error"() {
        given: "a batch with duplicate name"
            def vendor = Party.builder().name("Dup").build()
            vendor.id = "v1"
            def req = DocumentSyncRequest.fromVendor(vendor)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboV = new Vendor()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * vendorMapper.toQboVendor(vendor) >> qboV
            1 * apiClient.saveBatch(connection, [qboV]) >> [
                QuickBooksApiClient.BatchResult.failure("Duplicate Name Exists Error")
            ]

        and: "result is conflict"
            results["v1"].conflictDetected
    }

    def "should handle batch exception returning error for all"() {
        given: "a batch that causes an exception"
            def vendor = Party.builder().name("Error").build()
            vendor.id = "v1"
            def req = DocumentSyncRequest.fromVendor(vendor)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * vendorMapper.toQboVendor(_) >> { throw new RuntimeException("Batch error") }

        and: "all results are failures"
            results["v1"].errorMessage.contains("Batch push error")
    }

    def "should handle batch result with missing result for document"() {
        given: "a batch with fewer results than requests"
            def vendor = Party.builder().name("V").build()
            vendor.id = "v1"
            def req = DocumentSyncRequest.fromVendor(vendor)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboV = new Vendor()

        when: "pushing batch with empty results"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * vendorMapper.toQboVendor(vendor) >> qboV
            1 * apiClient.saveBatch(connection, [qboV]) >> []

        and: "result indicates no result returned"
            !results["v1"].success
            results["v1"].errorMessage.contains("No result returned")
    }
}
