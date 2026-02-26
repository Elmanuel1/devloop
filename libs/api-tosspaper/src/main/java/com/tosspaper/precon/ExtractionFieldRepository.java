package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;

import java.util.List;
import java.util.Optional;

public interface ExtractionFieldRepository {

    List<ExtractionFieldsRecord> findByExtractionId(ExtractionFieldQuery query);

    Optional<ExtractionFieldsRecord> findById(String id);

    List<ExtractionFieldsRecord> findAllByIds(List<String> ids);

    int updateEditedValue(String id, org.jooq.JSONB editedValue);

    int deleteByExtractionId(String extractionId);
}
