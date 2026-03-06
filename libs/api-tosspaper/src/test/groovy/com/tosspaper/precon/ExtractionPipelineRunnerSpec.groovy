package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPipelineRunnerSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionWorker extractionWorker = Mock()

    /** Same-thread executor makes async operations deterministic in unit tests. */
    Executor sameThreadExecutor = { Runnable r -> r.run() }

    @Subject
    ExtractionPipelineRunner runner = new ExtractionPipelineRunner(repository, extractionWorker, sameThreadExecutor)

    // ==================== run(List) — batch entry point ====================

    def "TC-PR-01: run calls extractionWorker.process once per document in each extraction"() {
        given: "two extractions, each with one document"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            extractionWorker.process(_, _) >> true

        when: "run is called with the full batch"
            runner.run([e1, e2])

        then: "extractionWorker.process was called once per document"
            1 * extractionWorker.process(e1, "doc-1") >> true
            1 * extractionWorker.process(e2, "doc-2") >> true
    }

    def "TC-PR-12: run with empty batch is a no-op — extractionWorker.process is never called"() {
        when: "run is called with an empty batch"
            runner.run([])

        then: "the worker is never invoked"
            0 * extractionWorker.process(_, _)
    }

    // ==================== process delegates to ExtractionWorker ====================

    def "TC-PR-04: run calls extractionWorker.process for each document in the extraction"() {
        given: "an extraction with two documents"
            def extraction = buildExtractionWithDocs("ext-1", ["doc-A", "doc-B"])
            extractionWorker.process(_, _) >> true

        when: "run is invoked"
            runner.run([extraction])

        then: "extractionWorker.process was called for each document"
            1 * extractionWorker.process(extraction, "doc-A") >> true
            1 * extractionWorker.process(extraction, "doc-B") >> true
    }

    def "TC-PR-04b: run passes the full extraction context to extractionWorker.process for each document"() {
        given: "an extraction with two documents"
            def extraction = buildExtractionWithDocs("ext-ctx", ["doc-1", "doc-2"])
            def capturedContexts = []
            extractionWorker.process(_, _) >> { ExtractionWithDocs ctx, String docId ->
                capturedContexts << ctx
                return true
            }

        when: "run is called"
            runner.run([extraction])

        then: "extractionWorker.process received the full extraction context for each document"
            capturedContexts.size() == 2
            capturedContexts.every { it.is(extraction) }
    }

    // ==================== Success path ====================

    def "TC-PR-05: successful run calls markAsCompleted with a PipelineExtractionResult"() {
        given:
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            extractionWorker.process(_, _) >> true

        when: "run is called"
            runner.run([extraction])

        then: "markAsCompleted is called with the extraction ID and a result"
            1 * repository.markAsCompleted("ext-ok", { PipelineExtractionResult r ->
                r.extractionId() == "ext-ok"
            }) >> 1
    }

    def "TC-PR-13: run completes normally when all extractions succeed"() {
        given:
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            extractionWorker.process(_, _) >> true

        when: "run is called"
            runner.run([extraction])

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== Error handling ====================

    def "TC-PR-07: uncaught exception in extractionWorker.process marks extraction as FAILED"() {
        given:
            def extraction = buildExtractionWithDocs("ext-fail", ["doc-1"])
            extractionWorker.process(_, _) >> { throw new RuntimeException("network error — prepare failed") }

        when: "run is called (synchronous via same-thread executor)"
            runner.run([extraction])

        then: "extraction IS marked FAILED with the error message stored"
            1 * repository.markAsFailed("ext-fail", "network error — prepare failed") >> 1
    }

    def "TC-PR-14: failure in one extraction does not prevent other batch members from completing"() {
        given: "a batch of two extractions — one throws, one succeeds"
            def failing  = buildExtractionWithDocs("ext-fail", ["doc-bad"])
            def succeeds = buildExtractionWithDocs("ext-ok",   ["doc-good"])
            extractionWorker.process(failing, _)  >> { throw new RuntimeException("boom") }
            extractionWorker.process(succeeds, _) >> true

        when: "run is called with the full batch"
            runner.run([failing, succeeds])

        then: "the failing extraction is marked FAILED with its error reason"
            1 * repository.markAsFailed("ext-fail", "boom") >> 1

        and: "the succeeding extraction is marked COMPLETED with its result"
            1 * repository.markAsCompleted("ext-ok", _ as PipelineExtractionResult) >> 1
    }

    def "TC-PR-11: executor is used for all extraction submissions — bounded concurrency is exercised"() {
        given: "a tracking executor that records each submitted task"
            def submittedTasks = []
            Executor trackingExecutor = { Runnable r -> submittedTasks << r; r.run() }
            def e1 = buildExtractionWithDocs("ext-1", ["doc-X"])
            def e2 = buildExtractionWithDocs("ext-2", ["doc-Y"])
            def e3 = buildExtractionWithDocs("ext-3", ["doc-Z"])
            def trackingRunner = new ExtractionPipelineRunner(repository, extractionWorker, trackingExecutor)
            extractionWorker.process(_, _) >> true

        when: "run is called with three extractions"
            trackingRunner.run([e1, e2, e3])

        then: "the executor received one task submission per extraction"
            submittedTasks.size() == 3
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
