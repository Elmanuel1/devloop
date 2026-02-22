package com.tosspaper.precon;

import com.tosspaper.generated.model.TenderContentType;
import com.tosspaper.generated.model.TenderDocument;
import com.tosspaper.generated.model.TenderDocumentStatus;
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenderDocumentMapper {

    public TenderDocument toDto(TenderDocumentsRecord record) {
        TenderDocument doc = new TenderDocument();
        doc.setId(UUID.fromString(record.getId()));
        doc.setTenderId(UUID.fromString(record.getTenderId()));
        doc.setFileName(record.getFileName());
        doc.setFileSize(record.getFileSize());
        doc.setS3Key(record.getS3Key());
        doc.setCreatedAt(record.getCreatedAt());
        doc.setUpdatedAt(record.getUpdatedAt());
        doc.setUploadedAt(record.getUploadedAt());
        doc.setErrorReason(record.getErrorReason());

        // Parse content type
        if (record.getContentType() != null) {
            try {
                doc.setContentType(TenderContentType.fromValue(record.getContentType()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown content type: {}", record.getContentType());
            }
        }

        // Parse status
        if (record.getStatus() != null) {
            try {
                doc.setStatus(TenderDocumentStatus.fromValue(record.getStatus()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown document status: {}", record.getStatus());
            }
        }

        return doc;
    }

    public List<TenderDocument> toDtoList(List<TenderDocumentsRecord> records) {
        return records.stream().map(this::toDto).toList();
    }
}
