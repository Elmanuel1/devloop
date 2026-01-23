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
 * Implementation of VendorDependencyPushService.
 * Auto-pushes vendors to provider if they lack external IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorDependencyPushServiceImpl implements VendorDependencyPushService {

    private final ContactSyncService contactSyncService;
    private final IntegrationProviderFactory providerFactory;

    @Override
    public DependencyPushResult ensureHaveExternalIds(
            IntegrationConnection connection,
            List<Party> vendors) {

        // Filter vendors needing push (those without externalId)
        List<Party> needsPush = vendors.stream()
            .filter(vendor -> vendor.getExternalId() == null)
            .toList();

        if (needsPush.isEmpty()) {
            log.debug("All {} vendors already have external IDs, skipping push", vendors.size());
            return DependencyPushResult.success();
        }

        log.info("Auto-pushing {} vendors (out of {}) to {} to obtain external IDs",
            needsPush.size(), vendors.size(), connection.getProvider());

        // Get provider-specific push provider
        IntegrationPushProvider<Party> pushProvider = providerFactory
            .getPushProvider(connection.getProvider(), IntegrationEntityType.VENDOR)
            .map(provider -> (IntegrationPushProvider<Party>) provider)
            .orElseThrow(() -> new IllegalStateException(
                "No vendor push provider found for " + connection.getProvider()));

        // Batch push vendors
        List<DocumentSyncRequest<?>> requests = needsPush.stream()
            .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromVendor)
            .collect(Collectors.toList());

        try {
            Map<String, SyncResult> results = pushProvider.pushBatch(connection, requests);

            // Process results and prepare batch updates
            List<SyncStatusUpdate> batchUpdates = new ArrayList<>();

            for (Party vendor : needsPush) {
                SyncResult result = results.get(vendor.getId());

                if (result == null) {
                    String errorMsg = String.format(
                        "No result returned for vendor %s (id=%s)",
                        vendor.getName(), vendor.getId());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }

                if (result.isSuccess()) {
                    // Add to batch updates
                    batchUpdates.add(new SyncStatusUpdate(
                        vendor.getId(),
                        connection.getProvider().getValue(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt()
                    ));

                    // Update in-memory object so enricher can use it immediately
                    vendor.setProvider(connection.getProvider().getValue());
                    vendor.setExternalId(result.getExternalId());
                    vendor.setProviderVersion(result.getProviderVersion());
                    vendor.setProviderLastUpdatedAt(result.getProviderLastUpdatedAt());

                    log.debug("Successfully pushed vendor {} to {}, externalId={}",
                        vendor.getName(), connection.getProvider(), result.getExternalId());
                } else {
                    String errorMsg = String.format(
                        "Failed to push vendor %s (id=%s) to %s: %s",
                        vendor.getName(), vendor.getId(),
                        connection.getProvider(), result.getErrorMessage());
                    log.error(errorMsg);

                    // If non-retryable (conflict/duplicate name), mark vendor as permanently failed
                    if (!result.isRetryable()) {
                        try {
                            contactSyncService.markAsPermanentlyFailed(vendor.getId(), result.getErrorMessage());
                            log.warn("Vendor {} marked as permanently failed (non-retryable): {}",
                                    vendor.getId(), result.getErrorMessage());
                        } catch (Exception e) {
                            log.error("Failed to mark vendor {} as permanently failed", vendor.getId(), e);
                        }
                    } else {
                        // Retryable error: increment retry count
                        try {
                            contactSyncService.incrementRetryCount(vendor.getId(), result.getErrorMessage());
                            log.warn("Vendor {} push failed, retry count incremented: {}",
                                    vendor.getId(), result.getErrorMessage());
                        } catch (Exception e) {
                            log.error("Failed to increment retry count for vendor {}", vendor.getId(), e);
                        }
                    }

                    return DependencyPushResult.failure(errorMsg);
                }
            }

            // Batch update all successful syncs
            if (!batchUpdates.isEmpty()) {
                contactSyncService.batchUpdateSyncStatus(batchUpdates);
            }
        } catch (Exception e) {
            String errorMsg = String.format(
                "Exception during batch push of %d vendors to %s: %s",
                needsPush.size(), connection.getProvider(), e.getMessage());
            log.error(errorMsg, e);
            return DependencyPushResult.failure(errorMsg);
        }

        log.info("Successfully auto-pushed {} vendors to {}",
            needsPush.size(), connection.getProvider());
        return DependencyPushResult.success();
    }
}
