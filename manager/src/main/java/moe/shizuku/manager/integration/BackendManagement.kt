package moe.shizuku.manager.integration

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.application
import moe.shizuku.manager.receiver.BootCompleteReceiver
import moe.shizuku.manager.recovery.BackendRecovery
import moe.shizuku.manager.recovery.ExperimentalWirelessSession
import moe.shizuku.manager.recovery.RecoveryService
import moe.shizuku.manager.recovery.RecoverySettings
import rikka.shizuku.Shizuku

/** Manager-process implementation. No host identity, Flutter or Activity dependency. */
object BackendManagement {
    const val METHOD = "embeddedManagementV1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var busy = false
    private var operation = "idle"
    private var activeKind = ""
    private var errorCode: String? = null
    private var generation = 0L
    private val output = StringBuilder()
    @Volatile private var pairing: AdbPairingClient? = null
    @Volatile private var activeJob: Job? = null

    fun call(command: String?, args: Bundle): Bundle = when (command) {
        "snapshot" -> snapshot()
        "configure" -> configure(args)
        "start", "pair" -> enqueue(command, args)
        "cancel" -> {
            val starting = synchronized(lock) {
                if (busy) operation = "cancelling"
                busy && ManagementPolicy.shouldStopBackend(activeKind)
            }
            activeJob?.cancel()
            pairing?.cancel()
            if (starting) BackendRecovery.stop()
            snapshot()
        }
        "stop" -> {
            synchronized(lock) { operation = if (busy) "cancelling" else "stopped" }
            activeJob?.cancel()
            pairing?.cancel()
            BackendRecovery.stop()
            snapshot()
        }
        "report" -> snapshot().apply {
            putString("report", buildString {
                appendLine("Shizuku Embed management v${ManagementPolicy.VERSION}")
                appendLine(RecoverySettings.statusReport("No recovery attempt recorded."))
                appendLine()
                appendLine(RecoverySettings.experimentReport("No offline startup attempt recorded."))
                synchronized(lock) { if (output.isNotEmpty()) append("\nLast operation\n$output") }
            }.takeLast(24000))
        }
        else -> throw IllegalArgumentException("unknown_command")
    }

    private fun configure(args: Bundle): Bundle {
        val values = args.keySet().associateWith { @Suppress("DEPRECATION") args.get(it) }
        ManagementPolicy.validateSettings(values)
        if (args.getBoolean("offlineStartup", false)) {
            require(Build.VERSION.SDK_INT >= 30) { "unsupported" }
            ExperimentalWirelessSession.checkPrerequisites(application)
        }
        synchronized(lock) {
            check(!busy) { "busy" }
            val prefs = RecoverySettings.prefs
            val editor = prefs.edit()
            values.forEach { (name, value) -> when (name) {
                "startOnBoot" -> {
                    val enabled = value as Boolean
                    application.packageManager.setComponentEnabledSetting(
                        ComponentName(application, BootCompleteReceiver::class.java),
                        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP)
                    editor.putBoolean(ShizukuSettings.KEEP_START_ON_BOOT, enabled)
                    if (!enabled) editor.putBoolean(RecoverySettings.BOOT_PENDING, false)
                }
                "watchdog" -> editor.putBoolean(RecoverySettings.WATCHDOG, value as Boolean)
                "tcpEnabled" -> editor.putBoolean(RecoverySettings.TCP_ENABLED, value as Boolean)
                "tcpPort" -> editor.putString(RecoverySettings.TCP_PORT, value.toString())
                "offlineStartup" -> {
                    editor.putBoolean(RecoverySettings.EXPERIMENT_ENABLED, value as Boolean)
                    if (!value) BackendRecovery.disableExperiment()
                    RecoverySettings.resetExperiment()
                }
            } }
            editor.apply()
        }
        RecoveryService.refresh(application)
        return snapshot()
    }

    private fun enqueue(kind: String, args: Bundle): Bundle {
        ManagementPolicy.validateOperation(kind, args.keySet().associateWith { @Suppress("DEPRECATION") args.get(it) })
        if (kind == "pair") {
            require(Build.VERSION.SDK_INT >= 30) { "unsupported" }
            ManagementPolicy.validatePairing(args.getInt("port"), args.getString("code").orEmpty())
        }
        val ticket = synchronized(lock) {
            check(!busy) { "busy" }
            busy = true
            errorCode = null
            operation = if (kind == "pair") "pairing" else "starting"
            activeKind = kind
            output.setLength(0)
            ++generation
        }
        val intent = Intent(application, BackendControlService::class.java)
            .putExtra("kind", kind).putExtra("ticket", ticket)
            .putExtra("root", args.getBoolean("root", false))
            .putExtra("restart", args.getBoolean("restart", false))
        if (kind == "pair") intent.putExtra("port", args.getInt("port"))
            .putExtra("code", args.getString("code"))
        try {
            if (Build.VERSION.SDK_INT >= 26) application.startForegroundService(intent)
            else application.startService(intent)
        } catch (e: Exception) {
            synchronized(lock) { busy = false; operation = "error"; errorCode = "foreground_start_denied" }
            throw e
        }
        return snapshot()
    }

    internal fun run(service: Service, intent: Intent, startId: Int) {
        val ticket = intent.getLongExtra("ticket", -1)
        val task = scope.launch {
            try {
                if (synchronized(lock) { ticket != generation || !busy }) return@launch
                check(synchronized(lock) { operation != "cancelling" }) { "cancelled" }
                currentCoroutineContext().ensureActive()
                if (intent.getStringExtra("kind") == "pair") {
                    val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                    val client = AdbPairingClient("127.0.0.1", intent.getIntExtra("port", 0),
                        intent.getStringExtra("code").orEmpty(), key)
                    // The code exists only in this one-shot request, never diagnostics or preferences.
                    intent.removeExtra("code")
                    pairing = client
                    currentCoroutineContext().ensureActive()
                    client.use { check(it.start()) { "pairing_failed" } }
                    currentCoroutineContext().ensureActive()
                    RecoverySettings.prefs.edit().putLong("management_last_paired", System.currentTimeMillis()).apply()
                    synchronized(lock) { operation = "paired" }
                } else {
                    BackendRecovery.startManual(intent.getBooleanExtra("root", false),
                        restart = intent.getBooleanExtra("restart", false), output = ::appendOutput)
                    synchronized(lock) { operation = "running" }
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    operation = if (operation == "cancelling" || e is CancellationException) "cancelled" else "error"
                    // Exception messages can contain protocol details. Expose stable, non-secret codes.
                    errorCode = if (operation == "cancelled") null else when (e.javaClass.simpleName) {
                        "AdbInvalidPairingCodeException" -> "invalid_pairing_code"
                        "AdbKeyException" -> "key_unavailable"
                        "ExperimentalPermissionException" -> "permission_required"
                        "SocketTimeoutException" -> "connection_timeout"
                        else -> if (intent.getStringExtra("kind") == "pair") "pairing_failed" else "start_failed"
                    }
                }
            } finally {
                pairing = null
            }
        }
        activeJob = task
        if (synchronized(lock) { operation == "cancelling" }) task.cancel()
        task.invokeOnCompletion {
            synchronized(lock) {
                if (ticket == generation) {
                    busy = false
                    activeJob = null
                    if (operation == "cancelling") operation = "cancelled"
                }
            }
            service.stopSelf(startId)
        }
    }

    internal fun serviceDestroyed(ticket: Long) {
        if (synchronized(lock) { busy && generation == ticket }) {
            synchronized(lock) { operation = "cancelling" }
            activeJob?.cancel()
            pairing?.cancel()
            if (synchronized(lock) { ManagementPolicy.shouldStopBackend(activeKind) }) BackendRecovery.stop()
        }
    }

    private fun appendOutput(line: String) = synchronized(lock) {
        output.appendLine(line)
        if (output.length > 16000) output.delete(0, output.length - 16000)
    }

    fun snapshot(): Bundle {
        RecoverySettings.reconcileBoot()
        val running = Shizuku.pingBinder()
        val uid = if (running) runCatching { Shizuku.getUid() }.getOrDefault(-1) else -1
        return Bundle().apply {
            putInt("protocolVersion", ManagementPolicy.VERSION)
            putBoolean("running", running)
            putInt("uid", uid)
            putString("launchMode", when (ShizukuSettings.getLastLaunchMode()) {
                ShizukuSettings.LaunchMethod.ROOT -> "root"
                ShizukuSettings.LaunchMethod.ADB -> "adb"
                else -> "unknown"
            })
            putInt("androidApi", Build.VERSION.SDK_INT)
            putBoolean("pairingSupported", Build.VERSION.SDK_INT >= 30)
            putBoolean("writeSecureSettings", application.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)
            putBoolean("hotspotPermission", Build.VERSION.SDK_INT >= 30 &&
                application.checkSelfPermission(ExperimentalWirelessSession.hotspotPermission()) == PackageManager.PERMISSION_GRANTED)
            putString("applicationId", application.packageName)
            putBoolean("startOnBoot", RecoverySettings.startOnBoot)
            putBoolean("watchdog", RecoverySettings.watchdog)
            putBoolean("tcpEnabled", RecoverySettings.tcpEnabled)
            putInt("tcpPort", RecoverySettings.port)
            putBoolean("offlineStartup", RecoverySettings.experimentEnabled)
            putLong("lastPairedAt", RecoverySettings.prefs.getLong("management_last_paired", 0))
            putString("recoveryStatus", RecoverySettings.statusSummary("No recovery attempt recorded."))
            synchronized(lock) {
                putBoolean("busy", busy)
                putString("operation", operation)
                putString("errorCode", errorCode)
                putString("output", output.toString())
            }
            putLong("readAtElapsedMs", SystemClock.elapsedRealtime())
        }
    }
}
