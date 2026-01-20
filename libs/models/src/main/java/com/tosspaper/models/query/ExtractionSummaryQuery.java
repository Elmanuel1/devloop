package com.tosspaper.models.query;

import com.tosspaper.models.domain.ExtractionSummaryBucket;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class ExtractionSummaryQuery extends BaseQuery {
    ExtractionSummaryBucket bucket;
    String assignedEmail;
    String documentType;
    String fromEmail;
    String projectId;
}


