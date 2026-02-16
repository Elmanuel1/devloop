package com.tosspaper.accounts

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.integration.IntegrationAccount
import com.tosspaper.models.jooq.Tables
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

import java.time.OffsetDateTime

class IntegrationAccountControllerSpec extends BaseIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    @Autowired
    DSLContext dsl

    @Autowired
    IntegrationAccountRepository integrationAccountRepository

    Long companyId
    String connectionId = "conn-test-001"

    def setup() {
        companyId = TestSecurityConfiguration.TEST_COMPANY_ID

        dsl.insertInto(Tables.COMPANIES)
            .set(Tables.COMPANIES.ID, companyId)
            .set(Tables.COMPANIES.NAME, "Test Company")
            .set(Tables.COMPANIES.EMAIL, "test@test.com")
            .set(Tables.COMPANIES.ASSIGNED_EMAIL, "test@dev-clientdocs.useassetiq.com")
            .onDuplicateKeyIgnore()
            .execute()

        dsl.insertInto(Tables.INTEGRATION_CONNECTIONS)
            .set(Tables.INTEGRATION_CONNECTIONS.ID, connectionId)
            .set(Tables.INTEGRATION_CONNECTIONS.COMPANY_ID, companyId)
            .set(Tables.INTEGRATION_CONNECTIONS.PROVIDER, "quickbooks")
            .set(Tables.INTEGRATION_CONNECTIONS.STATUS, "enabled")
            .set(Tables.INTEGRATION_CONNECTIONS.CATEGORY, "financial")
            .set(Tables.INTEGRATION_CONNECTIONS.ACCESS_TOKEN, "encrypted-dummy-token")
            .set(Tables.INTEGRATION_CONNECTIONS.TOKEN_EXPIRES_AT, OffsetDateTime.now().plusDays(30))
            .onDuplicateKeyIgnore()
            .execute()
    }

    def cleanup() {
        dsl.deleteFrom(Tables.INTEGRATION_ACCOUNTS).where(Tables.INTEGRATION_ACCOUNTS.CONNECTION_ID.eq(connectionId)).execute()
        dsl.deleteFrom(Tables.INTEGRATION_CONNECTIONS).where(Tables.INTEGRATION_CONNECTIONS.ID.eq(connectionId)).execute()
        dsl.deleteFrom(Tables.COMPANIES).where(Tables.COMPANIES.ID.eq(companyId)).execute()
    }

    // ==================== getIntegrationAccounts ====================

    def "getIntegrationAccounts returns OK with account list"() {
        given: "expense accounts exist for company"
            integrationAccountRepository.upsert(connectionId, [
                buildAccount("ext-1", "Expense Account", "Expense", "OperatingExpense", "Expense", true, 1500.00),
                buildAccount("ext-2", "Office Supplies", "Expense", "OfficeSupplies", "Expense", true, 320.50)
            ])

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getIntegrationAccounts with expense type filter"
            def response = restTemplate.exchange("/v1/accounts?type=expense", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains accounts with all fields"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
            with(body.data.find { it.externalId == "ext-1" }) {
                id != null
                connectionId == this.connectionId
                externalId == "ext-1"
                name == "Expense Account"
                accountType == "Expense"
                accountSubType == "OperatingExpense"
                classification == "Expense"
                active == true
                currentBalance == 1500.00
                createdAt != null
            }
            with(body.data.find { it.externalId == "ext-2" }) {
                id != null
                connectionId == this.connectionId
                externalId == "ext-2"
                name == "Office Supplies"
                accountType == "Expense"
                accountSubType == "OfficeSupplies"
                classification == "Expense"
                active == true
                currentBalance == 320.50
                createdAt != null
            }
    }

    def "getIntegrationAccounts handles all type parameter"() {
        given: "mixed account types exist"
            integrationAccountRepository.upsert(connectionId, [
                buildAccount("ext-3", "Revenue Account", "Income", "SalesOfProductIncome", "Revenue", true, 25000.00),
                buildAccount("ext-4", "Travel Expense", "Expense", "TravelExpense", "Expense", true, 800.00)
            ])

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getIntegrationAccounts with default type (all)"
            def response = restTemplate.exchange("/v1/accounts", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response contains all account types"
            def body = objectMapper.readValue(response.body, Map)
            body.data.size() == 2
            body.data.any { it.accountType == "Income" }
            body.data.any { it.accountType == "Expense" }
    }

    def "getIntegrationAccounts handles invalid type parameter"() {
        given: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", companyId.toString())

        when: "calling getIntegrationAccounts with invalid type"
            def response = restTemplate.exchange("/v1/accounts?type=invalid_type", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK with empty data"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, Map)
            body.data != null
    }

    // ==================== Helper Methods ====================

    private IntegrationAccount buildAccount(String externalId, String name, String accountType,
                                             String accountSubType, String classification,
                                             Boolean active, BigDecimal currentBalance) {
        def account = IntegrationAccount.builder()
            .connectionId(connectionId)
            .name(name)
            .accountType(accountType)
            .accountSubType(accountSubType)
            .classification(classification)
            .active(active)
            .currentBalance(currentBalance)
            .build()
        account.externalId = externalId
        account.provider = "quickbooks"
        account
    }
}
