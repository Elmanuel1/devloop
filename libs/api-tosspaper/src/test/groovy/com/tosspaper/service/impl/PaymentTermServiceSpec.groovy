package com.tosspaper.service.impl

import com.tosspaper.models.domain.PaymentTerm
import com.tosspaper.payment_terms.PaymentTermRepository
import spock.lang.Specification

class PaymentTermServiceSpec extends Specification {

    PaymentTermRepository paymentTermRepository
    PaymentTermServiceImpl service

    def setup() {
        paymentTermRepository = Mock()
        service = new PaymentTermServiceImpl(paymentTermRepository)
    }

    // ==================== upsertFromProvider ====================

    def "upsertFromProvider delegates to repository"() {
        given: "payment terms to upsert"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def terms = [
                createPaymentTerm("550e8400-e29b-41d4-a716-446655440000", "Net 30"),
                createPaymentTerm("550e8400-e29b-41d4-a716-446655440001", "Net 60")
            ]

        when: "upserting payment terms"
            service.upsertFromProvider(companyId, provider, terms)

        then: "repository is called"
            1 * paymentTermRepository.upsertFromProvider(companyId, provider, terms)
    }

    def "upsertFromProvider handles empty list"() {
        given: "empty payment terms list"
            def companyId = 1L
            def provider = "QUICKBOOKS"
            def terms = []

        when: "upserting empty list"
            service.upsertFromProvider(companyId, provider, terms)

        then: "repository is called with empty list"
            1 * paymentTermRepository.upsertFromProvider(companyId, provider, [])
    }

    // ==================== Helper Methods ====================

    private PaymentTerm createPaymentTerm(String id, String name) {
        PaymentTerm.builder()
            .id(UUID.fromString(id))
            .name(name)
            .build()
    }
}
