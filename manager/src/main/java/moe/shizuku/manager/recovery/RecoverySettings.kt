package moe.shizuku.manager.recovery

import android.content.ComponentName
import android.provider.Settings
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

    val prefs get() = ShizukuSettings.getPreferences()
    val tcpEnabled get() = prefs.getBoolean(TCP_ENABLED, false)
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
        prefs.edit().putString(STATUS, message).apply()
    }
}
