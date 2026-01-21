package com.tosspaper.rbac;

import com.tosspaper.models.query.BaseQuery;
import lombok.Getter;
import lombok.experimental.SuperBuilder;

/**
 * Query object for authorized user searches.
 * Extends BaseQuery to support filtering and email-based cursor pagination.
 */
@Getter
@SuperBuilder
public class AuthorizedUserQuery extends BaseQuery {
    /**
     * Filter by email (partial match, case-insensitive)
     */
    String email;

    /**
     * Filter by status (enabled, disabled)
     */
    String status;

    /**
     * Filter by role ID (owner, admin, operations, viewer)
     */
    String roleId;
}

