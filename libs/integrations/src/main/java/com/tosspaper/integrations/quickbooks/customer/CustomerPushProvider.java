package com.tosspaper.integrations.quickbooks.customer;

import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.Party;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerPushProvider implements IntegrationPushProvider<Party> {

    private final QuickBooksApiClient apiClient;
    private final CustomerMapper customerMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.JOB_LOCATION;
    }

    @Override
    public DocumentType getDocumentType() {
        throw new UnsupportedOperationException("CustomerPushProvider does not support DocumentType - customers are not documents");
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public SyncResult push(IntegrationConnection connection, DocumentSyncRequest<Party> request) {
        return push(connection, request.getDocument());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushBatch(IntegrationConnection connection, List<DocumentSyncRequest<?>> batch) {
        try {
            List<com.intuit.ipp.data.Customer> qboCustomers = batch.stream()
                    .map(req -> (Party) req.getDocument())
                    .map(customerMapper::toQboCustomer)
                    .collect(Collectors.toList());

            List<QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Customer>> batchResults =
                    apiClient.saveBatch(connection, qboCustomers);

            Map<String, SyncResult> results = new HashMap<>();

            // Map results to document IDs
            for (int i = 0; i < batch.size(); i++) {
                DocumentSyncRequest<?> request = batch.get(i);
                String documentId = request.getDocumentId();

                if (i < batchResults.size()) {
                    QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Customer> result = batchResults.get(i);
                    if (result.success()) {
                        // Extract QB's LastUpdatedTime for accurate sync tracking
                        java.time.OffsetDateTime qbLastUpdated = result.entity().getMetaData() != null && result.entity().getMetaData().getLastUpdatedTime() != null
                                ? result.entity().getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC)
                                : null;

                        results.put(documentId, SyncResult.success(
                                result.entity().getId(),
                                result.entity().getDisplayName(),
                                result.entity().getSyncToken(),
                                qbLastUpdated
                        ));
                    } else {
                        // Detect stale object errors and convert to conflict
                        String errorMsg = result.errorMessage();
                        boolean isStaleError = errorMsg != null &&
                            (errorMsg.toLowerCase().contains("stale") ||
                             errorMsg.toLowerCase().contains("synctoken"));

                        if (isStaleError) {
                            results.put(documentId, SyncResult.conflict(errorMsg));
                        } else {
                            results.put(documentId, SyncResult.failure(errorMsg, true));
                        }
                    }
                } else {
                    results.put(documentId, SyncResult.failure("No result returned from batch", false));
                }
            }

            return results;

        } catch (Exception e) {
            log.error("Customer batch push failed", e);
            Map<String, SyncResult> errorResults = new HashMap<>();
            for (DocumentSyncRequest<?> request : batch) {
                errorResults.put(request.getDocumentId(),
                        SyncResult.failure("Batch push error: " + e.getMessage(), true));
            }
            return errorResults;
        }
    }

    /**
     * Push a single customer to QuickBooks.
     * Handles both CREATE (new customer) and UPDATE (existing customer) operations.
     */
    public SyncResult push(IntegrationConnection connection, Party customer) {
        try {
            com.intuit.ipp.data.Customer qboCustomer = customerMapper.toQboCustomer(customer);

            log.debug("{} customer {} in QuickBooks",
                    customer.isUpdatable() ? "Updating" : "Creating",
                    customer.isUpdatable() ? customer.getExternalId() : customer.getName());

            com.intuit.ipp.data.Customer result = apiClient.save(connection, qboCustomer);

            // Extract QB's LastUpdatedTime for accurate sync tracking
            java.time.OffsetDateTime qbLastUpdated = result.getMetaData() != null && result.getMetaData().getLastUpdatedTime() != null
                    ? result.getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC)
                    : null;

            return SyncResult.success(
                    result.getId(),
                    result.getDisplayName(),
                    result.getSyncToken(),
                    qbLastUpdated
            );

        } catch (ProviderVersionConflictException e) {
            log.warn("Sync token conflict for customer {}: {}", customer.getId(), e.getMessage());
            return SyncResult.conflict("Entity modified in QuickBooks - please refresh and retry");
        } catch (Exception e) {
            log.error("Failed to push customer to QuickBooks", e);
            return SyncResult.failure("Failed to push customer: " + e.getMessage(), true);
        }
    }
}
