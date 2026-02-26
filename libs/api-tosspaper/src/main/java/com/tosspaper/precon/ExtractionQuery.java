package com.tosspaper.precon;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class ExtractionQuery {
    private final String entityId;
    private final String status;
    private final int limit;
    private final OffsetDateTime cursorCreatedAt;
    private final String cursorId;
}
