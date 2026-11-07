package moe.shizuku.manager.recovery

/** Transport and retry rules shared by manual, boot and watchdog starts. */
internal object RecoveryPolicy {
    const val DEFAULT_TCP_PORT = 5555
    const val EXPERIMENT_WINDOW_MS = 120_000L
    const val EXPERIMENT_COOLDOWN_MS = 300_000L
    const val EXPERIMENT_MAX_FAILURES = 3

    fun experimentalAllowed(enabled: Boolean, wifiConnected: Boolean, manual: Boolean,
                            failures: Int, retryAt: Long, now: Long) =
        enabled && !wifiConnected &&
            (manual || failures < EXPERIMENT_MAX_FAILURES && now >= retryAt)

    fun validPort(port: Int) = port in 1..65535

    fun ports(systemPort: Int, tcpEnabled: Boolean, configuredPort: Int,
              savedPort: Int, savedTls: Boolean, savedBoot: Int, boot: Int): List<Int> {
        return listOf(systemPort, if (tcpEnabled) configuredPort else -1,
            if (!savedTls || savedBoot >= 0 && savedBoot == boot) savedPort else -1)
            .filter(::validPort).distinct()
    }

    fun retryDelayMs(failures: Int): Long = 5_000L shl failures.coerceIn(0, 6)

    fun autoAllowed(stopped: Boolean, paused: Boolean, watchdog: Boolean, bootPending: Boolean) =
        !stopped && !paused && (watchdog || bootPending)
}
