package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.IntegrationPushCoordinator;
import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.service.PurchaseOrderSyncService;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ActivityImpl(taskQueues = "integration-sync")
@RequiredArgsConstructor
public class POPushActivitiesImpl implements POPushActivities {

    private final IntegrationConnectionService connectionService;
    private final PurchaseOrderSyncService purchaseOrderSyncService;
    private final IntegrationPushCoordinator pushCoordinator;
    private final com.tosspaper.integrations.config.PushRetryConfig pushRetryConfig;

    @Override
    public SyncConnectionData getConnection(String connectionId) {
        IntegrationConnection connection = connectionService.findById(connectionId);
        if (connection == null || !connection.isEnabled()) {
            throw new IntegrationException("Integration connection not found or not enabled: " + connectionId);
        }
        IntegrationConnection activeConnection = connectionService.ensureActiveToken(connection);
        return SyncConnectionData.from(activeConnection);
    }

    @Override
    public List<PurchaseOrder> fetchPOsNeedingPush(SyncConnectionData connection, int limit) {
        log.debug("Fetching POs needing push: companyId={}, limit={}, maxRetries={}",
                connection.getCompanyId(), limit, pushRetryConfig.getMaxAttempts());
        List<PurchaseOrder> pos = purchaseOrderSyncService.findNeedingPush(
                connection.getCompanyId(), limit, pushRetryConfig.getMaxAttempts());
        if (pos.isEmpty()) {
            log.debug("Found {} POs needing push", pos.size());
        } else {
            log.info("Found {} POs needing push", pos.size());
        }
        return pos;
    }

    @Override
    public Map<String, SyncResult> pushPOs(SyncConnectionData connection, List<PurchaseOrder> pos) {
        if (pos.isEmpty()) {
            return Map.of();
        }

        log.info("Pushing {} POs to provider: {}", pos.size(), connection.getProvider());

        // Fetch fresh connection with tokens (not from Temporal history)
        IntegrationConnection integrationConnection = getConnectionWithTokens(connection);

        // Coordinator handles dependency resolution + push (IDs extracted automatically)
        Map<String, SyncResult> results = pushCoordinator.pushBatchWithDependencies(
                integrationConnection,
                IntegrationEntityType.PURCHASE_ORDER,
                pos
        );

        long successfulCount = results.values().stream().filter(SyncResult::isSuccess).count();
        long failedCount = results.size() - successfulCount;

        log.info("Pushed {} POs with {} successful and {} failed",
                pos.size(), successfulCount, failedCount);

        // Log details of failed pushes
        if (failedCount > 0) {
            results.entrySet().stream()
                    .filter(entry -> !entry.getValue().isSuccess())
                    .forEach(entry -> {
                        SyncResult result = entry.getValue();
                        log.warn("PO {} push failed: {} (retryable: {})",
                                entry.getKey(),
                                result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error",
                                result.isRetryable());
                    });
        }

        return results;
    }

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

    @Override
    public int markPOsAsPushed(Map<String, SyncResult> results) {
        int markedCount = 0;

        for (Map.Entry<String, SyncResult> entry : results.entrySet()) {
            String poId = entry.getKey();
            SyncResult result = entry.getValue();

            // Early exit: Success case
            if (result.isSuccess()) {
                try {
                    purchaseOrderSyncService.updateSyncStatus(
                            poId,
                            result.getExternalId(),
                            result.getProviderVersion(),
                            result.getProviderLastUpdatedAt()
                    );
                    markedCount++;
                } catch (Exception e) {
                    log.error("Failed to mark PO {} as pushed", poId, e);
                }
                continue;
            }

            // Early exit: Non-retryable failures (conflicts, duplicate names)
            if (!result.isRetryable()) {
                try {
                    purchaseOrderSyncService.markAsPermanentlyFailed(poId, result.getErrorMessage());
                    log.warn("PO {} marked as permanently failed (non-retryable): {}",
                            poId, result.getErrorMessage());
                } catch (Exception e) {
                    log.error("Failed to mark PO {} as permanently failed", poId, e);
                }
                continue;
            }

            // Retryable errors: increment retry count
            try {
                purchaseOrderSyncService.incrementRetryCount(poId, result.getErrorMessage());

                // Check if exceeded max retries
                PurchaseOrder po = purchaseOrderSyncService.findById(poId);
                if (po != null && po.getPushRetryCount() != null) {
                    log.warn("PO {} push failed (attempt {}/{}): {}",
                            poId, po.getPushRetryCount(), pushRetryConfig.getMaxAttempts(),
                            result.getErrorMessage());

                    if (po.getPushRetryCount() >= pushRetryConfig.getMaxAttempts()) {
                        purchaseOrderSyncService.markAsPermanentlyFailed(
                                poId,
                                String.format("Exceeded max retries (%d). Last error: %s",
                                        pushRetryConfig.getMaxAttempts(),
                                        result.getErrorMessage()));
                        log.warn("PO {} exceeded max retries and marked permanently failed", poId);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to increment retry count for PO {}", poId, e);
            }
        }

        log.info("Marked {} POs as successfully pushed", markedCount);
        return markedCount;
    }
}
