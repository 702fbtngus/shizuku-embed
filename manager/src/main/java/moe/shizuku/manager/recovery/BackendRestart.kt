package moe.shizuku.manager.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The synchronous exit transaction can lose its reply when the server exits. */
internal suspend fun stopBackendForRestart(
    requestStop: () -> Unit,
    isPreviousAlive: () -> Boolean,
    checkCurrent: suspend () -> Unit,
    timeoutMillis: Long = 5_000,
) {
    checkCurrent()
    if (!isPreviousAlive()) return
    var stopFailure: Exception? = null
    try {
        requestStop()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        stopFailure = e
    }
    val stopped = withTimeoutOrNull(timeoutMillis) {
        while (isPreviousAlive()) {
            checkCurrent()
            delay(50)
        }
        checkCurrent()
        true
    } ?: false
    if (!stopped) throw stopFailure ?: IllegalStateException("The previous service did not stop.")
}
