package com.tosspaper.precon

import com.fasterxml.jackson.databind.ObjectMapper
import com.tosspaper.aiengine.client.common.dto.ExtractTaskResult
import com.tosspaper.aiengine.service.ProcessingService
import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookHandlerServiceSpec extends Specification {

    PreconExtractionRepository preconExtractionRepository = Mock()
    ProcessingService processingService = Mock()
    ObjectMapper objectMapper = new ObjectMapper()

    @Subject
    ReductoWebhookHandlerService handlerService =
            new ReductoWebhookHandlerService(preconExtractionRepository, processingService, objectMapper)

    static final String JOB_ID        = "reducto-job-abc123"
    static final String EXTRACTION_ID = "extraction-uuid-001"
    static final String DOCUMENT_ID   = "document-uuid-001"

    // ==================== handle — job not found ====================

    def "TC-WHS-01: throws NotFoundException when no extraction matches the job_id"() {
        given: "repository returns empty Optional"
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.empty()

        when: "handler is called for an unknown job"
            handlerService.handle(completedPayload(JOB_ID))

        then: "NotFoundException is thrown — ProcessingService never called"
            def ex = thrown(NotFoundException)
            ex.message != null
            0 * processingService.getExtractTask(_)
    }

    // ==================== handle — Completed status ====================

    def "TC-WHS-02: on Completed — validates job belongs to an extraction and fetches result"() {
        given: "extraction is found"
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.of(buildExtraction(EXTRACTION_ID))

        when: "handler receives a Completed webhook"
            handlerService.handle(completedPayload(JOB_ID))

        then: "job result is fetched — extraction-level completion is NOT triggered"
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult('{"invoice_number":"INV-001"}')
    }

    def "TC-WHS-03: Completed is case-insensitive — 'COMPLETED' also fetches result"() {
        given:
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.of(buildExtraction(EXTRACTION_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "COMPLETED"))

        then:
            1 * processingService.getExtractTask(JOB_ID) >> completedTaskResult(null)
    }

    // ==================== handle — Failed status ====================

    def "TC-WHS-04: on Failed — validates job and fetches error reason"() {
        given:
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.of(buildExtraction(EXTRACTION_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "error reason fetched"
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult("OCR timeout after 900s")
    }

    def "TC-WHS-05: on Failed with null error — uses fallback reason string"() {
        given:
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.of(buildExtraction(EXTRACTION_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then:
            1 * processingService.getExtractTask(JOB_ID) >> failedTaskResult(null)
    }

    def "TC-WHS-06: does NOT call ProcessingService for unknown/intermediate status"() {
        given:
            preconExtractionRepository.findByDocumentExternalTaskId(JOB_ID) >> Optional.of(buildExtraction(EXTRACTION_ID))

        when:
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "InProgress"))

        then: "no ProcessingService call"
            0 * processingService.getExtractTask(_)
    }

    // ==================== handle — lookup uses correct job_id ====================

    def "TC-WHS-07: passes job_id from payload to extraction repository lookup"() {
        given:
            String capturedJobId = null
            preconExtractionRepository.findByDocumentExternalTaskId(_ as String) >> { String id ->
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

    // ==================== setDocumentExternalId ====================

    def "TC-WHS-08: setDocumentExternalId merges new entry into empty map and calls updateDocumentExternalIds"() {
        given: "repository returns empty map for the extraction"
            preconExtractionRepository.getDocumentExternalIds(EXTRACTION_ID) >> [:]

        when: "setting an external task ID for a document"
            handlerService.setDocumentExternalId(EXTRACTION_ID, DOCUMENT_ID, "task-abc")

        then: "repository is called with a map containing the single new entry"
            1 * preconExtractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.size() == 1 && m[DOCUMENT_ID] == "task-abc"
            })
    }

    def "TC-WHS-09: setDocumentExternalId merges into existing map preserving other entries"() {
        given: "repository returns an existing map containing doc-1"
            preconExtractionRepository.getDocumentExternalIds(EXTRACTION_ID) >>
                ["doc-1": "old-task"]

        when: "setting an external task ID for a different document"
            handlerService.setDocumentExternalId(EXTRACTION_ID, "doc-2", "new-task")

        then: "repository is called with both entries in the map"
            1 * preconExtractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.size() == 2 &&
                m["doc-1"] == "old-task" &&
                m["doc-2"] == "new-task"
            })
    }

    def "TC-WHS-10: setDocumentExternalId replaces existing entry for the same document key"() {
        given: "repository returns an existing map with doc-1 already mapped"
            preconExtractionRepository.getDocumentExternalIds(EXTRACTION_ID) >>
                ["doc-1": "stale-task"]

        when: "setting a new task ID for the same doc-1 key"
            handlerService.setDocumentExternalId(EXTRACTION_ID, "doc-1", "fresh-task")

        then: "repository is called with the updated entry"
            1 * preconExtractionRepository.updateDocumentExternalIds(EXTRACTION_ID, { Map<String, String> m ->
                m.size() == 1 && m["doc-1"] == "fresh-task"
            })
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

    private static ExtractionsRecord buildExtraction(String id) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-uuid-1")
        return record
    }
}
