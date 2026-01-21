package com.tosspaper.service.impl

import com.tosspaper.company.CompanyRepository
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import spock.lang.Specification

class CompanySyncServiceSpec extends Specification {

    CompanyRepository companyRepository
    CompanySyncServiceImpl service

    def setup() {
        companyRepository = Mock()
        service = new CompanySyncServiceImpl(companyRepository)
    }

    // ==================== updateCurrencyFromIntegration ====================

    def "updateCurrencyFromIntegration updates currency"() {
        given: "a company and currency"
            def companyId = 1L
            def currency = Currency.USD
            def company = new CompaniesRecord()
            company.id = companyId

        when: "updating currency"
            service.updateCurrencyFromIntegration(companyId, currency, null)

        then: "company is fetched"
            1 * companyRepository.findById(companyId) >> company

        and: "company is updated with currency"
            1 * companyRepository.update(_ as CompaniesRecord) >> { CompaniesRecord c ->
                assert c.currency == "USD"
                return c
            }
    }

    def "updateCurrencyFromIntegration updates multicurrency flag"() {
        given: "a company and multicurrency flag"
            def companyId = 1L
            def multicurrencyEnabled = true
            def company = new CompaniesRecord()
            company.id = companyId

        when: "updating multicurrency flag"
            service.updateCurrencyFromIntegration(companyId, null, multicurrencyEnabled)

        then: "company is fetched"
            1 * companyRepository.findById(companyId) >> company

        and: "company is updated with flag"
            1 * companyRepository.update(_ as CompaniesRecord) >> { CompaniesRecord c ->
                assert c.multicurrencyEnabled == true
                return c
            }
    }

    def "updateCurrencyFromIntegration updates both currency and multicurrency"() {
        given: "a company with both updates"
            def companyId = 1L
            def currency = Currency.CAD
            def multicurrencyEnabled = true
            def company = new CompaniesRecord()
            company.id = companyId

        when: "updating both"
            service.updateCurrencyFromIntegration(companyId, currency, multicurrencyEnabled)

        then: "company is fetched"
            1 * companyRepository.findById(companyId) >> company

        and: "company is updated with both values"
            1 * companyRepository.update(_ as CompaniesRecord) >> { CompaniesRecord c ->
                assert c.currency == "CAD"
                assert c.multicurrencyEnabled == true
                return c
            }
    }

    def "updateCurrencyFromIntegration does nothing when both null"() {
        given: "null values"
            def companyId = 1L

        when: "updating with nulls"
            service.updateCurrencyFromIntegration(companyId, null, null)

        then: "company is fetched"
            1 * companyRepository.findById(companyId) >> new CompaniesRecord()

        and: "no update called"
            0 * companyRepository.update(_)
    }

    def "updateCurrencyFromIntegration handles exceptions gracefully"() {
        given: "repository throws exception"
            def companyId = 1L

        when: "updating"
            service.updateCurrencyFromIntegration(companyId, Currency.USD, true)

        then: "repository throws"
            1 * companyRepository.findById(companyId) >> { throw new RuntimeException("DB error") }

        and: "no exception propagated (graceful handling)"
            noExceptionThrown()
    }
}
