package moe.shizuku.manager.recovery

import java.io.Closeable

/** Owns one request, including a reservation delivered after timeout or cancellation. */
internal class HotspotLease : Closeable {
    private val lock = Any()
    private var closed = false
    private var reservation: AutoCloseable? = null

    fun attach(value: AutoCloseable): Boolean {
        val accepted = synchronized(lock) {
            if (closed || reservation != null) false else {
                reservation = value
                true
            }
        }
        if (!accepted) runCatching { value.close() }
        return accepted
    }

    override fun close() {
        val value = synchronized(lock) {
            closed = true
            reservation.also { reservation = null }
        }
        runCatching { value?.close() }
    }
}
