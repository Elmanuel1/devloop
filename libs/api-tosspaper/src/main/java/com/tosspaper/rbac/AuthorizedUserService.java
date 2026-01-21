package com.tosspaper.rbac;

import com.tosspaper.generated.model.AuthorizedUser;
import com.tosspaper.generated.model.PaginatedAuthorizedUserList;

/**
 * Service for authorized user operations.
 * Handles business logic for team members.
 */
public interface AuthorizedUserService {

    /**
     * List company's authorized users with optional filters and cursor pagination
     *
     * @param companyId Company ID
     * @param email     Optional email filter
     * @param status    Optional status filter
     * @param roleId    Optional role filter
     * @param cursor    Optional cursor for pagination (URL-safe base64 encoded email address)
     * @param limit     Maximum number of results
     * @return Paginated list of authorized users
     */
    PaginatedAuthorizedUserList listAuthorizedUsers(
            Long companyId,
            String email,
            String status,
            String roleId,
            String cursor,
            Integer limit);

    /**
     * Update a user's role in the company
     *
     * @param companyId Company ID
     * @param userId    User ID to update
     * @param roleId    New role ID
     * @param updatedBy Email of user making the update
     * @return Updated authorized user
     */
    AuthorizedUser updateUserRole(Long companyId, String userId, String roleId, String updatedBy);

    /**
     * Remove a user from the company
     *
     * @param companyId Company ID
     * @param userId    User ID to remove
     */
    void removeUser(Long companyId, String userId);
}

