package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord;

import java.util.List;

public interface ExtractionRepository {

    ExtractionsRecord insert(ExtractionsRecord record);

    ExtractionsRecord findById(String id);

    List<ExtractionsRecord> findByEntityId(String companyId, String entityId, ExtractionQuery query);

    /**
     * Updates status and increments version atomically.
     */
    int updateStatus(String id, String status);

    int softDelete(String id);
}
