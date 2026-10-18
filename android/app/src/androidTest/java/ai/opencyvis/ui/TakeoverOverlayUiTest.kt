package ai.opencyvis.ui

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class TakeoverOverlayUiTest {

    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun setUp() {
        shell("logcat -c")
        shell("am start-foreground-service -n $PACKAGE/.AgentService")
        shell("am startservice -n $PACKAGE/.OverlayService")
        device.waitForIdle()
    }

    @After
    fun tearDown() {
        broadcast("stop")
    }

    @Test
    fun takeoverChatHeadReturnsToExpandedControls() {
        startDebugTakeover()

        device.pressHome()
        assertNotNull(
            "Paused takeover overlay handle should be visible on launcher",
            waitForOverlayHandle()
        )

        waitForOverlayHandle()!!.click()

        assertNotNull(
            "ViewActivity should return to foreground",
            device.wait(Until.findObject(By.res(PACKAGE, "bottom_panel")), TIMEOUT_MS)
        )
        assertTrue(
            "Takeover controls should be expanded after chat-head click",
            waitForLog("Bottom panel expanded", TIMEOUT_MS)
        )
        assertTrue(
            "Overlay logs should show Paused state",
            waitForLog("updateState: .*Paused", TIMEOUT_MS)
        )
    }

    @Test
    fun takeoverTapShowsKeyboardProxyAndForwardsTyping() {
        startDebugTakeover()

        device.click(device.displayWidth / 2, device.displayHeight / 2)

        assertTrue(
            "Takeover tap should request the local IME through keyboard proxy",
            waitForLog("Takeover keyboard proxy requested IME", TIMEOUT_MS)
        )

        assertTrue("ViewActivity should remain in takeover mode", waitForLog("Takeover mode: true", TIMEOUT_MS))
    }

    @Test
    fun runningAgentChatHeadReflectsRunningStateOnLauncher() {
        broadcast("running")
        device.pressHome()

        assertNotNull(
            "Running agent overlay handle should be visible on launcher",
            waitForOverlayHandle()
        )
        assertTrue(
            "Overlay logs should show Running state while app is backgrounded",
            waitForLog("updateState: Running", TIMEOUT_MS)
        )
    }

    @Test
    fun askUserFabRestoresQuestionAfterPanelCollapse() {
        broadcast("running")
        broadcast("view")
        assertNotNull(
            "ViewActivity should open in debug view mode",
            device.wait(Until.findObject(By.res(PACKAGE, "surface_view")), TIMEOUT_MS)
        )

        broadcastAskUser(ASK_QUESTION)
        assertNotNull(
            "Ask-user question should be shown immediately",
            device.wait(Until.findObject(By.text(ASK_QUESTION)), TIMEOUT_MS)
        )

        device.click(device.displayWidth / 2, device.displayHeight / 3)
        assertNotNull(
            "FAB should be visible after collapsing ask-user panel",
            device.wait(Until.findObject(By.res(PACKAGE, "fab")), TIMEOUT_MS)
        )

        device.findObject(By.res(PACKAGE, "fab")).click()

        assertNotNull(
            "Question should be restored when the FAB is clicked again",
            device.wait(Until.findObject(By.text(ASK_QUESTION)), TIMEOUT_MS)
        )
        assertTrue(
            "ViewActivity should log ask-user restoration",
            waitForLog("Ask user section restored", TIMEOUT_MS)
        )
    }

    @Test
    fun handoffUsesBottomPanelAndTakeoverMode() {
        broadcast("running")
        broadcastHandoff(HANDOFF_REASON)

        assertNotNull(
            "ViewActivity should open for handoff",
            device.wait(Until.findObject(By.res(PACKAGE, "surface_view")), TIMEOUT_MS)
        )
        assertNotNull(
            "Handoff section should be shown inside the bottom panel",
            device.wait(Until.findObject(By.res(PACKAGE, "handoff_panel")), TIMEOUT_MS)
        )
        assertNotNull(
            "Bottom panel should be visible for handoff controls",
            device.wait(Until.findObject(By.res(PACKAGE, "bottom_panel")), TIMEOUT_MS)
        )
        assertNotNull(
            "Handoff reason should be visible",
            device.wait(Until.findObject(By.textContains(HANDOFF_REASON)), TIMEOUT_MS)
        )
        assertTrue(
            "Handoff should enter takeover mode",
            waitForLog("Entered TAKEOVER mode", TIMEOUT_MS)
        )
    }

    private fun startDebugTakeover() {
        broadcast("running")
        broadcast("view")
        assertNotNull(
            "ViewActivity should open in debug view mode",
            device.wait(Until.findObject(By.res(PACKAGE, "surface_view")), TIMEOUT_MS)
        )

        val fab = device.wait(Until.findObject(By.res(PACKAGE, "fab")), TIMEOUT_MS)
        assertNotNull("FAB should be available", fab)
        fab.click()

        val takeover = device.wait(Until.findObject(By.res(PACKAGE, "btn_takeover")), TIMEOUT_MS)
        assertNotNull("Takeover button should be available", takeover)
        takeover.click()

        assertTrue(
            "AgentService should enter takeover mode",
            waitForLog("Entered TAKEOVER mode", TIMEOUT_MS)
        )
        assertNotNull(
            "FAB should return after takeover panel collapses",
            device.wait(Until.findObject(By.res(PACKAGE, "fab")), TIMEOUT_MS)
        )
    }

    private fun broadcast(debugCommand: String) {
        shell("am broadcast -a ai.opencyvis.TEST -p $PACKAGE --es debug $debugCommand")
    }

    private fun broadcastAskUser(question: String) {
        shell("am broadcast -a ai.opencyvis.TEST -p $PACKAGE --es askuser $question")
    }

    private fun broadcastHandoff(reason: String) {
        shell("am broadcast -a ai.opencyvis.TEST -p $PACKAGE --es handoff $reason")
    }

    private fun waitForOverlayHandle(): UiObject2? {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.desc("AI Agent"))?.let { return it }
            device.findObject(By.res(PACKAGE, "pill_root"))?.let { return it }
            Thread.sleep(250)
        }
        return null
    }

    private fun waitForLog(pattern: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val regex = Regex(pattern)
        while (System.currentTimeMillis() < deadline) {
            if (regex.containsMatchIn(shell("logcat -d -v brief"))) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd: ParcelFileDescriptor = automation.executeShellCommand(command)
        return FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private companion object {
        private const val PACKAGE = "ai.opencyvis"
        private const val TIMEOUT_MS = 8_000L
        private const val ASK_QUESTION = "Need_user_confirmation?"
        private const val HANDOFF_REASON = "Sensitive_password_required"
    }
}
