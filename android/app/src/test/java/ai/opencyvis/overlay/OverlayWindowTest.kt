package ai.opencyvis.overlay

import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OverlayWindow pure logic: state tracking, log formatting,
 * auto-expand/minimize decisions, and detail toggle behavior.
 *
 * Since OverlayWindow depends on Android framework (WindowManager, View),
 * these tests exercise the logic via a testable helper that mirrors the
 * OverlayWindow state machine without needing a real Android context.
 */
class OverlayWindowTest {

    // ============================================================
    // State tracking & auto-expand/minimize logic
    // ============================================================

    @Test
    fun `initial state is Idle with no previous state transition`() {
        val sm = OverlayStateMachine()
        assertEquals(AgentState.Idle(), sm.currentState)
        assertEquals(AgentState.Idle(), sm.previousState)
    }

    @Test
    fun `transition to Running records previous state`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "starting"))
        assertTrue(sm.previousState is AgentState.Idle)
        assertTrue(sm.currentState is AgentState.Running)
    }

    @Test
    fun `transition from Running to Idle triggers auto-expand when minimized`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "working"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Idle())
        assertTrue("Should auto-expand on completion", sm.shouldExpand)
    }

    @Test
    fun `transition from Running to Idle generates completion summary even when expanded`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "working"))
        sm.onStateUpdate(AgentState.Idle())
        assertNotNull("Should generate summary even when expanded", sm.completionSummary)
    }

    @Test
    fun `initial Idle does not trigger auto-expand`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Idle())
        assertFalse("Initial Idle should not auto-expand", sm.shouldExpand)
    }

    @Test
    fun `transition from Paused to Idle does not trigger auto-expand`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Paused)
        sm.onStateUpdate(AgentState.Idle())
        assertFalse("Paused->Idle should not auto-expand", sm.shouldExpand)
    }

    @Test
    fun `Paused state does not auto-expand`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "working"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Paused)
        assertFalse("Paused should not auto-expand (chat head turns amber)", sm.shouldExpand)
    }

    @Test
    fun `Error state does not auto-expand`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "working"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Error("API timeout"))
        assertFalse("Error should not auto-expand (chat head turns red)", sm.shouldExpand)
    }

    @Test
    fun `Error then Idle auto-expands`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "working"))
        sm.onStateUpdate(AgentState.Error("fail"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Idle())
        assertTrue("Error->Idle should auto-expand", sm.shouldExpand)
    }

    @Test
    fun `Running state does not trigger auto-expand`() {
        val sm = OverlayStateMachine()
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Running(1, "step 1"))
        assertFalse("Running should not auto-expand", sm.shouldExpand)
        sm.onStateUpdate(AgentState.Running(2, "step 2"))
        assertFalse("Running should not auto-expand on step update", sm.shouldExpand)
    }

    @Test
    fun `full lifecycle Running to Idle to Running to Idle`() {
        val sm = OverlayStateMachine()

        // First run
        sm.onStateUpdate(AgentState.Running(1, "go"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Idle())
        assertTrue("First completion should auto-expand", sm.shouldExpand)
        sm.isMinimized = false

        // Second run
        sm.onStateUpdate(AgentState.Running(1, "go again"))
        sm.isMinimized = true
        sm.onStateUpdate(AgentState.Idle())
        assertTrue("Second completion should auto-expand", sm.shouldExpand)
    }

    // ============================================================
    // Completion summary
    // ============================================================

    @Test
    fun `completion summary generated when Running to Idle`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Running(1, "go"))
        sm.onStateUpdate(AgentState.Running(5, "last step"))
        sm.onStateUpdate(AgentState.Idle())

        assertNotNull(sm.completionSummary)
        assertTrue("Summary should mention steps",
            sm.completionSummary!!.contains("5 steps"))
    }

    @Test
    fun `no completion summary on initial Idle`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Idle())
        assertNull(sm.completionSummary)
    }

    @Test
    fun `no completion summary on Error to Idle`() {
        val sm = OverlayStateMachine()
        sm.onStateUpdate(AgentState.Error("oops"))
        sm.onStateUpdate(AgentState.Idle())
        assertNull(sm.completionSummary)
    }

    // ============================================================
    // Step progress display
    // ============================================================

    @Test
    fun `running status text includes step and maxSteps`() {
        val maxSteps = 100
        val state = AgentState.Running(3, "thinking")
        val statusText = formatRunningStatus(state, maxSteps)
        assertEquals("Running (3/100)", statusText)
    }

    @Test
    fun `running status text at step 1 of 5`() {
        val statusText = formatRunningStatus(AgentState.Running(1, "start"), 5)
        assertEquals("Running (1/5)", statusText)
    }

    @Test
    fun `running status text at max steps`() {
        val statusText = formatRunningStatus(AgentState.Running(10, "last"), 10)
        assertEquals("Running (10/10)", statusText)
    }

    // ============================================================
    // Status colors
    // ============================================================

    @Test
    fun `status color for Idle is green`() {
        assertEquals(0xFF4CAF50.toInt(), getStatusColor(AgentState.Idle()))
    }

    @Test
    fun `status color for Running is blue`() {
        assertEquals(0xFF2196F3.toInt(), getStatusColor(AgentState.Running(1, "")))
    }

    @Test
    fun `status color for Paused is amber`() {
        assertEquals(0xFFFFC107.toInt(), getStatusColor(AgentState.Paused))
    }

    @Test
    fun `status color for Error is red`() {
        assertEquals(0xFFF44336.toInt(), getStatusColor(AgentState.Error("err")))
    }

    // ============================================================
    // Chat head colors
    // ============================================================

    @Test
    fun `chat head color for Idle is blue`() {
        assertEquals(0xFF1976D2.toInt(), getChatHeadColor(AgentState.Idle()))
    }

    @Test
    fun `chat head color for Running is green`() {
        assertEquals(0xFF4CAF50.toInt(), getChatHeadColor(AgentState.Running(1, "")))
    }

    @Test
    fun `chat head color for Paused is amber`() {
        assertEquals(0xFFFFC107.toInt(), getChatHeadColor(AgentState.Paused))
    }

    @Test
    fun `chat head color for Error is red`() {
        assertEquals(0xFFF44336.toInt(), getChatHeadColor(AgentState.Error("err")))
    }

    // ============================================================
    // Pause/Resume button logic (single listener)
    // ============================================================

    @Test
    fun `pause button action when Running should pause`() {
        val action = getPauseButtonAction(AgentState.Running(1, "working"))
        assertEquals(PauseAction.PAUSE, action)
    }

    @Test
    fun `pause button action when Paused should resume`() {
        val action = getPauseButtonAction(AgentState.Paused)
        assertEquals(PauseAction.RESUME, action)
    }

    @Test
    fun `pause button text when Running is Pause`() {
        assertEquals("Pause", getPauseButtonText(AgentState.Running(1, "")))
    }

    @Test
    fun `pause button text when Paused is Resume`() {
        assertEquals("Resume", getPauseButtonText(AgentState.Paused))
    }

    @Test
    fun `pause button text resets to Pause on Idle`() {
        assertEquals("Pause", getPauseButtonText(AgentState.Idle()))
    }

    @Test
    fun `pause button text resets to Pause on Error`() {
        assertEquals("Pause", getPauseButtonText(AgentState.Error("err")))
    }

    // ============================================================
    // Log entry formatting (7d: duration + failure mark)
    // ============================================================

    @Test
    fun `log entry for successful step includes duration`() {
        val result = StepResult(1, "tap", "tapping", true, "Tapped at (500, 300)", 340L, false)
        val entry = formatLogEntry(result)
        assertEquals("[1] tap: Tapped at (500, 300) (340ms)", entry)
    }

    @Test
    fun `log entry for failed step includes X mark and duration`() {
        val result = StepResult(1, "tap", "tapping", false, "failed", 340L, false)
        val entry = formatLogEntry(result)
        assertEquals("[1] \u2718 tap: failed (340ms)", entry)
    }

    @Test
    fun `log entry truncates long detail to 60 chars`() {
        val longDetail = "A".repeat(100)
        val result = StepResult(2, "swipe", "swiping", true, longDetail, 100L, false)
        val entry = formatLogEntry(result)
        assertTrue("Should contain truncated detail", entry.contains("A".repeat(60)))
        assertFalse("Should not contain full detail", entry.contains("A".repeat(61)))
    }

    @Test
    fun `log entry for zero duration`() {
        val result = StepResult(1, "wait", "waiting", true, "waited", 0L, false)
        val entry = formatLogEntry(result)
        assertEquals("[1] wait: waited (0ms)", entry)
    }

    // ============================================================
    // Log entries buffer (max 5)
    // ============================================================

    @Test
    fun `log buffer caps at 5 entries`() {
        val buffer = LogBuffer(maxEntries = 5)
        for (i in 1..8) {
            buffer.add("[${i}] tap: action $i")
        }
        assertEquals(5, buffer.entries.size)
        assertEquals("[4] tap: action 4", buffer.entries[0])
        assertEquals("[8] tap: action 8", buffer.entries[4])
    }

    @Test
    fun `log buffer returns joined text`() {
        val buffer = LogBuffer(maxEntries = 5)
        buffer.add("line1")
        buffer.add("line2")
        assertEquals("line1\nline2", buffer.text)
    }

    @Test
    fun `empty log buffer returns empty text`() {
        val buffer = LogBuffer(maxEntries = 5)
        assertEquals("", buffer.text)
    }

    // ============================================================
    // Detail toggle state
    // ============================================================

    @Test
    fun `detail section starts collapsed`() {
        val toggle = DetailToggle()
        assertFalse(toggle.isVisible)
        assertEquals("▼", toggle.buttonText)
    }

    @Test
    fun `toggle expands detail section`() {
        val toggle = DetailToggle()
        toggle.toggle()
        assertTrue(toggle.isVisible)
        assertEquals("▲", toggle.buttonText)
    }

    @Test
    fun `toggle twice collapses detail section`() {
        val toggle = DetailToggle()
        toggle.toggle()
        toggle.toggle()
        assertFalse(toggle.isVisible)
        assertEquals("▼", toggle.buttonText)
    }

    // ============================================================
    // Focusable state transitions
    // ============================================================

    @Test
    fun `focusable is true on Idle`() {
        assertTrue(shouldBeFocusable(AgentState.Idle()))
    }

    @Test
    fun `focusable is false on Running`() {
        assertFalse(shouldBeFocusable(AgentState.Running(1, "")))
    }

    @Test
    fun `focusable is true on Error`() {
        assertTrue(shouldBeFocusable(AgentState.Error("err")))
    }

    @Test
    fun `focusable is false on Paused`() {
        assertFalse(shouldBeFocusable(AgentState.Paused))
    }

    // ============================================================
    // Button enabled states
    // ============================================================

    @Test
    fun `Idle state button enablement`() {
        val buttons = getButtonStates(AgentState.Idle())
        assertTrue(buttons.startEnabled)
        assertFalse(buttons.pauseEnabled)
        assertFalse(buttons.stopEnabled)
    }

    @Test
    fun `Running state button enablement`() {
        val buttons = getButtonStates(AgentState.Running(1, ""))
        assertFalse(buttons.startEnabled)
        assertTrue(buttons.pauseEnabled)
        assertTrue(buttons.stopEnabled)
    }

    @Test
    fun `Paused state button enablement`() {
        val buttons = getButtonStates(AgentState.Paused)
        assertFalse(buttons.startEnabled)
        assertTrue(buttons.pauseEnabled)
        assertFalse(buttons.stopEnabled)
    }

    @Test
    fun `Error state button enablement`() {
        val buttons = getButtonStates(AgentState.Error("err"))
        assertTrue(buttons.startEnabled)
        assertFalse(buttons.pauseEnabled)
        assertFalse(buttons.stopEnabled)
    }

    // ============================================================
    // Drag slop logic
    // ============================================================

    @Test
    fun `movement below slop is not a drag`() {
        val slop = 30 // 10dp * ~3 density
        assertFalse(isDrag(5, 5, slop))
        assertFalse(isDrag(0, 0, slop))
        assertFalse(isDrag(20, 20, slop)) // sqrt(800) ~= 28 < 30
    }

    @Test
    fun `movement above slop is a drag`() {
        val slop = 30
        assertTrue(isDrag(25, 25, slop)) // sqrt(1250) ~= 35 > 30
        assertTrue(isDrag(50, 0, slop))
        assertTrue(isDrag(0, 50, slop))
    }

    @Test
    fun `zero slop means any movement is a drag`() {
        val slop = 0
        assertTrue(isDrag(1, 1, slop))
    }

    // ============================================================
    // Helper classes and functions mirroring OverlayWindow logic
    // ============================================================

    enum class PauseAction { PAUSE, RESUME }

    data class ButtonStates(
        val startEnabled: Boolean,
        val pauseEnabled: Boolean,
        val stopEnabled: Boolean
    )

    class OverlayStateMachine {
        var currentState: AgentState = AgentState.Idle()
        var previousState: AgentState = AgentState.Idle()
        var shouldExpand: Boolean = false
        var isMinimized: Boolean = false
        var completionSummary: String? = null

        private var runStartTime: Long = 0L

        fun onStateUpdate(state: AgentState) {
            previousState = currentState
            currentState = state
            shouldExpand = false
            completionSummary = null

            when (state) {
                is AgentState.Idle -> {
                    val wasRunning = previousState is AgentState.Running ||
                            previousState is AgentState.Error || previousState is AgentState.Paused
                    if (previousState is AgentState.Running) {
                        val steps = (previousState as AgentState.Running).step
                        completionSummary = "Completed in $steps steps"
                    }
                    if (wasRunning && isMinimized) shouldExpand = true
                }
                is AgentState.Running -> {
                    if (previousState !is AgentState.Running) {
                        runStartTime = System.currentTimeMillis()
                    }
                }
                is AgentState.Paused -> {
                    // Don't auto-expand — chat head turns amber
                }
                is AgentState.Error -> {
                    // Don't auto-expand — chat head turns red
                }
                is AgentState.WaitingForUser -> {
                    if (isMinimized) shouldExpand = true
                }
                is AgentState.WaitingForHandoff -> {
                    if (isMinimized) shouldExpand = true
                }
            }
        }
    }

    class LogBuffer(private val maxEntries: Int) {
        val entries = mutableListOf<String>()
        val text: String get() = entries.joinToString("\n")

        fun add(entry: String) {
            entries.add(entry)
            if (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
    }

    class DetailToggle {
        var isVisible: Boolean = false
        val buttonText: String get() = if (isVisible) "▲" else "▼"

        fun toggle() {
            isVisible = !isVisible
        }
    }

    private fun formatRunningStatus(state: AgentState.Running, maxSteps: Int): String {
        return "Running (${state.step}/$maxSteps)"
    }

    private fun getStatusColor(state: AgentState): Int = when (state) {
        is AgentState.Idle -> 0xFF4CAF50.toInt()
        is AgentState.Running -> 0xFF2196F3.toInt()
        is AgentState.Paused -> 0xFFFFC107.toInt()
        is AgentState.Error -> 0xFFF44336.toInt()
        is AgentState.WaitingForUser -> 0xFFFF9800.toInt()
        is AgentState.WaitingForHandoff -> 0xFFFF9800.toInt()
    }

    private fun getChatHeadColor(state: AgentState): Int = when (state) {
        is AgentState.Idle -> 0xFF1976D2.toInt()
        is AgentState.Running -> 0xFF4CAF50.toInt()
        is AgentState.Paused -> 0xFFFFC107.toInt()
        is AgentState.Error -> 0xFFF44336.toInt()
        is AgentState.WaitingForUser -> 0xFFFF9800.toInt()
        is AgentState.WaitingForHandoff -> 0xFFFF9800.toInt()
    }

    private fun getPauseButtonAction(state: AgentState): PauseAction {
        return if (state is AgentState.Paused) PauseAction.RESUME else PauseAction.PAUSE
    }

    private fun getPauseButtonText(state: AgentState): String = when (state) {
        is AgentState.Paused -> "Resume"
        else -> "Pause"
    }

    private fun formatLogEntry(result: StepResult): String {
        val failMark = if (!result.success) "\u2718 " else ""
        val duration = "(${result.durationMs}ms)"
        return "[${result.step}] ${failMark}${result.actionType}: ${result.detail.take(60)} $duration"
    }

    private fun shouldBeFocusable(state: AgentState): Boolean = when (state) {
        is AgentState.Idle, is AgentState.Error, is AgentState.WaitingForUser,
        is AgentState.WaitingForHandoff -> true
        is AgentState.Running, is AgentState.Paused -> false
    }

    private fun getButtonStates(state: AgentState): ButtonStates = when (state) {
        is AgentState.Idle -> ButtonStates(startEnabled = true, pauseEnabled = false, stopEnabled = false)
        is AgentState.Running -> ButtonStates(startEnabled = false, pauseEnabled = true, stopEnabled = true)
        is AgentState.Paused -> ButtonStates(startEnabled = false, pauseEnabled = true, stopEnabled = false)
        is AgentState.Error -> ButtonStates(startEnabled = true, pauseEnabled = false, stopEnabled = false)
        is AgentState.WaitingForUser -> ButtonStates(startEnabled = false, pauseEnabled = false, stopEnabled = true)
        is AgentState.WaitingForHandoff -> ButtonStates(startEnabled = false, pauseEnabled = false, stopEnabled = true)
    }

    private fun isDrag(dx: Int, dy: Int, slop: Int): Boolean {
        return dx * dx + dy * dy > slop * slop
    }
}
