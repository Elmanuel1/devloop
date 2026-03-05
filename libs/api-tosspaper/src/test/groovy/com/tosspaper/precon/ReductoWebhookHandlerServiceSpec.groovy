package com.tosspaper.precon

import com.tosspaper.aiengine.client.reducto.ReductoClient
import com.tosspaper.aiengine.client.reducto.dto.ReductoJobStatusResponse
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookHandlerServiceSpec extends Specification {

    PreconExtractionRepository preconExtractionRepository = Mock()
    ConflictDetector conflictDetector = Mock()
    ReductoClient reductoClient = Mock()

    @Subject
    ReductoWebhookHandlerService handlerService =
            new ReductoWebhookHandlerService(preconExtractionRepository, conflictDetector, reductoClient)

    static final String JOB_ID        = "reducto-job-abc123"
    static final String EXTRACTION_ID = "extraction-uuid-001"

    // ==================== handle — job not found ====================

    def "TC-WHS-01: throws NotFoundException when no extraction matches the job_id"() {
        given: "repository returns empty Optional"
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.empty()

        when: "handler is called for an unknown job"
            handlerService.handle(completedPayload(JOB_ID))

        then: "NotFoundException is thrown — Reducto API and ConflictDetector never called"
            def ex = thrown(NotFoundException)
            ex.message != null
            0 * reductoClient.getJobStatus(_)
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    // ==================== handle — Completed status ====================

    def "TC-WHS-02: on Completed — fetches job result, marks completed, runs conflict detection"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1", "doc-2"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when: "handler receives a Completed webhook"
            handlerService.handle(completedPayload(JOB_ID))

        then: "job result fetched, extraction marked completed, conflict detection runs"
            1 * reductoClient.getJobStatus(JOB_ID) >> completedJobStatus("some-raw-response")
            1 * preconExtractionRepository.markAsCompleted(EXTRACTION_ID, _ as PipelineExtractionResult) >> 1
            1 * conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 3
    }

    def "TC-WHS-03: Completed is case-insensitive — 'COMPLETED' also triggers fetch and conflict detection"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "COMPLETED"))

        then:
            1 * reductoClient.getJobStatus(JOB_ID) >> completedJobStatus(null)
            1 * preconExtractionRepository.markAsCompleted(EXTRACTION_ID, _) >> 1
            1 * conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 0
    }

    // ==================== handle — Failed status ====================

    def "TC-WHS-04: on Failed — fetches job status for reason and marks extraction failed"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "job status fetched and extraction marked failed with reason"
            1 * reductoClient.getJobStatus(JOB_ID) >> failedJobStatus("OCR timeout after 900s")
            1 * preconExtractionRepository.markAsFailed(EXTRACTION_ID, "OCR timeout after 900s") >> 1
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    def "TC-WHS-05: on Failed with null reason — uses fallback reason string"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "fallback reason is used when Reducto provides no reason"
            1 * reductoClient.getJobStatus(JOB_ID) >> failedJobStatus(null)
            1 * preconExtractionRepository.markAsFailed(EXTRACTION_ID, _ as String) >> 1
    }

    def "TC-WHS-06: does NOT call Reducto API or mark extraction for unknown/intermediate status"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "InProgress"))

        then: "no Reducto API call, no state change, no conflict detection"
            0 * reductoClient.getJobStatus(_)
            0 * preconExtractionRepository.markAsCompleted(_, _)
            0 * preconExtractionRepository.markAsFailed(_, _)
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    // ==================== handle — Reducto API failure ====================

    def "TC-WHS-07: wraps IOException from getJobStatus as IllegalStateException"() {
        given:
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)
            reductoClient.getJobStatus(JOB_ID) >> { throw new IOException("connection refused") }

        when:
            handlerService.handle(completedPayload(JOB_ID))

        then:
            thrown(IllegalStateException)
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    // ==================== handle — lookup uses correct job_id ====================

    def "TC-WHS-08: passes job_id from payload to repository lookup"() {
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

    private static ReductoJobStatusResponse completedJobStatus(String rawResponse) {
        return ReductoJobStatusResponse.builder()
                .status("Completed")
                .rawResponse(rawResponse)
                .build()
    }

    private static ReductoJobStatusResponse failedJobStatus(String reason) {
        return ReductoJobStatusResponse.builder()
                .status("Failed")
                .reason(reason)
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
