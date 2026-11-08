package moe.shizuku.manager.adb

import moe.shizuku.manager.integration.ManagementPolicy

/** Notification replies belong to one discovery session and its current local port. */
internal class PairingNotificationSession(val token: String) {
    var port = -1
        private set
    var state = "searching"
        private set

    fun discover(value: Int) {
        if (state != "searching" && state != "code_required") return
        port = value.takeIf { it in 1..65535 } ?: -1
        state = if (port > 0) "code_required" else "searching"
    }

    fun submit(replyToken: String?, replyPort: Int, code: String): Boolean {
        if (replyToken != token || replyPort != port || state != "code_required") return false
        ManagementPolicy.validatePairing(replyPort, code)
        state = "pairing"
        return true
    }
}
