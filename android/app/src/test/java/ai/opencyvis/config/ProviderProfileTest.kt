package ai.opencyvis.config

import org.junit.Assert.*
import org.junit.Test

class ProviderProfileTest {

    @Test
    fun `json round-trip preserves all fields`() {
        val profile = ProviderProfile(
            name = "test-profile",
            provider = "anthropic",
            apiKey = "sk-test-key-123",
            model = "claude-sonnet-4-7-20250415",
            baseUrl = "https://api.anthropic.com"
        )
        val json = profile.toJson()
        val restored = ProviderProfile.fromJson(json)
        assertEquals(profile, restored)
    }

    @Test
    fun `json array round-trip preserves list`() {
        val profiles = listOf(
            ProviderProfile("p1", "openai", "key1", "gpt-5.5", "https://api.openai.com/v1"),
            ProviderProfile("p2", "ollama", "", "gemma4", "https://localhost:11434")
        )
        val jsonStr = ProviderProfile.toJsonArray(profiles)
        val restored = ProviderProfile.fromJsonArray(jsonStr)
        assertEquals(profiles, restored)
    }

    @Test
    fun `fromJsonArray handles empty string`() {
        assertEquals(emptyList<ProviderProfile>(), ProviderProfile.fromJsonArray(""))
    }

    @Test
    fun `fromJsonArray handles malformed json`() {
        assertEquals(emptyList<ProviderProfile>(), ProviderProfile.fromJsonArray("{broken"))
    }

    @Test
    fun `fromJson fills defaults for missing optional fields`() {
        val json = org.json.JSONObject().apply { put("name", "minimal") }
        val profile = ProviderProfile.fromJson(json)
        assertEquals("minimal", profile.name)
        assertEquals(ConfigRepository.PROVIDER_OPENAI, profile.provider)
        assertEquals("", profile.apiKey)
        assertEquals(ConfigRepository.DEFAULT_MODEL, profile.model)
        assertEquals(ConfigRepository.DEFAULT_BASE_URL, profile.baseUrl)
    }
}
