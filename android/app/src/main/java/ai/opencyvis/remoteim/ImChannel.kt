package ai.opencyvis.remoteim

import kotlinx.coroutines.flow.StateFlow

interface ImChannel {
    val channelId: String
    val isConnected: StateFlow<Boolean>

    suspend fun start()
    suspend fun stop()

    suspend fun sendText(chatId: String, text: String, replyTo: String? = null)
    suspend fun sendPhoto(chatId: String, photoBytes: ByteArray, caption: String?)
    suspend fun sendTyping(chatId: String)

    fun setMessageHandler(handler: suspend (ImInboundMessage) -> Unit)
}

data class ImInboundMessage(
    val channel: String,
    val chatId: String,
    val senderId: String,
    val text: String,
    val chatType: String = "private",
    val rawMessageId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ImOutboundRecord(
    val channel: String,
    val chatId: String,
    val kind: Kind,
    val text: String?,
    val photoBytes: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    enum class Kind { TEXT, PHOTO }
}
