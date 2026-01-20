package com.tosspaper.aiengine.repository.impl;

import com.tosspaper.models.jooq.tables.records.DocumentApprovalsRecord;
import com.tosspaper.aiengine.repository.DocumentApprovalRepository;
import com.tosspaper.models.domain.DocumentApproval;
import com.tosspaper.models.domain.DocumentType;
import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.query.DocumentApprovalQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.tosspaper.models.jooq.Tables.DOCUMENT_APPROVALS;
import static com.tosspaper.models.jooq.Tables.INTEGRATION_CONNECTIONS;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JooqDocumentApprovalRepositoryImpl implements DocumentApprovalRepository {
    private final DSLContext dsl;
    
    private DocumentApproval save(DSLContext dslContext, DocumentApproval approval) {
        log.debug("Saving/updating document approval for assigned ID: {}", approval.getAssignedId());

        try {
            
            var record = Optional.ofNullable(dslContext).orElse(dsl).insertInto(DOCUMENT_APPROVALS)
                .set(DOCUMENT_APPROVALS.ASSIGNED_ID, approval.getAssignedId())
                .set(DOCUMENT_APPROVALS.COMPANY_ID, approval.getCompanyId())
                .set(DOCUMENT_APPROVALS.FROM_EMAIL, approval.getFromEmail())
                .set(DOCUMENT_APPROVALS.DOCUMENT_TYPE, approval.getDocumentType())
                .set(DOCUMENT_APPROVALS.PROJECT_ID, approval.getProjectId())
                .set(DOCUMENT_APPROVALS.APPROVED_AT, approval.getApprovedAt())
                .set(DOCUMENT_APPROVALS.REJECTED_AT, approval.getRejectedAt())
                .set(DOCUMENT_APPROVALS.REVIEWED_BY, approval.getReviewedBy())
                .set(DOCUMENT_APPROVALS.REVIEW_NOTES, approval.getReviewNotes())
                .set(DOCUMENT_APPROVALS.DOCUMENT_SUMMARY, approval.getDocumentSummary())
                .set(DOCUMENT_APPROVALS.STORAGE_KEY, approval.getStorageKey())
                .returning()
                .fetchSingle();

            return mapRecordToDomain(record);

        } catch (Exception e) {
            log.error("Failed to save document approval for assigned ID: {}", approval.getAssignedId(), e);
            throw e;
        }
    }

    @Override
    public DocumentApproval findById( String id) {

        return dsl.selectFrom(DOCUMENT_APPROVALS)
            .where(DOCUMENT_APPROVALS.ID.eq(id))
            .fetchOptional()
            .map(this::mapRecordToDomain)
                .orElseThrow(() -> new NotFoundException("Document approval not found for id: " + id));
    }
    
    @Override
    public Optional<DocumentApproval> findByAssignedId(String assignedId) {
        log.debug("Finding document approval by assigned ID: {}", assignedId);
        
        return dsl.selectFrom(DOCUMENT_APPROVALS)
            .where(DOCUMENT_APPROVALS.ASSIGNED_ID.eq(assignedId))
            .fetchOptional()
            .map(this::mapRecordToDomain);
    }
    
    @Override
    public DocumentApproval approve(DSLContext ctx, String approvalId, String projectId,  String reviewedBy, String notes) {
        var record  = ctx.dsl().update(DOCUMENT_APPROVALS)
                    .set(DOCUMENT_APPROVALS.APPROVED_AT, OffsetDateTime.now())
                    .set(DOCUMENT_APPROVALS.REVIEWED_BY, reviewedBy)
                    .set(DOCUMENT_APPROVALS.REVIEW_NOTES, notes)
                    .set(DOCUMENT_APPROVALS.PROJECT_ID, projectId)
                    .where(DOCUMENT_APPROVALS.ID.eq(approvalId))

                .returning()
                .fetchSingle();
        return mapRecordToDomain(record);

    }
    
    @Override
    public void reject(String approvalId, String reviewedBy, String notes) {
        log.debug("Rejecting document approval for ID: {}, reviewedBy: {}", approvalId, reviewedBy);
        OffsetDateTime now = OffsetDateTime.now();

        dsl.transactionResult(ctx -> ctx.dsl().update(DOCUMENT_APPROVALS)
                .set(DOCUMENT_APPROVALS.REJECTED_AT, now)
                .set(DOCUMENT_APPROVALS.REVIEWED_BY, reviewedBy)
                .set(DOCUMENT_APPROVALS.REVIEW_NOTES, notes)
                .where(DOCUMENT_APPROVALS.ID.eq(approvalId))

                .returning()
                .fetchSingle());

        log.debug("Rejected document approval for ID: {}", approvalId);
    }
    
    @Override
    public List<DocumentApproval> findByQuery(DocumentApprovalQuery query) {
        log.debug("Fetching document approvals with query: {}", query);
        
        int pageSize = query.getPageSize();
        Condition condition = buildCondition(query);
        
        var baseSelect = dsl.selectFrom(DOCUMENT_APPROVALS)
            .where(condition);
        
        // Order by status-appropriate timestamp based on status filter
        var orderedSelect = baseSelect.orderBy(
            getOrderField(query).desc(),
            DOCUMENT_APPROVALS.ID.desc()
        );
        
        var pagedSelect = orderedSelect.limit(pageSize);
        
        return pagedSelect.fetch(record -> mapRecordToDomain(record));
    }
    
    private Condition buildCondition(DocumentApprovalQuery query) {
        List<Condition> conditions = new ArrayList<>();
        
        // Company ID filter
        if (query.getCompanyId() != null) {
            conditions.add(DOCUMENT_APPROVALS.COMPANY_ID.eq(Long.parseLong(query.getCompanyId())));
        }
        
        if (query.getProjectId() != null) {
            conditions.add(DOCUMENT_APPROVALS.PROJECT_ID.eq(query.getProjectId()));
        }
        
        if (query.getDocumentType() != null) {
            conditions.add(DOCUMENT_APPROVALS.DOCUMENT_TYPE.eq(query.getDocumentType()));
        }
        
        if (query.getFromEmail() != null) {
            conditions.add(DOCUMENT_APPROVALS.FROM_EMAIL.eq(query.getFromEmail()));
        }
        
        if (query.getStatus() != null) {
            switch (query.getStatus().toLowerCase()) {
                case "approved" -> 
                    conditions.add(DOCUMENT_APPROVALS.APPROVED_AT.isNotNull());
                case "rejected" -> 
                    conditions.add(DOCUMENT_APPROVALS.REJECTED_AT.isNotNull()
                        .and(DOCUMENT_APPROVALS.APPROVED_AT.isNull()));
                case "pending" -> 
                    conditions.add(DOCUMENT_APPROVALS.APPROVED_AT.isNull()
                        .and(DOCUMENT_APPROVALS.REJECTED_AT.isNull()));
            }
        }
        
        if (query.getCreatedDateFrom() != null) {
            conditions.add(DOCUMENT_APPROVALS.CREATED_AT.ge(query.getCreatedDateFrom()));
        }
        
        if (query.getCreatedDateTo() != null) {
            conditions.add(DOCUMENT_APPROVALS.CREATED_AT.le(query.getCreatedDateTo()));
        }
        
        // Cursor pagination: Use the appropriate timestamp field based on status filter
        // If status is filtered, use that specific field; otherwise use COALESCE to match ordering
        if (query.getCursorCreatedAt() != null && query.getCursorId() != null) {
            var orderTimestamp = getOrderField(query);
            Condition cursorCondition = orderTimestamp.lessThan(query.getCursorCreatedAt())
                .or(orderTimestamp.eq(query.getCursorCreatedAt())
                    .and(DOCUMENT_APPROVALS.ID.lessThan(query.getCursorId())));
            conditions.add(cursorCondition);
        }
        
        return conditions.stream().reduce(org.jooq.impl.DSL::and).orElse(org.jooq.impl.DSL.trueCondition());
    }
    
    private org.jooq.Field<OffsetDateTime> getOrderField(DocumentApprovalQuery query) {
        if (query.getStatus() != null) {
            // When filtering by status, use the corresponding timestamp field
            return switch (query.getStatus().toLowerCase()) {
                case "approved" -> DOCUMENT_APPROVALS.APPROVED_AT;
                case "rejected" -> DOCUMENT_APPROVALS.REJECTED_AT;
                case "pending" -> DOCUMENT_APPROVALS.CREATED_AT;
                default -> org.jooq.impl.DSL.coalesce(
                    DOCUMENT_APPROVALS.APPROVED_AT,
                    DOCUMENT_APPROVALS.REJECTED_AT,
                    DOCUMENT_APPROVALS.CREATED_AT
                );
            };
        } else {
            // No status filter: use COALESCE to pick the appropriate timestamp
            return org.jooq.impl.DSL.coalesce(
                DOCUMENT_APPROVALS.APPROVED_AT,
                DOCUMENT_APPROVALS.REJECTED_AT,
                DOCUMENT_APPROVALS.CREATED_AT
            );
        }
    }
    @Override
    public void createInitialApproval(
            DSLContext ctx,
            String poNumber,
            String documentNumber,
            String assignedId,
            Long companyId,
            String fromEmail,
            DocumentType documentType) {

        log.debug("Creating initial approval record for assigned id: {}", assignedId);

        ctx.insertInto(DOCUMENT_APPROVALS)
                .set(DOCUMENT_APPROVALS.ASSIGNED_ID, assignedId)
                .set(DOCUMENT_APPROVALS.COMPANY_ID, companyId)
                .set(DOCUMENT_APPROVALS.DOCUMENT_TYPE, documentType.getFilePrefix())
                .set(DOCUMENT_APPROVALS.EXTERNAL_DOCUMENT_NUMBER, documentNumber)
                .set(DOCUMENT_APPROVALS.FROM_EMAIL, fromEmail)
                .set(DOCUMENT_APPROVALS.PO_NUMBER, poNumber)
                .onConflict(DOCUMENT_APPROVALS.ASSIGNED_ID)
                .doNothing()
                .execute();

        log.info("Created initial approval record for extraction task: {}", assignedId);
    }

    @Override
    public void createAutoApprovedRecord(
            DSLContext ctx,
            String poNumber,
            String documentNumber,
            String assignedId,
            Long companyId,
            String fromEmail,
            DocumentType documentType,
            String projectId) {

        log.debug("Creating auto-approved approval record for assigned id: {}", assignedId);

        OffsetDateTime now = OffsetDateTime.now();

        var record = ctx.insertInto(DOCUMENT_APPROVALS)
                .set(DOCUMENT_APPROVALS.ASSIGNED_ID, assignedId)
                .set(DOCUMENT_APPROVALS.COMPANY_ID, companyId)
                .set(DOCUMENT_APPROVALS.DOCUMENT_TYPE, documentType.getFilePrefix())
                .set(DOCUMENT_APPROVALS.EXTERNAL_DOCUMENT_NUMBER, documentNumber)
                .set(DOCUMENT_APPROVALS.FROM_EMAIL, fromEmail)
                .set(DOCUMENT_APPROVALS.PO_NUMBER, poNumber)
                .set(DOCUMENT_APPROVALS.PROJECT_ID, projectId)
                .set(DOCUMENT_APPROVALS.APPROVED_AT, now)
                .set(DOCUMENT_APPROVALS.REVIEWED_BY, "SYSTEM")
                .set(DOCUMENT_APPROVALS.REVIEW_NOTES, "Auto-approved: amount below threshold")
                .onConflict(DOCUMENT_APPROVALS.ASSIGNED_ID)
                .doNothing()
                .returning()
                .fetchSingle();

        log.info("Created auto-approved approval record for extraction task: {}", assignedId);
        mapRecordToDomain(record);
    }

    @Override
    public List<DocumentApproval> findApprovedForSync(String connectionId, OffsetDateTime cursorAt, String cursorId, int limit) {
        log.debug("Finding approved documents for sync: connectionId={}, cursorAt={}, cursorId={}, limit={}", 
                connectionId, cursorAt, cursorId, limit);

        // Use COALESCE(sync_from, created_at) to determine start time
        var syncFromField = org.jooq.impl.DSL.coalesce(
                org.jooq.impl.DSL.field("sync_from", OffsetDateTime.class),
                INTEGRATION_CONNECTIONS.CREATED_AT
        );

        Condition condition = INTEGRATION_CONNECTIONS.ID.eq(connectionId)
                .and(INTEGRATION_CONNECTIONS.STATUS.eq("enabled"))
                .and(DOCUMENT_APPROVALS.APPROVED_AT.isNotNull())
                .and(DOCUMENT_APPROVALS.APPROVED_AT.ge(syncFromField))
                .and(DOCUMENT_APPROVALS.SYNC_STATUS.isNull().or(DOCUMENT_APPROVALS.SYNC_STATUS.eq("failed"))); // Fetch unsynced or failed documents

        if (cursorAt != null) {
            Condition cursorCondition;
            if (cursorId != null) {
                cursorCondition = DOCUMENT_APPROVALS.APPROVED_AT.gt(cursorAt)
                        .or(DOCUMENT_APPROVALS.APPROVED_AT.eq(cursorAt)
                                .and(DOCUMENT_APPROVALS.ID.gt(cursorId))); // Use ID (PK) as tie-breaker
            } else {
                cursorCondition = DOCUMENT_APPROVALS.APPROVED_AT.gt(cursorAt);
            }
            condition = condition.and(cursorCondition);
        }

        return dsl.select(DOCUMENT_APPROVALS.fields())
                .from(DOCUMENT_APPROVALS)
                .join(INTEGRATION_CONNECTIONS)
                    .on(INTEGRATION_CONNECTIONS.COMPANY_ID.eq(DOCUMENT_APPROVALS.COMPANY_ID))
                .where(condition)
                .orderBy(DOCUMENT_APPROVALS.APPROVED_AT.asc(), DOCUMENT_APPROVALS.ID.asc()) // Order by ID (PK)
                .limit(limit)
                .fetch(record -> mapRecordToDomain(record.into(DOCUMENT_APPROVALS)));
    }
    
    private DocumentApproval mapRecordToDomain(DocumentApprovalsRecord record) {
        return DocumentApproval.builder()
            .id(record.getId())
            .assignedId(record.getAssignedId())
            .companyId(record.getCompanyId())
            .fromEmail(record.getFromEmail())
            .documentType(record.getDocumentType())
            .projectId(record.getProjectId())
            .approvedAt(record.getApprovedAt())
            .rejectedAt(record.getRejectedAt())
            .reviewedBy(record.getReviewedBy())
            .reviewNotes(record.getReviewNotes())
            .documentSummary(record.getDocumentSummary())
            .storageKey(record.getStorageKey())
            .createdAt(record.getCreatedAt())
            .externalDocumentNumber(record.getExternalDocumentNumber())
            .poNumber(record.getPoNumber())
            .syncStatus(record.getSyncStatus() != null 
                ? com.tosspaper.models.domain.DocumentSyncStatus.fromValue(record.getSyncStatus()) 
                : null)
            .lastSyncAttempt(record.getLastSyncAttempt())
            .build();
    }
}

