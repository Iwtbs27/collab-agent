package ai.opencyvis.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import ai.opencyvis.backend.CaptureOps
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.SystemBackend

/**
 * Orchestration layer for screen capture.
 *
 * Handles JPEG encoding, base64 conversion, and VirtualDisplay bitmap passthrough.
 * Actual privileged capture is delegated to a [PrivilegeBackend].
 *
 * The [backend] field defaults to [SystemBackend] so existing callers work without
 * explicit initialization. Task 9 will set it properly during AgentService startup.
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    @Volatile var backend: PrivilegeBackend = SystemBackend()

    /**
     * Capture the default (physical) display and return as base64 JPEG.
     */
    fun captureBase64(): String? = captureBase64(displayId = 0)

    /**
     * Capture a specific display and return as base64 JPEG.
     * @param displayId 0 for physical display, or a virtual display ID
     * @param virtualDisplayBitmap Optional pre-captured bitmap from VirtualDisplayManager
     */
    fun captureBase64(displayId: Int = 0, virtualDisplayBitmap: Bitmap? = null): String? {
        val bytes = captureJpegBytes(displayId, virtualDisplayBitmap) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun captureJpegBytes(displayId: Int = 0, virtualDisplayBitmap: Bitmap? = null): ByteArray? {
        if (virtualDisplayBitmap != null) {
            return try {
                ImageUtil.processScreenshotToBytes(virtualDisplayBitmap)
            } finally {
                if (!virtualDisplayBitmap.isRecycled) virtualDisplayBitmap.recycle()
            }
        }
        return backend.captureScreen(displayId, 540, 85)
    }

    /**
     * Capture a specific display as a Bitmap.
     *
     * Used by AgentEngine as a fallback when VirtualDisplay ImageReader capture fails.
     * Goes through the backend: JPEG bytes are decoded back to Bitmap.
     * The roundtrip quality loss is acceptable for a fallback path.
     */
    fun captureBitmap(displayId: Int = 0): Bitmap? {
        val bytes = backend.captureScreen(displayId, 0, 100) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
