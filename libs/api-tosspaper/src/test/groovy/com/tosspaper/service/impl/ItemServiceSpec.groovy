package com.tosspaper.service.impl

import com.tosspaper.item.ItemRepository
import com.tosspaper.models.common.SyncStatusUpdate
import com.tosspaper.models.domain.integration.Item
import spock.lang.Specification

import java.time.OffsetDateTime

class ItemServiceSpec extends Specification {

    ItemRepository itemRepository
    ItemServiceImpl service

    def setup() {
        itemRepository = Mock()
        service = new ItemServiceImpl(itemRepository)
    }

    // ==================== findById ====================

    def "findById returns item from repository"() {
        given: "an item exists"
            def itemId = "item-123"
            def item = createItem(itemId, "Test Item")

        when: "finding item"
            def result = service.findById(itemId)

        then: "repository is queried"
            1 * itemRepository.findById(itemId) >> item

        and: "item is returned"
            result.id == itemId
            result.name == "Test Item"
    }

    // ==================== findByIds ====================

    def "findByIds returns items from repository"() {
        given: "multiple item IDs"
            def ids = ["item-1", "item-2"]
            def items = [createItem("item-1", "Item 1"), createItem("item-2", "Item 2")]

        when: "finding items"
            def result = service.findByIds(ids)

        then: "repository is queried"
            1 * itemRepository.findByIds(ids) >> items

        and: "items are returned"
            result.size() == 2
    }

    def "findByIds returns empty list for null IDs"() {
        when: "finding with null IDs"
            def result = service.findByIds(null)

        then: "repository not called"
            0 * itemRepository.findByIds(_)

        and: "empty list returned"
            result.isEmpty()
    }

    def "findByIds returns empty list for empty IDs"() {
        when: "finding with empty IDs"
            def result = service.findByIds([])

        then: "repository not called"
            0 * itemRepository.findByIds(_)

        and: "empty list returned"
            result.isEmpty()
    }

    // ==================== upsertFromProvider ====================

    def "upsertFromProvider delegates to repository"() {
        given: "items to upsert"
            def companyId = 1L
            def connectionId = "conn-123"
            def items = [createItem("item-1", "Item 1")]

        when: "upserting items"
            service.upsertFromProvider(companyId, connectionId, items)

        then: "repository is called"
            1 * itemRepository.upsertFromProvider(companyId, connectionId, items)
    }

    // ==================== findIdsByExternalIdsAndConnection ====================

    def "findIdsByExternalIdsAndConnection returns mapping"() {
        given: "external IDs"
            def connectionId = "conn-123"
            def externalIds = ["ext-1", "ext-2"]
            def mapping = ["ext-1": "item-1", "ext-2": "item-2"]

        when: "finding IDs"
            def result = service.findIdsByExternalIdsAndConnection(connectionId, externalIds)

        then: "repository is queried"
            1 * itemRepository.findIdsByExternalIdsAndConnection(connectionId, externalIds) >> mapping

        and: "mapping is returned"
            result.size() == 2
    }

    // ==================== updateSyncStatus ====================

    def "updateSyncStatus delegates to repository"() {
        given: "sync status update params"
            def itemId = "item-123"
            def provider = "QUICKBOOKS"
            def externalId = "qb-123"
            def providerVersion = "1"
            def lastUpdatedAt = OffsetDateTime.now()

        when: "updating sync status"
            service.updateSyncStatus(itemId, provider, externalId, providerVersion, lastUpdatedAt)

        then: "repository is called"
            1 * itemRepository.updateSyncStatus(itemId, provider, externalId, providerVersion, lastUpdatedAt)
    }

    // ==================== batchUpdateSyncStatus ====================

    def "batchUpdateSyncStatus delegates to repository"() {
        given: "batch updates"
            def updates = [
                new SyncStatusUpdate("item-1", "QUICKBOOKS", "qb-1", "1", OffsetDateTime.now()),
                new SyncStatusUpdate("item-2", "QUICKBOOKS", "qb-2", "1", OffsetDateTime.now())
            ]

        when: "batch updating"
            service.batchUpdateSyncStatus(updates)

        then: "repository is called"
            1 * itemRepository.batchUpdateSyncStatus(updates)
    }

    // ==================== findNeedingPush ====================

    def "findNeedingPush returns items from repository"() {
        given: "items needing push"
            def companyId = 1L
            def connectionId = "conn-123"
            def limit = 10
            def maxRetries = 5
            def items = [createItem("item-1", "Item 1")]

        when: "finding items needing push"
            def result = service.findNeedingPush(companyId, connectionId, limit, maxRetries)

        then: "repository is queried"
            1 * itemRepository.findNeedingPush(companyId, connectionId, limit, maxRetries) >> items

        and: "items are returned"
            result.size() == 1
    }

    // ==================== clearSyncStatus ====================

    def "clearSyncStatus delegates to repository"() {
        given: "an item ID"
            def itemId = "item-123"

        when: "clearing sync status"
            service.clearSyncStatus(itemId)

        then: "repository is called"
            1 * itemRepository.clearSyncStatus(itemId)
    }

    // ==================== incrementRetryCount ====================

    def "incrementRetryCount delegates to repository"() {
        given: "an item ID and error message"
            def itemId = "item-123"
            def errorMessage = "Push failed"

        when: "incrementing retry count"
            service.incrementRetryCount(itemId, errorMessage)

        then: "repository is called"
            1 * itemRepository.incrementRetryCount(itemId, errorMessage)
    }

    // ==================== markAsPermanentlyFailed ====================

    def "markAsPermanentlyFailed delegates to repository"() {
        given: "an item ID and error message"
            def itemId = "item-123"
            def errorMessage = "Duplicate name error"

        when: "marking as permanently failed"
            service.markAsPermanentlyFailed(itemId, errorMessage)

        then: "repository is called"
            1 * itemRepository.markAsPermanentlyFailed(itemId, errorMessage)
    }

    // ==================== resetRetryTracking ====================

    def "resetRetryTracking delegates to repository"() {
        given: "an item ID"
            def itemId = "item-123"

        when: "resetting retry tracking"
            service.resetRetryTracking(itemId)

        then: "repository is called"
            1 * itemRepository.resetRetryTracking(itemId)
    }

    // ==================== Helper Methods ====================

    private Item createItem(String id, String name) {
        Item.builder()
            .id(id)
            .name(name)
            .companyId(1L)
            .active(true)
            .build()
    }
}
