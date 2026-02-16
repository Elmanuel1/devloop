package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.common.IntegrationPushCoordinator
import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.config.PushRetryConfig
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for POPushActivitiesImpl.
 * Tests all purchase order push activities with mocked services.
 */
class POPushActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    PurchaseOrderSyncService purchaseOrderSyncService = Mock()
    IntegrationPushCoordinator pushCoordinator = Mock()
    PushRetryConfig pushRetryConfig = Mock()

    @Subject
    POPushActivitiesImpl activities = new POPushActivitiesImpl(
        connectionService,
        purchaseOrderSyncService,
        pushCoordinator,
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

    def "fetchPOsNeedingPush should return purchase orders"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)
            def pos = [
                PurchaseOrder.builder().id("po-1").displayId("PO-001").build(),
                PurchaseOrder.builder().id("po-2").displayId("PO-002").build()
            ]

        when:
            def result = activities.fetchPOsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * purchaseOrderSyncService.findNeedingPush(100L, 10, 5) >> pos
            result.size() == 2
    }

    def "fetchPOsNeedingPush should return empty list when no POs need push"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)

        when:
            def result = activities.fetchPOsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * purchaseOrderSyncService.findNeedingPush(100L, 10, 5) >> []
            result.isEmpty()
    }

    def "pushPOs should return empty map for empty POs list"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

        when:
            def result = activities.pushPOs(connectionData, [])

        then:
            result.isEmpty()
    }

    def "pushPOs should push purchase orders successfully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def po = PurchaseOrder.builder()
                .id("po-1")
                .displayId("PO-001")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

            def syncResult = SyncResult.success("po-ext-1", null, "1", OffsetDateTime.now())

        when:
            def result = activities.pushPOs(connectionData, [po])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushBatchWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, [po]) >> ["po-1": syncResult]
            result.size() == 1
            result["po-1"].isSuccess()
    }

    def "pushPOs should handle multiple POs with mixed results"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def po1 = PurchaseOrder.builder().id("po-1").displayId("PO-001").build()
            def po2 = PurchaseOrder.builder().id("po-2").displayId("PO-002").build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def timestamp = OffsetDateTime.now()

        when:
            def result = activities.pushPOs(connectionData, [po1, po2])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * pushCoordinator.pushBatchWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, _) >> [
                "po-1": SyncResult.success("ext-1", null, "1", timestamp),
                "po-2": SyncResult.failure("Validation error", false)
            ]
            result.size() == 2
            result["po-1"].isSuccess()
            !result["po-2"].isSuccess()
    }

    def "markPOsAsPushed should mark successful POs as pushed"() {
        given:
            def timestamp = OffsetDateTime.now()
            def syncResult = SyncResult.success("po-ext-1", null, "1", timestamp)
            def results = ["po-1": syncResult]

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.updateSyncStatus("po-1", "po-ext-1", "1", timestamp)
            count == 1
    }

    def "markPOsAsPushed should mark non-retryable failures as permanently failed"() {
        given:
            def syncResult = SyncResult.failure("Duplicate name", false)
            def results = ["po-1": syncResult]

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.markAsPermanentlyFailed("po-1", "Duplicate name")
            count == 0
    }

    def "markPOsAsPushed should increment retry count for retryable failures"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["po-1": syncResult]

            def po = PurchaseOrder.builder()
                .id("po-1")
                .build()
            po.setPushRetryCount(1)

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * purchaseOrderSyncService.incrementRetryCount("po-1", "Network error")
            1 * purchaseOrderSyncService.findById("po-1") >> po
            0 * purchaseOrderSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markPOsAsPushed should mark as permanently failed when max retries exceeded"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["po-1": syncResult]

            def po = PurchaseOrder.builder()
                .id("po-1")
                .build()
            po.setPushRetryCount(3)

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 3
            1 * purchaseOrderSyncService.incrementRetryCount("po-1", "Network error")
            1 * purchaseOrderSyncService.findById("po-1") >> po
            1 * purchaseOrderSyncService.markAsPermanentlyFailed("po-1", _) >> { args ->
                assert args[1].contains("Exceeded max retries")
            }
            count == 0
    }

    def "markPOsAsPushed should return 0 for empty results"() {
        when:
            def count = activities.markPOsAsPushed([:])

        then:
            count == 0
    }

    def "markPOsAsPushed should handle exceptions gracefully"() {
        given:
            def syncResult = SyncResult.success("po-ext-1", null, "1", OffsetDateTime.now())
            def results = ["po-1": syncResult]

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.updateSyncStatus(_, _, _, _) >> { throw new RuntimeException("DB error") }
            notThrown(Exception)
            count == 0
    }

    def "markPOsAsPushed should handle multiple successful POs"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "po-1": SyncResult.success("ext-1", null, "1", timestamp),
                "po-2": SyncResult.success("ext-2", null, "1", timestamp),
                "po-3": SyncResult.success("ext-3", null, "1", timestamp)
            ]

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.updateSyncStatus("po-1", "ext-1", "1", timestamp)
            1 * purchaseOrderSyncService.updateSyncStatus("po-2", "ext-2", "1", timestamp)
            1 * purchaseOrderSyncService.updateSyncStatus("po-3", "ext-3", "1", timestamp)
            count == 3
    }

    def "markPOsAsPushed should handle mixed success and failure results"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "po-1": SyncResult.success("ext-1", null, "1", timestamp),
                "po-2": SyncResult.failure("Duplicate", false),
                "po-3": SyncResult.failure("Network error", true)
            ]

            def po3 = PurchaseOrder.builder().id("po-3").build()
            po3.setPushRetryCount(1)

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.updateSyncStatus("po-1", "ext-1", "1", timestamp)
            1 * purchaseOrderSyncService.markAsPermanentlyFailed("po-2", "Duplicate")
            1 * purchaseOrderSyncService.incrementRetryCount("po-3", "Network error")
            1 * purchaseOrderSyncService.findById("po-3") >> po3
            _ * pushRetryConfig.getMaxAttempts() >> 10
            count == 1
    }

    def "markPOsAsPushed should handle null retry count"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["po-1": syncResult]

            def po = PurchaseOrder.builder().id("po-1").build()
            // pushRetryCount is null

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * purchaseOrderSyncService.incrementRetryCount("po-1", "Network error")
            1 * purchaseOrderSyncService.findById("po-1") >> po
            0 * purchaseOrderSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markPOsAsPushed should handle PO not found after increment"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["po-1": syncResult]

        when:
            def count = activities.markPOsAsPushed(results)

        then:
            1 * purchaseOrderSyncService.incrementRetryCount("po-1", "Network error")
            1 * purchaseOrderSyncService.findById("po-1") >> null
            0 * purchaseOrderSyncService.markAsPermanentlyFailed(_, _)
            count == 0
    }
}
