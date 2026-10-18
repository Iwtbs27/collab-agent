package ai.opencyvis.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDeepLinkTest {

    @Test
    fun `valid OpenAI config imports all fields`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "openai",
                "api_key" to "sk-test-1234",
                "base_url" to "https://example.com/v1",
                "model" to "custom-model",
                "max_steps" to "12"
            )
        )

        val config = result.getOrThrow()
        assertEquals(ConfigRepository.PROVIDER_OPENAI, config.provider)
        assertEquals("sk-test-1234", config.apiKey)
        assertEquals("https://example.com/v1", config.baseUrl)
        assertEquals("custom-model", config.model)
        assertEquals(12, config.maxSteps)
    }

    @Test
    fun `Anthropic config defaults missing model and base URL`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "anthropic",
                "api_key" to "anthropic-key"
            )
        )

        val config = result.getOrThrow()
        assertEquals(ConfigRepository.PROVIDER_ANTHROPIC, config.provider)
        assertEquals(ConfigRepository.DEFAULT_ANTHROPIC_MODEL, config.model)
        assertEquals(ConfigRepository.DEFAULT_ANTHROPIC_BASE_URL, config.baseUrl)
    }

    @Test
    fun `Ollama accepts missing API key`() {
        val result = ConfigDeepLink.parse(mapOf("provider" to "ollama"))

        val config = result.getOrThrow()
        assertEquals(ConfigRepository.PROVIDER_OLLAMA, config.provider)
        assertEquals("", config.apiKey)
        assertEquals(ConfigRepository.DEFAULT_OLLAMA_MODEL, config.model)
        assertEquals(ConfigRepository.DEFAULT_OLLAMA_BASE_URL, config.baseUrl)
    }

    @Test
    fun `invalid provider is rejected`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "unknown",
                "api_key" to "sk-test"
            )
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `non-Ollama missing API key is rejected`() {
        val result = ConfigDeepLink.parse(mapOf("provider" to "openai"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `zero max steps is rejected`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "openai",
                "api_key" to "sk-test",
                "max_steps" to "0"
            )
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `large max steps is accepted`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "openai",
                "api_key" to "sk-test",
                "max_steps" to "1000"
            )
        )

        assertEquals(1000, result.getOrThrow().maxSteps)
    }

    @Test
    fun `key and url aliases are accepted`() {
        val result = ConfigDeepLink.parse(
            mapOf(
                "provider" to "openai",
                "key" to "sk-alias",
                "url" to "https://alias.example/v1"
            )
        )

        val config = result.getOrThrow()
        assertEquals("sk-alias", config.apiKey)
        assertEquals("https://alias.example/v1", config.baseUrl)
    }

    @Test
    fun `redacted API key does not expose full value`() {
        val redacted = ConfigDeepLink.redactedApiKey("sk-secret-value-1234")

        assertEquals("sk-...1234", redacted)
    }
}
