package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.PurchaseOrderLineItemResolver;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPullProvider;
import com.tosspaper.integrations.repository.IntegrationConnectionRepository;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.PaymentTerm;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationAccount;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.Item;
import com.tosspaper.models.domain.integration.Preferences;
import com.tosspaper.models.service.CompanySyncService;
import com.tosspaper.models.service.ContactSyncService;
import com.tosspaper.models.service.IntegrationAccountService;
import com.tosspaper.models.service.ItemService;
import com.tosspaper.models.service.PaymentTermService;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(workers = "integration-sync-worker")
public class IntegrationPullActivitiesImpl implements IntegrationPullActivities {

    private final IntegrationConnectionService connectionService;
    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationProviderFactory providerFactory;
    private final ContactSyncService contactSyncService;
    private final IntegrationAccountService integrationAccountService;
    private final ItemService itemService;
    private final PaymentTermService paymentTermService;
    private final PurchaseOrderSyncService purchaseOrderSyncService;
    private final CompanySyncService companySyncService;
    private final PurchaseOrderLineItemResolver lineItemResolver;
    
    /**
     * Get IntegrationConnection with fresh tokens for API calls.
     * Re-fetches from database to ensure tokens are current and not from Temporal history.
     */
    private IntegrationConnection getConnectionWithTokens(SyncConnectionData connectionData) {
        IntegrationConnection connection = connectionService.findById(connectionData.getId());
        if (connection == null || !connection.isEnabled()) {
            throw new IntegrationException("Integration connection not found or not enabled: " + connectionData.getId());
        }
        return connectionService.ensureActiveToken(connection);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> pullFromProvider(SyncConnectionData connection, IntegrationEntityType entityType) {
        IntegrationConnection apiConnection = getConnectionWithTokens(connection);
        IntegrationPullProvider<T> provider = (IntegrationPullProvider<T>) providerFactory
            .getPullProvider(connection.getProvider(), entityType)
            .orElseThrow(() -> new IntegrationException(
                "Pull provider not found for " + connection.getProvider() + "/" + entityType.getValue()));

        if (!provider.isEnabled()) {
            log.warn("Pull provider disabled for {}/{}", connection.getProvider(), entityType);
            return List.of();
        }

        return provider.pullBatch(apiConnection);
    }

    @Override
    public List<Party> fetchVendorsSinceLastSync(SyncConnectionData connection) {
        return pullFromProvider(connection, IntegrationEntityType.VENDOR);
    }
    
    @Override
    public void storeVendorsInContacts(SyncConnectionData connection, List<Party> vendors) {
        contactSyncService.upsertFromProvider(connection.getCompanyId(), vendors);
    }
    
    @Override
    public List<IntegrationAccount> fetchAccountsSinceLastSync(SyncConnectionData connection) {
        return pullFromProvider(connection, IntegrationEntityType.ACCOUNT);
    }
    
    @Override
    public void storeAccounts(SyncConnectionData connection, List<IntegrationAccount> accounts) {
        integrationAccountService.upsert(connection.getId(), accounts);
    }
    
    @Override
    public List<PaymentTerm> fetchPaymentTermsSinceLastSync(SyncConnectionData connection) {
        return pullFromProvider(connection, IntegrationEntityType.PAYMENT_TERM);
    }
    
    @Override
    public void storePaymentTerms(SyncConnectionData connection, List<PaymentTerm> terms) {
        paymentTermService.upsertFromProvider(connection.getCompanyId(), connection.getProvider().name(), terms);
    }
    
    @Override
    public List<Item> fetchItemsSinceLastSync(SyncConnectionData connection) {
        return pullFromProvider(connection, IntegrationEntityType.ITEM);
    }
    
    @Override
    public void storeItems(SyncConnectionData connection, List<Item> items) {
        itemService.upsertFromProvider(connection.getCompanyId(), connection.getId(), items);
    }
    
    @Override
    public List<PurchaseOrder> fetchPurchaseOrdersSinceLastSync(SyncConnectionData connection) {
        return pullFromProvider(connection, IntegrationEntityType.PURCHASE_ORDER);
    }
    
    @Override
    public void storePurchaseOrders(SyncConnectionData connection, List<PurchaseOrder> purchaseOrders) {
        // Resolve itemIds and accountIds from external refs before saving
        lineItemResolver.resolveLineItemReferences(connection.getId(), purchaseOrders);
        purchaseOrderSyncService.upsertFromProvider(connection.getCompanyId(), purchaseOrders);
    }
    
    @Override
    public SyncConnectionData updateLastSyncAt(String connectionId, OffsetDateTime timestamp) {
        var updatedConnection = connectionRepository.updateLastSyncAt(connectionId, timestamp);
        return SyncConnectionData.from(updatedConnection);
    }
    
    @Override
    public OffsetDateTime getCurrentTime() {
        return OffsetDateTime.now();
    }

    @Override
    public SyncConnectionData getConnection(String connectionId) {
        IntegrationConnection connection = connectionService.findById(connectionId);
        if (connection == null || !connection.isEnabled()) {
            throw new com.tosspaper.integrations.common.exception.IntegrationException(
                    "Integration connection not found or not enabled: " + connectionId);
        }
        IntegrationConnection activeConnection = connectionService.ensureActiveToken(connection);
        return SyncConnectionData.from(activeConnection);
    }

    @Override
    public void syncPreferences(SyncConnectionData connection) {
        try {
            List<Preferences> prefsList = pullFromProvider(connection, IntegrationEntityType.PREFERENCES);

            if (prefsList != null && !prefsList.isEmpty()) {
                Preferences prefs = prefsList.getFirst();
                connectionRepository.updatePreferences(connection.getId(), prefs);
                log.info("Updated preferences for connection {}: defaultCurrency={}, multicurrencyEnabled={}", 
                        connection.getId(), 
                        prefs.getDefaultCurrency() != null ? prefs.getDefaultCurrency().getCode() : null,
                        prefs.getMulticurrencyEnabled());
                
                // Sync company currency and multicurrency settings with integration preferences
                companySyncService.updateCurrencyFromIntegration(
                        connection.getCompanyId(), 
                        prefs.getDefaultCurrency(), 
                        prefs.getMulticurrencyEnabled());
            } else {
                log.debug("No Preferences found for connection: {}", connection.getId());
            }
        } catch (Exception e) {
            log.debug("Preferences sync not supported or failed for provider {}: {}", 
                    connection.getProvider(), e.getMessage());
            // Don't fail the entire pull workflow if Preferences sync fails
        }
    }
}
