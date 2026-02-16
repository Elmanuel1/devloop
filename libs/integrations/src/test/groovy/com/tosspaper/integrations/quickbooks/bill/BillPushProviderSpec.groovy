package com.tosspaper.integrations.quickbooks.bill

import com.intuit.ipp.data.Bill
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException
import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.models.common.DocumentSyncRequest
import com.tosspaper.models.domain.DocumentType
import com.tosspaper.models.domain.Invoice
import com.tosspaper.models.extraction.dto.Party
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.exception.DuplicateException
import spock.lang.Specification

class BillPushProviderSpec extends Specification {

    QuickBooksApiClient apiClient = Mock()
    BillMapper billMapper = Mock()
    QuickBooksProperties properties = Mock()
    BillPushProvider provider

    def setup() {
        provider = new BillPushProvider(apiClient, billMapper, properties)
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

        then: "returns BILL"
            entityType == IntegrationEntityType.BILL
    }

    def "should return correct document type"() {
        when: "getting document type"
            def documentType = provider.getDocumentType()

        then: "returns INVOICE"
            documentType == DocumentType.INVOICE
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

    // ==================== push(connection, request) Tests ====================

    def "should successfully push bill to QuickBooks"() {
        given: "an invoice with seller info"
            def seller = new Party().withReferenceNumber("qb-vendor-1")
            def invoice = Invoice.builder()
                .assignedId("inv-1")
                .sellerInfo(seller)
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

            def qboBill = new Bill()
            def createdBill = new Bill()
            createdBill.id = "qb-bill-1"
            createdBill.docNumber = "BILL-001"

        when: "pushing bill"
            def result = provider.push(connection, request)

        then: "mapper and API are called"
            1 * billMapper.mapToBill(request, "qb-vendor-1") >> qboBill
            1 * apiClient.createBill(connection, qboBill) >> createdBill

        and: "result is success"
            result.success
            result.externalId == "qb-bill-1"
            result.externalDocNumber == "BILL-001"
    }

    def "should return failure when vendor ID is missing"() {
        given: "an invoice without seller info"
            def invoice = Invoice.builder()
                .assignedId("inv-2")
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing bill"
            def result = provider.push(connection, request)

        then: "no API calls"
            0 * billMapper._
            0 * apiClient._

        and: "result is non-retryable failure"
            !result.success
            !result.retryable
            result.errorMessage.contains("Vendor ID missing")
    }

    def "should return failure when sellerInfo referenceNumber is null"() {
        given: "an invoice with seller but no reference number"
            def seller = new Party().withName("Some Vendor")
            def invoice = Invoice.builder()
                .assignedId("inv-3")
                .sellerInfo(seller)
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()

        when: "pushing bill"
            def result = provider.push(connection, request)

        then: "no API calls"
            0 * billMapper._
            0 * apiClient._

        and: "result is non-retryable failure"
            !result.success
            result.errorMessage.contains("Vendor ID missing")
    }

    def "should return conflict on ProviderVersionConflictException"() {
        given: "an invoice"
            def seller = new Party().withReferenceNumber("qb-v")
            def invoice = Invoice.builder()
                .assignedId("inv-4")
                .sellerInfo(seller)
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboBill = new Bill()

        when: "pushing bill"
            def result = provider.push(connection, request)

        then:
            1 * billMapper.mapToBill(request, "qb-v") >> qboBill
            1 * apiClient.createBill(connection, qboBill) >> { throw new ProviderVersionConflictException("stale") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return conflict on DuplicateException"() {
        given: "a duplicate bill"
            def seller = new Party().withReferenceNumber("qb-v")
            def invoice = Invoice.builder()
                .assignedId("inv-5")
                .sellerInfo(seller)
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboBill = new Bill()

        when: "pushing bill"
            def result = provider.push(connection, request)

        then:
            1 * billMapper.mapToBill(request, "qb-v") >> qboBill
            1 * apiClient.createBill(connection, qboBill) >> { throw new DuplicateException("dup") }

        and: "result is conflict"
            !result.success
            result.conflictDetected
    }

    def "should return retryable failure on generic exception"() {
        given: "a bill that fails"
            def seller = new Party().withReferenceNumber("qb-v")
            def invoice = Invoice.builder()
                .assignedId("inv-6")
                .sellerInfo(seller)
                .build()
            def request = DocumentSyncRequest.fromInvoice(invoice)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def qboBill = new Bill()

        when: "pushing bill"
            def result = provider.push(connection, request)

        then:
            1 * billMapper.mapToBill(request, "qb-v") >> qboBill
            1 * apiClient.createBill(connection, qboBill) >> { throw new RuntimeException("Network error") }

        and: "result is retryable failure"
            !result.success
            result.retryable
            result.errorMessage.contains("Failed to push bill")
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

        then: "returns empty map"
            results.isEmpty()
    }

    def "should push batch of bills successfully"() {
        given: "batch of invoices"
            def seller = new Party().withReferenceNumber("qb-v")
            def inv1 = Invoice.builder().assignedId("inv-1").sellerInfo(seller).build()
            def inv2 = Invoice.builder().assignedId("inv-2").sellerInfo(seller).build()
            def req1 = DocumentSyncRequest.fromInvoice(inv1)
            def req2 = DocumentSyncRequest.fromInvoice(inv2)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def bill1 = new Bill()
            def bill2 = new Bill()
            def rBill1 = new Bill()
            rBill1.id = "qb-b1"
            rBill1.docNumber = "B-001"
            rBill1.syncToken = "0"
            def rBill2 = new Bill()
            rBill2.id = "qb-b2"
            rBill2.docNumber = "B-002"
            rBill2.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then:
            1 * billMapper.mapToBill(req1, "qb-v") >> bill1
            1 * billMapper.mapToBill(req2, "qb-v") >> bill2
            1 * apiClient.saveBatch(connection, [bill1, bill2]) >> [
                QuickBooksApiClient.BatchResult.success(rBill1),
                QuickBooksApiClient.BatchResult.success(rBill2)
            ]

        and: "both succeed"
            results["inv-1"].success
            results["inv-1"].externalId == "qb-b1"
            results["inv-2"].success
    }

    def "should skip invoices without vendor ID in batch"() {
        given: "batch with one missing vendor ID"
            def seller = new Party().withReferenceNumber("qb-v")
            def inv1 = Invoice.builder().assignedId("inv-1").sellerInfo(seller).build()
            def inv2 = Invoice.builder().assignedId("inv-2").build()
            def req1 = DocumentSyncRequest.fromInvoice(inv1)
            def req2 = DocumentSyncRequest.fromInvoice(inv2)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def bill1 = new Bill()
            def rBill1 = new Bill()
            rBill1.id = "qb-b1"
            rBill1.docNumber = "B-001"
            rBill1.syncToken = "0"

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req1, req2])

        then:
            1 * billMapper.mapToBill(req1, "qb-v") >> bill1
            1 * apiClient.saveBatch(connection, [bill1]) >> [
                QuickBooksApiClient.BatchResult.success(rBill1)
            ]

        and: "first succeeds, second fails"
            results["inv-1"].success
            !results["inv-2"].success
            results["inv-2"].errorMessage.contains("Vendor ID missing")
    }

    def "should handle batch stale error"() {
        given: "batch with stale error"
            def seller = new Party().withReferenceNumber("qb-v")
            def inv = Invoice.builder().assignedId("inv-1").sellerInfo(seller).build()
            def req = DocumentSyncRequest.fromInvoice(inv)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def bill = new Bill()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * billMapper.mapToBill(req, "qb-v") >> bill
            1 * apiClient.saveBatch(connection, [bill]) >> [
                QuickBooksApiClient.BatchResult.failure("Stale object error")
            ]

        and: "result is conflict"
            results["inv-1"].conflictDetected
    }

    def "should handle batch duplicate error"() {
        given: "batch with duplicate"
            def seller = new Party().withReferenceNumber("qb-v")
            def inv = Invoice.builder().assignedId("inv-1").sellerInfo(seller).build()
            def req = DocumentSyncRequest.fromInvoice(inv)
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .provider(IntegrationProvider.QUICKBOOKS)
                .build()
            def bill = new Bill()

        when: "pushing batch"
            def results = provider.pushBatch(connection, [req])

        then:
            1 * billMapper.mapToBill(req, "qb-v") >> bill
            1 * apiClient.saveBatch(connection, [bill]) >> [
                QuickBooksApiClient.BatchResult.failure("Duplicate entry already exists")
            ]

        and: "result is conflict"
            results["inv-1"].conflictDetected
    }
}
