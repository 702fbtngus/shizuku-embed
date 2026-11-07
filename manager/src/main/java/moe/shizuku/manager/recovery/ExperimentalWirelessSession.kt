package moe.shizuku.manager.recovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Adapted from Shizuku Next ForcedWirelessDebugging, revision 35d46fb8dbada23960a9818fa957c8c3f283124a. */
@RequiresApi(30)
internal class ExperimentalWirelessSession(private val context: Context) {
    private val lease = HotspotLease()
    private val started = SystemClock.elapsedRealtime()
    private val diagnostics = StringBuilder("Experimental offline startup\n")
    private var nudger: Job? = null
    @Volatile private var hotspotRequest: CancellableContinuation<Boolean>? = null
    @Volatile var writeFailure: String? = null
        private set
    private var writes = 0

    suspend fun prepare(output: (String) -> Unit) {
        checkPrerequisites(context)
        record("${Build.MANUFACTURER} ${Build.MODEL}; API ${Build.VERSION.SDK_INT}; package=${context.packageName}.")
        record("WSS and hotspot permission granted; TCP on start=${RecoverySettings.tcpEnabled}.")
        saveLog()
        output("Requesting a temporary local-only hotspot for offline startup...")
        val hotspot = requestHotspot()
        record(if (hotspot) "Local-only hotspot ready." else "Continuing without a hotspot interface.")
        output("Searching for offline ADB for up to 120 seconds...")
        nudger = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            val deadline = SystemClock.elapsedRealtime() + RecoveryPolicy.EXPERIMENT_WINDOW_MS
            while (isActive && SystemClock.elapsedRealtime() < deadline) {
                try {
                    // SettingsProvider ignores an unchanged value. Ask again only after Android turns it off.
                    if (Settings.Global.getInt(resolver, "adb_wifi_enabled", 0) != 1) {
                        if (!Settings.Global.putInt(resolver, "adb_wifi_enabled", 1)) {
                            error("The system rejected the wireless debugging write.")
                        }
                        writes += 1
                    }
                } catch (e: Exception) {
                    writeFailure = "${e.javaClass.simpleName}: ${e.message}"
                    record("Wireless debugging write failed. $writeFailure")
                    break
                }
                delay(20)
            }
        }
    }

    private suspend fun requestHotspot(): Boolean {
        val wifi = context.getSystemService(WifiManager::class.java)
        val ready = withTimeoutOrNull(12_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    hotspotRequest = continuation
                    continuation.invokeOnCancellation { lease.close() }
                    try {
                        wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                                val accepted = lease.attach(reservation)
                                if (continuation.isActive) {
                                    continuation.resume(accepted) { _, _, _ -> lease.close() }
                                }
                            }

                            override fun onStopped() {
                                record("Local-only hotspot stopped by Android.")
                                if (continuation.isActive) continuation.resume(false)
                            }

                            override fun onFailed(reason: Int) {
                                record("Local-only hotspot refused. ${hotspotReason(reason)} (reason=$reason).")
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }, Handler(Looper.getMainLooper()))
                    } catch (e: Exception) {
                        record("Local-only hotspot request failed. ${e.javaClass.simpleName}: ${e.message}")
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        }
        hotspotRequest = null
        if (ready == null) record("Hotspot callback timed out after 12 seconds.")
        return ready == true
    }

    @Synchronized fun record(message: String) {
        if (diagnostics.length < 12_000) {
            diagnostics.append(SystemClock.elapsedRealtime() - started).append("ms ")
                .appendLine(message)
        }
    }

    @Synchronized private fun saveLog() {
        RecoverySettings.prefs.edit().putString(RecoverySettings.EXPERIMENT_LOG, diagnostics.toString()).apply()
    }

    suspend fun stopNudging() {
        nudger?.cancelAndJoin()
        nudger = null
    }

    fun cancel() {
        nudger?.cancel()
        hotspotRequest?.cancel(CancellationException("Offline attempt cancelled."))
        lease.close()
    }

    suspend fun close() {
        stopNudging()
        lease.close()
        record("Finished; wireless debugging writes=$writes; hotspot cleanup requested.")
        saveLog()
    }

    companion object {
        private fun hotspotReason(reason: Int): String = when (reason) {
            WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No channel available"
            WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "The system refused the request"
            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Incompatible Wi-Fi or tethering mode"
            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Tethering is disallowed"
            else -> "Unknown system refusal"
        }

        fun hotspotPermission(): String = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

        fun checkPrerequisites(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED)
                throw ExperimentalPermissionException("Grant WRITE_SECURE_SETTINGS through ADB first.")
            if (context.checkSelfPermission(hotspotPermission()) != PackageManager.PERMISSION_GRANTED)
                throw ExperimentalPermissionException("Allow the hotspot permission in Shizuku Settings, then start manually.")
        }

        fun hasWifiConnection(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
    }
}

internal class ExperimentalPermissionException(message: String) : Exception(message)
