package ai.opencyvis.ui

class ChatMessageList {

    private val messages = mutableListOf<ChatMessage>()

    data class Change(val type: ChangeType, val index: Int)
    enum class ChangeType { INSERTED, CHANGED, REMOVED, RANGE_REMOVED }

    fun get(index: Int): ChatMessage = messages[index]
    val size: Int get() = messages.size

    fun addMessage(message: ChatMessage): Change {
        messages.add(message)
        return Change(ChangeType.INSERTED, messages.size - 1)
    }

    fun startCycle(): Change? {
        val idx = messages.indexOfLast { it.type == MessageType.AGENT_CYCLE }
        if (idx < 0) {
            return addMessage(ChatMessage(MessageType.AGENT_CYCLE, ""))
        }
        return null
    }

    fun updateCycleText(text: String): Change? {
        val idx = messages.indexOfLast { it.type == MessageType.AGENT_CYCLE }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(text = text)
            return Change(ChangeType.CHANGED, idx)
        }
        return null
    }

    fun removeCycle(): Change? {
        val idx = messages.indexOfLast { it.type == MessageType.AGENT_CYCLE }
        if (idx >= 0) {
            messages.removeAt(idx)
            return Change(ChangeType.REMOVED, idx)
        }
        return null
    }

    fun hasCycle(): Boolean = messages.any { it.type == MessageType.AGENT_CYCLE }

    fun convertCycleToResult(summary: String): Change {
        val idx = messages.indexOfLast { it.type == MessageType.AGENT_CYCLE }
        if (idx >= 0) {
            messages[idx] = ChatMessage(MessageType.AGENT_RESULT, summary, messages[idx].timestamp)
            return Change(ChangeType.CHANGED, idx)
        }
        return addMessage(ChatMessage(MessageType.AGENT_RESULT, summary))
    }

    fun updateLastAgentStatus(text: String): Change {
        val idx = messages.indexOfLast { it.type == MessageType.AGENT_STATUS }
        if (idx >= 0 && idx == messages.size - 1) {
            messages[idx] = messages[idx].copy(text = text)
            return Change(ChangeType.CHANGED, idx)
        }
        return addMessage(ChatMessage(MessageType.AGENT_STATUS, text))
    }

    fun clear(): Change {
        val size = messages.size
        messages.clear()
        return Change(ChangeType.RANGE_REMOVED, size)
    }
}
