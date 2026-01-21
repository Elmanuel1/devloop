package com.tosspaper.integrations.api

import com.tosspaper.integrations.quickbooks.config.QuickBooksProperties
import com.tosspaper.integrations.quickbooks.webhook.QuickBooksWebhookValidator
import com.tosspaper.models.service.RedisStreamPublisher
import org.springframework.http.HttpStatus
import spock.lang.Specification

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

class QuickBooksWebhookControllerSpec extends Specification {

    QuickBooksProperties quickBooksProperties
    RedisStreamPublisher redisStreamPublisher
    QuickBooksWebhookController controller

    def setup() {
        quickBooksProperties = new QuickBooksProperties()
        quickBooksProperties.setWebhooks(new QuickBooksProperties.Webhooks())
        quickBooksProperties.getWebhooks().setVerifierToken("test-verifier-token")

        redisStreamPublisher = Mock()
        controller = new QuickBooksWebhookController(quickBooksProperties, redisStreamPublisher)
    }

    // ==================== handleWebhook - Valid Signature ====================

    def "handleWebhook returns OK and publishes to stream when signature is valid"() {
        given: "a valid webhook payload and signature"
            def payload = '{"eventNotifications": []}'
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "message is published to Redis stream"
            1 * redisStreamPublisher.publish("quickbooks-events", _) >> { args ->
                def message = args[1] as Map<String, String>
                assert message["payload"] == payload
                assert message["signature"] == signature
                assert message["timestamp"] != null
            }

        and: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body indicates success"
            response.body["status"] == "success"
            response.body["message"] == "Webhook event received and queued for processing"
    }

    def "handleWebhook handles complex JSON payload"() {
        given: "a complex webhook payload"
            def payload = '''{"eventNotifications":[{"realmId":"123","dataChangeEvent":{"entities":[{"id":"456","operation":"Create","name":"Invoice"}]}}]}'''
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "message is published"
            1 * redisStreamPublisher.publish("quickbooks-events", _) >> { args ->
                def message = args[1] as Map<String, String>
                assert message["payload"] == payload
            }

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    // ==================== handleWebhook - Invalid Signature ====================

    def "handleWebhook returns UNAUTHORIZED when signature is invalid"() {
        given: "an invalid signature"
            def payload = '{"eventNotifications": []}'
            def signature = "invalid-signature"

        when: "calling handleWebhook with invalid signature"
            def response = controller.handleWebhook(payload, signature)

        then: "no message is published"
            0 * redisStreamPublisher.publish(_, _)

        and: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED

        and: "response body contains error"
            response.body["error"] == "Invalid signature"
    }

    def "handleWebhook returns UNAUTHORIZED when signature is null"() {
        given: "a payload without signature"
            def payload = '{"eventNotifications": []}'

        when: "calling handleWebhook without signature"
            def response = controller.handleWebhook(payload, null)

        then: "no message is published"
            0 * redisStreamPublisher.publish(_, _)

        and: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED
    }

    def "handleWebhook returns UNAUTHORIZED when signature is empty"() {
        given: "a payload with empty signature"
            def payload = '{"eventNotifications": []}'
            def signature = ""

        when: "calling handleWebhook with empty signature"
            def response = controller.handleWebhook(payload, signature)

        then: "no message is published"
            0 * redisStreamPublisher.publish(_, _)

        and: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED
    }

    def "handleWebhook returns UNAUTHORIZED when payload is modified after signing"() {
        given: "signature for different payload"
            def originalPayload = '{"eventNotifications": []}'
            def modifiedPayload = '{"eventNotifications": [{"id": "1"}]}'
            def signature = computeValidSignature(originalPayload, "test-verifier-token")

        when: "calling handleWebhook with modified payload"
            def response = controller.handleWebhook(modifiedPayload, signature)

        then: "no message is published"
            0 * redisStreamPublisher.publish(_, _)

        and: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED
    }

    // ==================== handleWebhook - Edge Cases ====================

    def "handleWebhook handles empty payload with valid signature"() {
        given: "an empty payload"
            def payload = ''
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "message is published"
            1 * redisStreamPublisher.publish("quickbooks-events", _)

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "handleWebhook handles special characters in payload"() {
        given: "payload with special characters"
            def payload = '{"note": "Test with special chars: \\"quoted\\" & <tags> \u00e9"}'
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "message is published"
            1 * redisStreamPublisher.publish("quickbooks-events", _) >> { args ->
                def message = args[1] as Map<String, String>
                assert message["payload"] == payload
            }

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "handleWebhook handles very long payload"() {
        given: "a very long payload"
            def payload = '{"data": "' + ('x' * 10000) + '"}'
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "message is published"
            1 * redisStreamPublisher.publish("quickbooks-events", _)

        and: "response status is OK"
            response.statusCode == HttpStatus.OK
    }

    def "handleWebhook includes timestamp in published message"() {
        given: "a valid webhook payload and signature"
            def payload = '{"eventNotifications": []}'
            def signature = computeValidSignature(payload, "test-verifier-token")

        when: "calling handleWebhook"
            def response = controller.handleWebhook(payload, signature)

        then: "timestamp is included in message"
            1 * redisStreamPublisher.publish("quickbooks-events", _) >> { args ->
                def message = args[1] as Map<String, String>
                assert message["timestamp"] != null
                // Verify timestamp is in ISO format
                assert message["timestamp"].contains("T")
            }
    }

    // ==================== handleWebhook - Different Verifier Tokens ====================

    def "handleWebhook validates against configured verifier token"() {
        given: "a different verifier token configured"
            quickBooksProperties.getWebhooks().setVerifierToken("different-token")
            def payload = '{"eventNotifications": []}'
            def signatureWithOldToken = computeValidSignature(payload, "test-verifier-token")
            def signatureWithNewToken = computeValidSignature(payload, "different-token")

        when: "calling with signature from old token"
            def response1 = controller.handleWebhook(payload, signatureWithOldToken)

        then: "request is rejected"
            response1.statusCode == HttpStatus.UNAUTHORIZED

        when: "calling with signature from new token"
            def response2 = controller.handleWebhook(payload, signatureWithNewToken)

        then: "request is accepted"
            1 * redisStreamPublisher.publish(_, _)
            response2.statusCode == HttpStatus.OK
    }

    // ==================== Helper Methods ====================

    /**
     * Computes a valid HMAC SHA-256 signature for testing.
     * This mirrors the validation logic in QuickBooksWebhookValidator.
     */
    private static String computeValidSignature(String payload, String verifierToken) {
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
