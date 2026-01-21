package com.tosspaper.emailengine.repository.impl;

import com.tosspaper.models.exception.DuplicateException;
import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.paging.Paginated;
import com.tosspaper.models.paging.Pagination;
import com.tosspaper.models.query.ReceivedMessageQuery;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.domain.EmailThread;
import com.tosspaper.models.enums.MessageDirection;
import com.tosspaper.models.enums.MessageStatus;
import com.tosspaper.emailengine.repository.EmailMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.tosspaper.models.jooq.Tables.EMAIL_ATTACHMENT;
import static com.tosspaper.models.jooq.Tables.EMAIL_MESSAGE;
import static com.tosspaper.models.jooq.Tables.EMAIL_THREAD;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PostgresEmailMessageRepositoryImpl implements EmailMessageRepository {

    private final DSLContext dsl;


    @Override
    public EmailMessage findById(UUID id) {
        log.debug("Finding email message by id: {}", id);
        
        var record = dsl.select(EMAIL_MESSAGE.asterisk())
                .from(EMAIL_MESSAGE)
                .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .where(EMAIL_MESSAGE.ID.eq(id))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException("Email message not found with id: " + id));
                
        return mapToEmailMessage(record.into(EMAIL_MESSAGE));
    }

    @Override
    public Optional<EmailMessage> findByProviderMessageId(String provider, String providerMessageId) {
        log.debug("Finding email message by provider: {} and providerMessageId: {}", provider, providerMessageId);

        return dsl.select(EMAIL_MESSAGE.asterisk())
                .from(EMAIL_MESSAGE)
                .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .where(EMAIL_MESSAGE.PROVIDER.eq(provider))
                .and(EMAIL_MESSAGE.PROVIDER_MESSAGE_ID.eq(providerMessageId))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOptional()
                .map(record -> mapToEmailMessage(record.into(EMAIL_MESSAGE)));
    }

    @Override
    public Optional<EmailMessage> findByAttachmentId(String attachmentAssignedId) {
        log.debug("Finding email message by attachment assigned ID: {}", attachmentAssignedId);

        return dsl.select(EMAIL_MESSAGE.asterisk())
                .from(EMAIL_ATTACHMENT)
                .join(EMAIL_MESSAGE)
                .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
                .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .where(EMAIL_ATTACHMENT.ASSIGNED_ID.eq(attachmentAssignedId))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOptional()
                .map(record -> mapToEmailMessage(record.into(EMAIL_MESSAGE)));
    }


    @Override
    public EmailMessage save(EmailMessage message) {
        log.debug("Saving email message");
        try {   
        
        var record = dsl.insertInto(EMAIL_MESSAGE)
            .set(EMAIL_MESSAGE.THREAD_ID, message.getThreadId())
            .set(EMAIL_MESSAGE.COMPANY_ID, message.getCompanyId())
            .set(EMAIL_MESSAGE.PROVIDER, message.getProvider())
            .set(EMAIL_MESSAGE.PROVIDER_MESSAGE_ID, message.getProviderMessageId())
            .set(EMAIL_MESSAGE.IN_REPLY_TO, message.getInReplyTo())
            .set(EMAIL_MESSAGE.FROM_ADDRESS, message.getFromAddress())
            .set(EMAIL_MESSAGE.TO_ADDRESS, message.getToAddress())
            .set(EMAIL_MESSAGE.CC, message.getCc())
            .set(EMAIL_MESSAGE.BCC, message.getBcc())
            .set(EMAIL_MESSAGE.SUBJECT, message.getSubject())
            .set(EMAIL_MESSAGE.BODY_TEXT, message.getBodyText())
            .set(EMAIL_MESSAGE.BODY_HTML, message.getBodyHtml())
            .set(EMAIL_MESSAGE.HEADERS, message.getHeaders() != null ? org.jooq.JSONB.valueOf(message.getHeaders()) : null)
            .set(EMAIL_MESSAGE.DIRECTION, message.getDirection().getValue())
            .set(EMAIL_MESSAGE.STATUS, message.getStatus().getValue())
            .set(EMAIL_MESSAGE.PROVIDER_TIMESTAMP, message.getProviderTimestamp())
            .returningResult(EMAIL_MESSAGE.asterisk())
            .fetchSingle();

        var messageRecord = record.into(EMAIL_MESSAGE);
        log.debug("Created new email message with id: {}", messageRecord.getId());
        
            return mapToEmailMessage(messageRecord);
        } catch (DuplicateKeyException e) {
           throw new DuplicateException(e);
        }
    }

    @Override
    public EmailMessage saveThreadAndMessage(EmailThread thread, EmailMessage message) {
        log.debug("Saving thread and message atomically");
        
        return dsl.transactionResult(configuration -> {
            DSLContext txDsl = configuration.dsl();
            
            // First, save the thread with ON CONFLICT to handle duplicate webhooks
            var savedThreadRecord = txDsl.insertInto(EMAIL_THREAD)
                .set(EMAIL_THREAD.SUBJECT, thread.getSubject())
                .set(EMAIL_THREAD.PROVIDER, thread.getProvider())
                .set(EMAIL_THREAD.PROVIDER_THREAD_ID, thread.getProviderThreadId())
                .set(EMAIL_THREAD.MESSAGE_COUNT, thread.getMessageCount())
                .onConflict(EMAIL_THREAD.PROVIDER, EMAIL_THREAD.PROVIDER_THREAD_ID)
                .doUpdate()
                .set(EMAIL_THREAD.LAST_UPDATED_AT, java.time.OffsetDateTime.now())
                .where(EMAIL_THREAD.PROVIDER.eq(thread.getProvider()))
                .and(EMAIL_THREAD.PROVIDER_THREAD_ID.eq(thread.getProviderThreadId()))
                .returningResult(EMAIL_THREAD.asterisk())
                .fetchSingle()
                .into(EMAIL_THREAD);
                
            log.debug("Upserted email thread with id: {}", savedThreadRecord.getId());
            
            // Then, save the message with the thread ID and ON CONFLICT to handle duplicate webhooks
            var savedMessageRecord = txDsl.insertInto(EMAIL_MESSAGE)
                .set(EMAIL_MESSAGE.THREAD_ID, savedThreadRecord.getId())
                .set(EMAIL_MESSAGE.COMPANY_ID, message.getCompanyId())
                .set(EMAIL_MESSAGE.PROVIDER, message.getProvider())
                .set(EMAIL_MESSAGE.PROVIDER_MESSAGE_ID, message.getProviderMessageId())
                .set(EMAIL_MESSAGE.IN_REPLY_TO, message.getInReplyTo())
                .set(EMAIL_MESSAGE.FROM_ADDRESS, message.getFromAddress())
                .set(EMAIL_MESSAGE.TO_ADDRESS, message.getToAddress())
                .set(EMAIL_MESSAGE.CC, message.getCc())
                .set(EMAIL_MESSAGE.BCC, message.getBcc())
                .set(EMAIL_MESSAGE.SUBJECT, message.getSubject())
                .set(EMAIL_MESSAGE.BODY_TEXT, message.getBodyText())
                .set(EMAIL_MESSAGE.BODY_HTML, message.getBodyHtml())
                .set(EMAIL_MESSAGE.HEADERS, message.getHeaders() != null ? org.jooq.JSONB.valueOf(message.getHeaders()) : null)
                .set(EMAIL_MESSAGE.DIRECTION, message.getDirection().getValue())
                .set(EMAIL_MESSAGE.STATUS, message.getStatus().getValue())
                .set(EMAIL_MESSAGE.PROVIDER_TIMESTAMP, message.getProviderTimestamp())
                .onConflict(EMAIL_MESSAGE.PROVIDER, EMAIL_MESSAGE.PROVIDER_MESSAGE_ID)
                .doNothing()
                .returningResult(EMAIL_MESSAGE.asterisk())
                .fetchSingle()
                .into(EMAIL_MESSAGE);
                
            log.debug("Upserted email message with id: {} for thread: {}", savedMessageRecord.getId(), savedThreadRecord.getId());
            
            return mapToEmailMessage(savedMessageRecord);
        });
    }
    
    private EmailMessage mapToEmailMessage(com.tosspaper.models.jooq.tables.records.EmailMessageRecord record) {
        return EmailMessage.builder()
            .id(record.getId())
            .threadId(record.getThreadId())
            .companyId(record.getCompanyId())
            .provider(record.getProvider())
            .providerMessageId(record.getProviderMessageId())
            .inReplyTo(record.getInReplyTo())
            .fromAddress(record.getFromAddress())
            .toAddress(record.getToAddress())
            .cc(record.getCc())
            .bcc(record.getBcc())
            .subject(record.getSubject())
            .bodyText(record.getBodyText())
            .bodyHtml(record.getBodyHtml())
            .headers(record.getHeaders() != null ? record.getHeaders().toString() : null)
            .direction(MessageDirection.fromValue(record.getDirection()))
            .status(MessageStatus.fromValue(record.getStatus()))
            .providerTimestamp(record.getProviderTimestamp())
            .attachmentsCount(record.getAttachmentsCount() != null ? record.getAttachmentsCount() : 0)
            .createdAt(record.getCreatedAt())
            .build();
    }

    @Override
    public Paginated<EmailMessage> findByQuery(ReceivedMessageQuery query) {
        log.debug("Finding email messages with query: {}", query);
        
        var conditions = new java.util.ArrayList<org.jooq.Condition>();
        
        // Date range filters
        if (query.getCreatedDateFrom() != null) {
            conditions.add(EMAIL_MESSAGE.PROVIDER_TIMESTAMP.greaterOrEqual(query.getCreatedDateFrom()));
        }
        if (query.getCreatedDateTo() != null) {
            conditions.add(EMAIL_MESSAGE.PROVIDER_TIMESTAMP.lessOrEqual(query.getCreatedDateTo()));
        }
        
        // Assigned email filter
        if (query.getAssignedEmail() != null) {
            conditions.add(EMAIL_MESSAGE.TO_ADDRESS.eq(query.getAssignedEmail()));
        }
        
        // Status filter
        if (query.getStatus() != null) {
            conditions.add(EMAIL_MESSAGE.STATUS.eq(query.getStatus()));
        }
        
        // From email filter (exact match after cleaning)
        if (query.getFromEmail() != null && !query.getFromEmail().trim().isEmpty()) {
            String cleanedEmail = com.tosspaper.models.utils.EmailUtils.cleanEmailAddress(query.getFromEmail());
            conditions.add(EMAIL_MESSAGE.FROM_ADDRESS.eq(cleanedEmail));
        }
        
        // Full-text search filter
        if (query.getSearch() != null && !query.getSearch().trim().isEmpty()) {
            String prefixQuery = com.tosspaper.models.utils.PostgresSearchUtils.buildPrefixQuery(query.getSearch());
            
            if (!prefixQuery.isEmpty()) {
                conditions.add(org.jooq.impl.DSL.condition(
                    "search_vector @@ to_tsquery('english', {0})", 
                    prefixQuery
                ));
            }
        }
        
        // Calculate offset
        int offset = (query.getPage() - 1) * query.getPageSize();
        
        // Fetch data with thread join for soft delete check
        var messages = dsl.select(EMAIL_MESSAGE.asterisk())
                .from(EMAIL_MESSAGE)
                .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .where(conditions)
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .orderBy(EMAIL_MESSAGE.PROVIDER_TIMESTAMP.desc())
                .limit(query.getPageSize())
                .offset(offset)
                .fetch()
                .map(record -> mapToEmailMessage(record.into(EMAIL_MESSAGE)));
        
        // Count total with thread join
        int totalItems = dsl.selectCount()
                .from(EMAIL_MESSAGE)
                .join(EMAIL_THREAD)
                .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
                .where(conditions)
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOne(0, int.class);
        
        int totalPages = (int) Math.ceil((double) totalItems / query.getPageSize());
        
        log.debug("Retrieved {} messages (page {}/{}, total: {})", 
                messages.size(), query.getPage(), totalPages, totalItems);
        
        var pagination = new Pagination(
                query.getPage(),
                query.getPageSize(),
                totalPages,
                totalItems
        );
        
        return new Paginated<>(messages, pagination);
    }
    
    @Override
    public boolean updateStatus(UUID messageId, MessageStatus expectedStatus, MessageStatus newStatus) {
        log.debug("Updating message status for id={} from expectedStatus={} to newStatus={}", 
                  messageId, expectedStatus, newStatus);
        
        var updateStep = dsl.update(EMAIL_MESSAGE)
                .set(EMAIL_MESSAGE.STATUS, newStatus.getValue())
                .where(EMAIL_MESSAGE.ID.eq(messageId));
        
        // Add conditional check if expectedStatus is provided
        if (expectedStatus != null) {
            updateStep = updateStep.and(EMAIL_MESSAGE.STATUS.eq(expectedStatus.getValue()));
        }
        
        int updated = updateStep.execute();
        
        if (updated == 0 && expectedStatus != null) {
            log.debug("Failed to update status for id={} - status was not {}", messageId, expectedStatus);
        }
        
        return updated > 0;
    }
    
    @Override
    public void delete(UUID messageId) {
        int deleted = dsl.deleteFrom(EMAIL_MESSAGE)
            .where(EMAIL_MESSAGE.ID.eq(messageId))
            .execute();
        
        log.debug("Deleted {} message(s) with id {}", deleted, messageId);
    }
}
