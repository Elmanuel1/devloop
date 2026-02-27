package com.tosspaper.precon;

import com.tosspaper.precon.generated.model.EntityType;
import org.jooq.JSONB;

public record ExtractionInsertParams(
    String id,
    String companyId,
    EntityType entityType,
    String entityId,
    JSONB documentIds,
    JSONB fieldNames
) {}
