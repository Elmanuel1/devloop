package com.tosspaper.integrations.service

import com.tosspaper.integrations.oauth.OAuthStateService
import com.tosspaper.integrations.oauth.OAuthTokens
import com.tosspaper.integrations.provider.IntegrationCompanyInfoProvider
import com.tosspaper.integrations.provider.IntegrationOAuthProvider
import com.tosspaper.integrations.provider.IntegrationProviderFactory
import com.tosspaper.models.domain.integration.IntegrationProvider
import com.tosspaper.integrations.temporal.IntegrationScheduleManager
import com.tosspaper.models.domain.Currency
import com.tosspaper.models.domain.integration.IntegrationCategory
import com.tosspaper.models.domain.integration.IntegrationConnection
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus
import com.tosspaper.models.jooq.tables.records.CompaniesRecord
import com.tosspaper.models.service.CompanyLookupService
import com.tosspaper.models.service.IntegrationsService
import org.jooq.DSLContext
import org.jooq.Result
import org.jooq.SelectConditionStep
import org.jooq.SelectWhereStep
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

import static com.tosspaper.models.jooq.Tables.COMPANIES

/**
 * Comprehensive tests for IntegrationsServiceImpl.
 * Tests all service methods, connection management, and settings retrieval.
 */
class IntegrationsServiceImplSpec extends Specification {

    DSLContext dsl = Mock()
    IntegrationProviderFactory providerFactory = Mock()
    OAuthStateService oauthStateService = Mock()
    IntegrationConnectionService connectionService = Mock()
    CompanyLookupService companyLookupService = Mock()
    IntegrationScheduleManager scheduleManager = Mock()

    @Subject
    IntegrationsServiceImpl service = new IntegrationsServiceImpl(
        dsl,
        providerFactory,
        oauthStateService,
        connectionService,
        companyLookupService,
        scheduleManager
    )

    def setup() {
        service.integrationsRedirectUrl = "https://app.example.com/integrations"
    }

    /**
     * Creates a CompaniesRecord attached to a mock configuration so that
     * record.update() doesn't throw DetachedException.
     */
    private CompaniesRecord createAttachedCompaniesRecord(Long id, String currency, Boolean autoApproval, BigDecimal threshold) {
        // Create a mock PreparedStatement that returns 1 row updated
        def mockPreparedStatement = Stub(java.sql.PreparedStatement) {
            executeUpdate() >> 1
            getUpdateCount() >> 1
        }
        // Create a mock Connection
        def mockConnection = Stub(java.sql.Connection) {
            prepareStatement(_) >> mockPreparedStatement
            prepareStatement(_, _) >> mockPreparedStatement
        }
        // Use a real DefaultConfiguration with the mock connection
        def config = new org.jooq.impl.DefaultConfiguration()
        config.setSQLDialect(org.jooq.SQLDialect.POSTGRES)
        config.setConnectionProvider({ -> mockConnection } as org.jooq.ConnectionProvider)

        def company = new CompaniesRecord()
        company.setId(id)
        company.setCurrency(currency)
        company.setAutoApprovalEnabled(autoApproval)
        company.setAutoApprovalThreshold(threshold)
        company.attach(config)
        // Reset changed flags so update knows which fields to update
        company.changed(false)
        return company
    }

    def "getSettings should return company auto approval settings"() {
        given:
        def autoApprovalSettings = new CompanyLookupService.AutoApprovalSettings(
            true,
            new BigDecimal("5000.00"),
            "USD"
        )

        when:
        def result = service.getSettings(100L)

        then:
        1 * companyLookupService.getAutoApprovalSettings(100L) >> autoApprovalSettings
        result.currency() == "USD"
        result.autoApprovalEnabled() == true
        result.autoApprovalThreshold() == new BigDecimal("5000.00")
    }

    def "updateSettings should update company settings and return updated values"() {
        given:
        def updateRequest = new IntegrationsService.IntegrationSettingsUpdate(
            "CAD",
            false,
            new BigDecimal("10000.00")
        )

        def company = createAttachedCompaniesRecord(100L, "USD", true, new BigDecimal("5000.00"))

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        when:
        def updated = service.updateSettings(100L, updateRequest)

        then:
        1 * dsl.selectFrom(COMPANIES) >> selectStep
        1 * selectStep.where(_) >> conditionStep
        1 * conditionStep.fetchOne() >> company
        updated.currency() == "CAD"
        updated.autoApprovalEnabled() == false
        updated.autoApprovalThreshold() == new BigDecimal("10000.00")
    }

    def "updateSettings should throw exception when company not found"() {
        given:
        def updateRequest = new IntegrationsService.IntegrationSettingsUpdate(
            "CAD",
            null,
            null
        )

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        when:
        service.updateSettings(999L, updateRequest)

        then:
        1 * dsl.selectFrom(COMPANIES) >> selectStep
        1 * selectStep.where(_) >> conditionStep
        1 * conditionStep.fetchOne() >> null
        thrown(IllegalArgumentException)
    }

    def "updateSettings should only update provided fields"() {
        given:
        def updateRequest = new IntegrationsService.IntegrationSettingsUpdate(
            null,
            false,
            null
        )

        def company = createAttachedCompaniesRecord(100L, "USD", true, new BigDecimal("5000.00"))

        def selectStep = Mock(SelectWhereStep)
        def conditionStep = Mock(SelectConditionStep)

        when:
        def updated = service.updateSettings(100L, updateRequest)

        then:
        1 * dsl.selectFrom(COMPANIES) >> selectStep
        1 * selectStep.where(_) >> conditionStep
        1 * conditionStep.fetchOne() >> company
        updated.currency() == "USD"
        updated.autoApprovalEnabled() == false
        updated.autoApprovalThreshold() == new BigDecimal("5000.00")
    }

    def "getProviders should return list of all available providers"() {
        given:
        def providers = [
            new IntegrationProviderFactory.ProviderInfo("quickbooks", "QuickBooks", "accounting")
        ]

        when:
        def result = service.getProviders()

        then:
        1 * providerFactory.getAllProviders() >> providers
        result.size() == 1
        result[0].id() == "quickbooks"
        result[0].displayName() == "QuickBooks"
        result[0].category() == "accounting"
    }

    def "getAuthUrl should generate OAuth authorization URL with state"() {
        given:
        def oauthProvider = Mock(IntegrationOAuthProvider)

        when:
        def result = service.getAuthUrl(100L, "quickbooks")

        then:
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthStateService.storeState(_ as String, 100L, "quickbooks")
        1 * oauthProvider.buildAuthorizationUrl(_ as String) >> "https://oauth.example.com/authorize?state=abc"
        result.authUrl() == "https://oauth.example.com/authorize?state=abc"
        result.state() != null
    }

    def "handleCallback should create integration connection successfully"() {
        given:
        def code = "auth-code-123"
        def state = "state-456"
        def realmId = "realm-789"
        def stateData = new OAuthStateService.StateData(100L, "quickbooks")

        def tokens = new OAuthTokens(
            "access-token",
            "refresh-token",
            OffsetDateTime.now().plusHours(1),
            OffsetDateTime.now().plusDays(100),
            "realm-789"
        )

        def companyInfo = new IntegrationCompanyInfoProvider.CompanyInfo(
            "realm-789",
            "Test Company",
            Currency.USD
        )

        def oauthProvider = Mock(IntegrationOAuthProvider)
        oauthProvider.getDisplayName() >> "QuickBooks"

        def companyInfoProvider = Mock(IntegrationCompanyInfoProvider)

        when:
        def result = service.handleCallback(code, state, realmId, "quickbooks")

        then:
        1 * oauthStateService.validateAndConsumeState(state) >> stateData
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.exchangeCodeForTokens(code, realmId, 100L) >> tokens
        1 * providerFactory.getCompanyInfoProvider(IntegrationProvider.QUICKBOOKS) >> Optional.of(companyInfoProvider)
        1 * companyInfoProvider.fetchCompanyInfo("access-token", "realm-789") >> companyInfo
        1 * connectionService.create(_) >> { IntegrationConnection conn ->
            assert conn.companyId == 100L
            assert conn.provider == IntegrationProvider.QUICKBOOKS
            assert conn.status == IntegrationConnectionStatus.DISABLED
            return conn
        }
        result.contains("status=success")
        result.contains("provider=quickbooks")
    }

    def "handleCallback should return error URL on authentication exception"() {
        given:
        def code = "auth-code-123"
        def state = "state-456"
        def stateData = new OAuthStateService.StateData(100L, "quickbooks")

        def oauthProvider = Mock(IntegrationOAuthProvider)

        when:
        def result = service.handleCallback(code, state, null, "quickbooks")

        then:
        1 * oauthStateService.validateAndConsumeState(state) >> stateData
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.exchangeCodeForTokens(code, null, 100L) >> { throw new com.tosspaper.integrations.common.exception.IntegrationAuthException("Auth failed", "invalid_grant") }
        result.contains("status=error")
        result.contains("error=invalid_grant")
    }

    def "handleCallback should handle missing company info provider"() {
        given:
        def code = "auth-code-123"
        def state = "state-456"
        def stateData = new OAuthStateService.StateData(100L, "quickbooks")

        def tokens = new OAuthTokens(
            "access-token",
            "refresh-token",
            OffsetDateTime.now().plusHours(1),
            OffsetDateTime.now().plusDays(100),
            "realm-789"
        )

        def oauthProvider = Mock(IntegrationOAuthProvider)

        when:
        def result = service.handleCallback(code, state, null, "quickbooks")

        then:
        1 * oauthStateService.validateAndConsumeState(state) >> stateData
        1 * providerFactory.getOAuthProvider(IntegrationProvider.QUICKBOOKS) >> oauthProvider
        1 * oauthProvider.exchangeCodeForTokens(code, null, 100L) >> tokens
        1 * providerFactory.getCompanyInfoProvider(IntegrationProvider.QUICKBOOKS) >> Optional.empty()
        result.contains("status=error")
        result.contains("error=connection_failed")
    }

    def "handleCallbackError should consume state and return error URL"() {
        given:
        def state = "state-123"
        def error = "access_denied"
        def stateData = new OAuthStateService.StateData(100L, "quickbooks")

        when:
        def result = service.handleCallbackError(error, state, "quickbooks")

        then:
        1 * oauthStateService.validateAndConsumeState(state) >> stateData
        result.contains("status=error")
        result.contains("error=access_denied")
    }

    def "listConnections should return all connections for company"() {
        given:
        def connections = [
            IntegrationConnection.builder().id("conn-1").companyId(100L).build(),
            IntegrationConnection.builder().id("conn-2").companyId(100L).build()
        ]

        when:
        def result = service.listConnections(100L)

        then:
        1 * connectionService.listByCompany(100L) >> connections
        result.size() == 2
    }

    def "disconnect should delete schedule and disconnect connection"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .build()

        when:
        service.disconnect("conn-1", 100L)

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * scheduleManager.deleteSchedule(connection)
        1 * connectionService.disconnect("conn-1", 100L)
    }

    def "disconnect should not delete schedule when connection not found"() {
        when:
        service.disconnect("conn-999", 100L)

        then:
        1 * connectionService.findById("conn-999") >> null
        0 * scheduleManager.deleteSchedule(_)
        1 * connectionService.disconnect("conn-999", 100L)
    }

    def "updateConnectionStatus should enable connection and disable others in same category"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(IntegrationConnectionStatus.DISABLED)
            .category(IntegrationCategory.ACCOUNTING)
            .build()

        def otherConnection = IntegrationConnection.builder()
            .id("conn-2")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(IntegrationConnectionStatus.ENABLED)
            .category(IntegrationCategory.ACCOUNTING)
            .build()

        def updatedConnection = connection.toBuilder()
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        when:
        def result = service.updateConnectionStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED)

        then:
        1 * connectionService.findById("conn-1") >> connection
        1 * connectionService.listByCompany(100L) >> [connection, otherConnection]
        1 * connectionService.updateStatus("conn-2", 100L, IntegrationConnectionStatus.DISABLED)
        1 * scheduleManager.pauseSchedule(otherConnection)
        1 * connectionService.updateStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED) >> updatedConnection
        1 * scheduleManager.unpauseSchedule(connection)
        result.status == IntegrationConnectionStatus.ENABLED
    }

    def "updateConnectionStatus should disable connection and pause schedule"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .provider(IntegrationProvider.QUICKBOOKS)
            .status(IntegrationConnectionStatus.ENABLED)
            .category(IntegrationCategory.ACCOUNTING)
            .build()

        def updatedConnection = connection.toBuilder()
            .status(IntegrationConnectionStatus.DISABLED)
            .build()

        when:
        def result = service.updateConnectionStatus("conn-1", 100L, IntegrationConnectionStatus.DISABLED)

        then:
        1 * connectionService.findById("conn-1") >> connection
        0 * connectionService.listByCompany(_)
        1 * connectionService.updateStatus("conn-1", 100L, IntegrationConnectionStatus.DISABLED) >> updatedConnection
        1 * scheduleManager.pauseSchedule(connection)
        result.status == IntegrationConnectionStatus.DISABLED
    }

    def "updateConnectionStatus should throw exception for invalid connection"() {
        when:
        service.updateConnectionStatus("conn-999", 100L, IntegrationConnectionStatus.ENABLED)

        then:
        1 * connectionService.findById("conn-999") >> null
        thrown(IllegalArgumentException)
    }

    def "updateConnectionStatus should throw exception for wrong company"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(999L)
            .build()

        when:
        service.updateConnectionStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED)

        then:
        1 * connectionService.findById("conn-1") >> connection
        thrown(IllegalArgumentException)
    }

    def "updateConnectionStatus should throw exception for non-API status"() {
        given:
        def connection = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .build()

        when:
        service.updateConnectionStatus("conn-1", 100L, IntegrationConnectionStatus.EXPIRED)

        then:
        1 * connectionService.findById("conn-1") >> connection
        thrown(IllegalArgumentException)
    }

    def "updateConnectionStatus should not affect other categories when enabling"() {
        given:
        def accountingConn = IntegrationConnection.builder()
            .id("conn-1")
            .companyId(100L)
            .status(IntegrationConnectionStatus.DISABLED)
            .category(IntegrationCategory.ACCOUNTING)
            .build()

        // Connection with no category set (null) - should not be affected by enabling ACCOUNTING
        def otherConn = IntegrationConnection.builder()
            .id("conn-2")
            .companyId(100L)
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        def updatedConnection = accountingConn.toBuilder()
            .status(IntegrationConnectionStatus.ENABLED)
            .build()

        when:
        service.updateConnectionStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED)

        then:
        1 * connectionService.findById("conn-1") >> accountingConn
        1 * connectionService.listByCompany(100L) >> [accountingConn, otherConn]
        0 * connectionService.updateStatus("conn-2", _, _)
        1 * connectionService.updateStatus("conn-1", 100L, IntegrationConnectionStatus.ENABLED) >> updatedConnection
        1 * scheduleManager.unpauseSchedule(accountingConn)
    }
}
