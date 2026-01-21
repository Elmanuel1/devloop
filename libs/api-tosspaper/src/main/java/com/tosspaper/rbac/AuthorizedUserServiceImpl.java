package com.tosspaper.rbac;

import com.tosspaper.common.ApiErrorMessages;
import com.tosspaper.common.CursorUtils;
import com.tosspaper.common.ForbiddenException;
import com.tosspaper.generated.model.AuthorizedUser;
import com.tosspaper.generated.model.PaginatedAuthorizedUserList;
import com.tosspaper.generated.model.Pagination;
import com.tosspaper.models.domain.AuthorizedUser.UserStatus;
import com.tosspaper.models.domain.Role;
import com.tosspaper.models.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Service implementation for authorized user operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizedUserServiceImpl implements AuthorizedUserService {

    private final AuthorizedUserRepository authorizedUserRepository;
    private final AuthorizedUserMapper authorizedUserMapper;
    private final CompanyInvitationRepository invitationRepository;
    private final UserRoleCacheService userRoleCacheService;

    @Override
    public PaginatedAuthorizedUserList listAuthorizedUsers(
            Long companyId,
            String email,
            String status,
            String roleId,
            String cursor,
            Integer limit) {

        // Decode cursor if provided (email is URL-safe base64 encoded)
        String cursorEmail = (cursor != null && !cursor.isBlank()) ? CursorUtils.decodeEmailCursor(cursor) : null;

        // Build query object - cursor is email address (stored in cursorId)
        AuthorizedUserQuery query = AuthorizedUserQuery.builder()
                .email(email)
                .status(status)
                .roleId(roleId)
                .cursorId(cursorEmail) // Decoded email cursor stored in cursorId
                .pageSize(limit)
                .build();

        List<com.tosspaper.models.domain.AuthorizedUser> users = authorizedUserRepository.findByCompanyId(companyId, query);

        // Map to generated models
        List<AuthorizedUser> data = users.stream()
                .map(authorizedUserMapper::toGenerated)
                .toList();

        // Generate nextCursor from last item's email if there are more results (URL-safe base64 encoded)
        String nextCursor = (users.size() == limit && !users.isEmpty())
                ? CursorUtils.encodeEmailCursor(users.getLast().email())
                : null;

        // Build pagination
        Pagination pagination = new Pagination();
        pagination.setCursor(nextCursor);

        PaginatedAuthorizedUserList result = new PaginatedAuthorizedUserList();
        result.setData(data);
        result.setPagination(pagination);

        return result;
    }

    @Override
    public AuthorizedUser updateUserRole(Long companyId, String userId, String roleId, String updatedBy) {
        // Find user
        com.tosspaper.models.domain.AuthorizedUser user = authorizedUserRepository.findById(userId);

        // Verify user belongs to this company
        if (!user.companyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, "User does not belong to this company");
        }

        // Validate role - convert from generated enum to domain enum
        Role newRole = Role.fromId(roleId)
                .orElseThrow(() -> new BadRequestException("invalid.role", "Invalid role ID. Must be: owner, admin, operations, or viewer"));

        // Update user role
        com.tosspaper.models.domain.AuthorizedUser updatedUser = user.toBuilder()
                .roleId(newRole.getId())
                .roleName(newRole.getDisplayName())
                .updatedAt(OffsetDateTime.now())
                .lastUpdatedBy(updatedBy)
                .build();

        com.tosspaper.models.domain.AuthorizedUser savedUser = authorizedUserRepository.save(updatedUser);

        // Evict cache for immediate role change effect
        userRoleCacheService.evictUserRole(savedUser.email(), companyId);

        log.info("Updated role for user {} in company {} to {} and evicted cache", userId, companyId, newRole.getDisplayName());

        return authorizedUserMapper.toGenerated(savedUser);
    }

    @Override
    public void removeUser(Long companyId, String userId) {
        // Find user
        com.tosspaper.models.domain.AuthorizedUser user = authorizedUserRepository.findById(userId);

        // Verify user belongs to this company
        if (!user.companyId().equals(companyId)) {
            throw new ForbiddenException(ApiErrorMessages.FORBIDDEN_CODE, "User does not belong to this company");
        }

        // Prevent removing the last owner
        if (user.isOwner()) {
            AuthorizedUserQuery query = AuthorizedUserQuery.builder()
                    .status(UserStatus.ENABLED.getValue())
                    .roleId(Role.OWNER.getId())
                    .pageSize(2)
                    .build();
            List<com.tosspaper.models.domain.AuthorizedUser> owners = authorizedUserRepository.findByCompanyId(companyId, query);
            if (owners.size() == 1) {
                throw new BadRequestException("atleast.one.owner", "There should be at least one owner assigned to the company. Reassign the owner role before removing this user");
            }
        }

        // Evict cache before deletion for immediate revocation
        userRoleCacheService.evictUserRole(user.email(), companyId);

        // Cascade delete: Remove invitation record if it exists
        invitationRepository.delete(companyId, user.email());

        authorizedUserRepository.delete(userId);

        log.info("Removed user {} from company {}, evicted cache, and deleted associated invitation", userId, companyId);
    }

}

