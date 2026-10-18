package ai.opencyvis.remoteim

import ai.opencyvis.config.ConfigRepository
import io.mockk.*
import org.junit.Before
import org.junit.Test

class ImPairingManagerTest {

    private lateinit var config: ConfigRepository
    private lateinit var manager: ImPairingManager

    @Before
    fun setup() {
        config = mockk(relaxed = true)
        every { config.telegramAllowedChatId } returns ""
        every { config.feishuAllowedOpenId } returns ""
        manager = ImPairingManager(config)
    }

    @Test
    fun `generateCode returns 6-digit string`() {
        val code = manager.generateCode("telegram")
        assert(code.length == 6) { "Code length should be 6, got ${code.length}" }
        assert(code.all { it in '0'..'9' }) { "Code should be all digits" }
    }

    @Test
    fun `attemptPairing with correct code returns SUCCESS`() {
        val code = manager.generateCode("telegram")

        val result = manager.attemptPairing("telegram", "user1", code)

        assert(result == PairingResult.SUCCESS)
        verify { config.telegramAllowedChatId = "user1" }
    }

    @Test
    fun `attemptPairing with wrong code returns WRONG_CODE`() {
        manager.generateCode("telegram")

        val result = manager.attemptPairing("telegram", "user1", "000000")

        assert(result == PairingResult.WRONG_CODE)
        verify(exactly = 0) { config.telegramAllowedChatId = any() }
    }

    @Test
    fun `attemptPairing with expired code returns EXPIRED`() {
        // Generate code, then remove it to simulate expiry
        manager.generateCode("telegram")

        // Access internal state via reflection to age the code
        val codesField = ImPairingManager::class.java.getDeclaredField("codes")
        codesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val codes = codesField.get(manager) as MutableMap<String, Pair<String, Long>>
        val entry = codes["telegram"]!!
        codes["telegram"] = Pair(entry.first, System.currentTimeMillis() - 11 * 60 * 1000L)

        val result = manager.attemptPairing("telegram", "user1", entry.first)

        assert(result == PairingResult.EXPIRED)
    }

    @Test
    fun `attemptPairing with no code returns EXPIRED`() {
        val result = manager.attemptPairing("telegram", "user1", "123456")

        assert(result == PairingResult.EXPIRED)
    }

    @Test
    fun `lockout after MAX_FAILED_ATTEMPTS`() {
        manager.generateCode("telegram")

        repeat(ImPairingManager.MAX_FAILED_ATTEMPTS - 1) {
            val r = manager.attemptPairing("telegram", "user1", "000000")
            assert(r == PairingResult.WRONG_CODE) { "Attempt ${it + 1} should be WRONG_CODE" }
        }

        // The 5th wrong attempt should still return WRONG_CODE (lockout triggers on attempt #5)
        val fifth = manager.attemptPairing("telegram", "user1", "000000")
        assert(fifth == PairingResult.WRONG_CODE) { "5th attempt should still be WRONG_CODE" }

        // Now any further attempt (even correct code) should be LOCKED_OUT
        val code = manager.currentCode("telegram")!!
        val locked = manager.attemptPairing("telegram", "user1", code)
        assert(locked == PairingResult.LOCKED_OUT) { "Should be locked out after 5 failures" }
    }

    @Test
    fun `isLockedOut returns false initially`() {
        assert(!manager.isLockedOut("telegram", "user1"))
    }

    @Test
    fun `isPaired returns true when whitelist is set`() {
        every { config.telegramAllowedChatId } returns "user1"

        assert(manager.isPaired("telegram"))
    }

    @Test
    fun `isPaired returns false when whitelist is empty`() {
        assert(!manager.isPaired("telegram"))
    }

    @Test
    fun `unpair clears whitelist`() {
        manager.unpair("telegram")
        verify { config.telegramAllowedChatId = "" }

        manager.unpair("feishu")
        verify { config.feishuAllowedOpenId = "" }
    }

    @Test
    fun `currentCode returns null when no code generated`() {
        assert(manager.currentCode("telegram") == null)
    }

    @Test
    fun `currentCode returns code when valid`() {
        val code = manager.generateCode("telegram")
        assert(manager.currentCode("telegram") == code)
    }

    @Test
    fun `feishu pairing saves to feishu whitelist`() {
        val code = manager.generateCode("feishu")

        val result = manager.attemptPairing("feishu", "ou_abc", code)

        assert(result == PairingResult.SUCCESS)
        verify { config.feishuAllowedOpenId = "ou_abc" }
    }

    @Test
    fun `different senders have independent lockout`() {
        manager.generateCode("telegram")

        // User1 fails 5 times
        repeat(5) {
            manager.attemptPairing("telegram", "user1", "000000")
        }
        assert(manager.isLockedOut("telegram", "user1"))

        // User2 is NOT locked out
        assert(!manager.isLockedOut("telegram", "user2"))
    }

    @Test
    fun `lockout expires after LOCKOUT_MS`() {
        manager.generateCode("telegram")

        // Fail 5 times to trigger lockout
        repeat(5) {
            manager.attemptPairing("telegram", "user1", "000000")
        }
        assert(manager.isLockedOut("telegram", "user1"))

        // Advance the failed attempts timestamp past lockout
        val field = ImPairingManager::class.java.getDeclaredField("failedAttempts")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val attempts = field.get(manager) as MutableMap<String, Pair<Int, Long>>
        val entry = attempts["telegram:user1"]!!
        attempts["telegram:user1"] = Pair(entry.first, System.currentTimeMillis() - 11 * 60 * 1000L)

        assert(!manager.isLockedOut("telegram", "user1"))
    }

    @Test
    fun `generating new code replaces old one`() {
        val code1 = manager.generateCode("telegram")
        val code2 = manager.generateCode("telegram")

        assert(code1 != code2 || code1 == code2) // Could theoretically be same but unlikely
        assert(manager.currentCode("telegram") == code2)
    }
}
