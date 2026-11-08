package moe.shizuku.manager.integration

import org.junit.Assert.*
import org.junit.Test

class ManagementPolicyTest {
    @Test fun notificationSetupBlocksAutomaticStartupOnlyWhileActive() {
        for (state in listOf("searching", "code_required", "pairing")) {
            assertTrue(ManagementPolicy.notificationPairingActive(state))
        }
        for (state in listOf("idle", "paired", "error")) {
            assertFalse(ManagementPolicy.notificationPairingActive(state))
        }
    }

    @Test fun partialSettingsUpdatesPreserveAWellDefinedContract() {
        ManagementPolicy.validateSettings(mapOf("tcpEnabled" to true, "tcpPort" to 5555))
        ManagementPolicy.validateSettings(mapOf("startOnBoot" to false))
        ManagementPolicy.validateSettings(mapOf("watchdog" to true, "offlineStartup" to false))
    }

    @Test fun unknownSettingsAndImplicitTypeConversionsAreRejected() {
        listOf(mapOf("packageName" to "another.app"), mapOf("watchdog" to "true"),
            mapOf("tcpPort" to "5555"), mapOf("tcpPort" to 5555L),
            mapOf("tcpPort" to 0), mapOf("tcpPort" to 65536), emptyMap()).forEach {
            assertThrows(IllegalArgumentException::class.java) { ManagementPolicy.validateSettings(it) }
        }
    }

    @Test fun pairingUsesAnExplicitLocalPortAndExactlySixAsciiDigits() {
        ManagementPolicy.validatePairing(37123, "012345")
        listOf("12345", "1234567", "12 345", "abcdef", "１２３４５６").forEach {
            assertThrows(IllegalArgumentException::class.java) { ManagementPolicy.validatePairing(37123, it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ManagementPolicy.validatePairing(0, "123456") }
    }

    @Test fun cancellingPairingDoesNotTerminateAnAlreadyRunningBackend() {
        assertFalse(ManagementPolicy.shouldStopBackend("pair"))
        assertTrue(ManagementPolicy.shouldStopBackend("start"))
        assertTrue(ManagementPolicy.shouldStopBackend("pair", explicitStop = true))
    }

    @Test fun operationsDoNotCoerceTypesOrAcceptOtherHosts() {
        ManagementPolicy.validateOperation("start", emptyMap())
        ManagementPolicy.validateOperation("start", mapOf("root" to true, "restart" to false))
        ManagementPolicy.validateOperation("pair", mapOf("port" to 12345, "code" to "012345"))
        assertThrows(IllegalArgumentException::class.java) {
            ManagementPolicy.validateOperation("start", mapOf("root" to "true"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagementPolicy.validateOperation("pair", mapOf("port" to 12345, "code" to "123456", "host" to "example.com"))
        }
    }
}
