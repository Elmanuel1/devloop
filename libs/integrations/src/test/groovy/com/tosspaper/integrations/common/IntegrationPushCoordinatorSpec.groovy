package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.integrations.provider.IntegrationPushProvider
import com.tosspaper.models.domain.Party
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.domain.integration.Item
import spock.lang.Specification
import spock.lang.Subject

class IntegrationPushCoordinatorSpec extends Specification {

    DependencyCoordinatorService dependencyCoordinator = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    IntegrationPushProvider pushProvider = Mock()

    @Subject
    IntegrationPushCoordinator coordinator = new IntegrationPushCoordinator(
        dependencyCoordinator, providerFactory
    )

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    // ==================== pushWithDependencies Tests ====================

    def "should push vendor with dependencies resolved"() {
        given: "a vendor entity"
            def vendor = Party.builder().name("Test Vendor").build()
            vendor.id = "v1"
            def syncResult = SyncResult.success("qb-42", "Test Vendor")

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, vendor)

        then: "dependencies are ensured"
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [vendor]) >> DependencyPushResult.success()

        and: "provider is obtained and entity is pushed"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.push(connection, _) >> syncResult

        and: "result is success"
            result.success
            result.externalId == "qb-42"
    }

    def "should push purchase order with dependencies resolved"() {
        given: "a PO entity"
            def po = PurchaseOrder.builder().displayId("PO-001").build()
            po.id = "po1"
            def syncResult = SyncResult.success("qb-po-1", "PO-001")

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, po)

        then: "dependencies are ensured"
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, [po]) >> DependencyPushResult.success()

        and: "provider is obtained and entity is pushed"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.PURCHASE_ORDER) >> Optional.of(pushProvider)
            1 * pushProvider.push(connection, _) >> syncResult

        and: "result is success"
            result.success
    }

    def "should push item with dependencies resolved"() {
        given: "an item entity"
            def item = Item.builder().name("Widget").build()
            item.id = "i1"
            def syncResult = SyncResult.builder().success(true).externalId("qb-10").build()

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.ITEM, item)

        then:
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.ITEM, [item]) >> DependencyPushResult.success()
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.ITEM) >> Optional.of(pushProvider)
            1 * pushProvider.push(connection, _) >> syncResult

        and: "result is success"
            result.success
    }

    def "should push invoice with dependencies resolved"() {
        given: "an invoice entity"
            def invoice = Invoice.builder().assignedId("inv-1").build()
            def syncResult = SyncResult.builder().success(true).externalId("qb-bill-1").build()

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.BILL, invoice)

        then:
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.BILL, [invoice]) >> DependencyPushResult.success()
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.BILL) >> Optional.of(pushProvider)
            1 * pushProvider.push(connection, _) >> syncResult

        and: "result is success"
            result.success
    }

    def "should return failure when dependency resolution fails"() {
        given: "a vendor entity"
            def vendor = Party.builder().name("Fail Vendor").build()
            vendor.id = "v1"

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, vendor)

        then: "dependency resolution fails"
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [vendor]) >> DependencyPushResult.failure("Dep failed")

        and: "no push is attempted"
            0 * providerFactory._
            0 * pushProvider._

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Dep failed")
    }

    def "should return failure when no push provider is available"() {
        given: "a vendor entity"
            def vendor = Party.builder().name("No Provider").build()
            vendor.id = "v1"

        when: "pushing with dependencies"
            def result = coordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, vendor)

        then:
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [vendor]) >> DependencyPushResult.success()
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.empty()

        and: "result is non-retryable failure"
            !result.success
            !result.retryable
            result.errorMessage.contains("No push provider")
    }

    // ==================== pushBatchWithDependencies Tests ====================

    def "should return empty results for empty entity list"() {
        when: "pushing empty batch"
            def results = coordinator.pushBatchWithDependencies(connection, IntegrationEntityType.VENDOR, [])

        then: "returns empty map"
            results.isEmpty()
    }

    def "should push batch of vendors with dependencies resolved"() {
        given: "vendors"
            def v1 = Party.builder().name("V1").build()
            v1.id = "v1"
            def v2 = Party.builder().name("V2").build()
            v2.id = "v2"
            def sr1 = SyncResult.success("qb-1", "V1")
            def sr2 = SyncResult.success("qb-2", "V2")

        when: "pushing batch"
            def results = coordinator.pushBatchWithDependencies(connection, IntegrationEntityType.VENDOR, [v1, v2])

        then: "dependencies are resolved for the batch"
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [v1, v2]) >> DependencyPushResult.success()

        and: "provider is obtained"
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)

        and: "each entity is pushed individually"
            1 * pushProvider.push(connection, { it.documentId == "v1" }) >> sr1
            1 * pushProvider.push(connection, { it.documentId == "v2" }) >> sr2

        and: "results are mapped"
            results["v1"].success
            results["v2"].success
    }

    def "should return failure for all when dependency resolution fails"() {
        given: "vendors"
            def v1 = Party.builder().name("V1").build()
            v1.id = "v1"
            def v2 = Party.builder().name("V2").build()
            v2.id = "v2"

        when: "pushing batch"
            def results = coordinator.pushBatchWithDependencies(connection, IntegrationEntityType.VENDOR, [v1, v2])

        then: "dependency resolution fails"
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [v1, v2]) >> DependencyPushResult.failure("Dep failed")

        and: "all results are failures"
            results["v1"].errorMessage.contains("Dep failed")
            results["v2"].errorMessage.contains("Dep failed")
    }

    def "should return failure for all when no push provider is available"() {
        given: "vendors"
            def v1 = Party.builder().name("V1").build()
            v1.id = "v1"

        when: "pushing batch"
            def results = coordinator.pushBatchWithDependencies(connection, IntegrationEntityType.VENDOR, [v1])

        then:
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [v1]) >> DependencyPushResult.success()
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.empty()

        and: "all results are failures"
            !results["v1"].success
            !results["v1"].retryable
    }

    def "should handle exception during individual entity push"() {
        given: "a vendor"
            def v1 = Party.builder().name("V1").build()
            v1.id = "v1"

        when: "pushing batch"
            def results = coordinator.pushBatchWithDependencies(connection, IntegrationEntityType.VENDOR, [v1])

        then:
            1 * dependencyCoordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [v1]) >> DependencyPushResult.success()
            1 * providerFactory.getPushProvider(IntegrationProvider.QUICKBOOKS, IntegrationEntityType.VENDOR) >> Optional.of(pushProvider)
            1 * pushProvider.push(connection, _) >> { throw new RuntimeException("Push error") }

        and: "result is retryable failure"
            !results["v1"].success
            results["v1"].retryable
            results["v1"].errorMessage.contains("Push failed")
    }

    // ==================== createSyncRequest Tests ====================

    def "should throw for unsupported entity type"() {
        given: "an entity type that is not PurchaseOrder, Party, Item, or Invoice"
            def entity = new UnsupportedEntity()
            dependencyCoordinator.ensureAllDependencies(_, _, _) >> DependencyPushResult.success()
            providerFactory.getPushProvider(_, _) >> Optional.of(pushProvider)

        when: "pushing with dependencies"
            coordinator.pushWithDependencies(connection, IntegrationEntityType.VENDOR, entity)

        then: "throws because entity type is not supported by createSyncRequest"
            thrown(IllegalArgumentException)
    }

    /** Minimal TossPaperEntity that doesn't match any known domain type */
    static class UnsupportedEntity implements com.tosspaper.models.domain.TossPaperEntity {
        String getId() { return "unsupported-1" }
    }
}
