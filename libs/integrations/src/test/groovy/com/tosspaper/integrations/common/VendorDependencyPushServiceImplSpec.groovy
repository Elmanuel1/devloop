package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.ContactSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class VendorDependencyPushServiceImplSpec extends Specification {

    ContactSyncService contactSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationPushProvider pushProvider = Mock()

    @Subject
    VendorDependencyPushServiceImpl service = new VendorDependencyPushServiceImpl(
        contactSyncService, providerFactory
    )

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return success when all vendors already have external IDs"() {
        given: "vendors that already have external IDs"
            def vendor = Party.builder().name("Existing").build()
            vendor.id = "v1"
            vendor.externalId = "qb-42"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "no push is needed"
            0 * providerFactory._
            0 * pushProvider._
            result.success
    }

    def "should push vendors without external IDs and update sync status"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("New Vendor").build()
            vendor.id = "v1"
            def now = OffsetDateTime.now()
            def syncResult = SyncResult.success("qb-42", "New Vendor", "0", now)

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "push provider is obtained and batch push is called"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["v1": syncResult]

        and: "sync status is batch-updated"
            1 * contactSyncService.batchUpdateSyncStatus(_)

        and: "in-memory vendor is updated"
            vendor.externalId == "qb-42"
            vendor.provider == "quickbooks"
            vendor.providerVersion == "0"

        and: "result is success"
            result.success
    }

    def "should return failure when push result is missing for vendor"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("Missing Result").build()
            vendor.id = "v1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "push provider is obtained and batch push returns empty results"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [:]

        and: "result is failure"
            !result.success
            result.message.contains("No result returned")
    }

    def "should return failure and mark as permanently failed when push fails with non-retryable error"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("Fail Vendor").build()
            vendor.id = "v1"
            def failResult = SyncResult.builder()
                .success(false)
                .errorMessage("Duplicate name")
                .retryable(false)
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "push provider is obtained and batch push fails"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["v1": failResult]

        and: "vendor is marked as permanently failed"
            1 * contactSyncService.markAsPermanentlyFailed("v1", "Duplicate name")

        and: "result is failure"
            !result.success
    }

    def "should return failure and increment retry count when push fails with retryable error"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("Retry Vendor").build()
            vendor.id = "v1"
            def failResult = SyncResult.builder()
                .success(false)
                .errorMessage("Network error")
                .retryable(true)
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "push provider is obtained and batch push fails"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["v1": failResult]

        and: "retry count is incremented"
            1 * contactSyncService.incrementRetryCount("v1", "Network error")

        and: "result is failure"
            !result.success
    }

    def "should return failure when batch push throws exception"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("Exception Vendor").build()
            vendor.id = "v1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor])

        then: "push provider is obtained and batch push throws"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> { throw new RuntimeException("Batch error") }

        and: "result is failure"
            !result.success
            result.message.contains("Exception during batch push")
    }

    def "should throw when no push provider is found"() {
        given: "a vendor without external ID"
            def vendor = Party.builder().name("No Provider").build()
            vendor.id = "v1"

        when: "ensuring external IDs"
            service.ensureHaveExternalIds(connection, [vendor])

        then: "no push provider found"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.empty()

        and: "exception is thrown"
            thrown(IllegalStateException)
    }

    def "should only push vendors that need external IDs"() {
        given: "a mix of vendors with and without external IDs"
            def vendor1 = Party.builder().name("Existing").build()
            vendor1.id = "v1"
            vendor1.externalId = "qb-existing"

            def vendor2 = Party.builder().name("New").build()
            vendor2.id = "v2"

            def syncResult = SyncResult.success("qb-new", "New", "0", OffsetDateTime.now())

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [vendor1, vendor2])

        then: "only the vendor without external ID is pushed"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, { it.size() == 1 }) >> ["v2": syncResult]
            1 * contactSyncService.batchUpdateSyncStatus(_)

        and: "result is success"
            result.success
    }
}
