package com.tosspaper.precon

import org.redisson.api.RLock
import org.redisson.api.RedissonClient
import spock.lang.Specification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

class ExtractionLockManagerSpec extends Specification {

    RedissonClient redissonClient = Mock()
    RLock rLock = Mock()

    @Subject
    ExtractionLockManager manager = new ExtractionLockManager(redissonClient)

    static final String EXTRACTION_ID = "ext-abc-123"
    static final String EXPECTED_KEY = "extraction:lock:ext-abc-123"

    def setup() {
        redissonClient.getLock(EXPECTED_KEY) >> rLock
    }

    // ==================== tryWithExtractionLock — lock acquired ====================

    def "TC-L-01: should execute action and return true when per-ID lock is acquired"() {
        given: "the lock is available"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> true
            rLock.isHeldByCurrentThread() >> true
            def executed = false
            Runnable action = { executed = true }

        when:
            def result = manager.tryWithExtractionLock(EXTRACTION_ID, action)

        then: "action ran and lock was released"
            result == true
            executed == true
            1 * rLock.unlock()
    }

    def "TC-L-02: should return false and skip action when lock is not acquired"() {
        given: "another instance holds the lock"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> false
            rLock.isHeldByCurrentThread() >> false
            def executed = false
            Runnable action = { executed = true }

        when:
            def result = manager.tryWithExtractionLock(EXTRACTION_ID, action)

        then: "action did not run, lock was never unlocked"
            result == false
            executed == false
            0 * rLock.unlock()
    }

    // ==================== tryWithExtractionLock — lock released after action ====================

    def "TC-L-03: should release lock after action completes successfully"() {
        given: "the lock is acquired"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> true
            rLock.isHeldByCurrentThread() >> true

        when:
            manager.tryWithExtractionLock(EXTRACTION_ID, { /* no-op */ })

        then: "lock is released in the finally block"
            1 * rLock.unlock()
    }

    def "TC-L-04: should release lock even when action throws a RuntimeException"() {
        given: "the lock is acquired but the action throws"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> true
            rLock.isHeldByCurrentThread() >> true
            Runnable action = { throw new RuntimeException("boom") }

        when:
            manager.tryWithExtractionLock(EXTRACTION_ID, action)

        then: "exception propagates but lock is still released"
            thrown(RuntimeException)
            1 * rLock.unlock()
    }

    // ==================== tryWithExtractionLock — interrupted ====================

    def "TC-L-05: should return false and restore interrupt flag when tryLock is interrupted"() {
        given: "tryLock throws InterruptedException"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> {
                throw new InterruptedException("interrupted")
            }
            rLock.isHeldByCurrentThread() >> false

        when:
            def result = manager.tryWithExtractionLock(EXTRACTION_ID, { /* no-op */ })

        then: "returns false and the thread interrupt flag is restored"
            result == false
            Thread.currentThread().interrupted() // clears the flag after asserting it is set
    }

    // ==================== lock key constant ====================

    def "TC-L-06: lock key prefix constant has expected value"() {
        expect:
            ExtractionLockManager.EXTRACTION_LOCK_PREFIX == "extraction:lock:"
    }

    def "TC-L-07: lock lease duration constant has expected value (20 minutes)"() {
        expect:
            ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES == 20L
    }

    // ==================== tryWithExtractionLock — expired lease ====================

    def "TC-L-08: should not unlock when lock is not held by current thread (e.g. lease expired)"() {
        given: "lock was acquired but lease expired before finally block"
            rLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> true
            rLock.isHeldByCurrentThread() >> false  // simulates expired lease

        when:
            manager.tryWithExtractionLock(EXTRACTION_ID, { /* no-op */ })

        then: "unlock is not called to avoid IllegalMonitorStateException"
            0 * rLock.unlock()
    }

    // ==================== lock key is built from extraction ID ====================

    def "TC-L-09: lock key is derived from extraction ID with correct prefix"() {
        given: "a specific extraction ID and a fresh manager with a spy on RedissonClient"
            def specificId = "ext-specific-999"
            def composedKey = "extraction:lock:ext-specific-999"
            def calledKeys = []
            def freshLock = Mock(RLock)
            def freshRedisson = Mock(RedissonClient) {
                getLock(_) >> { String key -> calledKeys << key; freshLock }
            }
            freshLock.tryLock(0L, ExtractionLockManager.EXTRACTION_LOCK_LEASE_MINUTES, TimeUnit.MINUTES) >> false
            freshLock.isHeldByCurrentThread() >> false
            def freshManager = new ExtractionLockManager(freshRedisson)

        when: "trying to lock that extraction"
            freshManager.tryWithExtractionLock(specificId, { /* no-op */ })

        then: "Redis was asked for a lock with the composed key"
            calledKeys == [composedKey]
    }

    // ==================== releaseLock ====================

    def "TC-L-10: releaseLock unlocks when lock is held by current thread"() {
        given: "the lock is held by the current thread"
            rLock.isHeldByCurrentThread() >> true

        when:
            manager.releaseLock(EXTRACTION_ID)

        then: "unlock is called"
            1 * rLock.unlock()
    }

    def "TC-L-11: releaseLock is a no-op when lock is not held"() {
        given: "the lock is not held"
            rLock.isHeldByCurrentThread() >> false

        when:
            manager.releaseLock(EXTRACTION_ID)

        then: "unlock is not called"
            0 * rLock.unlock()
    }
}
