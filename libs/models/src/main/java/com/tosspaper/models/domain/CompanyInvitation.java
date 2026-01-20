package com.tosspaper.models.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Company invitation for inviting users to join a company with a specific role.
 * Uses composite key (companyId, email) - no invite code needed.
 * Integrates with Supabase inviteUserByEmail flow.
 */
@Builder(toBuilder = true)
public record CompanyInvitation(
        Long companyId,
        String email,
        String roleId,         // owner, admin, operations, viewer
        String roleName,       // Owner, Admin, Operations, Viewer (display name)
        InvitationStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt) {  // 24 hours from creation (matches Supabase token expiry)

    /**
     * Invitation status values
     */
    @Getter
    public enum InvitationStatus {
        /**
         * Invitation has been sent and is pending acceptance/decline
         */
        INVITED("invited"),

        /**
         * Invitation was accepted by the user
         */
        ACCEPTED("accepted"),

        /**
         * Invitation was declined by the user
         */
        DECLINED("declined");

        private final String value;

        InvitationStatus(String value) {
            this.value = value;
        }

        public static InvitationStatus fromValue(String value) {
            for (InvitationStatus status : values()) {
                if (status.value.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown invitation status: " + value);
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
     * Check if invitation has expired
     */
    public boolean isExpired() {
        return expiresAt != null && OffsetDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if invitation is pending (invited and not expired)
     */
    public boolean isPending() {
        return status == InvitationStatus.INVITED && !isExpired();
    }

    /**
     * Check if invitation can be accepted/declined
     */
    public boolean canRespond() {
        return isPending();
    }
}
