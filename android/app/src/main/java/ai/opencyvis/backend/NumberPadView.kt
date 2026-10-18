package ai.opencyvis.backend

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.TextView

/**
 * A self-contained 3x4 numeric keypad (1-9, 0, backspace) rendered inside our own
 * activity layout. Used to drive [OtpDigitBox] without ever raising the system soft
 * keyboard — critical for split-screen ADB pairing where the system IME would cover
 * the pairing code shown in the adjacent Settings window.
 *
 * Layout:
 * ```
 *   1  2  3
 *   4  5  6
 *   7  8  9
 *      0  ⌫
 * ```
 *
 * Wire up via [onDigit] (called with "0".."9") and [onBackspace].
 */
class NumberPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : GridLayout(context, attrs, defStyleAttr) {

    var onDigit: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null

    // Fixed key dimensions — do NOT use column weight here: this GridLayout is
    // wrap_content, and weighted columns (width=0) only distribute *excess* space,
    // which collapses every column to 0 width under wrap_content. Fixed widths make
    // the grid deterministically size to 3 columns + margins.
    private val keyWidthPx = dp(96f)
    private val keyHeightPx = dp(52f)
    private val gapPx = dp(8f)

    init {
        columnCount = 3
        rowCount = 4

        // Row 0..2: digits 1-9
        val layout = listOf(
            "1", "2", "3",
            "4", "5", "6",
            "7", "8", "9",
            "", "0", "<"
        )
        layout.forEachIndexed { index, label ->
            val row = index / 3
            val col = index % 3
            val cell = when (label) {
                "" -> makeSpacer()
                "<" -> makeKey("⌫", isBackspace = true)
                else -> makeKey(label, isBackspace = false)
            }
            val params = LayoutParams(spec(row), spec(col)).apply {
                width = keyWidthPx
                height = keyHeightPx
                setMargins(gapPx / 2, gapPx / 2, gapPx / 2, gapPx / 2)
            }
            cell.layoutParams = params
            addView(cell)
        }
    }

    private fun makeKey(label: String, isBackspace: Boolean): View {
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isBackspace) 20f else 20f)
            setTextColor(0xFF1F1F1F.toInt())
            background = context.getDrawable(ai.opencyvis.R.drawable.bg_otp_key_selector)
            isClickable = true
            isFocusable = true
            contentDescription = if (isBackspace) "Backspace" else label
            setOnClickListener {
                if (isBackspace) onBackspace?.invoke() else onDigit?.invoke(label)
            }
        }
    }

    private fun makeSpacer(): View = View(context)

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
        ).toInt()
}
