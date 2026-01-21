package com.tosspaper.rbac;

import com.tosspaper.generated.api.RolesApi;
import com.tosspaper.generated.model.RoleInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for role operations.
 * Returns list of available roles in the system.
 *
 * No company context required - roles are system-wide.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RoleController implements RolesApi {

    private final RoleService roleService;

    @Override
    public ResponseEntity<List<RoleInfo>> getRoles() {
        log.debug("Fetching all available roles");
        List<RoleInfo> roles = roleService.getRoles();
        return ResponseEntity.ok(roles);
    }
}
