package moe.shizuku.manager.recovery

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbAuthorizationException
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.application
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

/** Runs only in the manager process. Every launch path shares one cancellable ADB attempt. */
object BackendRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var activeClient: AdbClient? = null
    @Volatile private var stopLateBinderUntil = 0L
    private var nextAttempt = 0L
    private var failures = 0
    private var startFailures = 0
    private var initialized = false

    fun initialize() {
        if (initialized) return
        initialized = true
        RecoverySettings.reconcileBoot()
        Shizuku.addBinderReceivedListenerSticky {
            scope.launch {
                mutex.lock()
                try {
                    if (RecoverySettings.stopped && SystemClock.elapsedRealtime() < stopLateBinderUntil) {
                        runCatching { Shizuku.exit() }
                    } else {
                        recordRunning()
                    }
                } finally { mutex.unlock() }
                RecoveryService.refresh(application)
            }
        }
        Shizuku.addBinderDeadListener {
            scope.launch {
                RecoveryService.refresh(application)
                tick()
            }
        }
        RecoveryService.scheduleJob(application)
    }

    fun networkAvailable() {
        scope.launch {
            if (mutex.tryLock()) {
                try { nextAttempt = 0 } finally { mutex.unlock() }
            }
            tick()
        }
    }

    private fun recordRunning() {
        if (RecoverySettings.stopped) return
        val uid = runCatching { Shizuku.getUid() }.getOrNull() ?: return
        if (uid != 0 && uid != 2000) return
        ShizukuSettings.setLastLaunchMode(if (uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
        RecoverySettings.prefs.edit().putBoolean(RecoverySettings.BOOT_PENDING, false)
            .putBoolean(RecoverySettings.PAUSED, false).apply()
        failures = 0
        startFailures = 0
        nextAttempt = 0
        RecoverySettings.status("Running as ${if (uid == 0) "root" else "shell"}.")
    }

    fun stop() {
        RecoverySettings.prefs.edit().putBoolean(RecoverySettings.STOPPED, true)
            .putBoolean(RecoverySettings.BOOT_PENDING, false).apply()
        if (mutex.isLocked) stopLateBinderUntil = SystemClock.elapsedRealtime() + 30_000
        generation.incrementAndGet()
        activeClient?.close()
        RecoverySettings.status("Stopped by user. Start manually to resume recovery.")
        RecoveryService.refresh(application)
        scope.launch {
            mutex.lock()
            try { runCatching { Shizuku.exit() } } finally { mutex.unlock() }
        }
    }

    suspend fun startManual(root: Boolean, host: String? = null, port: Int = 0,
                            output: (String) -> Unit) {
        val ticket = generation.incrementAndGet()
        activeClient?.close()
        mutex.lock()
        try {
            checkCurrent(ticket)
            RecoverySettings.prefs.edit().putBoolean(RecoverySettings.STOPPED, false)
                .putBoolean(RecoverySettings.PAUSED, false).apply()
            stopLateBinderUntil = 0
            startFailures = 0
            failures = 0
            nextAttempt = 0
            start(root, host, port, true, ticket, output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (ticket == generation.get()) {
                RecoverySettings.prefs.edit().putBoolean(RecoverySettings.PAUSED, true).apply()
                RecoverySettings.status("Manual start failed. Check pairing and start again. ${e.message}")
            }
            throw e
        } finally {
            mutex.unlock()
            RecoveryService.refresh(application)
        }
    }

    /** Performs one automatic attempt, respecting backoff and manual stop. */
    suspend fun tick() {
        RecoverySettings.reconcileBoot()
        val ticket = generation.get()
        if (!RecoverySettings.autoAllowed || !mutex.tryLock()) return
        try {
            if (!RecoverySettings.autoAllowed) return
            if (Shizuku.pingBinder()) {
                recordRunning()
                return
            }
            if (SystemClock.elapsedRealtime() < nextAttempt) return
            try {
                start(ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT,
                    null, 0, false, ticket, RecoverySettings::status)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return
            } catch (e: Exception) {
                if (ticket != generation.get()) return
                val authorization = e is AdbAuthorizationException || e is SSLHandshakeException ||
                    e is AdbKeyException || e is RootUnavailableException
                if (e is ServerStartException) startFailures += 1
                if (authorization || startFailures >= 3) {
                    RecoverySettings.prefs.edit().putBoolean(RecoverySettings.PAUSED, true).apply()
                    RecoverySettings.status("Automatic recovery paused. Open Shizuku, check pairing and start manually. ${e.message}")
                } else {
                    val delay = RecoveryPolicy.retryDelayMs(failures++)
                    nextAttempt = SystemClock.elapsedRealtime() + delay
                    RecoverySettings.status("Waiting to retry in ${delay / 1000}s. ${e.message}")
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun start(root: Boolean, host: String?, port: Int, manual: Boolean,
                              ticket: Long, output: (String) -> Unit) {
        if (!root && !application.getSystemService(UserManager::class.java).isUserUnlocked) {
            error("Unlock the device once before restoring the ADB key.")
        }
        checkCurrent(ticket)
        if (root) {
            output("Starting with root…")
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                throw RootUnavailableException()
            }
            checkCurrent(ticket)
            val result = Shell.cmd(Starter.internalCommand).exec()
            checkCurrent(ticket)
            result.out.forEach(output)
            if (!result.isSuccess) throw ServerStartException("Root starter failed (${result.code}).")
        } else {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(RecoverySettings.prefs), "shizuku")
            } catch (e: Exception) {
                throw AdbKeyException(e)
            }
            var client = connect(key, host, port, manual, ticket, output)
            try {
                if (RecoverySettings.tcpEnabled && (client.isTls || RecoverySettings.prefs.getInt("recovery_last_port", -1) != RecoverySettings.port)) {
                    val tcpPort = RecoverySettings.port
                    output("Enabling authenticated ADB TCP on port $tcpPort…")
                    // adbd restarts here. Enable TCP before starting the privileged server.
                    try {
                        client.command("tcpip:$tcpPort") { output(String(it)) }
                    } catch (_: EOFException) {
                        // A daemon restart may close its transport before sending A_CLSE.
                    } catch (_: SocketException) {
                    }
                    client.close()
                    checkCurrent(ticket)
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (!listening(tcpPort) && SystemClock.elapsedRealtime() < deadline) {
                        checkCurrent(ticket)
                        delay(250)
                    }
                    client = open(key, "127.0.0.1", tcpPort, manual, ticket)
                }
                checkCurrent(ticket)
                output("Starting privileged server…")
                try {
                    client.shellCommand(Starter.internalCommand) { output(String(it)) }
                } catch (e: Exception) {
                    checkCurrent(ticket)
                    throw ServerStartException("ADB starter failed. ${e.message}")
                }
            } finally {
                client.close()
                activeClient = null
            }
        }
        output("Waiting for the service binder…")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!Shizuku.pingBinder() && SystemClock.elapsedRealtime() < deadline) {
            checkCurrent(ticket)
            delay(100)
        }
        checkCurrent(ticket)
        if (!Shizuku.pingBinder()) throw ServerStartException("The starter ran, but the service did not connect.")
        recordRunning()
        output("Service started. This window will close in 3 seconds.")
    }

    private suspend fun connect(key: AdbKey, host: String?, port: Int, manual: Boolean,
                                ticket: Long, output: (String) -> Unit): AdbClient {
        if (host != null && RecoveryPolicy.validPort(port)) return open(key, host, port, manual, ticket)
        val prefs = RecoverySettings.prefs
        val ports = RecoveryPolicy.ports(EnvironmentUtils.getAdbTcpPort(), RecoverySettings.tcpEnabled,
            RecoverySettings.port, prefs.getInt("recovery_last_port", -1),
            prefs.getBoolean("recovery_last_tls", true), prefs.getInt("recovery_last_boot", -1),
            RecoverySettings.bootCount)
        for (candidate in ports) {
            checkCurrent(ticket)
            if (!listening(candidate)) continue
            try {
                return open(key, "127.0.0.1", candidate, manual, ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbAuthorizationException) {
                throw e
            } catch (e: SSLHandshakeException) {
                throw e
            } catch (_: Exception) {
                // A cached/system-property port can be stale or belong to another service.
            }
        }
        if (Build.VERSION.SDK_INT < 30) error("No local ADB endpoint. Enable ADB TCP from a computer first.")
        if (application.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val resolver = application.contentResolver
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        }
        output("Waiting for wireless debugging. Connect Wi-Fi and enable wireless debugging if needed…")
        val found = CompletableDeferred<Int>()
        val mdns = AdbMdns(application, AdbMdns.TLS_CONNECT) {
            if (RecoveryPolicy.validPort(it)) found.complete(it)
        }
        val discovered = try {
            mdns.start()
            withTimeoutOrNull(8_000) { found.await() }
        } finally { runCatching { mdns.stop() } }
        checkCurrent(ticket)
        if (discovered == null) error("No ADB endpoint. Waiting for Wi-Fi / wireless debugging.")
        return open(key, "127.0.0.1", discovered, manual, ticket)
    }

    private suspend fun open(key: AdbKey, host: String, port: Int, manual: Boolean, ticket: Long): AdbClient {
        checkCurrent(ticket)
        val client = AdbClient(host, port, key, timeoutMs = 8_000, allowAuthorizationPrompt = manual)
        activeClient = client
        try {
            client.connect()
            checkCurrent(ticket)
            RecoverySettings.prefs.edit().putInt("recovery_last_port", port)
                .putBoolean("recovery_last_tls", client.isTls)
                .putInt("recovery_last_boot", RecoverySettings.bootCount).apply()
            return client
        } catch (e: Exception) {
            client.close()
            activeClient = null
            throw e
        }
    }

    private fun listening(port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    }.getOrDefault(false)

    private suspend fun checkCurrent(ticket: Long) {
        currentCoroutineContext().ensureActive()
        if (generation.get() != ticket) throw CancellationException("Start cancelled.")
    }

    private class RootUnavailableException : Exception("Root access is unavailable. Check authorization and start manually.")
    private class ServerStartException(message: String) : Exception(message)
}
