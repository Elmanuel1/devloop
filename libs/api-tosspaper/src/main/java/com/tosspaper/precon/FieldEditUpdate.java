package com.tosspaper.precon;

import org.jooq.JSONB;

public record FieldEditUpdate(String fieldId, JSONB editedValue) {}
