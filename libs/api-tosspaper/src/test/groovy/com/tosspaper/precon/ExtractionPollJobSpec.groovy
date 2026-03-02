package com.tosspaper.precon

import com.tosspaper.models.jooq.tables.records.ExtractionsRecord
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.Executor

class ExtractionPollJobSpec extends Specification {

    PreconExtractionRepository repository = Mock()
    ExtractionPipelineLockService lockService = Mock()
    Executor executor = Mock()

    @Subject
    ExtractionPollJob job = new ExtractionPollJob(repository, lockService, executor)

    // ==================== poll — lock not acquired ====================

    def "TC-PJ-01: should not query repository when lock is not acquired"() {
        given: "the distributed lock is held by another instance"
            lockService.tryRunExclusive(_) >> false

        when: "poll runs"
            job.poll()

        then: "repository is never touched"
            0 * repository.findPendingExtractions(_)

        and: "executor is never touched"
            0 * executor.execute(_)
    }

    // ==================== poll — lock acquired, no pending records ====================

    def "TC-PJ-02: should not submit any tasks when there are no pending extractions"() {
        given: "lock is acquired and no pending extractions exist"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []

        when: "poll runs"
            job.poll()

        then: "executor is never invoked"
            0 * executor.execute(_)
    }

    // ==================== poll — lock acquired, pending records found ====================

    def "TC-PJ-03: should submit one task per pending extraction to the processing executor"() {
        given: "lock is acquired and two pending extractions exist"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record1 = buildRecord("ext-id-1")
            def record2 = buildRecord("ext-id-2")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record1, record2]

        when: "poll runs"
            job.poll()

        then: "one executor task is submitted per record"
            2 * executor.execute(_)
    }

    def "TC-PJ-04: should query repository with exactly POLL_BATCH_SIZE"() {
        given: "lock is acquired"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

        when: "poll runs"
            job.poll()

        then: "repository is called with the configured batch size constant"
            1 * repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> []
    }

    def "TC-PJ-05: POLL_BATCH_SIZE constant has expected value"() {
        expect:
            ExtractionPollJob.POLL_BATCH_SIZE == 50
    }

    // ==================== poll — executor tasks run without blocking scheduler ====================

    def "TC-PJ-06: should execute task runnable that logs extraction id"() {
        given: "lock is acquired and one pending extraction exists"
            lockService.tryRunExclusive(_) >> { Runnable action -> action.run(); return true }

            def record = buildRecord("ext-id-abc")
            repository.findPendingExtractions(ExtractionPollJob.POLL_BATCH_SIZE) >> [record]

            Runnable capturedTask
            executor.execute(_ as Runnable) >> { Runnable r -> capturedTask = r }

        when: "poll runs"
            job.poll()

        then: "one task was submitted"
            capturedTask != null

        when: "the captured task is executed (simulating thread pool dispatch)"
            capturedTask.run()

        then: "no exception is thrown — processRecord stub runs cleanly"
            noExceptionThrown()
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
