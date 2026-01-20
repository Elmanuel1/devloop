package com.tosspaper.models.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Permission record representing a resource:action pair.
 * Permissions are assigned to roles via the role_permissions table.
 *
 * Format: resource:action (e.g., "companies:view", "documents:approve")
 */
public record Permission(
        String resource,
        String action,
        String description,
        OffsetDateTime createdAt) {

    /**
     * Create permission from resource and action
     */
    public Permission(String resource, String action, String description) {
        this(resource, action, description, null);
    }

    /**
     * Get permission as authority string for Spring Security.
     * Format: "resource:action"
     *
     * @return Authority string (e.g., "companies:view")
     */
    public String toAuthority() {
        return resource + ":" + action;
    }

    /**
     * Get full authority with company ID prefix.
     * Format: "companyId:resource:action"
     *
     * @param companyId Company ID
     * @return Full authority string (e.g., "123:companies:view")
     */
    public String toAuthority(Long companyId) {
        return companyId + ":" + resource + ":" + action;
    }

    /**
     * Parse permission from authority string (e.g., "companies:view")
     *
     * @param authority Authority string in format "resource:action"
     * @return Permission object
     * @throws IllegalArgumentException if format is invalid
     */
    public static Permission fromAuthority(String authority) {
        if (authority == null || authority.isBlank()) {
            throw new IllegalArgumentException("Authority cannot be null or blank");
        }

        String[] parts = authority.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid authority format. Expected 'resource:action', got: " + authority);
        }

        return new Permission(parts[0], parts[1], null);
    }

    /**
     * Equality based only on resource and action (ignoring description and createdAt)
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Permission that = (Permission) o;
        return Objects.equals(resource, that.resource) &&
                Objects.equals(action, that.action);
    }

    /**
     * Hash based only on resource and action
     */
    @Override
    public int hashCode() {
        return Objects.hash(resource, action);
    }

    @Override
    public String toString() {
        return toAuthority();
    }
}
