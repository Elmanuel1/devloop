package com.tosspaper.rbac;

import com.tosspaper.generated.api.PermissionsApi;
import com.tosspaper.generated.model.PermissionsResponse;
import com.tosspaper.generated.model.RolePermissions;
import com.tosspaper.models.domain.PermissionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controller for permissions operations.
 * Returns list of all roles and their permissions from PermissionRegistry.
 *
 * No authentication required - permissions are static and public information.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PermissionsController implements PermissionsApi {

    @Override
    public ResponseEntity<PermissionsResponse> getPermissions() {
        log.debug("Fetching all role permissions from PermissionRegistry");

        Map<String, Set<String>> rolePermissionsMap = PermissionRegistry.getAllRolePermissions();

        List<RolePermissions> rolesList = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : rolePermissionsMap.entrySet()) {
            // Strip "role_" prefix for OAuth compatibility (e.g., "role_owner" -> "owner")
            String roleId = entry.getKey().replace("role_", "");
            RolePermissions rolePermissions = new RolePermissions(
                roleId,
                new ArrayList<>(entry.getValue())
            );
            rolesList.add(rolePermissions);
        }

        PermissionsResponse response = new PermissionsResponse(rolesList);
        return ResponseEntity.ok(response);
    }
}
