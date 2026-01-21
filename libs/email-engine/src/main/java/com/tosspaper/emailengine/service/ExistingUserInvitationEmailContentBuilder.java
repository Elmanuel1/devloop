package com.tosspaper.emailengine.service;

import com.tosspaper.models.config.FrontendUrlProperties;
import com.tosspaper.models.util.InvitationCodeUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builder for existing user invitation email content.
 * Used when a user already has a Supabase account and needs to login to accept an invitation.
 */
@Component
@RequiredArgsConstructor
public class ExistingUserInvitationEmailContentBuilder {

    private final FrontendUrlProperties frontendUrlProperties;

    /**
     * Build email subject for existing user invitation.
     */
    public String buildSubject(String companyName) {
        return String.format("You've been invited to join %s on TossPaper", companyName);
    }

    /**
     * Build email body for existing user invitation.
     *
     * @param email the user's email address
     * @param companyName the company name
     * @param roleName the role display name (e.g., "Admin", "Viewer")
     * @param companyId the company ID
     * @return email body text
     */
    public String buildBody(String email, String companyName, String roleName, Long companyId) {
        // Generate base64 URL-safe invitation code
        String invitationCode = InvitationCodeUtils.encode(companyId, email);
        String invitationUrl = String.format("%s/invitations/%s",
            frontendUrlProperties.getBaseUrl(), invitationCode);

        return String.format(
            "Hello,\n\n" +
            "You've been invited to join %s on TossPaper as a %s.\n\n" +
            "We noticed you already have a TossPaper account with this email address (%s). " +
            "Click the link below to accept this invitation and join the organization:\n\n" +
            "%s\n\n" +
            "(You will be prompted to log in if not already authenticated)\n\n" +
            "This invitation will expire in 24 hours.\n\n" +
            "If you did not expect this invitation, you can safely ignore this email.\n\n" +
            "Best regards,\n" +
            "TossPaper Team",
            companyName,
            roleName,
            email,
            invitationUrl
        );
    }
}
