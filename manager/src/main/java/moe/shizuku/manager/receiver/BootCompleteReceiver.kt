package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.recovery.RecoveryService
import moe.shizuku.manager.recovery.RecoverySettings
import moe.shizuku.manager.recovery.BackendRecovery
import moe.shizuku.manager.utils.UserHandleCompat

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (UserHandleCompat.myUserId() > 0) return
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> RecoverySettings.armBoot()
            Intent.ACTION_MY_PACKAGE_REPLACED -> RecoverySettings.reconcileBoot()
            Intent.ACTION_USER_UNLOCKED -> {
                RecoverySettings.reconcileBoot()
                BackendRecovery.retryNow()
            }
            else -> return
        }
        RecoveryService.refresh(context)
    }
}
