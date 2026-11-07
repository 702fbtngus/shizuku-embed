package moe.shizuku.manager.recovery

import java.io.Closeable

/** Owns one request, including a reservation delivered after timeout or cancellation. */
internal class HotspotLease : Closeable {
    private val lock = Any()
    private var closed = false
    private var reservation: AutoCloseable? = null

    fun attach(value: AutoCloseable): Boolean {
        var rejected = false
        val accepted = synchronized(lock) {
            when {
                reservation === value -> !closed
                closed || reservation != null -> {
                    rejected = true
                    false
                }
                else -> {
                    reservation = value
                    true
                }
            }
        }
        if (rejected) runCatching { value.close() }
        return accepted
    }

    override fun close() {
        val value = synchronized(lock) {
            if (closed) null else {
                closed = true
                reservation
            }
        }
        runCatching { value?.close() }
    }
}
