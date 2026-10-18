package ai.opencyvis.remoteim

import ai.opencyvis.config.ConfigRepository
import java.security.SecureRandom

/** Result of a pairing attempt. */
enum class PairingResult {
    SUCCESS,
    WRONG_CODE,
    EXPIRED,
    LOCKED_OUT
}

/**
 * Manages 6-digit pairing codes for IM channels (Telegram, Feishu).
 *
 * - Codes are stored in-memory only (not persisted).
 * - Each code expires after [CODE_TTL_MS] (10 minutes).
 * - Brute-force protection: after [MAX_FAILED_ATTEMPTS] wrong guesses
 *   per channelId:senderId, that sender is locked out for [LOCKOUT_MS].
 * - On successful pairing, the sender ID is saved to [ConfigRepository]
 *   so it persists across restarts.
 */
class ImPairingManager(
    private val configRepository: ConfigRepository
) {

    companion object {
        private const val TAG = "ImPairingManager"
        const val CHANNEL_TELEGRAM = "telegram"
        const val CHANNEL_FEISHU = "feishu"
        private const val CODE_LENGTH = 6
        private const val CODE_TTL_MS = 10 * 60 * 1000L       // 10 minutes
        const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_MS = 10 * 60 * 1000L        // 10 minutes
    }

    private val secureRandom = SecureRandom()

    // channelId -> (code, generatedAt)
    private val codes = mutableMapOf<String, Pair<String, Long>>()

    // "channelId:senderId" -> (failedCount, lastFailAt)
    private val failedAttempts = mutableMapOf<String, Pair<Int, Long>>()

    /**
     * Generate a new 6-digit pairing code for the given channel.
     * Replaces any existing code for that channel.
     */
    fun generateCode(channelId: String): String {
        var code: String
        do {
            code = StringBuilder(CODE_LENGTH).apply {
                repeat(CODE_LENGTH) {
                    append('0' + secureRandom.nextInt(10))
                }
            }.toString()
            // Ensure we get a 6-digit number (leading zeros are fine for display)
        } while (code.length != CODE_LENGTH)

        codes[channelId] = Pair(code, System.currentTimeMillis())
        return code
    }

    /**
     * Return the current code for the channel if it has not expired,
     * or null if no code exists or it has expired.
     */
    fun currentCode(channelId: String): String? {
        val entry = codes[channelId] ?: return null
        if (System.currentTimeMillis() - entry.second > CODE_TTL_MS) {
            codes.remove(channelId)
            return null
        }
        return entry.first
    }

    /**
     * Attempt to pair a sender with a channel using the provided code.
     *
     * On [PairingResult.SUCCESS] the sender ID is persisted to the
     * ConfigRepository whitelist for the channel.
     */
    fun attemptPairing(channelId: String, senderId: String, inputCode: String): PairingResult {
        val key = "$channelId:$senderId"

        // Check lockout first
        if (isLockedOut(channelId, senderId)) {
            return PairingResult.LOCKED_OUT
        }

        // Check code exists and is not expired
        val entry = codes[channelId]
        if (entry == null || System.currentTimeMillis() - entry.second > CODE_TTL_MS) {
            codes.remove(channelId)
            return PairingResult.EXPIRED
        }

        // Check code matches
        if (inputCode != entry.first) {
            recordFailure(key)
            return PairingResult.WRONG_CODE
        }

        // Success — clear any failed attempts for this sender
        failedAttempts.remove(key)

        // Persist sender ID to the appropriate whitelist
        saveToWhitelist(channelId, senderId)

        return PairingResult.SUCCESS
    }

    /**
     * Check whether a channel is already paired (whitelist is non-empty).
     */
    fun isPaired(channelId: String): Boolean {
        return when (channelId) {
            CHANNEL_TELEGRAM -> configRepository.telegramAllowedChatId.isNotEmpty()
            CHANNEL_FEISHU -> configRepository.feishuAllowedOpenId.isNotEmpty()
            else -> false
        }
    }

    /**
     * Remove the pairing for a channel (clear the whitelist).
     */
    fun unpair(channelId: String) {
        when (channelId) {
            CHANNEL_TELEGRAM -> configRepository.telegramAllowedChatId = ""
            CHANNEL_FEISHU -> configRepository.feishuAllowedOpenId = ""
        }
    }

    /**
     * Check whether a sender is currently locked out for a channel.
     */
    fun isLockedOut(channelId: String, senderId: String): Boolean {
        val key = "$channelId:$senderId"
        val entry = failedAttempts[key] ?: return false

        if (entry.first < MAX_FAILED_ATTEMPTS) {
            return false
        }

        // Check if lockout period has elapsed
        if (System.currentTimeMillis() - entry.second > LOCKOUT_MS) {
            // Lockout expired — reset
            failedAttempts.remove(key)
            return false
        }

        return true
    }

    // --- Internal helpers ---

    private fun recordFailure(key: String) {
        val current = failedAttempts[key]
        val newCount = (current?.first ?: 0) + 1
        failedAttempts[key] = Pair(newCount, System.currentTimeMillis())
    }

    private fun saveToWhitelist(channelId: String, senderId: String) {
        when (channelId) {
            CHANNEL_TELEGRAM -> configRepository.telegramAllowedChatId = senderId
            CHANNEL_FEISHU -> configRepository.feishuAllowedOpenId = senderId
        }
    }
}
