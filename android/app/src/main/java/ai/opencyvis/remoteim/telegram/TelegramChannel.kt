package ai.opencyvis.remoteim.telegram

import android.util.Log
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.remoteim.ImChannel
import ai.opencyvis.remoteim.ImInboundMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelegramChannel(
    private val config: ConfigRepository
) : ImChannel {

    companion object {
        private const val TAG = "TelegramChannel"
        private val BACKOFF = longArrayOf(2000, 4000, 8000, 30000)
    }

    override val channelId = "telegram"
    override val isConnected = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var messageHandler: (suspend (ImInboundMessage) -> Unit)? = null
    private var api: TelegramApi? = null

    override suspend fun start() {
        val token = config.telegramBotToken
        if (token.isBlank()) {
            Log.w(TAG, "No bot token configured")
            return
        }
        api = TelegramApi(token, config)
        isConnected.value = true
        startPolling()
    }

    override suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        isConnected.value = false
    }

    override suspend fun sendText(chatId: String, text: String, replyTo: String?) {
        api?.sendMessage(chatId.toLong(), text)
            ?: throw IllegalStateException("TelegramChannel not started")
    }

    override suspend fun sendPhoto(chatId: String, photoBytes: ByteArray, caption: String?) {
        api?.sendPhoto(chatId.toLong(), photoBytes, caption ?: "")
            ?: throw IllegalStateException("TelegramChannel not started")
    }

    override suspend fun sendTyping(chatId: String) {
        api?.sendChatAction(chatId.toLong())
    }

    override fun setMessageHandler(handler: suspend (ImInboundMessage) -> Unit) {
        messageHandler = handler
    }

    private fun startPolling() {
        pollJob = scope.launch {
            var backoffIndex = 0
            Log.i(TAG, "Polling started")
            while (isActive) {
                try {
                    // Stale poll detection
                    if (api?.checkStale() == true) {
                        Log.w(TAG, "Stale poll detected, reconnecting")
                        api?.resetStale()
                    }

                    val updates = api?.getUpdates(30) ?: emptyList()
                    backoffIndex = 0 // Success, reset backoff

                    for (update in updates) {
                        Log.d(TAG, "Inbound: sender=${update.senderId} chat=${update.chatId} type=${update.chatType} text=${update.text.take(50)}")
                        messageHandler?.invoke(
                            ImInboundMessage(
                                channel = channelId,
                                chatId = update.chatId.toString(),
                                senderId = update.senderId.toString(),
                                text = update.text,
                                chatType = update.chatType
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error, backoff ${BACKOFF[backoffIndex]}ms", e)
                    delay(BACKOFF[backoffIndex])
                    if (backoffIndex < BACKOFF.size - 1) backoffIndex++
                }
            }
        }
    }
}
