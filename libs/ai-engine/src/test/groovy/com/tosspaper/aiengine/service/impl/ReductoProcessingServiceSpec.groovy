package com.tosspaper.aiengine.service.impl

import com.tosspaper.aiengine.client.common.exception.StartTaskException
import com.tosspaper.aiengine.client.reducto.ReductoClient
import com.tosspaper.aiengine.client.reducto.dto.ReductoAsyncExtractResponse
import com.tosspaper.aiengine.client.reducto.dto.ReductoJobStatusResponse
import com.tosspaper.aiengine.client.reducto.dto.ReductoUploadResponse
import com.tosspaper.aiengine.dto.StartTaskRequest
import com.tosspaper.models.domain.ExtractionStatus
import com.tosspaper.models.domain.ExtractionTask
import com.tosspaper.models.domain.FileObject
import spock.lang.Specification
import spock.lang.Subject

class ReductoProcessingServiceSpec extends Specification {

    ReductoClient reductoClient = Mock()

    @Subject
    ReductoProcessingService service = new ReductoProcessingService(reductoClient)

    // ==================== PREPARE TASK TESTS ====================

    def "prepareTask should upload file and return success"() {
        given: "a file object"
            def fileObject = FileObject.builder()
                .assignedId("attach-123")
                .fileName("document.pdf")
                .contentType("application/pdf")
                .content(new byte[100])
                .build()

        and: "presigned URL request succeeds"
            def uploadResponse = new ReductoUploadResponse()
            uploadResponse.setFileId("file-123")
            uploadResponse.setPresignedUrl("https://presigned-url")
            reductoClient.requestPresignedUrl() >> uploadResponse

        and: "upload succeeds"
            reductoClient.uploadFile(fileObject, "https://presigned-url") >> {}

        when: "preparing task"
            def result = service.prepareTask(fileObject)

        then: "result is successful"
            result.isSuccessful()
            result.preparationId == "file-123"
            result.status == ExtractionStatus.PREPARE_SUCCEEDED
    }

    def "prepareTask should return failure when presigned URL request fails"() {
        given: "a file object"
            def fileObject = FileObject.builder()
                .assignedId("attach-123")
                .fileName("document.pdf")
                .content(new byte[100])
                .build()

        and: "presigned URL request fails"
            reductoClient.requestPresignedUrl() >> { throw new IOException("Network error") }

        when: "preparing task"
            def result = service.prepareTask(fileObject)

        then: "result is failure"
            result.isFailed()
            result.error.contains("Preparation failed")
    }

    def "prepareTask should return failure when upload fails"() {
        given: "a file object"
            def fileObject = FileObject.builder()
                .assignedId("attach-123")
                .fileName("document.pdf")
                .content(new byte[100])
                .build()

        and: "presigned URL request succeeds"
            def uploadResponse = new ReductoUploadResponse()
            uploadResponse.setFileId("file-123")
            uploadResponse.setPresignedUrl("https://presigned-url")
            reductoClient.requestPresignedUrl() >> uploadResponse

        and: "upload fails"
            reductoClient.uploadFile(_, _) >> { throw new IOException("Upload failed") }

        when: "preparing task"
            def result = service.prepareTask(fileObject)

        then: "result is failure"
            result.isFailed()
    }

    // ==================== START TASK TESTS ====================

    def "startTask should create async extract task and return success"() {
        given: "a start task request"
            def request = StartTaskRequest.builder()
                .preparationId("prep-123")
                .schema('{"type": "object"}')
                .prompt("Extract data")
                .assignedId("attach-123")
                .build()

        and: "async extract succeeds"
            def extractResponse = new ReductoAsyncExtractResponse()
            extractResponse.setJobId("job-456")
            reductoClient.createAsyncExtractTask("prep-123", '{"type": "object"}', "Extract data") >> extractResponse

        when: "starting task"
            def result = service.startTask(request)

        then: "result is successful"
            result.isSuccessful()
            result.taskId == "job-456"
    }

    def "startTask should return failure on StartTaskException"() {
        given: "a start task request"
            def request = StartTaskRequest.builder()
                .preparationId("prep-123")
                .schema('{}')
                .prompt("Extract")
                .assignedId("attach-123")
                .build()

        and: "start task throws StartTaskException"
            reductoClient.createAsyncExtractTask(_, _, _) >> {
                throw new StartTaskException("Invalid schema")
            }

        when: "starting task"
            def result = service.startTask(request)

        then: "result is failed"
            result.isFailed()
            result.error.contains("Failed to start extraction task")
    }

    def "startTask should return unknown on generic exception"() {
        given: "a start task request"
            def request = StartTaskRequest.builder()
                .preparationId("prep-123")
                .schema('{}')
                .prompt("Extract")
                .assignedId("attach-123")
                .build()

        and: "start task throws generic exception"
            reductoClient.createAsyncExtractTask(_, _, _) >> {
                throw new RuntimeException("Unexpected error")
            }

        when: "starting task"
            def result = service.startTask(request)

        then: "result is unknown"
            result.isFailed()
            result.status == com.tosspaper.aiengine.client.common.dto.StartTaskResult.StartTaskStatus.UNKNOWN
    }

    // ==================== SEARCH EXECUTION TASK TESTS ====================

    def "searchExecutionTask should return not found"() {
        given: "an extraction task"
            def task = ExtractionTask.builder()
                .taskId("task-123")
                .build()

        when: "searching for task"
            def result = service.searchExecutionTask(task)

        then: "not found is returned"
            !result.isFound()
            result.error.contains("not supported")
    }

    // ==================== SEARCH EXECUTION FILE TESTS ====================

    def "searchExecutionFile should return not found"() {
        given: "an extraction task"
            def task = ExtractionTask.builder()
                .preparationId("file-123")
                .build()

        when: "searching for file"
            def result = service.searchExecutionFile(task)

        then: "not found is returned"
            !result.isFound()
            result.error.contains("not supported")
    }

    // ==================== GET EXTRACT TASK TESTS ====================

    def "getExtractTask should return completed task result"() {
        given: "a completed job status"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Completed")
                .rawResponse('{"result":{"result":{"documentType":{"value":"invoice"}}}}')
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result is found with status"
            result.isFound()
            result.taskId == "job-123"
            result.status == ExtractionStatus.COMPLETED
            result.type == "invoice"
    }

    def "getExtractTask should return failed task result"() {
        given: "a failed job status"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Failed")
                .rawResponse('{}')
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result is found with failed status"
            result.isFound()
            result.status == ExtractionStatus.FAILED
    }

    def "getExtractTask should return pending task result"() {
        given: "a pending job status"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Pending")
                .rawResponse('{}')
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result is found with started status"
            result.isFound()
            result.status == ExtractionStatus.STARTED
    }

    def "getExtractTask should handle exception"() {
        given: "API call fails"
            reductoClient.getJobStatus("job-123") >> { throw new IOException("Connection error") }

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "error result returned"
            !result.isFound()
            result.error.contains("Failed to get extract task")
    }

    def "getExtractTask should handle null raw response for document type"() {
        given: "a completed job status with null raw response"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Completed")
                .rawResponse(null)
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result has unknown type"
            result.isFound()
            result.type == "unknown"
    }

    def "getExtractTask should handle malformed raw response for document type"() {
        given: "a completed job with invalid JSON"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Completed")
                .rawResponse("not json")
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result has unknown type"
            result.isFound()
            result.type == "unknown"
    }

    def "getExtractTask should handle idle status"() {
        given: "an idle job status"
            def jobStatus = ReductoJobStatusResponse.builder()
                .status("Idle")
                .rawResponse('{}')
                .build()
            reductoClient.getJobStatus("job-123") >> jobStatus

        when: "getting extract task"
            def result = service.getExtractTask("job-123")

        then: "result is found with pending status"
            result.isFound()
            result.status == ExtractionStatus.PENDING
    }
}
