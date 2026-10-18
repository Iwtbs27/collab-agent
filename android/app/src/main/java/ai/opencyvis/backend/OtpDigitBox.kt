package ai.opencyvis.backend

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.LinearLayout
import ai.opencyvis.R

/**
 * A compact OTP-style 6-digit code display widget.
 *
 * This widget is **display-only** — it never raises the system soft keyboard.
 * Digits are fed in programmatically via [appendDigit] / [deleteDigit], driven by
 * a [NumberPadView] rendered inside our own activity. This is essential for the
 * split-screen pairing flow: the system IME rises from the screen bottom and would
 * cover the pairing code shown in the Settings half. By using an in-app number pad,
 * the system keyboard never appears and the pairing code stays visible.
 *
 * Features:
 * - 6 individual square digit boxes arranged horizontally
 * - The "current" box (next to be filled) is highlighted via [android.view.View.setActivated]
 * - Auto-submits via [OnCodeCompleteListener] when all 6 digits are entered
 * - [clear] resets all boxes and the cursor to the first
 */
class OtpDigitBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    interface OnCodeCompleteListener {
        fun onCodeComplete(code: String)
    }

    var listener: OnCodeCompleteListener? = null

    private val digitCount = 6
    private val boxes = mutableListOf<TextView>()
    private var currentIndex = 0
    private var inputEnabled = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER

        val boxSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics
        ).toInt()
        val marginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
        ).toInt()
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics
        )

        for (i in 0 until digitCount) {
            val box = TextView(context).apply {
                inputType = InputType.TYPE_NULL
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                setTextColor(0xFF1F1F1F.toInt())
                background = context.getDrawable(R.drawable.bg_otp_digit_selector)
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false

                layoutParams = LayoutParams(boxSizePx, boxSizePx).apply {
                    if (i < digitCount - 1) marginEnd = marginPx
                }
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            boxes.add(box)
            addView(box)
        }
        updateActiveHighlight()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        inputEnabled = enabled
        boxes.forEach { it.alpha = if (enabled) 1f else 0.4f }
    }

    fun getCode(): String = boxes.joinToString("") { it.text.toString() }

    fun clear() {
        boxes.forEach { it.text = "" }
        currentIndex = 0
        updateActiveHighlight()
    }

    /** Append a single digit at the current position and advance. Auto-submits when full. */
    fun appendDigit(digit: String) {
        if (!inputEnabled) return
        if (currentIndex >= digitCount) return // already full — ignore extra input
        boxes[currentIndex].text = digit
        currentIndex++
        updateActiveHighlight()
        if (currentIndex >= digitCount) {
            val code = getCode()
            if (code.length == digitCount) listener?.onCodeComplete(code)
        }
    }

    /** Delete the digit at the current position (or step back if current is empty). */
    fun deleteDigit() {
        if (!inputEnabled) return
        when {
            // All boxes filled: step back into the last box and clear it.
            currentIndex >= digitCount -> currentIndex = digitCount - 1
            // Current box is empty: step back to the previously filled box.
            boxes[currentIndex].text.isNullOrEmpty() && currentIndex > 0 -> currentIndex--
        }
        boxes[currentIndex].text = ""
        updateActiveHighlight()
    }

    private fun updateActiveHighlight() {
        // When full, keep the highlight on the last box rather than nowhere.
        val active = currentIndex.coerceAtMost(digitCount - 1)
        boxes.forEachIndexed { i, box -> box.isActivated = (i == active) }
    }
}
