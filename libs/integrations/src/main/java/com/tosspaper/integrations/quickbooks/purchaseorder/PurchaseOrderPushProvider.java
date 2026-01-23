package com.tosspaper.integrations.quickbooks.purchaseorder;

import com.tosspaper.integrations.common.PurchaseOrderLineItemEnricher;
import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.common.exception.ProviderVersionConflictException;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.PurchaseOrder;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * QuickBooks push provider for Purchase Orders.
 * <p>
 * This provider focuses solely on mapping and pushing POs to QuickBooks.
 * Dependency resolution is handled by IntegrationPushCoordinator before calling this provider.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseOrderPushProvider implements IntegrationPushProvider<PurchaseOrder> {

    private final QuickBooksApiClient apiClient;
    private final QBOPurchaseOrderMapper poMapper;
    private final QuickBooksProperties properties;
    private final PurchaseOrderLineItemEnricher lineItemEnricher;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.PURCHASE_ORDER;
    }

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.PURCHASE_ORDER;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public SyncResult push(IntegrationConnection connection, DocumentSyncRequest<PurchaseOrder> request) {
        return push(connection, request.getDocument());
    }

    /**
     * Push a single purchase order to QuickBooks.
     * Handles both CREATE (new PO) and UPDATE (existing PO) operations.
     * <p>
     * NOTE: Dependency resolution (ensuring vendors/items/accounts have external IDs)
     * is handled by IntegrationPushCoordinator before this method is called.
     */
    public SyncResult push(IntegrationConnection connection, PurchaseOrder domainPo) {
        try {
            // 1. Enrich line items with external IDs from items/accounts
             lineItemEnricher.enrichLineItems(connection.getId(), List.of(domainPo));

            // 2. Map to QuickBooks object (handles both CREATE and UPDATE)
            com.intuit.ipp.data.PurchaseOrder qboPO = poMapper.toQboPurchaseOrder(domainPo);

            log.debug("{} purchase order {} in QuickBooks",
                    domainPo.isUpdatable() ? "Updating" : "Creating",
                    domainPo.isUpdatable() ? domainPo.getExternalId() : domainPo.getDisplayId());

            com.intuit.ipp.data.PurchaseOrder result = apiClient.save(connection, qboPO);

            // Extract QB's LastUpdatedTime for accurate sync tracking
            java.time.OffsetDateTime qbLastUpdated = result.getMetaData() != null && result.getMetaData().getLastUpdatedTime() != null
                    ? result.getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC)
                    : null;

            return SyncResult.success(
                    result.getId(),
                    result.getDocNumber(),
                    result.getSyncToken(),
                    qbLastUpdated
            );

        } catch (ProviderVersionConflictException e) {
            log.warn("Sync token conflict for PO {}: {}", domainPo.getId(), e.getMessage());
            return SyncResult.conflict("Entity modified in QuickBooks - please refresh and retry");
        } catch (com.tosspaper.models.exception.DuplicateException e) {
            log.warn("Duplicate name error for PO {}: {}", domainPo.getId(), e.getMessage());
            return SyncResult.conflict("Duplicate name - another entity is using this name in QuickBooks");
        } catch (Exception e) {
            log.error("Failed to push purchase order to QuickBooks: id={}", domainPo.getId(), e);
            return SyncResult.failure("Failed to push PO: " + e.getMessage(), true);
        }
    }

    @Override
    public Map<String, SyncResult> pushBatch(IntegrationConnection connection, List<DocumentSyncRequest<?>> requests) {
        Map<String, SyncResult> results = new HashMap<>();

        if (requests.isEmpty()) {
            return results;
        }

        // Extract domain POs
        List<PurchaseOrder> domainPos = requests.stream()
                .map(req -> (PurchaseOrder) req.getDocument())
                .toList();

        // NOTE: Dependency resolution is handled by IntegrationPushCoordinator before this method is called.
        // This method assumes all dependencies already have external IDs.

        // Enrich line items with external IDs from items/accounts
        lineItemEnricher.enrichLineItems(connection.getId(), domainPos);

        // Map requests to Purchase Orders - track document IDs by index since batch results are in same order
        List<com.intuit.ipp.data.PurchaseOrder> purchaseOrders = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();

        for (DocumentSyncRequest<?> request : requests) {
            PurchaseOrder po = (PurchaseOrder) request.getDocument();
            com.intuit.ipp.data.PurchaseOrder qboPO = poMapper.toQboPurchaseOrder(po);
            purchaseOrders.add(qboPO);
            documentIds.add(request.getDocumentId());
        }
        
        // Execute batch save - results are returned in same order as input
        List<QuickBooksApiClient.BatchResult<com.intuit.ipp.data.PurchaseOrder>> batchResults = 
                apiClient.saveBatch(connection, purchaseOrders);
        
        // Map batch results back to document IDs using index
        for (int i = 0; i < batchResults.size(); i++) {
            String documentId = documentIds.get(i);
            QuickBooksApiClient.BatchResult<com.intuit.ipp.data.PurchaseOrder> batchResult = batchResults.get(i);
            
            if (batchResult.success() && batchResult.entity() != null) {
                // Extract QB's LastUpdatedTime for accurate sync tracking
                java.time.OffsetDateTime qbLastUpdated = batchResult.entity().getMetaData() != null && batchResult.entity().getMetaData().getLastUpdatedTime() != null
                        ? batchResult.entity().getMetaData().getLastUpdatedTime().toInstant().atOffset(java.time.ZoneOffset.UTC)
                        : null;

                results.put(documentId, SyncResult.success(
                        batchResult.entity().getId(),
                        batchResult.entity().getDocNumber(),
                        batchResult.entity().getSyncToken(),
                        qbLastUpdated
                ));
            } else {
                String errorMsg = batchResult.errorMessage() != null ? batchResult.errorMessage() : "Unknown batch error";
                String lowerMsg = errorMsg.toLowerCase();

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
        }

        return results;
    }
}

