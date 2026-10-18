package ai.opencyvis.remoteim.feishu

import android.util.Log
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.remoteim.ImChannel
import ai.opencyvis.remoteim.ImInboundMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FeishuChannel(
    private val config: ConfigRepository
) : ImChannel {

    companion object {
        private const val TAG = "FeishuChannel"
    }

    override val channelId = "feishu"
    override val isConnected = MutableStateFlow(false)

    private var api: FeishuOpenApi? = null
    private var wsClient: FeishuWsClient? = null
    private var messageHandler: (suspend (ImInboundMessage) -> Unit)? = null

    override suspend fun start() {
        val appId = config.feishuAppId
        val appSecret = config.feishuAppSecret
        if (appId.isBlank() || appSecret.isBlank()) {
            Log.w(TAG, "Feishu credentials not configured")
            return
        }

        api = FeishuOpenApi(appId, appSecret)

        wsClient = FeishuWsClient(
            appId = appId,
            appSecret = appSecret,
            onMessage = { chatId, senderId, text, chatType ->
                messageHandler?.invoke(
                    ImInboundMessage(
                        channel = channelId,
                        chatId = chatId,
                        senderId = senderId,
                        text = text,
                        chatType = chatType
                    )
                )
            }
        )
        wsClient?.connect()
        isConnected.value = true

        catchUpMissedMessages()
    }

    override suspend fun stop() {
        wsClient?.disconnect()
        wsClient = null
        api = null
        isConnected.value = false
    }

    private suspend fun catchUpMissedMessages() {
        val chatId = config.feishuTargetChatId
        val allowedId = config.feishuAllowedOpenId
        val lastProcessed = config.feishuLastProcessedMsgTime
        if (chatId.isBlank() || allowedId.isBlank() || lastProcessed == 0L) return

        try {
            val msg = api?.fetchRecentMessages(chatId, allowedId) ?: return
            if (msg.createTime > lastProcessed) {
                Log.i(TAG, "Catch-up: found missed message (age=${System.currentTimeMillis() - msg.createTime}ms)")
                messageHandler?.invoke(
                    ImInboundMessage(
                        channel = channelId,
                        chatId = msg.chatId,
                        senderId = msg.senderId,
                        text = msg.text,
                        chatType = "p2p"
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Catch-up failed: ${e.message}")
        }
    }

    override suspend fun sendText(chatId: String, text: String, replyTo: String?) {
        val success = api?.sendText(chatId, text) ?: throw IllegalStateException("Not started")
        if (!success) throw RuntimeException("Feishu sendText failed")
    }

    override suspend fun sendPhoto(chatId: String, photoBytes: ByteArray, caption: String?) {
        val success = api?.sendPhoto(chatId, photoBytes, caption ?: "")
            ?: throw IllegalStateException("Not started")
        if (!success) throw RuntimeException("Feishu sendPhoto failed")
    }

    override suspend fun sendTyping(chatId: String) {
        api?.sendTypingStatus(chatId)
    }

    override fun setMessageHandler(handler: suspend (ImInboundMessage) -> Unit) {
        messageHandler = handler
    }
}
