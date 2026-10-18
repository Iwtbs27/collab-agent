package ai.opencyvis.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import ai.opencyvis.R

/**
 * Floating control bar shown when the operated app is on the physical display.
 * Contains a bottom bar with [Back to Chat] and [Takeover/Return Control] buttons,
 * plus a full-screen transparent touch interception layer for view-only mode.
 *
 * Touch blocking:
 * - VIEW mode: touch interceptor blocks all touches (FLAG_NOT_TOUCHABLE not set)
 * - TAKEOVER mode: touch interceptor passes through (FLAG_NOT_TOUCHABLE set via updateViewLayout)
 */
class FloatingControlBar(private val context: Context) {

    companion object {
        private const val TAG = "FloatingControlBar"
    }

    interface Callback {
        fun onBackToChat()
        fun onToggleTakeover(isTakeover: Boolean)
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Touch interception layer (full screen transparent)
    private var touchBlockerView: View? = null
    private var touchBlockerParams: WindowManager.LayoutParams? = null

    // Bottom control bar
    private var controlBarView: View? = null
    private var controlBarParams: WindowManager.LayoutParams? = null

    private var statusText: TextView? = null
    private var btnTakeover: Button? = null
    private var btnBackToChat: Button? = null

    var callback: Callback? = null
    private var isShowing = false
    private var isTakeoverMode = false

    /**
     * Show the floating control bar with touch blocker (view-only mode).
     * Z-order: touch blocker (bottom) → control bar (top).
     * Later addView calls place windows on top of earlier ones.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        // 1. Touch blocker — added FIRST so it's below the control bar
        touchBlockerView = View(context).apply {
            setBackgroundColor(0x00000000) // fully transparent
            setOnTouchListener { _, _ -> true } // consume all touches
        }
        touchBlockerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(touchBlockerView, touchBlockerParams)

        // 2. Control bar — added SECOND so it's on top (receives touches first)
        val inflater = LayoutInflater.from(context)
        controlBarView = inflater.inflate(R.layout.floating_control_bar, null)

        statusText = controlBarView?.findViewById(R.id.text_status)
        btnTakeover = controlBarView?.findViewById(R.id.btn_takeover)
        btnBackToChat = controlBarView?.findViewById(R.id.btn_back_to_chat)

        btnTakeover?.setOnClickListener {
            isTakeoverMode = !isTakeoverMode
            setTakeoverMode(isTakeoverMode)
            callback?.onToggleTakeover(isTakeoverMode)
        }

        btnBackToChat?.setOnClickListener {
            callback?.onBackToChat()
        }

        controlBarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }
        windowManager.addView(controlBarView, controlBarParams)

        isTakeoverMode = false
        isShowing = true
        updateUI()
        Log.i(TAG, "Floating control bar shown (view mode)")
    }

    /**
     * Dismiss all overlay views.
     */
    fun dismiss() {
        if (!isShowing) return
        try {
            touchBlockerView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        try {
            controlBarView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        touchBlockerView = null
        controlBarView = null
        isShowing = false
        isTakeoverMode = false
        Log.i(TAG, "Floating control bar dismissed")
    }

    /**
     * Switch between view-only and takeover mode.
     * Uses updateViewLayout to toggle FLAG_NOT_TOUCHABLE on the touch blocker,
     * avoiding window add/remove flicker.
     */
    fun setTakeoverMode(takeover: Boolean) {
        isTakeoverMode = takeover
        val params = touchBlockerParams ?: return
        val view = touchBlockerView ?: return

        if (takeover) {
            // Pass touches through to the app beneath
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            // Block all touches
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }

        try {
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update touch blocker layout", e)
        }

        updateUI()
        Log.i(TAG, "Touch blocker mode: ${if (takeover) "TAKEOVER (passthrough)" else "VIEW (blocking)"}")
    }

    /**
     * Hide overlay temporarily (for screenshot safety).
     * Uses removeViewImmediate() for synchronous removal from SurfaceFlinger.
     */
    fun hide() {
        try {
            touchBlockerView?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {}
        try {
            controlBarView?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
        } catch (_: Exception) {}
    }

    /**
     * Re-show overlay after hide(). Add blocker first, then bar (same z-order as show()).
     */
    fun unhide() {
        try {
            touchBlockerView?.let {
                if (!it.isAttachedToWindow) windowManager.addView(it, touchBlockerParams)
            }
        } catch (_: Exception) {}
        try {
            controlBarView?.let {
                if (!it.isAttachedToWindow) windowManager.addView(it, controlBarParams)
            }
        } catch (_: Exception) {}
    }

    private fun updateUI() {
        if (isTakeoverMode) {
            statusText?.text = "Takeover mode"
            btnTakeover?.text = "Return control"
        } else {
            statusText?.text = "View mode"
            btnTakeover?.text = "Takeover"
        }
    }
}
