package com.tosspaper.precon

import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

class ExtractionPipelineLockServiceSpec extends Specification {

    RedissonClient redissonClient = Mock()
    RLock rLock = Mock()

    @Subject
    ExtractionPipelineLockService service = new ExtractionPipelineLockService(redissonClient)

    def setup() {
        redissonClient.getLock(ExtractionPipelineLockService.EXTRACTION_PIPELINE_LOCK_KEY) >> rLock
    }

    // ==================== tryRunExclusive — lock acquired ====================

    def "TC-L-01: should execute action and return true when lock is acquired"() {
        given: "the lock is available"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> true
            rLock.isHeldByCurrentThread() >> true
            def executed = false
            Runnable action = { executed = true }

        when:
            def result = service.tryRunExclusive(action)

        then: "action ran and lock was released"
            result == true
            executed == true
            1 * rLock.unlock()
    }

    def "TC-L-02: should return false and skip action when lock is not acquired"() {
        given: "another instance holds the lock"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> false
            rLock.isHeldByCurrentThread() >> false
            def executed = false
            Runnable action = { executed = true }

        when:
            def result = service.tryRunExclusive(action)

        then: "action did not run, lock was never unlocked"
            result == false
            executed == false
            0 * rLock.unlock()
    }

    // ==================== tryRunExclusive — lock released after action ====================

    def "TC-L-03: should release lock after action completes successfully"() {
        given: "the lock is acquired"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> true
            rLock.isHeldByCurrentThread() >> true

        when:
            service.tryRunExclusive({ /* no-op */ })

        then: "lock is always unlocked in finally block"
            1 * rLock.unlock()
    }

    def "TC-L-04: should release lock even when action throws a RuntimeException"() {
        given: "the lock is acquired but the action throws"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> true
            rLock.isHeldByCurrentThread() >> true
            Runnable action = { throw new RuntimeException("boom") }

        when:
            service.tryRunExclusive(action)

        then: "exception propagates but lock is still released"
            thrown(RuntimeException)
            1 * rLock.unlock()
    }

    // ==================== tryRunExclusive — interrupted ====================

    def "TC-L-05: should return false and restore interrupt flag when tryLock is interrupted"() {
        given: "tryLock throws InterruptedException"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> { throw new InterruptedException("interrupted") }
            rLock.isHeldByCurrentThread() >> false

        when:
            def result = service.tryRunExclusive({ /* no-op */ })

        then: "returns false and the thread interrupt flag is restored"
            result == false
            Thread.currentThread().interrupted() // clears the flag after asserting it is set
    }

    // ==================== lock key constant ====================

    def "TC-L-06: lock key constant has expected value"() {
        expect:
            ExtractionPipelineLockService.EXTRACTION_PIPELINE_LOCK_KEY == "extraction:pipeline:lock"
    }

    // ==================== lock not held after successful run ====================

    def "TC-L-07: should not unlock when lock is not held by current thread (e.g. lease expired)"() {
        given: "lock was acquired but lease expired before finally block"
            rLock.tryLock(0L, 30L, TimeUnit.SECONDS) >> true
            rLock.isHeldByCurrentThread() >> false  // simulates expired lease

        when:
            service.tryRunExclusive({ /* no-op */ })

        then: "unlock is not called to avoid IllegalMonitorStateException"
            0 * rLock.unlock()
    }
}
