package ai.opencyvis.backend

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent

object InputOps {
    private const val TAG = "InputOps"

    private val inputManager: Any? by lazy {
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "input") as? IBinder ?: return@lazy null
            Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IInputManager", e)
            null
        }
    }

    private val injectMethod: java.lang.reflect.Method? by lazy {
        try {
            inputManager?.javaClass?.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve injectInputEvent", e)
            null
        }
    }

    fun injectInputEvent(event: InputEvent, displayId: Int, mode: Int): Boolean {
        return try {
            if (displayId != 0) {
                try {
                    event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                        .invoke(event, displayId)
                } catch (_: Exception) {}
            }
            val im = inputManager ?: return false
            val method = injectMethod ?: return false
            method.invoke(im, event, mode) as? Boolean ?: false
        } catch (e: Exception) {
            Log.e(TAG, "injectInputEvent failed", e)
            false
        }
    }

    fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        return injectInputEvent(event, displayId, mode)
    }
}
