package moe.shizuku.manager.integration

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import moe.shizuku.manager.R

/** Keeps a user-requested operation alive when the consuming UI leaves the foreground. */
class BackendControlService : Service() {
    private var ticket = -1L
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.embed_control_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            notification.setContentIntent(PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        startForeground(0x53485A43, notification.setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(getString(R.string.embed_control_title))
            .setContentText(getString(R.string.embed_control_description)).setOngoing(true).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) stopSelf(startId) else {
            ticket = intent.getLongExtra("ticket", -1)
            BackendManagement.run(this, intent, startId)
        }
        // Never ask Android to persist/redeliver a request containing a pairing code.
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { BackendManagement.serviceDestroyed(ticket); super.onDestroy() }

    companion object { private const val CHANNEL = "shizuku_embed_operations" }
}
