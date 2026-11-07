package moe.shizuku.manager.recovery

/** Tracks connection backoff and credential readiness under the coordinator mutex. */
internal class RecoveryRetry {
    private var failures = 0
    private var nextAttempt = 0L

    fun canAttempt(now: Long, waitingForUnlock: Boolean): Boolean {
        if (waitingForUnlock) {
            reset()
            return false
        }
        return now >= nextAttempt
    }

    fun failed(now: Long): Long {
        val delay = RecoveryPolicy.retryDelayMs(failures)
        failures += 1
        nextAttempt = now + delay
        return delay
    }

    fun reset() {
        failures = 0
        nextAttempt = 0
    }
}
