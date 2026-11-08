package moe.shizuku.manager.integration

import android.content.Context
import android.net.Uri
import android.os.Bundle
import moe.shizuku.manager.ShizukuManagerProvider
import moe.shizuku.server.IShizukuService

/** Access from the host UID. Call on a worker thread after host authentication. */
object EmbeddedShizuku {
    /** Versioned, host-UID-only management. Call off the main thread after host authentication.
     * Commands: snapshot, configure, start, pair, cancel, stop, report. Long operations
     * return immediately; observe busy/operation/output in subsequent snapshots.
     */
    @JvmStatic
    fun manage(context: Context, command: String, arguments: Bundle = Bundle()): Bundle {
        require(command in setOf("snapshot", "configure", "start", "pair", "cancel", "stop", "report")) { "unknown_command" }
        return context.contentResolver.call(Uri.parse("content://${context.packageName}.shizuku"),
            BackendManagement.METHOD, command, arguments) ?: error("management_unavailable")
    }

    @JvmStatic
    fun getService(context: Context): IShizukuService? {
        val reply = context.contentResolver.call(
            Uri.parse("content://${context.packageName}.shizuku"),
            ShizukuManagerProvider.METHOD_GET_HOST_BINDER, null, Bundle(),
        )
        val binder = reply?.getBinder(ShizukuManagerProvider.EXTRA_HOST_BINDER)
            ?.takeIf { it.pingBinder() } ?: return null
        return IShizukuService.Stub.asInterface(binder)
    }
}
