package com.tosspaper.models.domain;

import java.util.Map;
import java.util.Set;

/**
 * Static registry of permissions for each role.
 * Single source of truth for RBAC permissions - no database queries needed.
 *
 * Permission format: "resource:action" (e.g., "companies:edit", "documents:upload")
 */
public class PermissionRegistry {

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = Map.of(
            // OWNER: Full system control
            "owner", Set.of(
                    "companies:view", "companies:edit", "companies:delete",
                    "contacts:view", "contacts:edit", "contacts:delete", "contacts:create",
                    "members:view", "members:invite", "members:edit", "members:delete",
                    "invitations:view", "invitations:send", "invitations:cancel",
                    "projects:view", "projects:create", "projects:edit", "projects:delete",
                    "purchase-orders:view", "purchase-orders:create", "purchase-orders:edit", "purchase-orders:delete",
                    "documents:view", "documents:upload", "documents:edit", "documents:delete",
                    "extraction:view", "extraction:approve", "extraction:reject",
                    "senders:view", "senders:approve", "senders:reject",
                    "integrations:view", "integrations:edit",
                    "accounts:view", "items:view", "items:create", "items:update",
                    "tenders:view", "tenders:create", "tenders:edit", "tenders:delete",
                    "extractions:view", "extractions:create", "extractions:edit", "extractions:delete", "extractions:apply"
            ),

            // ADMIN: System configuration and operations (cannot manage members or create POs)
            "admin", Set.of(
                    "companies:view", "companies:edit",
                    "members:view", // Can see members but not manage them
                    "contacts:view", "contacts:edit", "contacts:delete", "contacts:create",
                    "projects:view", "projects:create", "projects:edit", "projects:delete",
                    "purchase-orders:view", // Can view but not create/edit POs
                    "documents:view", "documents:upload", "documents:edit", "documents:delete",
                    "extraction:view", "extraction:approve", "extraction:reject",
                    "senders:view", "senders:approve", "senders:reject",
                    "integrations:view", "integrations:edit",
                    "accounts:view", "items:view", "items:create", "items:update"
            ),

            // OPERATIONS: Document processing workflow (95% of usage)
            "operations", Set.of(
                    "companies:view",
                    "projects:view",
                    "purchase-orders:view",
                    "contacts:view", "contacts:edit", "contacts:delete", "contacts:create",
                    "documents:view", "documents:upload", "documents:edit", "documents:delete",
                    "extraction:view", "extraction:approve", "extraction:reject",
                    "senders:view", "senders:approve", "senders:reject",
                    "integrations:view",
                    "accounts:view", "items:view", "items:create", "items:update",
                    "tenders:view", "tenders:create", "tenders:edit",
                    "extractions:view", "extractions:create", "extractions:edit", "extractions:apply"
            ),

            // VIEWER: Read-only access for auditors
            "viewer", Set.of(
                    "companies:view",
                    "projects:view",
                    "purchase-orders:view",
                    "documents:view",
                    "extraction:view",
                    "senders:view",
                    "contacts:view",
                    "members:view",
                    "invitations:view",
                    "integrations:view",
                    "accounts:view", "items:view",
                    "tenders:view",
                    "extractions:view"
            )
    );

    /**
     * Check if a role has a specific permission (combined format)
     *
     * @param roleId     Role ID (owner, admin, operations, viewer)
     * @param permission Permission in "resource:action" format
     * @return true if the role has the permission
     */
    public static boolean hasPermission(String roleId, String permission) {
        Set<String> permissions = ROLE_PERMISSIONS.get(roleId);
        if (permissions == null) {
            return false;
        }
        return permissions.contains(permission);
    }

    /**
     * Get all role permissions
     *
     * @return Map of role IDs to their permissions
     */
    public static Map<String, Set<String>> getAllRolePermissions() {
        return ROLE_PERMISSIONS;
    }
}
