package com.tosspaper.precon

import com.tosspaper.aiengine.client.reducto.ReductoClient
import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionLockManager lockManager = Mock()
    ReductoClient reductoClient = Mock()
    Executor processingExecutor = Mock()

    @Subject
    ExtractionPollJob job = new ExtractionPollJob(
            repository, lockManager, reductoClient, processingExecutor, 5000L)

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

    def "TC-PJ-05: should not attempt any locks when there are no pending extractions"() {
        given: "no pending extractions"
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        when: "poll runs"
            job.poll()

        then: "lock manager is never called"
            0 * lockManager.tryWithExtractionLock(_, _)

        and: "markAsProcessing is never called"
            0 * repository.markAsProcessing(_)

        and: "processing executor is never touched"
            0 * processingExecutor.execute(_)
    }

    // ==================== poll — lock not acquired ====================

    def "TC-PJ-06: should skip extraction when per-ID lock is not acquired"() {
        given: "one pending extraction but lock is held by another instance"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [extraction]
            lockManager.tryWithExtractionLock("ext-id-1", _) >> false

        when: "poll runs"
            job.poll()

        then: "markAsProcessing is never called"
            0 * repository.markAsProcessing(_)

        and: "no futures are submitted"
            0 * processingExecutor.execute(_)
    }

    // ==================== poll — lock acquired ====================

    def "TC-PJ-07: should markAsProcessing immediately after acquiring per-ID lock"() {
        given: "one pending extraction and lock is available"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [extraction]

            // Capture and immediately invoke the action passed to tryWithExtractionLock
            lockManager.tryWithExtractionLock("ext-id-1", _) >> { String id, Runnable action ->
                action.run()
                return true
            }

            // executor captures submitted futures but does not run them
            processingExecutor.execute(_) >> {}

        when: "poll runs"
            job.poll()

        then: "markAsProcessing was called for the extraction"
            1 * repository.markAsProcessing("ext-id-1") >> 1
    }

    def "TC-PJ-08: should query repository with exactly POLL_BATCH_SIZE"() {
        when: "poll runs"
            job.poll()

        then: "repository is called with the configured batch size constant"
            1 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []
    }

    def "TC-PJ-09: POLL_BATCH_SIZE constant has expected value"() {
        expect:
            ExtractionPollJob.POLL_BATCH_SIZE == 50
    }

    def "TC-PJ-10: should acquire per-ID lock for each pending extraction"() {
        given: "two pending extractions"
            def e1 = buildExtractionWithDocs("ext-id-1", ["doc-1"])
            def e2 = buildExtractionWithDocs("ext-id-2", ["doc-2"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [e1, e2]

            // Lock is not acquired for either — simplest path to verify lock calls
            lockManager.tryWithExtractionLock(_, _) >> false

        when: "poll runs"
            job.poll()

        then: "a lock attempt is made for each extraction"
            1 * lockManager.tryWithExtractionLock("ext-id-1", _)
            1 * lockManager.tryWithExtractionLock("ext-id-2", _)
    }

    def "TC-PJ-11: should submit one CompletableFuture per document to reductoExecutor"() {
        given: "one extraction with two documents, lock acquired"
            def extraction = buildExtractionWithDocs("ext-id-1", ["doc-1", "doc-2"])
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [extraction]
            repository.markAsProcessing("ext-id-1") >> 1

            lockManager.tryWithExtractionLock("ext-id-1", _) >> { String id, Runnable action ->
                action.run()
                return true
            }

            def submittedTasks = []
            processingExecutor.execute(_ as Runnable) >> { Runnable r -> submittedTasks << r }

        when: "poll runs"
            job.poll()

        then: "two tasks were submitted (one per document)"
            submittedTasks.size() == 2
    }

    def "TC-PJ-12: poll does not use global lock — all instances poll freely"() {
        when: "poll is called multiple times (simulating multiple instances)"
            job.poll()
            job.poll()
            job.poll()

        then: "repository was queried every time — no global gate"
            3 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        and: "lock manager was never involved (no extractions returned)"
            0 * lockManager._
    }

    // ==================== scatterGather ====================

    def "TC-PJ-13: scatterGather submits one task per document"() {
        given: "an extraction with three documents"
            def extraction = buildExtractionWithDocs("ext-sg-1", ["doc-A", "doc-B", "doc-C"])
            repository.markAsProcessing("ext-sg-1") >> 1

            def submittedCount = 0
            processingExecutor.execute(_ as Runnable) >> { submittedCount++ }

        when: "scatterGather is called directly"
            job.scatterGather(extraction)

        then: "three tasks submitted to the executor"
            submittedCount == 3
    }

    def "TC-PJ-14: scatterGather with no documents submits no tasks"() {
        given: "an extraction with an empty document list"
            def extraction = buildExtractionWithDocs("ext-sg-empty", [])

        when: "scatterGather is called"
            job.scatterGather(extraction)

        then: "no tasks submitted"
            0 * processingExecutor.execute(_)
    }

    // ==================== callReducto ====================

    def "TC-PJ-15: callReducto throws UnsupportedOperationException (TOS-38 not yet wired)"() {
        when: "callReducto is invoked directly"
            job.callReducto("doc-xyz")

        then: "UnsupportedOperationException propagates — stub not yet implemented"
            thrown(UnsupportedOperationException)
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
