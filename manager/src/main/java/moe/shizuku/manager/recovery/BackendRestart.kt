package moe.shizuku.manager.recovery

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The native starter replaces the server. Its previous Binder cannot prove success. */
internal suspend fun <T : Any> awaitBackendReplacement(
    previous: T?,
    current: () -> T?,
    isAlive: (T) -> Boolean,
    checkCurrent: suspend () -> Unit,
    timeoutMillis: Long = 15_000,
): T {
    checkCurrent()
    val replacement = withTimeoutOrNull(timeoutMillis) {
        var candidate = current()
        while (candidate == null || candidate === previous || !isAlive(candidate)) {
            checkCurrent()
            delay(100)
            candidate = current()
        }
        checkCurrent()
        candidate
    }
    return replacement ?: throw IllegalStateException("The starter ran, but a replacement service did not connect.")
}
