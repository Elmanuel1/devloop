package com.tosspaper.rbac;

import com.tosspaper.models.domain.CompanyInvitation;
import org.jooq.DSLContext;

import java.util.List;
import java.util.Optional;

/**
 * Repository for company invitation operations.
 * Manages email invitations for users to join companies.
 *
 * Flow with Supabase:
 * 1. Owner sends invite → save() invitation + Supabase inviteUserByEmail()
 * 2. User clicks Supabase link → Supabase webhook auto-accepts invitation
 */
public interface CompanyInvitationRepository {

    /**
     * Find all invitations for a company with optional filters and cursor pagination
     *
     * @param companyId Company ID
     * @param query     Query object containing filters and pagination (cursor is email address)
     * @return List of invitations
     */
    List<CompanyInvitation> findByCompanyId(Long companyId, CompanyInvitationQuery query);

    /**
     * Find invitation by company and email
     *
     * @param companyId Company ID
     * @param email     User email
     * @return Optional invitation
     */
    Optional<CompanyInvitation> findByCompanyIdAndEmail(Long companyId, String email);

    /**
     * Find invitation by company and email using provided DSLContext
     *
     * @param dsl Transaction-aware DSLContext
     * @param companyId Company ID
     * @param email     User email
     * @return Optional invitation
     */
    Optional<CompanyInvitation> findByCompanyIdAndEmail(DSLContext dsl, Long companyId, String email);

    /**
     * Save or update an invitation (UPSERT based on composite key)
     * Re-inviting a user will update the existing record.
     *
     * @param invitation Invitation to save
     * @return Saved invitation
     */
    CompanyInvitation save(CompanyInvitation invitation);

    /**
     * Save or update an invitation using provided DSLContext
     *
     * @param dsl Transaction-aware DSLContext
     * @param invitation Invitation to save
     * @return Saved invitation
     */
    CompanyInvitation save(DSLContext dsl, CompanyInvitation invitation);

    /**
     * Delete an invitation
     *
     * @param companyId Company ID
     * @param email     User email
     * @return
     */
    boolean delete(Long companyId, String email);
}
