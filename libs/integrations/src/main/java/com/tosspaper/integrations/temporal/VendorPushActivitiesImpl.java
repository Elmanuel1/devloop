package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.service.ContactSyncService;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ActivityImpl(taskQueues = "integration-sync")
@RequiredArgsConstructor
public class VendorPushActivitiesImpl implements VendorPushActivities {

    private final IntegrationConnectionService connectionService;
    private final ContactSyncService contactSyncService;
    private final IntegrationProviderFactory providerFactory;
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
    public List<Party> fetchVendorsNeedingPush(SyncConnectionData connection, int limit) {
        log.debug("Fetching vendors needing push: companyId={}, limit={}, maxRetries={}",
                connection.getCompanyId(), limit, pushRetryConfig.getMaxAttempts());
        List<Party> vendors = contactSyncService.findNeedingPush(
                connection.getCompanyId(),
                limit,
                List.of("vendor", "supplier"),
                pushRetryConfig.getMaxAttempts());
        if (vendors.isEmpty()) {
            log.debug("Found {} vendors needing push", vendors.size());
        } else {
            log.info("Found {} vendors needing push", vendors.size());
        }
        return vendors;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushVendors(SyncConnectionData connection, List<Party> vendors) {
        Map<String, SyncResult> results = new HashMap<>();

        if (vendors.isEmpty()) {
            return results;
        }

        log.info("Pushing {} vendors to provider: {}", vendors.size(), connection.getProvider());

        var pushProviderOpt = providerFactory.getPushProvider(connection.getProvider(), IntegrationEntityType.VENDOR);

        if (pushProviderOpt.isEmpty()) {
            log.warn("No vendor push provider found for: {}", connection.getProvider());
            for (Party vendor : vendors) {
                results.put(vendor.getId(), SyncResult.failure("No push provider available", false));
            }
            return results;
        }

        IntegrationPushProvider<Party> pushProvider = (IntegrationPushProvider<Party>) pushProviderOpt.get();

        // Fetch fresh connection with tokens (not from Temporal history)
        IntegrationConnection integrationConnection = getConnectionWithTokens(connection);

        List<DocumentSyncRequest<?>> requests = vendors.stream()
                .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromVendor)
                .collect(java.util.stream.Collectors.toList());

        results = pushProvider.pushBatch(integrationConnection, requests);

        long successfulCount = results.values().stream().filter(SyncResult::isSuccess).count();
        long failedCount = results.size() - successfulCount;

        log.info("Pushed {} vendors with {} successful and {} failed",
                vendors.size(), successfulCount, failedCount);

        // Log details of failed pushes
        if (failedCount > 0) {
            results.entrySet().stream()
                    .filter(entry -> !entry.getValue().isSuccess())
                    .forEach(entry -> {
                        SyncResult result = entry.getValue();
                        log.warn("Vendor {} push failed: {} (retryable: {})",
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
    public int markVendorsAsPushed(String provider, Map<String, SyncResult> results) {
        int markedCount = 0;

        for (Map.Entry<String, SyncResult> entry : results.entrySet()) {
            String vendorId = entry.getKey();
            SyncResult result = entry.getValue();

            // Early exit: Success case
            if (result.isSuccess()) {
                try {
                    contactSyncService.updateSyncStatus(
                            vendorId,
                            provider,
                            result.getExternalId(),
                            result.getProviderVersion(),
                            result.getProviderLastUpdatedAt()
                    );
                    markedCount++;
                } catch (Exception e) {
                    log.error("Failed to mark vendor {} as pushed", vendorId, e);
                }
                continue;
            }

            // Early exit: Non-retryable failures (conflicts, duplicate names)
            if (!result.isRetryable()) {
                try {
                    contactSyncService.markAsPermanentlyFailed(vendorId, result.getErrorMessage());
                    log.warn("Vendor {} marked as permanently failed (non-retryable): {}",
                            vendorId, result.getErrorMessage());
                } catch (Exception e) {
                    log.error("Failed to mark vendor {} as permanently failed", vendorId, e);
                }
                continue;
            }

            // Retryable errors: increment retry count
            try {
                contactSyncService.incrementRetryCount(vendorId, result.getErrorMessage());

                // Check if exceeded max retries
                Party vendor = contactSyncService.findById(vendorId);
                if (vendor != null && vendor.getPushRetryCount() != null &&
                    vendor.getPushRetryCount() >= pushRetryConfig.getMaxAttempts()) {
                    contactSyncService.markAsPermanentlyFailed(
                            vendorId,
                            String.format("Exceeded max retries (%d). Last error: %s",
                                    pushRetryConfig.getMaxAttempts(),
                                    result.getErrorMessage()));
                    log.warn("Vendor {} exceeded max retries and marked permanently failed", vendorId);
                }
            } catch (Exception e) {
                log.error("Failed to increment retry count for vendor {}", vendorId, e);
            }
        }

        log.info("Marked {} vendors as successfully pushed", markedCount);
        return markedCount;
    }
}
