package ai.opencyvis.remoteim

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeImChannel(
    override val channelId: String = "fake"
) : ImChannel {

    override val isConnected = MutableStateFlow(false)

    private var messageHandler: (suspend (ImInboundMessage) -> Unit)? = null

    val sentTexts = mutableListOf<Triple<String, String, String?>>()
    val sentPhotos = mutableListOf<Triple<String, ByteArray, String?>>()

    override suspend fun start() {
        isConnected.value = true
    }

    override suspend fun stop() {
        isConnected.value = false
    }

    override suspend fun sendText(chatId: String, text: String, replyTo: String?) {
        sentTexts.add(Triple(chatId, text, replyTo))
    }

    override suspend fun sendPhoto(chatId: String, photoBytes: ByteArray, caption: String?) {
        sentPhotos.add(Triple(chatId, photoBytes, caption))
    }

    override suspend fun sendTyping(chatId: String) {
        // no-op for fake
    }

    override fun setMessageHandler(handler: suspend (ImInboundMessage) -> Unit) {
        messageHandler = handler
    }

    suspend fun injectInbound(msg: ImInboundMessage) {
        messageHandler?.invoke(msg)
    }
}
