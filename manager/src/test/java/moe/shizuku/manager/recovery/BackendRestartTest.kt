package moe.shizuku.manager.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackendRestartTest {
    @Test fun lostExitReplyStillAllowsStartupAfterTheOldServerDies() = runBlocking {
        var alive = true
        val events = mutableListOf<String>()
        stopBackendForRestart(
            requestStop = {
                events.add("exit")
                alive = false
                throw RuntimeException("Binder disconnected before the exit reply")
            },
            isPreviousAlive = { alive },
            checkCurrent = {},
        )
        events.add("start")
        assertEquals(listOf("exit", "start"), events)
    }

    @Test fun lostReplyWaitsForActualDeathBeforeContinuing() = runBlocking {
        var alive = true
        val disconnected = CompletableDeferred<Unit>()
        val dyingServer = launch {
            disconnected.await()
            delay(25)
            alive = false
        }
        stopBackendForRestart(
            requestStop = {
                disconnected.complete(Unit)
                throw RuntimeException("Remote exit")
            },
            isPreviousAlive = { alive },
            checkCurrent = {},
        )
        assertFalse(alive)
        dyingServer.join()
    }

    @Test fun normalExitAlsoWaitsForDeath() = runBlocking {
        var alive = true
        var checks = 0
        stopBackendForRestart(
            requestStop = {},
            isPreviousAlive = {
                checks++
                if (checks >= 3) alive = false
                alive
            },
            checkCurrent = {},
        )
        assertFalse(alive)
    }

    @Test fun anExitErrorIsPreservedWhenTheServerRemainsAlive() = runBlocking {
        val failure = IllegalStateException("Exit rejected")
        val result = runCatching {
            stopBackendForRestart({ throw failure }, { true }, {}, timeoutMillis = 25)
        }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun aLiveServerTimesOutWithoutStartingASecondServer() = runBlocking {
        var started = false
        val result = runCatching {
            stopBackendForRestart({}, { true }, {}, timeoutMillis = 25)
            started = true
        }
        assertEquals("The previous service did not stop.", result.exceptionOrNull()?.message)
        assertFalse(started)
    }

    @Test fun cancellationDuringShutdownCannotContinueToStartup() = runBlocking {
        val stopping = CompletableDeferred<Unit>()
        var started = false
        val job = launch {
            stopBackendForRestart({ stopping.complete(Unit) }, { true }, {})
            started = true
        }
        stopping.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(started)
    }

    @Test fun explicitCancellationAndPermissionErrorsAreNeverSuppressed() = runBlocking {
        for (failure in listOf(CancellationException("Cancelled"), SecurityException("Not authorized"))) {
            var alive = true
            val result = runCatching {
                stopBackendForRestart(
                    requestStop = {
                        alive = false
                        throw failure
                    },
                    isPreviousAlive = { alive },
                    checkCurrent = {},
                )
            }
            assertSame(failure, result.exceptionOrNull())
        }
    }

    @Test fun alreadyDeadServerNeedsNoExitRequest() = runBlocking {
        var requested = false
        stopBackendForRestart({ requested = true }, { false }, {})
        assertFalse(requested)
    }
}
