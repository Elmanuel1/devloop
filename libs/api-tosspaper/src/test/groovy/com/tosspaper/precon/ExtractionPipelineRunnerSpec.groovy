package com.tosspaper.precon

import com.fasterxml.jackson.databind.node.NullNode
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPipelineRunnerSpec extends Specification {

    PreconExtractionRepository repository = Mock()

    /**
     * Same-thread executor: runs tasks inline on the calling thread.
     * Makes async operations deterministic in unit tests — no Thread.sleep needed.
     */
    Executor sameThreadExecutor = { Runnable r -> r.run() }

    @Subject
    ExtractionPipelineRunner runner = new ExtractionPipelineRunner(repository, sameThreadExecutor)

    // ==================== run(List) — batch entry point ====================

    def "TC-PR-01: run submits one future per extraction in the batch"() {
        given: "two extractions in the batch"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(_) >> emptyResult("ignored")

        when: "run is called with the full batch"
            runnerSpy.run([e1, e2])

        then: "callReducto was called once per extraction"
            1 * runnerSpy.callReducto(e1)
            1 * runnerSpy.callReducto(e2)
    }

    def "TC-PR-12: run with empty batch is a no-op — callReducto is never called"() {
        given: "a spy to detect any callReducto calls"
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])

        when: "run is called with an empty batch"
            runnerSpy.run([])

        then: "callReducto is never invoked"
            0 * runnerSpy.callReducto(_)
    }

    // ==================== callReducto — stub behavior ====================

    def "TC-PR-04: callReducto throws UnsupportedOperationException (TOS-38 not yet wired)"() {
        given: "an extraction context"
            def extraction = buildExtractionWithDocs("ext-1", ["doc-xyz"])

        when: "callReducto is invoked directly"
            runner.callReducto(extraction)

        then: "UnsupportedOperationException propagates — stub not yet implemented"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("TOS-38")
    }

    def "TC-PR-04b: callReducto takes only the extraction context — no separate document ID arg"() {
        given: "a spy that stubs callReducto"
            def extraction = buildExtractionWithDocs("ext-ctx", ["doc-1", "doc-2"])
            def capturedContexts = []
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(_) >> { ExtractionWithDocs ctx ->
                capturedContexts << ctx
                return emptyResult(ctx.getId())
            }

        when: "run is called"
            runnerSpy.run([extraction])

        then: "callReducto received the full extraction context, not just an ID"
            capturedContexts.size() == 1
            capturedContexts[0].is(extraction)
    }

    // ==================== Success path ====================

    def "TC-PR-05: successful callReducto result is passed to markAsCompleted"() {
        given: "a spy with a successful callReducto"
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            def expectedResult = emptyResult(extraction.getId())
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(extraction) >> expectedResult

        when: "run is called"
            runnerSpy.run([extraction])

        then: "markAsCompleted is called with the extraction ID and the result"
            1 * repository.markAsCompleted("ext-ok", expectedResult) >> 1
    }

    def "TC-PR-13: run completes normally when all extractions succeed"() {
        given: "a spy with successful callReducto"
            def extraction = buildExtractionWithDocs("ext-ok", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(_) >> emptyResult(extraction.getId())

        when: "run is called"
            runnerSpy.run([extraction])

        then: "no exception thrown"
            noExceptionThrown()
    }

    // ==================== Error handling ====================

    def "TC-PR-07: failure in callReducto marks extraction as FAILED with error reason"() {
        given: "a spy that throws on callReducto"
            def extraction = buildExtractionWithDocs("ext-fail", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(_) >> { throw new RuntimeException("network error — prepare failed") }

        when: "run is called (synchronous via same-thread executor)"
            runnerSpy.run([extraction])

        then: "extraction IS marked FAILED with the error message stored"
            1 * repository.markAsFailed("ext-fail", "network error — prepare failed") >> 1
    }

    def "TC-PR-14: failure in one extraction does not prevent other batch members from completing"() {
        given: "a batch of two extractions — one fails, one succeeds"
            def failing  = buildExtractionWithDocs("ext-fail", ["doc-bad"])
            def succeeds = buildExtractionWithDocs("ext-ok",   ["doc-good"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, sameThreadExecutor])
            runnerSpy.callReducto(failing)  >> { throw new RuntimeException("boom") }
            runnerSpy.callReducto(succeeds) >> emptyResult(succeeds.getId())

        when: "run is called with the full batch"
            runnerSpy.run([failing, succeeds])

        then: "the failing extraction is marked FAILED with its error reason"
            1 * repository.markAsFailed("ext-fail", "boom") >> 1

        and: "the succeeding extraction is marked COMPLETED with its result"
            1 * repository.markAsCompleted("ext-ok", _ as PipelineExtractionResult) >> 1
    }

    def "TC-PR-11: executor is used for all extraction submissions — bounded concurrency is exercised"() {
        given: "a spy executor that records each submitted task"
            def submittedTasks = []
            Executor trackingExecutor = { Runnable r -> submittedTasks << r; r.run() }
            def e1 = buildExtractionWithDocs("ext-1", ["doc-X"])
            def e2 = buildExtractionWithDocs("ext-2", ["doc-Y"])
            def e3 = buildExtractionWithDocs("ext-3", ["doc-Z"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, trackingExecutor])
            runnerSpy.callReducto(_) >> { ExtractionWithDocs ctx -> emptyResult(ctx.getId()) }

        when: "run is called with three extractions"
            runnerSpy.run([e1, e2, e3])

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
