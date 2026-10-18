package ai.opencyvis.overlay

import ai.opencyvis.AgentService
import ai.opencyvis.engine.AgentState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStatePolicyTest {

    @Test
    fun `running and waiting states keep desktop overlay active`() {
        assertTrue(
            OverlayStatePolicy.isActive(
                AgentState.Running(1, "working"),
                AgentService.DisplayState.CHAT
            )
        )
        assertTrue(
            OverlayStatePolicy.isActive(
                AgentState.WaitingForUser("confirm?", 1),
                AgentService.DisplayState.VIEW
            )
        )
        assertTrue(
            OverlayStatePolicy.isActive(
                AgentState.WaitingForHandoff("password", 2),
                AgentService.DisplayState.TAKEOVER
            )
        )
    }

    @Test
    fun `paused is active only while display state is takeover`() {
        assertTrue(
            OverlayStatePolicy.isActive(
                AgentState.Paused,
                AgentService.DisplayState.TAKEOVER
            )
        )
        assertFalse(
            OverlayStatePolicy.isActive(
                AgentState.Paused,
                AgentService.DisplayState.VIEW
            )
        )
        assertFalse(
            OverlayStatePolicy.isActive(
                AgentState.Paused,
                AgentService.DisplayState.CHAT
            )
        )
    }

    @Test
    fun `idle error and null states hide desktop overlay`() {
        assertFalse(OverlayStatePolicy.isActive(AgentState.Idle(), AgentService.DisplayState.CHAT))
        assertFalse(OverlayStatePolicy.isActive(AgentState.Error("boom"), AgentService.DisplayState.VIEW))
        assertFalse(OverlayStatePolicy.isActive(null, AgentService.DisplayState.TAKEOVER))
    }
}
