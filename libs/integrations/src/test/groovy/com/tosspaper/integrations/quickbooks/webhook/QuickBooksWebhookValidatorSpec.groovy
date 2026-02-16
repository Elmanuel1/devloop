package com.tosspaper.integrations.quickbooks.webhook

import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

/**
 * Comprehensive tests for QuickBooksWebhookValidator.
 * Tests HMAC SHA-256 signature validation for QuickBooks webhooks.
 */
class QuickBooksWebhookValidatorSpec extends Specification {

    def "validateSignature should return true for valid signature"() {
        given:
        def payload = '{"eventNotifications":[{"realmId":"123","entityId":"45"}]}'
        def verifierToken = "test-verifier-token-123"
        def signature = computeExpectedSignature(payload, verifierToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, signature, verifierToken)

        then:
        result == true
    }

    def "validateSignature should return false for invalid signature"() {
        given:
        def payload = '{"eventNotifications":[]}'
        def verifierToken = "test-verifier-token"
        def invalidSignature = "invalid-signature"

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, invalidSignature, verifierToken)

        then:
        result == false
    }

    def "validateSignature should return false for null payload"() {
        when:
        def result = QuickBooksWebhookValidator.validateSignature(null, "signature", "token")

        then:
        result == false
    }

    def "validateSignature should return false for null signature"() {
        when:
        def result = QuickBooksWebhookValidator.validateSignature("payload", null, "token")

        then:
        result == false
    }

    def "validateSignature should return false for null verifier token"() {
        when:
        def result = QuickBooksWebhookValidator.validateSignature("payload", "signature", null)

        then:
        result == false
    }

    def "validateSignature should be case-insensitive"() {
        given:
        def payload = '{"test":"data"}'
        def verifierToken = "secret"
        def signature = computeExpectedSignature(payload, verifierToken)
        def upperCaseSignature = signature.toUpperCase()

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, upperCaseSignature, verifierToken)

        then:
        result == true
    }

    def "validateSignature should return false for tampered payload"() {
        given:
        def originalPayload = '{"eventNotifications":[]}'
        def tamperedPayload = '{"eventNotifications":[{"realmId":"999"}]}'
        def verifierToken = "test-token"
        def signature = computeExpectedSignature(originalPayload, verifierToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(tamperedPayload, signature, verifierToken)

        then:
        result == false
    }

    def "validateSignature should return false for wrong verifier token"() {
        given:
        def payload = '{"data":"test"}'
        def correctToken = "correct-token"
        def wrongToken = "wrong-token"
        def signature = computeExpectedSignature(payload, correctToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, signature, wrongToken)

        then:
        result == false
    }

    def "validateSignature should handle empty payload"() {
        given:
        def payload = ""
        def verifierToken = "token"
        def signature = computeExpectedSignature(payload, verifierToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, signature, verifierToken)

        then:
        result == true
    }

    def "validateSignature should handle special characters in payload"() {
        given:
        def payload = '{"data":"special chars: !@#$%^&*(){}[]<>?/\\|`~"}'
        def verifierToken = "token"
        def signature = computeExpectedSignature(payload, verifierToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, signature, verifierToken)

        then:
        result == true
    }

    def "validateSignature should handle unicode characters in payload"() {
        given:
        def payload = '{"data":"unicode: こんにちは 🌍"}'
        def verifierToken = "token"
        def signature = computeExpectedSignature(payload, verifierToken)

        when:
        def result = QuickBooksWebhookValidator.validateSignature(payload, signature, verifierToken)

        then:
        result == true
    }

    def "validator should not be instantiable"() {
        when:
        def constructor = QuickBooksWebhookValidator.getDeclaredConstructor()
        constructor.setAccessible(true)
        constructor.newInstance()

        then:
        thrown(java.lang.reflect.InvocationTargetException)
    }

    private String computeExpectedSignature(String payload, String verifierToken) {
        Mac mac = Mac.getInstance("HmacSHA256")
        SecretKeySpec secretKeySpec = new SecretKeySpec(
            verifierToken.getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"
        )
        mac.init(secretKeySpec)
        byte[] hashBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashBytes)
    }
}
