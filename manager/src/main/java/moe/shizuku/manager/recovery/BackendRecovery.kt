package moe.shizuku.manager.recovery

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemProperties
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLHandshakeException

/** Runs only in the manager process. Every launch path shares one cancellable ADB attempt. */
object BackendRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var activeClient: AdbClient? = null
    @Volatile private var activeExperiment: ExperimentalWirelessSession? = null
    @Volatile private var stopLateBinderUntil = 0L
    private val retry = RecoveryRetry()
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

    fun retryNow(reason: String = "readiness") {
        scope.launch {
            mutex.lock()
            try {
                retry.reset()
                if (RecoverySettings.autoAllowed) RecoverySettings.trigger(reason)
            } finally { mutex.unlock() }
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
        startFailures = 0
        retry.reset()
        RecoverySettings.resetExperiment()
        RecoverySettings.status("Running as ${if (uid == 0) "root" else "shell"}.")
    }

    fun stop() {
        RecoverySettings.prefs.edit().putBoolean(RecoverySettings.STOPPED, true)
            .putBoolean(RecoverySettings.BOOT_PENDING, false).apply()
        if (mutex.isLocked) stopLateBinderUntil = SystemClock.elapsedRealtime() + 30_000
        val ticket = generation.incrementAndGet()
        activeExperiment?.cancel()
        activeClient?.close()
        RecoverySettings.status("Stopped by user. Start manually to resume recovery.")
        RecoveryService.refresh(application)
        scope.launch {
            mutex.lock()
            try {
                if (generation.get() == ticket && RecoverySettings.stopped) runCatching { Shizuku.exit() }
            } finally { mutex.unlock() }
        }
    }

    fun disableExperiment() {
        if (activeExperiment != null) {
            generation.incrementAndGet()
            activeExperiment?.cancel()
            activeClient?.close()
        }
    }

    suspend fun startManual(root: Boolean, host: String? = null, port: Int = 0,
                            restart: Boolean = false, output: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        val ticket = generation.incrementAndGet()
        activeExperiment?.cancel()
        activeClient?.close()
        mutex.lock()
        try {
            checkCurrent(ticket)
            RecoverySettings.prefs.edit().putBoolean(RecoverySettings.STOPPED, false)
                .putBoolean(RecoverySettings.PAUSED, false).apply()
            stopLateBinderUntil = 0
            startFailures = 0
            retry.reset()
            RecoverySettings.trigger("manual")
            RecoverySettings.resetExperiment()
            if (restart && Shizuku.pingBinder()) {
                val previousBinder = Shizuku.getBinder()
                output("Stopping the previous service...")
                stopBackendForRestart(
                    requestStop = { Shizuku.exit() },
                    isPreviousAlive = { previousBinder?.pingBinder() == true },
                    checkCurrent = { checkCurrent(ticket) },
                )
                output("The previous service stopped. Starting again...")
            }
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
            val root = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT
            val waitingForUnlock = !root && !application.getSystemService(UserManager::class.java).isUserUnlocked
            if (!retry.canAttempt(SystemClock.elapsedRealtime(), waitingForUnlock)) {
                if (waitingForUnlock) RecoverySettings.status("Waiting for the first device unlock. Recovery resumes automatically.")
                return
            }
            try {
                start(root, null, 0, false, ticket, RecoverySettings::status)
            } catch (_: UserLockedException) {
                retry.reset()
                RecoverySettings.status("Waiting for the first device unlock. Recovery resumes automatically.")
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return
            } catch (e: Exception) {
                if (ticket != generation.get()) return
                val authorization = e is AdbAuthorizationException || e is SSLHandshakeException ||
                    e is AdbKeyException || e is RootUnavailableException || e is ExperimentalPermissionException
                if (e is ServerStartException) startFailures += 1
                if (authorization || startFailures >= 3) {
                    RecoverySettings.prefs.edit().putBoolean(RecoverySettings.PAUSED, true).apply()
                    RecoverySettings.status("Automatic recovery paused. Open Shizuku, check pairing and start manually. ${e.message}")
                } else {
                    val delay = retry.failed(SystemClock.elapsedRealtime())
                    RecoverySettings.status("Waiting to retry in ${delay / 1000}s. ${e.message}")
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun start(root: Boolean, host: String?, port: Int, manual: Boolean,
                              ticket: Long, output: (String) -> Unit) {
        try {
            startServer(root, host, port, manual, ticket, output)
            activeExperiment?.record("Service binder received.")
        } catch (e: CancellationException) {
            activeExperiment?.record("Attempt cancelled.")
            throw e
        } catch (e: Exception) {
            activeExperiment?.record("Attempt failed. ${e.javaClass.simpleName}: ${e.message}")
            if (activeExperiment != null) RecoverySettings.failedExperiment(SystemClock.elapsedRealtime())
            throw e
        } finally {
            withContext(NonCancellable) { activeExperiment?.close() }
            activeExperiment = null
        }
    }

    private suspend fun startServer(root: Boolean, host: String?, port: Int, manual: Boolean,
                                    ticket: Long, output: (String) -> Unit) {
        if (!root && !application.getSystemService(UserManager::class.java).isUserUnlocked) {
            throw UserLockedException()
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
                    activeExperiment?.stopNudging()
                    activeExperiment?.record("Switching adbd to TCP port $tcpPort.")
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
        val wifiConnected = ExperimentalWirelessSession.hasWifiConnection(application)
        val offline = RecoverySettings.experimentEnabled && !wifiConnected
        val experimentAllowed = RecoveryPolicy.experimentalAllowed(RecoverySettings.experimentEnabled,
            wifiConnected, manual, RecoverySettings.experimentFailures, RecoverySettings.experimentRetryAt,
            SystemClock.elapsedRealtime())
        if (experimentAllowed) {
            val experiment = ExperimentalWirelessSession(application)
            activeExperiment = experiment
            experiment.prepare(output)
        }
        checkCurrent(ticket)
        if (application.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val resolver = application.contentResolver
            Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)
        }
        if (!experimentAllowed) output("Waiting for wireless debugging. Connect Wi-Fi and enable wireless debugging if needed.")
        val discoveredPort = AtomicInteger(-1)
        val mdns = AdbMdns(application, AdbMdns.TLS_CONNECT) {
            if (RecoveryPolicy.validPort(it)) discoveredPort.set(it)
        }
        val window = if (experimentAllowed) RecoveryPolicy.EXPERIMENT_WINDOW_MS else 8_000L
        val deadline = SystemClock.elapsedRealtime() + window
        var lastFailure: String? = null
        try {
            // Start discovery after the temporary interface has settled.
            mdns.start()
            while (SystemClock.elapsedRealtime() < deadline) {
                checkCurrent(ticket)
                activeExperiment?.writeFailure?.let { throw ExperimentalPermissionException(it) }
                val direct = SystemProperties.getInt("service.adb.tls.port", -1)
                val candidates = listOf(direct, discoveredPort.get()).filter(RecoveryPolicy::validPort).distinct()
                for (candidate in candidates) {
                    if (!listening(candidate)) continue
                    try {
                        val client = open(key, "127.0.0.1", candidate, manual, ticket)
                        activeExperiment?.record("Authenticated ADB endpoint on port $candidate.")
                        return client
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: AdbAuthorizationException) {
                        throw e
                    } catch (e: SSLHandshakeException) {
                        throw e
                    } catch (e: Exception) {
                        // Android can close the experimental TLS endpoint while a handshake is in flight.
                        lastFailure = "${e.javaClass.simpleName}: ${e.message}"
                    }
                }
                delay(125)
            }
        } finally { runCatching { mdns.stop() } }
        checkCurrent(ticket)
        if (experimentAllowed) error("Offline startup timed out. Android may require a Wi-Fi connection. Last connection failure=$lastFailure")
        if (offline) error("No ADB endpoint. Offline experiment is cooling down or reached its three-attempt limit. Connect Wi-Fi or start manually.")
        error("No ADB endpoint. Waiting for Wi-Fi / wireless debugging.")
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
    private class UserLockedException : Exception("Unlock the device once before restoring the ADB key.")
    private class ServerStartException(message: String) : Exception(message)
}
