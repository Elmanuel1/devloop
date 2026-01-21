package com.tosspaper.rbac;

import com.tosspaper.generated.api.TeamMembersApi;
import com.tosspaper.generated.model.AuthorizedUser;
import com.tosspaper.generated.model.PaginatedAuthorizedUserList;
import com.tosspaper.generated.model.RoleIdEnum;
import com.tosspaper.generated.model.UpdateUserRoleRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.tosspaper.common.security.SecurityUtils.getSubjectFromJwt;

/**
 * Controller for authorized user operations.
 * Handles listing, updating, and removing team members in a company.
 *
 * All endpoints require company context and proper permissions.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthorizedUserController implements TeamMembersApi {

    private final AuthorizedUserService authorizedUserService;

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'members:view')")
    public ResponseEntity<PaginatedAuthorizedUserList> listAuthorizedUsers(
            @RequestHeader("X-Context-Id") Long xContextId,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) RoleIdEnum roleId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "1000") Integer limit) {

        log.debug("Listing authorized users for company {} with filters: email={}, status={}, roleId={}, cursor={}, limit={}",
                xContextId, email, status, roleId, cursor, limit);

            PaginatedAuthorizedUserList result = authorizedUserService.listAuthorizedUsers(
                    xContextId, email, status, roleId != null ? roleId.getValue() : null, cursor, limit);

        return ResponseEntity.ok(result);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'members:edit')")
    public ResponseEntity<AuthorizedUser> updateUserRole(
            @RequestHeader("X-Context-Id") Long xContextId,
            @PathVariable("userId") String userId,
            @RequestBody UpdateUserRoleRequest updateUserRoleRequest) {

        log.debug("Updating role for user {} in company {} to {}", userId, xContextId, updateUserRoleRequest.getRoleId());

        AuthorizedUser updatedUser = authorizedUserService.updateUserRole(
                xContextId,
                userId,
                updateUserRoleRequest.getRoleId().getValue(),
                getSubjectFromJwt());

        return ResponseEntity.ok(updatedUser);
    }

    @Override
    @PreAuthorize("hasPermission(#xContextId, 'company', 'members:delete')")
    public ResponseEntity<Void> removeUser(
            @RequestHeader("X-Context-Id") Long xContextId,
            @PathVariable("userId") String userId) {

        log.debug("Removing user {} from company {}", userId, xContextId);

        authorizedUserService.removeUser(xContextId, userId);

        return ResponseEntity.noContent().build();
    }
}
