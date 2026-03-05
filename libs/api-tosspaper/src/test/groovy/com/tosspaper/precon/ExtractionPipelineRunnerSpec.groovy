package com.tosspaper.precon

import com.fasterxml.jackson.databind.node.NullNode
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPipelineRunnerSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionWorker extractionWorker = Mock()

    /**
     * Same-thread executor: runs tasks inline on the calling thread.
     * Makes async operations deterministic in unit tests — no Thread.sleep needed.
     */
    Executor sameThreadExecutor = { Runnable r -> r.run() }

    @Subject
    ExtractionPipelineRunner runner = new ExtractionPipelineRunner(repository, extractionWorker, sameThreadExecutor)

    // ==================== run(List) — batch entry point ====================

    def "TC-PR-01: run calls extractionWorker.process once per extraction in the batch"() {
        given: "two extractions in the batch"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            extractionWorker.process(e1) >> emptyResult("ext-id-1")
            extractionWorker.process(e2) >> emptyResult("ext-id-2")

        when: "run is called with the full batch"
            runner.run([e1, e2])

        then: "extractionWorker.process was called once per extraction"
            1 * extractionWorker.process(e1) >> emptyResult("ext-id-1")
            1 * extractionWorker.process(e2) >> emptyResult("ext-id-2")
    }

    def "TC-PR-12: run with empty batch is a no-op — extractionWorker.process is never called"() {
        when: "run is called with an empty batch"
            runner.run([])

        then: "the worker is never invoked"
            0 * extractionWorker.process(_)
    }

    // ==================== process delegates to ExtractionWorker ====================

    def "TC-PR-04: run delegates per-extraction work directly to extractionWorker.process"() {
        given: "an extraction context"
            def extraction = buildExtractionWithDocs("ext-1", ["doc-xyz"])

        when: "run is invoked"
            runner.run([extraction])

        then: "extractionWorker.process was called with the extraction"
            1 * extractionWorker.process(extraction) >> emptyResult("ext-1")
    }

    def "TC-PR-04b: run passes the full extraction context to extractionWorker.process — not just an ID"() {
        given: "an extraction with multiple documents"
            def extraction = buildExtractionWithDocs("ext-ctx", ["doc-1", "doc-2"])
            def capturedContexts = []
            extractionWorker.process(_) >> { ExtractionWithDocs ctx ->
                capturedContexts << ctx
                return emptyResult(ctx.getId())
            }

        when: "run is called"
            runner.run([extraction])

        then: "extractionWorker.process received the full extraction context, not just an ID"
            capturedContexts.size() == 1
            capturedContexts[0].is(extraction)
    }

    // ==================== Success path ====================

    def "TC-PR-05: successful process result is passed to markAsCompleted"() {
        given:
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            def expectedResult = emptyResult(extraction.getId())
            extractionWorker.process(extraction) >> expectedResult

        when: "run is called"
            runner.run([extraction])

        then: "markAsCompleted is called with the extraction ID and the result"
            1 * repository.markAsCompleted("ext-ok", expectedResult) >> 1
    }

    def "TC-PR-13: run completes normally when all extractions succeed"() {
        given:
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            extractionWorker.process(_) >> emptyResult(extraction.getId())

        when: "run is called"
            runner.run([extraction])

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== Error handling ====================

    def "TC-PR-07: failure in extractionWorker.process marks extraction as FAILED with error reason"() {
        given:
            def extraction = buildExtractionWithDocs("ext-fail", ["doc-1"])
            extractionWorker.process(_) >> { throw new RuntimeException("network error — prepare failed") }

        when: "run is called (synchronous via same-thread executor)"
            runner.run([extraction])

        then: "extraction IS marked FAILED with the error message stored"
            1 * repository.markAsFailed("ext-fail", "network error — prepare failed") >> 1
    }

    def "TC-PR-14: failure in one extraction does not prevent other batch members from completing"() {
        given: "a batch of two extractions — one fails, one succeeds"
            def failing  = buildExtractionWithDocs("ext-fail", ["doc-bad"])
            def succeeds = buildExtractionWithDocs("ext-ok",   ["doc-good"])
            extractionWorker.process(failing)  >> { throw new RuntimeException("boom") }
            extractionWorker.process(succeeds) >> emptyResult(succeeds.getId())

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
            extractionWorker.process(_) >> { ExtractionWithDocs ctx -> emptyResult(ctx.getId()) }

        when: "run is called with three extractions"
            trackingRunner.run([e1, e2, e3])

        then: "the executor received one task submission per extraction"
            submittedTasks.size() == 3
    }

    // ==================== Helper Methods ====================

    private static PipelineExtractionResult emptyResult(String extractionId) {
        return new PipelineExtractionResult(extractionId, NullNode.getInstance())
    }

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
