package ai.opencyvis.fixtures

import ai.opencyvis.llm.LLMClientInterface

class FakeLlmClient : LLMClientInterface {
    private val responseQueue = ArrayDeque<Map<String, Any?>>()
    val requestHistory = mutableListOf<List<Map<String, Any>>>()

    fun enqueue(vararg responses: Map<String, Any?>) {
        responseQueue.addAll(responses)
    }

    override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> {
        requestHistory.add(messages.toList())
        return responseQueue.removeFirstOrNull()
            ?: error("FakeLlmClient: no more responses queued (${requestHistory.size} calls made)")
    }

    override fun shutdown() {}
}
