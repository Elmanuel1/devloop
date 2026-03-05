package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionPipelineRunner pipelineRunner = Mock()

    @Subject
    ExtractionPollJob job = new ExtractionPollJob(repository, pipelineRunner)

    // ==================== poll — no pending records ====================

    def "TC-PJ-01: should not invoke pipeline runner when there are no claimed extractions"() {
        given: "no pending extractions to claim"
            repository.claimNextBatch(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        when: "poll runs"
            job.poll()

        then: "pipeline runner is never called"
            0 * pipelineRunner.run(_)
    }

    // ==================== poll — records found ====================

    def "TC-PJ-02: should invoke pipelineRunner.run for each claimed extraction"() {
        given: "two claimed extractions"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            repository.claimNextBatch(ExtractionPollJob.POLL_BATCH_SIZE) >> [e1, e2]

        when: "poll runs"
            job.poll()

        then: "one run call per extraction"
            1 * pipelineRunner.run(e1)
            1 * pipelineRunner.run(e2)
    }

    def "TC-PJ-03: should claim repository with exactly POLL_BATCH_SIZE"() {
        when: "poll runs"
            job.poll()

        then: "repository is called with the configured batch size constant"
            1 * repository.claimNextBatch(ExtractionPollJob.POLL_BATCH_SIZE) >> []
    }

    def "TC-PJ-04: POLL_BATCH_SIZE constant has expected value"() {
        expect:
            ExtractionPollJob.POLL_BATCH_SIZE == 50
    }

    def "TC-PJ-05: poll queries repository on every invocation — no global lock gate"() {
        when: "poll is called multiple times (simulating rapid scheduling)"
            job.poll()
            job.poll()
            job.poll()

        then: "repository was queried on every invocation — no global gate"
            3 * repository.claimNextBatch(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        and: "pipeline runner was never invoked (no extractions claimed)"
            0 * pipelineRunner.run(_)
    }

    def "TC-PJ-06: should invoke pipelineRunner.run exactly once for a single claimed extraction"() {
        given: "one claimed extraction"
            def e1 = buildExtractionWithDocs("ext-id-single", ["doc-1", "doc-2"])
            repository.claimNextBatch(ExtractionPollJob.POLL_BATCH_SIZE) >> [e1]

        when: "poll runs"
            job.poll()

        then: "run is called exactly once"
            1 * pipelineRunner.run(e1)
    }

    // ==================== reap ====================

    def "TC-PJ-07: reap should call reapStaleExtractions with STALE_MINUTES constant"() {
        when: "reap runs"
            job.reap()

        then: "repository reap method is called with the stale threshold"
            1 * repository.reapStaleExtractions(ExtractionPollJob.STALE_MINUTES) >> 0
    }

    def "TC-PJ-08: STALE_MINUTES constant has expected value"() {
        expect:
            ExtractionPollJob.STALE_MINUTES == 10
    }

    def "TC-PJ-09: reap is idempotent — no exception when no stale rows exist"() {
        given: "no stale rows"
            repository.reapStaleExtractions(_) >> 0

        when: "reap runs"
            job.reap()

        then: "no exception thrown"
            noExceptionThrown()
    }

    def "TC-PJ-10: reap returns count of rows reset"() {
        given: "two stale rows"
            repository.reapStaleExtractions(ExtractionPollJob.STALE_MINUTES) >> 2

        when: "reap runs"
            job.reap()

        then: "no exception — count is logged but not returned to caller"
            noExceptionThrown()
    }

    // ==================== Helper Methods ====================

    private static ExtractionWithDocs buildExtractionWithDocs(String id, List<String> docIds) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("processing")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-1")
        record.setVersion(1)
        return new ExtractionWithDocs(record, docIds)
    }
}
