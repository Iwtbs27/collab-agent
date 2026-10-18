package ai.opencyvis.backend

import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Tests for [NumberPadView] — the in-app 3x4 numeric keypad that drives [OtpDigitBox]
 * without raising the system soft keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NumberPadViewTest {

    private fun newPad(): NumberPadView =
        NumberPadView(RuntimeEnvironment.getApplication())

    private fun keyWithText(pad: NumberPadView, label: String): TextView? =
        (0 until pad.childCount)
            .map { pad.getChildAt(it) }
            .filterIsInstance<TextView>()
            .firstOrNull { it.text?.toString() == label }

    @Test
    fun `renders digit keys 0 through 9 plus backspace`() {
        val pad = newPad()
        ('0'..'9').forEach { d ->
            assertNotNull("missing key $d", keyWithText(pad, d.toString()))
        }
        assertNotNull("missing backspace key", keyWithText(pad, "⌫"))
    }

    @Test
    fun `tapping a digit key invokes onDigit with that digit`() {
        val pad = newPad()
        var got: String? = null
        pad.onDigit = { got = it }
        keyWithText(pad, "7")!!.performClick()
        assertEquals("7", got)
    }

    @Test
    fun `tapping backspace invokes onBackspace`() {
        val pad = newPad()
        var backspaces = 0
        pad.onBackspace = { backspaces++ }
        keyWithText(pad, "⌫")!!.performClick()
        assertEquals(1, backspaces)
    }

    @Test
    fun `pad wired to box enters a full code and auto-submits`() {
        val pad = newPad()
        val box = OtpDigitBox(RuntimeEnvironment.getApplication())
        var submitted: String? = null
        box.listener = object : OtpDigitBox.OnCodeCompleteListener {
            override fun onCodeComplete(code: String) { submitted = code }
        }
        pad.onDigit = { box.appendDigit(it) }
        pad.onBackspace = { box.deleteDigit() }

        "482931".forEach { keyWithText(pad, it.toString())!!.performClick() }
        assertEquals("482931", submitted)
    }
}
