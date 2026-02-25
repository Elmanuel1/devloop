package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;

import java.util.List;
import java.util.Optional;

public interface TenderDocumentRepository {

    TenderDocumentsRecord insert(String id, String tenderId, String companyId, String fileName,
                                  String contentType, long fileSize, String s3Key, String status);

    TenderDocumentsRecord findById(String id);

    List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit, String cursorCreatedAt, String cursorId);

    int softDelete(String id);

    int updateStatusToProcessing(String id);

    int updateStatusToReady(String id);

    int updateStatusToFailed(String id, String errorReason);

    Optional<TenderDocumentsRecord> findByS3Key(String s3Key);
}
