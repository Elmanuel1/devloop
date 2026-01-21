package com.tosspaper.accounts

import com.tosspaper.generated.model.IntegrationAccount
import com.tosspaper.models.domain.integration.IntegrationAccount as DomainIntegrationAccount
import spock.lang.Specification

class IntegrationAccountAPIServiceSpec extends Specification {

    IntegrationAccountRepository integrationAccountRepository
    IntegrationAccountMapper integrationAccountMapper
    IntegrationAccountAPIServiceImpl service

    def setup() {
        integrationAccountRepository = Mock()
        integrationAccountMapper = Mock()
        service = new IntegrationAccountAPIServiceImpl(integrationAccountRepository, integrationAccountMapper)
    }

    // ==================== getAccounts ====================

    def "getAccounts returns all accounts for company"() {
        given: "accounts exist for company"
            def companyId = 1L
            def domainAccounts = [
                createDomainAccount("acc-1", "Expense Account"),
                createDomainAccount("acc-2", "Revenue Account")
            ]
            def apiAccount1 = createApiAccount("acc-1", "Expense Account")
            def apiAccount2 = createApiAccount("acc-2", "Revenue Account")

        when: "fetching accounts"
            def result = service.getAccounts(companyId, null)

        then: "repository returns accounts"
            1 * integrationAccountRepository.findByCompanyId(companyId, null) >> domainAccounts

        and: "each account is mapped"
            1 * integrationAccountMapper.toApi(domainAccounts[0]) >> apiAccount1
            1 * integrationAccountMapper.toApi(domainAccounts[1]) >> apiAccount2

        and: "result contains all accounts"
            with(result) {
                data.size() == 2
                data[0].id == "acc-1"
                data[0].name == "Expense Account"
                data[1].id == "acc-2"
                data[1].name == "Revenue Account"
            }
    }

    def "getAccounts filters by account type"() {
        given: "accounts of specific type"
            def companyId = 1L
            def accountType = AccountType.EXPENSE
            def domainAccounts = [createDomainAccount("acc-1", "Office Supplies")]
            def apiAccount = createApiAccount("acc-1", "Office Supplies")

        when: "fetching accounts by type"
            def result = service.getAccounts(companyId, accountType)

        then: "repository is called with account type"
            1 * integrationAccountRepository.findByCompanyId(companyId, AccountType.EXPENSE) >> domainAccounts

        and: "account is mapped"
            1 * integrationAccountMapper.toApi(domainAccounts[0]) >> apiAccount

        and: "result contains filtered accounts"
            with(result) {
                data.size() == 1
                data[0].name == "Office Supplies"
            }
    }

    def "getAccounts returns empty list when no accounts exist"() {
        given: "no accounts for company"
            def companyId = 1L

        when: "fetching accounts"
            def result = service.getAccounts(companyId, null)

        then: "repository returns empty list"
            1 * integrationAccountRepository.findByCompanyId(companyId, null) >> []

        and: "result is empty"
            with(result) {
                data.isEmpty()
            }
    }

    def "getAccounts handles different account types"(AccountType accountType) {
        given: "accounts of specific type"
            def companyId = 1L
            def domainAccounts = [createDomainAccount("acc-1", "Test Account")]
            def apiAccount = createApiAccount("acc-1", "Test Account")

        when: "fetching accounts by type"
            def result = service.getAccounts(companyId, accountType)

        then: "repository is called with correct type"
            1 * integrationAccountRepository.findByCompanyId(companyId, accountType) >> domainAccounts
            1 * integrationAccountMapper.toApi(_) >> apiAccount

        and: "result is returned"
            result.data.size() == 1

        where:
            accountType << [AccountType.EXPENSE, null]
    }

    // ==================== Helper Methods ====================

    private static DomainIntegrationAccount createDomainAccount(String id, String name) {
        return DomainIntegrationAccount.builder()
            .id(id)
            .name(name)
            .build()
    }

    private static IntegrationAccount createApiAccount(String id, String name) {
        def account = new IntegrationAccount()
        account.id = id
        account.name = name
        return account
    }
}
