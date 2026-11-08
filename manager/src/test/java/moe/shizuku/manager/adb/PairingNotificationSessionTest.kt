package moe.shizuku.manager.adb

import org.junit.Assert.*
import org.junit.Test

class PairingNotificationSessionTest {
    @Test fun replyNeedsAnAutomaticallyDiscoveredLocalPort() {
        val session = PairingNotificationSession("session-one")
        assertEquals("searching", session.state)
        assertFalse(session.submit("session-one", 37123, "123456"))
        session.discover(37123)
        assertEquals("code_required", session.state)
        assertTrue(session.submit("session-one", 37123, "012345"))
        assertEquals("pairing", session.state)
    }

    @Test fun anotherDiscoverySessionCannotReuseAnOldNotification() {
        val session = PairingNotificationSession("new-session")
        session.discover(37123)
        assertFalse(session.submit("old-session", 37123, "123456"))
        assertFalse(session.submit(null, 37123, "123456"))
        assertEquals("code_required", session.state)
    }

    @Test fun aLostOrChangedPortInvalidatesThePreviousReply() {
        val session = PairingNotificationSession("session")
        session.discover(37123)
        session.discover(-1)
        assertEquals("searching", session.state)
        assertFalse(session.submit("session", 37123, "123456"))
        session.discover(38001)
        assertFalse(session.submit("session", 37123, "123456"))
        assertTrue(session.submit("session", 38001, "123456"))
    }

    @Test fun duplicateRepliesCannotSubmitTheCodeTwice() {
        val session = PairingNotificationSession("session")
        session.discover(37123)
        assertTrue(session.submit("session", 37123, "123456"))
        session.discover(-1)
        assertEquals("pairing", session.state)
        assertFalse(session.submit("session", 37123, "123456"))
    }

    @Test fun invalidCodesNeverAdvanceToPairing() {
        val session = PairingNotificationSession("session")
        session.discover(37123)
        for (code in listOf("", "12345", "1234567", "12 345", "abcdef", "１２３４５６")) {
            assertThrows(IllegalArgumentException::class.java) { session.submit("session", 37123, code) }
            assertEquals("code_required", session.state)
        }
    }

    @Test fun invalidDiscoveryPortsNeverEnableCodeEntry() {
        val session = PairingNotificationSession("session")
        for (port in listOf(0, -1, 65536)) {
            session.discover(port)
            assertEquals(-1, session.port)
            assertEquals("searching", session.state)
        }
    }
}
