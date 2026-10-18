package ai.opencyvis.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for TypewriterTextView animation logic relevant to ANR prevention.
 *
 * Bug: Rapid calls to animateText() with the same text restart the animation
 * each time, flooding the main Handler with runnables every 30ms and contributing
 * to ANR when the user types rapidly in the supplement input.
 *
 * These tests verify source-level invariants (similar to AgentServiceStopOrderTest)
 * because TypewriterTextView depends on Android Handler which is not available in
 * plain JUnit. The tests ensure the fix (duplicate-text guard) is present and correct.
 */
class TypewriterTextViewTest {

    private val sourceFile = File("src/main/java/ai/opencyvis/ui/TypewriterTextView.kt")

    @Test
    fun `animateText must skip re-animation when called with same text`() {
        assertTrue(
            "TypewriterTextView.kt source file not found at ${sourceFile.absolutePath}",
            sourceFile.exists()
        )

        val source = sourceFile.readText()

        // Find the animateText method
        val methodStart = source.indexOf("fun animateText(")
        assertTrue("animateText() method not found in TypewriterTextView.kt", methodStart >= 0)

        val methodBody = extractMethodBody(source, methodStart)
        assertNotNull("Could not extract animateText() method body", methodBody)

        // The fix should include a guard that checks if the new text equals the current
        // fullText and the animation is already running (charIndex > 0 or similar).
        // Look for an early-return guard comparing newText to fullText.
        val hasGuard = methodBody!!.contains("fullText") &&
            (methodBody.contains("return") || methodBody.contains("== newText") || methodBody.contains("newText =="))

        // Even if the exact guard differs, there should be some condition that avoids
        // restarting for identical text. Verify removeCallbacksAndMessages is not
        // unconditionally the first statement — a guard must come before it.
        val removeIndex = methodBody.indexOf("removeCallbacksAndMessages")
        assertTrue("removeCallbacksAndMessages not found in animateText()", removeIndex >= 0)

        // Verify the method has a return/guard path. In the buggy version,
        // removeCallbacksAndMessages is the very first statement with no guard.
        // After the fix, there should be a condition before it.
        val codeBeforeRemove = methodBody.substring(0, removeIndex).trim()
        val hasEarlyReturnOrGuard = codeBeforeRemove.contains("if") ||
            codeBeforeRemove.contains("return") ||
            codeBeforeRemove.contains("when")

        // This test documents the expected fix. If it fails, the duplicate-text guard
        // has not been added yet (which is the bug we're testing for).
        assertTrue(
            "ANR Bug: animateText() must have a guard before removeCallbacksAndMessages " +
                "to skip re-animation when called with the same text. Currently, every call " +
                "unconditionally restarts the animation, flooding the main Handler at 30ms " +
                "intervals when updateCycleText is called rapidly.",
            hasEarlyReturnOrGuard
        )
    }

    @Test
    fun `animateText must allow animation restart with different text`() {
        assertTrue(sourceFile.exists())
        val source = sourceFile.readText()

        val methodStart = source.indexOf("fun animateText(")
        assertTrue("animateText() method not found", methodStart >= 0)

        val methodBody = extractMethodBody(source, methodStart)
        assertNotNull("Could not extract animateText() body", methodBody)

        // The method must still handle new (different) text by resetting charIndex
        // and scheduling the typeRunnable.
        assertTrue(
            "animateText() must reset charIndex for new text",
            methodBody!!.contains("charIndex") && methodBody.contains("0")
        )
        assertTrue(
            "animateText() must schedule typeRunnable for non-empty text",
            methodBody.contains("typeRunnable")
        )
    }

    @Test
    fun `charDelayMs must not be lower than 30ms`() {
        assertTrue(sourceFile.exists())
        val source = sourceFile.readText()

        // The 30ms delay is already aggressive. Verify it hasn't been made even lower,
        // which would worsen the ANR under rapid input.
        val delayMatch = Regex("""charDelayMs\s*=\s*(\d+)L?""").find(source)
        assertNotNull("charDelayMs field not found", delayMatch)

        val delayMs = delayMatch!!.groupValues[1].toLong()
        assertTrue(
            "charDelayMs ($delayMs ms) should not be lower than 30ms to avoid Handler flooding",
            delayMs >= 30
        )
    }

    @Test
    fun `handler must use main looper`() {
        assertTrue(sourceFile.exists())
        val source = sourceFile.readText()

        // Verify the handler is on the main looper — if it were on a background looper,
        // the animation runnables would not block the UI thread (but the current design
        // uses main looper, making Handler flooding an ANR risk).
        assertTrue(
            "TypewriterTextView handler should use Looper.getMainLooper() — " +
                "this confirms the ANR vector when runnables are flooded",
            source.contains("Handler(Looper.getMainLooper())")
        )
    }

    private fun extractMethodBody(source: String, startIndex: Int): String? {
        val openBrace = source.indexOf('{', startIndex)
        if (openBrace < 0) return null

        var depth = 0
        for (i in openBrace until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(startIndex, i + 1)
                    }
                }
            }
        }
        return null
    }
}
