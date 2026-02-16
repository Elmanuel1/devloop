package com.tosspaper.integrations.common.util

import spock.lang.Specification

import java.util.Base64

/**
 * Comprehensive tests for TokenEncryptionUtil.
 * Tests AES-256-GCM encryption and decryption of OAuth tokens.
 */
class TokenEncryptionUtilSpec extends Specification {

    // Valid 256-bit (32 bytes) encryption key, base64 encoded
    static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32])

    def "encrypt should successfully encrypt a token"() {
        given:
        def plaintext = "my-oauth-token-12345"

        when:
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)

        then:
        encrypted != null
        encrypted != plaintext
        encrypted.length() > plaintext.length()
    }

    def "decrypt should successfully decrypt an encrypted token"() {
        given:
        def plaintext = "my-oauth-token-67890"
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)

        when:
        def decrypted = TokenEncryptionUtil.decrypt(encrypted, VALID_KEY)

        then:
        decrypted == plaintext
    }

    def "encrypt and decrypt should work for long tokens"() {
        given:
        def plaintext = "a" * 1000 // 1000 character token

        when:
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)
        def decrypted = TokenEncryptionUtil.decrypt(encrypted, VALID_KEY)

        then:
        decrypted == plaintext
    }

    def "encrypt should throw exception for empty plaintext"() {
        when:
        TokenEncryptionUtil.encrypt("", VALID_KEY)

        then:
        thrown(IllegalArgumentException)
    }

    def "encrypt should throw exception for empty encryption key"() {
        when:
        TokenEncryptionUtil.encrypt("token", "")

        then:
        thrown(IllegalArgumentException)
    }

    def "encrypt should throw exception for invalid key length"() {
        given:
        def shortKey = Base64.getEncoder().encodeToString(new byte[16]) // Only 128 bits

        when:
        TokenEncryptionUtil.encrypt("token", shortKey)

        then:
        thrown(RuntimeException)
    }

    def "decrypt should throw exception for empty encrypted token"() {
        when:
        TokenEncryptionUtil.decrypt("", VALID_KEY)

        then:
        thrown(IllegalArgumentException)
    }

    def "decrypt should throw exception for empty encryption key"() {
        when:
        TokenEncryptionUtil.decrypt("encrypted", "")

        then:
        thrown(IllegalArgumentException)
    }

    def "decrypt should throw exception for tampered ciphertext"() {
        given:
        def plaintext = "my-token"
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)
        // Decode, flip bytes in the middle, re-encode to ensure substantial tampering
        def rawBytes = Base64.getDecoder().decode(encrypted)
        // Flip bytes in the ciphertext portion (after the 12-byte IV)
        for (int i = 12; i < Math.min(rawBytes.length, 20); i++) {
            rawBytes[i] = (byte)(rawBytes[i] ^ 0xFF)
        }
        def tamperedEncrypted = Base64.getEncoder().encodeToString(rawBytes)

        when:
        TokenEncryptionUtil.decrypt(tamperedEncrypted, VALID_KEY)

        then:
        thrown(RuntimeException)
    }

    def "decrypt should throw exception for wrong encryption key"() {
        given:
        def plaintext = "my-token"
        def key1 = Base64.getEncoder().encodeToString(new byte[32])
        def key2 = Base64.getEncoder().encodeToString(("x" * 32).bytes)
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, key1)

        when:
        TokenEncryptionUtil.decrypt(encrypted, key2)

        then:
        thrown(RuntimeException)
    }

    def "encrypt should produce different ciphertexts for same plaintext (unique IV)"() {
        given:
        def plaintext = "my-token"

        when:
        def encrypted1 = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)
        def encrypted2 = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)

        then:
        encrypted1 != encrypted2 // Different because of random IV
        TokenEncryptionUtil.decrypt(encrypted1, VALID_KEY) == plaintext
        TokenEncryptionUtil.decrypt(encrypted2, VALID_KEY) == plaintext
    }

    def "encrypt should handle special characters"() {
        given:
        def plaintext = "token-with-special-chars: !@#\$%^&*(){}[]<>?/\\|`~"

        when:
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)
        def decrypted = TokenEncryptionUtil.decrypt(encrypted, VALID_KEY)

        then:
        decrypted == plaintext
    }

    def "encrypt should handle unicode characters"() {
        given:
        def plaintext = "token-with-unicode: こんにちは 世界 🌍"

        when:
        def encrypted = TokenEncryptionUtil.encrypt(plaintext, VALID_KEY)
        def decrypted = TokenEncryptionUtil.decrypt(encrypted, VALID_KEY)

        then:
        decrypted == plaintext
    }

    def "decrypt should throw exception for invalid base64 ciphertext"() {
        when:
        TokenEncryptionUtil.decrypt("not-valid-base64!!!", VALID_KEY)

        then:
        thrown(RuntimeException)
    }

    def "decrypt should throw exception for too short ciphertext"() {
        given:
        def shortCiphertext = Base64.getEncoder().encodeToString(new byte[5])

        when:
        TokenEncryptionUtil.decrypt(shortCiphertext, VALID_KEY)

        then:
        thrown(RuntimeException)
    }
}
