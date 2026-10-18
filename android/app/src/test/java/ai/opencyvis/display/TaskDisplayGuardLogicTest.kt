package ai.opencyvis.display

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the TaskDisplayGuard logic (launch tracking, cooldown, launcher skip).
 *
 * These test the decision logic in isolation. Full integration tests
 * (with VD + on-device) are in tests/ee/.
 */
class TaskDisplayGuardLogicTest {

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun task(
        taskId: Int = 42,
        displayId: Int = Display.DEFAULT_DISPLAY,
        basePackage: String = "com.example.target",
        topPackage: String = "com.example.target"
    ) = TaskSnapshot(taskId, displayId, basePackage, topPackage, System.currentTimeMillis())

    // ── Launch tracking logic ────────────────────────────────────────────

    @Test
    fun `pending launch package is recognized`() {
        val pendingPackages = mutableSetOf("com.example.target")
        val deadline = System.currentTimeMillis() + 3000

        val task = task(topPackage = "com.example.target")
        val isPending = task.topPackage in pendingPackages &&
                System.currentTimeMillis() < deadline

        assertTrue(isPending)
    }

    @Test
    fun `expired launch tracking is not recognized`() {
        val pendingPackages = mutableSetOf("com.example.target")
        val deadline = System.currentTimeMillis() - 1 // already expired

        val task = task(topPackage = "com.example.target")
        val isPending = task.topPackage in pendingPackages &&
                System.currentTimeMillis() < deadline

        assertFalse(isPending)
    }

    @Test
    fun `unrelated package is not recognized as pending`() {
        val pendingPackages = mutableSetOf("com.example.target")
        val deadline = System.currentTimeMillis() + 3000

        val task = task(topPackage = "com.other.app")
        val isPending = task.topPackage in pendingPackages &&
                System.currentTimeMillis() < deadline

        assertFalse(isPending)
    }

    // ── Cooldown logic ───────────────────────────────────────────────────

    @Test
    fun `cooldown prevents duplicate rescue within window`() {
        val recentRescues = mutableMapOf<Int, Long>()
        val cooldownMs = 5000L
        val taskId = 42

        // First rescue
        val now1 = System.currentTimeMillis()
        recentRescues[taskId] = now1

        // Second attempt within cooldown
        val now2 = now1 + 2000 // 2s later
        val lastRescue = recentRescues[taskId] ?: 0
        val cooledDown = now2 - lastRescue >= cooldownMs

        assertFalse(cooledDown) // should be blocked
    }

    @Test
    fun `cooldown allows rescue after window expires`() {
        val recentRescues = mutableMapOf<Int, Long>()
        val cooldownMs = 5000L
        val taskId = 42

        val now1 = System.currentTimeMillis()
        recentRescues[taskId] = now1

        val now2 = now1 + 6000 // 6s later
        val lastRescue = recentRescues[taskId] ?: 0
        val cooledDown = now2 - lastRescue >= cooldownMs

        assertTrue(cooledDown) // should be allowed
    }

    @Test
    fun `cooldown is per task id`() {
        val recentRescues = mutableMapOf<Int, Long>()
        val cooldownMs = 5000L
        val now = System.currentTimeMillis()

        recentRescues[1] = now // task 1 rescued

        val task2LastRescue = recentRescues[2] ?: 0
        val cooledDown = now - task2LastRescue >= cooldownMs

        assertTrue(cooledDown) // task 2 should be allowed
    }

    // ── Decision priority ────────────────────────────────────────────────

    /**
     * Simulates the decision tree in maybeDispatchEscape:
     * 1. Pending launch → silent reparent
     * 2. Launcher on top → skip
     * 3. Cooldown → skip
     * 4. Otherwise → dispatch escape
     */
    private enum class Decision {
        SILENT_REPARENT, SKIP_LAUNCHER, SKIP_COOLDOWN, DISPATCH_ESCAPE
    }

    private fun decide(
        task: TaskSnapshot,
        pendingPackages: Set<String>,
        pendingDeadline: Long,
        launcherTop: Boolean,
        recentRescues: Map<Int, Long>,
        cooldownMs: Long,
        now: Long
    ): Decision {
        // Path 1: pending launch
        val isPending = task.topPackage in pendingPackages && now < pendingDeadline
        if (isPending) return Decision.SILENT_REPARENT

        // Path 2: launcher on top
        if (launcherTop) return Decision.SKIP_LAUNCHER

        // Path 3: cooldown
        val lastRescue = recentRescues[task.taskId] ?: 0
        if (now - lastRescue < cooldownMs) return Decision.SKIP_COOLDOWN

        // Path 4: genuine escape
        return Decision.DISPATCH_ESCAPE
    }

    @Test
    fun `decision - pending launch takes priority over everything`() {
        val result = decide(
            task = task(topPackage = "com.example.target"),
            pendingPackages = setOf("com.example.target"),
            pendingDeadline = 5000,
            launcherTop = true, // even with launcher on top
            recentRescues = mapOf(42 to 0), // even with recent rescue
            cooldownMs = 5000,
            now = 1000
        )
        assertEquals(Decision.SILENT_REPARENT, result)
    }

    @Test
    fun `decision - launcher on top skips when no pending launch`() {
        val result = decide(
            task = task(),
            pendingPackages = emptySet(),
            pendingDeadline = 0,
            launcherTop = true,
            recentRescues = emptyMap(),
            cooldownMs = 5000,
            now = 1000
        )
        assertEquals(Decision.SKIP_LAUNCHER, result)
    }

    @Test
    fun `decision - cooldown blocks after recent rescue`() {
        val result = decide(
            task = task(taskId = 42),
            pendingPackages = emptySet(),
            pendingDeadline = 0,
            launcherTop = false,
            recentRescues = mapOf(42 to 500),
            cooldownMs = 5000,
            now = 3000 // 2.5s after last rescue
        )
        assertEquals(Decision.SKIP_COOLDOWN, result)
    }

    @Test
    fun `decision - genuine escape dispatched when all clear`() {
        val result = decide(
            task = task(),
            pendingPackages = emptySet(),
            pendingDeadline = 0,
            launcherTop = false,
            recentRescues = emptyMap(),
            cooldownMs = 5000,
            now = 6000 // after default lastRescue=0 cooldown
        )
        assertEquals(Decision.DISPATCH_ESCAPE, result)
    }

    @Test
    fun `decision - expired cooldown allows dispatch`() {
        val result = decide(
            task = task(taskId = 42),
            pendingPackages = emptySet(),
            pendingDeadline = 0,
            launcherTop = false,
            recentRescues = mapOf(42 to 100),
            cooldownMs = 5000,
            now = 7000 // 6s after last rescue
        )
        assertEquals(Decision.DISPATCH_ESCAPE, result)
    }

    // ── Policy tests (shouldRescue) ──────────────────────────────────────

    @Test
    fun `policy - controlled task on VD should not be rescued`() {
        val task = task(displayId = 7)
        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(task, setOf(42), emptySet())
        )
    }

    @Test
    fun `policy - controlled task on Display 0 should be rescued`() {
        val task = task(displayId = Display.DEFAULT_DISPLAY)
        assertTrue(
            TaskDisplayGuardPolicy.shouldRescue(task, setOf(42), emptySet())
        )
    }

    @Test
    fun `policy - uncontrolled task on Display 0 should not be rescued`() {
        val task = task(taskId = 99, displayId = Display.DEFAULT_DISPLAY)
        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(task, setOf(42), emptySet())
        )
    }

    @Test
    fun `policy - untracked package task on Display 0 should not be rescued`() {
        // Package-based rescue was removed to avoid rescuing stale tasks.
        // Only explicitly tracked taskIds are rescued.
        val task = task(taskId = 99, displayId = Display.DEFAULT_DISPLAY, topPackage = "com.example.target.Activity")
        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(task, emptySet(), setOf("com.example.target"))
        )
    }
}
