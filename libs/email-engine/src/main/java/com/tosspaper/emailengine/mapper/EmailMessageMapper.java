package com.tosspaper.emailengine.mapper;

import com.tosspaper.models.jooq.tables.records.EmailMessageRecord;
import com.tosspaper.models.domain.EmailMessage;
import com.tosspaper.models.enums.MessageDirection;
import com.tosspaper.models.enums.MessageStatus;
import lombok.experimental.UtilityClass;

import java.util.Locale;

/**
 * Mapper utility for converting between EmailMessage JOOQ records and domain models.
 */
@UtilityClass
public class EmailMessageMapper {

    /**
     * Map JOOQ EmailMessageRecord to domain EmailMessage
     *
     * @param record JOOQ record
     * @return Domain model
     */
    public static EmailMessage toDomain(EmailMessageRecord record) {
        if (record == null) {
            return null;
        }

        return EmailMessage.builder()
            .id(record.getId())
            .threadId(record.getThreadId())
            .provider(record.getProvider())
            .providerMessageId(record.getProviderMessageId())
            .inReplyTo(record.getInReplyTo())
            .fromAddress(record.getFromAddress())
            .toAddress(record.getToAddress())
            .cc(record.getCc())
            .bcc(record.getBcc())
            .bodyText(record.getBodyText())
            .bodyHtml(record.getBodyHtml())
            .headers(record.getHeaders() != null ? record.getHeaders().data() : null)
            .direction(record.getDirection() != null ? MessageDirection.valueOf(record.getDirection().toUpperCase(Locale.ROOT)) : null)
            .status(record.getStatus() != null ? MessageStatus.valueOf(record.getStatus().toUpperCase(Locale.ROOT)) : null)
            .providerTimestamp(record.getProviderTimestamp())
            .createdAt(record.getCreatedAt())
            .attachmentsCount(record.getAttachmentsCount() != null ? record.getAttachmentsCount() : 0)
            .build();
    }

    /**
     * Update JOOQ record from domain model
     *
     * @param record JOOQ record to update
     * @param domain Domain model
     */
    public static void updateRecord(EmailMessageRecord record, EmailMessage domain) {
        if (record == null || domain == null) {
            return;
        }

        if (domain.getId() != null) {
            record.setId(domain.getId());
        }
        if (domain.getThreadId() != null) {
            record.setThreadId(domain.getThreadId());
        }
        if (domain.getProvider() != null) {
            record.setProvider(domain.getProvider());
        }
        if (domain.getProviderMessageId() != null) {
            record.setProviderMessageId(domain.getProviderMessageId());
        }
        if (domain.getInReplyTo() != null) {
            record.setInReplyTo(domain.getInReplyTo());
        }
        if (domain.getFromAddress() != null) {
            record.setFromAddress(domain.getFromAddress());
        }
        if (domain.getToAddress() != null) {
            record.setToAddress(domain.getToAddress());
        }
        if (domain.getCc() != null) {
            record.setCc(domain.getCc());
        }
        if (domain.getBcc() != null) {
            record.setBcc(domain.getBcc());
        }
        if (domain.getBodyText() != null) {
            record.setBodyText(domain.getBodyText());
        }
        if (domain.getBodyHtml() != null) {
            record.setBodyHtml(domain.getBodyHtml());
        }
        if (domain.getHeaders() != null) {
            record.setHeaders(org.jooq.JSONB.jsonb(domain.getHeaders()));
        }
        if (domain.getDirection() != null) {
            record.setDirection(domain.getDirection().name().toLowerCase());
        }
        if (domain.getStatus() != null) {
            record.setStatus(domain.getStatus().name().toLowerCase());
        }
        if (domain.getProviderTimestamp() != null) {
            record.setProviderTimestamp(domain.getProviderTimestamp());
        }
        if (domain.getCreatedAt() != null) {
            record.setCreatedAt(domain.getCreatedAt());
        }
        if (domain.getAttachmentsCount() > 0) {
            record.setAttachmentsCount(domain.getAttachmentsCount());
        }
    }

    /**
     * Create new JOOQ record from domain model
     *
     * @param domain Domain model
     * @return New JOOQ record
     */
    public static EmailMessageRecord toRecord(EmailMessage domain) {
        if (domain == null) {
            return null;
        }

        EmailMessageRecord record = new EmailMessageRecord();
        updateRecord(record, domain);
        return record;
    }
}

