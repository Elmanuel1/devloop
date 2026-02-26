package com.tosspaper.precon;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ExtractionFieldQuery {
    private final String extractionId;
    private final String fieldName;
    private final String documentId;
    private final int limit;
    private final OffsetDateTime cursorCreatedAt;
    private final String cursorId;
}
