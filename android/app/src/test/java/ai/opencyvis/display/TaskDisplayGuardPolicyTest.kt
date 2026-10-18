package ai.opencyvis.display

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDisplayGuardPolicyTest {

    @Test
    fun `rescues controlled task id on default display`() {
        val task = TaskSnapshot(
            taskId = 42,
            displayId = Display.DEFAULT_DISPLAY,
            basePackage = "com.example.target",
            topPackage = "com.example.target",
            lastActiveTime = 10L
        )

        assertTrue(
            TaskDisplayGuardPolicy.shouldRescue(
                task,
                controlledTaskIds = setOf(42),
                controlledPackages = emptySet()
            )
        )
    }

    @Test
    fun `does not rescue untracked task even if package matches`() {
        // Package-based rescue was removed — only explicitly tracked taskIds are rescued.
        val task = TaskSnapshot(
            taskId = 99,
            displayId = Display.DEFAULT_DISPLAY,
            basePackage = "com.example.target",
            topPackage = "com.example.target.pay",
            lastActiveTime = 10L
        )

        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(
                task,
                controlledTaskIds = setOf(42),
                controlledPackages = setOf("com.example.target")
            )
        )
    }

    @Test
    fun `does not rescue controlled task that remains on virtual display`() {
        val task = TaskSnapshot(
            taskId = 42,
            displayId = 7,
            basePackage = "com.example.target",
            topPackage = "com.example.target",
            lastActiveTime = 10L
        )

        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(
                task,
                controlledTaskIds = setOf(42),
                controlledPackages = setOf("com.example.target")
            )
        )
    }

    @Test
    fun `does not rescue unrelated task on default display`() {
        val task = TaskSnapshot(
            taskId = 100,
            displayId = Display.DEFAULT_DISPLAY,
            basePackage = "com.other",
            topPackage = "com.other",
            lastActiveTime = 10L
        )

        assertFalse(
            TaskDisplayGuardPolicy.shouldRescue(
                task,
                controlledTaskIds = setOf(42),
                controlledPackages = setOf("com.example.target")
            )
        )
    }
}
