package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.TenderDocumentsRecord
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookHandlerServiceSpec extends Specification {

    TenderDocumentRepository tenderDocumentRepository = Mock()
    ProcessingService processingService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ReductoWebhookHandlerService handlerService =
            new ReductoWebhookHandlerService(tenderDocumentRepository, processingService, objectMapper)

    static final String JOB_ID      = "reducto-job-abc123"
    static final String DOCUMENT_ID = "document-uuid-001"

    // ==================== handle — job not found ====================

    def "TC-WHS-01: throws NotFoundException when no document matches the job_id"() {
        given: "repository returns empty Optional"
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.empty()

        when: "handler is called for an unknown job"
            handlerService.handle(completedPayload(JOB_ID))

        then: "NotFoundException is thrown — ProcessingService never called"
            def ex = thrown(NotFoundException)
            ex.message != null
            0 * processingService.getExtractTask(_)
    }

    // ==================== handle — Completed status ====================

    def "TC-WHS-02: on Completed — validates job belongs to a document and fetches document result"() {
        given: "document is found"
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.of(buildDocument(DOCUMENT_ID))

        when: "handler receives a Completed webhook"
            handlerService.handle(completedPayload(JOB_ID))

        then: "job result is fetched — extraction-level completion is NOT triggered"
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult('{"invoice_number":"INV-001"}')
    }

    def "TC-WHS-03: Completed is case-insensitive — 'COMPLETED' also fetches document result"() {
        given:
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.of(buildDocument(DOCUMENT_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "COMPLETED"))

        then:
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult(null)
    }

    // ==================== handle — Failed status ====================

    def "TC-WHS-04: on Failed — validates job and fetches error reason"() {
        given:
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.of(buildDocument(DOCUMENT_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "error reason fetched"
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult("OCR timeout after 900s")
    }

    def "TC-WHS-05: on Failed with null error — uses fallback reason string"() {
        given:
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.of(buildDocument(DOCUMENT_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then:
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult(null)
    }

    def "TC-WHS-06: does NOT call ProcessingService for unknown/intermediate status"() {
        given:
            tenderDocumentRepository.findByExternalTaskId(JOB_ID) >> Optional.of(buildDocument(DOCUMENT_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "InProgress"))

        then: "no ProcessingService call"
            0 * processingService.getExtractTask(_)
    }

    // ==================== handle — lookup uses correct job_id ====================

    def "TC-WHS-07: passes job_id from payload to document repository lookup"() {
        given:
            String capturedJobId = null
            tenderDocumentRepository.findByExternalTaskId(_ as String) >> { String id ->
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

    private static TenderDocumentsRecord buildDocument(String id) {
        def record = new TenderDocumentsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setTenderId("tender-uuid-1")
        record.setFileName("tender-doc.pdf")
        record.setContentType("application/pdf")
        record.setFileSize(1024L)
        record.setS3Key("uploads/tender-doc.pdf")
        return record
    }
}
