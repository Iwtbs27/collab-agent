package ai.opencyvis.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LlmPromptsTest {

    @Test
    fun `systemPrompt returns non-empty string`() {
        val prompt = LlmPrompts.systemPrompt()
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("phone_action") || prompt.contains("tap") || prompt.contains("open_app"))
    }

    @Test
    fun `toolDescription returns non-empty string`() {
        val desc = LlmPrompts.toolDescription()
        assertTrue(desc.isNotBlank())
    }

    @Test
    fun `paramDescription returns value for all known keys`() {
        val keys = listOf(
            "thought", "action_type", "x", "y", "app_name", "direction",
            "key", "text", "reason", "question", "handoff_reason",
            "note", "memory_key", "memory_value", "memory_category", "completed"
        )
        for (key in keys) {
            val desc = LlmPrompts.paramDescription(key)
            assertTrue("paramDescription('$key') should not be empty", desc.isNotBlank())
            assertNotEquals("paramDescription('$key') should not return raw key", key, desc)
        }
    }

    @Test
    fun `guardFeedback returns value for all known keys`() {
        val keys = listOf(
            "repeated_type_text", "repeated_submit", "repeated_tap",
            "escalation_high", "escalation_low"
        )
        for (key in keys) {
            val fb = LlmPrompts.guardFeedback(key)
            assertTrue("guardFeedback('$key') should not be empty", fb.isNotBlank())
        }
    }

    @Test
    fun `agentFeedback returns value for all known keys`() {
        val keys = listOf(
            "vd_blank_hint", "handoff_default_reason", "handoff_completed",
            "action_failed", "completed_side_effect", "max_steps_reached",
            "user_answer_prefix", "system_feedback_prefix",
            "ui_elements_header", "user_supplement_header",
            "global_memory_header", "notes_header"
        )
        for (key in keys) {
            val fb = LlmPrompts.agentFeedback(key)
            assertTrue("agentFeedback('$key') should not be empty", fb.isNotBlank())
        }
    }

    @Test
    fun `locale switch produces different system prompts`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val enPrompt = LlmPrompts.systemPrompt()

            Locale.setDefault(Locale.CHINESE)
            val zhPrompt = LlmPrompts.systemPrompt()

            assertNotEquals("EN and ZH system prompts should differ", enPrompt, zhPrompt)
            assertTrue("EN prompt should contain English text", enPrompt.contains("Android phone control assistant"))
            assertTrue("ZH prompt should contain Chinese text", zhPrompt.contains("手机操控助手"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `locale switch produces different tool descriptions`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val enDesc = LlmPrompts.toolDescription()

            Locale.setDefault(Locale.CHINESE)
            val zhDesc = LlmPrompts.toolDescription()

            assertNotEquals("EN and ZH tool descriptions should differ", enDesc, zhDesc)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `locale switch produces different guard feedback`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val enFb = LlmPrompts.guardFeedback("repeated_type_text")

            Locale.setDefault(Locale.CHINESE)
            val zhFb = LlmPrompts.guardFeedback("repeated_type_text")

            assertNotEquals("EN and ZH guard feedback should differ", enFb, zhFb)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `unknown key returns key itself as fallback`() {
        assertEquals("nonexistent_key", LlmPrompts.paramDescription("nonexistent_key"))
        assertEquals("nonexistent_key", LlmPrompts.guardFeedback("nonexistent_key"))
        assertEquals("nonexistent_key", LlmPrompts.agentFeedback("nonexistent_key"))
    }

    @Test
    fun `agentFeedback format strings have correct placeholders`() {
        val handoff = LlmPrompts.agentFeedback("handoff_completed")
        assertTrue("handoff_completed should have %s placeholder", handoff.contains("%s"))

        val actionFailed = LlmPrompts.agentFeedback("action_failed")
        assertTrue("action_failed should have %s placeholder", actionFailed.contains("%s"))

        val maxSteps = LlmPrompts.agentFeedback("max_steps_reached")
        assertTrue("max_steps_reached should have %d placeholder", maxSteps.contains("%d"))

        val sideEffect = LlmPrompts.agentFeedback("completed_side_effect")
        assertTrue("completed_side_effect should have %s placeholder", sideEffect.contains("%s"))
    }

    @Test
    fun `escalation feedback mentions ask_user in both locales`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            assertTrue(LlmPrompts.guardFeedback("escalation_low").contains("ask_user"))
            assertTrue(LlmPrompts.guardFeedback("escalation_high").contains("ask_user"))

            Locale.setDefault(Locale.CHINESE)
            assertTrue(LlmPrompts.guardFeedback("escalation_low").contains("ask_user"))
            assertTrue(LlmPrompts.guardFeedback("escalation_high").contains("ask_user"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
