package com.tosspaper.accounts

import com.tosspaper.models.domain.integration.IntegrationAccount
import spock.lang.Specification

import java.time.OffsetDateTime

class IntegrationAccountMapperSpec extends Specification {

    IntegrationAccountMapper mapper

    def setup() {
        mapper = new IntegrationAccountMapper()
    }

    // ==================== toApi ====================

    def "toApi returns null when domain is null"() {
        when: "mapping null domain"
            def result = mapper.toApi(null)

        then: "result is null"
            result == null
    }

    def "toApi maps all fields correctly"() {
        given: "a complete domain integration account"
            def createdAt = OffsetDateTime.now()
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .name("Cash Account")
                .accountType("Bank")
                .accountSubType("Checking")
                .classification("Asset")
                .active(true)
                .currentBalance(new BigDecimal("1500.50"))
                .createdAt(createdAt)
                .build()
            domain.setExternalId("qb-789")

        when: "mapping to API model"
            def result = mapper.toApi(domain)

        then: "all fields are mapped correctly"
            result != null
            result.id == "acc-123"
            result.connectionId == "conn-456"
            result.externalId == "qb-789"
            result.name == "Cash Account"
            result.accountType == "Bank"
            result.accountSubType == "Checking"
            result.classification == "Asset"
            result.active == true
            result.currentBalance == new BigDecimal("1500.50")
            result.createdAt == createdAt
    }

    def "toApi handles null createdAt"() {
        given: "domain with null createdAt"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .name("Test Account")
                .createdAt(null)
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "createdAt is not set"
            result != null
            result.createdAt == null
    }

    def "toApi handles null optional fields"() {
        given: "domain with minimal fields"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "only required fields are set"
            result != null
            result.id == "acc-123"
            result.connectionId == "conn-456"
            result.externalId == null
            result.name == null
            result.accountType == null
            result.accountSubType == null
            result.classification == null
            result.active == null
            result.currentBalance == null
    }

    def "toApi handles inactive account"() {
        given: "an inactive account"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .name("Inactive Account")
                .active(false)
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "active flag is correctly set"
            result != null
            result.active == false
    }

    def "toApi handles zero balance"() {
        given: "account with zero balance"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .currentBalance(BigDecimal.ZERO)
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "balance is correctly set to zero"
            result != null
            result.currentBalance == BigDecimal.ZERO
    }

    def "toApi handles negative balance"() {
        given: "account with negative balance"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .currentBalance(new BigDecimal("-250.75"))
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "negative balance is preserved"
            result != null
            result.currentBalance == new BigDecimal("-250.75")
    }

    def "toApi creates new instance each time"() {
        given: "a domain account"
            def domain = createDomainAccount()

        when: "mapping twice"
            def result1 = mapper.toApi(domain)
            def result2 = mapper.toApi(domain)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toApi handles various account types"() {
        given: "accounts with different types"
            def bankAccount = createDomainAccount(accountType: "Bank")
            def expenseAccount = createDomainAccount(accountType: "Expense")
            def revenueAccount = createDomainAccount(accountType: "Revenue")

        when: "mapping different account types"
            def bankResult = mapper.toApi(bankAccount)
            def expenseResult = mapper.toApi(expenseAccount)
            def revenueResult = mapper.toApi(revenueAccount)

        then: "account types are preserved"
            bankResult.accountType == "Bank"
            expenseResult.accountType == "Expense"
            revenueResult.accountType == "Revenue"
    }

    def "toApi preserves large balance values"() {
        given: "account with large balance"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .currentBalance(new BigDecimal("999999999.99"))
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "large balance is preserved"
            result != null
            result.currentBalance == new BigDecimal("999999999.99")
    }

    def "toApi handles precise decimal values"() {
        given: "account with precise decimal"
            def domain = IntegrationAccount.builder()
                .id("acc-123")
                .connectionId("conn-456")
                .currentBalance(new BigDecimal("123.456789"))
                .build()

        when: "mapping to API"
            def result = mapper.toApi(domain)

        then: "precision is preserved"
            result != null
            result.currentBalance == new BigDecimal("123.456789")
    }

    // ==================== Helper Methods ====================

    private IntegrationAccount createDomainAccount(Map overrides = [:]) {
        def defaults = [
            id: "acc-123",
            connectionId: "conn-456",
            externalId: "qb-789",
            name: "Test Account",
            accountType: "Bank",
            accountSubType: "Checking",
            classification: "Asset",
            active: true,
            currentBalance: new BigDecimal("1000.00"),
            createdAt: OffsetDateTime.now()
        ]

        def merged = defaults + overrides

        def account = IntegrationAccount.builder()
            .id(merged.id)
            .connectionId(merged.connectionId)
            .name(merged.name)
            .accountType(merged.accountType)
            .accountSubType(merged.accountSubType)
            .classification(merged.classification)
            .active(merged.active)
            .currentBalance(merged.currentBalance)
            .createdAt(merged.createdAt)
            .build()

        if (merged.externalId != null) {
            account.setExternalId(merged.externalId)
        }

        return account
    }
}
