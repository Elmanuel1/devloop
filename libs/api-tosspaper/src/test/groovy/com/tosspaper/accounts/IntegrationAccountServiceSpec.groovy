package com.tosspaper.accounts

import com.tosspaper.models.domain.integration.IntegrationAccount
import spock.lang.Specification

class IntegrationAccountServiceSpec extends Specification {

    IntegrationAccountRepository integrationAccountRepository
    IntegrationAccountServiceImpl service

    def setup() {
        integrationAccountRepository = Mock()
        service = new IntegrationAccountServiceImpl(integrationAccountRepository)
    }

    // ==================== upsert ====================

    def "upsert delegates to repository"() {
        given: "accounts to upsert"
            def connectionId = "conn-123"
            def accounts = [
                createAccount("acc-1", "Expense Account"),
                createAccount("acc-2", "Revenue Account")
            ]

        when: "upserting accounts"
            service.upsert(connectionId, accounts)

        then: "repository is called"
            1 * integrationAccountRepository.upsert(connectionId, accounts)
    }

    // ==================== findByConnectionId ====================

    def "findByConnectionId returns accounts from repository"() {
        given: "accounts exist for connection"
            def connectionId = "conn-123"
            def accounts = [
                createAccount("acc-1", "Account 1"),
                createAccount("acc-2", "Account 2")
            ]

        when: "finding accounts"
            def result = service.findByConnectionId(connectionId)

        then: "repository is queried"
            1 * integrationAccountRepository.findByConnectionId(connectionId) >> accounts

        and: "accounts are returned"
            result.size() == 2
            result[0].id == "acc-1"
            result[1].id == "acc-2"
    }

    def "findByConnectionId returns empty list when no accounts"() {
        given: "no accounts for connection"
            def connectionId = "conn-empty"

        when: "finding accounts"
            def result = service.findByConnectionId(connectionId)

        then: "repository returns empty"
            1 * integrationAccountRepository.findByConnectionId(connectionId) >> []

        and: "empty list returned"
            result.isEmpty()
    }

    // ==================== findById ====================

    def "findById returns account from repository"() {
        given: "an account exists"
            def accountId = "acc-123"
            def account = createAccount(accountId, "Test Account")

        when: "finding account"
            def result = service.findById(accountId)

        then: "repository is queried"
            1 * integrationAccountRepository.findById(accountId) >> account

        and: "account is returned"
            result.id == accountId
            result.name == "Test Account"
    }

    // ==================== findByIds ====================

    def "findByIds returns accounts from repository"() {
        given: "multiple account IDs"
            def connectionId = "conn-123"
            def ids = ["acc-1", "acc-2"]
            def accounts = [
                createAccount("acc-1", "Account 1"),
                createAccount("acc-2", "Account 2")
            ]

        when: "finding accounts by IDs"
            def result = service.findByIds(connectionId, ids)

        then: "repository is queried"
            1 * integrationAccountRepository.findByIds(connectionId, ids) >> accounts

        and: "accounts are returned"
            result.size() == 2
    }

    def "findByIds returns empty list for null IDs"() {
        given: "null IDs"
            def connectionId = "conn-123"

        when: "finding accounts"
            def result = service.findByIds(connectionId, null)

        then: "repository not called"
            0 * integrationAccountRepository.findByIds(_, _)

        and: "empty list returned"
            result.isEmpty()
    }

    def "findByIds returns empty list for empty IDs"() {
        given: "empty IDs list"
            def connectionId = "conn-123"

        when: "finding accounts"
            def result = service.findByIds(connectionId, [])

        then: "repository not called"
            0 * integrationAccountRepository.findByIds(_, _)

        and: "empty list returned"
            result.isEmpty()
    }

    // ==================== findIdsByExternalIdsAndConnection ====================

    def "findIdsByExternalIdsAndConnection returns mapping from repository"() {
        given: "external IDs"
            def connectionId = "conn-123"
            def externalIds = ["ext-1", "ext-2"]
            def mapping = ["ext-1": "acc-1", "ext-2": "acc-2"]

        when: "finding IDs"
            def result = service.findIdsByExternalIdsAndConnection(connectionId, externalIds)

        then: "repository is queried"
            1 * integrationAccountRepository.findIdsByExternalIdsAndConnection(connectionId, externalIds) >> mapping

        and: "mapping is returned"
            result.size() == 2
            result["ext-1"] == "acc-1"
            result["ext-2"] == "acc-2"
    }

    // ==================== Helper Methods ====================

    private static IntegrationAccount createAccount(String id, String name) {
        IntegrationAccount.builder()
            .id(id)
            .name(name)
            .build()
    }
}
