package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionPipelineRunner pipelineRunner = Mock()

    @Subject
    ExtractionPollJob job = new ExtractionPollJob(repository, pipelineRunner, 5000L)

    // ==================== SmartLifecycle ====================

    def "TC-PJ-01: isRunning returns false before start"() {
        expect:
            !job.isRunning()
    }

    def "TC-PJ-02: isRunning returns true after start and false after stop"() {
        when: "job is started"
            job.start()

        then: "isRunning is true"
            job.isRunning()

        when: "job is stopped"
            job.stop()

        then: "isRunning is false"
            !job.isRunning()
    }

    def "TC-PJ-03: isAutoStartup returns true"() {
        expect:
            job.isAutoStartup()
    }

    def "TC-PJ-04: stop is idempotent — does not throw when called without start"() {
        when: "stop is called without prior start"
            job.stop()

        then: "no exception is thrown"
            noExceptionThrown()
    }

    // ==================== poll — no pending records ====================

    def "TC-PJ-05: should not invoke pipeline runner when there are no pending extractions"() {
        given: "no pending extractions"
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        when: "poll runs"
            job.poll()

        then: "pipeline runner is never called"
            0 * pipelineRunner.execute(_)
    }

    // ==================== poll — records found ====================

    def "TC-PJ-06: should invoke pipelineRunner.execute for each pending extraction"() {
        given: "two pending extractions"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [e1, e2]

        when: "poll runs"
            job.poll()

        then: "one execute call per extraction"
            1 * pipelineRunner.execute(e1)
            1 * pipelineRunner.execute(e2)
    }

    def "TC-PJ-07: should query repository with exactly POLL_BATCH_SIZE"() {
        when: "poll runs"
            job.poll()

        then: "repository is called with the configured batch size constant"
            1 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []
    }

    def "TC-PJ-08: POLL_BATCH_SIZE constant has expected value"() {
        expect:
            ExtractionPollJob.POLL_BATCH_SIZE == 50
    }

    def "TC-PJ-09: poll does not use a global lock — all instances poll freely on every invocation"() {
        when: "poll is called multiple times (simulating multiple instances)"
            job.poll()
            job.poll()
            job.poll()

        then: "repository was queried on every invocation — no global gate"
            3 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        and: "pipeline runner was never invoked (no pending extractions returned)"
            0 * pipelineRunner.execute(_)
    }

    def "TC-PJ-10: should invoke pipelineRunner.execute exactly once for a single pending extraction"() {
        given: "one pending extraction"
            def e1 = buildExtractionWithDocs("ext-id-single", ["doc-1", "doc-2"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [e1]

        when: "poll runs"
            job.poll()

        then: "execute is called exactly once"
            1 * pipelineRunner.execute(e1)
    }

    // ==================== Helper Methods ====================

    private static ExtractionWithDocs buildExtractionWithDocs(String id, List<String> docIds) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("pending")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-1")
        record.setVersion(0)
        return new ExtractionWithDocs(record, docIds)
    }
}
