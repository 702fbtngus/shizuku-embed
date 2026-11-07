// Modified for OpenLock embedding; standalone behavior is retained.
package moe.shizuku.manager.ktx

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.app.Application

val Context.application: Application
    get() {
        return applicationContext as Application
    }

fun Context.createDeviceProtectedStorageContextCompat(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}

fun Context.createDeviceProtectedStorageContextCompatWhenLocked(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
        createDeviceProtectedStorageContext()
    } else {
        this
    }
}
