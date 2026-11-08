// Modified for host embedding: expose the running server to the host UID.
package moe.shizuku.manager

import android.os.Bundle
import android.os.Binder
import android.os.Process
import androidx.core.os.bundleOf
import moe.shizuku.api.BinderContainer
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.server.ktx.workerHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import moe.shizuku.manager.integration.BackendManagement

class ShizukuManagerProvider : ShizukuProvider() {

    companion object {
        private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
        private const val METHOD_SEND_USER_SERVICE = "sendUserService"
        // Host embedding protocol. The server already authorizes its manager UID.
        const val METHOD_GET_HOST_BINDER = "getHostServerBinder"
        const val EXTRA_HOST_BINDER = "hostServerBinder"
    }

    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == BackendManagement.METHOD) {
            if (!rikka.shizuku.common.BuildConfig.EMBEDDED_MANAGER || Binder.getCallingUid() != Process.myUid()) {
                throw SecurityException("Embedded management requires the host application UID.")
            }
            return BackendManagement.call(arg, extras ?: Bundle())
        }
        if (method == METHOD_GET_HOST_BINDER) {
            if (!rikka.shizuku.common.BuildConfig.EMBEDDED_MANAGER ||
                Binder.getCallingUid() != Process.myUid()) {
                throw SecurityException("Embedded server access requires the host application UID.")
            }
            return Bundle().apply { putBinder(EXTRA_HOST_BINDER, Shizuku.getBinder()) }
        }
        if (extras == null) return null

        return if (method == METHOD_SEND_USER_SERVICE) {
            try {
                extras.classLoader = BinderContainer::class.java.classLoader

                val token = extras.getString(USER_SERVICE_ARG_TOKEN) ?: return null
                val binder = extras.getParcelable<BinderContainer>(EXTRA_BINDER)?.binder ?: return null

                val countDownLatch = CountDownLatch(1)
                var reply: Bundle? = Bundle()

                val listener = object : Shizuku.OnBinderReceivedListener {

                    override fun onBinderReceived() {
                        try {
                            Shizuku.attachUserService(binder, bundleOf(
                                USER_SERVICE_ARG_TOKEN to token
                            ))
                            reply!!.putParcelable(EXTRA_BINDER, BinderContainer(Shizuku.getBinder()))
                        } catch (e: Throwable) {
                            LOGGER.e(e, "attachUserService $token")
                            reply = null
                        }

                        Shizuku.removeBinderReceivedListener(this)

                        countDownLatch.countDown()
                    }
                }

                Shizuku.addBinderReceivedListenerSticky(listener, workerHandler)

                return try {
                    countDownLatch.await(5, TimeUnit.SECONDS)
                    reply
                } catch (e: TimeoutException) {
                    LOGGER.e(e, "Binder not received in 5s")
                    null
                }
            } catch (e: Throwable) {
                LOGGER.e(e, "sendUserService")
                null
            }
        } else {
            super.call(method, arg, extras)
        }
    }
}
