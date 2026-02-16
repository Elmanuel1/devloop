package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.PurchaseOrderSyncService
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class PurchaseOrderDependencyPushServiceImplSpec extends Specification {

    PurchaseOrderSyncService purchaseOrderSyncService = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationPushProvider pushProvider = Mock()

    @Subject
    PurchaseOrderDependencyPushServiceImpl service = new PurchaseOrderDependencyPushServiceImpl(
        purchaseOrderSyncService, providerFactory
    )

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return success when all POs already have external IDs"() {
        given: "POs with external IDs"
            def po = PurchaseOrder.builder().displayId("PO-001").build()
            po.id = "po1"
            po.externalId = "qb-po-1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then: "no push needed"
            0 * providerFactory._
            result.success
    }

    def "should push POs without external IDs and update sync status"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-NEW").build()
            po.id = "po1"
            def now = OffsetDateTime.now()
            def syncResult = SyncResult.success("qb-po-1", "PO-NEW", "0", now)

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then: "push provider is obtained and batch push is called"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["po1": syncResult]

        and: "sync status is updated per PO"
            1 * purchaseOrderSyncService.updateSyncStatus("po1", "qb-po-1", "0", now)

        and: "in-memory PO is updated"
            po.externalId == "qb-po-1"
            po.providerVersion == "0"

        and: "result is success"
            result.success
    }

    def "should return failure when push result is missing"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-MISS").build()
            po.id = "po1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> [:]

        and: "result is failure"
            !result.success
            result.message.contains("No result returned")
    }

    def "should return failure and mark as permanently failed when push fails non-retryable"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-FAIL").build()
            po.id = "po1"
            def failResult = SyncResult.builder()
                .success(false)
                .errorMessage("Duplicate name")
                .retryable(false)
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["po1": failResult]

        and: "PO is marked as permanently failed"
            1 * purchaseOrderSyncService.markAsPermanentlyFailed("po1", "Duplicate name")

        and: "result is failure"
            !result.success
    }

    def "should return failure and increment retry when push fails retryable"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-RETRY").build()
            po.id = "po1"
            def failResult = SyncResult.builder()
                .success(false)
                .errorMessage("Network error")
                .retryable(true)
                .build()

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> ["po1": failResult]

        and: "retry count is incremented"
            1 * purchaseOrderSyncService.incrementRetryCount("po1", "Network error")

        and: "result is failure"
            !result.success
    }

    def "should return failure when batch push throws exception"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-EX").build()
            po.id = "po1"

        when: "ensuring external IDs"
            def result = service.ensureHaveExternalIds(connection, [po])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.pushBatch(connection, _) >> { throw new RuntimeException("Error") }

        and: "result is failure"
            !result.success
    }

    def "should throw when no push provider is found"() {
        given: "a PO without external ID"
            def po = PurchaseOrder.builder().displayId("PO-NP").build()
            po.id = "po1"

        when: "ensuring external IDs"
            service.ensureHaveExternalIds(connection, [po])

        then:
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.empty()
            thrown(IllegalStateException)
    }
}
