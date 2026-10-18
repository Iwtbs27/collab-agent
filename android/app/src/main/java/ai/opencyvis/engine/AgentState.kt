package ai.opencyvis.engine

sealed class AgentState {
    data class Idle(val resultMessage: String? = null) : AgentState()
    data class Running(val step: Int, val thought: String) : AgentState()
    object Paused : AgentState()
    data class Error(val message: String) : AgentState()
    data class WaitingForUser(val question: String, val step: Int) : AgentState()
    data class WaitingForHandoff(val reason: String, val step: Int) : AgentState()
}
