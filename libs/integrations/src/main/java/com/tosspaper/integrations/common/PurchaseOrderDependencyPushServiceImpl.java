package com.tosspaper.integrations.common;

import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of PurchaseOrderDependencyPushService.
 * Auto-pushes purchase orders to provider if they lack external IDs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseOrderDependencyPushServiceImpl implements PurchaseOrderDependencyPushService {

    private final PurchaseOrderSyncService purchaseOrderSyncService;
    private final IntegrationProviderFactory providerFactory;

    @Override
    public DependencyPushResult ensureHaveExternalIds(
            IntegrationConnection connection,
            List<PurchaseOrder> purchaseOrders) {

        // Filter purchase orders needing push (those without externalId)
        List<PurchaseOrder> needsPush = purchaseOrders.stream()
                .filter(po -> po.getExternalId() == null)
                .toList();

        if (needsPush.isEmpty()) {
            log.debug("All {} purchase orders already have external IDs, skipping push", purchaseOrders.size());
            return DependencyPushResult.success();
        }

        log.info("Auto-pushing {} purchase orders (out of {}) to {} to obtain external IDs",
                needsPush.size(), purchaseOrders.size(), connection.getProvider());

        // Get provider-specific push provider
        IntegrationPushProvider<PurchaseOrder> pushProvider = providerFactory
                .getPushProvider(connection.getProvider(), IntegrationEntityType.PURCHASE_ORDER)
                .map(provider -> (IntegrationPushProvider<PurchaseOrder>) provider)
                .orElseThrow(() -> new IllegalStateException(
                        "No purchase order push provider found for " + connection.getProvider()));

        // Batch push purchase orders
        List<DocumentSyncRequest<?>> requests = needsPush.stream()
                .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromPurchaseOrder)
                .collect(Collectors.toList());

        try {
            Map<String, SyncResult> results = pushProvider.pushBatch(connection, requests);

            for (PurchaseOrder po : needsPush) {
                SyncResult result = results.get(po.getId());

                if (result == null) {
                    String errorMsg = String.format(
                            "No result returned for purchase order %s (id=%s)",
                            po.getDisplayId(), po.getId());
                    log.error(errorMsg);
                    return DependencyPushResult.failure(errorMsg);
                }

                if (!result.isSuccess()) {
                    String errorMsg = String.format(
                            "Failed to push purchase order %s (id=%s) to %s: %s",
                            po.getDisplayId(), po.getId(),
                            connection.getProvider(), result.getErrorMessage());
                    log.error(errorMsg);

                    // If non-retryable, mark as permanently failed
                    if (!result.isRetryable()) {
                        try {
                            purchaseOrderSyncService.markAsPermanentlyFailed(po.getId(), result.getErrorMessage());
                            log.warn("Purchase order {} marked as permanently failed (non-retryable): {}",
                                    po.getDisplayId(), result.getErrorMessage());
                        } catch (Exception e) {
                            log.error("Failed to mark purchase order {} as permanently failed", po.getDisplayId(), e);
                        }
                    } else {
                        // Retryable error: increment retry count
                        try {
                            purchaseOrderSyncService.incrementRetryCount(po.getId(), result.getErrorMessage());
                            log.warn("Purchase order {} push failed, retry count incremented: {}",
                                    po.getId(), result.getErrorMessage());
                        } catch (Exception e) {
                            log.error("Failed to increment retry count for purchase order {}", po.getId(), e);
                        }
                    }

                    return DependencyPushResult.failure(errorMsg);
                }

                // Update sync status in DB
                purchaseOrderSyncService.updateSyncStatus(
                        po.getId(),
                        result.getExternalId(),
                        result.getProviderVersion(),
                        result.getProviderLastUpdatedAt());

                // Update in-memory object so enricher can use it immediately
                po.setExternalId(result.getExternalId());
                po.setProviderVersion(result.getProviderVersion());
                po.setProviderLastUpdatedAt(result.getProviderLastUpdatedAt());

                log.debug("Successfully pushed purchase order {} to {}, externalId={}",
                        po.getDisplayId(), connection.getProvider(), result.getExternalId());
            }
        } catch (Exception e) {
            String errorMsg = String.format(
                    "Exception during batch push of %d purchase orders to %s: %s",
                    needsPush.size(), connection.getProvider(), e.getMessage());
            log.error(errorMsg, e);
            return DependencyPushResult.failure(errorMsg);
        }

        log.info("Successfully auto-pushed {} purchase orders to {}",
                needsPush.size(), connection.getProvider());
        return DependencyPushResult.success();
    }
}
