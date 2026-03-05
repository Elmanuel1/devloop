package com.tosspaper.precon

import com.tosspaper.common.NotFoundException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ReductoWebhookHandlerServiceSpec extends Specification {

    PreconExtractionRepository preconExtractionRepository = Mock()
    ConflictDetector conflictDetector = Mock()

    @Subject
    ReductoWebhookHandlerService handlerService =
            new ReductoWebhookHandlerService(preconExtractionRepository, conflictDetector)

    static final String JOB_ID        = "reducto-task-abc123"
    static final String EXTRACTION_ID = "extraction-uuid-001"

    // ==================== handle — job not found ====================

    def "TC-WHS-01: throws NotFoundException when no extraction matches the job_id"() {
        given: "repository returns empty Optional"
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.empty()

        when: "handler is called for an unknown job"
            handlerService.handle(completedPayload(JOB_ID))

        then: "NotFoundException is thrown — ConflictDetector never runs"
            def ex = thrown(NotFoundException)
            ex.message != null
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    // ==================== handle — completed status ====================

    def "TC-WHS-02: runs conflict detection when payload status is 'Completed'"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1", "doc-2"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)
            conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 3

        when: "handler receives a Completed webhook"
            handlerService.handle(completedPayload(JOB_ID))

        then: "conflict detection runs once for the extraction"
            1 * conflictDetector.detectAndMarkConflicts(EXTRACTION_ID)
    }

    def "TC-WHS-03: runs conflict detection with case-insensitive 'COMPLETED' status"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)
            conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 0

        when: "handler receives a webhook with status in uppercase"
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "COMPLETED"))

        then: "conflict detection still runs"
            1 * conflictDetector.detectAndMarkConflicts(EXTRACTION_ID)
    }

    // ==================== handle — failed status ====================

    def "TC-WHS-04: does NOT run conflict detection when payload status is 'Failed'"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when: "handler receives a Failed webhook"
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "Failed"))

        then: "conflict detection is NOT called for failures"
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    def "TC-WHS-05: does NOT run conflict detection for unknown/null status"() {
        given: "extraction is found"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)

        when: "handler receives a webhook with an unknown status"
            handlerService.handle(new ReductoWebhookPayload(JOB_ID, "InProgress"))

        then: "conflict detection is NOT called"
            0 * conflictDetector.detectAndMarkConflicts(_)
    }

    // ==================== handle — conflict detector result ====================

    def "TC-WHS-06: handles zero conflicted rows from ConflictDetector without error"() {
        given: "extraction found, no conflicts detected"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)
            conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 0

        when: "handler is called"
            handlerService.handle(completedPayload(JOB_ID))

        then: "completes without exception"
            noExceptionThrown()
    }

    def "TC-WHS-07: handles multiple conflicted rows from ConflictDetector without error"() {
        given: "extraction found, 5 conflicts detected"
            def extraction = buildExtractionWithDocs(EXTRACTION_ID, ["doc-1", "doc-2", "doc-3"])
            preconExtractionRepository.findByExternalTaskId(JOB_ID) >> Optional.of(extraction)
            conflictDetector.detectAndMarkConflicts(EXTRACTION_ID) >> 5

        when: "handler is called"
            handlerService.handle(completedPayload(JOB_ID))

        then: "completes without exception"
            noExceptionThrown()
    }

    // ==================== handle — lookup uses correct job_id ====================

    def "TC-WHS-08: passes job_id from payload to repository lookup"() {
        given: "capture the job_id passed to findByExternalTaskId"
            String capturedJobId = null
            preconExtractionRepository.findByExternalTaskId(_ as String) >> { String id ->
                capturedJobId = id
                return Optional.empty()
            }

        when: "handler is called — NotFoundException expected"
            def payload = new ReductoWebhookPayload("specific-job-id-99", "Completed")
            try {
                handlerService.handle(payload)
            } catch (NotFoundException ignored) {}

        then: "repository was queried with the exact job_id from the payload"
            capturedJobId == "specific-job-id-99"
    }

    // ==================== Helper Methods ====================

    private static ReductoWebhookPayload completedPayload(String jobId) {
        return new ReductoWebhookPayload(jobId, "Completed")
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
