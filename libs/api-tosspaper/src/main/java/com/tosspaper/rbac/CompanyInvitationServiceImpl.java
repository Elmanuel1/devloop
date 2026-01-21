package com.tosspaper.rbac;

import com.tosspaper.common.BadRequestException;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.DuplicateException;
import com.tosspaper.company.CompanyRepository;
import com.tosspaper.generated.model.CompanyInvitation;
import com.tosspaper.generated.model.PaginatedCompanyInvitationList;
import com.tosspaper.generated.model.Pagination;
import com.tosspaper.models.domain.Role;
import com.tosspaper.models.service.EmailDomainService;
import com.tosspaper.models.service.SenderNotificationService;
import com.tosspaper.models.util.InvitationCodeUtils;
import com.tosspaper.supabase.AuthInvitationClient;
import com.tosspaper.supabase.UserAlreadyExistsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service implementation for company invitation operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyInvitationServiceImpl implements CompanyInvitationService {

    private final CompanyInvitationRepository invitationRepository;
    private final InvitationMapper invitationMapper;
    private final AuthInvitationClient authInvitationClient;
    private final AuthorizedUserRepository authorizedUserRepository;
    private final EmailDomainService emailDomainService;
    private final SenderNotificationService senderNotificationService;
    private final CompanyRepository companyRepository;
    private final DSLContext dslContext;

    @Override
    public PaginatedCompanyInvitationList listCompanyInvitations(
            Long companyId,
            String email,
            String status,
            String cursor,
            Integer limit) {

        // Decode cursor if provided (email is URL-safe base64 encoded)
        String cursorEmail = (cursor != null && !cursor.isBlank()) ? CursorUtils.decodeEmailCursor(cursor) : null;

        // Build query object - cursor is email address (stored in cursorId)
        CompanyInvitationQuery query = CompanyInvitationQuery.builder()
                .email(email)
                .status(status)
                .cursorId(cursorEmail) // Decoded email cursor stored in cursorId
                .pageSize(limit)
                .build();

        List<com.tosspaper.models.domain.CompanyInvitation> invitations = invitationRepository.findByCompanyId(companyId, query);

        // Map to generated models
        List<CompanyInvitation> data = invitations.stream()
                .map(invitationMapper::toGenerated)
                .toList();

        // Generate nextCursor from last item's email if there are more results (URL-safe base64 encoded)
        String nextCursor = (invitations.size() == limit && !invitations.isEmpty())
                ? CursorUtils.encodeEmailCursor(invitations.getLast().email())
                : null;

        // Build pagination
        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        PaginatedCompanyInvitationList result = new PaginatedCompanyInvitationList();
        result.setData(data);
        result.setPagination(pagination);

        return result;
    }

    @Override
    public Optional<com.tosspaper.models.domain.CompanyInvitation> findByCompanyIdAndEmail(Long companyId, String email) {
        return invitationRepository.findByCompanyIdAndEmail(companyId, email);
    }

    @Override
    public CompanyInvitation sendInvitation(Long companyId, String email, String roleId) {
        // Validate role
        Role role = Role.fromId(roleId)
                .orElseThrow(() -> new BadRequestException("invalid.role", "Invalid role ID. Must be: owner, admin, operations, or viewer"));

        // Check if email domain is blocked (disposable or personal email)
        String domain = email.substring(email.lastIndexOf("@") + 1);
        if (emailDomainService.isBlockedDomain(domain)) {
            throw new BadRequestException("blocked.email.domain",
                    "Cannot send invitation to disposable or personal email addresses. Please use a business email.");
        }

        // Check if user is already a member of the company
        Optional<com.tosspaper.models.domain.AuthorizedUser> existingMember =
                authorizedUserRepository.findByCompanyIdAndEmail(companyId, email);
        if (existingMember.isPresent()) {
            throw new DuplicateException("user.already.member",
                    "User is already a member of this company");
        }

        // Check if invitation already exists
        var existingInvitation = findByCompanyIdAndEmail(companyId, email);
        if (existingInvitation.isPresent()) {
            var invitation = existingInvitation.get();
            // Block if invitation is pending or already accepted
            if (invitation.status() == com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.INVITED) {
                throw new DuplicateException("invitation.already.exists",
                        "Invitation already sent to this email");
            }
            if (invitation.status() == com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.ACCEPTED) {
                throw new DuplicateException("invitation.already.accepted",
                        "User has already accepted an invitation to this company");
            }
        }

        // Create invitation
        com.tosspaper.models.domain.CompanyInvitation invitation = com.tosspaper.models.domain.CompanyInvitation.builder()
                .companyId(companyId)
                .email(email)
                .roleId(role.getId())
                .roleName(role.getDisplayName())
                .status(com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.INVITED)
                .createdAt(OffsetDateTime.now())
                .expiresAt(OffsetDateTime.now().plusHours(24)) // 24-hour expiry matching Supabase token
                .build();

        com.tosspaper.models.domain.CompanyInvitation savedInvitation = invitationRepository.save(invitation);

        // Send Supabase invitation with company metadata
        try {
            Map<String, Object> metadata = Map.of("company_id", companyId.toString());
            authInvitationClient.inviteUserByEmail(email, metadata);
            log.info("Supabase invitation sent to {} with company_id {}", email, companyId);
        } catch (UserAlreadyExistsException e) {
            // User already exists in Supabase - send them an email to login and join
            log.info("User {} already exists in Supabase. Sending login invitation email instead.", email);

            // Get company name for email
            var company = companyRepository.findById(companyId);
            String companyName = company.getName();

            // Send email to existing user with login link
            senderNotificationService.sendExistingUserInvitationNotification(
                    email, companyId, companyName, role.getDisplayName());

            log.info("Existing user invitation email sent to {} for company {} ({})",
                    email, companyName, companyId);
        } catch (Exception e) {
            log.error("Failed to send Supabase invitation to {}: {}", email, e.getMessage(), e);
            // Delete the invitation from DB since Supabase failed
            invitationRepository.delete(companyId, email);
            log.info("Deleted invitation for {} due to Supabase failure", email);
            throw new RuntimeException("Failed to send invitation email. Please try again later.", e);
        }

        log.info("Invitation created for {} to join company {} as {}", email, companyId, role.getDisplayName());

        return invitationMapper.toGenerated(savedInvitation);
    }

    @Override
    public void cancelInvitation(Long companyId, String email) {
        invitationRepository.delete(companyId, email);
        log.info("Invitation cancelled for {} from company {}", email, companyId);
    }

    @Override
    public CompanyInvitation acceptInvitationByCode(String invitationCode) {
        // Decode the invitation code to extract companyId and email
        InvitationCodeUtils.InvitationData invitationData;
        try {
            invitationData = InvitationCodeUtils.decode(invitationCode);
        } catch (IllegalArgumentException e) {
            log.error("Invalid invitation code: {}", invitationCode, e);
            throw new BadRequestException("invalid.invitation.code", "Invalid invitation code");
        }

        Long companyId = invitationData.companyId();
        String email = invitationData.email();

        log.info("Accepting invitation for {} to join company {}", email, companyId);

        // Find the invitation
        var invitationOpt = invitationRepository.findByCompanyIdAndEmail(companyId, email);
        if (invitationOpt.isEmpty()) {
            throw new BadRequestException("invitation.not.found", "Invitation not found");
        }

        com.tosspaper.models.domain.CompanyInvitation invitation = invitationOpt.get();

        // Validate invitation status
        if (invitation.status() == com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.ACCEPTED) {
            log.info("Invitation already accepted for {} to company {}", email, companyId);
            // Return the already accepted invitation (idempotent)
            return invitationMapper.toGenerated(invitation);
        }

        if (invitation.status() == com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.DECLINED) {
            throw new BadRequestException("invitation.declined", "This invitation has been declined");
        }

        // Check if expired
        if (invitation.isExpired()) {
            throw new BadRequestException("invitation.expired", "This invitation has expired");
        }

        // Check if user is already a member
        Optional<com.tosspaper.models.domain.AuthorizedUser> existingMember =
                authorizedUserRepository.findByCompanyIdAndEmail(companyId, email);
        if (existingMember.isPresent()) {
            log.info("User {} is already a member of company {}", email, companyId);
            throw new BadRequestException("invitation.expired", "This invitation has expired");
        }

        // Get user ID from Supabase (external call - outside transaction)
        String userId = authInvitationClient.getUserIdByEmail(email);
        log.info("Found Supabase user ID {} for email {}", userId, email);

        // Get role for the invitation
        Role role = invitation.getRole();

        // Atomic transaction: create authorized_user and update invitation together
        dslContext.transaction(configuration -> {
            DSLContext dsl = configuration.dsl();

            // Create authorized_user record
            com.tosspaper.models.domain.AuthorizedUser authorizedUser = com.tosspaper.models.domain.AuthorizedUser.builder()
                    .id(UUID.randomUUID().toString())
                    .companyId(companyId)
                    .userId(userId)
                    .email(email)
                    .roleId(role.getId())
                    .roleName(role.getDisplayName())
                    .status(com.tosspaper.models.domain.AuthorizedUser.UserStatus.ENABLED)
                    .createdAt(OffsetDateTime.now())
                    .build();

            authorizedUserRepository.save(dsl, authorizedUser);
            log.info("Created authorized_user record for user {} with role {} in company {}",
                    email, role.getId(), companyId);

            // Update invitation status to accepted
            com.tosspaper.models.domain.CompanyInvitation acceptedInvitation = invitation.toBuilder()
                    .status(com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.ACCEPTED)
                    .updatedAt(OffsetDateTime.now())
                    .build();

            invitationRepository.save(dsl, acceptedInvitation);
            log.info("Marked invitation as accepted for user {} in company {}", email, companyId);
        });

        log.info("Invitation accepted for {} to join company {} as {}", email, companyId, role.getDisplayName());

        // Return the accepted invitation (re-fetch or build from existing)
        com.tosspaper.models.domain.CompanyInvitation acceptedInvitation = invitation.toBuilder()
                .status(com.tosspaper.models.domain.CompanyInvitation.InvitationStatus.ACCEPTED)
                .updatedAt(OffsetDateTime.now())
                .build();

        return invitationMapper.toGenerated(acceptedInvitation);
    }

}

