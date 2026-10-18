package ai.opencyvis.ui

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for ChatAdapter / ChatMessageList behavior under rapid updateCycleText calls.
 *
 * Bug context: When the agent is running and the user rapidly types in the supplement
 * input, updateCycleText is called on every step result. Each call triggers
 * notifyItemChanged on the RecyclerView, which causes the CycleViewHolder to re-bind
 * and call TypewriterTextView.animateText(). If the text hasn't actually changed,
 * this produces redundant RecyclerView updates and redundant animation restarts.
 *
 * These tests verify the data layer behavior that feeds into the ANR scenario.
 */
class ChatAdapterTest {

    private lateinit var list: ChatMessageList

    @Before
    fun setUp() {
        list = ChatMessageList()
    }

    // ── updateCycleText with same text ─────────────────────────────────────

    @Test
    fun `updateCycleText with identical text still returns CHANGED`() {
        // Current behavior: updateCycleText always returns CHANGED even for same text.
        // This documents the current behavior — a fix could make it return null for
        // identical text to avoid redundant notifyItemChanged calls.
        list.startCycle()
        list.updateCycleText("Thinking...")

        val change = list.updateCycleText("Thinking...")
        // The data layer currently does not deduplicate — this test documents that.
        // After the fix, this should return null (no change needed).
        assertNotNull(
            "updateCycleText with same text should return a Change (current behavior) — " +
                "a fix should make this return null to prevent redundant RecyclerView notifications",
            change
        )
        if (change != null) {
            assertEquals(ChatMessageList.ChangeType.CHANGED, change.type)
        }
    }

    @Test
    fun `updateCycleText with different text returns CHANGED`() {
        list.startCycle()
        list.updateCycleText("Step 1")

        val change = list.updateCycleText("Step 2")
        assertNotNull(change)
        assertEquals(ChatMessageList.ChangeType.CHANGED, change!!.type)
        assertEquals("Step 2", list.get(0).text)
    }

    // ── Rapid updateCycleText simulating fast step results ─────────────────

    @Test
    fun `rapid updateCycleText calls all produce changes for list size 1`() {
        // Simulates the scenario where agent steps arrive rapidly.
        // Each updateCycleText modifies the single cycle message in-place.
        list.startCycle()

        val texts = (1..50).map { "Working on step $it..." }
        var changeCount = 0

        for (text in texts) {
            val change = list.updateCycleText(text)
            if (change != null) changeCount++
        }

        // All 50 updates should produce changes (different text each time)
        assertEquals(50, changeCount)
        // But there should still be only 1 message in the list
        assertEquals(1, list.size)
        assertEquals("Working on step 50...", list.get(0).text)
    }

    @Test
    fun `repeated identical updateCycleText should not grow the list`() {
        // Simulates the degenerate case where the same thought text is reported
        // multiple times (e.g. agent stalling on one step).
        list.startCycle()

        repeat(100) {
            list.updateCycleText("Still thinking...")
        }

        assertEquals(1, list.size)
        assertEquals("Still thinking...", list.get(0).text)
    }

    // ── Cycle lifecycle under rapid updates ────────────────────────────────

    @Test
    fun `startCycle is idempotent even during rapid updates`() {
        list.startCycle()
        list.updateCycleText("Step 1")

        // Calling startCycle again should be a no-op
        val secondStart = list.startCycle()
        assertNull("startCycle should be no-op when cycle exists", secondStart)
        assertEquals(1, list.size)
        assertEquals("Step 1", list.get(0).text)
    }

    @Test
    fun `convertCycleToResult during rapid updates preserves only final text`() {
        list.startCycle()

        // Rapid updates
        list.updateCycleText("Step 1")
        list.updateCycleText("Step 2")
        list.updateCycleText("Step 3")

        // Then task completes
        val change = list.convertCycleToResult("Task complete")
        assertEquals(ChatMessageList.ChangeType.CHANGED, change.type)
        assertEquals(1, list.size)
        assertEquals(MessageType.AGENT_RESULT, list.get(0).type)
        assertEquals("Task complete", list.get(0).text)
        assertFalse(list.hasCycle())
    }

    // ── Supplement messages interleaved with cycle updates ──────────────────

    @Test
    fun `supplement messages interleaved with cycle updates keep correct order`() {
        // Simulates the ANR scenario: user sends supplements while agent is cycling
        list.addMessage(ChatMessage(MessageType.USER_INPUT, "Open settings"))
        list.startCycle()
        list.updateCycleText("Opening...")

        // User sends a supplement
        list.addMessage(ChatMessage(MessageType.USER_SUPPLEMENT, "💬 Also check wifi"))

        // Agent continues cycling — cycle message should still be updatable
        list.updateCycleText("Checking wifi...")

        // Verify order: USER_INPUT, AGENT_CYCLE, USER_SUPPLEMENT
        assertEquals(3, list.size)
        assertEquals(MessageType.USER_INPUT, list.get(0).type)
        assertEquals(MessageType.AGENT_CYCLE, list.get(1).type)
        assertEquals(MessageType.USER_SUPPLEMENT, list.get(2).type)
        // Cycle text was updated
        assertEquals("Checking wifi...", list.get(1).text)
    }

    @Test
    fun `multiple rapid supplements do not interfere with cycle`() {
        list.startCycle()
        list.updateCycleText("Working...")

        // Simulate rapid supplement input (user typing fast)
        repeat(5) { i ->
            list.addMessage(ChatMessage(MessageType.USER_SUPPLEMENT, "💬 Info $i"))
        }

        // Cycle still exists and is updateable
        assertTrue(list.hasCycle())
        val change = list.updateCycleText("Still working...")
        assertNotNull(change)
        assertEquals("Still working...", list.get(0).text)

        // Total: 1 cycle + 5 supplements
        assertEquals(6, list.size)
    }
}
