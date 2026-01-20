package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Represents a user authorized to access a company with a specific role.
 * Replaces the CompanyMember concept with clearer naming.
 *
 * The user_id references Supabase auth.users.id directly - no local users table needed.
 * Email is denormalized from Supabase JWT for query performance and is the primary lookup key.
 */
@Builder(toBuilder = true)
public record AuthorizedUser(
        String id,
        Long companyId,
        String userId,         // Supabase auth.users.id
        String email,          // Primary lookup key, denormalized from JWT
        String roleId,         // owner, admin, operations, viewer
        String roleName,       // Owner, Admin, Operations, Viewer (display name)
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String lastUpdatedBy) {

    /**
     * User status in the company
     */
    @Getter
    public enum UserStatus {
        ENABLED("enabled"),
        DISABLED("disabled");

        private final String value;

        UserStatus(String value) {
            this.value = value;
        }

        public static UserStatus fromValue(String value) {
            for (UserStatus status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown user status: " + value);
        }
    }

    /**
     * Get role as enum
     * @throws IllegalArgumentException if roleId is invalid (indicates data corruption)
     */
    public Role getRole() {
        return Role.fromId(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid roleId in database: " + roleId));
    }

    /**
     * Check if user is enabled
     */
    public boolean isEnabled() {
        return status == UserStatus.ENABLED;
    }

    /**
     * Check if user has owner role
     */
    public boolean isOwner() {
        return getRole().isOwner();
    }

    /**
     * Check if user can manage team members
     */
    public boolean canManageMembers() {
        return getRole().canManageMembers();
    }
}
