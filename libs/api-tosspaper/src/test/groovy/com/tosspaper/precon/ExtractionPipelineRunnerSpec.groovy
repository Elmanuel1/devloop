package com.tosspaper.precon

import com.tosspaper.aiengine.client.reducto.ReductoClient
import com.tosspaper.aiengine.client.reducto.dto.ReductoStatus
import com.tosspaper.models.exception.ReductoIntermediateStatusException
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class ExtractionPipelineRunnerSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionLockManager lockManager = Mock()
    ReductoClient reductoClient = Mock()

    /**
     * Synchronous executor: runs submitted tasks immediately in the calling thread.
     * This makes the entire CompletableFuture chain execute synchronously, allowing
     * straightforward assertions without any Thread.sleep or CountDownLatch.
     */
    Executor syncExecutor = { Runnable r -> r.run() }

    @Subject
    ExtractionPipelineRunner runner = new ExtractionPipelineRunner(
            repository, lockManager, reductoClient, syncExecutor)

    // ==================== execute — lock not acquired ====================

    def "TC-PR-01: execute should skip extraction when per-ID lock is not acquired"() {
        given: "lock is held by another instance"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            lockManager.tryWithExtractionLock("ext-id-1", _) >> false

        when: "execute is called"
            runner.execute(extraction)

        then: "markAsProcessing is never called"
            0 * repository.markAsProcessing(_)
    }

    // ==================== execute — lock acquired ====================

    def "TC-PR-02: execute should markAsProcessing immediately after acquiring per-ID lock"() {
        given: "lock is available and immediately invokes the action"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            lockManager.tryWithExtractionLock("ext-id-1", _) >> { String id, Runnable action ->
                action.run()
                return true
            }
            // Non-running executor: capture tasks without executing to avoid UnsupportedOperationException
            Executor capturingExecutor = { Runnable r -> /* captured, not run */ }
            ExtractionPipelineRunner runnerWithCapture = new ExtractionPipelineRunner(
                    repository, lockManager, reductoClient, capturingExecutor)

        when: "execute is called"
            runnerWithCapture.execute(extraction)

        then: "markAsProcessing was called for the extraction"
            1 * repository.markAsProcessing("ext-id-1") >> 1
    }

    def "TC-PR-03: execute should acquire per-ID lock for each extraction"() {
        given: "one pending extraction, lock not acquired"
            def extraction = buildExtractionWithDocs("ext-lock-check", ["doc-1"])
            lockManager.tryWithExtractionLock("ext-lock-check", _) >> false

        when: "execute is called"
            runner.execute(extraction)

        then: "lock was attempted with the correct extraction ID"
            1 * lockManager.tryWithExtractionLock("ext-lock-check", _)
    }

    // ==================== scatterGather — document submission ====================

    def "TC-PR-04: scatterGather submits one task per document to reductoExecutor"() {
        given: "an extraction with two documents and a counting executor"
            def extraction = buildExtractionWithDocs("ext-1", ["doc-A", "doc-B"])
            def submittedCount = 0
            Executor countingExecutor = { Runnable r -> submittedCount++ }
            ExtractionPipelineRunner r = new ExtractionPipelineRunner(
                    repository, lockManager, reductoClient, countingExecutor)

        when: "scatterGather is called"
            r.scatterGather(extraction)

        then: "two tasks were submitted — one per document"
            submittedCount == 2
    }

    def "TC-PR-05: scatterGather with no documents calls combineAndSave immediately"() {
        given: "an extraction with an empty document list"
            def extraction = buildExtractionWithDocs("ext-empty", [])

        when: "scatterGather is called"
            runner.scatterGather(extraction)

        then: "extraction is marked COMPLETED via combineAndSave"
            1 * repository.markAsCompleted("ext-empty") >> 1

        and: "lock is released via combineAndSave"
            1 * lockManager.releaseLock("ext-empty")
    }

    // ==================== callReducto — stub behavior ====================

    def "TC-PR-06: callReducto throws UnsupportedOperationException (TOS-38 not yet wired)"() {
        when: "callReducto is invoked directly"
            runner.callReducto("doc-xyz")

        then: "UnsupportedOperationException propagates — stub not yet implemented"
            def ex = thrown(UnsupportedOperationException)
            ex.message.contains("TOS-38")
    }

    // ==================== combineAndSave ====================

    def "TC-PR-07: combineAndSave marks extraction as COMPLETED and releases lock"() {
        given: "two successfully completed futures"
            def f1 = CompletableFuture.completedFuture(new DocResult("doc-1", "text-1"))
            def f2 = CompletableFuture.completedFuture(new DocResult("doc-2", "text-2"))

        when: "combineAndSave is called"
            runner.combineAndSave("ext-1", [f1, f2])

        then: "extraction is marked COMPLETED"
            1 * repository.markAsCompleted("ext-1") >> 1

        and: "lock is released in the finally block"
            1 * lockManager.releaseLock("ext-1")
    }

    def "TC-PR-08: combineAndSave releases lock even when markAsCompleted throws"() {
        given: "markAsCompleted throws a RuntimeException"
            repository.markAsCompleted("ext-fail") >> { throw new RuntimeException("db error") }

        when: "combineAndSave is called"
            runner.combineAndSave("ext-fail", [])

        then: "exception propagates but lock is still released"
            thrown(RuntimeException)
            1 * lockManager.releaseLock("ext-fail")
    }

    // ==================== Error handling — prepare vs checkback ====================

    def "TC-PR-09: hard prepare failure marks extraction as FAILED and releases lock"() {
        given: "spy that overrides callReducto to throw a hard prepare error"
            def extraction = buildExtractionWithDocs("ext-fail", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, lockManager, reductoClient, syncExecutor])
            runnerSpy.callReducto(_) >> { throw new RuntimeException("network error — prepare failed") }

        when: "scatterGather is called"
            runnerSpy.scatterGather(extraction)

        then: "extraction IS marked FAILED — hard errors are permanent"
            1 * repository.markAsFailed("ext-fail") >> 1

        and: "lock is released"
            1 * lockManager.releaseLock("ext-fail")
    }

    def "TC-PR-10: checkback PROCESSING status does NOT mark extraction as FAILED"() {
        given: "spy that overrides callReducto to signal Reducto is still processing"
            def extraction = buildExtractionWithDocs("ext-processing", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, lockManager, reductoClient, syncExecutor])
            runnerSpy.callReducto(_) >> {
                throw new ReductoIntermediateStatusException(ReductoStatus.PROCESSING.name())
            }

        when: "scatterGather is called"
            runnerSpy.scatterGather(extraction)

        then: "extraction is NOT marked FAILED — task is still in-flight"
            0 * repository.markAsFailed(_)

        and: "lock is released so the next poll cycle can retry"
            1 * lockManager.releaseLock("ext-processing")
    }

    def "TC-PR-11: checkback PENDING status does NOT mark extraction as FAILED"() {
        given: "spy that overrides callReducto to signal Reducto task is queued"
            def extraction = buildExtractionWithDocs("ext-pending", ["doc-1"])
            ExtractionPipelineRunner runnerSpy = Spy(ExtractionPipelineRunner,
                    constructorArgs: [repository, lockManager, reductoClient, syncExecutor])
            runnerSpy.callReducto(_) >> {
                throw new ReductoIntermediateStatusException(ReductoStatus.PENDING.name())
            }

        when: "scatterGather is called"
            runnerSpy.scatterGather(extraction)

        then: "extraction is NOT marked FAILED — PENDING is a transient state"
            0 * repository.markAsFailed(_)

        and: "lock is released so the next poll cycle can retry"
            1 * lockManager.releaseLock("ext-pending")
    }

    def "TC-PR-12: ReductoIntermediateStatusException carries the status name that triggered it"() {
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
