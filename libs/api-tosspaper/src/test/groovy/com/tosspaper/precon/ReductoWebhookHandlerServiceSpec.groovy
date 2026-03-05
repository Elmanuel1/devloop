package com.tosspaper.precon

import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookHandlerServiceSpec extends Specification {

    PreconExtractionRepository preconExtractionRepository = Mock()
    ProcessingService processingService = Mock()

    @Subject
    ReductoWebhookHandlerService handlerService =
            new ReductoWebhookHandlerService(preconExtractionRepository, processingService)

    static final String JOB_ID        = "reducto-job-abc123"
    static final String EXTRACTION_ID = "extraction-uuid-001"

    // ==================== handle — job not found ====================

    def "TC-WHS-01: throws NotFoundException when no extraction matches the job_id"() {
        given: "repository returns empty Optional"
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.empty()

        when: "handler is called for an unknown job"
            handlerService.handle(completedPayload(JOB_ID))

        then: "NotFoundException is thrown — ProcessingService never called"
            def ex = thrown(NotFoundException)
            ex.message != null
            0 * processingService.getExtractTask(_)
    }

    // ==================== handle — Completed status ====================

    def "TC-WHS-02: on Completed — fetches job result and marks extraction completed"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1", "doc-2"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when: "handler receives a Completed webhook"
            handlerService.handle(completedPayload(JOB_ID))

        then: "job result fetched and extraction marked completed"
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult("some-raw-response")
            1 * preconExtractionRepository.markAsCompleted(EXTRACTION_ID, _ as PipelineExtractionResult) >> 1
    }

    def "TC-WHS-03: Completed is case-insensitive — 'COMPLETED' also triggers fetch and mark"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "COMPLETED"))

        then:
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult(null)
            1 * preconExtractionRepository.markAsCompleted(EXTRACTION_ID, _) >> 1
    }

    // ==================== handle — Failed status ====================

    def "TC-WHS-04: on Failed — fetches job result for error and marks extraction failed"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "job result fetched and extraction marked failed with error"
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult("OCR timeout after 900s")
            1 * preconExtractionRepository.markAsFailed(EXTRACTION_ID, "OCR timeout after 900s") >> 1
    }

    def "TC-WHS-05: on Failed with null error — uses fallback reason string"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "fallback reason is used when ProcessingService provides no error"
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult(null)
            1 * preconExtractionRepository.markAsFailed(EXTRACTION_ID, _ as String) >> 1
    }

    def "TC-WHS-06: does NOT call ProcessingService or mark extraction for unknown/intermediate status"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "InProgress"))

        then: "no ProcessingService call and no state change"
            0 * processingService.getExtractTask(_)
            0 * preconExtractionRepository.markAsCompleted(_, _)
            0 * preconExtractionRepository.markAsFailed(_, _)
    }

    // ==================== handle — lookup uses correct job_id ====================

    def "TC-WHS-07: passes job_id from payload to repository lookup"() {
        given:
            String capturedJobId = null
            preconExtractionRepository.findByExternalTaskId(_ as String) >> { String id ->
                capturedJobId = id
                return Optional.empty()
            }

        when:
            try {
                handlerService.handle(new ReductoWebhookPayload("specific-job-id-99", "Completed"))
            } catch (NotFoundException ignored) {}

        then:
            capturedJobId == "specific-job-id-99"
    }

    // ==================== Helper Methods ====================

    private static ReductoWebhookPayload completedPayload(String jobId) {
        return new ReductoWebhookPayload(jobId, "Completed")
    }

    private static ExtractTaskResult completedTaskResult(String rawResponse) {
        return ExtractTaskResult.builder()
                .found(true)
                .rawResponse(rawResponse)
                .build()
    }

    private static ExtractTaskResult failedTaskResult(String error) {
        return ExtractTaskResult.builder()
                .found(true)
                .error(error)
                .build()
    }

    private static ExtractionWithDocs buildExtractionWithDocs(String id, List<String> docIds) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-uuid-1")
        record.setVersion(2)
        return new ExtractionWithDocs(record, docIds)
    }
}
