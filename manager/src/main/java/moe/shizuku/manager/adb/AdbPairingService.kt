package moe.shizuku.manager.adb

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.integration.BackendManagement
import moe.shizuku.manager.integration.ManagementPolicy
import moe.shizuku.manager.recovery.BackendRecovery
import java.util.UUID

/** Keeps Settings' pairing dialog visible while the user replies through SystemUI. */
@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var discovery: AdbMdns? = null
    private var session: PairingNotificationSession? = null
    private var activeTicket: Long? = null
    @Volatile private var closing = false
    private val timeout = Runnable { finish(false, "connection_timeout") }
    private val manager get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        instance = this
        manager.createNotificationChannel(NotificationChannel(notificationChannel,
            getString(R.string.notification_channel_adb_pairing), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            setShowBadge(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> beginSearch()
            STOP -> if (session == null || intent.getStringExtra(TOKEN) == session?.token) stopSelf()
            REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString().orEmpty()
                val token = intent.getStringExtra(TOKEN)
                val port = intent.getIntExtra(PORT, -1)
                // Never retain or ask Android to redeliver the reply/code Intent.
                intent.clipData = null
                intent.replaceExtras(null as android.os.Bundle?)
                submit(token, port, code)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginSearch() {
        if (session != null || closing) return
        if (!notificationsEnabled(this)) {
            state = "error"
            stopSelf()
            return
        }
        session = PairingNotificationSession(UUID.randomUUID().toString())
        state = "searching"
        try {
            startForeground(ID, searching(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            BackendRecovery.pauseForPairing()
            discovery = AdbMdns(this, AdbMdns.TLS_PAIRING) { port ->
                main.post {
                    val current = session ?: return@post
                    if (closing || current.state == "pairing") return@post
                    current.discover(port)
                    state = current.state
                    manager.notify(ID, if (current.port > 0) inputNotification(current) else searching())
                }
            }.apply { start() }
            main.postDelayed(timeout, 120_000)
        } catch (_: Exception) { finish(false, "pairing_failed") }
    }

    private fun submit(token: String?, port: Int, code: String) {
        if (closing) return
        val current = session
        if (current == null) {
            beginSearch()
            return
        }
        if (state == "idle" || current.state == "pairing") return
        val accepted = try {
            current.submit(token, port, code)
        } catch (_: IllegalArgumentException) {
            finish(false, "invalid_pairing_code")
            return
        }
        if (!accepted) {
            manager.notify(ID, if (current.port > 0) inputNotification(current) else searching())
            return
        }
        state = "pairing"
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 45_000)
        manager.notify(ID, builder(getString(R.string.notification_adb_pairing_working_title)).setOngoing(true).build())
        val request = try {
            BackendManagement.pairFromNotification(this, port, code)
        } catch (e: Exception) {
            finish(false, e.message)
            return
        }
        activeTicket = request.ticket
        scope.launch {
            val outcome = request.result.await()
            activeTicket = null
            finish(outcome.success, outcome.error)
        }
    }

    private fun finish(success: Boolean, error: String?) {
        if (closing) return
        closing = true
        activeTicket?.let(BackendManagement::cancelNotificationPairing)
        activeTicket = null
        main.removeCallbacks(timeout)
        runCatching { discovery?.stop() }
        discovery = null
        session = null
        state = if (success) "paired" else "error"
        BackendRecovery.closeWirelessDebuggingWhenIdle()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val title = getString(if (success) R.string.notification_adb_pairing_succeed_title else R.string.notification_adb_pairing_failed_title)
        val text = when {
            success -> getString(R.string.notification_adb_pairing_succeed_text)
            error == "invalid_pairing_code" -> getString(R.string.paring_code_is_wrong)
            error == "key_unavailable" -> getString(R.string.adb_error_key_store)
            else -> getString(R.string.embed_pairing_retry_help)
        }
        val notification = builder(title).setContentText(text).setAutoCancel(true)
        if (!success) notification.addAction(action(getString(R.string.notification_adb_pairing_retry), startIntent(this), false))
        runCatching { manager.notify(ID, notification.build()) }
        stopSelf()
    }

    private fun builder(title: String) = Notification.Builder(this, notificationChannel)
        .setColor(getColor(R.color.notification)).setSmallIcon(R.drawable.ic_system_icon)
        .setContentTitle(title).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)

    private fun stopAction(): Notification.Action = action(getString(R.string.notification_adb_pairing_stop_searching),
        Intent(this, AdbPairingService::class.java).setAction(STOP)
            .setData(Uri.parse("shizuku-pairing://$packageName/${session?.token}/stop"))
            .putExtra(TOKEN, session?.token), false)

    private fun searching(): Notification = builder(getString(R.string.notification_adb_pairing_searching_for_service_title))
        .setContentText(getString(R.string.embed_pairing_search_help)).setOngoing(true).addAction(stopAction()).build()

    private fun inputNotification(current: PairingNotificationSession): Notification {
        val intent = Intent(this, AdbPairingService::class.java).setAction(REPLY)
            .setData(Uri.parse("shizuku-pairing://$packageName/${current.token}/${current.port}"))
            .putExtra(TOKEN, current.token).putExtra(PORT, current.port)
        return builder(getString(R.string.notification_adb_pairing_service_found_title))
            .setContentText(getString(R.string.embed_pairing_reply_help)).setOngoing(true)
            .addAction(action(getString(R.string.notification_adb_pairing_input_paring_code), intent, true))
            .addAction(stopAction()).build()
    }

    private fun action(label: String, intent: Intent, reply: Boolean): Notification.Action {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (reply && Build.VERSION.SDK_INT >= 31)
            PendingIntent.FLAG_MUTABLE else if (reply) 0 else PendingIntent.FLAG_IMMUTABLE
        val pending = if (reply) PendingIntent.getForegroundService(this, 1, intent, flags)
            else PendingIntent.getService(this, 2, intent, flags)
        return Notification.Action.Builder(null, label, pending).apply {
            if (reply) addRemoteInput(RemoteInput.Builder(CODE)
                .setLabel(getString(R.string.dialog_adb_pairing_paring_code)).build())
        }.build()
    }

    override fun onDestroy() {
        closing = true
        activeTicket?.let(BackendManagement::cancelNotificationPairing)
        activeTicket = null
        main.removeCallbacksAndMessages(null)
        session = null
        runCatching { discovery?.stop() }
        discovery = null
        scope.cancel()
        if (instance === this) {
            if (ManagementPolicy.notificationPairingActive(state)) state = "idle"
            instance = null
        }
        BackendRecovery.closeWirelessDebuggingWhenIdle()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) { finish(false, "connection_timeout") }

    override fun onTimeout(startId: Int, fgsType: Int) { finish(false, "connection_timeout") }

    companion object {
        const val notificationChannel = "adb_pairing"
        private const val ID = 1
        private const val START = "start"
        private const val STOP = "stop"
        private const val REPLY = "reply"
        private const val TOKEN = "pairing_session"
        private const val PORT = "pairing_port"
        private const val CODE = "pairing_code"
        @Volatile var state = "idle"
            private set
        @Volatile private var instance: AdbPairingService? = null

        fun startIntent(context: Context) = Intent(context, AdbPairingService::class.java).setAction(START)

        fun startSearch(context: Context) {
            if (ManagementPolicy.notificationPairingActive(state)) return
            state = "searching"
            try { context.startForegroundService(startIntent(context)) }
            catch (e: Exception) { state = "error"; throw e }
        }

        fun stopSearch(context: Context) {
            instance?.closing = true
            context.stopService(Intent(context, AdbPairingService::class.java))
            state = "idle"
        }

        fun notificationsEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannel(notificationChannel) ?: return true
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
            val group = channel.group?.let { manager.getNotificationChannelGroup(it) }
            return group?.isBlocked != true
        }
    }
}
