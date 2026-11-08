package moe.shizuku.manager.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackendRestartTest {
    private data class BinderToken(val alive: Boolean = true)

    @Test fun firstStartAcceptsALiveNewBinder() = runBlocking {
        val fresh = BinderToken()
        val result = awaitBackendReplacement(null, { fresh }, { it.alive }, {})
        assertSame(fresh, result)
    }

    @Test fun anOldLiveBinderCannotCountAsSuccessfulRestart() = runBlocking {
        val old = BinderToken()
        val result = runCatching {
            awaitBackendReplacement(old, { old }, { it.alive }, {}, timeoutMillis = 25)
        }
        assertEquals("The starter ran, but a replacement service did not connect.", result.exceptionOrNull()?.message)
    }

    @Test fun aDistinctBinderIsAcceptedEvenWhenItsValuesMatchTheOldOne() = runBlocking {
        val old = BinderToken()
        val fresh = BinderToken()
        assertEquals(old, fresh)
        assertNotSame(old, fresh)
        assertSame(fresh, awaitBackendReplacement(old, { fresh }, { it.alive }, {}))
    }

    @Test fun waitsThroughAnOldBinderAndTheDisconnectedInterval() = runBlocking {
        val old = BinderToken()
        val fresh = BinderToken()
        val reads = listOf(old, null, fresh).iterator()
        val result = awaitBackendReplacement(old, { reads.next() }, { it.alive }, {})
        assertSame(fresh, result)
    }

    @Test fun oldBinderLivenessIsNeverQueriedDuringReplacement() = runBlocking {
        val old = BinderToken()
        val fresh = BinderToken()
        val reads = listOf(old, fresh).iterator()
        val result = awaitBackendReplacement(old, { reads.next() }, { binder ->
            check(binder !== old) { "A dead previous Binder must not receive another call" }
            binder.alive
        }, {})
        assertSame(fresh, result)
    }

    @Test fun aNewButDeadBinderCannotCountAsSuccess() = runBlocking {
        val dead = BinderToken(alive = false)
        val fresh = BinderToken()
        val reads = listOf(dead, fresh).iterator()
        assertSame(fresh, awaitBackendReplacement(null, { reads.next() }, { it.alive }, {}))
    }

    @Test fun cancellationWhileWaitingCannotReportSuccessfulRestart() = runBlocking {
        val old = BinderToken()
        val waiting = CompletableDeferred<Unit>()
        var completed = false
        val job = launch {
            awaitBackendReplacement(old, {
                waiting.complete(Unit)
                old
            }, { it.alive }, {})
            completed = true
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(completed)
    }

    @Test fun explicitCancellationAndPermissionFailurePropagate() = runBlocking {
        for (failure in listOf(CancellationException("Cancelled"), SecurityException("Not authorized"))) {
            val result = runCatching {
                awaitBackendReplacement<BinderToken>(null, { throw failure }, { it.alive }, {})
            }
            val actual = result.exceptionOrNull()
            assertNotNull(actual)
            // Coroutine debug stack recovery can copy the exception instance.
            assertEquals(failure.javaClass, actual!!.javaClass)
            assertEquals(failure.message, actual.message)
        }
    }

    @Test fun aSupersededOperationCannotAcceptAnAlreadyAvailableNewBinder() = runBlocking {
        val cancelled = CancellationException("Superseded")
        val result = runCatching {
            awaitBackendReplacement(null, { BinderToken() }, { it.alive }, { throw cancelled })
        }
        assertSame(cancelled, result.exceptionOrNull())
    }
}
