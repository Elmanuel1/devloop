package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionPipelineRunner pipelineRunner = Mock()
    ReductoProperties reductoProperties = new ReductoProperties()

    @Subject
    ExtractionPollJob job

    def setup() {
        reductoProperties.setBaseUrl("https://api.reducto.ai")
        reductoProperties.setApiKey("test-key")
        reductoProperties.setWebhookBaseUrl("https://app.example.com")
        reductoProperties.setWebhookPath("/internal/reducto/webhook")
        reductoProperties.setBatchSize(20)
        reductoProperties.setStaleMinutes(15)
        reductoProperties.setTimeoutSeconds(30)

        job = new ExtractionPollJob(repository, pipelineRunner, reductoProperties)
    }

    // ── poll — no pending records ─────────────────────────────────────────────

    def "TC-PJ-01: poll does not invoke pipeline runner when no extractions are claimed"() {
        given: "no pending extractions to claim"
            repository.claimNextBatch(20) >> []

        when:
            job.poll()

        then:
            0 * pipelineRunner.run(_)
    }

    // ── poll — batch submitted ────────────────────────────────────────────────

    def "TC-PJ-02: poll passes the entire claimed batch to pipelineRunner.run in one call"() {
        given:
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            repository.claimNextBatch(20) >> [e1, e2]

        when:
            job.poll()

        then:
            1 * pipelineRunner.run([e1, e2])
    }

    def "TC-PJ-03: poll claims exactly reductoProperties.batchSize rows from repository"() {
        when:
            job.poll()

        then:
            1 * repository.claimNextBatch(20) >> []
    }

    def "TC-PJ-04: poll uses configured batch size — not a hardcoded constant"() {
        given: "batch size changed to 10"
            reductoProperties.setBatchSize(10)

        when:
            job.poll()

        then:
            1 * repository.claimNextBatch(10) >> []
            0 * repository.claimNextBatch(20)
    }

    def "TC-PJ-05: poll queries repository on every invocation — no global lock gate"() {
        when:
            job.poll()
            job.poll()
            job.poll()

        then:
            3 * repository.claimNextBatch(20) >> []
            0 * pipelineRunner.run(_)
    }

    def "TC-PJ-06: poll passes a single-element list when only one extraction is claimed"() {
        given:
            def e1 = buildExtractionWithDocs("ext-id-single", ["doc-1", "doc-2"])
            repository.claimNextBatch(20) >> [e1]

        when:
            job.poll()

        then:
            1 * pipelineRunner.run([e1])
    }

    // ── reap ──────────────────────────────────────────────────────────────────

    def "TC-PJ-07: reap delegates to reapStaleExtractions with configured stale minutes"() {
        when:
            job.reap()

        then:
            1 * repository.reapStaleExtractions(15) >> 0
    }

    def "TC-PJ-08: reap uses configured staleMinutes — not a hardcoded constant"() {
        given: "stale threshold changed to 30 minutes"
            reductoProperties.setStaleMinutes(30)

        when:
            job.reap()

        then:
            1 * repository.reapStaleExtractions(30) >> 0
            0 * repository.reapStaleExtractions(15)
    }

    def "TC-PJ-09: reap is idempotent — no exception when no stale rows exist"() {
        given:
            repository.reapStaleExtractions(_) >> 0

        when:
            job.reap()

        then:
            noExceptionThrown()
    }

    def "TC-PJ-10: reap does not throw when reap returns positive count"() {
        given:
            repository.reapStaleExtractions(15) >> 2

        when:
            job.reap()

        then:
            noExceptionThrown()
    }

    // ── ReductoProperties defaults ────────────────────────────────────────────

    def "TC-PJ-11: default batch size is 20"() {
        expect:
            new ReductoProperties().getBatchSize() == 20
    }

    def "TC-PJ-12: default stale minutes is 15"() {
        expect:
            new ReductoProperties().getStaleMinutes() == 15
    }

    // ── Helper methods ────────────────────────────────────────────────────────

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
