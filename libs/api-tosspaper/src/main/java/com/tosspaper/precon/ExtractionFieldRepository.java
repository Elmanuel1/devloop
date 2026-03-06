package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;
import org.jooq.JSONB;

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

    /**
     * Returns all {@code extraction_fields} rows for a given extraction,
     * ordered by creation time ascending. Used by conflict detection which
     * must see every document's contribution to each field.
     *
     * @param extractionId the extraction to fetch fields for
     * @return all rows for the extraction, never null
     */
    List<ExtractionFieldsRecord> findAllByExtractionId(String extractionId);

    /**
     * Marks all {@code extraction_fields} rows for a given field name within an
     * extraction as conflicted and populates the {@code competing_values} JSONB
     * column.
     *
     * <p>This is a batch UPDATE — all rows sharing {@code field_name} under
     * the given {@code extractionId} are updated in a single statement.
     *
     * @param extractionId    the extraction that owns the fields
     * @param fieldName       the field name to flag
     * @param competingValues JSONB array of {@code {field_id, value, confidence}}
     * @return number of rows updated
     */
    int markConflict(String extractionId, String fieldName, JSONB competingValues);
}
