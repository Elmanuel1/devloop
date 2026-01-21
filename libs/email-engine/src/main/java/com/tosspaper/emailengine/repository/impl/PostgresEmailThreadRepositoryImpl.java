package com.tosspaper.emailengine.repository.impl;

import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.domain.EmailThread;
import com.tosspaper.emailengine.repository.EmailThreadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.tosspaper.models.jooq.Tables.EMAIL_THREAD;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PostgresEmailThreadRepositoryImpl implements EmailThreadRepository {

    private final DSLContext dsl;

    @Override
    public EmailThread findById(UUID id) {
        var record = dsl.selectFrom(EMAIL_THREAD)
                .where(EMAIL_THREAD.ID.eq(id))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOptional()
                .orElseThrow(() -> new NotFoundException("Email thread not found with id: " + id));
                
        return mapToEmailThread(record.into(EMAIL_THREAD));
    }

    @Override
    public Optional<EmailThread> findByProviderThreadId(String provider, String providerThreadId) {
        log.debug("Finding email thread by provider: {} and providerThreadId: {}", provider, providerThreadId);

        return dsl.selectFrom(EMAIL_THREAD)
                .where(EMAIL_THREAD.PROVIDER.eq(provider))
                .and(EMAIL_THREAD.PROVIDER_THREAD_ID.eq(providerThreadId))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .fetchOptional()
                .map(record -> mapToEmailThread(record.into(EMAIL_THREAD)));
    }

    @Override
    public void delete(UUID threadId) {
        int deleted = dsl.deleteFrom(EMAIL_THREAD)
                .where(EMAIL_THREAD.ID.eq(threadId))
                .execute();
        
        log.debug("Deleted {} thread(s) with id {}", deleted, threadId);
    }

    @Override
    public void softDelete(UUID threadId, OffsetDateTime deletedAt) {
        int updated = dsl.update(EMAIL_THREAD)
                .set(EMAIL_THREAD.DELETED_AT, deletedAt)
                .where(EMAIL_THREAD.ID.eq(threadId))
                .and(EMAIL_THREAD.DELETED_AT.isNull())
                .execute();
        
        log.debug("Soft deleted {} thread(s) with id {}", updated, threadId);
    }
    
    private EmailThread mapToEmailThread(com.tosspaper.models.jooq.tables.records.EmailThreadRecord record) {
        return EmailThread.builder()
            .id(record.getId())
            .subject(record.getSubject())
            .provider(record.getProvider())
            .providerThreadId(record.getProviderThreadId())
            .messageCount(record.getMessageCount())
            .lastUpdatedAt(record.getLastUpdatedAt())
            .createdAt(record.getCreatedAt())
            .deletedAt(record.getDeletedAt())
            .build();
    }
    
}
