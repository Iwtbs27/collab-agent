package ai.opencyvis.remoteim

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ImChannelManager {

    companion object {
        private const val TAG = "ImChannelManager"
        private const val RING_CAPACITY = 64
    }

    private val mutex = Mutex()
    private val channels = mutableMapOf<String, ImChannel>()
    private val outboundRing = ArrayDeque<ImOutboundRecord>(RING_CAPACITY)
    private var router: ImSessionRouter? = null

    fun setRouter(router: ImSessionRouter) {
        this.router = router
    }

    suspend fun register(channel: ImChannel) = mutex.withLock {
        channel.setMessageHandler { msg -> router?.onInbound(channel, msg) }
        channels[channel.channelId] = channel
        Log.i(TAG, "Registered channel: ${channel.channelId}")
    }

    suspend fun unregister(channelId: String) = mutex.withLock {
        channels.remove(channelId)
    }

    suspend fun startAll() {
        channels.values.forEach { ch ->
            try {
                ch.start()
                Log.i(TAG, "Started channel: ${ch.channelId}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ${ch.channelId}", e)
            }
        }
    }

    suspend fun stopAll() {
        channels.values.forEach { ch ->
            try {
                ch.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ${ch.channelId}", e)
            }
        }
    }

    fun get(channelId: String): ImChannel? = channels[channelId]

    suspend fun injectInbound(channelId: String, senderId: String, chatId: String, text: String) {
        val channel = channels[channelId] ?: run {
            Log.w(TAG, "injectInbound: channel $channelId not found")
            return
        }
        router?.onInbound(
            channel,
            ImInboundMessage(
                channel = channelId,
                chatId = chatId,
                senderId = senderId,
                text = text
            )
        )
    }

    fun recentOutbound(limit: Int = 32): List<ImOutboundRecord> = outboundRing.takeLast(limit)

    suspend fun sendText(channelId: String, chatId: String, text: String) {
        val channel = channels[channelId] ?: throw IllegalStateException("Channel $channelId not found")
        channel.sendText(chatId, text)
        mutex.withLock {
            if (outboundRing.size >= RING_CAPACITY) outboundRing.removeFirst()
            outboundRing.addLast(
                ImOutboundRecord(
                    channel = channelId,
                    chatId = chatId,
                    kind = ImOutboundRecord.Kind.TEXT,
                    text = text
                )
            )
        }
    }

    suspend fun sendPhoto(channelId: String, chatId: String, bytes: ByteArray, caption: String?) {
        val channel = channels[channelId] ?: throw IllegalStateException("Channel $channelId not found")
        channel.sendPhoto(chatId, bytes, caption)
        mutex.withLock {
            if (outboundRing.size >= RING_CAPACITY) outboundRing.removeFirst()
            outboundRing.addLast(
                ImOutboundRecord(
                    channel = channelId,
                    chatId = chatId,
                    kind = ImOutboundRecord.Kind.PHOTO,
                    text = caption,
                    photoBytes = bytes.size
                )
            )
        }
    }

    suspend fun sendTyping(channelId: String, chatId: String) {
        val channel = channels[channelId] ?: return
        try {
            channel.sendTyping(chatId)
        } catch (e: Exception) {
            Log.w(TAG, "sendTyping failed for $channelId: ${e.message}")
        }
    }

    suspend fun replaceWithFake(channelId: String): FakeImChannel {
        val fake = FakeImChannel(channelId)
        register(fake)
        return fake
    }

    suspend fun restoreReal(channelId: String, real: ImChannel) {
        register(real)
    }
}
