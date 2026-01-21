package com.tosspaper.accounts

import com.tosspaper.generated.model.IntegrationAccount
import com.tosspaper.generated.model.IntegrationAccountList
import org.springframework.http.HttpStatus
import spock.lang.Specification

class IntegrationAccountControllerSpec extends Specification {

    IntegrationAccountAPIService integrationAccountService
    IntegrationAccountController controller

    def setup() {
        integrationAccountService = Mock()
        controller = new IntegrationAccountController(integrationAccountService)
    }

    // ==================== getIntegrationAccounts ====================

    def "getIntegrationAccounts returns OK with account list"() {
        given: "valid context and type parameter"
            def xContextId = "123"
            def type = "expense"

            def accountList = new IntegrationAccountList()
            accountList.setData([createAccount("acc-1", "Expense Account"), createAccount("acc-2", "Office Supplies")])

        when: "calling getIntegrationAccounts"
            def response = controller.getIntegrationAccounts(xContextId, type)

        then: "service is called with parsed company ID and account type"
            1 * integrationAccountService.getAccounts(123L, AccountType.EXPENSE) >> accountList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains accounts"
            with(response.body) {
                data.size() == 2
                data[0].id == "acc-1"
                data[1].id == "acc-2"
            }
    }

    def "getIntegrationAccounts handles null type parameter"() {
        given: "context with no type filter"
            def xContextId = "456"
            def accountList = new IntegrationAccountList()
            accountList.setData([])

        when: "calling getIntegrationAccounts with null type"
            def response = controller.getIntegrationAccounts(xContextId, null)

        then: "service is called with null account type"
            1 * integrationAccountService.getAccounts(456L, null) >> accountList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "getIntegrationAccounts handles invalid type parameter"() {
        given: "context with invalid type"
            def xContextId = "789"
            def type = "invalid_type"
            def accountList = new IntegrationAccountList()
            accountList.setData([])

        when: "calling getIntegrationAccounts with invalid type"
            def response = controller.getIntegrationAccounts(xContextId, type)

        then: "service is called with null (invalid type returns null from fromString)"
            1 * integrationAccountService.getAccounts(789L, null) >> accountList

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== Helper Methods ====================

    private static IntegrationAccount createAccount(String id, String name) {
        def account = new IntegrationAccount()
        account.id = id
        account.name = name
        return account
    }
}
