package ai.opencyvis.ui

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class TypewriterTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var fullText = ""
    private var charIndex = 0
    private var cursorVisible = true
    private val cursor = "▍"
    private val charDelayMs = 30L
    private val cursorBlinkMs = 530L

    private val typeRunnable = object : Runnable {
        override fun run() {
            if (charIndex < fullText.length) {
                charIndex++
                text = fullText.substring(0, charIndex) + cursor
                handler.postDelayed(this, charDelayMs)
            } else {
                handler.post(cursorBlinkRunnable)
            }
        }
    }

    // Keep cursor character always present to avoid layout jitter;
    // toggle visibility via transparent/opaque color instead.
    private val cursorBlinkRunnable = object : Runnable {
        override fun run() {
            cursorVisible = !cursorVisible
            val s = SpannableString(fullText + cursor)
            if (!cursorVisible) {
                val start = fullText.length
                s.setSpan(ForegroundColorSpan(Color.TRANSPARENT), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = s
            handler.postDelayed(this, cursorBlinkMs)
        }
    }

    fun animateText(newText: String) {
        if (newText == fullText && charIndex < fullText.length) return
        handler.removeCallbacksAndMessages(null)
        fullText = newText
        charIndex = 0
        cursorVisible = true
        if (newText.isEmpty()) {
            text = cursor
            handler.post(cursorBlinkRunnable)
        } else {
            text = cursor
            handler.postDelayed(typeRunnable, charDelayMs)
        }
    }

    fun stopAnimation() {
        handler.removeCallbacksAndMessages(null)
        text = fullText
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }
}
