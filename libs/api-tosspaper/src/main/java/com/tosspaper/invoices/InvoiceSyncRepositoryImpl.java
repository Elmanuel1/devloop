package com.tosspaper.invoices;

import com.tosspaper.models.common.PushResult;
import com.tosspaper.models.domain.Invoice;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;

import static com.tosspaper.models.jooq.Tables.INVOICES;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDER_ITEMS;
import static com.tosspaper.models.jooq.Tables.PURCHASE_ORDERS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InvoiceSyncRepositoryImpl implements InvoiceSyncRepository {

    private final DSLContext dsl;
    private final InvoiceMapper invoiceMapper;

    @Override
    public List<Invoice> findNeedingPush(Long companyId, int limit, int maxRetries) {
        var records = dsl.selectFrom(INVOICES)
                .where(INVOICES.COMPANY_ID.eq(companyId))
                // Only push invoices that have never been pushed (one-time push, no updates after creation)
                // - last_sync_at IS NULL: invoice has never been pushed to the provider
                // Note: Invoices are never updated after creation, so we only check if they've been pushed before
                .and(INVOICES.LAST_SYNC_AT.isNull())
                // Exclude permanently failed and over max retries
                .and(INVOICES.PUSH_PERMANENTLY_FAILED.eq(false))
                .and(INVOICES.PUSH_RETRY_COUNT.lessThan(maxRetries))
                // Exclude invoices without a PO number
                .and(INVOICES.PO_NUMBER.isNotNull())
                // Exclude invoices where linked PO has line items that lack external IDs for QuickBooks push
                // A line item must have either external_item_id or external_account_id to be pushed to QB
                .andNotExists(
                        dsl.selectOne()
                                .from(PURCHASE_ORDERS)
                                .join(PURCHASE_ORDER_ITEMS).on(PURCHASE_ORDER_ITEMS.PURCHASE_ORDER_ID.eq(PURCHASE_ORDERS.ID))
                                .where(PURCHASE_ORDERS.DISPLAY_ID.eq(INVOICES.PO_NUMBER))
                                .and(PURCHASE_ORDERS.COMPANY_ID.eq(INVOICES.COMPANY_ID))
                                .and(PURCHASE_ORDER_ITEMS.EXTERNAL_ITEM_ID.isNull())
                                .and(PURCHASE_ORDER_ITEMS.EXTERNAL_ACCOUNT_ID.isNull()))
                .orderBy(INVOICES.CREATED_AT.asc())
                .limit(limit)
                .fetch();

        return records.stream()
                .map(invoiceMapper::toDomain)
                .toList();
    }

    @Override
    public int markAsPushed(List<PushResult> results) {
        log.info("InvoiceSyncRepository.markAsPushed called with {} results", results.size());

        if (results.isEmpty()) {
            log.warn("InvoiceSyncRepository.markAsPushed: results list is empty");
            return 0;
        }

        // Log each invoice being marked
        results.forEach(pr -> log.info("Preparing to mark invoice {} as pushed: externalId={}, syncedAt={}",
            pr.getDocumentId(), pr.getExternalId(), pr.getSyncedAt()));

        // Build batched UPDATE statements
        List<org.jooq.Query> updates = results.stream().<org.jooq.Query>map(pushResult -> {
            String extractionTaskId = pushResult.getDocumentId();  // This is the extraction_task_id

            var updateStep = dsl.update(INVOICES)
                    .set(INVOICES.PROVIDER, "QUICKBOOKS")
                    .set(INVOICES.LAST_SYNC_AT, pushResult.getSyncedAt())
                    // Reset retry tracking on a successful push
                    .set(INVOICES.PUSH_RETRY_COUNT, 0)
                    .set(INVOICES.PUSH_PERMANENTLY_FAILED, false)
                    .set(INVOICES.PUSH_FAILURE_REASON, (String) null);

            if (pushResult.getExternalId() != null) {
                updateStep = updateStep.set(INVOICES.EXTERNAL_ID, pushResult.getExternalId());
            }

            return updateStep.where(INVOICES.EXTRACTION_TASK_ID.eq(extractionTaskId));
        })
                .collect(Collectors.toList());

        log.info("Executing batch update for {} invoices", updates.size());
        int[] batchResults = dsl.batch(updates).execute();

        int successCount = 0;
        for (int i = 0; i < batchResults.length; i++) {
            int result = batchResults[i];
            log.info("Invoice {} batch update result: {} rows affected",
                results.get(i).getDocumentId(), result);
            if (result > 0)
                successCount++;
        }

        log.info("Successfully updated {} invoices in database", successCount);
        return successCount;
    }

    @Override
    public void incrementRetryCount(String invoiceId, String errorMessage) {
        dsl.update(INVOICES)
                .set(INVOICES.PUSH_RETRY_COUNT, INVOICES.PUSH_RETRY_COUNT.add(1))
                .set(INVOICES.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .set(INVOICES.PUSH_FAILURE_REASON, errorMessage)
                .where(INVOICES.EXTRACTION_TASK_ID.eq(invoiceId))  // invoiceId is extraction_task_id
                .execute();
    }

    @Override
    public void markAsPermanentlyFailed(String invoiceId, String errorMessage) {
        dsl.update(INVOICES)
                .set(INVOICES.PUSH_PERMANENTLY_FAILED, true)
                .set(INVOICES.PUSH_FAILURE_REASON, errorMessage)
                .set(INVOICES.PUSH_RETRY_LAST_ATTEMPT_AT, OffsetDateTime.now())
                .where(INVOICES.EXTRACTION_TASK_ID.eq(invoiceId))  // invoiceId is extraction_task_id
                .execute();
    }

    @Override
    public void resetRetryTracking(String invoiceId) {
        dsl.update(INVOICES)
                .set(INVOICES.PUSH_RETRY_COUNT, 0)
                .set(INVOICES.PUSH_PERMANENTLY_FAILED, false)
                .set(INVOICES.PUSH_FAILURE_REASON, (String) null)
                .set(INVOICES.PUSH_RETRY_LAST_ATTEMPT_AT, (OffsetDateTime) null)
                .where(INVOICES.EXTRACTION_TASK_ID.eq(invoiceId))  // invoiceId is extraction_task_id
                .execute();
    }

    @Override
    public Invoice findById(String invoiceId) {
        var record = dsl.selectFrom(INVOICES)
                .where(INVOICES.EXTRACTION_TASK_ID.eq(invoiceId))  // invoiceId is extraction_task_id
                .fetchOne();

        return record != null ? invoiceMapper.toDomain(record) : null;
    }
}
