package moe.shizuku.manager.integration

import android.content.Context
import android.net.Uri
import android.os.Bundle
import moe.shizuku.manager.ShizukuManagerProvider
import moe.shizuku.server.IShizukuService

/** Access from the host UID. Call on a worker thread after host authentication. */
object EmbeddedShizuku {
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
