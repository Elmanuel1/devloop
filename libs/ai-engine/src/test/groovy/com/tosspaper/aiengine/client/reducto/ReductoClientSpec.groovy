package com.tosspaper.aiengine.client.reducto

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.client.common.exception.StartTaskException
import com.tosspaper.aiengine.client.reducto.exception.ReductoTaskException
import com.tosspaper.aiengine.client.reducto.exception.ReductoUploadException
import com.tosspaper.models.domain.FileObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import spock.lang.AutoCleanup
import spock.lang.Specification

import java.util.concurrent.TimeUnit

/**
 * Unit tests for ReductoClient using MockWebServer with custom baseUrl.
 * Tests HTTP interactions with Reducto API including:
 * - Presigned URL requests
 * - File uploads
 * - Async extract task creation
 * - Job status retrieval
 * - Error handling for various HTTP status codes
 *
 * CRITICAL: Uses the package-private constructor with custom baseUrl to test the REAL
 * ReductoClient class directly (not a subclass), ensuring JaCoCo coverage is properly attributed.
 */
class ReductoClientSpec extends Specification {

    @AutoCleanup
    MockWebServer mockServer = new MockWebServer()

    ObjectMapper objectMapper = new ObjectMapper()
    OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    String apiKey = "test-api-key"
    String webhookChannel = "test-webhook-channel"

    ReductoClient client

    def setup() {
        mockServer.start()
        // Use package-private constructor with custom baseUrl for testing
        def mockBaseUrl = mockServer.url("/").toString().replaceAll('/+$', '')
        client = new ReductoClient(apiKey, httpClient, objectMapper, webhookChannel, mockBaseUrl)
    }

    // ==================== requestPresignedUrl Tests ====================

    def "requestPresignedUrl should return upload response with file_id and presigned_url"() {
        given: "mock successful response"
            def responseBody = """
                {
                    "file_id": "reducto-file-12345",
                    "presigned_url": "https://storage.reducto.ai/upload/abc123?signature=xyz"
                }
            """
            mockServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.requestPresignedUrl()

        then:
            result.fileId == "reducto-file-12345"
            result.presignedUrl == "https://storage.reducto.ai/upload/abc123?signature=xyz"

            and: "request was sent with correct headers"
            def request = mockServer.takeRequest()
            request.getHeader("Authorization") == "Bearer test-api-key"
            request.method == "POST"
            request.path == "/upload"
    }

    def "requestPresignedUrl should throw ReductoUploadException on 401 error"() {
        given: "mock 401 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Invalid API key"}')
                .setResponseCode(401))

        when:
            client.requestPresignedUrl()

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Failed to request presigned URL")
            ex.message.contains("401")
    }

    def "requestPresignedUrl should throw ReductoUploadException on 500 error"() {
        given: "mock 500 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Internal server error"}')
                .setResponseCode(500))

        when:
            client.requestPresignedUrl()

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Failed to request presigned URL")
            ex.message.contains("500")
    }

    def "requestPresignedUrl should handle empty response body gracefully"() {
        given: "mock error response with empty body"
            mockServer.enqueue(new MockResponse()
                .setBody("")
                .setResponseCode(500))

        when:
            client.requestPresignedUrl()

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Failed to request presigned URL")
    }

    // ==================== uploadFile Tests ====================

    def "uploadFile should successfully upload file to presigned URL"() {
        given: "a file object and mock successful upload"
            def fileContent = "PDF content here".bytes
            def fileObject = FileObject.builder()
                .fileName("invoice.pdf")
                .contentType("application/pdf")
                .content(fileContent)
                .sizeBytes(fileContent.length)
                .build()

            def presignedUrl = mockServer.url("/upload/presigned").toString()
            mockServer.enqueue(new MockResponse().setResponseCode(200))

        when:
            client.uploadFile(fileObject, presignedUrl)

        then: "upload was successful"
            noExceptionThrown()

            and: "file was uploaded with PUT method"
            def request = mockServer.takeRequest()
            request.method == "PUT"
            request.body.readByteArray() == fileContent
    }

    def "uploadFile should throw ReductoUploadException on 403 with specific message"() {
        given: "a file object and mock 403 error"
            def fileObject = FileObject.builder()
                .fileName("test.pdf")
                .content("content".bytes)
                .sizeBytes(7)
                .build()

            def presignedUrl = mockServer.url("/upload/expired").toString()
            mockServer.enqueue(new MockResponse()
                .setBody("Signature has expired")
                .setResponseCode(403))

        when:
            client.uploadFile(fileObject, presignedUrl)

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Presigned URL expired or invalid signature")
            ex.message.contains("403")
    }

    def "uploadFile should throw ReductoUploadException on other errors"() {
        given: "a file object and mock 400 error"
            def fileObject = FileObject.builder()
                .fileName("test.pdf")
                .content("content".bytes)
                .sizeBytes(7)
                .build()

            def presignedUrl = mockServer.url("/upload/error").toString()
            mockServer.enqueue(new MockResponse()
                .setBody("Bad request")
                .setResponseCode(400))

        when:
            client.uploadFile(fileObject, presignedUrl)

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Failed to upload file")
            ex.message.contains("400")
    }

    def "uploadFile should handle empty error body gracefully"() {
        given: "a file object and mock error with empty body"
            def fileObject = FileObject.builder()
                .fileName("test.pdf")
                .content("content".bytes)
                .sizeBytes(7)
                .build()

            def presignedUrl = mockServer.url("/upload/error").toString()
            mockServer.enqueue(new MockResponse()
                .setBody("")
                .setResponseCode(500))

        when:
            client.uploadFile(fileObject, presignedUrl)

        then:
            def ex = thrown(ReductoUploadException)
            ex.message.contains("Failed to upload file")
    }

    // ==================== createAsyncExtractTask Tests ====================

    def "createAsyncExtractTask should return job response with job_id"() {
        given: "mock successful extract task creation"
            def responseBody = """
                {
                    "job_id": "job-abc123"
                }
            """
            mockServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

            def schema = '{"type": "object", "properties": {"invoice_number": {"type": "string"}}}'
            def systemPrompt = "Extract invoice data"

        when:
            def result = client.createAsyncExtractTask("file-id-123", schema, systemPrompt)

        then:
            result.jobId == "job-abc123"

            and: "request was sent with correct headers and body"
            def request = mockServer.takeRequest()
            request.getHeader("Authorization") == "Bearer test-api-key"
            request.getHeader("Content-Type").startsWith("application/json")
            request.method == "POST"
            request.path == "/extract_async"
    }

    def "createAsyncExtractTask should throw StartTaskException on 401 error"() {
        given: "mock 401 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Unauthorized"}')
                .setResponseCode(401))

        when:
            client.createAsyncExtractTask("file-id", "{}", "prompt")

        then:
            def ex = thrown(StartTaskException)
            ex.message.contains("Start task failed")
            ex.message.contains("401")
    }

    def "createAsyncExtractTask should throw StartTaskException on 422 validation error"() {
        given: "mock 422 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Validation failed"}')
                .setResponseCode(422))

        when:
            client.createAsyncExtractTask("file-id", "{}", "prompt")

        then:
            def ex = thrown(StartTaskException)
            ex.message.contains("Start task failed")
            ex.message.contains("422")
    }

    def "createAsyncExtractTask should throw ReductoTaskException on 500 error"() {
        given: "mock 500 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Internal server error"}')
                .setResponseCode(500))

        when:
            client.createAsyncExtractTask("file-id", "{}", "prompt")

        then:
            def ex = thrown(ReductoTaskException)
            ex.message.contains("Failed to create async extract task")
            ex.message.contains("500")
    }

    def "createAsyncExtractTask should handle empty response body on error"() {
        given: "mock error response with empty body"
            mockServer.enqueue(new MockResponse()
                .setBody("")
                .setResponseCode(500))

        when:
            client.createAsyncExtractTask("file-id", "{}", "prompt")

        then:
            def ex = thrown(ReductoTaskException)
            ex.message.contains("Failed to create async extract task")
    }

    // ==================== getJobStatus Tests ====================

    def "getJobStatus should return job status response"() {
        given: "mock successful job status response"
            def responseBody = """
                {
                    "status": "Completed",
                    "progress": 1.0,
                    "type": "Extract",
                    "num_pages": 5,
                    "total_pages": 5,
                    "duration": 12.5,
                    "result": {"invoice_number": "INV-001"}
                }
            """
            mockServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-123")

        then:
            result.status == "Completed"
            result.progress == 1.0
            result.type == "Extract"
            result.numPages == 5
            result.totalPages == 5
            result.duration == 12.5
            result.rawResponse != null

            and: "request was sent correctly"
            def request = mockServer.takeRequest()
            request.method == "GET"
            request.path == "/job/job-123"
            request.getHeader("Authorization") == "Bearer test-api-key"
    }

    def "getJobStatus should return pending status"() {
        given: "mock pending job status response"
            def responseBody = """
                {
                    "status": "Pending",
                    "progress": 0.5,
                    "type": "Extract",
                    "num_pages": 2,
                    "total_pages": 5
                }
            """
            mockServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-pending")

        then:
            result.status == "Pending"
            result.progress == 0.5
            result.mapToInternalStatus().toString() == "STARTED"
    }

    def "getJobStatus should return failed status"() {
        given: "mock failed job status response"
            def responseBody = """
                {
                    "status": "Failed",
                    "reason": "Unable to parse document"
                }
            """
            mockServer.enqueue(new MockResponse()
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-failed")

        then:
            result.status == "Failed"
            result.reason == "Unable to parse document"
            result.mapToInternalStatus().toString() == "FAILED"
    }

    def "getJobStatus should throw ReductoTaskException on 404 error"() {
        given: "mock 404 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Job not found"}')
                .setResponseCode(404))

        when:
            client.getJobStatus("non-existent-job")

        then:
            def ex = thrown(ReductoTaskException)
            ex.message.contains("Failed to get job status")
            ex.message.contains("404")
    }

    def "getJobStatus should throw ReductoTaskException on 500 error"() {
        given: "mock 500 error response"
            mockServer.enqueue(new MockResponse()
                .setBody('{"error": "Internal server error"}')
                .setResponseCode(500))

        when:
            client.getJobStatus("job-123")

        then:
            def ex = thrown(ReductoTaskException)
            ex.message.contains("Failed to get job status")
            ex.message.contains("500")
    }

    def "getJobStatus should handle empty response body on error"() {
        given: "mock error response with empty body"
            mockServer.enqueue(new MockResponse()
                .setBody("")
                .setResponseCode(500))

        when:
            client.getJobStatus("job-123")

        then:
            def ex = thrown(ReductoTaskException)
            ex.message.contains("Failed to get job status")
    }

    // ==================== Status Mapping Tests ====================

    def "mapToInternalStatus should map Completed to COMPLETED"() {
        given: "mock completed job status"
            mockServer.enqueue(new MockResponse()
                .setBody('{"status": "Completed"}')
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-1")

        then:
            result.mapToInternalStatus().toString() == "COMPLETED"
    }

    def "mapToInternalStatus should map Idle to PENDING"() {
        given: "mock idle job status"
            mockServer.enqueue(new MockResponse()
                .setBody('{"status": "Idle"}')
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-2")

        then:
            result.mapToInternalStatus().toString() == "PENDING"
    }

    def "mapToInternalStatus should map unknown status to MANUAL_INTERVENTION"() {
        given: "mock unknown job status"
            mockServer.enqueue(new MockResponse()
                .setBody('{"status": "Unknown"}')
                .setHeader("Content-Type", "application/json")
                .setResponseCode(200))

        when:
            def result = client.getJobStatus("job-3")

        then:
            result.mapToInternalStatus().toString() == "MANUAL_INTERVENTION"
    }
}
