package com.tosspaper.integrations.common.util;

import jakarta.annotation.Nonnull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

/**
 * Utility class for encrypting and decrypting OAuth tokens using AES-256-GCM.
 * Uses BouncyCastle provider for reliable GCM support.
 * Tokens are encrypted before storage in the database and decrypted when retrieved.
 */
@Slf4j
@UtilityClass
public class TokenEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Encrypt a token using AES-256-GCM.
     *
     * @param plaintext the token to encrypt (must not be null or empty)
     * @param encryptionKey the base64-encoded encryption key (must be 32 bytes)
     * @return base64-encoded encrypted token (includes IV)
     * @throws IllegalArgumentException if plaintext or encryptionKey is null or empty
     */
    public static String encrypt(@Nonnull String plaintext, @Nonnull String encryptionKey) {
        if (plaintext.isEmpty()) {
            throw new IllegalArgumentException("Plaintext cannot be empty");
        }
        if (encryptionKey.isEmpty()) {
            throw new IllegalArgumentException("Encryption key cannot be empty");
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Encryption key must be 256 bits (32 bytes) when base64 decoded");
            }
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Failed to encrypt token", e);
            throw new RuntimeException("Token encryption failed", e);
        }
    }

    /**
     * Decrypt a token using AES-256-GCM.
     *
     * @param encryptedToken the base64-encoded encrypted token (includes IV) (must not be null or empty)
     * @param encryptionKey the base64-encoded encryption key (must be 32 bytes)
     * @return the decrypted token
     * @throws IllegalArgumentException if encryptedToken or encryptionKey is null or empty
     */
    public static String decrypt(@Nonnull String encryptedToken, @Nonnull String encryptionKey) {
        if (encryptedToken.isEmpty()) {
            throw new IllegalArgumentException("Encrypted token cannot be empty");
        }
        if (encryptionKey.isEmpty()) {
            throw new IllegalArgumentException("Encryption key cannot be empty");
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Encryption key must be 256 bits (32 bytes) when base64 decoded");
            }
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM);

            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedToken);

            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedBytes);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION, "BC");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plaintextBytes = cipher.doFinal(ciphertext);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt token", e);
            throw new RuntimeException("Token decryption failed", e);
        }
    }

}
