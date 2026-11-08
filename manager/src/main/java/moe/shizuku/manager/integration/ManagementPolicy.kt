package moe.shizuku.manager.integration

/** Public management protocol validation, independent of Android and any host UI. */
object ManagementPolicy {
    const val VERSION = 1
    val settingNames = setOf("startOnBoot", "watchdog", "tcpEnabled", "tcpPort", "offlineStartup")

    fun validateSettings(values: Map<String, Any?>) {
        require(values.isNotEmpty() && values.keys.all { it in settingNames }) { "invalid_settings" }
        values.forEach { (key, value) ->
            if (key == "tcpPort") require(value is Int && value in 1..65535) { "invalid_port" }
            else require(value is Boolean) { "invalid_settings" }
        }
    }

    fun validatePairing(port: Int, code: String) {
        require(port in 1..65535) { "invalid_port" }
        require(code.length == 6 && code.all { it in '0'..'9' }) { "invalid_pairing_code" }
    }

    fun validateOperation(kind: String, values: Map<String, Any?>) {
        when (kind) {
            "start" -> require(values.keys.all { it in setOf("root", "restart") } &&
                values.values.all { it is Boolean }) { "invalid_arguments" }
            "pair" -> {
                require(values.keys == setOf("port", "code") && values["port"] is Int &&
                    values["code"] is String) { "invalid_arguments" }
                validatePairing(values["port"] as Int, values["code"] as String)
            }
            else -> throw IllegalArgumentException("unknown_command")
        }
    }

    fun shouldStopBackend(operation: String, explicitStop: Boolean = false) =
        explicitStop || operation == "start"

    fun notificationPairingActive(state: String) = state in setOf("searching", "code_required", "pairing")
}
