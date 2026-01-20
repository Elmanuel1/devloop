package com.tosspaper.models.query;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;

@Getter
@SuperBuilder
public class BaseQuery {
    String status;
    OffsetDateTime createdDateFrom;
    OffsetDateTime createdDateTo;
    Integer page;
    Integer pageSize;
    String search;
    OffsetDateTime cursorCreatedAt;
    String cursorId;
} 