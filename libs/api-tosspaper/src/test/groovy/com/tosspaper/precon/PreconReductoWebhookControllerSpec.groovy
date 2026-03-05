package com.tosspaper.precon

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

    static final String VALID_PAYLOAD = '{"task_id":"task-abc","status":"completed","result":null,"error":null}'
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

    def "TC-WC-02: returns 401 when Svix signature verification fails"() {
        given: "WebhookVerifier throws WebhookVerificationException"
            webhookVerifier.verify(VALID_PAYLOAD, _) >> { throw new WebhookVerificationException("bad signature") }

        when: "webhook is posted with invalid signature"
            def response = controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "response is 401 and handler is never called"
            response.statusCode == HttpStatus.UNAUTHORIZED
            0 * handlerService.handle(_)
    }

    def "TC-WC-03: returns 401 when svix-id header is missing"() {
        when: "webhook is posted without the svix-id header"
            def response = controller.receiveWebhook(VALID_PAYLOAD, null, SVIX_TS, SVIX_SIG)

        then: "response is 401 — missing headers detected before verifier is called"
            response.statusCode == HttpStatus.UNAUTHORIZED
            0 * handlerService.handle(_)
    }

    def "TC-WC-04: returns 401 when svix-timestamp header is missing"() {
        when: "webhook is posted without the svix-timestamp header"
            def response = controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, null, SVIX_SIG)

        then: "response is 401"
            response.statusCode == HttpStatus.UNAUTHORIZED
            0 * handlerService.handle(_)
    }

    def "TC-WC-05: returns 401 when svix-signature header is missing"() {
        when: "webhook is posted without the svix-signature header"
            def response = controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, null)

        then: "response is 401"
            response.statusCode == HttpStatus.UNAUTHORIZED
            0 * handlerService.handle(_)
    }

    def "TC-WC-06: returns 401 when all Svix headers are missing"() {
        when: "webhook is posted without any Svix headers"
            def response = controller.receiveWebhook(VALID_PAYLOAD, null, null, null)

        then: "response is 401"
            response.statusCode == HttpStatus.UNAUTHORIZED
            0 * handlerService.handle(_)
    }

    // ==================== DB not accessed before verification ====================

    def "TC-WC-07: handler service is never called on bad signature — no DB access"() {
        given: "signature verification fails"
            webhookVerifier.verify(_, _) >> { throw new WebhookVerificationException("invalid") }

        when: "webhook is posted"
            controller.receiveWebhook(VALID_PAYLOAD, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler service (which would access DB) is never invoked"
            0 * handlerService._
    }

    // ==================== Payload deserialisation ====================

    def "TC-WC-08: deserialises task_id and status from the raw body"() {
        given: "verification passes"
            webhookVerifier.verify(_, _) >> null

            ReductoWebhookPayload capturedPayload = null
            handlerService.handle(_ as ReductoWebhookPayload) >> { ReductoWebhookPayload p ->
                capturedPayload = p
            }

            def body = '{"task_id":"task-xyz","status":"completed","result":null,"error":null}'

        when: "webhook is posted"
            controller.receiveWebhook(body, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler receives the correctly parsed payload"
            with(capturedPayload) {
                taskId == "task-xyz"
                status == "completed"
                // "result": null in JSON becomes a NullNode instance, not Java null
                result == null || result.isNull()
            }
    }

    def "TC-WC-09: deserialises failed status payload correctly"() {
        given: "verification passes"
            webhookVerifier.verify(_, _) >> null

            ReductoWebhookPayload capturedPayload = null
            handlerService.handle(_ as ReductoWebhookPayload) >> { ReductoWebhookPayload p ->
                capturedPayload = p
            }

            def body = '{"task_id":"task-fail","status":"failed","result":null,"error":"Extraction timed out"}'

        when: "webhook is posted"
            controller.receiveWebhook(body, SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "handler receives the failure payload"
            with(capturedPayload) {
                taskId == "task-fail"
                status == "failed"
                error == "Extraction timed out"
            }
    }

    def "TC-WC-10: returns 400 when body is not valid JSON"() {
        given: "verification passes but body is malformed"
            webhookVerifier.verify(_, _) >> null

        when: "webhook is posted with malformed body"
            def response = controller.receiveWebhook("not-json-at-all", SVIX_ID, SVIX_TS, SVIX_SIG)

        then: "response is 400 Bad Request — handler is never called"
            response.statusCode == HttpStatus.BAD_REQUEST
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
