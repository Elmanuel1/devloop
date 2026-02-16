package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.common.DependencyCoordinatorService
import com.tosspaper.integrations.common.DependencyPushResult
import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.config.PushRetryConfig
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.common.PushResult
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.service.InvoiceSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for IntegrationPushActivitiesImpl.
 * Tests all push activities with mocked services.
 */
class IntegrationPushActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    InvoiceSyncService invoiceSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    PushRetryConfig pushRetryConfig = Mock()
    DependencyCoordinatorService dependencyCoordinator = Mock()

    @Subject
    IntegrationPushActivitiesImpl activities = new IntegrationPushActivitiesImpl(
        connectionService,
        invoiceSyncService,
        providerFactory,
        pushRetryConfig,
        dependencyCoordinator
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

    def "fetchAcceptedInvoicesNeedingPush should return invoices"() {
        given:
        def connectionData = createConnectionData(id: "conn-1", companyId: 100L)

        def invoices = [
            Invoice.builder().assignedId("inv-1").build(),
            Invoice.builder().assignedId("inv-2").build()
        ]

        when:
        def result = activities.fetchAcceptedInvoicesNeedingPush(connectionData, 10)

        then:
        1 * invoiceSyncService.findAcceptedNeedingPush(100L, 10) >> invoices
        result.size() == 2
    }

    def "pushInvoicesAsBills should return empty map for empty invoices list"() {
        given:
        def connectionData = createConnectionData(
            id: "conn-1", companyId: 100L,
            provider: IntegrationProvider.QUICKBOOKS
        )

        when:
        def result = activities.pushInvoicesAsBills(connectionData, [])

        then:
        result.isEmpty()
    }

    def "pushInvoicesAsBills should push invoices successfully"() {
        given:
        def connectionData = createConnectionData(
            id: "conn-1", companyId: 100L,
            provider: IntegrationProvider.QUICKBOOKS
        )

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(IntegrationConnectionStatus.ENABLED)
            .accessToken("token")
            .build()

        def pushProvider = Mock(IntegrationPushProvider)
        def syncResult = SyncResult.success("bill-ext-1", "1")

        when:
        def result = activities.pushInvoicesAsBills(connectionData, [invoice])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.BILL) >> Optional.of(pushProvider)
        1 * pushProvider.isEnabled() >> true
        1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.BILL, [invoice]) >> DependencyPushResult.success()
        1 * pushProvider.pushBatch(connection, _) >> ["inv-1": syncResult]
        result.size() == 1
        result["inv-1"].isSuccess()
    }

    def "pushInvoicesAsBills should return failure when dependency resolution fails"() {
        given:
        def connectionData = createConnectionData(
            id: "conn-1", companyId: 100L,
            provider: IntegrationProvider.QUICKBOOKS
        )

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(IntegrationConnectionStatus.ENABLED)
            .accessToken("token")
            .build()

        def pushProvider = Mock(IntegrationPushProvider)

        when:
        def result = activities.pushInvoicesAsBills(connectionData, [invoice])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.BILL) >> Optional.of(pushProvider)
        1 * pushProvider.isEnabled() >> true
        1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.BILL, [invoice]) >> DependencyPushResult.failure("Vendor not found")
        result.size() == 1
        !result["inv-1"].isSuccess()
        result["inv-1"].getErrorMessage().contains("Dependency resolution failed")
    }

    def "pushInvoicesAsBills should return empty map when push provider is disabled"() {
        given:
        def connectionData = createConnectionData(
            id: "conn-1", companyId: 100L,
            provider: IntegrationProvider.QUICKBOOKS
        )

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        def pushProvider = Mock(IntegrationPushProvider)

        when:
        def result = activities.pushInvoicesAsBills(connectionData, [invoice])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.BILL) >> Optional.of(pushProvider)
        1 * pushProvider.isEnabled() >> false
        result.isEmpty()
    }

    def "pushInvoicesAsBills should throw exception when push provider not found"() {
        given:
        def connectionData = createConnectionData(
            id: "conn-1", companyId: 100L,
            provider: IntegrationProvider.QUICKBOOKS
        )

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()

        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        when:
        activities.pushInvoicesAsBills(connectionData, [invoice])

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.ensureActiveToken(connection) >> connection
        1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.BILL) >> Optional.empty()
        thrown(IntegrationException)
    }

    def "markInvoicesAsPushed should mark successful invoices as pushed"() {
        given:
        def syncResult = SyncResult.success("bill-ext-1", "1")
        def results = ["inv-1": syncResult]

        when:
        def count = activities.markInvoicesAsPushed(results)

        then:
        1 * invoiceSyncService.markAsPushed(_) >> { args ->
            List<PushResult> pushResults = args[0]
            assert pushResults.size() == 1
            assert pushResults[0].documentId == "inv-1"
            assert pushResults[0].externalId == "bill-ext-1"
            return 1
        }
        count == 1
    }

    def "markInvoicesAsPushed should mark non-retryable failures as permanently failed"() {
        given:
        def syncResult = SyncResult.failure("Duplicate name", false)
        def results = ["inv-1": syncResult]

        when:
        def count = activities.markInvoicesAsPushed(results)

        then:
        1 * invoiceSyncService.markAsPermanentlyFailed("inv-1", "Duplicate name")
        count == 0
    }

    def "markInvoicesAsPushed should increment retry count for retryable failures"() {
        given:
        def syncResult = SyncResult.failure("Network error", true)
        def results = ["inv-1": syncResult]

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()
        invoice.setPushRetryCount(1)

        when:
        def count = activities.markInvoicesAsPushed(results)

        then:
        _ * pushRetryConfig.getMaxAttempts() >> 10
        1 * invoiceSyncService.incrementRetryCount("inv-1", "Network error")
        1 * invoiceSyncService.findById("inv-1") >> invoice
        0 * invoiceSyncService.markAsPermanentlyFailed(_, _)
        count == 0
    }

    def "markInvoicesAsPushed should mark as permanently failed when max retries exceeded"() {
        given:
        def syncResult = SyncResult.failure("Network error", true)
        def results = ["inv-1": syncResult]

        def invoice = Invoice.builder()
            .assignedId("inv-1")
            .build()
        invoice.setPushRetryCount(3)

        when:
        def count = activities.markInvoicesAsPushed(results)

        then:
        _ * pushRetryConfig.getMaxAttempts() >> 3
        1 * invoiceSyncService.incrementRetryCount("inv-1", "Network error")
        1 * invoiceSyncService.findById("inv-1") >> invoice
        1 * invoiceSyncService.markAsPermanentlyFailed("inv-1", _) >> { args ->
            assert args[1].contains("Exceeded max retries")
        }
        count == 0
    }

    def "markInvoicesAsPushed should return 0 for empty results"() {
        when:
        def count = activities.markInvoicesAsPushed([:])

        then:
        count == 0
    }

    def "markInvoicesAsPushed should handle exceptions gracefully"() {
        given:
        def syncResult = SyncResult.success("bill-ext-1", "1")
        def results = ["inv-1": syncResult]

        when:
        def count = activities.markInvoicesAsPushed(results)

        then:
        1 * invoiceSyncService.markAsPushed(_) >> { throw new RuntimeException("DB error") }
        notThrown(Exception)
        count == 0
    }
}
