package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;

import java.util.List;
import java.util.Optional;

public interface TenderDocumentRepository {

    TenderDocumentsRecord insert(String id, String tenderId, String companyId, String fileName,
                                  String contentType, long fileSize, String s3Key, String status);

    Optional<TenderDocumentsRecord> findById(String id);

    List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit, String cursorCreatedAt, String cursorId);

    int softDelete(String id);
}
