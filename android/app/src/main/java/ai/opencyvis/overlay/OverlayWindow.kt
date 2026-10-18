package ai.opencyvis.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.opencyvis.R
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult

/**
 * Floating overlay: chat-head (D-style, corner) ⇄ island (A-style, centered top).
 *
 * Minimized = gradient chat-head ball in top-right corner, draggable.
 * Expanded  = Dynamic Island capsule centered at top (task name + status + stop).
 *
 * Tap chat-head → expand to island.
 * Tap island body → return to app, collapse to chat-head.
 * WaitingForUser → auto-expand to island.
 */
class OverlayWindow(private val context: Context) {

    interface Callback {
        fun onReturnToApp()
        fun onStop()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val debugMode = ConfigRepository(context).debugMode

    // Island (expanded) view — Dynamic Island style
    private var pillView: View? = null
    private var pillParams: WindowManager.LayoutParams? = null
    private var pillDot: View? = null
    private var pillTask: TextView? = null
    private var pillStep: TextView? = null
    private var pillStop: ImageButton? = null

    // Chat-head (minimized) view — gradient ball
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private var chatHead: ImageView? = null

    var callback: Callback? = null
    var maxSteps: Int = 100

    var currentInstruction: String = ""
        set(value) {
            field = value
            pillTask?.text = trimTask(value)
        }

    private var isExpanded = false
    private var isPrepared = false
    private var isAttached = false
    private var currentStep: Int = 0
    private var _currentState: AgentState = AgentState.Idle()

    private val dragSlop: Int = (10 * context.resources.displayMetrics.density).toInt()
    private var glowAnimator: ObjectAnimator? = null
    private var dotAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoCollapseRunnable = Runnable { setExpanded(false) }
    private val autoCollapseDuration = 5000L

    internal var attachCount: Int = 0
        private set
    internal var detachCount: Int = 0
        private set

    private val colorIdle = 0xFF888888.toInt()
    private val colorRunning = 0xFF4ADE80.toInt()
    private val colorWaiting = 0xFFFA8C16.toInt()
    private val colorPaused = 0xFFFFCBA1.toInt()
    private val colorError = 0xFFEF4444.toInt()
    private val colorSuccess = 0xFF4CAF50.toInt()

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    fun prepare() {
        if (isPrepared) return

        val inflater = LayoutInflater.from(context)

        // Island view (expanded) — centered at top
        pillView = inflater.inflate(R.layout.overlay_window, null)
        pillDot = pillView?.findViewById(R.id.pill_dot)
        pillTask = pillView?.findViewById(R.id.pill_task)
        pillStep = pillView?.findViewById(R.id.pill_step)
        pillStop = pillView?.findViewById(R.id.pill_stop)

        pillTask?.text = trimTask(currentInstruction)

        pillView?.findViewById<LinearLayout>(R.id.pill_root)?.setOnClickListener {
            setExpanded(false)
        }
        pillStop?.setOnClickListener { callback?.onStop() }

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 80
        }

        // Chat-head view (minimized) — corner ball
        minimizedView = inflater.inflate(R.layout.overlay_minimized, null)
        chatHead = minimizedView?.findViewById(R.id.chat_head)

        minimizedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 120
        }

        chatHead?.setOnClickListener {
            if (_currentState is AgentState.Paused) {
                callback?.onReturnToApp()
            } else {
                setExpanded(true)
            }
        }
        chatHead?.setOnLongClickListener {
            callback?.onReturnToApp()
            setExpanded(false)
            true
        }
        setupDragging(chatHead, minimizedParams!!)

        applyStateColors()
        startGlowAnimation()
        isPrepared = true
        Log.d(TAG, "prepare() done")
    }

    fun attach() {
        if (!isPrepared) {
            Log.w(TAG, "attach() called before prepare(); ignoring")
            return
        }
        if (isAttached) return
        applyStateColors()
        refreshPillText()

        val view = if (isExpanded) pillView else minimizedView
        val params = if (isExpanded) pillParams else minimizedParams
        try {
            if (view != null && params != null && !view.isAttachedToWindow) {
                windowManager.addView(view, params)
                attachCount++
                // Entry animation
                if (isExpanded) {
                    // Island: scale from small + fade
                    view.scaleX = 0.5f
                    view.scaleY = 0.5f
                    view.alpha = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    // Chat-head: bounce in
                    view.scaleX = 0f
                    view.scaleY = 0f
                    view.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
                Log.d(TAG, "attach() added ${if (isExpanded) "island" else "chatHead"}")
            }
            isAttached = true
        } catch (e: Exception) {
            Log.w(TAG, "attach: addView failed: ${e.message}")
        }
    }

    fun detach() {
        if (!isPrepared) return
        if (!isAttached) return
        try {
            pillView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        try {
            minimizedView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (_: Exception) {}
        isAttached = false
        detachCount++
        Log.d(TAG, "detach() done")
    }

    fun dismiss() {
        handler.removeCallbacks(autoCollapseRunnable)
        glowAnimator?.cancel()
        dotAnimator?.cancel()
        detach()
        pillView = null
        minimizedView = null
        isPrepared = false
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        handler.removeCallbacks(autoCollapseRunnable)
        if (expanded && _currentState !is AgentState.WaitingForUser
            && _currentState !is AgentState.WaitingForHandoff) {
            handler.postDelayed(autoCollapseRunnable, autoCollapseDuration)
        }
        if (!isAttached) return

        val newView = if (expanded) pillView else minimizedView
        val newParams = if (expanded) pillParams else minimizedParams
        val oldView = if (expanded) minimizedView else pillView
        try {
            oldView?.let {
                if (it.isAttachedToWindow) windowManager.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded remove old: $e")
        }
        try {
            if (newView != null && newParams != null && !newView.isAttachedToWindow) {
                windowManager.addView(newView, newParams)
                // Transition animation
                if (expanded) {
                    newView.scaleX = 0.5f
                    newView.scaleY = 0.5f
                    newView.alpha = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(250)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                } else {
                    newView.scaleX = 0f
                    newView.scaleY = 0f
                    newView.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(300)
                        .setInterpolator(OvershootInterpolator(1.5f))
                        .start()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "setExpanded add new: $e")
        }
    }

    fun isAttachedForTest(): Boolean = isAttached
    fun isExpandedForTest(): Boolean = isExpanded
    fun isPreparedForTest(): Boolean = isPrepared

    // ── State updates ──────────────────────────────────────────────────────

    fun updateState(state: AgentState) {
        Log.d(TAG, "updateState: $state isAttached=$isAttached isExpanded=$isExpanded")
        _currentState = state
        when (state) {
            is AgentState.Running -> {
                currentStep = state.step
                pillStep?.text = if (debugMode) "step ${state.step} / $maxSteps" else context.getString(R.string.overlay_running)
            }
            is AgentState.WaitingForUser -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_answer)
                setExpanded(true)
            }
            is AgentState.WaitingForHandoff -> {
                pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
                setExpanded(true)
            }
            is AgentState.Paused -> {
                pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            }
            is AgentState.Idle -> {
                val idle = state as AgentState.Idle
                if (idle.resultMessage != null) {
                    pillStep?.text = context.getString(R.string.overlay_done)
                    pillStop?.visibility = View.GONE
                }
            }
            is AgentState.Error -> {
                pillStep?.text = context.getString(R.string.overlay_failed)
                pillStop?.visibility = View.GONE
            }
            else -> {}
        }
        applyStateColors()
    }

    fun addStepResult(result: StepResult) {
        currentStep = result.step
        pillStep?.text = if (debugMode) "step ${result.step} / $maxSteps" else context.getString(R.string.overlay_running)
    }

    private fun applyStateColors() {
        val color = when (_currentState) {
            is AgentState.Running -> colorRunning
            is AgentState.WaitingForUser -> colorWaiting
            is AgentState.WaitingForHandoff -> colorWaiting
            is AgentState.Paused -> colorPaused
            is AgentState.Error -> colorError
            is AgentState.Idle -> if ((_currentState as AgentState.Idle).resultMessage != null) colorSuccess else colorIdle
            else -> colorIdle
        }
        (pillDot?.background as? GradientDrawable)?.setColor(color)
    }

    private fun refreshPillText() {
        pillTask?.text = trimTask(currentInstruction)
        when (val s = _currentState) {
            is AgentState.Running -> pillStep?.text = if (debugMode) "step ${s.step} / $maxSteps" else context.getString(R.string.overlay_running)
            is AgentState.WaitingForUser -> pillStep?.text = context.getString(R.string.overlay_waiting_answer)
            is AgentState.WaitingForHandoff -> pillStep?.text = context.getString(R.string.overlay_waiting_handoff)
            is AgentState.Paused -> pillStep?.text = if (debugMode) context.getString(R.string.overlay_paused_takeover) else context.getString(R.string.overlay_paused)
            else -> {}
        }
    }

    private fun startGlowAnimation() {
        glowAnimator = ObjectAnimator.ofFloat(chatHead, "alpha", 1f, 0.65f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dotAnimator = ObjectAnimator.ofFloat(pillDot, "alpha", 1f, 0.4f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun trimTask(text: String): String {
        if (text.isBlank()) return "OpenCyvis"
        return if (text.length > 20) text.take(20) + "…" else text
    }

    // ── Drag handling ──────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragging(view: View?, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging) {
                        if (dx * dx + dy * dy > dragSlop * dragSlop) isDragging = true
                        else return@setOnTouchListener false
                    }
                    params.x = initialX - dx
                    params.y = initialY + dy
                    try {
                        if (v.isAttachedToWindow) {
                            windowManager.updateViewLayout(v, params)
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"
    }
}
