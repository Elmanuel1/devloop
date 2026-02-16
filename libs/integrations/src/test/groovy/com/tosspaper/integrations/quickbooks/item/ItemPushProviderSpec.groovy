package com.tosspaper.integrations.quickbooks.item

import com.intuit.ipp.data.ModificationMetaData
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import com.tosspaper.models.exception.DuplicateException
import spock.lang.Specification

class ItemPushProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    ItemMapper itemMapper = Mock()
    QuickBooksProperties properties = Mock()
    ItemPushProvider provider

    def setup() {
        provider = new ItemPushProvider(apiClient, itemMapper, properties)
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

        then: "returns ITEM"
            entityType == IntegrationEntityType.ITEM
    }

    def "should throw UnsupportedOperationException when getting document type"() {
        when: "getting document type"
            provider.getDocumentType()

        then: "throws exception"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("items are not documents")
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

    // ==================== push(connection, item) Tests ====================

    def "should successfully push new item to QuickBooks"() {
        given: "an item without external ID (CREATE)"
            def item = Item.builder().name("Widget").build()
            item.id = "item-1"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboItem = new com.intuit.ipp.data.Item()
            def resultItem = new com.intuit.ipp.data.Item()
            resultItem.id = "qb-10"
            resultItem.syncToken = "0"

        when: "pushing item"
            def result = provider.push(connection, item)

        then: "mapper is called and API saves"
            1 * itemMapper.toQboItem(item) >> qboItem
            1 * apiClient.save(connection, qboItem) >> resultItem

        and: "result is success"
            result.success
            result.externalId == "qb-10"
            result.providerVersion == "0"
    }

    def "should return conflict on ProviderVersionConflictException"() {
        given: "an item with external ID (UPDATE)"
            def item = Item.builder().name("Stale Item").build()
            item.id = "item-2"
            item.externalId = "qb-10"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboItem = new com.intuit.ipp.data.Item()

        when: "pushing item"
            def result = provider.push(connection, item)

        then:
            1 * itemMapper.toQboItem(item) >> qboItem
            1 * apiClient.save(connection, qboItem) >> { throw new ProviderVersionConflictException("stale") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return conflict on DuplicateException"() {
        given: "a duplicate item"
            def item = Item.builder().name("Dup Item").build()
            item.id = "item-3"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboItem = new com.intuit.ipp.data.Item()

        when: "pushing item"
            def result = provider.push(connection, item)

        then:
            1 * itemMapper.toQboItem(item) >> qboItem
            1 * apiClient.save(connection, qboItem) >> { throw new DuplicateException("duplicate name") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return retryable failure on generic exception"() {
        given: "an item"
            def item = Item.builder().name("Error Item").build()
            item.id = "item-4"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboItem = new com.intuit.ipp.data.Item()

        when: "pushing item"
            def result = provider.push(connection, item)

        then:
            1 * itemMapper.toQboItem(item) >> qboItem
            1 * apiClient.save(connection, qboItem) >> { throw new RuntimeException("Network error") }

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Failed to push item")
    }

    def "should push via DocumentSyncRequest"() {
        given: "a document sync request wrapping an item"
            def item = Item.builder().name("Req Item").build()
            item.id = "item-5"
            def request = DocumentSyncRequest.fromItem(item)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboItem = new com.intuit.ipp.data.Item()
            def resultItem = new com.intuit.ipp.data.Item()
            resultItem.id = "qb-50"
            resultItem.syncToken = "0"

        when: "pushing via request"
            def result = provider.push(connection, request)

        then:
            1 * itemMapper.toQboItem(item) >> qboItem
            1 * apiClient.save(connection, qboItem) >> resultItem

        and: "result is success"
            result.success
            result.externalId == "qb-50"
    }

    // ==================== pushBatch Tests ====================

    def "should push batch of items successfully"() {
        given: "batch of items"
            def i1 = Item.builder().name("Item 1").build()
            i1.id = "i1"
            def i2 = Item.builder().name("Item 2").build()
            i2.id = "i2"
            def req1 = DocumentSyncRequest.fromItem(i1)
            def req2 = DocumentSyncRequest.fromItem(i2)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboI1 = new com.intuit.ipp.data.Item()
            def qboI2 = new com.intuit.ipp.data.Item()
            def rI1 = new com.intuit.ipp.data.Item()
            rI1.id = "qb-1"
            rI1.syncToken = "0"
            def rI2 = new com.intuit.ipp.data.Item()
            rI2.id = "qb-2"
            rI2.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then:
            1 * itemMapper.toQboItem(i1) >> qboI1
            1 * itemMapper.toQboItem(i2) >> qboI2
            1 * apiClient.saveBatch(connection, [qboI1, qboI2]) >> [
                QuickBooksApiClient.BatchResult.success(rI1),
                QuickBooksApiClient.BatchResult.success(rI2)
            ]

        and: "both succeed"
            results["i1"].success
            results["i1"].externalId == "qb-1"
            results["i2"].success
    }

    def "should handle batch with stale error"() {
        given: "batch with stale item"
            def i1 = Item.builder().name("Stale").build()
            i1.id = "i1"
            def req = DocumentSyncRequest.fromItem(i1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboI = new com.intuit.ipp.data.Item()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * itemMapper.toQboItem(i1) >> qboI
            1 * apiClient.saveBatch(connection, [qboI]) >> [
                QuickBooksApiClient.BatchResult.failure("Stale object: synctoken mismatch")
            ]

        and: "result is conflict"
            results["i1"].conflictDetected
    }

    def "should handle batch exception returning error for all"() {
        given: "batch causing exception"
            def i1 = Item.builder().name("Error").build()
            i1.id = "i1"
            def req = DocumentSyncRequest.fromItem(i1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * itemMapper.toQboItem(_) >> { throw new RuntimeException("Batch error") }

        and: "all results are failures"
            results["i1"].errorMessage.contains("Batch push error")
    }

    def "should handle batch result with missing result for document"() {
        given: "batch with fewer results"
            def i1 = Item.builder().name("I").build()
            i1.id = "i1"
            def req = DocumentSyncRequest.fromItem(i1)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboI = new com.intuit.ipp.data.Item()

        when: "pushing batch with empty results"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * itemMapper.toQboItem(i1) >> qboI
            1 * apiClient.saveBatch(connection, [qboI]) >> []

        and: "result indicates no result returned"
            !results["i1"].success
            results["i1"].errorMessage.contains("No result returned")
    }
}
