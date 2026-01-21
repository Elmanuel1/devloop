package com.tosspaper.integrations.api;

import com.tosspaper.generated.api.IntegrationsApi;
import com.tosspaper.generated.model.IntegrationAuthUrl;
import com.tosspaper.generated.model.IntegrationConnection;
import com.tosspaper.generated.model.IntegrationPreferences;
import com.tosspaper.generated.model.IntegrationProvider;
import com.tosspaper.generated.model.IntegrationSettings;
import com.tosspaper.generated.model.IntegrationSettingsUpdate;
import com.tosspaper.generated.model.UpdateIntegrationConnectionRequest;
import com.tosspaper.models.domain.integration.IntegrationConnectionStatus;
import com.tosspaper.models.service.IntegrationsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for integration endpoints.
 * Delegates all business logic to IntegrationsService.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class IntegrationsController implements IntegrationsApi {

    private final IntegrationsService integrationsService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:view')")
    public ResponseEntity<IntegrationSettings> getIntegrationSettings(String xContextId, Long companyId) {
        var settings = integrationsService.getSettings(companyId);
        return ResponseEntity.ok(toGeneratedSettings(settings));
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:edit')")
    public ResponseEntity<IntegrationSettings> updateIntegrationSettings(
            String xContextId,
            Long companyId,
            IntegrationSettingsUpdate request) {

        var update = new IntegrationsService.IntegrationSettingsUpdate(
                request.getCurrency(),
                request.getAutoApprovalEnabled(),
                request.getAutoApprovalThreshold()
        );

        var settings = integrationsService.updateSettings(companyId, update);
        return ResponseEntity.ok(toGeneratedSettings(settings));
    }

    @Override
    public ResponseEntity<List<IntegrationProvider>> getIntegrationProviders() {
        var providers = integrationsService.getProviders();
        List<IntegrationProvider> response = providers.stream()
                .map(p -> {
                    IntegrationProvider provider = new IntegrationProvider();
                    provider.setId(p.id());
                    provider.setDisplayName(p.displayName());
                    provider.setCategory(p.category());
                    return provider;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:edit')")
    public ResponseEntity<IntegrationAuthUrl> createAuthUrl(String xContextId, String providerId) {
        Long companyId = Long.parseLong(xContextId);
        var authUrl = integrationsService.getAuthUrl(companyId, providerId);

        IntegrationAuthUrl response = new IntegrationAuthUrl();
        response.setAuthUrl(URI.create(authUrl.authUrl()));
        response.setState(authUrl.state());

        return ResponseEntity.ok(response);
    }

    /**
     * OAuth callback handler for all integration providers.
     * This endpoint is called directly by the OAuth provider (not the frontend).
     * Handles both success (code present) and denial (error present) cases.
     * Redirects to frontend with appropriate query parameters.
     */
    @GetMapping("/v1/integrations/{providerId}/callback")
    public RedirectView handleOAuthCallback(
            @PathVariable String providerId,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String error,
            @RequestParam String state,
            @RequestParam(required = false) String realmId) {
        
        // User denied authorization or other OAuth error occurred
        if (error != null) {
            String redirectUrl = integrationsService.handleCallbackError(error, state, providerId);
            return new RedirectView(redirectUrl);
        }
        
        // Invalid callback - neither code nor error present
        if (code == null) {
            log.warn("OAuth callback received with neither code nor error for provider {}", providerId);
            String redirectUrl = integrationsService.handleCallbackError("invalid_callback", state, providerId);
            return new RedirectView(redirectUrl);
        }
        
        // Success - process the authorization code
        String redirectUrl = integrationsService.handleCallback(code, state, realmId, providerId);
        return new RedirectView(redirectUrl);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:edit')")
    public ResponseEntity<IntegrationConnection> updateIntegrationConnection(
            String xContextId, String connectionId, UpdateIntegrationConnectionRequest request) {
        Long companyId = Long.parseLong(xContextId);
        
        // Only allow updating status to enabled/disabled
        if (request.getStatus() == null) {
            return ResponseEntity.badRequest().build();
        }
        
        IntegrationConnectionStatus newStatus = IntegrationConnectionStatus.fromValue(request.getStatus().getValue());
        if (newStatus != IntegrationConnectionStatus.ENABLED && newStatus != IntegrationConnectionStatus.DISABLED) {
            return ResponseEntity.badRequest().build();
        }
        
        // Update connection status (this will trigger schedule create/delete)
        var connection = integrationsService.updateConnectionStatus(connectionId, companyId, newStatus);
        return ResponseEntity.ok(toGeneratedModel(connection));
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:view')")
    public ResponseEntity<List<IntegrationConnection>> listIntegrationConnections(String xContextId) {
        Long companyId = Long.parseLong(xContextId);
        var connections = integrationsService.listConnections(companyId);

        List<IntegrationConnection> response = connections.stream()
                .map(this::toGeneratedModel)
                .toList();

        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'integrations:edit')")
    public ResponseEntity<Void> disconnectIntegration(String xContextId, String connectionId) {
        Long companyId = Long.parseLong(xContextId);
        integrationsService.disconnect(connectionId, companyId);
        return ResponseEntity.noContent().build();
    }

    private IntegrationSettings toGeneratedSettings(IntegrationsService.IntegrationSettings settings) {
        IntegrationSettings generated = new IntegrationSettings();
        generated.setCurrency(settings.currency());
        generated.setAutoApprovalEnabled(settings.autoApprovalEnabled());
        generated.setAutoApprovalThreshold(settings.autoApprovalThreshold());
        return generated;
    }

    private IntegrationConnection toGeneratedModel(
            com.tosspaper.models.domain.integration.IntegrationConnection connection) {
        IntegrationConnection model = new IntegrationConnection();
        model.setId(connection.getId());
        model.setProvider(IntegrationConnection.ProviderEnum.fromValue(connection.getProvider().name()));
        model.setStatus(IntegrationConnection.StatusEnum.fromValue(connection.getStatus().getValue()));
        model.setExternalCompanyName(connection.getExternalCompanyName());
        model.setRealmId(connection.getRealmId());
        model.setLastSyncAt(connection.getLastSyncAt());
        model.setConnectedAt(connection.getCreatedAt());
        
        // Map preferences if available
        if (connection.getDefaultCurrency() != null || connection.getMulticurrencyEnabled() != null) {
            IntegrationPreferences preferences = new IntegrationPreferences();
            if (connection.getDefaultCurrency() != null) {
                preferences.setDefaultCurrency(connection.getDefaultCurrency().getCode());
            }
            if (connection.getMulticurrencyEnabled() != null) {
                preferences.setMulticurrencyEnabled(connection.getMulticurrencyEnabled());
            }
            model.setPreferences(preferences);
        }
        
        return model;
    }
}
