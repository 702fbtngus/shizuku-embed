package moe.shizuku.manager.recovery

import org.junit.Assert.*
import org.junit.Test

class RecoveryRetryTest {
    @Test fun ordinaryConnectionFailuresStillBackOff() {
        val retry = RecoveryRetry()
        assertEquals(5_000L, retry.failed(100))
        assertFalse(retry.canAttempt(5_099, false))
        assertTrue(retry.canAttempt(5_100, false))
        assertEquals(10_000L, retry.failed(5_100))
    }

    @Test fun lockedBootClearsAnExisting320SecondDelay() {
        val retry = RecoveryRetry()
        repeat(7) { retry.failed(100) }
        assertFalse(retry.canAttempt(101, false))
        assertFalse(retry.canAttempt(101, true))
        assertTrue(retry.canAttempt(102, false))
        assertEquals(5_000L, retry.failed(102))
    }

    @Test fun waitingForUnlockDoesNotAccumulateFailures() {
        val retry = RecoveryRetry()
        repeat(100) { assertFalse(retry.canAttempt(it * 5_000L, true)) }
        assertTrue(retry.canAttempt(500_001, false))
        assertEquals(5_000L, retry.failed(500_001))
    }

    @Test fun readinessSignalResetsTransportBackoff() {
        val retry = RecoveryRetry()
        retry.failed(10)
        retry.reset()
        assertTrue(retry.canAttempt(11, false))
    }
}
