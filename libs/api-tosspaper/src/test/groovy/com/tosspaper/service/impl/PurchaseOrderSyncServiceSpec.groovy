package com.tosspaper.service.impl

import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.purchaseorder.PurchaseOrderSyncRepository
import spock.lang.Specification

import java.time.OffsetDateTime

class PurchaseOrderSyncServiceSpec extends Specification {

    PurchaseOrderSyncRepository purchaseOrderSyncRepository
    PurchaseOrderSyncServiceImpl service

    def setup() {
        purchaseOrderSyncRepository = Mock()
        service = new PurchaseOrderSyncServiceImpl(purchaseOrderSyncRepository)
    }

    // ==================== upsertFromProvider ====================

    def "upsertFromProvider delegates to repository"() {
        given: "purchase orders to upsert"
            def companyId = 1L
            def purchaseOrders = [createPurchaseOrder("po-1"), createPurchaseOrder("po-2")]

        when: "upserting purchase orders"
            service.upsertFromProvider(companyId, purchaseOrders)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.upsertFromProvider(companyId, purchaseOrders)
    }

    // ==================== deleteByProviderAndExternalIds ====================

    def "deleteByProviderAndExternalIds returns count from repository"() {
        given: "delete parameters"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def externalIds = ["ext-1", "ext-2"]

        when: "deleting by provider and external IDs"
            def result = service.deleteByProviderAndExternalIds(companyId, provider, externalIds)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.deleteByProviderAndExternalIds(companyId, provider, externalIds) >> 2

        and: "count is returned"
            result == 2
    }

    // ==================== updateSyncStatus ====================

    def "updateSyncStatus delegates to repository"() {
        given: "sync status params"
            def poId = "po-123"
            def externalId = "ext-123"
            def providerVersion = "1"
            def lastUpdatedAt = OffsetDateTime.now()

        when: "updating sync status"
            service.updateSyncStatus(poId, externalId, providerVersion, lastUpdatedAt)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.updateSyncStatus(poId, externalId, providerVersion, lastUpdatedAt)
    }

    // ==================== findNeedingPush ====================

    def "findNeedingPush returns purchase orders from repository"() {
        given: "purchase orders needing push"
            def companyId = 1L
            def limit = 10
            def maxRetries = 5
            def purchaseOrders = [createPurchaseOrder("po-1")]

        when: "finding purchase orders needing push"
            def result = service.findNeedingPush(companyId, limit, maxRetries)

        then: "repository is queried"
            1 * purchaseOrderSyncRepository.findNeedingPush(companyId, limit, maxRetries) >> purchaseOrders

        and: "purchase orders are returned"
            result.size() == 1
    }

    // ==================== findByProviderAndExternalId ====================

    def "findByProviderAndExternalId returns purchase order from repository"() {
        given: "a purchase order exists"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def externalId = "ext-123"
            def purchaseOrder = createPurchaseOrder("po-123")

        when: "finding purchase order"
            def result = service.findByProviderAndExternalId(companyId, provider, externalId)

        then: "repository is queried"
            1 * purchaseOrderSyncRepository.findByProviderAndExternalId(companyId, provider, externalId) >> purchaseOrder

        and: "purchase order is returned"
            result.id == "po-123"
    }

    // ==================== findByCompanyIdAndDisplayIds ====================

    def "findByCompanyIdAndDisplayIds returns purchase orders from repository"() {
        given: "display IDs"
            def companyId = 1L
            def displayIds = ["PO-001", "PO-002"]
            def purchaseOrders = [createPurchaseOrder("po-1"), createPurchaseOrder("po-2")]

        when: "finding purchase orders"
            def result = service.findByCompanyIdAndDisplayIds(companyId, displayIds)

        then: "repository is queried"
            1 * purchaseOrderSyncRepository.findByCompanyIdAndDisplayIds(companyId, displayIds) >> purchaseOrders

        and: "purchase orders are returned"
            result.size() == 2
    }

    // ==================== findById ====================

    def "findById returns purchase order from repository"() {
        given: "a purchase order exists"
            def poId = "po-123"
            def purchaseOrder = createPurchaseOrder(poId)

        when: "finding purchase order"
            def result = service.findById(poId)

        then: "repository is queried"
            1 * purchaseOrderSyncRepository.findById(poId) >> purchaseOrder

        and: "purchase order is returned"
            result.id == poId
    }

    // ==================== incrementRetryCount ====================

    def "incrementRetryCount delegates to repository"() {
        given: "PO ID and error"
            def poId = "po-123"
            def errorMessage = "Push failed"

        when: "incrementing retry count"
            service.incrementRetryCount(poId, errorMessage)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.incrementRetryCount(poId, errorMessage)
    }

    // ==================== markAsPermanentlyFailed ====================

    def "markAsPermanentlyFailed delegates to repository"() {
        given: "PO ID and error"
            def poId = "po-123"
            def errorMessage = "Duplicate"

        when: "marking as permanently failed"
            service.markAsPermanentlyFailed(poId, errorMessage)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.markAsPermanentlyFailed(poId, errorMessage)
    }

    // ==================== resetRetryTracking ====================

    def "resetRetryTracking delegates to repository"() {
        given: "PO ID"
            def poId = "po-123"

        when: "resetting retry tracking"
            service.resetRetryTracking(poId)

        then: "repository is called"
            1 * purchaseOrderSyncRepository.resetRetryTracking(poId)
    }

    // ==================== Helper Methods ====================

    private PurchaseOrder createPurchaseOrder(String id) {
        PurchaseOrder.builder()
            .id(id)
            .companyId(1L)
            .displayId("PO-${id}")
            .build()
    }
}
