package ai.opencyvis.backend

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-level screen capture operations using reflection.
 *
 * Strategy chain:
 * 1. ScreenCapture.capture() public API (API 36+, uses displayId, no token needed)
 * 2. ScreenCaptureInternal.captureDisplay() hidden API (API 36+, needs display token)
 * 3. SurfaceControl.screenshot() hidden API (API 30-35, needs display token)
 * 4. `screencap` command fallback (always works for system apps)
 *
 * For virtual displays (displayId != 0), only the API 36+ ScreenCapture API works.
 */
object CaptureOps {

    private const val TAG = "CaptureOps"

    /**
     * Capture a display and return JPEG bytes.
     * Strategy chain: ScreenCapture API 36+ -> ScreenCaptureInternal -> SurfaceControl -> screencap command.
     * For virtual displays (displayId != 0), only the API 36+ ScreenCapture API works.
     *
     * @param displayId 0 for physical display, or a virtual display ID
     * @param maxWidth  Maximum width for the output; 0 means no scaling
     * @param quality   JPEG quality (0-100)
     */
    fun captureScreen(displayId: Int, maxWidth: Int, quality: Int): ByteArray? {
        val bitmap = captureBitmap(displayId) ?: return null
        return try {
            bitmapToJpeg(bitmap, maxWidth, quality)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Capture a specific display as a Bitmap.
     *
     * For virtual displays (displayId != 0), only the API 36+ ScreenCapture API
     * supports targeting by displayId. The older fallback methods always capture the
     * physical display, so we skip them.
     */
    fun captureBitmap(displayId: Int = 0): Bitmap? {
        return try {
            val bitmap = captureViaScreenCaptureApi(displayId)
            if (bitmap != null) return bitmap

            if (displayId != 0) {
                Log.d(TAG, "ScreenCapture API unavailable for virtual display $displayId, skipping physical-only fallbacks")
                return null
            }

            // Physical display: try all fallbacks
            captureViaScreenCaptureInternal()
                ?: captureViaOldSurfaceControl()
                ?: captureViaCommand()
        } catch (e: Exception) {
            Log.e(TAG, "All screenshot methods failed", e)
            null
        }
    }

    // ---- Strategy 1: API 36+ ScreenCapture.capture() ----

    /**
     * API 36+: ScreenCapture.capture(ScreenCaptureParams, Executor, OutcomeReceiver)
     *
     * This is the preferred approach -- uses displayId (int) instead of display token,
     * goes through IWindowManager.screenCapture(), and returns a HardwareBuffer
     * directly (zero-copy from SurfaceFlinger).
     *
     * Requires READ_FRAME_BUFFER permission (granted via privapp-permissions).
     * Gated behind FlaggedApi(readback_screenshot) -- may not be available on all builds.
     */
    private fun captureViaScreenCaptureApi(displayId: Int = 0): Bitmap? {
        return try {
            val scClass = Class.forName("android.window.ScreenCapture")
            val paramsClass = Class.forName(
                "android.window.ScreenCapture\$ScreenCaptureParams"
            )
            val builderClass = Class.forName(
                "android.window.ScreenCapture\$ScreenCaptureParams\$Builder"
            )
            val resultClass = Class.forName(
                "android.window.ScreenCapture\$ScreenCaptureResult"
            )

            // Build params: new ScreenCaptureParams.Builder(displayId).build()
            val builder = builderClass.getConstructor(Int::class.javaPrimitiveType)
                .newInstance(displayId)
            val params = builderClass.getMethod("build").invoke(builder)

            // We need to call ScreenCapture.capture(params, executor, receiver)
            // receiver is OutcomeReceiver<ScreenCaptureResult, Exception>
            // Use CountDownLatch to make the async call synchronous
            val latch = CountDownLatch(1)
            val resultHolder = AtomicReference<Any?>(null)
            val errorHolder = AtomicReference<Exception?>(null)

            // Create OutcomeReceiver proxy
            val receiverClass = Class.forName("android.os.OutcomeReceiver")
            val receiver = Proxy.newProxyInstance(
                receiverClass.classLoader,
                arrayOf(receiverClass),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "onResult" -> {
                            resultHolder.set(args?.get(0))
                            latch.countDown()
                        }
                        "onError" -> {
                            errorHolder.set(args?.get(0) as? Exception)
                            latch.countDown()
                        }
                        // Default methods from Object (toString, hashCode, equals)
                        "toString" -> return@InvocationHandler "ScreenCaptureReceiver"
                        "hashCode" -> return@InvocationHandler System.identityHashCode(this)
                        "equals" -> return@InvocationHandler this === args?.get(0)
                    }
                    null
                }
            )

            val executor = Executors.newSingleThreadExecutor()
            try {
                // ScreenCapture.capture(ScreenCaptureParams, Executor, OutcomeReceiver)
                val captureMethod = scClass.getMethod(
                    "capture",
                    paramsClass,
                    java.util.concurrent.Executor::class.java,
                    receiverClass
                )
                captureMethod.invoke(null, params, executor, receiver)

                // Wait for result (5 second timeout)
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    Log.w(TAG, "ScreenCapture.capture() timed out")
                    return null
                }

                val error = errorHolder.get()
                if (error != null) {
                    Log.d(TAG, "ScreenCapture.capture() error: ${error.message}")
                    return null
                }

                val result = resultHolder.get() ?: return null

                // Extract HardwareBuffer and ColorSpace from ScreenCaptureResult
                val hwBuffer = resultClass.getMethod("getHardwareBuffer")
                    .invoke(result) as? HardwareBuffer ?: return null
                val colorSpace = resultClass.getMethod("getColorSpace")
                    .invoke(result) as? ColorSpace

                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                hwBuffer.close()

                if (bitmap != null) {
                    // Convert from hardware bitmap to software bitmap for JPEG encoding
                    val swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    Log.d(TAG, "ScreenCapture API succeeded: ${swBitmap?.width}x${swBitmap?.height}")
                    swBitmap
                } else null
            } finally {
                executor.shutdown()
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "ScreenCapture API not available (class not found)")
            null
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "ScreenCapture API not available (method not found): ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "ScreenCapture API failed: ${e.message}")
            null
        }
    }

    // ---- Strategy 2: API 36+ ScreenCaptureInternal ----

    /**
     * API 36+: ScreenCaptureInternal.captureDisplay(DisplayCaptureArgs)
     * DisplayCaptureArgs needs a display token from DisplayControl (server-side class,
     * accessible via same-process classloading for system_server, but for platform-signed
     * apps we try loading it from the boot classpath).
     */
    private fun captureViaScreenCaptureInternal(): Bitmap? {
        return try {
            // DisplayControl is in services.jar -- try to access via reflection
            val dcClass = try {
                Class.forName("com.android.server.display.DisplayControl")
            } catch (_: ClassNotFoundException) {
                null
            }

            val displayToken: IBinder? = if (dcClass != null) {
                val ids = dcClass.getMethod("getPhysicalDisplayIds").invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    dcClass.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                        .invoke(null, ids[0]) as? IBinder
                } else null
            } else {
                getDisplayTokenViaSurfaceControl()
            }

            if (displayToken == null) return null

            val sciClass = Class.forName("android.window.ScreenCaptureInternal")
            val builderClass = Class.forName(
                "android.window.ScreenCaptureInternal\$DisplayCaptureArgs\$Builder"
            )
            val builder = builderClass.getConstructor(IBinder::class.java)
                .newInstance(displayToken)
            val args = builderClass.getMethod("build").invoke(builder)
            val argsClass = Class.forName(
                "android.window.ScreenCaptureInternal\$DisplayCaptureArgs"
            )
            val result = sciClass.getMethod("captureDisplay", argsClass).invoke(null, args)
            if (result != null) {
                Log.d(TAG, "ScreenCaptureInternal succeeded")
                extractBitmap(result)
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "ScreenCaptureInternal not available: ${e.message}")
            null
        }
    }

    // ---- Strategy 3: API 30-35 SurfaceControl ----

    /**
     * API 30-35: SurfaceControl.screenshot() variants
     */
    private fun captureViaOldSurfaceControl(): Bitmap? {
        return try {
            val sc = Class.forName("android.view.SurfaceControl")
            val token = getDisplayTokenViaSurfaceControl() ?: return null

            // screenshot(IBinder, Rect, int, int, int)
            try {
                val m = sc.getMethod("screenshot",
                    IBinder::class.java, Rect::class.java,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                val r = m.invoke(null, token, Rect(), 0, 0, 0)
                if (r != null) return extractBitmap(r)
            } catch (_: NoSuchMethodException) {}

            // screenshot(IBinder, int, int)
            try {
                val m = sc.getMethod("screenshot",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                val r = m.invoke(null, token, 0, 0)
                if (r != null) return extractBitmap(r)
            } catch (_: NoSuchMethodException) {}

            null
        } catch (e: Exception) {
            Log.d(TAG, "Old SurfaceControl approach failed: ${e.message}")
            null
        }
    }

    // ---- Strategy 4: screencap command ----

    /**
     * Fallback: execute `screencap` command.
     * Works for all system apps. Outputs PNG via pipe.
     */
    private fun captureViaCommand(): Bitmap? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
            val pngBytes = process.inputStream.use { it.readBytes() }
            process.waitFor()

            if (pngBytes.isEmpty()) {
                Log.e(TAG, "screencap returned empty output")
                return null
            }

            val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
            if (bitmap != null) {
                Log.d(TAG, "screencap command succeeded: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.e(TAG, "Failed to decode screencap output (${pngBytes.size} bytes)")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "screencap command failed", e)
            null
        }
    }

    // ---- Shared helpers ----

    private fun getDisplayTokenViaSurfaceControl(): IBinder? {
        val sc = Class.forName("android.view.SurfaceControl")
        // getInternalDisplayToken (API 31-35)
        try {
            return sc.getMethod("getInternalDisplayToken").invoke(null) as? IBinder
        } catch (_: NoSuchMethodException) {}
        // getBuiltInDisplay(int) (API 30)
        try {
            return sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                .invoke(null, 0) as? IBinder
        } catch (_: NoSuchMethodException) {}
        return null
    }

    private fun extractBitmap(result: Any): Bitmap? {
        if (result is Bitmap) return result
        val cls = result.javaClass
        // asBitmap()
        try {
            val b = cls.getMethod("asBitmap").invoke(result) as? Bitmap
            if (b != null) return b
        } catch (_: NoSuchMethodException) {}
        // getHardwareBuffer() -> Bitmap.wrapHardwareBuffer()
        try {
            val hwb = cls.getMethod("getHardwareBuffer").invoke(result) ?: return null
            val hwbClass = Class.forName("android.hardware.HardwareBuffer")
            return Bitmap::class.java.getMethod(
                "wrapHardwareBuffer", hwbClass, ColorSpace::class.java
            ).invoke(null, hwb, null) as? Bitmap
        } catch (_: NoSuchMethodException) {}
        return null
    }

    /**
     * Scale a bitmap (if needed) and compress to JPEG bytes.
     *
     * @param bitmap   Source bitmap (not recycled by this method)
     * @param maxWidth Maximum width; 0 means no scaling
     * @param quality  JPEG quality (0-100)
     */
    private fun bitmapToJpeg(bitmap: Bitmap, maxWidth: Int, quality: Int): ByteArray {
        val scaled = if (maxWidth > 0 && bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, maxWidth, newHeight, true)
        } else {
            bitmap
        }
        return try {
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        } finally {
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        }
    }
}
