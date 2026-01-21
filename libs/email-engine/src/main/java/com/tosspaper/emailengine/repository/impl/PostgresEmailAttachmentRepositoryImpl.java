package com.tosspaper.emailengine.repository.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tosspaper.models.exception.NotFoundException;
import com.tosspaper.models.domain.EmailAttachment;
import com.tosspaper.models.domain.AttachmentStatus;
import com.tosspaper.emailengine.repository.EmailAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static com.tosspaper.models.jooq.Tables.EMAIL_ATTACHMENT;
import static com.tosspaper.models.jooq.Tables.EMAIL_MESSAGE;
import static com.tosspaper.models.jooq.Tables.EMAIL_THREAD;

@Slf4j
@Repository
@RequiredArgsConstructor
public class PostgresEmailAttachmentRepositoryImpl implements EmailAttachmentRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public List<EmailAttachment> saveAll(List<EmailAttachment> attachments) {
        log.debug("Saving {} email attachments", attachments.size());
        
        return attachments.stream()
                .map(this::save)
                .toList();
    }
    
    private EmailAttachment save(EmailAttachment attachment) {
        log.debug("Saving email attachment");
        
        var record = dsl.insertInto(EMAIL_ATTACHMENT)
            .set(EMAIL_ATTACHMENT.MESSAGE_ID, attachment.getMessageId())
            .set(EMAIL_ATTACHMENT.ASSIGNED_ID, attachment.getAssignedId())
            .set(EMAIL_ATTACHMENT.FILE_NAME, attachment.getFileName())
            .set(EMAIL_ATTACHMENT.CONTENT_TYPE, attachment.getContentType())
            .set(EMAIL_ATTACHMENT.SIZE_BYTES, attachment.getSizeBytes())
            .set(EMAIL_ATTACHMENT.STORAGE_URL, attachment.getStorageUrl())
            .set(EMAIL_ATTACHMENT.LOCAL_FILE_PATH, attachment.getLocalFilePath())
            .set(EMAIL_ATTACHMENT.CHECKSUM, attachment.getChecksum())
            .set(EMAIL_ATTACHMENT.STATUS, attachment.getStatus().getValue())
                .returningResult(EMAIL_ATTACHMENT.asterisk())
            .fetchSingle();
        
        var attachmentRecord = record.into(EMAIL_ATTACHMENT);
        
        return mapToEmailAttachment(attachmentRecord);
    }
    
    @SneakyThrows
    private EmailAttachment mapToEmailAttachment(com.tosspaper.models.jooq.tables.records.EmailAttachmentRecord record) {
        Map<String, String> metadata = null;
        if (record.getMetadata() != null) {
                metadata = objectMapper.readValue(record.getMetadata().data(), new TypeReference<Map<String, String>>() {});
        }

        return EmailAttachment.builder()
            .id(record.getId())
            .messageId(record.getMessageId())
            .assignedId(record.getAssignedId())
            .fileName(record.getFileName())
            .contentType(record.getContentType())
            .sizeBytes(record.getSizeBytes())
            .storageUrl(record.getStorageUrl())
            .localFilePath(record.getLocalFilePath())
            .checksum(record.getChecksum())
            .status(AttachmentStatus.fromValue(record.getStatus()))
            .attempts(record.getAttempts())
            .region(record.getRegion())
            .endpoint(record.getEndpoint())
            .metadata(metadata)
            .createdAt(record.getCreatedAt())
            .build();
    }
    
    @Override
    public EmailAttachment updateStatusToProcessing(String assignedId) {
        var result = dsl.update(EMAIL_ATTACHMENT)
            .set(EMAIL_ATTACHMENT.STATUS, "processing")
            .set(EMAIL_ATTACHMENT.ATTEMPTS, EMAIL_ATTACHMENT.ATTEMPTS.plus(1))
            .where(EMAIL_ATTACHMENT.ASSIGNED_ID.eq(assignedId))
            .returning(EMAIL_ATTACHMENT.asterisk())
                .fetchSingle();
        return mapToEmailAttachment(result);
    }
    
    @Override
    public List<EmailAttachment> findByStatus(AttachmentStatus status) {
        log.debug("Finding attachments with status: {}", status);
        
        return dsl.select(EMAIL_ATTACHMENT.asterisk())
            .from(EMAIL_ATTACHMENT)
            .join(EMAIL_MESSAGE)
            .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .join(EMAIL_THREAD)
            .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
            .where(EMAIL_ATTACHMENT.STATUS.eq(status.getValue()))
            .and(EMAIL_THREAD.DELETED_AT.isNull())
            .fetch()
            .map(record -> mapToEmailAttachment(record.into(EMAIL_ATTACHMENT)));
    }
    
    @Override
    public Optional<EmailAttachment> findByAssignedId(String assignedId) {
        log.debug("Finding attachment with assignedId: {}", assignedId);
        
        return dsl.select(EMAIL_ATTACHMENT.asterisk())
            .from(EMAIL_ATTACHMENT)
            .join(EMAIL_MESSAGE)
            .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .join(EMAIL_THREAD)
            .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
            .where(EMAIL_ATTACHMENT.ASSIGNED_ID.eq(assignedId))
            .and(EMAIL_THREAD.DELETED_AT.isNull())
            .fetchOptional()
            .map(record -> mapToEmailAttachment(record.into(EMAIL_ATTACHMENT)));
    }
    
    @Override
    public void updateStatus(EmailAttachment attachment, AttachmentStatus expectedStatus) {
        log.debug("Updating attachment {} status to {} with lastUpdatedAt", 
                attachment.getAssignedId(), expectedStatus);
        
        try {
            String metadataJson = attachment.getMetadata() != null ? 
                objectMapper.writeValueAsString(attachment.getMetadata()) : null;
            
            int updatedRows = dsl.update(EMAIL_ATTACHMENT)
                .set(EMAIL_ATTACHMENT.STATUS, attachment.getStatus().getValue())
                .set(EMAIL_ATTACHMENT.STORAGE_URL, attachment.getStorageUrl())
                .set(EMAIL_ATTACHMENT.REGION, attachment.getRegion())
                .set(EMAIL_ATTACHMENT.ENDPOINT, attachment.getEndpoint())
                .set(EMAIL_ATTACHMENT.METADATA, JSONB.jsonb(metadataJson))
                .set(EMAIL_ATTACHMENT.LAST_UPDATED_AT, OffsetDateTime.now())
                .set(EMAIL_ATTACHMENT.ATTEMPTS, attachment.getAttempts())
                .where(EMAIL_ATTACHMENT.ASSIGNED_ID.eq(attachment.getAssignedId())
                    .and(EMAIL_ATTACHMENT.STATUS.eq(expectedStatus.getValue())))
                .execute();
            
            if (updatedRows == 0) {
                throw new NotFoundException("Attachment not found with assigned id" + attachment.getAssignedId() );
            }
        } catch (JsonProcessingException e) {
            log.error("Error serializing metadata for attachment {} update", attachment.getAssignedId(), e);
            throw new RuntimeException("Failed to update attachment metadata", e);
        }
    }
    
    @Override
    public List<EmailAttachment> findByMessageId(UUID messageId) {
        log.debug("Finding attachments for message: {}", messageId);
        
        return dsl.select(EMAIL_ATTACHMENT.asterisk())
            .from(EMAIL_ATTACHMENT)
            .join(EMAIL_MESSAGE)
            .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .join(EMAIL_THREAD)
            .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
            .where(EMAIL_ATTACHMENT.MESSAGE_ID.eq(messageId))
            .and(EMAIL_THREAD.DELETED_AT.isNull())
            .fetch()
            .map(record -> mapToEmailAttachment(record.into(EMAIL_ATTACHMENT)));
    }
    
    @Override
    public List<EmailAttachment> findByDomain(String domain) {
        log.debug("Finding attachments for domain: {}", domain);
        
        String domainPattern = "%@" + domain;
        
        return dsl.select(EMAIL_ATTACHMENT.asterisk())
            .from(EMAIL_ATTACHMENT)
            .join(EMAIL_MESSAGE)
            .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .join(EMAIL_THREAD)
            .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
            .where(EMAIL_MESSAGE.FROM_ADDRESS.like(domainPattern))
            .and(EMAIL_THREAD.DELETED_AT.isNull())
            .fetch()
            .map(record -> mapToEmailAttachment(record.into(EMAIL_ATTACHMENT)));
    }
    
    @Override
    public List<EmailAttachment> findByEmail(String email) {
        log.debug("Finding attachments for email: {}", email);
        
        return dsl.select(EMAIL_ATTACHMENT.asterisk())
            .from(EMAIL_ATTACHMENT)
            .join(EMAIL_MESSAGE)
            .on(EMAIL_ATTACHMENT.MESSAGE_ID.eq(EMAIL_MESSAGE.ID))
            .join(EMAIL_THREAD)
            .on(EMAIL_MESSAGE.THREAD_ID.eq(EMAIL_THREAD.ID))
            .where(EMAIL_MESSAGE.FROM_ADDRESS.eq(email))
            .and(EMAIL_THREAD.DELETED_AT.isNull())
            .fetch()
            .map(record -> mapToEmailAttachment(record.into(EMAIL_ATTACHMENT)));
    }
}
