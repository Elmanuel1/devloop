package com.tosspaper.integrations.common

import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class AccountDependencyServiceImplSpec extends Specification {

    @Subject
    AccountDependencyServiceImpl service = new AccountDependencyServiceImpl()

    def connection = IntegrationConnection.builder()
        .id("conn-1")
        .companyId(100L)
        .provider(IntegrationProvider.QUICKBOOKS)
        .build()

    def "should return success when all accounts have external IDs"() {
        given: "accounts with external IDs"
            def account1 = IntegrationAccount.builder()
                .name("Expenses")
                .build()
            account1.externalId = "qb-1"
            def account2 = IntegrationAccount.builder()
                .name("Revenue")
                .build()
            account2.externalId = "qb-2"

        when: "validating external IDs"
            def result = service.validateHaveExternalIds(connection, [account1, account2])

        then: "result is success"
            result.success
    }

    def "should return success for empty list"() {
        when: "validating empty list"
            def result = service.validateHaveExternalIds(connection, [])

        then: "result is success"
            result.success
    }

    def "should return failure when some accounts are missing external IDs"() {
        given: "an account without external ID"
            def account1 = IntegrationAccount.builder()
                .name("Expenses")
                .build()
            account1.externalId = "qb-1"
            def account2 = IntegrationAccount.builder()
                .name("Missing Account")
                .build()

        when: "validating external IDs"
            def result = service.validateHaveExternalIds(connection, [account1, account2])

        then: "result is failure"
            !result.success
            result.message.contains("Missing Account")
            result.message.contains("1 account(s) without external IDs")
            result.message.contains("QUICKBOOKS")
    }

    def "should return failure when all accounts are missing external IDs"() {
        given: "accounts without external IDs"
            def account1 = IntegrationAccount.builder()
                .name("Cost of Goods")
                .build()
            def account2 = IntegrationAccount.builder()
                .name("Shipping")
                .build()

        when: "validating external IDs"
            def result = service.validateHaveExternalIds(connection, [account1, account2])

        then: "result is failure listing all accounts"
            !result.success
            result.message.contains("2 account(s) without external IDs")
            result.message.contains("Cost of Goods")
            result.message.contains("Shipping")
    }
}
