package com.tosspaper.precon;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class TenderQuery {
    private final String search;
    private final String status;
    private final String sortBy;
    private final String sortDirection;
    private final int limit;
    private final OffsetDateTime cursorCreatedAt;
    private final String cursorId;
}
