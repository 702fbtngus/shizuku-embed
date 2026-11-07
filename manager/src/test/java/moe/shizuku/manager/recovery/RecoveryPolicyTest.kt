package moe.shizuku.manager.recovery

import org.junit.Assert.*
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun rejectsPortsOutsideTcpRange() {
        assertFalse(RecoveryPolicy.validPort(-1))
        assertFalse(RecoveryPolicy.validPort(0))
        assertTrue(RecoveryPolicy.validPort(1))
        assertTrue(RecoveryPolicy.validPort(65535))
        assertFalse(RecoveryPolicy.validPort(65536))
    }

    @Test fun triesSystemThenConfiguredThenCachedWithoutDuplicates() {
        assertEquals(listOf(5555, 4444, 3333),
            RecoveryPolicy.ports(5555, true, 4444, 3333, false, 1, 2))
        assertEquals(listOf(5555),
            RecoveryPolicy.ports(5555, true, 5555, 5555, false, 1, 2))
    }

    @Test fun tcpPreferenceDoesNotDiscardPreviouslyUsableTcpEndpoint() {
        assertEquals(listOf(4444),
            RecoveryPolicy.ports(-1, false, 5555, 4444, false, 1, 2))
    }

    @Test fun tlsCacheExpiresOnReboot() {
        assertEquals(listOf(37000),
            RecoveryPolicy.ports(-1, false, 5555, 37000, true, 2, 2))
        assertEquals(emptyList<Int>(),
            RecoveryPolicy.ports(-1, false, 5555, 37000, true, 1, 2))
        assertEquals(emptyList<Int>(),
            RecoveryPolicy.ports(-1, false, 5555, 37000, true, -1, -1))
    }

    @Test fun rejectsInvalidCandidates() {
        assertEquals(emptyList<Int>(),
            RecoveryPolicy.ports(65536, true, 0, -1, false, 1, 1))
    }

    @Test fun retryBackoffIsBounded() {
        assertEquals(5_000, RecoveryPolicy.retryDelayMs(0))
        assertEquals(10_000, RecoveryPolicy.retryDelayMs(1))
        assertEquals(320_000, RecoveryPolicy.retryDelayMs(1000))
        assertEquals(5_000, RecoveryPolicy.retryDelayMs(-1))
    }

    @Test fun intentionalStopAndPausedAuthorizationOverrideEveryAutoPolicy() {
        for (watchdog in listOf(false, true)) for (boot in listOf(false, true)) {
            assertFalse(RecoveryPolicy.autoAllowed(true, false, watchdog, boot))
            assertFalse(RecoveryPolicy.autoAllowed(false, true, watchdog, boot))
        }
    }

    @Test fun bootRetryAndWatchdogCanOperateIndependently() {
        assertFalse(RecoveryPolicy.autoAllowed(false, false, false, false))
        assertTrue(RecoveryPolicy.autoAllowed(false, false, true, false))
        assertTrue(RecoveryPolicy.autoAllowed(false, false, false, true))
    }
}
