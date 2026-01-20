package com.tosspaper.aiengine.repository.impl;

import com.tosspaper.aiengine.repository.DocumentMatchRepository;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.domain.MatchType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

import static com.tosspaper.models.jooq.Tables.INVOICES;
import static com.tosspaper.models.jooq.Tables.DELIVERY_SLIPS;
import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK;
import static com.tosspaper.models.jooq.Tables.DOCUMENT_APPROVALS;

/**
 * Repository implementation for managing document match state transitions.
 * All operations are atomic and update both document tables (invoices/delivery_slips)
 * and extraction_task (for fast queries).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class JooqDocumentMatchRepositoryImpl implements DocumentMatchRepository {

    private final DSLContext dsl;

    @Override
    public void updateToInProgress(String assignedId, DocumentType documentType) {
        log.debug("Updating document {} to IN_PROGRESS", assignedId);

        dsl.transaction(ctx -> {
            OffsetDateTime now = OffsetDateTime.now();

            // Sync extraction_task.match_type for fast queries
            ctx.dsl().update(EXTRACTION_TASK)
                .set(EXTRACTION_TASK.MATCH_TYPE, MatchType.IN_PROGRESS.getValue())
                .set(EXTRACTION_TASK.UPDATED_AT, now)
                .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
                .execute();
        });

        log.debug("Updated document {} to IN_PROGRESS", assignedId);
    }

    @Override
    public void updateToManual(String assignedId, DocumentType documentType,
                              String matchReport, String poId, String poNumber, String projectId) {
        log.debug("Updating document {} to MANUAL with PO {}", assignedId, poId);

        dsl.transaction(ctx -> {
            OffsetDateTime now = OffsetDateTime.now();
            JSONB matchReportJson = matchReport != null ? JSONB.jsonbOrNull(matchReport) : null;

            // Update document table (invoices, delivery_slips, or delivery_notes)
            if (documentType == DocumentType.INVOICE) {
                ctx.dsl().update(INVOICES)
                    .set(INVOICES.PURCHASE_ORDER_ID, poId)
                    .set(INVOICES.PO_NUMBER, poNumber)
                    .set(INVOICES.PROJECT_ID, projectId)
                    .set(INVOICES.UPDATED_AT, now)
                    .where(INVOICES.EXTRACTION_TASK_ID.eq(assignedId))
                    .execute();
            } else if (documentType == DocumentType.DELIVERY_SLIP) {
                ctx.dsl().update(DELIVERY_SLIPS)
                    .set(DELIVERY_SLIPS.PURCHASE_ORDER_ID, poId)
                    .set(DELIVERY_SLIPS.PO_NUMBER, poNumber)
                    .set(DELIVERY_SLIPS.PROJECT_ID, projectId)
                    .set(DELIVERY_SLIPS.UPDATED_AT, now)
                    .where(DELIVERY_SLIPS.EXTRACTION_TASK_ID.eq(assignedId))
                    .execute();
            } else if (documentType == DocumentType.DELIVERY_NOTE) {
                // Delivery notes are handled via extraction_task only, no separate table update needed
                log.debug("Delivery note match updated via extraction_task for: {}", assignedId);
            } else if (documentType == DocumentType.UNKNOWN || documentType == DocumentType.PURCHASE_ORDER) {
                // Unknown and purchase order document types are handled via extraction_task only
                log.debug("{} document type match updated via extraction_task for: {}", documentType, assignedId);
            }

            // Sync extraction_task for fast queries
            ctx.dsl().update(EXTRACTION_TASK)
                .set(EXTRACTION_TASK.MATCH_TYPE, MatchType.MANUAL.getValue())
                .set(EXTRACTION_TASK.MATCH_REPORT, matchReportJson)
                .set(EXTRACTION_TASK.PURCHASE_ORDER_ID, poId)
                .set(EXTRACTION_TASK.PO_NUMBER, poNumber)
                .set(EXTRACTION_TASK.PROJECT_ID, projectId)
                .set(EXTRACTION_TASK.UPDATED_AT, now)
                .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
                .execute();
        });

        log.debug("Updated document {} to MANUAL", assignedId);
    }

    @Override
    public void updateToPending(String assignedId, DocumentType documentType) {
        log.debug("Updating document {} to PENDING", assignedId);

        dsl.transaction(ctx -> {
            OffsetDateTime now = OffsetDateTime.now();

            // Sync extraction_task.match_type for fast queries
            ctx.dsl().update(EXTRACTION_TASK)
                .set(EXTRACTION_TASK.MATCH_TYPE, MatchType.PENDING.getValue())
                .set(EXTRACTION_TASK.UPDATED_AT, now)
                .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
                .execute();
        });

        log.debug("Updated document {} to PENDING", assignedId);
    }

    @Override
    public void updateMatchInfo(
            String assignedId,
            DocumentType documentType,
            MatchType matchType,
            String matchReport,
            String purchaseOrderId,
            String poNumber,
            String projectId) {

        dsl.transactionResult(ctx -> {

            ctx.dsl().update(EXTRACTION_TASK)
                    .set(EXTRACTION_TASK.MATCH_TYPE, matchType.getValue())
                    .set(EXTRACTION_TASK.MATCH_REPORT, JSONB.jsonbOrNull(matchReport))
                    .set(EXTRACTION_TASK.PURCHASE_ORDER_ID, purchaseOrderId)
                    .set(EXTRACTION_TASK.PO_NUMBER, poNumber)
                    .set(EXTRACTION_TASK.PROJECT_ID, projectId)
                    .where(EXTRACTION_TASK.ASSIGNED_ID.eq(assignedId))
                    .execute();
            // Update existing approval record with match information
            // Approval record already created by createInitialApproval() with po_number
            if(projectId != null) {
                ctx.dsl().update(DOCUMENT_APPROVALS)
                        .set(DOCUMENT_APPROVALS.PROJECT_ID, projectId)
                        .where(DOCUMENT_APPROVALS.ASSIGNED_ID.eq(assignedId))
                        .execute();
            }

            log.debug("Updated document and approval records with match info for extraction task: {}", assignedId);
            return null;
        });
    }
}