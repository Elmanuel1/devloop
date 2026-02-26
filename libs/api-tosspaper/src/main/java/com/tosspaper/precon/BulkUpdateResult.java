package com.tosspaper.precon;

import com.tosspaper.models.jooq.tables.records.ExtractionFieldsRecord;

import java.util.List;

public record BulkUpdateResult(List<ExtractionFieldsRecord> fields, int versionRowsUpdated) {}
