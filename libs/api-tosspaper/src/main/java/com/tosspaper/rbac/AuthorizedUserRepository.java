package com.tosspaper.rbac;

import com.tosspaper.models.domain.AuthorizedUser;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

/**
 * Repository for authorized user operations.
 * Manages user memberships in companies with role assignments.
 */
public interface AuthorizedUserRepository {

    /**
     * Find all authorized users for a user's email across all companies.
     * Used by auth hook to generate JWT authorities.
     *
     * @param email User email from JWT
     * @return List of authorized users (may span multiple companies)
     */
    List<AuthorizedUser> findByEmail(String email);

    /**
     * Find all authorized users for a company with optional filters and cursor pagination
     *
     * @param companyId Company ID
     * @param query     Query object containing filters and pagination (cursor is email address)
     * @return List of authorized users
     */
    List<AuthorizedUser> findByCompanyId(Long companyId, AuthorizedUserQuery query);

    /**
     * Find an authorized user by email in a company
     *
     * @param companyId Company ID
     * @param email     User email
     * @return Optional authorized user
     */
    Optional<AuthorizedUser> findByCompanyIdAndEmail(Long companyId, String email);

    /**
     * Find an authorized user by ID
     *
     * @param userId User ID
     * @return Authorized user
     * @throws com.tosspaper.common.NotFoundException if user not found
     */
    AuthorizedUser findById(String userId);

    /**
     * Save or update an authorized user
     *
     * @param user Authorized user to save
     * @return Saved authorized user
     */
    AuthorizedUser save(AuthorizedUser user);

    /**
     * Save or update an authorized user using provided DSLContext
     *
     * @param dsl Transaction-aware DSLContext
     * @param user Authorized user to save
     * @return Saved authorized user
     */
    AuthorizedUser save(DSLContext dsl, AuthorizedUser user);

    /**
     * Delete an authorized user
     *
     * @param userId User ID to delete
     */
    void delete(String userId);
}
