package com.tosspaper.models.domain;

import lombok.Getter;

import java.util.Arrays;

/**
 * Company member roles for RBAC system.
 *
 * Role hierarchy:
 * - OWNER: Full system control, manages company and members
 * - ADMIN: System configuration and operations, cannot manage members or POs
 * - OPERATIONS: Document processing workflow (95% of usage)
 * - VIEWER: Read-only access for auditors
 */
@Getter
public enum Role {
    OWNER("owner", "Owner"),
    ADMIN("admin", "Admin"),
    OPERATIONS("operations", "Operations"),
    VIEWER("viewer", "Viewer");

    private final String id;
    private final String displayName;

    Role(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /**
     * Parse role from ID string (e.g., "owner" -> OWNER)
     *
     * @param id Role ID from database
     * @return Optional Role enum, empty if role ID is invalid
     */
    public static java.util.Optional<Role> fromId(String id) {
        return Arrays.stream(values())
                .filter(role -> role.id.equals(id))
                .findFirst();
    }

    /**
     * Check if this role has higher or equal authority than another role.
     * Hierarchy: OWNER > ADMIN > OPERATIONS > VIEWER
     *
     * @param other Role to compare against
     * @return true if this role has higher or equal authority
     */
    public boolean hasAuthorityOver(Role other) {
        return this.ordinal() <= other.ordinal();
    }

    /**
     * Check if this role is owner
     */
    public boolean isOwner() {
        return this == OWNER;
    }

    /**
     * Check if this role is admin or higher
     */
    public boolean isAdminOrHigher() {
        return this == OWNER || this == ADMIN;
    }

    /**
     * Check if this role can manage team members (invite/remove)
     */
    public boolean canManageMembers() {
        return this == OWNER;
    }
}
