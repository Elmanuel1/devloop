package com.tosspaper.integrations.quickbooks.vendor;

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
public class VendorPushProvider implements IntegrationPushProvider<Party> {

    private final QuickBooksApiClient apiClient;
    private final VendorMapper vendorMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.VENDOR;
    }

    @Override
    public DocumentType getDocumentType() {
        throw new UnsupportedOperationException("VendorPushProvider does not support DocumentType - vendors are not documents");
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
            List<com.intuit.ipp.data.Vendor> qboVendors = batch.stream()
                    .map(req -> (Party) req.getDocument())
                    .map(vendorMapper::toQboVendor)
                    .collect(Collectors.toList());

            List<QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Vendor>> batchResults =
                    apiClient.saveBatch(connection, qboVendors);

            Map<String, SyncResult> results = new HashMap<>();

            // Map results to document IDs
            for (int i = 0; i < batch.size(); i++) {
                DocumentSyncRequest<?> request = batch.get(i);
                String documentId = request.getDocumentId();
                
                if (i < batchResults.size()) {
                    QuickBooksApiClient.BatchResult<com.intuit.ipp.data.Vendor> result = batchResults.get(i);
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
                        String errorMsg = result.errorMessage();
                        String lowerMsg = errorMsg != null ? errorMsg.toLowerCase() : "";

                        // Early exit: Sync token conflicts
                        if (lowerMsg.contains("stale") || lowerMsg.contains("synctoken")) {
                            results.put(documentId, SyncResult.conflict(errorMsg));
                            continue;
                        }

                        // Early exit: Duplicate name errors
                        if (lowerMsg.contains("duplicate name") ||
                            lowerMsg.contains("name supplied already exists") ||
                            lowerMsg.contains("already using this name")) {
                            results.put(documentId, SyncResult.conflict(errorMsg));
                            continue;
                        }

                        // Default: Retryable failure
                        results.put(documentId, SyncResult.failure(errorMsg, true));
                    }
                } else {
                    results.put(documentId, SyncResult.failure("No result returned from batch", false));
                }
            }

            return results;

        } catch (Exception e) {
            log.error("Vendor batch push failed", e);
            Map<String, SyncResult> errorResults = new HashMap<>();
            for (DocumentSyncRequest<?> request : batch) {
                errorResults.put(request.getDocumentId(),
                        SyncResult.failure("Batch push error: " + e.getMessage(), true));
            }
            return errorResults;
        }
    }

    /**
     * Push a single vendor to QuickBooks.
     * Handles both CREATE (new vendor) and UPDATE (existing vendor) operations.
     */
    public SyncResult push(IntegrationConnection connection, Party vendor) {
        try {
            com.intuit.ipp.data.Vendor qboVendor = vendorMapper.toQboVendor(vendor);

            log.debug("{} vendor {} in QuickBooks",
                    vendor.isUpdatable() ? "Updating" : "Creating",
                    vendor.isUpdatable() ? vendor.getExternalId() : vendor.getName());

            com.intuit.ipp.data.Vendor result = apiClient.save(connection, qboVendor);

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
            log.warn("Sync token conflict for vendor {}: {}", vendor.getId(), e.getMessage());
            return SyncResult.conflict("Entity modified in QuickBooks - please refresh and retry");
        } catch (com.tosspaper.models.exception.DuplicateException e) {
            log.warn("Duplicate name error for vendor {}: {}", vendor.getId(), e.getMessage());
            return SyncResult.conflict("Duplicate name - another contact is using this name in QuickBooks");
        } catch (Exception e) {
            log.error("Failed to push vendor to QuickBooks", e);
            return SyncResult.failure("Failed to push vendor: " + e.getMessage(), true);
        }
    }
}

