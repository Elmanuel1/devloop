package com.tosspaper.integrations.quickbooks.purchaseorder

import com.intuit.ipp.data.ModificationMetaData
import com.intuit.ipp.data.PurchaseOrder
import com.tosspaper.integrations.common.PurchaseOrderLineItemEnricher
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.exception.DuplicateException
import spock.lang.Specification

class PurchaseOrderPushProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    QBOPurchaseOrderMapper poMapper = Mock()
    QuickBooksProperties properties = Mock()
    PurchaseOrderLineItemEnricher lineItemEnricher = Mock()
    PurchaseOrderPushProvider provider

    def setup() {
        provider = new PurchaseOrderPushProvider(apiClient, poMapper, properties, lineItemEnricher)
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

        then: "returns PURCHASE_ORDER"
            entityType == IntegrationEntityType.PURCHASE_ORDER
    }

    def "should return correct document type"() {
        when: "getting document type"
            def documentType = provider.getDocumentType()

        then: "returns PURCHASE_ORDER"
            documentType == DocumentType.PURCHASE_ORDER
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

    // ==================== push(connection, domainPo) Tests ====================

    def "should successfully push new purchase order to QuickBooks"() {
        given: "a PO without external ID (CREATE)"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-001")
                .build()
            domainPo.id = "po-1"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()
            def resultPo = new PurchaseOrder()
            resultPo.id = "qb-po-1"
            resultPo.docNumber = "PO-001"
            resultPo.syncToken = "0"
            def metaData = new ModificationMetaData()
            metaData.lastUpdatedTime = new Date()
            resultPo.metaData = metaData

        when: "pushing PO"
            def result = provider.push(connection, domainPo)

        then: "enricher, mapper and API are called"
            1 * lineItemEnricher.enrichLineItems("conn-1", [domainPo])
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> resultPo

        and: "result is success"
            result.success
            result.externalId == "qb-po-1"
            result.externalDocNumber == "PO-001"
            result.providerVersion == "0"
            result.providerLastUpdatedAt != null
    }

    def "should successfully update existing purchase order"() {
        given: "a PO with external ID (UPDATE)"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-002")
                .build()
            domainPo.id = "po-2"
            domainPo.externalId = "qb-po-2"
            domainPo.providerVersion = "3"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()
            def resultPo = new PurchaseOrder()
            resultPo.id = "qb-po-2"
            resultPo.docNumber = "PO-002"
            resultPo.syncToken = "4"

        when: "pushing PO"
            def result = provider.push(connection, domainPo)

        then:
            1 * lineItemEnricher.enrichLineItems("conn-1", [domainPo])
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> resultPo

        and: "result is success"
            result.success
            result.externalId == "qb-po-2"
            result.providerVersion == "4"
    }

    def "should return conflict on ProviderVersionConflictException"() {
        given: "a PO"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-003")
                .build()
            domainPo.id = "po-3"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()

        when: "pushing PO"
            def result = provider.push(connection, domainPo)

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> { throw new ProviderVersionConflictException("stale") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return conflict on DuplicateException"() {
        given: "a duplicate PO"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-004")
                .build()
            domainPo.id = "po-4"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()

        when: "pushing PO"
            def result = provider.push(connection, domainPo)

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> { throw new DuplicateException("dup") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return retryable failure on generic exception"() {
        given: "a PO"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-005")
                .build()
            domainPo.id = "po-5"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()

        when: "pushing PO"
            def result = provider.push(connection, domainPo)

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> { throw new RuntimeException("Network error") }

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Failed to push PO")
    }

    def "should push via DocumentSyncRequest"() {
        given: "a request wrapping a PO"
            def domainPo = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-006")
                .build()
            domainPo.id = "po-6"
            def request = DocumentSyncRequest.fromPurchaseOrder(domainPo)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()
            def resultPo = new PurchaseOrder()
            resultPo.id = "qb-po-6"
            resultPo.docNumber = "PO-006"
            resultPo.syncToken = "0"

        when: "pushing via request"
            def result = provider.push(connection, request)

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(domainPo) >> qboPo
            1 * apiClient.save(connection, qboPo) >> resultPo

        and: "result is success"
            result.success
    }

    // ==================== pushBatch Tests ====================

    def "should return empty results for empty batch"() {
        given: "empty batch"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing empty batch"
            def results = provider.pushBatch(connection, [])

        then:
            results.isEmpty()
    }

    def "should push batch of purchase orders successfully"() {
        given: "batch of POs"
            def po1 = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-A")
                .build()
            po1.id = "po-a"
            def po2 = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-B")
                .build()
            po2.id = "po-b"
            def req1 = DocumentSyncRequest.fromPurchaseOrder(po1)
            def req2 = DocumentSyncRequest.fromPurchaseOrder(po2)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo1 = new PurchaseOrder()
            def qboPo2 = new PurchaseOrder()
            def rPo1 = new PurchaseOrder()
            rPo1.id = "qb-a"
            rPo1.docNumber = "PO-A"
            rPo1.syncToken = "0"
            def rPo2 = new PurchaseOrder()
            rPo2.id = "qb-b"
            rPo2.docNumber = "PO-B"
            rPo2.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then:
            1 * lineItemEnricher.enrichLineItems("conn-1", _)
            1 * poMapper.toQboPurchaseOrder(po1) >> qboPo1
            1 * poMapper.toQboPurchaseOrder(po2) >> qboPo2
            1 * apiClient.saveBatch(connection, [qboPo1, qboPo2]) >> [
                QuickBooksApiClient.BatchResult.success(rPo1),
                QuickBooksApiClient.BatchResult.success(rPo2)
            ]

        and: "both succeed"
            results["po-a"].success
            results["po-a"].externalId == "qb-a"
            results["po-b"].success
    }

    def "should handle batch with stale error"() {
        given: "batch with stale PO"
            def po = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-STALE")
                .build()
            po.id = "po-s"
            def req = DocumentSyncRequest.fromPurchaseOrder(po)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(po) >> qboPo
            1 * apiClient.saveBatch(connection, [qboPo]) >> [
                QuickBooksApiClient.BatchResult.failure("Stale synctoken mismatch")
            ]

        and: "result is conflict"
            results["po-s"].conflictDetected
    }

    def "should handle batch with duplicate name error"() {
        given: "batch with duplicate"
            def po = com.tosspaper.models.domain.PurchaseOrder.builder()
                .displayId("PO-DUP")
                .build()
            po.id = "po-d"
            def req = DocumentSyncRequest.fromPurchaseOrder(po)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboPo = new PurchaseOrder()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * lineItemEnricher.enrichLineItems(_, _)
            1 * poMapper.toQboPurchaseOrder(po) >> qboPo
            1 * apiClient.saveBatch(connection, [qboPo]) >> [
                QuickBooksApiClient.BatchResult.failure("Name supplied already exists")
            ]

        and: "result is conflict"
            results["po-d"].conflictDetected
    }
}
