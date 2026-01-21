package com.tosspaper.rbac;

import com.tosspaper.generated.model.CompanyInvitation;
import com.tosspaper.generated.model.PaginatedCompanyInvitationList;

import java.util.List;
import java.util.Optional;

/**
 * Service for company invitation operations.
 * Handles business logic for invitations.
 */
public interface CompanyInvitationService {

    /**
     * List company's sent invitations with optional filters and cursor pagination
     *
     * @param companyId Company ID
     * @param email     Optional email filter
     * @param status    Optional status filter
     * @param cursor    Optional cursor for pagination (URL-safe base64 encoded email address)
     * @param limit     Maximum number of results
     * @return Paginated list of invitations
     */
    PaginatedCompanyInvitationList listCompanyInvitations(
            Long companyId,
            String email,
            String status,
            String cursor,
            Integer limit);

    /**
     * Find invitation by company and email
     *
     * @param companyId Company ID
     * @param email     User email
     * @return Optional invitation
     */
    Optional<com.tosspaper.models.domain.CompanyInvitation> findByCompanyIdAndEmail(Long companyId, String email);

    /**
     * Send invitation to a user to join the company via Supabase
     *
     * @param companyId Company ID
     * @param email     Email to invite
     * @param roleId    Role ID to assign
     * @return Created invitation
     */
    CompanyInvitation sendInvitation(Long companyId, String email, String roleId);

    /**
     * Cancel an invitation
     *
     * @param companyId Company ID
     * @param email     Email of the invitation to cancel
     */
    void cancelInvitation(Long companyId, String email);

    /**
     * Accept an invitation using the invitation code.
     * Decodes the code, validates the invitation, and adds the user to the company.
     * No authentication required - the code itself acts as the token.
     *
     * @param invitationCode Base64 URL-safe encoded invitation code (companyId:email)
     * @return Accepted invitation
     * @throws IllegalArgumentException if code is invalid or invitation not found/expired
     */
    CompanyInvitation acceptInvitationByCode(String invitationCode);
}

