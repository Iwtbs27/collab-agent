package ai.opencyvis.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [routeReply] — the ACTION_REPLY routing decision in
 * AdbPairingService. The key case is DISCOVER: when a code is submitted but no
 * pairing port is known yet (because aggressive OEM power management froze our
 * process before mDNS could discover it), we must fall back to discovering the
 * port on submit rather than dropping the input.
 */
class AdbPairingReplyRouteTest {

    @Test
    fun `blank code routes to EMPTY`() {
        assertEquals(ReplyRoute.EMPTY, routeReply("", 12345))
        assertEquals(ReplyRoute.EMPTY, routeReply("   ", 12345))
        assertEquals(ReplyRoute.EMPTY, routeReply("", -1))
    }

    @Test
    fun `code with known port routes to DIRECT`() {
        assertEquals(ReplyRoute.DIRECT, routeReply("123456", 39503))
        assertEquals(ReplyRoute.DIRECT, routeReply("000000", 1))
    }

    @Test
    fun `code without known port routes to DISCOVER`() {
        // Placeholder port (-1) from the guidance notifications shown before
        // the resident mDNS search has found anything — the freeze-resilient path.
        assertEquals(ReplyRoute.DISCOVER, routeReply("123456", -1))
        // Port 0 is the "not yet discovered" sentinel from pairingPort flow.
        assertEquals(ReplyRoute.DISCOVER, routeReply("123456", 0))
    }

    @Test
    fun `blank code takes precedence over known port`() {
        // Even with a valid port, an empty submit should not attempt pairing.
        assertEquals(ReplyRoute.EMPTY, routeReply("  ", 39503))
    }
}
