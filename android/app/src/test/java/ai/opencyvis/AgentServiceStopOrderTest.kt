package ai.opencyvis

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Static-analysis test that verifies the ordering guarantee in stopAgent().
 *
 * Bug: After a task completes, clicking the stop button causes the controlled app
 * (e.g., JD.com) to briefly flash on screen before returning to ControlPanel.
 * Root cause: moveTaskToDisplay() is called BEFORE bringOpenCyvisUiToFront() in the
 * deferred Handler.post block inside stopAgent(), creating a window where the moved
 * task is visible on Display 0 before the OpenCyvis UI covers it.
 *
 * Fix: bringOpenCyvisUiToFront() must be called BEFORE moveTaskToDisplay().
 *
 * See: GitHub issue #22
 */
class AgentServiceStopOrderTest {

    private val sourceFile = File("src/main/java/ai/opencyvis/AgentService.kt")

    @Test
    fun `stopAgent deferred block must call bringOpenCyvisUiToFront before moveTaskToDisplay`() {
        assertTrue(
            "AgentService.kt source file not found at ${sourceFile.absolutePath}",
            sourceFile.exists()
        )

        val source = sourceFile.readText()

        // Find the stopAgent() method
        val stopAgentStart = source.indexOf("fun stopAgent()")
        assertTrue("stopAgent() method not found in AgentService.kt", stopAgentStart >= 0)

        // Scope to the stopAgent method body (find next top-level fun or end of class)
        val stopAgentBody = extractMethodBody(source, stopAgentStart)
        assertNotNull("Could not extract stopAgent() method body", stopAgentBody)

        // Find the Handler.post { ... } block within stopAgent
        val handlerPostIndex = stopAgentBody!!.indexOf("Handler(")
        assertTrue(
            "Handler.post block not found in stopAgent()",
            handlerPostIndex >= 0
        )

        val deferredBlock = stopAgentBody.substring(handlerPostIndex)

        // Verify ordering: bringOpenCyvisUiToFront must appear BEFORE moveTaskToDisplay
        val bringToFrontIndex = deferredBlock.indexOf("bringOpenCyvisUiToFront")
        val moveTaskIndex = deferredBlock.indexOf("moveTaskToDisplay")

        assertTrue(
            "bringOpenCyvisUiToFront() not found in deferred block",
            bringToFrontIndex >= 0
        )
        assertTrue(
            "moveTaskToDisplay() not found in deferred block",
            moveTaskIndex >= 0
        )
        assertTrue(
            "Bug #22: bringOpenCyvisUiToFront() must be called BEFORE moveTaskToDisplay() " +
                "in the deferred Handler.post block to prevent the controlled app from flashing. " +
                "Found bringOpenCyvisUiToFront at offset $bringToFrontIndex, " +
                "moveTaskToDisplay at offset $moveTaskIndex",
            bringToFrontIndex < moveTaskIndex
        )
    }

    /**
     * Extracts the body of a method starting from [startIndex] by counting brace depth.
     * Returns the substring from startIndex to the closing brace of the method.
     */
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
