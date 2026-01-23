package com.tosspaper.integrations.quickbooks.term

import com.intuit.ipp.data.ModificationMetaData
import com.intuit.ipp.data.Term
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class PaymentTermMapperSpec extends Specification {

    @Subject
    PaymentTermMapper mapper = new PaymentTermMapper()

    def "should map full Term to PaymentTerm with all fields"() {
        given:
        def qboTerm = QBOTestFixtures.loadTerm()

        when:
        def paymentTerm = mapper.toDomain(qboTerm)

        then:
        paymentTerm != null

        and: "basic fields are mapped"
        paymentTerm.name == "Net 30"
        paymentTerm.dueDays == 30
        paymentTerm.discountPercent == 0
        paymentTerm.active == true

        and: "provider tracking fields are set"
        paymentTerm.externalId == "3"
        paymentTerm.provider == IntegrationProvider.QUICKBOOKS.value
        paymentTerm.providerCreatedAt != null
        paymentTerm.providerLastUpdatedAt != null
    }

    def "should map Term with discount"() {
        given:
        def qboTerm = new Term()
        qboTerm.setId("5")
        qboTerm.setName("2% 10 Net 30")
        qboTerm.setDueDays(30)
        qboTerm.setDiscountPercent(new BigDecimal("2.00"))
        qboTerm.setDiscountDays(10)
        qboTerm.setActive(true)

        when:
        def paymentTerm = mapper.toDomain(qboTerm)

        then:
        paymentTerm != null
        paymentTerm.name == "2% 10 Net 30"
        paymentTerm.dueDays == 30
        paymentTerm.discountPercent == 2.00
        paymentTerm.discountDays == 10
        paymentTerm.active == true
    }

    def "should map Term with minimal fields"() {
        given:
        def qboTerm = new Term()
        qboTerm.setId("99")
        qboTerm.setName("Due on Receipt")
        qboTerm.setDueDays(0)
        qboTerm.setActive(true)

        when:
        def paymentTerm = mapper.toDomain(qboTerm)

        then:
        paymentTerm != null
        paymentTerm.name == "Due on Receipt"
        paymentTerm.dueDays == 0
        paymentTerm.discountPercent == null
        paymentTerm.discountDays == null
        paymentTerm.externalId == "99"
    }

    def "should handle inactive Term"() {
        given:
        def qboTerm = new Term()
        qboTerm.setId("10")
        qboTerm.setName("Deprecated Term")
        qboTerm.setDueDays(45)
        qboTerm.setActive(false)

        when:
        def paymentTerm = mapper.toDomain(qboTerm)

        then:
        paymentTerm != null
        paymentTerm.active == false
    }

    def "should handle null metadata gracefully"() {
        given:
        def qboTerm = new Term()
        qboTerm.setId("20")
        qboTerm.setName("No Metadata Term")
        qboTerm.setDueDays(15)
        qboTerm.setMetaData(null)

        when:
        def paymentTerm = mapper.toDomain(qboTerm)

        then:
        paymentTerm != null
        paymentTerm.externalId == "20"
        paymentTerm.providerCreatedAt == null
        paymentTerm.providerLastUpdatedAt == null
    }

    def "should return null when term is null"() {
        expect:
        mapper.toDomain(null) == null
    }

    def "should map all terms from list fixture"() {
        given:
        def terms = QBOTestFixtures.loadTermsList()

        when:
        def paymentTerms = terms.collect { mapper.toDomain(it) }

        then:
        paymentTerms.size() >= 1
        paymentTerms.every { it != null }
        paymentTerms.every { it.externalId != null }
        paymentTerms.every { it.provider == IntegrationProvider.QUICKBOOKS.value }
    }
}
