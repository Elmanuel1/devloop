package com.tosspaper.rbac;

import com.tosspaper.generated.model.RoleInfo;

import java.util.List;

/**
 * Service for role operations.
 * Handles business logic for roles.
 */
public interface RoleService {

    /**
     * Get all available roles in the system
     *
     * @return List of roles
     */
    List<RoleInfo> getRoles();
}

