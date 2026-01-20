package com.tosspaper.models.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Utility class for encoding and decoding invitation codes.
 * Invitation codes are base64 URL-safe encoded strings containing companyId and email.
 */
public class InvitationCodeUtils {

    private static final String SEPARATOR = ":";

    /**
     * Encode company ID and email into a base64 URL-safe invitation code.
     * Format: base64UrlEncode("companyId:email")
     *
     * @param companyId the company ID
     * @param email the email address
     * @return base64 URL-safe encoded invitation code
     */
    public static String encode(Long companyId, String email) {
        String payload = companyId + SEPARATOR + email;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode an invitation code to extract company ID and email.
     *
     * @param code the base64 URL-safe encoded invitation code
     * @return InvitationData containing companyId and email
     * @throws IllegalArgumentException if code is invalid
     */
    public static InvitationData decode(String code) {
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(code);
            String payload = new String(decodedBytes, StandardCharsets.UTF_8);
            String[] parts = payload.split(SEPARATOR, 2);

            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid invitation code format");
            }

            Long companyId = Long.parseLong(parts[0]);
            String email = parts[1];

            return new InvitationData(companyId, email);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid invitation code: " + e.getMessage(), e);
        }
    }

    /**
     * Data class to hold decoded invitation information.
     */
    public record InvitationData(Long companyId, String email) {}
}
