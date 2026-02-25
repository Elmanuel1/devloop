package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface TenderDocumentRepository {

    TenderDocumentsRecord insert(TenderDocumentsRecord record);

    Optional<TenderDocumentsRecord> findById(String id);

    List<TenderDocumentsRecord> findByTenderId(String tenderId, String status, int limit, OffsetDateTime cursorCreatedAt, String cursorId);

    int softDelete(String id);

    int updateStatusToProcessing(String id);

    int updateStatusToReady(String id);

    int updateStatusToFailed(String id, String errorReason);

}
