package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.config.PushRetryConfig
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.ContactSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for VendorPushActivitiesImpl.
 * Tests all vendor push activities with mocked services.
 */
class VendorPushActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    ContactSyncService contactSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    PushRetryConfig pushRetryConfig = Mock()

    @Subject
    VendorPushActivitiesImpl activities = new VendorPushActivitiesImpl(
        connectionService,
        contactSyncService,
        providerFactory,
        pushRetryConfig
    )

    private static SyncConnectionData createConnectionData(Map args) {
        new SyncConnectionData(
            args.id as String,
            args.companyId as Long,
            args.provider as IntegrationProvider,
            args.expiresAt as OffsetDateTime,
            args.realmId as String,
            args.lastSyncAt as OffsetDateTime,
            args.syncFrom as OffsetDateTime
        )
    }

    def "getConnection should return connection data when connection is enabled"() {
        given:
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

        when:
            def result = activities.getConnection("conn-1")

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            result.id == "conn-1"
            result.companyId == 100L
            result.provider == IntegrationProvider.QUICKBOOKS
    }

    def "getConnection should throw exception when connection not found"() {
        when:
            activities.getConnection("conn-999")

        then:
            1 * connectionService.findById("conn-999") >> null
            thrown(IntegrationException)
    }

    def "getConnection should throw exception when connection is disabled"() {
        given:
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.DISABLED)
                .build()

        when:
            activities.getConnection("conn-1")

        then:
            1 * connectionService.findById("conn-1") >> connection
            thrown(IntegrationException)
    }

    def "fetchVendorsNeedingPush should return vendors"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)
            def vendors = [
                Party.builder().id("vendor-1").name("Vendor A").build(),
                Party.builder().id("vendor-2").name("Vendor B").build()
            ]

        when:
            def result = activities.fetchVendorsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * contactSyncService.findNeedingPush(100L, 10, ["vendor", "supplier"], 5) >> vendors
            result.size() == 2
    }

    def "fetchVendorsNeedingPush should return empty list when no vendors need push"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)

        when:
            def result = activities.fetchVendorsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * contactSyncService.findNeedingPush(100L, 10, ["vendor", "supplier"], 5) >> []
            result.isEmpty()
    }

    def "pushVendors should return empty map for empty vendors list"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

        when:
            def result = activities.pushVendors(connectionData, [])

        then:
            result.isEmpty()
    }

    def "pushVendors should push vendors successfully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def vendor = Party.builder()
                .id("vendor-1")
                .name("ACME Corp")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

            def pushProvider = Mock(IntegrationPushProvider)
            def syncResult = SyncResult.success("vendor-ext-1", null, "1", OffsetDateTime.now())

        when:
            def result = activities.pushVendors(connectionData, [vendor])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["vendor-1": syncResult]
            result.size() == 1
            result["vendor-1"].isSuccess()
    }

    def "pushVendors should handle multiple vendors with mixed results"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def vendor1 = Party.builder().id("vendor-1").name("ACME Corp").build()
            def vendor2 = Party.builder().id("vendor-2").name("XYZ Inc").build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pushProvider = Mock(IntegrationPushProvider)

        when:
            def result = activities.pushVendors(connectionData, [vendor1, vendor2])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [
                "vendor-1": SyncResult.success("ext-1", null, "1", OffsetDateTime.now()),
                "vendor-2": SyncResult.failure("Duplicate name", false)
            ]
            result.size() == 2
            result["vendor-1"].isSuccess()
            !result["vendor-2"].isSuccess()
    }

    def "pushVendors should return failure results when push provider not found"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def vendor = Party.builder().id("vendor-1").name("ACME Corp").build()

        when:
            def result = activities.pushVendors(connectionData, [vendor])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.empty()
            result.size() == 1
            !result["vendor-1"].isSuccess()
            result["vendor-1"].getErrorMessage().contains("No push provider available")
    }

    def "markVendorsAsPushed should mark successful vendors as pushed"() {
        given:
            def timestamp = OffsetDateTime.now()
            def syncResult = SyncResult.success("vendor-ext-1", null, "1", timestamp)
            def results = ["vendor-1": syncResult]

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("vendor-1", "quickbooks", "vendor-ext-1", "1", timestamp)
            count == 1
    }

    def "markVendorsAsPushed should mark non-retryable failures as permanently failed"() {
        given:
            def syncResult = SyncResult.failure("Duplicate name", false)
            def results = ["vendor-1": syncResult]

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.markAsPermanentlyFailed("vendor-1", "Duplicate name")
            count == 0
    }

    def "markVendorsAsPushed should increment retry count for retryable failures"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["vendor-1": syncResult]

            def vendor = Party.builder()
                .id("vendor-1")
                .build()
            vendor.setPushRetryCount(1)

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * contactSyncService.incrementRetryCount("vendor-1", "Network error")
            1 * contactSyncService.findById("vendor-1") >> vendor
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markVendorsAsPushed should mark as permanently failed when max retries exceeded"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["vendor-1": syncResult]

            def vendor = Party.builder()
                .id("vendor-1")
                .build()
            vendor.setPushRetryCount(3)

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 3
            1 * contactSyncService.incrementRetryCount("vendor-1", "Network error")
            1 * contactSyncService.findById("vendor-1") >> vendor
            1 * contactSyncService.markAsPermanentlyFailed("vendor-1", _) >> { args ->
                assert args[1].contains("Exceeded max retries")
            }
            count == 0
    }

    def "markVendorsAsPushed should return 0 for empty results"() {
        when:
            def count = activities.markVendorsAsPushed("quickbooks", [:])

        then:
            count == 0
    }

    def "markVendorsAsPushed should handle exceptions gracefully"() {
        given:
            def syncResult = SyncResult.success("vendor-ext-1", null, "1", OffsetDateTime.now())
            def results = ["vendor-1": syncResult]

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus(_, _, _, _, _) >> { throw new RuntimeException("DB error") }
            notThrown(Exception)
            count == 0
    }

    def "markVendorsAsPushed should handle multiple successful vendors"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "vendor-1": SyncResult.success("ext-1", null, "1", timestamp),
                "vendor-2": SyncResult.success("ext-2", null, "1", timestamp),
                "vendor-3": SyncResult.success("ext-3", null, "1", timestamp)
            ]

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("vendor-1", "quickbooks", "ext-1", "1", timestamp)
            1 * contactSyncService.updateSyncStatus("vendor-2", "quickbooks", "ext-2", "1", timestamp)
            1 * contactSyncService.updateSyncStatus("vendor-3", "quickbooks", "ext-3", "1", timestamp)
            count == 3
    }

    def "markVendorsAsPushed should handle mixed success and failure results"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "vendor-1": SyncResult.success("ext-1", null, "1", timestamp),
                "vendor-2": SyncResult.failure("Duplicate", false),
                "vendor-3": SyncResult.failure("Network error", true)
            ]

            def vendor3 = Party.builder().id("vendor-3").build()
            vendor3.setPushRetryCount(1)

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("vendor-1", "quickbooks", "ext-1", "1", timestamp)
            1 * contactSyncService.markAsPermanentlyFailed("vendor-2", "Duplicate")
            1 * contactSyncService.incrementRetryCount("vendor-3", "Network error")
            1 * contactSyncService.findById("vendor-3") >> vendor3
            _ * pushRetryConfig.getMaxAttempts() >> 10
            count == 1
    }

    def "markVendorsAsPushed should handle null retry count"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["vendor-1": syncResult]

            def vendor = Party.builder().id("vendor-1").build()
            // pushRetryCount is null

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * contactSyncService.incrementRetryCount("vendor-1", "Network error")
            1 * contactSyncService.findById("vendor-1") >> vendor
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markVendorsAsPushed should handle vendor not found after increment"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["vendor-1": syncResult]

        when:
            def count = activities.markVendorsAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.incrementRetryCount("vendor-1", "Network error")
            1 * contactSyncService.findById("vendor-1") >> null
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }
}
