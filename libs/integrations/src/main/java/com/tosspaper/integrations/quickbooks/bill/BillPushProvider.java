package com.tosspaper.integrations.quickbooks.bill;

import com.intuit.ipp.data.Bill;
import com.tosspaper.integrations.common.SyncResult;
import com.tosspaper.integrations.provider.IntegrationEntityType;
import com.tosspaper.integrations.provider.IntegrationPushProvider;
import com.tosspaper.integrations.quickbooks.client.QuickBooksApiClient;
import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties;
import com.tosspaper.models.common.DocumentSyncRequest;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.Invoice;
import com.tosspaper.models.domain.integration.IntegrationConnection;
import com.tosspaper.models.domain.integration.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillPushProvider implements IntegrationPushProvider<Invoice> {

    private final QuickBooksApiClient apiClient;
    private final BillMapper billMapper;
    private final QuickBooksProperties properties;

    @Override
    public IntegrationProvider getProviderId() {
        return IntegrationProvider.QUICKBOOKS;
    }

    @Override
    public IntegrationEntityType getEntityType() {
        return IntegrationEntityType.BILL;
    }

    @Override
    public DocumentType getDocumentType() {
        return DocumentType.INVOICE;
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    @Override
    public SyncResult push(IntegrationConnection connection, DocumentSyncRequest<Invoice> request) {
        try {
            String vendorId = request.getDocument().getSellerInfo() != null
                    ? request.getDocument().getSellerInfo().getReferenceNumber()
                    : null;

            if (vendorId == null) {
                return SyncResult.failure("Vendor ID missing after dependency resolution", false);
            }

            Bill qboBill = billMapper.mapToBill(request, vendorId);
            Bill createdBill = apiClient.createBill(connection, qboBill);

            return SyncResult.builder()
                    .success(true)
                    .externalId(createdBill.getId())
                    .externalDocNumber(createdBill.getDocNumber())
                    .build();

        } catch (com.tosspaper.integrations.common.exception.ProviderVersionConflictException e) {
            log.warn("Sync token conflict for invoice {}: {}", request.getDocumentId(), e.getMessage());
            return SyncResult.conflict("Entity modified in QuickBooks - please refresh and retry");
        } catch (com.tosspaper.models.exception.DuplicateException e) {
            log.warn("Duplicate error for invoice {}: {}", request.getDocumentId(), e.getMessage());
            return SyncResult.conflict("Duplicate bill - another bill is using this number in QuickBooks");
        } catch (Exception e) {
            log.error("Failed to push bill to QuickBooks: documentId={}", request.getDocumentId(), e);
            return SyncResult.failure("Failed to push bill: " + e.getMessage(), true);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, SyncResult> pushBatch(IntegrationConnection connection, List<DocumentSyncRequest<?>> requests) {
        Map<String, SyncResult> results = new HashMap<>();

        if (requests.isEmpty()) {
            return results;
        }

        // Map requests to Bills - track document IDs by index since batch results are
        // in same order
        List<Bill> bills = new ArrayList<>();
        List<String> documentIds = new ArrayList<>();

        for (DocumentSyncRequest<?> request : requests) {
            Invoice invoice = (Invoice) request.getDocument();
            String vendorId = invoice.getSellerInfo() != null ? invoice.getSellerInfo().getReferenceNumber() : null;

            if (vendorId == null) {
                results.put(request.getDocumentId(),
                        SyncResult.failure("Vendor ID missing after dependency resolution", false));
                continue;
            }

            Bill qboBill = billMapper.mapToBill((DocumentSyncRequest<Invoice>) request, vendorId);
            bills.add(qboBill);
            documentIds.add(request.getDocumentId());
        }

        // Execute batch save - results are returned in same order as input
        List<QuickBooksApiClient.BatchResult<Bill>> batchResults = apiClient.saveBatch(connection, bills);

        // Map batch results back to document IDs using index
        for (int i = 0; i < batchResults.size(); i++) {
            String documentId = documentIds.get(i);
            QuickBooksApiClient.BatchResult<Bill> batchResult = batchResults.get(i);

            // Early exit: Success case
            if (batchResult.success()) {
                results.put(documentId, SyncResult.builder()
                        .success(true)
                        .externalId(batchResult.entity().getId())
                        .externalDocNumber(batchResult.entity().getDocNumber())
                        .providerVersion(batchResult.entity().getSyncToken())
                        .build());
                continue;
            }

            // Handle failures with early exits
            String errorMsg = batchResult.errorMessage();
            String lowerMsg = errorMsg != null ? errorMsg.toLowerCase() : "";

            // Early exit: Sync token conflicts
            if (lowerMsg.contains("stale") || lowerMsg.contains("synctoken")) {
                results.put(documentId, SyncResult.conflict(errorMsg));
                continue;
            }

            // Early exit: Duplicate errors
            if (lowerMsg.contains("duplicate") ||
                lowerMsg.contains("already exists")) {
                results.put(documentId, SyncResult.conflict(errorMsg));
                continue;
            }

            // Default: Retryable failure
            results.put(documentId, SyncResult.failure(errorMsg, true));
        }

        return results;
    }
}
