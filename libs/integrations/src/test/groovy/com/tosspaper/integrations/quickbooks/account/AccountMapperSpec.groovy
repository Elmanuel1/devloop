package com.tosspaper.integrations.quickbooks.account

import com.intuit.ipp.data.Account
import com.intuit.ipp.data.AccountClassificationEnum
import com.intuit.ipp.data.AccountTypeEnum
import com.intuit.ipp.data.ModificationMetaData
import com.tosspaper.integrations.fixtures.QBOTestFixtures
import com.tosspaper.models.domain.integration.IntegrationProvider
import spock.lang.Specification
import spock.lang.Subject

class AccountMapperSpec extends Specification {

    @Subject
    AccountMapper mapper = new AccountMapper()

    def "should map full Account to IntegrationAccount with all fields"() {
        given:
        def qboAccount = QBOTestFixtures.loadAccount()

        when:
        def account = mapper.toDomain(qboAccount)

        then:
        account != null

        and: "basic fields are mapped"
        account.name == "Sales of Product Income"
        account.accountType == "Income"
        account.accountSubType == "SalesOfProductIncome"
        account.classification == "Revenue"
        account.active == true
        account.currentBalance == 150000.00

        and: "provider tracking fields are set"
        account.externalId == "79"
        account.provider == IntegrationProvider.QUICKBOOKS.value
        account.providerCreatedAt != null
        account.providerLastUpdatedAt != null
    }

    def "should map Account with minimal fields"() {
        given:
        def qboAccount = new Account()
        qboAccount.setId("123")
        qboAccount.setName("Test Account")
        qboAccount.setActive(true)

        when:
        def account = mapper.toDomain(qboAccount)

        then:
        account != null
        account.name == "Test Account"
        account.externalId == "123"
        account.active == true
        account.accountType == null
        account.accountSubType == null
        account.classification == null
        account.currentBalance == null
    }

    def "should map Account with different account types"() {
        given:
        def qboAccount = new Account()
        qboAccount.setId("456")
        qboAccount.setName("Expense Account")
        qboAccount.setAccountType(AccountTypeEnum.EXPENSE)
        qboAccount.setClassification(AccountClassificationEnum.EXPENSE)
        qboAccount.setAccountSubType("AdvertisingPromotional")
        qboAccount.setActive(false)
        qboAccount.setCurrentBalance(new BigDecimal("5000.00"))

        when:
        def account = mapper.toDomain(qboAccount)

        then:
        account != null
        account.name == "Expense Account"
        account.accountType == "Expense"
        account.classification == "Expense"
        account.accountSubType == "AdvertisingPromotional"
        account.active == false
        account.currentBalance == 5000.00
    }

    def "should handle null metadata gracefully"() {
        given:
        def qboAccount = new Account()
        qboAccount.setId("789")
        qboAccount.setName("No Metadata Account")
        qboAccount.setMetaData(null)

        when:
        def account = mapper.toDomain(qboAccount)

        then:
        account != null
        account.externalId == "789"
        account.providerCreatedAt == null
        account.providerLastUpdatedAt == null
    }

    def "should return null when account is null"() {
        expect:
        mapper.toDomain(null) == null
    }

    def "should map all accounts from list fixture"() {
        given:
        def accounts = QBOTestFixtures.loadAccountsList()

        when:
        def domainAccounts = accounts.collect { mapper.toDomain(it) }

        then:
        domainAccounts.size() >= 1
        domainAccounts.every { it != null }
        domainAccounts.every { it.externalId != null }
        domainAccounts.every { it.provider == IntegrationProvider.QUICKBOOKS.value }
    }
}
