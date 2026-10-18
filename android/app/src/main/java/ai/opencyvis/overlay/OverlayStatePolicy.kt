package ai.opencyvis.overlay

import ai.opencyvis.AgentService
import ai.opencyvis.engine.AgentState

object OverlayStatePolicy {
    fun isActive(state: AgentState?, displayState: AgentService.DisplayState): Boolean {
        return state is AgentState.Running ||
                state is AgentState.WaitingForUser ||
                state is AgentState.WaitingForHandoff ||
                (state is AgentState.Paused && displayState == AgentService.DisplayState.TAKEOVER)
    }
}
