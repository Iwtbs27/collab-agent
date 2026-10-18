package ai.opencyvis.engine

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class ActionRepeatGuardDeviceTest {

    @Before
    fun setUp() {
        shell("logcat -c")
        shell("am start-foreground-service -n $PACKAGE/.AgentService")
        Thread.sleep(800)
    }

    @After
    fun tearDown() {
        shell("am broadcast -a ai.opencyvis.TEST -p $PACKAGE --es debug stop")
    }

    @Test
    fun repeatedTypeTextOnStableScreenIsBlocked() {
        broadcastDebug("repeat_type_text_block")
        assertTrue(waitForLog("TEST repeat_guard type_text decision=BLOCK", TIMEOUT_MS))
        assertTrue(waitForLog("不要重复执行同一输入", TIMEOUT_MS))
    }

    @Test
    fun repeatedTapOnStableScreenIsBlocked() {
        broadcastDebug("repeat_tap_block")
        assertTrue(waitForLog("TEST repeat_guard tap decision=BLOCK", TIMEOUT_MS))
        assertTrue(waitForLog("当前屏幕没有明显变化", TIMEOUT_MS))
    }

    @Test
    fun repeatedTapIsAllowedWhenScreenChanged() {
        broadcastDebug("repeat_tap_allow")
        assertTrue(waitForLog("TEST repeat_guard tap_changed decision=ALLOW", TIMEOUT_MS))
    }

    private fun broadcastDebug(command: String) {
        shell("am broadcast -a ai.opencyvis.TEST -p $PACKAGE --es debug $command")
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
    }
}
