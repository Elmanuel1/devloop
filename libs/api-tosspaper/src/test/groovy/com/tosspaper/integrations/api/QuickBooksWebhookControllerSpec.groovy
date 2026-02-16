package com.tosspaper.integrations.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.config.BaseIntegrationTest
import com.tosspaper.models.service.RedisStreamPublisher
import org.spockframework.spring.SpringBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets

@TestPropertySource(properties = [
    "app.integrations.quickbooks.webhooks.verifier-token=test-verifier-token"
])
class QuickBooksWebhookControllerSpec extends BaseIntegrationTest {

    @SpringBean
    RedisStreamPublisher redisStreamPublisher = Mock()

    static final String VERIFIER_TOKEN = "test-verifier-token"

    @Autowired
    TestRestTemplate restTemplate

    @Autowired
    ObjectMapper objectMapper

    /**
     * Creates HTTP headers for webhook requests.
     * Webhook paths are CSRF-exempt (external service calls), so no CSRF token needed.
     */
    private HttpHeaders createWebhookHeaders(String signature) {
        def headers = new HttpHeaders()
        headers.setContentType(MediaType.APPLICATION_JSON)
        if (signature != null) {
            headers.set("intuit-signature", signature)
        }
        return headers
    }

    // ==================== handleWebhook - Valid Signature ====================

    def "handleWebhook returns OK and publishes to stream when signature is valid"() {
        given: "a valid webhook payload and signature"
            def payload = '{"eventNotifications": []}'
            def signature = computeValidSignature(payload, VERIFIER_TOKEN)

        and: "request headers with intuit-signature"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "response body indicates success"
            def body = objectMapper.readValue(response.body, Map)
            body.status == "success"
            body.message == "Webhook event received and queued for processing"

        and: "message is published to Redis stream"
            1 * redisStreamPublisher.publish("quickbooks-events", { Map<String, String> message ->
                message["payload"] == payload &&
                message["signature"] == signature &&
                message["timestamp"] != null
            })
    }

    def "handleWebhook handles complex JSON payload"() {
        given: "a complex webhook payload"
            def payload = '{"eventNotifications":[{"realmId":"123","dataChangeEvent":{"entities":[{"id":"456","operation":"Create","name":"Invoice"}]}}]}'
            def signature = computeValidSignature(payload, VERIFIER_TOKEN)

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "message is published with correct payload"
            1 * redisStreamPublisher.publish("quickbooks-events", { Map<String, String> message ->
                message["payload"] == payload
            })
    }

    // ==================== handleWebhook - Invalid Signature ====================

    def "handleWebhook returns UNAUTHORIZED when signature is invalid"() {
        given: "an invalid signature"
            def payload = '{"eventNotifications": []}'
            def signature = "invalid-signature"

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook with invalid signature"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED

        and: "response body contains error"
            def body = objectMapper.readValue(response.body, Map)
            body.error == "Invalid signature"

        and: "no message is published"
            0 * redisStreamPublisher.publish(_, _)
    }

    def "handleWebhook returns UNAUTHORIZED when signature is missing"() {
        given: "a payload without signature header"
            def payload = '{"eventNotifications": []}'

        and: "request headers without intuit-signature"
            def headers = createWebhookHeaders(null)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook without signature"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED

        and: "no message is published"
            0 * redisStreamPublisher.publish(_, _)
    }

    def "handleWebhook returns UNAUTHORIZED when signature is empty"() {
        given: "a payload with empty signature"
            def payload = '{"eventNotifications": []}'

        and: "request headers with empty intuit-signature"
            def headers = createWebhookHeaders("")
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook with empty signature"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED

        and: "no message is published"
            0 * redisStreamPublisher.publish(_, _)
    }

    def "handleWebhook returns UNAUTHORIZED when payload is modified after signing"() {
        given: "signature for different payload"
            def originalPayload = '{"eventNotifications": []}'
            def modifiedPayload = '{"eventNotifications": [{"id": "1"}]}'
            def signature = computeValidSignature(originalPayload, VERIFIER_TOKEN)

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(modifiedPayload, headers)

        when: "calling handleWebhook with modified payload"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is UNAUTHORIZED"
            response.statusCode == HttpStatus.UNAUTHORIZED

        and: "no message is published"
            0 * redisStreamPublisher.publish(_, _)
    }

    // ==================== handleWebhook - Edge Cases ====================

    def "handleWebhook handles special characters in payload"() {
        given: "payload with special characters"
            def payload = '{"note": "Test with special chars: \\"quoted\\" & <tags> \u00e9"}'
            def signature = computeValidSignature(payload, VERIFIER_TOKEN)

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "message is published with correct payload"
            1 * redisStreamPublisher.publish("quickbooks-events", { Map<String, String> message ->
                message["payload"] == payload
            })
    }

    def "handleWebhook handles very long payload"() {
        given: "a very long payload"
            def payload = '{"data": "' + ('x' * 10000) + '"}'
            def signature = computeValidSignature(payload, VERIFIER_TOKEN)

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "message is published"
            1 * redisStreamPublisher.publish("quickbooks-events", _)
    }

    def "handleWebhook includes timestamp in published message"() {
        given: "a valid webhook payload and signature"
            def payload = '{"eventNotifications": []}'
            def signature = computeValidSignature(payload, VERIFIER_TOKEN)

        and: "request headers"
            def headers = createWebhookHeaders(signature)
            def entity = new HttpEntity<>(payload, headers)

        when: "calling handleWebhook"
            def response = restTemplate.postForEntity("/v1/quickbooks/events", entity, String)

        then: "response status is OK"
            response.statusCode == HttpStatus.OK

        and: "timestamp is included in message"
            1 * redisStreamPublisher.publish("quickbooks-events", { Map<String, String> message ->
                message["timestamp"] != null && message["timestamp"].contains("T")
            })
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
