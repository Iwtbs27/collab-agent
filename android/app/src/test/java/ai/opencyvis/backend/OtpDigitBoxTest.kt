package ai.opencyvis.backend

import android.text.InputType
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [OtpDigitBox] — the display-only 6-digit OTP widget driven by an in-app
 * number pad (no system keyboard).
 *
 * Context: In split-screen ADB pairing, the system soft keyboard rises from the screen
 * bottom and covers the pairing code shown in the adjacent Settings window. The fix is
 * to make OtpDigitBox display-only (never raises the IME) and feed digits via an in-app
 * [NumberPadView]. These tests lock in that behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtpDigitBoxTest {

    private fun newBox(): OtpDigitBox =
        OtpDigitBox(RuntimeEnvironment.getApplication())

    private fun digitViews(box: OtpDigitBox): List<TextView> =
        (0 until box.childCount).map { box.getChildAt(it) as TextView }

    // ── core input behavior ────────────────────────────────────────────────

    @Test
    fun `appendDigit fills boxes left to right`() {
        val box = newBox()
        box.appendDigit("1")
        box.appendDigit("2")
        box.appendDigit("3")
        assertEquals("123", box.getCode())
    }

    @Test
    fun `auto-submit fires exactly once when sixth digit entered`() {
        val box = newBox()
        var fired = 0
        var submitted: String? = null
        box.listener = object : OtpDigitBox.OnCodeCompleteListener {
            override fun onCodeComplete(code: String) { fired++; submitted = code }
        }
        "12345".forEach { box.appendDigit(it.toString()) }
        assertEquals("not yet complete", 0, fired)
        box.appendDigit("6")
        assertEquals(1, fired)
        assertEquals("123456", submitted)
    }

    @Test
    fun `appendDigit beyond sixth is ignored`() {
        val box = newBox()
        "123456".forEach { box.appendDigit(it.toString()) }
        box.appendDigit("7")
        box.appendDigit("8")
        assertEquals("123456", box.getCode())
    }

    @Test
    fun `deleteDigit removes current then steps back`() {
        val box = newBox()
        "123".forEach { box.appendDigit(it.toString()) }
        // cursor is on box index 3 (empty). delete steps back to index 2 and clears it.
        box.deleteDigit()
        assertEquals("12", box.getCode())
        box.deleteDigit()
        assertEquals("1", box.getCode())
    }

    @Test
    fun `deleteDigit at start is a no-op`() {
        val box = newBox()
        box.deleteDigit()
        assertEquals("", box.getCode())
    }

    @Test
    fun `clear resets all boxes and cursor`() {
        val box = newBox()
        "123456".forEach { box.appendDigit(it.toString()) }
        box.clear()
        assertEquals("", box.getCode())
        // cursor back at first box: appending lands at index 0
        box.appendDigit("9")
        assertEquals("9", box.getCode())
    }

    @Test
    fun `disabled box ignores input`() {
        val box = newBox()
        box.isEnabled = false
        box.appendDigit("5")
        assertEquals("", box.getCode())
    }

    // ── the critical invariant: never raises the system keyboard ────────────

    @Test
    fun `digit views are non-editable and non-focusable so no soft keyboard appears`() {
        val box = newBox()
        val views = digitViews(box)
        assertEquals("expected 6 digit boxes", 6, views.size)
        views.forEachIndexed { i, v ->
            assertEquals("box $i must use TYPE_NULL", InputType.TYPE_NULL, v.inputType)
            assertFalse("box $i must not be focusable", v.isFocusable)
            assertFalse("box $i must not be focusable in touch mode", v.isFocusableInTouchMode)
            assertFalse("box $i must not be clickable", v.isClickable)
        }
    }
}
