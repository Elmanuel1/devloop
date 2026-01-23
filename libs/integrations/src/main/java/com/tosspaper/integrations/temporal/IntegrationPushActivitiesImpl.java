package com.tosspaper.integrations.temporal;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.IntegrationException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationProviderFactory;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.service.IntegrationConnectionService;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.common.PushResult;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.service.InvoiceSyncService;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Implementation of push activities for syncing invoices as Bills to
 * QuickBooks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(workers = "integration-sync-worker")
public class IntegrationPushActivitiesImpl implements IntegrationPushActivities {

    private final IntegrationConnectionService connectionService;
    private final InvoiceSyncService invoiceSyncService;
    private final IntegrationProviderFactory providerFactory;
    private final com.tosspaper.integrations.config.PushRetryConfig pushRetryConfig;
    private final com.tosspaper.integrations.common.DependencyCoordinatorService dependencyCoordinator;

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
    public List<Invoice> fetchAcceptedInvoicesNeedingPush(SyncConnectionData connection, int limit) {
        log.debug("Fetching accepted invoices needing push for company {}, limit {}",
                connection.getCompanyId(), limit);

        List<Invoice> invoices = invoiceSyncService.findAcceptedNeedingPush(connection.getCompanyId(), limit);

        if (invoices.isEmpty()) {
            log.debug("Found {} accepted invoices needing push for company {}",
                    invoices.size(), connection.getCompanyId());
        } else {
            log.info("Found {} accepted invoices needing push for company {}",
                    invoices.size(), connection.getCompanyId());
        }

        return invoices;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushInvoicesAsBills(SyncConnectionData connection, List<Invoice> invoices) {
        if (invoices.isEmpty()) {
            return Map.of();
        }

        log.info("Pushing {} invoices as bills to {} for company {}",
                invoices.size(), connection.getProvider(), connection.getCompanyId());

        IntegrationConnection apiConnection = getConnectionWithTokens(connection);

        // Get the Bill push provider
        IntegrationPushProvider<Invoice> pushProvider = (IntegrationPushProvider<Invoice>) providerFactory
                .getPushProvider(connection.getProvider(), IntegrationEntityType.BILL)
                .orElseThrow(() -> new IntegrationException(
                        "Push provider not found for " + connection.getProvider() + "/BILL"));

        if (!pushProvider.isEnabled()) {
            log.warn("Push provider disabled for {}/BILL", connection.getProvider());
            return Map.of();
        }

        // Resolve dependencies (Vendor and PO) before pushing
        log.info("Resolving dependencies for {} invoices", invoices.size());
        com.tosspaper.integrations.common.DependencyPushResult dependencyResult =
                dependencyCoordinator.ensureAllDependencies(apiConnection, IntegrationEntityType.BILL, invoices);

        if (!dependencyResult.isSuccess()) {
            log.error("Dependency resolution failed for invoices: {}", dependencyResult.getMessage());
            // Return failure for all invoices
            Map<String, SyncResult> failureResults = new java.util.HashMap<>();
            for (Invoice invoice : invoices) {
                failureResults.put(invoice.getId(),
                        SyncResult.failure("Dependency resolution failed: " + dependencyResult.getMessage(), true));
            }
            return failureResults;
        }

        log.info("Dependencies resolved successfully for {} invoices", invoices.size());

        // Convert invoices to DocumentSyncRequests
        List<DocumentSyncRequest<?>> requests = invoices.stream()
                .<DocumentSyncRequest<?>>map(DocumentSyncRequest::fromInvoice)
                .toList();

        // Push batch
        Map<String, SyncResult> results = pushProvider.pushBatch(apiConnection, requests);

        // Log results
        long successCount = results.values().stream().filter(SyncResult::isSuccess).count();
        long failCount = results.size() - successCount;
        log.info("Push results for company {}: {} success, {} failed",
                connection.getCompanyId(), successCount, failCount);

        // Log details of failed pushes
        if (failCount > 0) {
            results.entrySet().stream()
                    .filter(entry -> !entry.getValue().isSuccess())
                    .forEach(entry -> {
                        SyncResult result = entry.getValue();
                        log.warn("Invoice {} push failed: {} (retryable: {})",
                                entry.getKey(),
                                result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error",
                                result.isRetryable());
                    });
        }

        return results;
    }

    @Override
    public int markInvoicesAsPushed(Map<String, SyncResult> results) {
        log.info("markInvoicesAsPushed called with {} results", results.size());

        if (results.isEmpty()) {
            log.warn("markInvoicesAsPushed: results map is empty, returning 0");
            return 0;
        }

        int markedCount = 0;
        OffsetDateTime syncedAt = OffsetDateTime.now();

        log.info("Processing {} invoice push results, syncedAt={}", results.size(), syncedAt);

        for (Map.Entry<String, SyncResult> entry : results.entrySet()) {
            String invoiceId = entry.getKey();
            SyncResult result = entry.getValue();

            log.debug("Processing invoice {}: success={}, externalId={}, retryable={}",
                invoiceId, result.isSuccess(), result.getExternalId(), result.isRetryable());

            // Early exit: Success case
            if (result.isSuccess()) {
                try {
                    log.info("Marking invoice {} as successfully pushed with externalId={}",
                        invoiceId, result.getExternalId());

                    invoiceSyncService.markAsPushed(List.of(PushResult.builder()
                            .documentId(invoiceId)
                            .externalId(result.getExternalId())
                            .syncedAt(syncedAt)
                            .build()));
                    markedCount++;

                    log.info("Successfully marked invoice {} as pushed (count={})", invoiceId, markedCount);
                } catch (Exception e) {
                    log.error("Failed to mark invoice {} as pushed", invoiceId, e);
                }
                continue;
            }

            // Early exit: Non-retryable failures (conflicts, duplicate names)
            if (!result.isRetryable()) {
                try {
                    invoiceSyncService.markAsPermanentlyFailed(invoiceId, result.getErrorMessage());
                    log.warn("Invoice {} marked as permanently failed (non-retryable): {}",
                            invoiceId, result.getErrorMessage());
                } catch (Exception e) {
                    log.error("Failed to mark invoice {} as permanently failed", invoiceId, e);
                }
                continue;
            }

            // Retryable errors: increment retry count
            try {
                invoiceSyncService.incrementRetryCount(invoiceId, result.getErrorMessage());

                // Check if exceeded max retries
                Invoice invoice = invoiceSyncService.findById(invoiceId);
                if (invoice != null && invoice.getPushRetryCount() != null) {
                    log.warn("Invoice {} push failed (attempt {}/{}): {}",
                            invoiceId, invoice.getPushRetryCount(), pushRetryConfig.getMaxAttempts(),
                            result.getErrorMessage());

                    if (invoice.getPushRetryCount() >= pushRetryConfig.getMaxAttempts()) {
                        invoiceSyncService.markAsPermanentlyFailed(
                                invoiceId,
                                String.format("Exceeded max retries (%d). Last error: %s",
                                        pushRetryConfig.getMaxAttempts(),
                                        result.getErrorMessage()));
                        log.warn("Invoice {} exceeded max retries and marked permanently failed", invoiceId);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to increment retry count for invoice {}", invoiceId, e);
            }
        }

        log.info("Marked {} invoices as successfully pushed", markedCount);
        return markedCount;
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
}
