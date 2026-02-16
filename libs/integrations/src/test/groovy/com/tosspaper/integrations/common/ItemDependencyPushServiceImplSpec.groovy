package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.service.ItemService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class ItemDependencyPushServiceImplSpec extends Specification {

    ItemService itemService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationPushProvider pushProvider = Mock()

    @Subject
    ItemDependencyPushServiceImpl service = new ItemDependencyPushServiceImpl(
        itemService, providerFactory
    )

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return success when all items already have external IDs"() {
        given: "items with external IDs"
            def item = Item.builder().name("Existing").build()
            item.id = "i1"
            item.externalId = "qb-10"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item])

        then: "no push needed"
            0 * providerFactory._
            result.success
    }

    def "should push items without external IDs and update sync status"() {
        given: "an item without external ID"
            def item = Item.builder().name("New Item").build()
            item.id = "i1"
            def syncResult = SyncResult.builder()
                .success(true)
                .externalId("qb-10")
                .providerVersion("0")
                .providerLastUpdatedAt(OffsetDateTime.now())
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item])

        then: "push provider is obtained and batch push is called"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["i1": syncResult]

        and: "sync status is batch-updated"
            1 * itemService.batchUpdateSyncStatus(_)

        and: "in-memory item is updated"
            item.externalId == "qb-10"
            item.provider == "quickbooks"

        and: "result is success"
            result.success
    }

    def "should return failure when push result is missing"() {
        given: "an item without external ID"
            def item = Item.builder().name("Missing").build()
            item.id = "i1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [:]

        and: "result is failure"
            !result.success
            result.message.contains("No result returned")
    }

    def "should return failure when push fails"() {
        given: "an item without external ID"
            def item = Item.builder().name("Fail").build()
            item.id = "i1"
            def failResult = SyncResult.failure("Push failed", true)

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["i1": failResult]

        and: "result is failure"
            !result.success
    }

    def "should return failure when batch push throws exception"() {
        given: "an item without external ID"
            def item = Item.builder().name("Exception").build()
            item.id = "i1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> { throw new RuntimeException("Error") }

        and: "result is failure"
            !result.success
    }

    def "should throw when no push provider is found"() {
        given: "an item without external ID"
            def item = Item.builder().name("No Provider").build()
            item.id = "i1"

        when: "ensuring external IDs"
            service.ensureHaveExternalIds(connection, [item])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.empty()
            thrown(IllegalStateException)
    }

    def "should only push items that need external IDs"() {
        given: "a mix of items"
            def item1 = Item.builder().name("Existing").build()
            item1.id = "i1"
            item1.externalId = "qb-existing"

            def item2 = Item.builder().name("New").build()
            item2.id = "i2"

            def syncResult = SyncResult.builder()
                .success(true)
                .externalId("qb-new")
                .providerVersion("0")
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [item1, item2])

        then: "only the item without external ID is pushed"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, { it.size() == 1 }) >> ["i2": syncResult]
            1 * itemService.batchUpdateSyncStatus(_)
            result.success
    }
}
