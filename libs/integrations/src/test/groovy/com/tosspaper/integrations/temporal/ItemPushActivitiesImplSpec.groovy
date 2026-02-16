package com.tosspaper.integrations.temporal

import com.tosspaper.integrations.common.SyncResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.config.PushRetryConfig
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.integrations.service.IntegrationConnectionService
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

/**
 * Comprehensive tests for ItemPushActivitiesImpl.
 * Tests all item push activities with mocked services.
 */
class ItemPushActivitiesImplSpec extends Specification {

    IntegrationConnectionService connectionService = Mock()
    ItemService itemService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    PushRetryConfig pushRetryConfig = Mock()

    @Subject
    ItemPushActivitiesImpl activities = new ItemPushActivitiesImpl(
        connectionService,
        itemService,
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

    def "fetchItemsNeedingPush should return items"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)
            def items = [
                Item.builder().id("item-1").name("Widget").build(),
                Item.builder().id("item-2").name("Gadget").build()
            ]

        when:
            def result = activities.fetchItemsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * itemService.findNeedingPush(100L, "conn-1", 10, 5) >> items
            result.size() == 2
    }

    def "fetchItemsNeedingPush should return empty list when no items need push"() {
        given:
            def connectionData = createConnectionData(id: "conn-1", companyId: 100L)

        when:
            def result = activities.fetchItemsNeedingPush(connectionData, 10)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 5
            1 * itemService.findNeedingPush(100L, "conn-1", 10, 5) >> []
            result.isEmpty()
    }

    def "pushItems should return empty map for empty items list"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

        when:
            def result = activities.pushItems(connectionData, [])

        then:
            result.isEmpty()
    }

    def "pushItems should push items successfully"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def item = Item.builder()
                .id("item-1")
                .name("Widget")
                .build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(100L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .accessToken("token")
                .build()

            def pushProvider = Mock(IntegrationPushProvider)
            def syncResult = SyncResult.success("item-ext-1", null, "1", OffsetDateTime.now())

        when:
            def result = activities.pushItems(connectionData, [item])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["item-1": syncResult]
            result.size() == 1
            result["item-1"].isSuccess()
    }

    def "pushItems should handle multiple items with mixed results"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def item1 = Item.builder().id("item-1").name("Widget").build()
            def item2 = Item.builder().id("item-2").name("Gadget").build()

            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .status(IntegrationConnectionStatus.ENABLED)
                .build()

            def pushProvider = Mock(IntegrationPushProvider)

        when:
            def result = activities.pushItems(connectionData, [item1, item2])

        then:
            1 * connectionService.findById("conn-1") >> connection
            1 * connectionService.ensureActiveToken(connection) >> connection
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [
                "item-1": SyncResult.success("ext-1", null, "1", OffsetDateTime.now()),
                "item-2": SyncResult.failure("Validation error", false)
            ]
            result.size() == 2
            result["item-1"].isSuccess()
            !result["item-2"].isSuccess()
    }

    def "pushItems should return failure results when push provider not found"() {
        given:
            def connectionData = createConnectionData(
                id: "conn-1",
                companyId: 100L,
                provider: IntegrationProvider.QUICKBOOKS
            )

            def item = Item.builder().id("item-1").name("Widget").build()

        when:
            def result = activities.pushItems(connectionData, [item])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.empty()
            result.size() == 1
            !result["item-1"].isSuccess()
            result["item-1"].getErrorMessage().contains("No push provider available")
    }

    def "markItemsAsPushed should mark successful items as pushed"() {
        given:
            def timestamp = OffsetDateTime.now()
            def syncResult = SyncResult.success("item-ext-1", null, "1", timestamp)
            def results = ["item-1": syncResult]

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.updateSyncStatus("item-1", "quickbooks", "item-ext-1", "1", timestamp)
            count == 1
    }

    def "markItemsAsPushed should mark non-retryable failures as permanently failed"() {
        given:
            def syncResult = SyncResult.failure("Duplicate name", false)
            def results = ["item-1": syncResult]

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.markAsPermanentlyFailed("item-1", "Duplicate name")
            count == 0
    }

    def "markItemsAsPushed should increment retry count for retryable failures"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["item-1": syncResult]

            def item = Item.builder()
                .id("item-1")
                .build()
            item.setPushRetryCount(1)

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * itemService.incrementRetryCount("item-1", "Network error")
            1 * itemService.findById("item-1") >> item
            0 * itemService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markItemsAsPushed should mark as permanently failed when max retries exceeded"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["item-1": syncResult]

            def item = Item.builder()
                .id("item-1")
                .build()
            item.setPushRetryCount(3)

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 3
            1 * itemService.incrementRetryCount("item-1", "Network error")
            1 * itemService.findById("item-1") >> item
            1 * itemService.markAsPermanentlyFailed("item-1", _) >> { args ->
                assert args[1].contains("Exceeded max retries")
            }
            count == 0
    }

    def "markItemsAsPushed should return 0 for empty results"() {
        when:
            def count = activities.markItemsAsPushed("quickbooks", [:])

        then:
            count == 0
    }

    def "markItemsAsPushed should handle exceptions gracefully"() {
        given:
            def syncResult = SyncResult.success("item-ext-1", null, "1", OffsetDateTime.now())
            def results = ["item-1": syncResult]

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.updateSyncStatus(_, _, _, _, _) >> { throw new RuntimeException("DB error") }
            notThrown(Exception)
            count == 0
    }

    def "markItemsAsPushed should handle multiple successful items"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "item-1": SyncResult.success("ext-1", null, "1", timestamp),
                "item-2": SyncResult.success("ext-2", null, "1", timestamp),
                "item-3": SyncResult.success("ext-3", null, "1", timestamp)
            ]

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.updateSyncStatus("item-1", "quickbooks", "ext-1", "1", timestamp)
            1 * itemService.updateSyncStatus("item-2", "quickbooks", "ext-2", "1", timestamp)
            1 * itemService.updateSyncStatus("item-3", "quickbooks", "ext-3", "1", timestamp)
            count == 3
    }

    def "markItemsAsPushed should handle mixed success and failure results"() {
        given:
            def timestamp = OffsetDateTime.now()
            def results = [
                "item-1": SyncResult.success("ext-1", null, "1", timestamp),
                "item-2": SyncResult.failure("Duplicate", false),
                "item-3": SyncResult.failure("Network error", true)
            ]

            def item3 = Item.builder().id("item-3").build()
            item3.setPushRetryCount(1)

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.updateSyncStatus("item-1", "quickbooks", "ext-1", "1", timestamp)
            1 * itemService.markAsPermanentlyFailed("item-2", "Duplicate")
            1 * itemService.incrementRetryCount("item-3", "Network error")
            1 * itemService.findById("item-3") >> item3
            _ * pushRetryConfig.getMaxAttempts() >> 10
            count == 1
    }

    def "markItemsAsPushed should handle null retry count"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["item-1": syncResult]

            def item = Item.builder().id("item-1").build()
            // pushRetryCount is null

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            _ * pushRetryConfig.getMaxAttempts() >> 10
            1 * itemService.incrementRetryCount("item-1", "Network error")
            1 * itemService.findById("item-1") >> item
            0 * itemService.markAsPermanentlyFailed(_, _)
            count == 0
    }

    def "markItemsAsPushed should handle item not found after increment"() {
        given:
            def syncResult = SyncResult.failure("Network error", true)
            def results = ["item-1": syncResult]

        when:
            def count = activities.markItemsAsPushed("quickbooks", results)

        then:
            1 * itemService.incrementRetryCount("item-1", "Network error")
            1 * itemService.findById("item-1") >> null
            0 * itemService.markAsPermanentlyFailed(_, _)
            count == 0
    }
}
