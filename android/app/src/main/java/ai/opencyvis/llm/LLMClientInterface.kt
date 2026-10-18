package ai.opencyvis.llm

interface LLMClientInterface {
    suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?>
    fun shutdown()
}
