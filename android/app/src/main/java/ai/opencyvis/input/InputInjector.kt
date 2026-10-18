package ai.opencyvis.input

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.SystemBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class InputSequenceResult {
    var success: Boolean = true
        private set

    fun record(injected: Boolean) {
        if (!injected) success = false
    }
}

/**
 * Injects input events using the hidden InputManager.injectInputEvent() API.
 * Requires the app to be signed with the platform key.
 */
class InputInjector(
    private val context: Context,
    private val displayId: Int = 0,
    displaySize: Point? = null,
    private val backend: PrivilegeBackend = SystemBackend()
) {

    companion object {
        private const val TAG = "InputInjector"

        // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val INJECT_MODE_ASYNC = 0
        // InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH = 2
        private const val INJECT_MODE_WAIT_FOR_FINISH = 2

        /**
         * Inject a MotionEvent to a specific display.
         * Used by ViewActivity for TAKEOVER touch forwarding.
         * Coordinates are 1:1 since VD matches physical display resolution.
         */
        fun injectToDisplay(context: Context, event: MotionEvent, targetDisplayId: Int): Boolean {
            return try {
                // Get InputManager instance
                val im = context.getSystemService(Context.INPUT_SERVICE)
                    ?: Class.forName("android.hardware.input.InputManager")
                        .getMethod("getInstance").invoke(null)
                    ?: return false

                // Set displayId on the event
                try {
                    event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .invoke(event, targetDisplayId)
                } catch (_: Exception) {}

                event.source = InputDevice.SOURCE_TOUCHSCREEN

                // Inject
                val method = im.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(im, event, INJECT_MODE_WAIT_FOR_FINISH) as? Boolean ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject event to display $targetDisplayId", e)
                false
            }
        }

        /**
         * Inject a KeyEvent to a specific display.
         * Used by ViewActivity in TAKEOVER mode to forward keyboard input to VD.
         */
        fun injectKeyToDisplay(context: Context, event: KeyEvent, targetDisplayId: Int): Boolean {
            return try {
                val im = context.getSystemService(Context.INPUT_SERVICE)
                    ?: Class.forName("android.hardware.input.InputManager")
                        .getMethod("getInstance").invoke(null)
                    ?: return false

                // Clone the event and set displayId
                val clone = KeyEvent(event)
                try {
                    clone.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .invoke(clone, targetDisplayId)
                } catch (_: Exception) {}

                val method = im.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                method.invoke(im, clone, INJECT_MODE_WAIT_FOR_FINISH) as? Boolean ?: false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject key event to display $targetDisplayId", e)
                false
            }
        }

        // Key code mappings matching Python key names
        val KEY_MAP = mapOf(
            "back" to KeyEvent.KEYCODE_BACK,
            "home" to KeyEvent.KEYCODE_HOME,
            "enter" to KeyEvent.KEYCODE_ENTER,
            "recent" to KeyEvent.KEYCODE_APP_SWITCH
        )
    }

    private val coordinateMapper = CoordinateMapper(context, displaySize)
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // Real touchscreen device ID for realistic MotionEvents (reference approach)
    private val touchDeviceId: Int by lazy {
        InputDevice.getDeviceIds()
            .firstOrNull { id ->
                InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) == true
            } ?: 0
    }

    private fun obtainTouchEvent(
        downTime: Long, eventTime: Long, action: Int, x: Float, y: Float
    ): MotionEvent {
        val pp = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val pc = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            pressure = 1.0f; size = 1.0f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(pp), arrayOf(pc),
            0, 0, 1.0f, 1.0f,
            touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    private fun injectEvent(event: InputEvent): Boolean {
        return injectEvent(event, INJECT_MODE_WAIT_FOR_FINISH)
    }

    private fun injectEventAsync(event: InputEvent): Boolean {
        return injectEvent(event, INJECT_MODE_ASYNC)
    }

    private fun injectEvent(event: InputEvent, mode: Int): Boolean {
        return try {
            val injected = backend.injectInputEvent(event, displayId, mode)
            if (!injected) {
                Log.w(TAG, "Input injection rejected: displayId=$displayId event=${event.javaClass.simpleName}")
            }
            injected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject input event", e)
            false
        }
    }

    /**
     * Tap at normalized coordinates (0-1000).
     */
    fun tap(nx: Int, ny: Int): Boolean {
        val pixel = coordinateMapper.normalizedToPixel(nx, ny)
        return tapPixel(pixel.x.toFloat(), pixel.y.toFloat())
    }

    /**
     * Tap at pixel coordinates.
     */
    private fun tapPixel(x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()

        val downEvent = obtainTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        val upEvent = obtainTouchEvent(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y)

        val result = injectEvent(downEvent) && injectEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()
        return result
    }

    /**
     * Long press at normalized coordinates (0-1000).
     */
    suspend fun longPress(nx: Int, ny: Int, durationMs: Long = 1000): Boolean {
        val pixel = coordinateMapper.normalizedToPixel(nx, ny)
        val x = pixel.x.toFloat()
        val y = pixel.y.toFloat()
        val downTime = SystemClock.uptimeMillis()

        val downEvent = obtainTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        val upEvent = obtainTouchEvent(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x, y)

        val downResult = injectEvent(downEvent)
        downEvent.recycle()

        // Wait for the long press duration
        delay(durationMs)

        val upResult = injectEvent(upEvent)
        upEvent.recycle()

        return downResult && upResult
    }

    /**
     * Swipe from (nx1,ny1) to (nx2,ny2) in normalized coordinates.
     */
    suspend fun swipe(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long = 300): Boolean {
        val start = coordinateMapper.normalizedToPixel(nx1, ny1)
        val end = coordinateMapper.normalizedToPixel(nx2, ny2)
        return swipePixel(
            start.x.toFloat(), start.y.toFloat(),
            end.x.toFloat(), end.y.toFloat(),
            durationMs
        )
    }

    private suspend fun swipePixel(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val mainHandler = Handler(Looper.getMainLooper())

        // DOWN (synchronous, wait for finish)
        val downEvent = obtainTouchEvent(downTime, downTime, MotionEvent.ACTION_DOWN, x1, y1)
        val downOk = injectEvent(downEvent)
        downEvent.recycle()
        if (!downOk) return false

        // MOVE via ValueAnimator on main thread (vsync-driven, async injection)
        val moveOk = suspendCancellableCoroutine { cont ->
            mainHandler.post {
                var allInjected = true
                val animator = ObjectAnimator.ofFloat(0f, 1f)
                animator.interpolator = AccelerateDecelerateInterpolator()
                animator.duration = durationMs
                animator.addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    val x = x1 + (x2 - x1) * fraction
                    val y = y1 + (y2 - y1) * fraction
                    val moveEvent = obtainTouchEvent(
                        downTime, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_MOVE, x, y
                    )
                    if (!injectEventAsync(moveEvent)) allInjected = false
                    moveEvent.recycle()
                }
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        cont.resume(allInjected)
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        cont.resume(false)
                    }
                })
                cont.invokeOnCancellation { animator.cancel() }
                animator.start()
            }
        }

        // UP (synchronous, wait for finish)
        val upEvent = obtainTouchEvent(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2)
        val upOk = injectEvent(upEvent)
        upEvent.recycle()

        return downOk && moveOk && upOk
    }

    /**
     * Send a key event by name (back, home, enter, recent).
     */
    fun keyEvent(keyName: String): Boolean {
        val keyCode = KEY_MAP[keyName.lowercase()] ?: return false
        val downTime = SystemClock.uptimeMillis()

        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }
        val up = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, keyCode, 0).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }

        // Use reflection to set source since KeyEvent.source setter may not be directly available
        return injectEvent(down) && injectEvent(up)
    }

    /**
     * Type text. For ASCII text, sends key events. For CJK/complex text,
     * uses clipboard paste (Ctrl+V).
     *
     * If the target view doesn't handle key events (e.g. a custom dialpad),
     * the injection will silently do nothing. The LLM detects this on the
     * next screenshot and adapts (e.g. taps individual buttons instead).
     */
    suspend fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false

        // Check if text contains non-ASCII characters
        val hasNonAscii = text.any { it.code > 127 }

        return if (hasNonAscii) {
            typeViaClipboard(text)
        } else {
            typeViaKeyEvents(text)
        }
    }

    private suspend fun typeViaKeyEvents(text: String): Boolean {
        val kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = kcm.getEvents(text.toCharArray()) ?: return typeViaClipboard(text)

        var success = true
        for (event in events) {
            val e = KeyEvent.changeFlags(event, event.flags)
            if (!injectEvent(e)) success = false
            delay(5)
        }
        return success
    }

    private fun typeViaClipboard(text: String): Boolean {
        clipboardManager.setPrimaryClip(ClipData.newPlainText("text", text))

        // KEYCODE_PASTE (279) is handled by Android framework (TextView.onKeyDown)
        // directly — more reliable than Ctrl+V which depends on the focused view
        // intercepting the key combo. Matches scrcpy's approach.
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PASTE, 0)
        val up = KeyEvent(downTime, downTime + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PASTE, 0)
        return injectEvent(down) && injectEvent(up)
    }

    /**
     * Get the current display size via the coordinate mapper.
     */
    fun getDisplaySize(): Point = coordinateMapper.getDisplaySize()
}
