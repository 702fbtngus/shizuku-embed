package moe.shizuku.manager.recovery

import android.content.ComponentName
import android.provider.Settings
import android.os.SystemClock
import android.os.UserManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.application
import moe.shizuku.manager.ktx.isComponentEnabled
import moe.shizuku.manager.receiver.BootCompleteReceiver

internal object RecoverySettings {
    const val TCP_ENABLED = "recovery_tcp_enabled"
    const val TCP_PORT = "recovery_tcp_port"
    const val WATCHDOG = "recovery_watchdog"
    const val STOPPED = "recovery_stopped"
    const val PAUSED = "recovery_paused"
    const val BOOT_PENDING = "recovery_boot_pending"
    const val STATUS = "recovery_status"
    private const val STATUS_TIME = "recovery_status_time"
    private const val STATUS_BOOT = "recovery_status_boot"
    private const val STATUS_UPTIME = "recovery_status_uptime"
    private const val TRIGGER = "recovery_trigger"
    const val EXPERIMENT_ENABLED = "recovery_experiment_enabled"
    const val EXPERIMENT_LOG = "recovery_experiment_log"
    const val EXPERIMENT_BOOT = "recovery_experiment_boot"
    private const val EXPERIMENT_FAILURES = "recovery_experiment_failures"
    private const val EXPERIMENT_RETRY_AT = "recovery_experiment_retry_at"

    val prefs get() = ShizukuSettings.getPreferences()
    val tcpEnabled get() = prefs.getBoolean(TCP_ENABLED, false)
    val experimentEnabled get() = prefs.getBoolean(EXPERIMENT_ENABLED, false)
    val experimentFailures get() = prefs.getInt(EXPERIMENT_FAILURES, 0)
    val experimentRetryAt get() = prefs.getLong(EXPERIMENT_RETRY_AT, 0)

    fun resetExperiment() {
        prefs.edit().putInt(EXPERIMENT_FAILURES, 0).putLong(EXPERIMENT_RETRY_AT, 0).apply()
    }

    fun failedExperiment(now: Long) {
        prefs.edit().putInt(EXPERIMENT_FAILURES, experimentFailures + 1)
            .putLong(EXPERIMENT_RETRY_AT, now + RecoveryPolicy.EXPERIMENT_COOLDOWN_MS).apply()
    }
    val port get() = prefs.getString(TCP_PORT, "5555")?.toIntOrNull()
        ?.takeIf(RecoveryPolicy::validPort) ?: RecoveryPolicy.DEFAULT_TCP_PORT
    val watchdog get() = prefs.getBoolean(WATCHDOG, false)
    val stopped get() = prefs.getBoolean(STOPPED, false)
    val bootPending get() = prefs.getBoolean(BOOT_PENDING, false)
    val autoAllowed get() = RecoveryPolicy.autoAllowed(stopped,
        prefs.getBoolean(PAUSED, false), watchdog, bootPending)
    val bootCount get() = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT, -1)
    val startOnBoot get() = application.packageManager.isComponentEnabled(
        ComponentName(application, BootCompleteReceiver::class.java))

    /** Jobs can wake before the boot receiver, so apply this policy at every entry. */
    @Synchronized
    fun reconcileBoot() {
        val boot = bootCount
        val previous = prefs.getInt("recovery_boot", -1)
        if (boot == previous) return
        val editor = prefs.edit().putInt("recovery_boot", boot)
        if (previous != -1) {
            val enabled = startOnBoot && ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.UNKNOWN
            editor.putBoolean(STOPPED, !enabled).putBoolean(BOOT_PENDING, enabled)
                .putBoolean(PAUSED, false)
                .putInt(EXPERIMENT_FAILURES, 0).putLong(EXPERIMENT_RETRY_AT, 0)
        }
        editor.apply()
    }

    fun armBoot() {
        reconcileBoot()
        if (startOnBoot && !stopped &&
            ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.UNKNOWN) {
            prefs.edit().putBoolean(BOOT_PENDING, true).apply()
        }
    }

    fun status(message: String) {
        val boot = bootCount
        if (prefs.getString(STATUS, null) == message && prefs.getInt(STATUS_BOOT, -1) == boot) return
        prefs.edit().putString(STATUS, message).putInt(STATUS_BOOT, boot)
            .putLong(STATUS_TIME, System.currentTimeMillis())
            .putLong(STATUS_UPTIME, SystemClock.elapsedRealtime()).apply()
    }

    fun trigger(reason: String) {
        prefs.edit().putString(TRIGGER, reason).apply()
    }

    val lastTrigger get() = prefs.getString(TRIGGER, "unknown").orEmpty()

    fun formatTime(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(time))

    fun statusSummary(fallback: String): String {
        val message = prefs.getString(STATUS, fallback).orEmpty()
        val time = prefs.getLong(STATUS_TIME, 0)
        return if (time == 0L) "$message\nRecorded time unavailable for this older status."
        else "$message\n${formatTime(time)} / boot ${prefs.getInt(STATUS_BOOT, -1)}"
    }

    fun statusReport(fallback: String): String = buildString {
        appendLine(statusSummary(fallback))
        appendLine("Recorded uptimeMs=${prefs.getLong(STATUS_UPTIME, -1)}")
        appendLine("Current boot=${prefs.getInt("recovery_boot", -1)} userUnlocked=${application.getSystemService(UserManager::class.java).isUserUnlocked}")
        appendLine("Last readiness trigger=$lastTrigger")
        appendLine("startOnBoot=$startOnBoot watchdog=$watchdog bootPending=$bootPending stopped=$stopped")
        appendLine("paused=${prefs.getBoolean(PAUSED, false)} experimental=$experimentEnabled tcpOnStart=$tcpEnabled")
        append("experimentalFailures=$experimentFailures retryAtUptimeMs=$experimentRetryAt")
    }

    fun experimentReport(fallback: String): String {
        val report = prefs.getString(EXPERIMENT_LOG, null) ?: return fallback
        val recorded = prefs.getInt(EXPERIMENT_BOOT, -1)
        val current = prefs.getInt("recovery_boot", -1)
        val origin = when {
            recorded < 0 -> "Recorded boot unavailable for this older report."
            recorded != current -> "Last experiment was recorded in a different boot."
            else -> "Last experiment was recorded in the current boot."
        }
        return "Current boot=$current / recorded boot=$recorded\n$origin\n\n$report"
    }
}
