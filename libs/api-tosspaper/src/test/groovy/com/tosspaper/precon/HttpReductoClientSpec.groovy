package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.loaders.JsonSchemaLoader
import com.tosspaper.aiengine.loaders.PromptLoader
import com.tosspaper.models.exception.ReductoClientException
import com.tosspaper.models.precon.ConstructionDocumentType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import spock.lang.Specification
import spock.lang.Subject

class HttpReductoClientSpec extends Specification {

    ReductoProperties props = new ReductoProperties()
    ObjectMapper mapper = new ObjectMapper()
    OkHttpClient httpClient = Mock()
    JsonSchemaLoader schemaLoader = Mock()
    PromptLoader promptLoader = Mock()
    Call uploadCall = Mock()
    Call extractCall = Mock()

    static final byte[] DUMMY_BYTES = "dummy".bytes
    static final String TASK_ID = "task-abc-123"
    static final String FILE_ID = "file-xyz-456"

    @Subject
    HttpReductoClient client

    def setup() {
        props.setBaseUrl("https://api.reducto.ai")
        props.setApiKey("test-api-key")
        props.setWebhookBaseUrl("https://my-service.example.com")
        props.setWebhookPath("/internal/reducto/webhook")
        props.setSvixChannel("precon-extraction")
        props.setTaskTimeoutMinutes(15)
        props.setTimeoutSeconds(30)

        schemaLoader.loadSchema("extraction") >> '{"type":"object"}'
        promptLoader.loadPrompt("extraction") >> "Extract construction document data."

        client = new HttpReductoClient(props, mapper, httpClient, schemaLoader, promptLoader)
    }

    private static Response buildResponse(int code, String body, Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("")
                .body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
                .build()
    }

    private ExtractionSubmitRequest buildRequest() {
        return new ExtractionSubmitRequest(
                "ext-1", "doc-1", "tenders/1/1/doc-1/file.pdf",
                DUMMY_BYTES,
                "https://my-service.example.com/internal/reducto/webhook",
                ConstructionDocumentType.BILL_OF_QUANTITIES,
                null)
    }

    private ExtractionSubmitRequest buildRequestWithFileId(String fileId) {
        return new ExtractionSubmitRequest(
                "ext-1", "doc-1", "tenders/1/1/doc-1/file.pdf",
                DUMMY_BYTES,
                "https://my-service.example.com/internal/reducto/webhook",
                ConstructionDocumentType.BILL_OF_QUANTITIES,
                fileId)
    }

    // ── Success: two-step flow ────────────────────────────────────────────────

    def "TC-RC-01: successful submission calls upload then extract and returns task ID and file ID"() {
        given:
            def uploadReq = new Request.Builder().url("https://api.reducto.ai/upload").build()
            def extractReq = new Request.Builder().url("https://api.reducto.ai/extract").build()
            httpClient.newCall(_) >> uploadCall >> extractCall
            uploadCall.execute() >> buildResponse(200, """{"file_id": "$FILE_ID"}""", uploadReq)
            extractCall.execute() >> buildResponse(200, """{"task_id": "$TASK_ID"}""", extractReq)

        when:
            def result = client.submit(buildRequest())

        then:
            result.taskId() == TASK_ID
            result.fileId() == FILE_ID
    }

    def "TC-RC-02: upload request includes Authorization Bearer header"() {
        given:
            def capturedUpload = []
            def capturedExtract = []
            httpClient.newCall(_) >> { Request req ->
                if (req.url().encodedPath().endsWith("/upload")) {
                    capturedUpload << req
                    return uploadCall
                }
                capturedExtract << req
                return extractCall
            }
            uploadCall.execute() >> { buildResponse(200, """{"file_id": "$FILE_ID"}""", capturedUpload[0]) }
            extractCall.execute() >> { buildResponse(200, """{"task_id": "$TASK_ID"}""", capturedExtract[0]) }

        when:
            client.submit(buildRequest())

        then:
            capturedUpload.size() == 1
            capturedUpload[0].header("Authorization") == "Bearer test-api-key"
    }

    def "TC-RC-03: extract request targets the /extract path"() {
        given:
            def capturedExtract = []
            httpClient.newCall(_) >> { Request req ->
                if (req.url().encodedPath().endsWith("/upload")) return uploadCall
                capturedExtract << req
                return extractCall
            }
            uploadCall.execute() >> { buildResponse(200, """{"file_id": "$FILE_ID"}""", new Request.Builder().url("https://api.reducto.ai/upload").build()) }
            extractCall.execute() >> { buildResponse(200, """{"task_id": "$TASK_ID"}""", capturedExtract[0]) }

        when:
            client.submit(buildRequest())

        then:
            capturedExtract.size() == 1
            capturedExtract[0].url().encodedPath() == "/extract"
    }

    def "TC-RC-04: extract request includes the file_id returned from upload"() {
        given:
            def extractBodies = []
            httpClient.newCall(_) >> { Request req ->
                if (req.url().encodedPath().endsWith("/upload")) return uploadCall
                extractBodies << req.body()
                return extractCall
            }
            uploadCall.execute() >> { buildResponse(200, """{"file_id": "$FILE_ID"}""", new Request.Builder().url("https://api.reducto.ai/upload").build()) }
            extractCall.execute() >> { buildResponse(200, """{"task_id": "$TASK_ID"}""", new Request.Builder().url("https://api.reducto.ai/extract").build()) }

        when:
            client.submit(buildRequest())

        then:
            extractBodies.size() == 1
            def buffer = new okio.Buffer()
            extractBodies[0].writeTo(buffer)
            buffer.readUtf8().contains(FILE_ID)
    }

    // ── Upload failures ───────────────────────────────────────────────────────

    def "TC-RC-05: non-2xx upload response throws ReductoClientException"() {
        given:
            httpClient.newCall(_) >> uploadCall
            uploadCall.execute() >> { buildResponse(500, '{"error": "server error"}', new Request.Builder().url("https://api.reducto.ai/upload").build()) }

        when:
            client.submit(buildRequest())

        then:
            thrown(ReductoClientException)
    }

    def "TC-RC-06: upload response with missing file_id throws ReductoClientException"() {
        given:
            httpClient.newCall(_) >> uploadCall
            uploadCall.execute() >> { buildResponse(200, '{"status": "ok"}', new Request.Builder().url("https://api.reducto.ai/upload").build()) }

        when:
            client.submit(buildRequest())

        then:
            thrown(ReductoClientException)
    }

    // ── Extract failures ──────────────────────────────────────────────────────

    def "TC-RC-07: non-2xx extract response throws ReductoClientException"() {
        given:
            httpClient.newCall(_) >> uploadCall >> extractCall
            uploadCall.execute() >> { buildResponse(200, """{"file_id": "$FILE_ID"}""", new Request.Builder().url("https://api.reducto.ai/upload").build()) }
            extractCall.execute() >> { buildResponse(400, '{"error": "bad request"}', new Request.Builder().url("https://api.reducto.ai/extract").build()) }

        when:
            client.submit(buildRequest())

        then:
            thrown(ReductoClientException)
    }

    def "TC-RC-08: extract response with missing task_id throws ReductoClientException"() {
        given:
            httpClient.newCall(_) >> uploadCall >> extractCall
            uploadCall.execute() >> { buildResponse(200, """{"file_id": "$FILE_ID"}""", new Request.Builder().url("https://api.reducto.ai/upload").build()) }
            extractCall.execute() >> { buildResponse(200, '{"status": "ok"}', new Request.Builder().url("https://api.reducto.ai/extract").build()) }

        when:
            client.submit(buildRequest())

        then:
            thrown(ReductoClientException)
    }

    // ── Skip upload when external_file_id already set ─────────────────────────

    def "TC-RC-10: upload is skipped when externalFileId is non-blank"() {
        given: "request with an existing fileId — no upload call should happen"
            def extractReq = new Request.Builder().url("https://api.reducto.ai/extract").build()
            httpClient.newCall(_) >> extractCall
            extractCall.execute() >> buildResponse(200, """{"task_id": "$TASK_ID"}""", extractReq)

        when:
            def result = client.submit(buildRequestWithFileId(FILE_ID))

        then: "upload endpoint is never called"
            0 * uploadCall.execute()

        and: "task ID is returned with the pre-existing file ID"
            result.taskId() == TASK_ID
            result.fileId() == FILE_ID
    }

    // ── Default properties ────────────────────────────────────────────────────

    def "TC-RC-09: default timeoutSeconds in ReductoProperties is 30"() {
        expect:
            new ReductoProperties().getTimeoutSeconds() == 30
    }
}
