package ai.opencyvis.config

import org.junit.Assert.*
import org.junit.Test

class ConfigDeepLinkProfileTest {

    @Test
    fun `parse extracts profile name`() {
        val params = mapOf(
            "provider" to "anthropic",
            "api_key" to "sk-test",
            "model" to "claude-sonnet-4-7-20250415",
            "base_url" to "https://api.anthropic.com",
            "profile" to "my-work-profile"
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertEquals("my-work-profile", result.profile)
    }

    @Test
    fun `parse returns null profile when absent`() {
        val params = mapOf(
            "provider" to "openai",
            "api_key" to "sk-test",
            "model" to "gpt-5.5",
            "base_url" to "https://api.openai.com/v1"
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertNull(result.profile)
    }

    @Test
    fun `parse returns null profile when empty`() {
        val params = mapOf(
            "provider" to "openai",
            "api_key" to "sk-test",
            "profile" to ""
        )
        val result = ConfigDeepLink.parse(params).getOrThrow()
        assertNull(result.profile)
    }

    @Test
    fun `buildUri includes profile param`() {
        val uri = ConfigDeepLink.buildUri("openai", "key", "model", "url", 100, "my-profile")
        assertTrue(uri.contains("profile=my-profile"))
    }

    @Test
    fun `buildUri omits profile when null`() {
        val uri = ConfigDeepLink.buildUri("openai", "key", "model", "url", 100, null)
        assertFalse(uri.contains("profile"))
    }

    @Test
    fun `buildUri backward compatible without profile`() {
        val uri = ConfigDeepLink.buildUri("openai", "key", "model", "url", 100)
        assertFalse(uri.contains("profile"))
        assertTrue(uri.startsWith("opencyvis://config?"))
    }
}
