package com.tosspaper.integrations.service;

import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.oauth.OAuthStateService;
import com.tosspaper.integrations.provider.IntegrationCompanyInfoProvider;
import com.tosspaper.integrations.provider.IntegrationOAuthProvider;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.temporal.IntegrationScheduleManager;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import com.tosspaper.models.service.CompanyLookupService;
import com.tosspaper.models.service.IntegrationsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.tosspaper.models.jooq.Tables.COMPANIES;

/**
 * Implementation of integrations service.
 * Handles all business logic for integration settings and connections.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationsServiceImpl implements IntegrationsService {

    private final DSLContext dsl;
    private final IntegrationProviderFactory providerFactory;
    private final OAuthStateService oauthStateService;
    private final IntegrationConnectionService connectionService;
    private final CompanyLookupService companyLookupService;
    private final IntegrationScheduleManager scheduleManager;

    @Value("${app.frontend.integrations-redirect-url}")
    private String integrationsRedirectUrl;

    @Override
    public IntegrationSettings getSettings(Long companyId) {
        CompanyLookupService.AutoApprovalSettings settings = companyLookupService.getAutoApprovalSettings(companyId);
        return new IntegrationSettings(
                settings.currency(),
                settings.enabled(),
                settings.threshold()
        );
    }

    @Override
    public IntegrationSettings updateSettings(Long companyId, IntegrationSettingsUpdate update) {
        CompaniesRecord company = dsl.selectFrom(COMPANIES)
                .where(COMPANIES.ID.eq(companyId))
                .fetchOne();

        if (company == null) {
            throw new IllegalArgumentException("Company not found: " + companyId);
        }

        if (update.currency() != null) {
            company.setCurrency(update.currency());
        }
        if (update.autoApprovalEnabled() != null) {
            company.setAutoApprovalEnabled(update.autoApprovalEnabled());
        }
        if (update.autoApprovalThreshold() != null) {
            company.setAutoApprovalThreshold(update.autoApprovalThreshold());
        }

        company.update();

        return new IntegrationSettings(
                company.getCurrency(),
                Boolean.TRUE.equals(company.getAutoApprovalEnabled()),
                company.getAutoApprovalThreshold()
        );
    }

    @Override
    public List<ProviderInfo> getProviders() {
        return providerFactory.getAllProviders().stream()
                .map(p -> new ProviderInfo(p.id(), p.displayName(), p.category()))
                .collect(Collectors.toList());
    }

    @Override
    public OAuthAuthUrl getAuthUrl(Long companyId, String providerId) {
        IntegrationProvider providerEnum = IntegrationProvider.fromValue(providerId);
        IntegrationOAuthProvider provider = providerFactory.getOAuthProvider(providerEnum);
        String state = UUID.randomUUID().toString();
        oauthStateService.storeState(state, companyId, providerId);
        String url = provider.buildAuthorizationUrl(state);

        return new OAuthAuthUrl(url, state);
    }

    @Override
    public String handleCallback(String code, String state, String realmId, String providerId) {
        try {
            // Validate state and get company ID + provider ID
            OAuthStateService.StateData stateData = oauthStateService.validateAndConsumeState(state);
            Long companyId = stateData.companyId();
            String stateProviderId = stateData.providerId();
            IntegrationProvider providerEnum = IntegrationProvider.fromValue(stateProviderId);

            // Get the correct OAuth provider
            IntegrationOAuthProvider provider = providerFactory.getOAuthProvider(providerEnum);

            // Exchange code for tokens
            var tokens = provider.exchangeCodeForTokens(code, realmId, companyId);

            // Fetch company info from provider
            String effectiveRealmId = realmId != null ? realmId : tokens.providerCompanyId();
            IntegrationCompanyInfoProvider companyInfoProvider = providerFactory.getCompanyInfoProvider(providerEnum)
                    .orElseThrow(() -> new IntegrationException("No company info provider for: " + providerEnum));
            IntegrationCompanyInfoProvider.CompanyInfo companyInfo = companyInfoProvider.fetchCompanyInfo(
                    tokens.accessToken(), effectiveRealmId);

            // Create connection with disabled status (user must enable sync)
            // Set category from provider
            com.tosspaper.models.domain.integration.IntegrationCategory category = providerEnum.getCategory();
            
            IntegrationConnection connection = IntegrationConnection.builder()
                    .companyId(companyId)
                    .provider(providerEnum)
                    .status(IntegrationConnectionStatus.DISABLED)
                    .realmId(effectiveRealmId)
                    .externalCompanyId(companyInfo.companyId())
                    .externalCompanyName(companyInfo.companyName())
                    .defaultCurrency(companyInfo.defaultCurrency())
                    .category(category)
                    .accessToken(tokens.accessToken())
                    .refreshToken(tokens.refreshToken())
                    .expiresAt(tokens.expiresAt())
                    .refreshTokenExpiresAt(tokens.refreshTokenExpiresAt())
                    .build();

            connectionService.create(connection);

            log.info("{} connection created for company {} with realm {} (external: {})",
                    provider.getDisplayName(), companyId, effectiveRealmId, companyInfo.companyName());
            
            return "%s&status=success&provider=%s"
                    .formatted(integrationsRedirectUrl, providerId);
                    
        } catch (com.tosspaper.integrations.common.exception.IntegrationAuthException e) {
            log.error("OAuth authentication error for provider {}", providerId, e);
            String errorCode = e.getErrorCode() != null ? e.getErrorCode() : "auth_failed";
            String encodedError = URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
            return "%s&status=error&provider=%s&error=%s"
                    .formatted(integrationsRedirectUrl, providerId, encodedError);
        } catch (Exception e) {
            log.error("Failed to process OAuth callback for provider {}", providerId, e);
            String encodedError = URLEncoder.encode("connection_failed", StandardCharsets.UTF_8);
            return "%s&status=error&provider=%s&error=%s"
                    .formatted(integrationsRedirectUrl, providerId, encodedError);
        }
    }

    @Override
    public String handleCallbackError(String error, String state, String providerId) {
        try {
            // Validate state and get company ID + provider ID
            OAuthStateService.StateData stateData = oauthStateService.validateAndConsumeState(state);
            Long companyId = stateData.companyId();
            String stateProviderId = stateData.providerId();
            
            log.info("OAuth authorization failed for provider {} and company {}: error={}", 
                    stateProviderId, companyId, error);
            
        } catch (Exception e) {
            log.error("Failed to process OAuth error callback for provider {}", providerId, e);
        }
        
        // Return error redirect URL
        String encodedError = URLEncoder.encode(error, StandardCharsets.UTF_8);
        return "%s&status=error&provider=%s&error=%s"
                .formatted(integrationsRedirectUrl, providerId, encodedError);
    }

    @Override
    public List<IntegrationConnection> listConnections(Long companyId) {
        return connectionService.listByCompany(companyId);
    }

    @Override
    public void disconnect(String connectionId, Long companyId) {
        // Get connection first to build schedule ID
        IntegrationConnection conn = connectionService.findById(connectionId);
        if (conn != null && conn.getCompanyId().equals(companyId)) {
            // Delete schedule before disconnecting
            scheduleManager.deleteSchedule(conn);
        }
        connectionService.disconnect(connectionId, companyId);
    }

    @Override
    public IntegrationConnection updateConnectionStatus(String connectionId, Long companyId, IntegrationConnectionStatus status) {
        IntegrationConnection conn = connectionService.findById(connectionId);

        // Ensure connection exists
        if (conn == null) {
            throw new IllegalArgumentException("Connection not found: " + connectionId);
        }

        // Verify company ownership
        if (!conn.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("Connection does not belong to company: " + companyId);
        }

        // Only allow ENABLED and DISABLED via API
        if (status != IntegrationConnectionStatus.ENABLED && status != IntegrationConnectionStatus.DISABLED) {
            throw new IllegalArgumentException("Only 'enabled' and 'disabled' status values are allowed via API");
        }

        IntegrationConnectionStatus oldStatus = conn.getStatus();
        
        // If enabling, disable all other connections in the same category
        if (status == IntegrationConnectionStatus.ENABLED && oldStatus != IntegrationConnectionStatus.ENABLED) {
            com.tosspaper.models.domain.integration.IntegrationCategory category = conn.getCategory();
            if (category != null) {
                List<IntegrationConnection> otherConnections = connectionService.listByCompany(companyId).stream()
                        .filter(c -> !c.getId().equals(connectionId))
                        .filter(c -> category.equals(c.getCategory()))
                        .filter(c -> c.getStatus() == IntegrationConnectionStatus.ENABLED)
                        .toList();
                
                for (IntegrationConnection otherConn : otherConnections) {
                    connectionService.updateStatus(otherConn.getId(), companyId, IntegrationConnectionStatus.DISABLED);
                    scheduleManager.pauseSchedule(otherConn);
                    log.info("Disabled other connection in same category: id={}, category={}", otherConn.getId(), category);
                }
            }
        }
        
        // Update status in DB
        IntegrationConnection updated = connectionService.updateStatus(connectionId, companyId, status);

        // Manage Temporal schedule
        if (status == IntegrationConnectionStatus.ENABLED && oldStatus != IntegrationConnectionStatus.ENABLED) {
            // Enable sync - unpause schedule (or create if doesn't exist)
            scheduleManager.unpauseSchedule(conn);
            log.info("Enabled sync for connection: id={}", connectionId);
        } else if (status == IntegrationConnectionStatus.DISABLED && oldStatus == IntegrationConnectionStatus.ENABLED) {
            // Disable sync - pause schedule (preserve history)
            scheduleManager.pauseSchedule(conn);
            log.info("Disabled sync for connection: id={}", connectionId);
        }

        return updated;
    }
}
