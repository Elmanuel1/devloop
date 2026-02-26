package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

import java.util.List;
import java.util.Optional;

public interface ExtractionRepository {

    ExtractionsRecord insert(ExtractionsRecord record);

    Optional<ExtractionsRecord> findById(String id);

    List<ExtractionsRecord> findByEntityId(String entityId, ExtractionQuery query);

    int updateStatus(String id, String status);

    /**
     * Atomically increments version where version = expectedVersion.
     * Returns number of rows updated (0 = stale version).
     */
    int incrementVersion(String id, int expectedVersion);

    int softDelete(String id);
}
