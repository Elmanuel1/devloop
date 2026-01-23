package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.common.SyncStatusUpdate;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.service.ContactSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of CustomerDependencyPushService.
 * Auto-pushes customers (ship-to locations) to provider if they lack external IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerDependencyPushServiceImpl implements CustomerDependencyPushService {

    private final ContactSyncService contactSyncService;
    private final IntegrationProviderFactory providerFactory;

    @Override
    public DependencyPushResult ensureHaveExternalIds(
            IntegrationConnection connection,
            List<Party> customers) {

        // Filter customers needing push (those without externalId)
        List<Party> needsPush = customers.stream()
            .filter(customer -> customer.getExternalId() == null)
            .toList();

        if (needsPush.isEmpty()) {
            log.debug("All {} customers already have external IDs, skipping push", customers.size());
            return DependencyPushResult.success();
        }

        log.info("Auto-pushing {} customers (out of {}) to {} to obtain external IDs",
            needsPush.size(), customers.size(), connection.getProvider());

        // Get provider-specific push provider
        IntegrationPushProvider<Party> pushProvider = providerFactory
            .getPushProvider(connection.getProvider(), IntegrationEntityType.JOB_LOCATION)
            .map(provider -> (IntegrationPushProvider<Party>) provider)
            .orElseThrow(() -> new IllegalStateException(
                "No job location push provider found for " + connection.getProvider()));

        // Batch push customers
        List<DocumentSyncRequest<?>> requests = needsPush.stream()
            .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromVendor)
            .collect(Collectors.toList());

        try {
            Map<String, SyncResult> results = pushProvider.pushBatch(connection, requests);

            // Process results and prepare batch updates
            List<SyncStatusUpdate> batchUpdates = new ArrayList<>();

            for (Party customer : needsPush) {
                SyncResult result = results.get(customer.getId());

                if (result == null) {
                    String errorMsg = String.format(
                        "No result returned for customer %s (id=%s)",
                        customer.getName(), customer.getId());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }

                if (result.isSuccess()) {
                    // Add to batch updates
                    batchUpdates.add(new SyncStatusUpdate(
                        customer.getId(),
                        connection.getProvider().getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt()
                    ));

                    // Update in-memory object so enricher can use it immediately
                    customer.setProvider(connection.getProvider().getValue());
                    customer.setExternalId(result.getExternalId());
                    customer.setProviderVersion(result.getProviderVersion());
                    customer.setProviderLastUpdatedAt(result.getProviderLastUpdatedAt());

                    log.debug("Successfully pushed customer {} to {}, externalId={}",
                        customer.getName(), connection.getProvider(), result.getExternalId());
                } else {
                    String errorMsg = String.format(
                        "Failed to push customer %s (id=%s) to %s: %s",
                        customer.getName(), customer.getId(),
                        connection.getProvider(), result.getErrorMessage());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }
            }

            // Batch update all successful syncs
            contactSyncService.batchUpdateSyncStatus(batchUpdates);
        } catch (Exception e) {
            String errorMsg = String.format(
                "Exception during batch push of %d customers to %s: %s",
                needsPush.size(), connection.getProvider(), e.getMessage());
            log.error(errorMsg, e);
            return DependencyPushResult.failure(errorMsg);
        }

        log.info("Successfully auto-pushed {} customers to {}",
            needsPush.size(), connection.getProvider());
        return DependencyPushResult.success();
    }
}
