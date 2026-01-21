package com.tosspaper.integrations.api

import com.tosspaper.generated.model.IntegrationSettingsUpdate
import com.tosspaper.generated.model.UpdateIntegrationConnectionRequest
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.models.service.IntegrationsService
import org.springframework.http.HttpStatus
import spock.lang.Specification

import java.time.OffsetDateTime

class IntegrationsControllerSpec extends Specification {

    IntegrationsService integrationsService
    IntegrationsController controller

    def setup() {
        integrationsService = Mock()
        controller = new IntegrationsController(integrationsService)
    }

    // ==================== getIntegrationSettings ====================

    def "getIntegrationSettings returns OK with settings"() {
        given: "valid context and company ID"
            def xContextId = "123"
            def companyId = 123L
            def settings = new IntegrationsService.IntegrationSettings(
                "USD",
                true,
                new BigDecimal("1000.00")
            )

        when: "calling getIntegrationSettings"
            def response = controller.getIntegrationSettings(xContextId, companyId)

        then: "service is called with company ID"
            1 * integrationsService.getSettings(companyId) >> settings

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains settings"
            with(response.body) {
                currency == "USD"
                autoApprovalEnabled == true
                autoApprovalThreshold == new BigDecimal("1000.00")
            }
    }

    def "getIntegrationSettings handles null currency"() {
        given: "settings with null currency"
            def xContextId = "123"
            def companyId = 123L
            def settings = new IntegrationsService.IntegrationSettings(
                null,
                false,
                null
            )

        when: "calling getIntegrationSettings"
            def response = controller.getIntegrationSettings(xContextId, companyId)

        then: "service is called"
            1 * integrationsService.getSettings(companyId) >> settings

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body has null values"
            with(response.body) {
                currency == null
                autoApprovalEnabled == false
                autoApprovalThreshold == null
            }
    }

    // ==================== updateIntegrationSettings ====================

    def "updateIntegrationSettings updates and returns settings"() {
        given: "update request"
            def xContextId = "456"
            def companyId = 456L
            def request = new IntegrationSettingsUpdate()
            request.setCurrency("EUR")
            request.setAutoApprovalEnabled(true)
            request.setAutoApprovalThreshold(new BigDecimal("500.00"))

            def updatedSettings = new IntegrationsService.IntegrationSettings(
                "EUR",
                true,
                new BigDecimal("500.00")
            )

        when: "calling updateIntegrationSettings"
            def response = controller.updateIntegrationSettings(xContextId, companyId, request)

        then: "service is called with update"
            1 * integrationsService.updateSettings(companyId, _) >> { args ->
                def update = args[1] as IntegrationsService.IntegrationSettingsUpdate
                assert update.currency() == "EUR"
                assert update.autoApprovalEnabled() == true
                assert update.autoApprovalThreshold() == new BigDecimal("500.00")
                return updatedSettings
            }

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains updated settings"
            with(response.body) {
                currency == "EUR"
                autoApprovalEnabled == true
                autoApprovalThreshold == new BigDecimal("500.00")
            }
    }

    def "updateIntegrationSettings handles partial update"() {
        given: "partial update request (only currency)"
            def xContextId = "456"
            def companyId = 456L
            def request = new IntegrationSettingsUpdate()
            request.setCurrency("GBP")
            // autoApprovalEnabled and autoApprovalThreshold are null

            def updatedSettings = new IntegrationsService.IntegrationSettings(
                "GBP",
                false,
                null
            )

        when: "calling updateIntegrationSettings"
            def response = controller.updateIntegrationSettings(xContextId, companyId, request)

        then: "service is called"
            1 * integrationsService.updateSettings(companyId, _) >> updatedSettings

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== getIntegrationProviders ====================

    def "getIntegrationProviders returns list of providers"() {
        given: "available providers"
            def providers = [
                new IntegrationsService.ProviderInfo("quickbooks", "QuickBooks Online", "accounting"),
                new IntegrationsService.ProviderInfo("xero", "Xero", "accounting")
            ]

        when: "calling getIntegrationProviders"
            def response = controller.getIntegrationProviders()

        then: "service is called"
            1 * integrationsService.getProviders() >> providers

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains providers"
            response.body.size() == 2
            response.body[0].id == "quickbooks"
            response.body[0].displayName == "QuickBooks Online"
            response.body[0].category == "accounting"
            response.body[1].id == "xero"
    }

    def "getIntegrationProviders returns empty list when no providers"() {
        when: "calling getIntegrationProviders"
            def response = controller.getIntegrationProviders()

        then: "service returns empty list"
            1 * integrationsService.getProviders() >> []

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body is empty"
            response.body.isEmpty()
    }

    // ==================== createAuthUrl ====================

    def "createAuthUrl returns auth URL"() {
        given: "context and provider"
            def xContextId = "789"
            def providerId = "quickbooks"
            def authUrl = new IntegrationsService.OAuthAuthUrl(
                "https://oauth.intuit.com/authorize?client_id=xxx",
                "state-token-123"
            )

        when: "calling createAuthUrl"
            def response = controller.createAuthUrl(xContextId, providerId)

        then: "service is called"
            1 * integrationsService.getAuthUrl(789L, providerId) >> authUrl

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains auth URL"
            response.body.authUrl.toString() == "https://oauth.intuit.com/authorize?client_id=xxx"
            response.body.state == "state-token-123"
    }

    // ==================== handleOAuthCallback ====================

    def "handleOAuthCallback redirects on success"() {
        given: "successful OAuth callback parameters"
            def providerId = "quickbooks"
            def code = "auth-code-123"
            def state = "state-token-123"
            def realmId = "realm-456"
            def redirectUrl = "https://app.tosspaper.com/integrations?success=true"

        when: "calling handleOAuthCallback"
            def response = controller.handleOAuthCallback(providerId, code, null, state, realmId)

        then: "service handles callback"
            1 * integrationsService.handleCallback(code, state, realmId, providerId) >> redirectUrl

        and: "redirect view is returned"
            response.url == redirectUrl
    }

    def "handleOAuthCallback handles error parameter"() {
        given: "OAuth callback with error"
            def providerId = "quickbooks"
            def error = "access_denied"
            def state = "state-token-123"
            def redirectUrl = "https://app.tosspaper.com/integrations?error=access_denied"

        when: "calling handleOAuthCallback with error"
            def response = controller.handleOAuthCallback(providerId, null, error, state, null)

        then: "service handles error callback"
            1 * integrationsService.handleCallbackError(error, state, providerId) >> redirectUrl

        and: "redirect view is returned"
            response.url == redirectUrl
    }

    def "handleOAuthCallback handles invalid callback (no code or error)"() {
        given: "OAuth callback without code or error"
            def providerId = "quickbooks"
            def state = "state-token-123"
            def redirectUrl = "https://app.tosspaper.com/integrations?error=invalid_callback"

        when: "calling handleOAuthCallback without code or error"
            def response = controller.handleOAuthCallback(providerId, null, null, state, null)

        then: "service handles as error"
            1 * integrationsService.handleCallbackError("invalid_callback", state, providerId) >> redirectUrl

        and: "redirect view is returned"
            response.url == redirectUrl
    }

    // ==================== updateIntegrationConnection ====================

    def "updateIntegrationConnection enables connection"() {
        given: "update request to enable connection"
            def xContextId = "123"
            def connectionId = "conn-456"
            def request = new UpdateIntegrationConnectionRequest()
            request.setStatus(UpdateIntegrationConnectionRequest.StatusEnum.ENABLED)

            def connection = createConnection(connectionId, IntegrationConnectionStatus.ENABLED)

        when: "calling updateIntegrationConnection"
            def response = controller.updateIntegrationConnection(xContextId, connectionId, request)

        then: "service updates connection status and returns updated connection"
            1 * integrationsService.updateConnectionStatus(connectionId, 123L, IntegrationConnectionStatus.ENABLED) >> connection

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains updated connection"
            response.body.id == connectionId
            response.body.status == com.tosspaper.generated.model.IntegrationConnection.StatusEnum.ENABLED
    }

    def "updateIntegrationConnection disables connection"() {
        given: "update request to disable connection"
            def xContextId = "123"
            def connectionId = "conn-456"
            def request = new UpdateIntegrationConnectionRequest()
            request.setStatus(UpdateIntegrationConnectionRequest.StatusEnum.DISABLED)

            def connection = createConnection(connectionId, IntegrationConnectionStatus.DISABLED)

        when: "calling updateIntegrationConnection"
            def response = controller.updateIntegrationConnection(xContextId, connectionId, request)

        then: "service updates connection status and returns updated connection"
            1 * integrationsService.updateConnectionStatus(connectionId, 123L, IntegrationConnectionStatus.DISABLED) >> connection

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "updateIntegrationConnection returns bad request when status is null"() {
        given: "update request without status"
            def xContextId = "123"
            def connectionId = "conn-456"
            def request = new UpdateIntegrationConnectionRequest()
            // status is null

        when: "calling updateIntegrationConnection"
            def response = controller.updateIntegrationConnection(xContextId, connectionId, request)

        then: "no service calls made"
            0 * integrationsService._

        and: "response status is BAD_REQUEST"
            response.statusCode == HttpStatus.BAD_REQUEST
    }

    def "updateIntegrationConnection successfully processes ENABLED status"() {
        given: "update request to enable"
            def xContextId = "123"
            def connectionId = "conn-456"
            def request = new UpdateIntegrationConnectionRequest()
            request.setStatus(UpdateIntegrationConnectionRequest.StatusEnum.ENABLED)

            def connection = createConnection(connectionId, IntegrationConnectionStatus.ENABLED)

        when: "calling updateIntegrationConnection"
            def response = controller.updateIntegrationConnection(xContextId, connectionId, request)

        then: "status is updated and connection returned"
            1 * integrationsService.updateConnectionStatus(connectionId, 123L, IntegrationConnectionStatus.ENABLED) >> connection

        and: "response is OK"
            response.statusCode == HttpStatus.OK
    }

    def "updateIntegrationConnection successfully processes DISABLED status"() {
        given: "update request to disable"
            def xContextId = "123"
            def connectionId = "conn-456"
            def request = new UpdateIntegrationConnectionRequest()
            request.setStatus(UpdateIntegrationConnectionRequest.StatusEnum.DISABLED)

            def connection = createConnection(connectionId, IntegrationConnectionStatus.DISABLED)

        when: "calling updateIntegrationConnection"
            def response = controller.updateIntegrationConnection(xContextId, connectionId, request)

        then: "status is updated and connection returned"
            1 * integrationsService.updateConnectionStatus(connectionId, 123L, IntegrationConnectionStatus.DISABLED) >> connection

        and: "response is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== listIntegrationConnections ====================

    def "listIntegrationConnections returns connections"() {
        given: "connections exist for company"
            def xContextId = "123"
            def connections = [
                createConnection("conn-1", IntegrationConnectionStatus.ENABLED),
                createConnection("conn-2", IntegrationConnectionStatus.DISABLED)
            ]

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service is called"
            1 * integrationsService.listConnections(123L) >> connections

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body contains connections"
            response.body.size() == 2
            response.body[0].id == "conn-1"
            response.body[1].id == "conn-2"
    }

    def "listIntegrationConnections returns empty list when no connections"() {
        given: "no connections for company"
            def xContextId = "123"

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service returns empty list"
            1 * integrationsService.listConnections(123L) >> []

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body is empty"
            response.body.isEmpty()
    }

    def "listIntegrationConnections maps connection with preferences"() {
        given: "connection with currency preferences"
            def xContextId = "123"
            def connection = createConnectionWithPreferences(
                "conn-1",
                IntegrationConnectionStatus.ENABLED,
                Currency.USD,
                true
            )

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service is called"
            1 * integrationsService.listConnections(123L) >> [connection]

        and: "response includes preferences"
            response.statusCode == HttpStatus.OK
            response.body[0].preferences != null
            response.body[0].preferences.defaultCurrency == "USD"
            response.body[0].preferences.multicurrencyEnabled == true
    }

    def "listIntegrationConnections handles null preferences"() {
        given: "connection without currency preferences"
            def xContextId = "123"
            def connection = createConnection("conn-1", IntegrationConnectionStatus.ENABLED)
            // defaultCurrency and multicurrencyEnabled are null

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service is called"
            1 * integrationsService.listConnections(123L) >> [connection]

        and: "response has no preferences"
            response.statusCode == HttpStatus.OK
            response.body[0].preferences == null
    }

    def "listIntegrationConnections handles only defaultCurrency set"() {
        given: "connection with only defaultCurrency"
            def xContextId = "123"
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

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service is called"
            1 * integrationsService.listConnections(123L) >> [connection]

        and: "response includes preferences with only currency"
            response.statusCode == HttpStatus.OK
            response.body[0].preferences != null
            response.body[0].preferences.defaultCurrency == "EUR"
            response.body[0].preferences.multicurrencyEnabled == null
    }

    def "listIntegrationConnections handles only multicurrencyEnabled set"() {
        given: "connection with only multicurrencyEnabled"
            def xContextId = "123"
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

        when: "calling listIntegrationConnections"
            def response = controller.listIntegrationConnections(xContextId)

        then: "service is called"
            1 * integrationsService.listConnections(123L) >> [connection]

        and: "response includes preferences with only multicurrency flag"
            response.statusCode == HttpStatus.OK
            response.body[0].preferences != null
            response.body[0].preferences.defaultCurrency == null
            response.body[0].preferences.multicurrencyEnabled == false
    }

    // ==================== disconnectIntegration ====================

    def "disconnectIntegration removes connection"() {
        given: "connection to disconnect"
            def xContextId = "123"
            def connectionId = "conn-456"

        when: "calling disconnectIntegration"
            def response = controller.disconnectIntegration(xContextId, connectionId)

        then: "service is called"
            1 * integrationsService.disconnect(connectionId, 123L)

        and: "response status is NO_CONTENT"
            response.statusCode == HttpStatus.NO_CONTENT
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
