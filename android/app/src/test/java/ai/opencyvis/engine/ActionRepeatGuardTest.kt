package ai.opencyvis.engine

import ai.opencyvis.action.Action
import org.junit.Assert.*
import org.junit.Test

class ActionRepeatGuardTest {

    private val sameScreen = ScreenFingerprint(0x0f0f0f0f0f0f0f0fL)
    private val changedScreen = ScreenFingerprint(-1L)

    @Test
    fun `repeated identical type_text is blocked`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
        val feedback = (decision as ActionRepeatGuard.Decision.Block).feedback
        assertTrue(feedback.isNotEmpty())
        assertTrue(feedback.contains("ask_user"))
    }

    @Test
    fun `same type_text is allowed after an intervening tap changes focus context`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)
        guard.recordExecuted(Action.Tap(500, 250), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `wait does not reset repeated type_text protection`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.TypeText("京东"), sameScreen)
        guard.recordExecuted(Action.Wait(), sameScreen)

        val decision = guard.evaluate(Action.TypeText("京东"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
    }

    @Test
    fun `nearby repeated tap is blocked when screen is unchanged`() {
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        val decision = guard.evaluate(Action.Tap(520, 590), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
        assertTrue((decision as ActionRepeatGuard.Decision.Block).feedback.contains("ask_user"))
    }

    @Test
    fun `nearby repeated tap is allowed when screen changed`() {
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        val decision = guard.evaluate(Action.Tap(520, 590), changedScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `distant repeated tap is allowed even when screen is unchanged`() {
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(100, 100), sameScreen)

        val decision = guard.evaluate(Action.Tap(800, 800), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `repeated enter is blocked when screen is unchanged`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.KeyEvent("enter"), sameScreen)

        val decision = guard.evaluate(Action.KeyEvent("enter"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Block)
    }

    @Test
    fun `non-submit key event is allowed when repeated`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.KeyEvent("back"), sameScreen)

        val decision = guard.evaluate(Action.KeyEvent("back"), sameScreen)

        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `open_app wait and swipe are allowed by repeat guard`() {
        val guard = ActionRepeatGuard()

        guard.recordExecuted(Action.OpenApp("应用宝"), sameScreen)
        assertTrue(guard.evaluate(Action.OpenApp("应用宝"), sameScreen) is ActionRepeatGuard.Decision.Allow)

        guard.recordExecuted(Action.Wait(), sameScreen)
        assertTrue(guard.evaluate(Action.Wait(), sameScreen) is ActionRepeatGuard.Decision.Allow)

        guard.recordExecuted(Action.Swipe("up"), sameScreen)
        assertTrue(guard.evaluate(Action.Swipe("up"), sameScreen) is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `wait as candidate returns allow immediately without updating guard state`() {
        val guard = ActionRepeatGuard()
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        // Wait should always be allowed
        val decision = guard.evaluate(Action.Wait(), sameScreen)
        assertTrue(decision is ActionRepeatGuard.Decision.Allow)
    }

    @Test
    fun `repeated tap after intervening wait is still blocked`() {
        // Scenario: tap(500,600) → wait → tap(500,600)
        // The wait must not reset the guard's memory of the previous tap,
        // so the second tap at the same position should be blocked.
        val guard = ActionRepeatGuard(tapTolerance = 35)
        guard.recordExecuted(Action.Tap(500, 600), sameScreen)

        // Intervening wait — evaluate returns Allow, recordExecuted skips it
        assertTrue(guard.evaluate(Action.Wait(), sameScreen) is ActionRepeatGuard.Decision.Allow)
        guard.recordExecuted(Action.Wait(), sameScreen)

        // Same-position tap should still be blocked because lastExecutedAction is still the original tap
        val decision = guard.evaluate(Action.Tap(510, 595), sameScreen)
        assertTrue(decision is ActionRepeatGuard.Decision.Block)
        assertTrue((decision as ActionRepeatGuard.Decision.Block).feedback.contains("ask_user"))
    }

    @Test
    fun `screen fingerprints tolerate small hamming differences`() {
        val base = ScreenFingerprint(0b101010L)
        val oneBitDifferent = ScreenFingerprint(0b101011L)
        val manyBitsDifferent = ScreenFingerprint(-1L)

        assertTrue(base.isSimilarTo(oneBitDifferent))
        assertFalse(base.isSimilarTo(manyBitsDifferent))
    }
}
