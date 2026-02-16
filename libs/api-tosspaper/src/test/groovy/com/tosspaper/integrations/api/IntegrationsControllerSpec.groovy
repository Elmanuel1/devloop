package com.tosspaper.integrations.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.config.TestSecurityConfiguration
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.IntegrationsService
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException

import java.time.OffsetDateTime

class IntegrationsControllerSpec extends BaseIntegrationTest {

    @SpringBean
    IntegrationsService integrationsService = Mock()

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    // ==================== getIntegrationSettings ====================

    def "getIntegrationSettings returns OK with settings"() {
        given: "valid context and company ID"
            def settings = new IntegrationsService.IntegrationSettings(
                "USD",
                true,
                new BigDecimal("1000.00")
            )
            integrationsService.getSettings(123L) >> settings

        and: "auth headers with X-Context-Id"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling getIntegrationSettings"
            def response = restTemplate.exchange("/v1/companies/123/integration-settings", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains settings"
            def body = objectMapper.readValue(response.body, Map)
            body.currency == "USD"
            body.autoApprovalEnabled == true
            body.autoApprovalThreshold == 1000.00
    }

    def "getIntegrationSettings handles null currency"() {
        given: "settings with null currency"
            def settings = new IntegrationsService.IntegrationSettings(
                null,
                false,
                null
            )
            integrationsService.getSettings(123L) >> settings

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling getIntegrationSettings"
            def response = restTemplate.exchange("/v1/companies/123/integration-settings", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body has null values"
            def body = objectMapper.readValue(response.body, Map)
            body.currency == null
            body.autoApprovalEnabled == false
            body.autoApprovalThreshold == null
    }

    // ==================== updateIntegrationSettings ====================

    def "updateIntegrationSettings updates and returns settings"() {
        given: "update request"
            def updatedSettings = new IntegrationsService.IntegrationSettings(
                "EUR",
                true,
                new BigDecimal("500.00")
            )
            integrationsService.updateSettings(456L, _ as IntegrationsService.IntegrationSettingsUpdate) >> updatedSettings

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "456")
            headers.setContentType(MediaType.APPLICATION_JSON)

            def requestBody = [currency: "EUR", autoApprovalEnabled: true, autoApprovalThreshold: 500.00]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling updateIntegrationSettings"
            def response = restTemplate.exchange("/v1/companies/456/integration-settings", HttpMethod.PUT, entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains updated settings"
            def body = objectMapper.readValue(response.body, Map)
            body.currency == "EUR"
            body.autoApprovalEnabled == true
            body.autoApprovalThreshold == 500.00
    }

    def "updateIntegrationSettings handles partial update"() {
        given: "partial update request (only currency)"
            def updatedSettings = new IntegrationsService.IntegrationSettings(
                "GBP",
                false,
                null
            )
            integrationsService.updateSettings(456L, _ as IntegrationsService.IntegrationSettingsUpdate) >> updatedSettings

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "456")
            headers.setContentType(MediaType.APPLICATION_JSON)

            def requestBody = [currency: "GBP"]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling updateIntegrationSettings"
            def response = restTemplate.exchange("/v1/companies/456/integration-settings", HttpMethod.PUT, entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== getIntegrationProviders ====================

    def "getIntegrationProviders returns list of providers"() {
        given: "available providers"
            def providers = [
                new IntegrationsService.ProviderInfo("quickbooks", "QuickBooks Online", "accounting"),
                new IntegrationsService.ProviderInfo("xero", "Xero", "accounting")
            ]
            integrationsService.getProviders() >> providers

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())

        when: "calling getIntegrationProviders"
            def response = restTemplate.exchange("/v1/integrations/providers", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains providers"
            def body = objectMapper.readValue(response.body, List)
            body.size() == 2
            body[0].id == "quickbooks"
            body[0].displayName == "QuickBooks Online"
            body[0].category == "accounting"
            body[1].id == "xero"
    }

    def "getIntegrationProviders returns empty list when no providers"() {
        given: "no providers"
            integrationsService.getProviders() >> []

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())

        when: "calling getIntegrationProviders"
            def response = restTemplate.exchange("/v1/integrations/providers", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body is empty"
            def body = objectMapper.readValue(response.body, List)
            body.isEmpty()
    }

    // ==================== createAuthUrl ====================

    def "createAuthUrl returns auth URL"() {
        given: "context and provider"
            def authUrl = new IntegrationsService.OAuthAuthUrl(
                "https://oauth.intuit.com/authorize?client_id=xxx",
                "state-token-123"
            )
            integrationsService.getAuthUrl(789L, "quickbooks") >> authUrl

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "789")
            headers.setContentType(MediaType.APPLICATION_JSON)

        when: "calling createAuthUrl"
            def response = restTemplate.exchange("/v1/integrations/quickbooks/auth-urls", HttpMethod.POST, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains auth URL"
            def body = objectMapper.readValue(response.body, Map)
            body.authUrl == "https://oauth.intuit.com/authorize?client_id=xxx"
            body.state == "state-token-123"
    }

    // ==================== handleOAuthCallback ====================

    def "handleOAuthCallback redirects on success"() {
        given: "successful OAuth callback parameters"
            def redirectUrl = "http://localhost:3000/dashboard/organization?tab=settings&section=integrations&success=true"
            integrationsService.handleCallback("auth-code-123", "state-token-123", "realm-456", "quickbooks") >> redirectUrl

        when: "calling handleOAuthCallback - OAuth callback is a public endpoint called by the provider"
            try {
                restTemplate.getForEntity(
                    "/v1/integrations/quickbooks/callback?code=auth-code-123&state=state-token-123&realmId=realm-456",
                    String
                )
            } catch (ResourceAccessException ignored) {
                // Expected: TestRestTemplate follows the redirect to localhost:3000 which isn't running
            }

        then: "service is called to handle the callback"
            1 * integrationsService.handleCallback("auth-code-123", "state-token-123", "realm-456", "quickbooks") >> redirectUrl
    }

    def "handleOAuthCallback handles error parameter"() {
        given: "OAuth callback with error"
            def redirectUrl = "http://localhost:3000/dashboard/organization?tab=settings&section=integrations&error=access_denied"
            integrationsService.handleCallbackError("access_denied", "state-token-123", "quickbooks") >> redirectUrl

        when: "calling handleOAuthCallback with error"
            try {
                restTemplate.getForEntity(
                    "/v1/integrations/quickbooks/callback?error=access_denied&state=state-token-123",
                    String
                )
            } catch (ResourceAccessException ignored) {
                // Expected: TestRestTemplate follows the redirect to localhost:3000 which isn't running
            }

        then: "service handles error callback"
            1 * integrationsService.handleCallbackError("access_denied", "state-token-123", "quickbooks") >> redirectUrl
    }

    def "handleOAuthCallback handles invalid callback (no code or error)"() {
        given: "OAuth callback without code or error"
            def redirectUrl = "http://localhost:3000/dashboard/organization?tab=settings&section=integrations&error=invalid_callback"
            integrationsService.handleCallbackError("invalid_callback", "state-token-123", "quickbooks") >> redirectUrl

        when: "calling handleOAuthCallback without code or error"
            try {
                restTemplate.getForEntity(
                    "/v1/integrations/quickbooks/callback?state=state-token-123",
                    String
                )
            } catch (ResourceAccessException ignored) {
                // Expected: TestRestTemplate follows the redirect to localhost:3000 which isn't running
            }

        then: "service handles as error"
            1 * integrationsService.handleCallbackError("invalid_callback", "state-token-123", "quickbooks") >> redirectUrl
    }

    // ==================== updateIntegrationConnection ====================

    def "updateIntegrationConnection enables connection"() {
        given: "update request to enable connection"
            def connection = createConnection("conn-456", IntegrationConnectionStatus.ENABLED)
            integrationsService.updateConnectionStatus("conn-456", 123L, IntegrationConnectionStatus.ENABLED) >> connection

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(MediaType.APPLICATION_JSON)

            def requestBody = [status: "enabled"]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling updateIntegrationConnection"
            def response = restTemplate.exchange("/v1/integrations/connections/conn-456", HttpMethod.PUT, entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains updated connection"
            def body = objectMapper.readValue(response.body, Map)
            body.id == "conn-456"
            body.status == "enabled"
    }

    def "updateIntegrationConnection disables connection"() {
        given: "update request to disable connection"
            def connection = createConnection("conn-456", IntegrationConnectionStatus.DISABLED)
            integrationsService.updateConnectionStatus("conn-456", 123L, IntegrationConnectionStatus.DISABLED) >> connection

        and: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(MediaType.APPLICATION_JSON)

            def requestBody = [status: "disabled"]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling updateIntegrationConnection"
            def response = restTemplate.exchange("/v1/integrations/connections/conn-456", HttpMethod.PUT, entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "updateIntegrationConnection returns bad request when status is null"() {
        given: "update request without status"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")
            headers.setContentType(MediaType.APPLICATION_JSON)

            def requestBody = [:]
            def entity = new HttpEntity<>(requestBody, headers)

        when: "calling updateIntegrationConnection without status"
            def response = restTemplate.exchange("/v1/integrations/connections/conn-456", HttpMethod.PUT, entity, String)

        then: "response status is BAD_REQUEST"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    // ==================== listIntegrationConnections ====================

    def "listIntegrationConnections returns connections"() {
        given: "connections exist for company"
            def connections = [
                createConnection("conn-1", IntegrationConnectionStatus.ENABLED),
                createConnection("conn-2", IntegrationConnectionStatus.DISABLED)
            ]
            integrationsService.listConnections(123L) >> connections

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains connections"
            def body = objectMapper.readValue(response.body, List)
            body.size() == 2
            body[0].id == "conn-1"
            body[1].id == "conn-2"
    }

    def "listIntegrationConnections returns empty list when no connections"() {
        given: "no connections for company"
            integrationsService.listConnections(123L) >> []

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body is empty"
            def body = objectMapper.readValue(response.body, List)
            body.isEmpty()
    }

    def "listIntegrationConnections maps connection with preferences"() {
        given: "connection with currency preferences"
            def connection = createConnectionWithPreferences(
                "conn-1",
                IntegrationConnectionStatus.ENABLED,
                Currency.USD,
                true
            )
            integrationsService.listConnections(123L) >> [connection]

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response includes preferences"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, List)
            body[0].preferences != null
            body[0].preferences.defaultCurrency == "USD"
            body[0].preferences.multicurrencyEnabled == true
    }

    def "listIntegrationConnections handles null preferences"() {
        given: "connection without currency preferences"
            def connection = createConnection("conn-1", IntegrationConnectionStatus.ENABLED)
            integrationsService.listConnections(123L) >> [connection]

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response has no preferences"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, List)
            body[0].preferences == null
    }

    def "listIntegrationConnections handles only defaultCurrency set"() {
        given: "connection with only defaultCurrency"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(123L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .realmId("realm-123")
                .externalCompanyName("Test Company")
                .defaultCurrency(Currency.EUR)
                .multicurrencyEnabled(null)
                .createdAt(OffsetDateTime.now())
                .build()
            integrationsService.listConnections(123L) >> [connection]

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response includes preferences with only currency"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, List)
            body[0].preferences != null
            body[0].preferences.defaultCurrency == "EUR"
            body[0].preferences.multicurrencyEnabled == null
    }

    def "listIntegrationConnections handles only multicurrencyEnabled set"() {
        given: "connection with only multicurrencyEnabled"
            def connection = IntegrationConnection.builder()
                .id("conn-1")
                .companyId(123L)
                .provider(IntegrationProvider.QUICKBOOKS)
                .status(IntegrationConnectionStatus.ENABLED)
                .realmId("realm-123")
                .externalCompanyName("Test Company")
                .defaultCurrency(null)
                .multicurrencyEnabled(false)
                .createdAt(OffsetDateTime.now())
                .build()
            integrationsService.listConnections(123L) >> [connection]

        and: "auth headers"
            def headers = new HttpHeaders()
            headers.setBearerAuth(TestSecurityConfiguration.getTestToken())
            headers.add("X-Context-Id", "123")

        when: "calling listIntegrationConnections"
            def response = restTemplate.exchange("/v1/integrations/connections", HttpMethod.GET, new HttpEntity<>(headers), String)

        then: "response includes preferences with only multicurrency flag"
            response.statusCode == HttpStatus.OK
            def body = objectMapper.readValue(response.body, List)
            body[0].preferences != null
            body[0].preferences.defaultCurrency == null
            body[0].preferences.multicurrencyEnabled == false
    }

    // ==================== disconnectIntegration ====================

    def "disconnectIntegration removes connection"() {
        given: "csrf and auth headers"
            def (csrfToken, csrfCookie) = initializeCsrfToken(restTemplate)
            def headers = createAuthHeaders(csrfToken, csrfCookie)
            headers.add("X-Context-Id", "123")

        when: "calling disconnectIntegration"
            def response = restTemplate.exchange("/v1/integrations/connections/conn-456", HttpMethod.DELETE, new HttpEntity<>(headers), String)

        then: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT

        and: "service is called"
            1 * integrationsService.disconnect("conn-456", 123L)
    }

    // ==================== Helper Methods ====================

    private IntegrationConnection createConnection(String id, IntegrationConnectionStatus status) {
        IntegrationConnection.builder()
            .id(id)
            .companyId(123L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(status)
            .realmId("realm-123")
            .externalCompanyName("Test Company")
            .createdAt(OffsetDateTime.now())
            .build()
    }

    private IntegrationConnection createConnectionWithPreferences(
            String id,
            IntegrationConnectionStatus status,
            Currency defaultCurrency,
            Boolean multicurrencyEnabled) {
        IntegrationConnection.builder()
            .id(id)
            .companyId(123L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(status)
            .realmId("realm-123")
            .externalCompanyName("Test Company")
            .defaultCurrency(defaultCurrency)
            .multicurrencyEnabled(multicurrencyEnabled)
            .createdAt(OffsetDateTime.now())
            .build()
    }
}
