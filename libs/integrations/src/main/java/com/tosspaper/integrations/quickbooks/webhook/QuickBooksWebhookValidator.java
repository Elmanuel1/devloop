package com.tosspaper.integrations.quickbooks.webhook;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Utility class for validating QuickBooks webhook signatures.
 * Uses HMAC SHA-256 to verify that webhook notifications are from Intuit.
 */
@Slf4j
public final class QuickBooksWebhookValidator {

    private static final String HMAC_SHA256_ALGORITHM = "HmacSHA256";

    // Private constructor to prevent instantiation
    private QuickBooksWebhookValidator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Validates a QuickBooks webhook signature using HMAC SHA-256.
     *
     * @param payload the raw webhook payload (JSON string)
     * @param signature the signature from the `intuit-signature` header
     * @param verifierToken the verifier token from QuickBooks app settings
     * @return true if signature is valid, false otherwise
     */
    public static boolean validateSignature(String payload, String signature, String verifierToken) {
        if (payload == null || signature == null || verifierToken == null) {
            log.warn("Missing required parameters for signature validation");
            return false;
        }

        try {
            // Compute HMAC SHA-256 hash
            String computedHash = computeHmacSha256(payload, verifierToken);

            // Compare signatures (case-insensitive, as QuickBooks may send in different case)
            boolean isValid = computedHash.equalsIgnoreCase(signature);
            
            if (!isValid) {
                log.warn("Signature validation failed. Expected: {}, Received: {}", computedHash, signature);
            } else {
                log.debug("Signature validation successful");
            }
            
            return isValid;
        } catch (Exception e) {
            log.error("Error validating webhook signature", e);
            return false;
        }
    }

    /**
     * Computes HMAC SHA-256 hash of the payload using the verifier token as the key.
     *
     * @param payload the payload to hash
     * @param verifierToken the verifier token to use as the key
     * @return Base64-encoded string representation of the HMAC SHA-256 hash
     * @throws NoSuchAlgorithmException if HMAC SHA-256 is not available
     * @throws InvalidKeyException if the key is invalid
     */
    private static String computeHmacSha256(String payload, String verifierToken)
            throws NoSuchAlgorithmException, InvalidKeyException {

        Mac mac = Mac.getInstance(HMAC_SHA256_ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                verifierToken.getBytes(StandardCharsets.UTF_8),
                HMAC_SHA256_ALGORITHM
        );
        mac.init(secretKeySpec);

        byte[] hashBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hashBytes);
    }
}

