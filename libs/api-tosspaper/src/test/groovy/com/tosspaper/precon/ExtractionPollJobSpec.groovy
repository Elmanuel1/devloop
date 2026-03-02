package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionPipelineLockService lockService = Mock()
    Executor processingExecutor = Mock()

    @Subject
    ExtractionPollJob job = new ExtractionPollJob(repository, lockService, processingExecutor, 5000L)

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

    // ==================== poll — lock not acquired ====================

    def "TC-PJ-05: should not query repository when lock is not acquired"() {
        given: "the distributed lock is held by another instance"
            lockService.tryRunExclusive(_) >> false

        when: "poll runs"
            job.poll()

        then: "repository is never touched"
            0 * repository.findPendingExtractions(_)

        and: "markAsProcessing is never called"
            0 * repository.markAsProcessing(_)

        and: "processing executor is never touched"
            0 * processingExecutor.execute(_)
    }

    // ==================== poll — lock acquired, no pending records ====================

    def "TC-PJ-06: should not submit any tasks when there are no pending extractions"() {
        given: "lock is acquired and no pending extractions exist"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        when: "poll runs"
            job.poll()

        then: "markAsProcessing is never called"
            0 * repository.markAsProcessing(_)

        and: "processing executor is never invoked"
            0 * processingExecutor.execute(_)
    }

    // ==================== poll — lock acquired, pending records found ====================

    def "TC-PJ-07: should mark each record as PROCESSING before submitting to processing executor"() {
        given: "lock is acquired and two pending extractions exist"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record1 = buildRecord("ext-id-1")
            def record2 = buildRecord("ext-id-2")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record1, record2]

        when: "poll runs"
            job.poll()

        then: "markAsProcessing is called for each record before dispatch"
            1 * repository.markAsProcessing("ext-id-1") >> 1
            1 * repository.markAsProcessing("ext-id-2") >> 1

        and: "one executor task is submitted per record"
            2 * processingExecutor.execute(_)
    }

    def "TC-PJ-08: should query repository with exactly POLL_BATCH_SIZE"() {
        given: "lock is acquired"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

        when: "poll runs"
            job.poll()

        then: "repository is called with the configured batch size constant"
            1 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []
    }

    def "TC-PJ-09: POLL_BATCH_SIZE constant has expected value"() {
        expect:
            ExtractionPollJob.POLL_BATCH_SIZE == 50
    }

    def "TC-PJ-10: should submit one task per pending extraction to the processing executor"() {
        given: "lock is acquired and two pending extractions exist"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record1 = buildRecord("ext-id-1")
            def record2 = buildRecord("ext-id-2")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record1, record2]
            repository.markAsProcessing(_) >> 1

        when: "poll runs"
            job.poll()

        then: "one executor task is submitted per record"
            2 * processingExecutor.execute(_)
    }

    // ==================== poll — executor tasks run without blocking scheduler ====================

    def "TC-PJ-11: should execute task runnable that logs extraction id"() {
        given: "lock is acquired and one pending extraction exists"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record = buildRecord("ext-id-abc")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record]
            repository.markAsProcessing("ext-id-abc") >> 1

            Runnable capturedTask
            processingExecutor.execute(_ as Runnable) >> { Runnable r -> capturedTask = r }

        when: "poll runs"
            job.poll()

        then: "one task was submitted"
            capturedTask != null

        when: "the captured task is executed (simulating thread pool dispatch)"
            capturedTask.run()

        then: "no exception is thrown — processRecord stub runs cleanly"
            noExceptionThrown()
    }

    def "TC-PJ-12: markAsProcessing is called in scheduler thread before executor.execute"() {
        given: "lock is acquired and one pending extraction"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record = buildRecord("ext-id-order")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record]

            def callOrder = []
            repository.markAsProcessing("ext-id-order") >> { callOrder << "markAsProcessing"; 1 }
            processingExecutor.execute(_ as Runnable) >> { callOrder << "execute" }

        when: "poll runs"
            job.poll()

        then: "markAsProcessing was called before execute"
            callOrder == ["markAsProcessing", "execute"]
    }

    // ==================== Helper Methods ====================

    private static ExtractionsRecord buildRecord(String id) {
        def record = new ExtractionsRecord()
        record.setId(id)
        record.setStatus("pending")
        record.setCompanyId("company-1")
        record.setEntityType("tender")
        record.setEntityId("tender-1")
        record.setVersion(0)
        return record
    }
}
