package com.tosspaper.emailengine.repository.impl;

import com.tosspaper.emailengine.repository.ApprovedSenderRepository;
import com.tosspaper.models.service.EmailDomainService;
import com.tosspaper.models.domain.ApprovedSender;
import com.tosspaper.models.domain.PendingApprovalDocument;
import com.tosspaper.models.domain.PendingSenderApproval;
import com.tosspaper.models.enums.EmailWhitelistValue;
import com.tosspaper.models.enums.SenderApprovalStatus;
import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.paging.Pagination;
import com.tosspaper.models.utils.EmailPatternMatcher;
import com.tosspaper.models.utils.EmailUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tosspaper.models.jooq.Tables.APPROVED_SENDERS;
import static com.tosspaper.models.jooq.Tables.EMAIL_ATTACHMENT;
import static com.tosspaper.models.jooq.Tables.EMAIL_MESSAGE;
import static com.tosspaper.models.jooq.Tables.EMAIL_THREAD;
import static com.tosspaper.models.jooq.Tables.EXTRACTION_TASK;


@Slf4j
@Repository
@RequiredArgsConstructor
public class ApprovedSenderRepositoryImpl implements ApprovedSenderRepository {

    private final DSLContext dsl;
    private final EmailDomainService emailDomainService;

    @Override
    public List<ApprovedSender> findByCompanyId(Long companyId) {
        return dsl.selectFrom(APPROVED_SENDERS)
            .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
            .fetch(record -> ApprovedSender.builder()
                .id(record.getId())
                .companyId(record.getCompanyId())
                .senderIdentifier(record.getSenderIdentifier())
                .whitelistType(EmailWhitelistValue.fromString(record.getWhitelistType()))
                .status(SenderApprovalStatus.fromValue(record.getStatus()))
                .approvedBy(record.getApprovedBy())
                .approvedAt(record.getApprovedAt())
                .updatedAt(record.getUpdatedAt())
                .createdAt(record.getCreatedAt())
                .scheduledDeletionAt(record.getScheduledDeletionAt())
                .build()
            );
    }
    
    @Override
    public Paginated<ApprovedSender> findByCompanyIdAndStatus(Long companyId, int page, int pageSize, SenderApprovalStatus... statuses) {
        // Calculate offset
        int offset = (page - 1) * pageSize;
        
        // Build conditions
        var conditions = new java.util.ArrayList<org.jooq.Condition>();
        conditions.add(APPROVED_SENDERS.COMPANY_ID.eq(companyId));
        
        // Add status condition using IN query
        if (statuses.length > 0) {
            var statusValues = java.util.Arrays.stream(statuses)
                .map(SenderApprovalStatus::getValue)
                .collect(java.util.stream.Collectors.toList());
            conditions.add(APPROVED_SENDERS.STATUS.in(statusValues));
        }
        
        // Get total count
        int totalItems = dsl.selectCount()
            .from(APPROVED_SENDERS)
            .where(conditions)
            .fetchOne(0, int.class);
        
        // Fetch paginated data
        List<ApprovedSender> data = dsl.selectFrom(APPROVED_SENDERS)
            .where(conditions)
            .orderBy(APPROVED_SENDERS.CREATED_AT.desc())
            .limit(pageSize)
            .offset(offset)
            .fetch(record -> ApprovedSender.builder()
                .id(record.getId())
                .companyId(record.getCompanyId())
                .senderIdentifier(record.getSenderIdentifier())
                .whitelistType(EmailWhitelistValue.fromString(record.getWhitelistType()))
                .status(SenderApprovalStatus.fromValue(record.getStatus()))
                .approvedBy(record.getApprovedBy())
                .approvedAt(record.getApprovedAt())
                .updatedAt(record.getUpdatedAt())
                .createdAt(record.getCreatedAt())
                .scheduledDeletionAt(record.getScheduledDeletionAt())
                .build()
            );
        
        // Calculate total pages
        int totalPages = (int) Math.ceil((double) totalItems / pageSize);
        
        Pagination pagination = new Pagination(page, pageSize, totalPages, totalItems);
        return new Paginated<>(data, pagination);
    }

    @Override
    public ApprovedSender upsert(ApprovedSender approvedSender) {
        var record = dsl.insertInto(APPROVED_SENDERS)
            .set(APPROVED_SENDERS.COMPANY_ID, approvedSender.getCompanyId())
            .set(APPROVED_SENDERS.SENDER_IDENTIFIER, approvedSender.getSenderIdentifier())
            .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
            .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
            .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
            .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
            .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
            .doUpdate()
            .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
            .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
            .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
            .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
            .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
            .where(APPROVED_SENDERS.COMPANY_ID.eq(approvedSender.getCompanyId()))
            .returning()
            .fetchSingle();

        return ApprovedSender.builder()
            .id(record.getId())
            .companyId(record.getCompanyId())
            .senderIdentifier(record.getSenderIdentifier())
            .whitelistType(EmailWhitelistValue.fromString(record.getWhitelistType()))
            .status(SenderApprovalStatus.fromValue(record.getStatus()))
            .approvedBy(record.getApprovedBy())
            .approvedAt(record.getApprovedAt())
            .updatedAt(record.getUpdatedAt())
            .createdAt(record.getCreatedAt())
            .scheduledDeletionAt(record.getScheduledDeletionAt())
            .build();
    }

    @Override
    public boolean insertIfNotExists(ApprovedSender approvedSender) {
        // Use ON CONFLICT DO NOTHING - returns number of affected rows
        // 1 = inserted (didn't exist), 0 = already existed (no insert)
        int affectedRows = dsl.insertInto(APPROVED_SENDERS)
            .set(APPROVED_SENDERS.COMPANY_ID, approvedSender.getCompanyId())
            .set(APPROVED_SENDERS.SENDER_IDENTIFIER, approvedSender.getSenderIdentifier())
            .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
            .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
            .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
            .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
            .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
            .doNothing()
            .execute();
        
        return affectedRows > 0; // true if inserted, false if already existed
    }

    @Override
    public List<PendingSenderApproval> findPendingDocumentsGroupedBySender(Long companyId, String assignedEmail) {
        // Query to get attachments with pending approval grouped by sender identifier
        var records = dsl.select(
                APPROVED_SENDERS.SENDER_IDENTIFIER,
                EMAIL_ATTACHMENT.ID.as("attachment_id"),
                EMAIL_ATTACHMENT.FILE_NAME,
                EMAIL_ATTACHMENT.STORAGE_URL,
                EMAIL_MESSAGE.ID.as("message_id"),
                EMAIL_MESSAGE.PROVIDER_TIMESTAMP.as("date_received")
            )
            .from(APPROVED_SENDERS)
            .join(EMAIL_MESSAGE)
                .on(EMAIL_MESSAGE.FROM_ADDRESS.eq(APPROVED_SENDERS.SENDER_IDENTIFIER))
                .and(EMAIL_MESSAGE.TO_ADDRESS.eq(assignedEmail))
            .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
            .join(EMAIL_ATTACHMENT)
                .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
            .and(APPROVED_SENDERS.STATUS.eq(SenderApprovalStatus.PENDING.getValue()))
            .and(APPROVED_SENDERS.WHITELIST_TYPE.eq("email"))
            .orderBy(APPROVED_SENDERS.SENDER_IDENTIFIER, EMAIL_MESSAGE.PROVIDER_TIMESTAMP.desc())
            .fetch();
        
        // Group by sender identifier
        Map<String, List<PendingApprovalDocument>> groupedBySender = new LinkedHashMap<>();
        
        for (Record record : records) {
            String rawSenderIdentifier = record.get(APPROVED_SENDERS.SENDER_IDENTIFIER);
            // Clean the email address for display
            String cleanedSenderIdentifier = EmailUtils.cleanEmailAddress(rawSenderIdentifier);
            
            PendingApprovalDocument doc = PendingApprovalDocument.builder()
                .attachmentId(record.get("attachment_id", String.class))
                .filename(record.get(EMAIL_ATTACHMENT.FILE_NAME))
                .storageKey(record.get(EMAIL_ATTACHMENT.STORAGE_URL))
                .messageId(record.get("message_id", String.class))
                .dateReceived(record.get("date_received", OffsetDateTime.class))
                .build();
            
            groupedBySender.computeIfAbsent(cleanedSenderIdentifier, k -> new ArrayList<>()).add(doc);
        }
        
        // Convert to PendingSenderApproval list
        List<PendingSenderApproval> result = new ArrayList<>();
        for (Map.Entry<String, List<PendingApprovalDocument>> entry : groupedBySender.entrySet()) {
            String senderEmail = entry.getKey();
            
            // Extract domain from email using existing utility
            String domain = EmailPatternMatcher.extractDomain(senderEmail);
            
            // Check if domain is blocked (disposable or personal)
            boolean isBlocked = emailDomainService.isBlockedDomain(domain);
            
            result.add(PendingSenderApproval.builder()
                .senderIdentifier(senderEmail)
                .documentsPending(entry.getValue().size())
                .domainAccessAllowed(!isBlocked)
                .attachments(entry.getValue())
                .build());
        }
        
        return result;
    }

    @Override
    public void delete(String id, Long companyId) {
        int deleted = dsl.deleteFrom(APPROVED_SENDERS)
            .where(APPROVED_SENDERS.ID.eq(id))
            .and(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
            .execute();
        
        if (deleted == 0) {
            throw new NotFoundException("Approved sender not found");
        }
    }
    
    @Override
    public int approveDomainAndRestoreThreads(Long companyId, String domain, String userId) {
        return dsl.transactionResult(configuration -> {
            var txDsl = configuration.dsl();
            
            // 1. Update all email-level entries from this domain to APPROVED and get the updated emails
            String domainPattern = "%@" + domain;
            var updatedEmails = txDsl.update(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.APPROVED.getValue())
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, (OffsetDateTime) null)
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
                .and(APPROVED_SENDERS.WHITELIST_TYPE.eq("email"))
                .and(APPROVED_SENDERS.SENDER_IDENTIFIER.like(domainPattern))
                .returning(APPROVED_SENDERS.SENDER_IDENTIFIER)
                .fetch()
                .map(record -> record.get(APPROVED_SENDERS.SENDER_IDENTIFIER));
            
            // 2. Restore (un-soft-delete) all threads from the updated email addresses
            // Use the exact email addresses from step 1 for efficiency
            int threadsRestored = txDsl.update(EMAIL_THREAD)
                .set(EMAIL_THREAD.DELETED_AT, (OffsetDateTime) null)
                .where(EMAIL_THREAD.ID.in(
                    txDsl.select(EMAIL_THREAD.ID)
                        .from(EMAIL_THREAD)
                        .join(EMAIL_MESSAGE)
                        .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                        .where(EMAIL_MESSAGE.FROM_ADDRESS.in(updatedEmails))
                        .and(EMAIL_THREAD.DELETED_AT.isNotNull())
                        .groupBy(EMAIL_THREAD.ID)
                ))
                .execute();
            
            // 3. Upsert domain-level approval record
            txDsl.insertInto(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.COMPANY_ID, companyId)
                .set(APPROVED_SENDERS.SENDER_IDENTIFIER, domain)
                .set(APPROVED_SENDERS.WHITELIST_TYPE, EmailWhitelistValue.DOMAIN.getValue())
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.APPROVED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, (OffsetDateTime) null)
                .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                .doUpdate()
                .set(APPROVED_SENDERS.WHITELIST_TYPE, EmailWhitelistValue.DOMAIN.getValue())
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.APPROVED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, (OffsetDateTime) null)
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
                .execute();
            
            log.info("AUDIT: Domain approved atomically | company_id={} | domain={} | approved_by={} | emails_updated={} | threads_restored={}", 
                companyId, domain, userId, updatedEmails.size(), threadsRestored);
            
            return updatedEmails.size();
        });
    }
    
    @Override
    public int approveEmailAndRestoreThreads(ApprovedSender approvedSender) {
        return dsl.transactionResult(configuration -> {
            var txDsl = configuration.dsl();
            
            // 1. Upsert the email-level approval record
            txDsl.insertInto(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.COMPANY_ID, approvedSender.getCompanyId())
                .set(APPROVED_SENDERS.SENDER_IDENTIFIER, approvedSender.getSenderIdentifier())
                .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
                .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
                .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                .doUpdate()
                .set(APPROVED_SENDERS.WHITELIST_TYPE, approvedSender.getWhitelistType().getValue())
                .set(APPROVED_SENDERS.STATUS, approvedSender.getStatus().getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, approvedSender.getApprovedBy())
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, approvedSender.getScheduledDeletionAt())
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(approvedSender.getCompanyId()))
                .execute();
            
            // 2. Restore (un-soft-delete) all threads from this sender
            int threadsRestored = txDsl.update(EMAIL_THREAD)
                .set(EMAIL_THREAD.DELETED_AT, (OffsetDateTime) null)
                .where(EMAIL_THREAD.ID.in(
                    txDsl.select(EMAIL_THREAD.ID)
                        .from(EMAIL_THREAD)
                        .join(EMAIL_MESSAGE)
                        .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                        .where(EMAIL_MESSAGE.FROM_ADDRESS.eq(approvedSender.getSenderIdentifier()))
                        .and(EMAIL_THREAD.DELETED_AT.isNotNull())
                        .groupBy(EMAIL_THREAD.ID)
                ))
                .execute();
            
            log.info("AUDIT: Email approved atomically | company_id={} | email={} | approved_by={} | threads_restored={}", 
                approvedSender.getCompanyId(), approvedSender.getSenderIdentifier(), 
                approvedSender.getApprovedBy(), threadsRestored);
            
            return threadsRestored;
        });
    }
    
    @Override
    public int rejectEmailAndSoftDeleteThreads(Long companyId, String senderEmail, String userId, 
                                               OffsetDateTime scheduledDeletionAt) {
        return dsl.transactionResult(configuration -> {
            var txDsl = configuration.dsl();
            
            // 1. Upsert sender with rejected status
            txDsl.insertInto(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.COMPANY_ID, companyId)
                .set(APPROVED_SENDERS.SENDER_IDENTIFIER, senderEmail)
                .set(APPROVED_SENDERS.WHITELIST_TYPE, EmailWhitelistValue.EMAIL.getValue())
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.REJECTED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, scheduledDeletionAt)
                .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                .doUpdate()
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.REJECTED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, scheduledDeletionAt)
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
                .execute();
            
            // 2. Find all thread IDs from this sender that have NO extraction records
            // Only soft-delete threads where extraction was never queued (no record in extraction_task)
            // Use LEFT JOIN to find threads with no extraction records
            var threadIds = txDsl.selectDistinct(EMAIL_THREAD.ID)
                    .from(EMAIL_THREAD)
                    .join(EMAIL_MESSAGE).on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                    .leftJoin(EMAIL_ATTACHMENT).on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
                    .leftJoin(EXTRACTION_TASK).on(EXTRACTION_TASK.ASSIGNED_ID.eq(EMAIL_ATTACHMENT.ASSIGNED_ID))
                    .where(EMAIL_MESSAGE.FROM_ADDRESS.eq(senderEmail))
                    .and(EMAIL_THREAD.DELETED_AT.isNull())
                    .and(EXTRACTION_TASK.ASSIGNED_ID.isNull()) // No extraction record exists
                    .fetch(record -> record.get(EMAIL_THREAD.ID));
            
            if (threadIds.isEmpty()) {
                log.info("No threads found to soft-delete for sender {} (all have successful extractions or none exist)", senderEmail);
                return 0;
            }
            
            // 3. Soft delete threads (only those without successful extractions)
            OffsetDateTime now = OffsetDateTime.now();
            int threadsDeleted = txDsl.update(EMAIL_THREAD)
                    .set(EMAIL_THREAD.DELETED_AT, now)
                    .where(EMAIL_THREAD.ID.in(threadIds))
                    .and(EMAIL_THREAD.DELETED_AT.isNull())
                    .execute();
            
            log.info("AUDIT: Sender rejected atomically | company_id={} | sender={} | rejected_by={} | scheduled_deletion_at={} | threads_deleted={}", 
                    companyId, senderEmail, userId, scheduledDeletionAt, threadsDeleted);
            
            return threadsDeleted;
        });
    }
    
    @Override
    public int rejectDomainAndSoftDeleteThreads(Long companyId, String domain, String userId, 
                                                OffsetDateTime scheduledDeletionAt) {
        return dsl.transactionResult(configuration -> {
            var txDsl = configuration.dsl();
            
            // 1. Update all email-level entries from this domain to REJECTED and get the updated emails
            String domainPattern = "%@" + domain;
            var updatedEmails = txDsl.update(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.REJECTED.getValue())
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, scheduledDeletionAt)
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
                .and(APPROVED_SENDERS.WHITELIST_TYPE.eq("email"))
                .and(APPROVED_SENDERS.SENDER_IDENTIFIER.like(domainPattern))
                .returning(APPROVED_SENDERS.SENDER_IDENTIFIER)
                .fetch()
                .map(record -> record.get(APPROVED_SENDERS.SENDER_IDENTIFIER));
            
            // 2. Upsert domain-level rejection record
            txDsl.insertInto(APPROVED_SENDERS)
                .set(APPROVED_SENDERS.COMPANY_ID, companyId)
                .set(APPROVED_SENDERS.SENDER_IDENTIFIER, domain)
                .set(APPROVED_SENDERS.WHITELIST_TYPE, EmailWhitelistValue.DOMAIN.getValue())
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.REJECTED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, scheduledDeletionAt)
                .onConflict(APPROVED_SENDERS.COMPANY_ID, APPROVED_SENDERS.SENDER_IDENTIFIER)
                .doUpdate()
                .set(APPROVED_SENDERS.STATUS, SenderApprovalStatus.REJECTED.getValue())
                .set(APPROVED_SENDERS.APPROVED_BY, userId)
                .set(APPROVED_SENDERS.SCHEDULED_DELETION_AT, scheduledDeletionAt)
                .set(APPROVED_SENDERS.UPDATED_AT, org.jooq.impl.DSL.currentOffsetDateTime())
                .where(APPROVED_SENDERS.COMPANY_ID.eq(companyId))
                .execute();
            
            // 3. Find all thread IDs from the updated email addresses that have NO extraction records
            // Only soft-delete threads where extraction was never queued (no record in extraction_task)
            // Use the exact email addresses from step 1 for efficiency
            var threadIds = txDsl.selectDistinct(EMAIL_THREAD.ID)
                    .from(EMAIL_THREAD)
                    .join(EMAIL_MESSAGE).on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                    .leftJoin(EMAIL_ATTACHMENT).on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
                    .leftJoin(EXTRACTION_TASK).on(EXTRACTION_TASK.ASSIGNED_ID.eq(EMAIL_ATTACHMENT.ASSIGNED_ID))
                    .where(EMAIL_MESSAGE.FROM_ADDRESS.in(updatedEmails))
                    .and(EMAIL_THREAD.DELETED_AT.isNull())
                    .and(EXTRACTION_TASK.ASSIGNED_ID.isNull()) // No extraction record exists
                    .fetch(record -> record.get(EMAIL_THREAD.ID));
            
            if (threadIds.isEmpty()) {
                log.info("No threads found to soft-delete for domain {} (all have successful extractions or none exist)", domain);
                return 0;
            }
            
            // 3. Soft delete threads (only those without successful extractions)
            OffsetDateTime now = OffsetDateTime.now();
            int threadsDeleted = txDsl.update(EMAIL_THREAD)
                    .set(EMAIL_THREAD.DELETED_AT, now)
                    .where(EMAIL_THREAD.ID.in(threadIds))
                    .and(EMAIL_THREAD.DELETED_AT.isNull())
                    .execute();
            
            log.info("AUDIT: Domain rejected atomically | company_id={} | domain={} | rejected_by={} | scheduled_deletion_at={} | emails_updated={} | threads_deleted={}", 
                    companyId, domain, userId, scheduledDeletionAt, updatedEmails.size(), threadsDeleted);
            
            return threadsDeleted;
        });
    }
}

