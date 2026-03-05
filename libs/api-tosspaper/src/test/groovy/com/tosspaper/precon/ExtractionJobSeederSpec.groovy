package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

class ExtractionJobSeederSpec extends Specification {

    PreconExtractionRepository preconExtractionRepository = Mock()
    ExtractionPipelineRunner pipelineRunner = Mock()
    ReductoProperties reductoProperties = new ReductoProperties()

    @Subject
    ExtractionJobSeeder seeder

    def setup() {
        reductoProperties.setBaseUrl("https://api.reducto.ai")
        reductoProperties.setApiKey("test-key")
        reductoProperties.setWebhookBaseUrl("https://app.example.com")
        reductoProperties.setWebhookPath("/internal/reducto/webhook")
        reductoProperties.setBatchSize(20)
        reductoProperties.setStaleMinutes(15)
        reductoProperties.setTimeoutSeconds(30)

        seeder = new ExtractionJobSeeder(preconExtractionRepository, pipelineRunner, reductoProperties)
    }

    // ── seedPendingExtractions — no pending records ───────────────────────────

    def "TC-JS-01: seedPendingExtractions does not call pipelineRunner when no extractions are claimed"() {
        given:
            preconExtractionRepository.claimNextBatch(20) >> []

        when:
            seeder.seedPendingExtractions()

        then:
            0 * pipelineRunner.run(_)
    }

    // ── seedPendingExtractions — batch submitted ──────────────────────────────

    def "TC-JS-02: seedPendingExtractions passes the entire claimed batch to pipelineRunner.run"() {
        given:
            def e1 = buildExtractionWithDocs("ext-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-2", ["doc-2"])
            preconExtractionRepository.claimNextBatch(20) >> [e1, e2]

        when:
            seeder.seedPendingExtractions()

        then:
            1 * pipelineRunner.run([e1, e2])
    }

    def "TC-JS-03: seedPendingExtractions claims exactly reductoProperties.batchSize rows"() {
        when:
            seeder.seedPendingExtractions()

        then:
            1 * preconExtractionRepository.claimNextBatch(20) >> []
    }

    def "TC-JS-04: seedPendingExtractions uses configured batch size — not a hardcoded constant"() {
        given: "batch size changed to 10"
            reductoProperties.setBatchSize(10)

        when:
            seeder.seedPendingExtractions()

        then:
            1 * preconExtractionRepository.claimNextBatch(10) >> []
            0 * preconExtractionRepository.claimNextBatch(20)
    }

    def "TC-JS-05: seedPendingExtractions queries repository on every invocation"() {
        when:
            seeder.seedPendingExtractions()
            seeder.seedPendingExtractions()
            seeder.seedPendingExtractions()

        then:
            3 * preconExtractionRepository.claimNextBatch(20) >> []
            0 * pipelineRunner.run(_)
    }

    def "TC-JS-06: seedPendingExtractions with single extraction calls run with one-element list"() {
        given:
            def e = buildExtractionWithDocs("ext-single", ["doc-A", "doc-B"])
            preconExtractionRepository.claimNextBatch(20) >> [e]

        when:
            seeder.seedPendingExtractions()

        then:
            1 * pipelineRunner.run([e])
    }

    // ── reapStuckJobs ─────────────────────────────────────────────────────────

    def "TC-JS-07: reapStuckJobs delegates to reapStaleExtractions with configured stale minutes"() {
        when:
            seeder.reapStuckJobs()

        then:
            1 * preconExtractionRepository.reapStaleExtractions(15) >> 0
    }

    def "TC-JS-08: reapStuckJobs uses configured staleMinutes — not a hardcoded constant"() {
        given: "stale threshold changed to 30 minutes"
            reductoProperties.setStaleMinutes(30)

        when:
            seeder.reapStuckJobs()

        then:
            1 * preconExtractionRepository.reapStaleExtractions(30) >> 0
            0 * preconExtractionRepository.reapStaleExtractions(15)
    }

    def "TC-JS-09: reapStuckJobs is idempotent — no exception when no stale rows exist"() {
        given:
            preconExtractionRepository.reapStaleExtractions(_) >> 0

        when:
            seeder.reapStuckJobs()

        then:
            noExceptionThrown()
    }

    def "TC-JS-10: reapStuckJobs does not throw when reap returns positive count"() {
        given:
            preconExtractionRepository.reapStaleExtractions(15) >> 5

        when:
            seeder.reapStuckJobs()

        then:
            noExceptionThrown()
    }

    def "TC-JS-11: reapStuckJobs swallows exceptions from repository and does not rethrow"() {
        given: "repository throws during reap"
            preconExtractionRepository.reapStaleExtractions(_) >> {
                throw new RuntimeException("DB timeout")
            }

        when:
            seeder.reapStuckJobs()

        then: "exception is logged but not propagated — scheduler must not be disrupted"
            noExceptionThrown()
    }

    // ── ReductoProperties default values ─────────────────────────────────────

    def "TC-JS-12: default batch size is 20"() {
        given:
            def defaultProps = new ReductoProperties()
        expect:
            defaultProps.getBatchSize() == 20
    }

    def "TC-JS-13: default stale minutes is 15"() {
        given:
            def defaultProps = new ReductoProperties()
        expect:
            defaultProps.getStaleMinutes() == 15
    }

    def "TC-JS-14: default webhook path is /internal/reducto/webhook"() {
        given:
            def defaultProps = new ReductoProperties()
        expect:
            defaultProps.getWebhookPath() == "/internal/reducto/webhook"
    }

    def "TC-JS-15: buildWebhookUrl concatenates base URL and path"() {
        given:
            reductoProperties.setWebhookBaseUrl("https://app.example.com")
            reductoProperties.setWebhookPath("/internal/reducto/webhook")
        expect:
            reductoProperties.buildWebhookUrl() == "https://app.example.com/internal/reducto/webhook"
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
