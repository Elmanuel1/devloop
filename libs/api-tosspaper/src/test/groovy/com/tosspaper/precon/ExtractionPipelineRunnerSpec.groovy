package com.tosspaper.precon

import com.tosspaper.aiengine.client.reducto.dto.ReductoStatus
import com.tosspaper.models.exception.ReductoIntermediateStatusException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CompletableFuture

class ExtractionPipelineRunnerSpec extends Specification {

    PreconExtractionRepository repository = Mock()

    @Subject
    ExtractionPipelineRunner runner = new ExtractionPipelineRunner(repository)

    // ==================== run — delegates to scatterGather ====================

    def "TC-PR-01: run should start processing for extraction with documents"() {
        given: "spy that stubs scatterGather to avoid real execution"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository])
            runnerSpy.scatterGather(_) >> {}

        when: "run is called"
            runnerSpy.run(extraction)

        then: "scatterGather was delegated to"
            1 * runnerSpy.scatterGather(extraction)
    }

    // ==================== scatterGather — document submission ====================

    def "TC-PR-02: scatterGather submits one task per document"() {
        given: "a spy that captures callReducto calls"
            def extraction = buildExtractionWithDocs("ext-1", ["doc-A", "doc-B"])
            def calledDocIds = []
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository])
            runnerSpy.callReducto(_) >> { String docId ->
                calledDocIds << docId
                return new DocResult(docId, "text")
            }

        when: "scatterGather is called"
            runnerSpy.scatterGather(extraction)
            // Wait for async tasks to complete
            Thread.sleep(200)

        then: "callReducto was called once per document"
            calledDocIds.size() == 2
            calledDocIds.containsAll(["doc-A", "doc-B"])
    }

    def "TC-PR-03: scatterGather with no documents calls combineAndSave immediately"() {
        given: "an extraction with an empty document list"
            def extraction = buildExtractionWithDocs("ext-empty", [])

        when: "scatterGather is called"
            runner.scatterGather(extraction)

        then: "extraction is marked COMPLETED via combineAndSave"
            1 * repository.markAsCompleted("ext-empty") >> 1
    }

    // ==================== callReducto — stub behavior ====================

    def "TC-PR-04: callReducto throws UnsupportedOperationException (TOS-38 not yet wired)"() {
        when: "callReducto is invoked directly"
            runner.callReducto("doc-xyz")

        then: "UnsupportedOperationException propagates — stub not yet implemented"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("TOS-38")
    }

    // ==================== combineAndSave ====================

    def "TC-PR-05: combineAndSave marks extraction as COMPLETED"() {
        given: "two successfully completed futures"
            def f1 = CompletableFuture.completedFuture(new DocResult("doc-1", "text-1"))
            def f2 = CompletableFuture.completedFuture(new DocResult("doc-2", "text-2"))

        when: "combineAndSave is called"
            runner.combineAndSave("ext-1", [f1, f2])

        then: "extraction is marked COMPLETED"
            1 * repository.markAsCompleted("ext-1") >> 1
    }

    def "TC-PR-06: combineAndSave with no futures marks extraction as COMPLETED"() {
        when: "combineAndSave is called with empty list"
            runner.combineAndSave("ext-empty", [])

        then: "extraction is still marked COMPLETED"
            1 * repository.markAsCompleted("ext-empty") >> 1
    }

    // ==================== Error handling — prepare vs checkback ====================

    def "TC-PR-07: hard prepare failure marks extraction as FAILED"() {
        given: "spy that overrides callReducto to throw a hard prepare error"
            def extraction = buildExtractionWithDocs("ext-fail", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository])
            runnerSpy.callReducto(_) >> { throw new RuntimeException("network error — prepare failed") }

        when: "scatterGather is called and we wait for async completion"
            runnerSpy.scatterGather(extraction)
            Thread.sleep(200)

        then: "extraction IS marked FAILED — hard errors are permanent"
            1 * repository.markAsFailed("ext-fail") >> 1
    }

    def "TC-PR-08: checkback PROCESSING status does NOT mark extraction as FAILED"() {
        given: "spy that overrides callReducto to signal Reducto is still processing"
            def extraction = buildExtractionWithDocs("ext-processing", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository])
            runnerSpy.callReducto(_) >> {
                throw new ReductoIntermediateStatusException(ReductoStatus.PROCESSING.name())
            }

        when: "scatterGather is called and we wait for async completion"
            runnerSpy.scatterGather(extraction)
            Thread.sleep(200)

        then: "extraction is NOT marked FAILED — task is still in-flight; reaper will handle it"
            0 * repository.markAsFailed(_)
    }

    def "TC-PR-09: checkback PENDING status does NOT mark extraction as FAILED"() {
        given: "spy that overrides callReducto to signal Reducto task is queued"
            def extraction = buildExtractionWithDocs("ext-pending", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository])
            runnerSpy.callReducto(_) >> {
                throw new ReductoIntermediateStatusException(ReductoStatus.PENDING.name())
            }

        when: "scatterGather is called and we wait for async completion"
            runnerSpy.scatterGather(extraction)
            Thread.sleep(200)

        then: "extraction is NOT marked FAILED — PENDING is a transient state"
            0 * repository.markAsFailed(_)
    }

    def "TC-PR-10: ReductoIntermediateStatusException carries the status name that triggered it"() {
        when: "exception is created with PROCESSING status"
            def ex = new ReductoIntermediateStatusException(ReductoStatus.PROCESSING.name())

        then: "status name is accessible"
            ex.getStatus() == "PROCESSING"

        when: "exception is created with PENDING status"
            def ex2 = new ReductoIntermediateStatusException(ReductoStatus.PENDING.name())

        then: "status name is accessible"
            ex2.getStatus() == "PENDING"
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
