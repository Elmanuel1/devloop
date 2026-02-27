package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;

import java.util.List;

public interface ExtractionFieldRepository {

    List<ExtractionFieldsRecord> findByExtractionId(ExtractionFieldQuery query);

    ExtractionFieldsRecord findById(String id);

    List<ExtractionFieldsRecord> findAllByIds(List<String> ids);

    int deleteByExtractionId(String extractionId);

    /**
     * Updates each field's edited_value (returns updated records via RETURNING),
     * then atomically increments the parent extraction's version with an
     * optimistic-lock check.
     */
    BulkUpdateResult bulkUpdateEditedValues(List<FieldEditUpdate> updates, String extractionId, int expectedVersion);
}
