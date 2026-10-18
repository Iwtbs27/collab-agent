package ai.opencyvis.remoteim

import android.util.Log
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ImSessionRouter(
    private val agent: ImAgentBridge,
    private val manager: ImChannelManager,
    private val config: ConfigRepository,
    private val pairingManager: ImPairingManager,
    private val stringProvider: ImStringProvider
) {
    companion object {
        private const val TAG = "ImSessionRouter"
        private const val HINT_THROTTLE_MS = 60 * 60 * 1000L // 1 hour
    }

    private val mutex = Mutex()
    private val lastHintSent = mutableMapOf<String, Long>() // "channel:sender" → timestamp

    suspend fun onInbound(channel: ImChannel, msg: ImInboundMessage) = mutex.withLock {
        val cmd = normalizeCommand(msg.text)
        val isPrivate = msg.chatType == "private" || msg.chatType == "p2p"
        Log.d(TAG, "onInbound: ch=${channel.channelId} sender=${msg.senderId} private=$isPrivate cmd=$cmd")

        // --- Group message handling ---
        if (!isPrivate) {
            if (cmd.startsWith("/")) {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.groupRejected())
            }
            return@withLock
        }

        // --- /pair: always available (no whitelist needed) ---
        if (cmd.startsWith("/pair")) {
            handlePair(channel, msg, cmd)
            return@withLock
        }

        // --- /start: show pairing hint if not paired ---
        if (cmd == "/start") {
            if (!isWhitelisted(channel.channelId, msg.senderId)) {
                sendHintOnce(channel, msg)
            }
            return@withLock
        }

        // --- Everything below requires whitelist ---
        if (!isWhitelisted(channel.channelId, msg.senderId)) {
            Log.w(TAG, "Not whitelisted: ${channel.channelId} sender=${msg.senderId}")
            // Only send hint for first message, throttled per sender
            sendHintOnce(channel, msg)
            return@withLock
        }

        // --- Paired user commands ---
        when {
            cmd == "/status" -> {
                manager.sendText(channel.channelId, msg.chatId, agent.statusText())
            }

            cmd == "/stop" -> {
                agent.stop()
                manager.sendText(channel.channelId, msg.chatId, stringProvider.stopped())
            }

            cmd == "/unpair" -> {
                pairingManager.unpair(channel.channelId)
                manager.sendText(channel.channelId, msg.chatId, stringProvider.unpairSuccess())
                Log.i(TAG, "Unpaired: ${channel.channelId}")
            }

            cmd.startsWith("/") -> {
                // Unknown slash command, ignore
            }

            agent.state.value is AgentState.WaitingForUser -> {
                agent.answerAskUser(msg.text.trim())
            }

            agent.state.value is AgentState.Running -> {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.busy())
            }

            else -> {
                Log.i(TAG, "Starting task: ${msg.text.take(50)}")
                agent.bindActiveSession(channel.channelId, msg.chatId)
                agent.startTask(msg.text.trim())
                if (channel.channelId == "feishu") {
                    config.feishuLastProcessedMsgTime = System.currentTimeMillis()
                }
            }
        }
    }

    private suspend fun handlePair(channel: ImChannel, msg: ImInboundMessage, cmd: String) {
        if (pairingManager.isLockedOut(channel.channelId, msg.senderId)) {
            manager.sendText(channel.channelId, msg.chatId, stringProvider.pairLockedOut())
            return
        }

        if (pairingManager.isPaired(channel.channelId)) {
            manager.sendText(channel.channelId, msg.chatId, stringProvider.alreadyPaired())
            return
        }

        val code = cmd.removePrefix("/pair").trim()
        if (code.isEmpty()) {
            sendHintOnce(channel, msg)
            return
        }

        val result = pairingManager.attemptPairing(channel.channelId, msg.senderId, code)
        when (result) {
            PairingResult.SUCCESS -> {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.pairSuccess())
                Log.i(TAG, "Paired: ${channel.channelId} sender=${msg.senderId}")
            }
            PairingResult.WRONG_CODE -> {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.pairWrongCode())
            }
            PairingResult.EXPIRED -> {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.pairExpired())
            }
            PairingResult.LOCKED_OUT -> {
                manager.sendText(channel.channelId, msg.chatId, stringProvider.pairLockedOut())
            }
        }
    }

    private suspend fun sendHintOnce(channel: ImChannel, msg: ImInboundMessage) {
        val key = "${channel.channelId}:${msg.senderId}"
        val now = System.currentTimeMillis()
        val last = lastHintSent[key] ?: 0
        if (now - last < HINT_THROTTLE_MS) return
        lastHintSent[key] = now
        manager.sendText(channel.channelId, msg.chatId, stringProvider.pairHint())
    }

    private fun isWhitelisted(channelId: String, senderId: String): Boolean {
        return when (channelId) {
            "telegram" -> {
                val allowed = config.telegramAllowedChatId
                allowed.isNotEmpty() && senderId == allowed
            }
            "feishu" -> {
                val allowed = config.feishuAllowedOpenId
                if (allowed.isNotEmpty()) {
                    senderId == allowed
                } else if (config.feishuAppId.isNotBlank() && config.feishuAppSecret.isNotBlank()) {
                    // Bot created via QR registration but no one paired yet — auto-pair first sender
                    config.feishuAllowedOpenId = senderId
                    Log.i(TAG, "Auto-paired feishu sender=$senderId (QR registration)")
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun normalizeCommand(text: String): String {
        val trimmed = text.trim()
        val atIndex = trimmed.indexOf('@')
        return if (atIndex > 0 && trimmed.startsWith("/")) {
            trimmed.substring(0, atIndex)
        } else {
            trimmed
        }
    }
}
