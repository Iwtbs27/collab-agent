package ai.opencyvis.ui

data class ChatMessage(
    val type: MessageType,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class MessageType {
    USER_INPUT,
    AGENT_STATUS,
    AGENT_DEBUG,
    AGENT_QUESTION,
    USER_ANSWER,
    USER_SUPPLEMENT,
    AGENT_RESULT,
    AGENT_CYCLE,
    SYSTEM
}
