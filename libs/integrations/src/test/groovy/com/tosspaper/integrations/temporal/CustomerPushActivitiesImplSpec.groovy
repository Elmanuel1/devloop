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
 * Comprehensive tests for CustomerPushActivitiesImpl.
 * Tests all customer push activities with mocked services.
 */
class CustomerPushActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    ContactSyncService contactSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    PushRetryConfig pushRetryConfig = Mock()

    @Subject
    CustomerPushActivitiesImpl activities = new CustomerPushActivitiesImpl(
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

    def "fetchCustomersNeedingPush should return customers"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)
            def customers = [
                Party.builder().id("customer-1").name("Job Site A").build(),
                Party.builder().id("customer-2").name("Job Site B").build()
            ]

        when:
            def result = activities.fetchCustomersNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * contactSyncService.findNeedingPush(100L, 10, ["ship_to"], 5) >> customers
            result.size() == 2
    }

    def "fetchCustomersNeedingPush should return empty list when no customers need push"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)

        when:
            def result = activities.fetchCustomersNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * contactSyncService.findNeedingPush(100L, 10, ["ship_to"], 5) >> []
            result.isEmpty()
    }

    def "pushCustomers should return empty map for empty customers list"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

        when:
            def result = activities.pushCustomers(connectionData, [])

        then:
            result.isEmpty()
    }

    def "pushCustomers should push customers successfully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def customer = Party.builder()
                .id("customer-1")
                .name("Job Site A")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

            def pushProvider = Mock(IntegrationPushProvider)
            def syncResult = SyncResult.success("customer-ext-1", null, "1", OffsetDateTime.now())

        when:
            def result = activities.pushCustomers(connectionData, [customer])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["customer-1": syncResult]
            result.size() == 1
            result["customer-1"].isSuccess()
    }

    def "pushCustomers should handle multiple customers with mixed results"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def customer1 = Party.builder().id("customer-1").name("Job Site A").build()
            def customer2 = Party.builder().id("customer-2").name("Job Site B").build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pushProvider = Mock(IntegrationPushProvider)
            def timestamp = OffsetDateTime.now()

        when:
            def result = activities.pushCustomers(connectionData, [customer1, customer2])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [
                "customer-1": SyncResult.success("ext-1", null, "1", timestamp),
                "customer-2": SyncResult.failure("Validation error", false)
            ]
            result.size() == 2
            result["customer-1"].isSuccess()
            !result["customer-2"].isSuccess()
    }

    def "pushCustomers should return failure results when push provider not found"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def customer = Party.builder().id("customer-1").name("Job Site A").build()

        when:
            def result = activities.pushCustomers(connectionData, [customer])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.JOB_LOCATION) >> Optional.empty()
            result.size() == 1
            !result["customer-1"].isSuccess()
            result["customer-1"].getErrorMessage().contains("No push provider available")
    }

    def "markCustomersAsPushed should mark successful customers as pushed"() {
        given:
            def timestamp = OffsetDateTime.now()
            def syncResult = SyncResult.success("customer-ext-1", null, "1", timestamp)
            def results = ["customer-1": syncResult]

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("customer-1", "quickbooks", "customer-ext-1", "1", timestamp)
            count == 1
    }

    def "markCustomersAsPushed should mark non-retryable failures as permanently failed"() {
        given:
            def syncResult = SyncResult.failure("Duplicate name", false)
            def results = ["customer-1": syncResult]

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.markAsPermanentlyFailed("customer-1", "Duplicate name")
            count == 0
    }

    def "markCustomersAsPushed should increment retry count for retryable failures"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["customer-1": syncResult]

            def customer = Party.builder()
                .id("customer-1")
                .build()
            customer.setPushRetryCount(1)

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * contactSyncService.incrementRetryCount("customer-1", "Network error")
            1 * contactSyncService.findById("customer-1") >> customer
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markCustomersAsPushed should mark as permanently failed when max retries exceeded"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["customer-1": syncResult]

            def customer = Party.builder()
                .id("customer-1")
                .build()
            customer.setPushRetryCount(3)

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 3
            1 * contactSyncService.incrementRetryCount("customer-1", "Network error")
            1 * contactSyncService.findById("customer-1") >> customer
            1 * contactSyncService.markAsPermanentlyFailed("customer-1", _) >> { args ->
                assert args[1].contains("Exceeded max retries")
            }
            count == 0
    }

    def "markCustomersAsPushed should return 0 for empty results"() {
        when:
            def count = activities.markCustomersAsPushed("quickbooks", [:])

        then:
            count == 0
    }

    def "markCustomersAsPushed should handle exceptions gracefully"() {
        given:
            def syncResult = SyncResult.success("customer-ext-1", null, "1", OffsetDateTime.now())
            def results = ["customer-1": syncResult]

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus(_, _, _, _, _) >> { throw new RuntimeException("DB error") }
            notThrown(Exception)
            count == 0
    }

    def "markCustomersAsPushed should handle multiple successful customers"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "customer-1": SyncResult.success("ext-1", null, "1", timestamp),
                "customer-2": SyncResult.success("ext-2", null, "1", timestamp),
                "customer-3": SyncResult.success("ext-3", null, "1", timestamp)
            ]

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("customer-1", "quickbooks", "ext-1", "1", timestamp)
            1 * contactSyncService.updateSyncStatus("customer-2", "quickbooks", "ext-2", "1", timestamp)
            1 * contactSyncService.updateSyncStatus("customer-3", "quickbooks", "ext-3", "1", timestamp)
            count == 3
    }

    def "markCustomersAsPushed should handle mixed success and failure results"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "customer-1": SyncResult.success("ext-1", null, "1", timestamp),
                "customer-2": SyncResult.failure("Duplicate", false),
                "customer-3": SyncResult.failure("Network error", true)
            ]

            def customer3 = Party.builder().id("customer-3").build()
            customer3.setPushRetryCount(1)

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.updateSyncStatus("customer-1", "quickbooks", "ext-1", "1", timestamp)
            1 * contactSyncService.markAsPermanentlyFailed("customer-2", "Duplicate")
            1 * contactSyncService.incrementRetryCount("customer-3", "Network error")
            1 * contactSyncService.findById("customer-3") >> customer3
            _ * pushRetryConfig.getMaxAttempts() >> 10
            count == 1
    }

    def "markCustomersAsPushed should handle null retry count"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["customer-1": syncResult]

            def customer = Party.builder().id("customer-1").build()
            // pushRetryCount is null

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * contactSyncService.incrementRetryCount("customer-1", "Network error")
            1 * contactSyncService.findById("customer-1") >> customer
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markCustomersAsPushed should handle customer not found after increment"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["customer-1": syncResult]

        when:
            def count = activities.markCustomersAsPushed("quickbooks", results)

        then:
            1 * contactSyncService.incrementRetryCount("customer-1", "Network error")
            1 * contactSyncService.findById("customer-1") >> null
            0 * contactSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }
}
