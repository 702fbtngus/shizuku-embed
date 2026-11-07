package moe.shizuku.manager.recovery

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HotspotLeaseTest {
    private class Reservation : Closeable {
        val closed = AtomicInteger()
        override fun close() { closed.incrementAndGet() }
    }

    @Test fun closesReservationDeliveredAfterCancellation() {
        val lease = HotspotLease()
        lease.close()
        val late = Reservation()
        assertFalse(lease.attach(late))
        assertEquals(1, late.closed.get())
    }

    @Test fun cleanupIsIdempotent() {
        val lease = HotspotLease()
        val reservation = Reservation()
        assertTrue(lease.attach(reservation))
        lease.close()
        lease.close()
        assertEquals(1, reservation.closed.get())
    }

    @Test fun duplicateCallbackCannotReplaceOwnedReservation() {
        val lease = HotspotLease()
        val first = Reservation()
        val second = Reservation()
        assertTrue(lease.attach(first))
        assertFalse(lease.attach(second))
        assertEquals(0, first.closed.get())
        assertEquals(1, second.closed.get())
        lease.close()
        assertEquals(1, first.closed.get())
    }

    @Test fun callbackRacingCancellationAlwaysClosesExactlyOnce() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val lease = HotspotLease()
                val reservation = Reservation()
                val start = CountDownLatch(1)
                val callback = executor.submit {
                    start.await()
                    lease.attach(reservation)
                }
                val cancellation = executor.submit {
                    start.await()
                    lease.close()
                }
                start.countDown()
                callback.get(2, TimeUnit.SECONDS)
                cancellation.get(2, TimeUnit.SECONDS)
                assertEquals(1, reservation.closed.get())
            }
        } finally { executor.shutdownNow() }
    }
}
