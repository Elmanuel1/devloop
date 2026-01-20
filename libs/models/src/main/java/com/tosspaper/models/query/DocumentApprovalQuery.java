package com.tosspaper.models.query;

import lombok.Getter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
public class DocumentApprovalQuery extends BaseQuery {
    String companyId;
    String projectId;
    String documentType;
    String fromEmail;
    // status is inherited from BaseQuery
}

