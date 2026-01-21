package com.tosspaper.rbac;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * Query object for company invitation searches.
 * Extends BaseQuery to support filtering and email-based cursor pagination.
 */
@Getter
@SuperBuilder
public class CompanyInvitationQuery extends BaseQuery {
    /**
     * Filter by email (partial match, case-insensitive)
     */
    String email;
}
