package moe.shizuku.manager.recovery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

/** An opt-in, visible watchdog. The persisted job backs up process/foreground-start failures. */
class RecoveryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL,
                getString(R.string.recovery_channel), NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTIFICATION_ID, notification())
        scope.launch {
            while (isActive) {
                if (!RecoverySettings.autoAllowed) break
                BackendRecovery.tick()
                if (!RecoverySettings.autoAllowed) break
                manager.notify(NOTIFICATION_ID, notification())
                delay(if (Shizuku.pingBinder()) 30_000 else 5_000)
            }
            scheduleJob(this@RecoveryService)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.recovery_channel))
            .setContentText(RecoverySettings.prefs.getString(RecoverySettings.STATUS,
                getString(R.string.recovery_waiting)))
            .setOngoing(true).setSilent(true)
        // Return through the host's launcher so its authentication policy still applies.
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "shizuku_backend_recovery"
        private const val NOTIFICATION_ID = 0x53485A4B
        // Reserved in this host UID for the embedded backend's periodic backstop.
        private const val JOB_ID = 0x53485A4B

        fun refresh(context: Context) {
            RecoverySettings.reconcileBoot()
            scheduleJob(context)
            val intent = Intent(context, RecoveryService::class.java)
            if (!RecoverySettings.autoAllowed) {
                context.stopService(intent)
                return
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                RecoverySettings.status("Foreground recovery unavailable. Periodic retry remains scheduled. ${it.javaClass.simpleName}")
            }
        }

        fun scheduleJob(context: Context) {
            val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
            if (!RecoverySettings.autoAllowed) {
                scheduler.cancel(JOB_ID)
                return
            }
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecoveryJobService::class.java))
                .setPersisted(true).setPeriodic(15 * 60_000L).build()
            runCatching { scheduler.schedule(job) }
        }
    }
}
