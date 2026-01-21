package com.tosspaper.service.impl

import com.tosspaper.integrations.config.PushRetryConfig
import com.tosspaper.invoices.InvoiceSyncRepository
import com.tosspaper.models.common.PushResult
import com.tosspaper.models.domain.Invoice
import spock.lang.Specification

class InvoiceSyncServiceSpec extends Specification {

    InvoiceSyncRepository invoiceSyncRepository
    PushRetryConfig pushRetryConfig
    InvoiceSyncServiceImpl service

    def setup() {
        invoiceSyncRepository = Mock()
        pushRetryConfig = Mock()
        pushRetryConfig.getMaxAttempts() >> 5
        service = new InvoiceSyncServiceImpl(invoiceSyncRepository, pushRetryConfig)
    }

    // ==================== findNeedingPush ====================

    def "findNeedingPush returns invoices from repository"() {
        given: "invoices needing push"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def limit = 10
            def invoices = [createInvoice("inv-1"), createInvoice("inv-2")]

        when: "finding invoices needing push"
            def result = service.findNeedingPush(companyId, provider, limit)

        then: "repository is queried with max attempts from config"
            1 * invoiceSyncRepository.findNeedingPush(companyId, limit, 5) >> invoices

        and: "invoices are returned"
            result.size() == 2
    }

    // ==================== findAcceptedNeedingPush ====================

    def "findAcceptedNeedingPush returns invoices from repository"() {
        given: "accepted invoices needing push"
            def companyId = 1L
            def limit = 10
            def invoices = [createInvoice("inv-1")]

        when: "finding accepted invoices needing push"
            def result = service.findAcceptedNeedingPush(companyId, limit)

        then: "repository is queried"
            1 * invoiceSyncRepository.findNeedingPush(companyId, limit, 5) >> invoices

        and: "invoices are returned"
            result.size() == 1
    }

    // ==================== markAsPushed ====================

    def "markAsPushed delegates to repository"() {
        given: "push results"
            def results = [
                new PushResult("inv-1", "ext-1", java.time.OffsetDateTime.now()),
                new PushResult("inv-2", "ext-2", java.time.OffsetDateTime.now())
            ]

        when: "marking as pushed"
            def count = service.markAsPushed(results)

        then: "repository is called"
            1 * invoiceSyncRepository.markAsPushed(results) >> 2

        and: "count is returned"
            count == 2
    }

    // ==================== incrementRetryCount ====================

    def "incrementRetryCount delegates to repository"() {
        given: "invoice ID and error"
            def invoiceId = "inv-123"
            def errorMessage = "Push failed"

        when: "incrementing retry count"
            service.incrementRetryCount(invoiceId, errorMessage)

        then: "repository is called"
            1 * invoiceSyncRepository.incrementRetryCount(invoiceId, errorMessage)
    }

    // ==================== markAsPermanentlyFailed ====================

    def "markAsPermanentlyFailed delegates to repository"() {
        given: "invoice ID and error"
            def invoiceId = "inv-123"
            def errorMessage = "Duplicate"

        when: "marking as permanently failed"
            service.markAsPermanentlyFailed(invoiceId, errorMessage)

        then: "repository is called"
            1 * invoiceSyncRepository.markAsPermanentlyFailed(invoiceId, errorMessage)
    }

    // ==================== resetRetryTracking ====================

    def "resetRetryTracking delegates to repository"() {
        given: "invoice ID"
            def invoiceId = "inv-123"

        when: "resetting retry tracking"
            service.resetRetryTracking(invoiceId)

        then: "repository is called"
            1 * invoiceSyncRepository.resetRetryTracking(invoiceId)
    }

    // ==================== findById ====================

    def "findById returns invoice from repository"() {
        given: "an invoice exists"
            def invoiceId = "inv-123"
            def invoice = createInvoice(invoiceId)

        when: "finding invoice"
            def result = service.findById(invoiceId)

        then: "repository is queried"
            1 * invoiceSyncRepository.findById(invoiceId) >> invoice

        and: "invoice is returned"
            result.id == invoiceId
    }

    // ==================== Helper Methods ====================

    private Invoice createInvoice(String id) {
        Invoice.builder()
            .assignedId(id)
            .companyId(1L)
            .build()
    }
}
