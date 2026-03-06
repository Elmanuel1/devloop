package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.models.exception.ReductoClientException
import com.tosspaper.models.precon.ConstructionDocumentType
import spock.lang.Specification
import spock.lang.Subject

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class HttpReductoClientSpec extends Specification {

    ReductoProperties props = new ReductoProperties()
    ObjectMapper mapper = new ObjectMapper()
    HttpClient httpClient = Mock()
    HttpResponse<String> httpResponse = Mock()

    @Subject
    HttpReductoClient client

    def setup() {
        props.setBaseUrl("https://api.reducto.ai")
        props.setApiKey("test-api-key")
        props.setWebhookBaseUrl("https://my-service.example.com")
        props.setWebhookPath("/internal/reducto/webhook")
        props.setDocumentCap(20)
        props.setTaskTimeoutMinutes(15)
        props.setTimeoutSeconds(30)

        client = new HttpReductoClient(props, mapper, httpClient)
    }

    // ── Success ───────────────────────────────────────────────────────────────

    def "TC-RC-01: successful submission returns task ID from Reducto response"() {
        given: "Reducto returns 200 with a task_id"
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": "task-abc-123"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            def result = client.submit(new ReductoSubmitRequest(
                    "ext-1", "doc-1", "tenders/1/1/doc-1/file.pdf",
                    "https://my-service.example.com/internal/reducto/webhook",
                    ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            result != null
            result.taskId() == "task-abc-123"
    }

    def "TC-RC-02: request includes Authorization header with Bearer token"() {
        given: "captures the outgoing HTTP request"
            def capturedRequests = []
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": "task-1"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> { HttpRequest req, handler ->
                capturedRequests << req
                return httpResponse
            }

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-1", "doc-1", "key/file.pdf",
                    "https://hook.example.com/webhook",
                    ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            capturedRequests.size() == 1
            capturedRequests[0].headers().firstValue("Authorization").orElse("") == "Bearer test-api-key"
    }

    def "TC-RC-03: request targets the /extract endpoint on the configured base URL"() {
        given:
            def capturedRequests = []
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": "task-2"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> { HttpRequest req, handler ->
                capturedRequests << req
                return httpResponse
            }

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-2", "doc-2", "key/file.pdf",
                    "https://hook.example.com/webhook",
                    ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            capturedRequests[0].uri().toString() == "https://api.reducto.ai/extract"
    }

    // ── Non-2xx responses ─────────────────────────────────────────────────────

    def "TC-RC-04: 400 response throws ReductoClientException"() {
        given:
            httpResponse.statusCode() >> 400
            httpResponse.body() >> '{"error": "bad request"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-3", "doc-3", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            thrown(ReductoClientException)
    }

    def "TC-RC-05: 500 response throws ReductoClientException"() {
        given:
            httpResponse.statusCode() >> 500
            httpResponse.body() >> '{"error": "internal error"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-4", "doc-4", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            thrown(ReductoClientException)
    }

    def "TC-RC-06: 401 Unauthorized throws ReductoClientException"() {
        given:
            httpResponse.statusCode() >> 401
            httpResponse.body() >> '{"error": "unauthorized"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-5", "doc-5", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            thrown(ReductoClientException)
    }

    // ── Missing task_id ───────────────────────────────────────────────────────

    def "TC-RC-07: 200 response with missing task_id throws ReductoClientException"() {
        given:
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"status": "ok"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-6", "doc-6", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            thrown(ReductoClientException)
    }

    def "TC-RC-08: 200 response with blank task_id throws ReductoClientException"() {
        given:
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": ""}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> httpResponse

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-7", "doc-7", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            thrown(ReductoClientException)
    }

    // ── Network failures ──────────────────────────────────────────────────────

    def "TC-RC-09: IOException from HttpClient is wrapped in ReductoClientException"() {
        given:
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >>
                    { throw new IOException("connection refused") }

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-8", "doc-8", "key/file.pdf", "https://hook.example.com/webhook", ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            def ex = thrown(ReductoClientException)
            ex.cause instanceof IOException
    }

    // ── Content-Type header ───────────────────────────────────────────────────

    def "TC-RC-10: request includes Content-Type application/json"() {
        given:
            def capturedRequests = []
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": "task-ct"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> { HttpRequest req, handler ->
                capturedRequests << req
                return httpResponse
            }

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-ct", "doc-ct", "key/file.pdf",
                    "https://hook.example.com/webhook",
                    ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            capturedRequests[0].headers().firstValue("Content-Type").orElse("") == "application/json"
    }

    // ── Timeout from properties ───────────────────────────────────────────────

    def "TC-RC-11: request timeout uses reductoProperties.timeoutSeconds — not a hardcoded constant"() {
        given: "timeout configured to 10 seconds"
            props.setTimeoutSeconds(10)
            def capturedRequests = []
            httpResponse.statusCode() >> 200
            httpResponse.body() >> '{"task_id": "task-to"}'
            httpClient.send(_ as HttpRequest, _ as HttpResponse.BodyHandler) >> { HttpRequest req, handler ->
                capturedRequests << req
                return httpResponse
            }

        when:
            client.submit(new ReductoSubmitRequest(
                    "ext-to", "doc-to", "key/file.pdf",
                    "https://hook.example.com/webhook",
                    ConstructionDocumentType.BILL_OF_QUANTITIES))

        then:
            capturedRequests[0].timeout().isPresent()
            capturedRequests[0].timeout().get().toSeconds() == 10
    }

    def "TC-RC-12: default timeoutSeconds in ReductoProperties is 30"() {
        expect:
            new ReductoProperties().getTimeoutSeconds() == 30
    }
}
