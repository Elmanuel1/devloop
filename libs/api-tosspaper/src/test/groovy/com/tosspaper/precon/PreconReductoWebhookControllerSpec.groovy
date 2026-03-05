package com.tosspaper.precon

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.svix.exceptions.WebhookVerificationException
import org.springframework.http.HttpStatus
import spock.lang.Specification
import spock.lang.Subject

class PreconReductoWebhookControllerSpec extends Specification {

    // WebhookVerifier is an interface — easily mockable unlike the final Webhook class
    WebhookVerifier webhookVerifier = Mock()
    ReductoWebhookHandlerService handlerService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    // Use package-private constructor to inject the mock WebhookVerifier
    PreconReductoWebhookController controller = new PreconReductoWebhookController(
            webhookVerifier, handlerService, objectMapper)

    static final String VALID_PAYLOAD = '{"job_id":"job-abc","status":"Completed"}'
    static final String SVIX_ID       = "msg-001"
    static final String SVIX_TS       = "1700000000"
    static final String SVIX_SIG      = "v1,dGVzdHNpZw=="

    // ==================== Signature verification — success path ====================

    def "TC-WC-01: returns 200 OK when Svix signature is valid and handler completes successfully"() {
        given: "WebhookVerifier does not throw — signature is valid"
            webhookVerifier.verify(VALID_PAYLOAD, _) >> null

        when: "webhook is posted with valid headers"
            def response = controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "response is 200 and handler is called once"
            response.statusCode == HttpStatus.OK
            1 * handlerService.handle(_ as ReductoWebhookPayload)
    }

    // ==================== Signature verification — failure path ====================

    def "TC-WC-02: throws WebhookVerificationException when Svix signature verification fails"() {
        given: "WebhookVerifier throws WebhookVerificationException"
            webhookVerifier.verify(VALID_PAYLOAD, _) >> { throw new WebhookVerificationException("bad signature") }

        when: "webhook is posted with invalid signature"
            controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "exception propagates to global handler — handler service never called"
            thrown(WebhookVerificationException)
            0 * handlerService.handle(_)
    }

    def "TC-WC-03: throws WebhookVerificationException when svix-id header is missing"() {
        when: "webhook is posted without the svix-id header"
            controller.receiveWebhook(VALID_PAYLOAD, null, SVIX_TS, SVIX_SIG)

        then: "missing headers detected before verifier is called"
            thrown(WebhookVerificationException)
            0 * handlerService.handle(_)
    }

    def "TC-WC-04: throws WebhookVerificationException when svix-timestamp header is missing"() {
        when: "webhook is posted without the svix-timestamp header"
            controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, null, SVIX_SIG)

        then:
            thrown(WebhookVerificationException)
            0 * handlerService.handle(_)
    }

    def "TC-WC-05: throws WebhookVerificationException when svix-signature header is missing"() {
        when: "webhook is posted without the svix-signature header"
            controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, null)

        then:
            thrown(WebhookVerificationException)
            0 * handlerService.handle(_)
    }

    def "TC-WC-06: throws WebhookVerificationException when all Svix headers are missing"() {
        when: "webhook is posted without any Svix headers"
            controller.receiveWebhook(VALID_PAYLOAD, null, null, null)

        then:
            thrown(WebhookVerificationException)
            0 * handlerService.handle(_)
    }

    // ==================== DB not accessed before verification ====================

    def "TC-WC-07: handler service is never called on bad signature — no DB access"() {
        given: "signature verification fails"
            webhookVerifier.verify(_, _) >> { throw new WebhookVerificationException("invalid") }

        when: "webhook is posted"
            controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler service (which would access DB) is never invoked"
            thrown(WebhookVerificationException)
            0 * handlerService._
    }

    // ==================== Payload deserialisation ====================

    def "TC-WC-08: deserialises job_id and status from the raw body"() {
        given: "verification passes"
            webhookVerifier.verify(_, _) >> null

            ReductoWebhookPayload capturedPayload = null
            handlerService.handle(_ as ReductoWebhookPayload) >> { ReductoWebhookPayload p ->
                capturedPayload = p
            }

            def body = '{"job_id":"job-xyz","status":"Completed"}'

        when: "webhook is posted"
            controller.receiveWebhook(body, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler receives the correctly parsed payload"
            with(capturedPayload) {
                jobId == "job-xyz"
                status == "Completed"
            }
    }

    def "TC-WC-09: deserialises Failed status payload correctly"() {
        given: "verification passes"
            webhookVerifier.verify(_, _) >> null

            ReductoWebhookPayload capturedPayload = null
            handlerService.handle(_ as ReductoWebhookPayload) >> { ReductoWebhookPayload p ->
                capturedPayload = p
            }

            def body = '{"job_id":"job-fail","status":"Failed","metadata":{"user_id":"123"}}'

        when: "webhook is posted"
            controller.receiveWebhook(body, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler receives the failure payload"
            with(capturedPayload) {
                jobId == "job-fail"
                status == "Failed"
            }
    }

    def "TC-WC-10: throws JsonProcessingException when body is not valid JSON"() {
        given: "verification passes but body is malformed"
            webhookVerifier.verify(_, _) >> null

        when: "webhook is posted with malformed body"
            controller.receiveWebhook("not-json-at-all", SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "exception propagates to global handler — handler service never called"
            thrown(JsonProcessingException)
            0 * handlerService.handle(_)
    }

    // ==================== Header constant values ====================

    def "TC-WC-11: SVIX_ID_HEADER constant is 'svix-id'"() {
        expect:
            PreconReductoWebhookController.SVIX_ID_HEADER == "svix-id"
    }

    def "TC-WC-12: SVIX_TIMESTAMP_HEADER constant is 'svix-timestamp'"() {
        expect:
            PreconReductoWebhookController.SVIX_TIMESTAMP_HEADER == "svix-timestamp"
    }

    def "TC-WC-13: SVIX_SIGNATURE_HEADER constant is 'svix-signature'"() {
        expect:
            PreconReductoWebhookController.SVIX_SIGNATURE_HEADER == "svix-signature"
    }
}
