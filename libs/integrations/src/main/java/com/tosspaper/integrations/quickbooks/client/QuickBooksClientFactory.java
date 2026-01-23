package com.tosspaper.integrations.quickbooks.client;

import com.intuit.ipp.core.Context;
import com.intuit.ipp.core.ServiceType;
import com.intuit.ipp.security.OAuth2Authorizer;
import com.intuit.ipp.services.DataService;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Factory for creating authenticated QuickBooks DataService clients.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuickBooksClientFactory {

    private final QuickBooksProperties properties;

    /**
     * Create a DataService instance for the given connection.
     *
     * @param connection the active integration connection
     * @return configured DataService
     */
    public DataService createDataService(IntegrationConnection connection) {
        return createDataService(connection.getAccessToken(), connection.getRealmId());
    }

    /**
     * Create a DataService instance with the given credentials.
     * Useful during OAuth callback before connection is created.
     *
     * @param accessToken OAuth access token
     * @param realmId     QuickBooks company/realm ID
     * @return configured DataService
     */
    @SneakyThrows
    public DataService createDataService(String accessToken, String realmId) {
        // Create OAuth2 authorizer
        OAuth2Authorizer oauth = new OAuth2Authorizer(accessToken);

        // Configure base URL from properties (supports sandbox/production)
        com.intuit.ipp.util.Config.setProperty(com.intuit.ipp.util.Config.BASE_URL_QBO, properties.getApiBaseUrl());

        // Create context with OAuth, service type, and realm ID
        Context context = new Context(oauth, ServiceType.QBO, realmId);

        // Create DataService
        return new DataService(context);
    }
}

