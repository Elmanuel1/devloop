package com.tosspaper.rbac;

import com.tosspaper.common.NotFoundException;
import com.tosspaper.company.CompanyMapper;
import com.tosspaper.company.CompanyRepository;
import com.tosspaper.generated.model.Company;
import com.tosspaper.generated.model.CompanyCreate;
import com.tosspaper.models.domain.AuthorizedUser;
import com.tosspaper.models.domain.CompanyInvitation;
import com.tosspaper.models.domain.Role;
import com.tosspaper.models.jooq.tables.records.CompaniesRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for orchestrating new user onboarding.
 * Handles atomic creation of company and authorized_user records for self-registering users.
 * Uses JOOQ transactions for atomicity.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserOnboardingService {

    private final CompanyRepository companyRepository;
    private final CompanyMapper companyMapper;
    private final AuthorizedUserRepository authorizedUserRepository;
    private final CompanyInvitationRepository invitationRepository;
    private final DSLContext dslContext;

    /**
     * Create a new company with the user as owner.
     * All database operations are atomic - both company and authorized_user are created or neither is.
     *
     * @param email User's email address
     * @param userId Supabase user ID from auth.users
     * @param companyName Name for the new company
     * @param countryOfIncorporation Optional ISO 3166-1 alpha-2 country code (e.g., "US", "CA", "FR")
     * @return Created company
     */
    public Company createCompanyWithOwner(String email, String userId, String companyName, String countryOfIncorporation) {
        log.info("Creating company '{}' for user {} (userId={}) with country of incorporation: {}",
                companyName, email, userId, countryOfIncorporation != null ? countryOfIncorporation : "null");

        // Map outside transaction - only DB writes in transaction
        CompanyCreate companyCreate = new CompanyCreate()
                .name(companyName)
                .countryOfIncorporation(countryOfIncorporation);
        CompaniesRecord record = companyMapper.toRecord(companyCreate, email);

        CompaniesRecord savedRecord = dslContext.transactionResult(configuration -> {
            DSLContext dsl = configuration.dsl();

            CompaniesRecord txSavedRecord = companyRepository.save(dsl, record);

            AuthorizedUser authorizedUser = AuthorizedUser.builder()
                    .id(UUID.randomUUID().toString())
                    .companyId(txSavedRecord.getId())
                    .userId(userId)
                    .email(email)
                    .roleId(Role.OWNER.getId())
                    .roleName(Role.OWNER.getDisplayName())
                    .status(AuthorizedUser.UserStatus.ENABLED)
                    .build();

            authorizedUserRepository.save(dsl, authorizedUser);

            return txSavedRecord;
        });

        Company createdCompany = companyMapper.toDto(savedRecord);

        log.info("Created company {} (id={}) for user {}", companyName, createdCompany.getId(), email);
        log.info("Created authorized_user record for user {} as owner of company {}", email, createdCompany.getId());

        return createdCompany;
    }

    /**
     * Accept an invitation and create authorized_user record.
     * Called when user signs up via Supabase invitation link with company_id in metadata.
     * All database operations are atomic - both authorized_user creation and invitation update happen together.
     *
     * @param companyId Company ID from invitation metadata
     * @param email User's email address
     * @param userId Supabase user ID from auth.users
     * @throws NotFoundException if invitation not found
     */
    public void acceptInvitationAndCreateAuthorizedUser(Long companyId, String email, String userId) {
        log.info("Auto-accepting invitation for user {} to company {}", email, companyId);

        // Find invitation (read operation - doesn't need transaction)
        CompanyInvitation invitation = invitationRepository.findByCompanyIdAndEmail(companyId, email)
                .orElseThrow(() -> new NotFoundException("invitation.not.found",
                        "No invitation found for email " + email));

        // Write operations in transaction
        dslContext.transaction(configuration -> {
            DSLContext dsl = configuration.dsl();

            // Create authorized_user record with role from invitation
            AuthorizedUser authorizedUser = AuthorizedUser.builder()
                    .id(UUID.randomUUID().toString())
                    .companyId(companyId)
                    .userId(userId)
                    .email(email)
                    .roleId(invitation.roleId())
                    .roleName(invitation.roleName())
                    .status(AuthorizedUser.UserStatus.ENABLED)
                    .build();

            authorizedUserRepository.save(dsl, authorizedUser);

            log.info("Created authorized_user record for user {} with role {} in company {}",
                     email, invitation.roleId(), companyId);

            // Mark invitation as accepted
            CompanyInvitation updatedInvitation = invitation.toBuilder()
                    .status(CompanyInvitation.InvitationStatus.ACCEPTED)
                    .build();

            invitationRepository.save(dsl, updatedInvitation);

            log.info("Marked invitation as accepted for user {} in company {}", email, companyId);
        });
    }

}
