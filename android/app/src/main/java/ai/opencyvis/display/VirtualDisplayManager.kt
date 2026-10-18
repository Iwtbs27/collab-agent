package ai.opencyvis.display

import ai.opencyvis.backend.DisplayOps
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.SystemBackend
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

data class TaskSnapshot(
    val taskId: Int,
    val displayId: Int,
    val basePackage: String?,
    val topPackage: String?,
    val lastActiveTime: Long
) {
    fun containsPackage(packageName: String): Boolean {
        return basePackage == packageName || topPackage == packageName
    }
}

/**
 * Creates and manages a VirtualDisplay for agent operation.
 * The agent runs apps on this virtual screen while the user uses the physical screen.
 *
 * Supports task reparenting: moving Activity tasks between the VD and physical display
 * via moveTaskToDisplay() for view/takeover mode.
 */
class VirtualDisplayManager(
    private val context: Context,
    private val backend: PrivilegeBackend = SystemBackend()
) {

    companion object {
        private const val TAG = "VirtualDisplayManager"
        private const val DISPLAY_NAME = "OpenCyvis-Agent"

        // Default virtual display size (compact to reduce rendering + LLM token cost)
        const val DEFAULT_WIDTH = 540
        const val DEFAULT_HEIGHT = 1170
        const val DEFAULT_DPI = 240

        // Hidden VirtualDisplay flags (not in public API)
        // VirtualDisplay flags: mFlags=120533
        private const val VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 shl 4            // 16
        private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 shl 6         // 64
        private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 shl 7   // 128
        private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9  // 512
        private const val VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 shl 10              // 1024
        private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 shl 11   // 2048
        private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 shl 12      // 4096
        private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13 // 8192
        private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 shl 14            // 16384
        private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15 // 32768
        private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16 // 65536
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var remoteVdDisplayId: Int = -1
    private var imageReader: ImageReader? = null
    private var readerHandlerThread: HandlerThread? = null
    private var readerHandler: Handler? = null

    private val cacheLock = Any()
    private var cachedBitmap: Bitmap? = null

    /**
     * The display ID of the virtual display, or -1 if not created.
     */
    val displayId: Int
        get() = if (remoteVdDisplayId > 0) remoteVdDisplayId else virtualDisplay?.display?.displayId ?: -1

    val isCreated: Boolean
        get() = virtualDisplay != null || remoteVdDisplayId > 0

    val hasLocalDisplay: Boolean
        get() = virtualDisplay != null

    val width: Int
        get() = imageReader?.width ?: DEFAULT_WIDTH

    val height: Int
        get() = imageReader?.height ?: DEFAULT_HEIGHT

    // ── Task reparenting API (delegates to PrivilegeBackend) ──────────

    /**
     * Get the top non-OpenCyvis task ID running on the specified display.
     * Returns null if no suitable task is found.
     */
    fun getTopTaskIdOnDisplay(targetDisplayId: Int): Int? {
        val id = backend.getTopTaskIdOnDisplay(targetDisplayId, context.packageName)
        return if (id > 0) id else null
    }

    fun getTopTaskOnDisplay(targetDisplayId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100)
            .filter { it.displayId == targetDisplayId }
            .firstOrNull { !isOpenCyvisTask(it) }
    }

    fun getTaskSnapshot(taskId: Int): TaskSnapshot? {
        return getRunningTasks(limit = 100).firstOrNull { it.taskId == taskId }
    }

    fun toTaskSnapshot(taskInfo: Any?): TaskSnapshot? {
        return taskInfoToSnapshot(taskInfo)
    }

    fun getRunningTasks(limit: Int = 50): List<TaskSnapshot> {
        return try {
            DisplayOps.getRunningTaskInfos(limit, displayId)
                .mapNotNull { taskInfoToSnapshot(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get running tasks", e)
            emptyList()
        }
    }

    private fun isOpenCyvisTask(task: TaskSnapshot): Boolean {
        return task.containsPackage(context.packageName)
    }

    fun isOwnPackage(task: TaskSnapshot): Boolean {
        val pkg = context.packageName
        return task.topPackage == pkg || task.basePackage == pkg
                || task.topPackage == "ai.opencyvis" || task.basePackage == "ai.opencyvis"
    }

    private fun taskInfoToSnapshot(taskInfo: Any?): TaskSnapshot? {
        if (taskInfo == null) return null
        return try {
            val taskId = DisplayOps.readIntField(taskInfo, "taskId") ?: return null
            val displayId = DisplayOps.readIntField(taskInfo, "displayId") ?: -1
            val baseActivity = DisplayOps.readComponent(taskInfo, "baseActivity")
                ?: DisplayOps.readComponentFromGetter(taskInfo, "getBaseActivity")
            val topActivity = DisplayOps.readComponent(taskInfo, "topActivity")
                ?: DisplayOps.readComponentFromGetter(taskInfo, "getTopActivity")
            val lastActiveTime = DisplayOps.readLongField(taskInfo, "lastActiveTime") ?: 0L
            TaskSnapshot(
                taskId = taskId,
                displayId = displayId,
                basePackage = baseActivity?.packageName,
                topPackage = topActivity?.packageName,
                lastActiveTime = lastActiveTime
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse task snapshot: ${e.message}")
            null
        }
    }

    /**
     * Move a task to a different display.
     * Returns true on success.
     */
    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return backend.moveTaskToDisplay(taskId, targetDisplayId)
    }

    // ── Surface switching ──────────────────────────────────────────────

    /** True when the VD surface is set to SurfaceView (VIEW mode). */
    @Volatile
    var isUsingExternalSurface = false
        private set

    private var externalSurface: Surface? = null

    /**
     * Switch the VD's rendering target between SurfaceView and ImageReader.
     * Pass a SurfaceView's surface to render VD content on Display 0 (VIEW mode).
     * Pass null to switch back to ImageReader surface (BACKGROUND mode).
     *
     * ScreenCapture API (SurfaceFlinger) is unaffected — it captures directly
     * from the compositor regardless of which surface the VD renders to.
     */
    fun setSurface(surface: Surface?) {
        val vd = virtualDisplay
        if (vd != null) {
            // Local VD — switch surface directly
            if (surface != null) {
                externalSurface = surface
                isUsingExternalSurface = true
                vd.surface = surface
                Log.i(TAG, "VD surface switched to SurfaceView (local)")
            } else {
                externalSurface = null
                isUsingExternalSurface = false
                vd.surface = imageReader?.surface
                Log.i(TAG, "VD surface switched back to ImageReader (local)")
            }
        } else if (remoteVdDisplayId > 0) {
            // Remote VD — pass Surface cross-process to PrivilegedService
            if (surface != null) {
                externalSurface = surface
                isUsingExternalSurface = true
                backend.setVirtualDisplaySurface(surface)
                Log.i(TAG, "VD surface switched to SurfaceView (remote)")
            } else {
                externalSurface = null
                isUsingExternalSurface = false
                backend.setVirtualDisplaySurface(null)
                Log.i(TAG, "VD surface switched back to ImageReader (remote)")
            }
        }
    }

    /**
     * Capture a frame via ImageReader, temporarily switching the VD surface
     * from SurfaceView if needed. This bypasses SurfaceFlinger's FLAG_SECURE
     * blackout since the ImageReader is the VD's own rendering surface.
     *
     * Returns null if no frame is available within the timeout.
     */
    fun captureViaImageReader(timeoutMs: Long = 150): Bitmap? {
        val vd = virtualDisplay ?: return null
        val reader = imageReader ?: return null
        val wasExternal = isUsingExternalSurface
        val savedSurface = externalSurface

        try {
            if (wasExternal) {
                // Cache is stale (was populated before switching to SurfaceView) —
                // drop it so we wait for a fresh frame from the listener.
                val old: Bitmap?
                synchronized(cacheLock) {
                    old = cachedBitmap
                    cachedBitmap = null
                }
                old?.recycle()
                vd.surface = reader.surface
            }

            // Wait for a fresh frame
            val deadline = System.currentTimeMillis() + timeoutMs
            var bitmap: Bitmap? = captureLatestBitmap()
            while (bitmap == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(16) // ~1 frame at 60fps
                bitmap = captureLatestBitmap()
            }
            return bitmap
        } finally {
            if (wasExternal && savedSurface != null) {
                // Restore SurfaceView
                vd.surface = savedSurface
            }
        }
    }

    // ── VirtualDisplay lifecycle ─────────────────────────────────────────

    /**
     * Create the virtual display with an ImageReader surface.
     * Returns the displayId or -1 on failure.
     */
    fun create(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI
    ): Int {
        if (virtualDisplay != null) {
            Log.w(TAG, "Virtual display already created (displayId=$displayId)")
            return displayId
        }

        try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

            // ImageReader: 8 buffers, RGBA_8888 for direct Bitmap conversion.
            // Large buffer count prevents stall between agent steps — frames produced
            // between captures won't cause the producer to block.
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 8)

            // Drain frames as they arrive on a background thread and cache the latest
            // bitmap. acquireLatestImage() only returns NEW frames since last acquire,
            // so on a static screen subsequent calls return null. The cache lets capture
            // return the most recent frame even when the producer is idle.
            readerHandlerThread = HandlerThread("VDImageReader").also { it.start() }
            readerHandler = Handler(readerHandlerThread!!.looper)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "acquireLatestImage in listener failed: ${e.message}")
                    null
                } ?: return@setOnImageAvailableListener
                val bmp = try {
                    imageToBitmap(image)
                } finally {
                    image.close()
                }
                if (bmp != null) {
                    val old: Bitmap?
                    synchronized(cacheLock) {
                        old = cachedBitmap
                        cachedBitmap = bmp
                    }
                    old?.recycle()
                }
            }, readerHandler)

            // VD config flags — adapt based on backend capabilities
            var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

            if (backend is ai.opencyvis.backend.SystemBackend) {
                flags = flags or DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE or
                    VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                    VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                    VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
            } else {
                // For RemoteBackend (shell uid), DO NOT use AUTO_MIRROR.
                // Without TRUSTED permission, AUTO_MIRROR causes the VD to permanently
                // mirror display 0 instead of showing independently launched activities.
                flags = flags or
                    VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS or
                    VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED or
                    VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                    VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP or
                    VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED
            }

            // For RemoteBackend, create VD via the privileged service (shell uid has permissions).
            // For SystemBackend, create locally (same process has permissions).
            if (backend is ai.opencyvis.backend.RemoteBackend) {
                // Try progressively reduced flag sets until VD creation succeeds
                val flagSetsToTry = listOf(
                    flags,
                    // Without ALWAYS_UNLOCKED but keep TRUSTED
                    flags and VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED.inv(),
                    // Without TRUSTED but keep ALWAYS_UNLOCKED
                    flags and VIRTUAL_DISPLAY_FLAG_TRUSTED.inv(),
                    // Without both TRUSTED + ALWAYS_UNLOCKED
                    flags and (VIRTUAL_DISPLAY_FLAG_TRUSTED or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED).inv(),
                    // Minimal: PUBLIC + SUPPORTS_TOUCH + ROTATES_WITH_CONTENT + SYSTEM_DECORATIONS
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                        VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                        VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT or
                        VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                )
                var remoteDisplayId = -1
                for ((idx, flagSet) in flagSetsToTry.withIndex()) {
                    remoteDisplayId = backend.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, flagSet)
                    if (remoteDisplayId > 0) {
                        Log.i(TAG, "VD created with flag set $idx (flags=0x${Integer.toHexString(flagSet)})")
                        break
                    }
                    Log.w(TAG, "VD creation failed with flag set $idx (flags=0x${Integer.toHexString(flagSet)})")
                }
                if (remoteDisplayId <= 0) {
                    Log.e(TAG, "Remote createVirtualDisplay failed (id=$remoteDisplayId)")
                    destroy()
                    return -1
                }
                remoteVdDisplayId = remoteDisplayId

                // For RemoteBackend, the PrivilegedService keeps its own ImageReader
                // and captures frames locally in the shell process. We retrieve screenshots
                // via the Binder captureScreen() call. Cross-process Surface passing
                // is unreliable on non-TRUSTED displays.
                Log.i(TAG, "Remote VD created: displayId=$remoteDisplayId (capture via remote service)")

                backend.setDisplayImePolicy(remoteDisplayId, 0)
                // Note: keyguard dismissal is handled by the PrivilegedService (shell uid)
                // immediately after VD creation via `wm dismiss-keyguard`.

                return remoteDisplayId
            }

            virtualDisplay = dm.createVirtualDisplay(
                DISPLAY_NAME, width, height, dpi,
                imageReader!!.surface, flags
            )

            val id = virtualDisplay?.display?.displayId ?: -1
            if (id == -1) {
                Log.e(TAG, "createVirtualDisplay returned null display")
                destroy()
                return -1
            }

            Log.i(TAG, "Virtual display created: displayId=$id, ${width}x${height} @${dpi}dpi")

            // Set IME policy to LOCAL so the keyboard renders inside the VD
            // (visible through SurfaceView in VIEW/TAKEOVER mode).
            // Without this, keyboard appears on Display 0 and input doesn't reach the VD.
            // DISPLAY_IME_POLICY_LOCAL = 0
            backend.setDisplayImePolicy(id, 0)

            return id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create virtual display", e)
            destroy()
            return -1
        }
    }

    /**
     * Return a copy of the most recent frame captured by the OnImageAvailableListener.
     * Returns null if no frame has been received yet (e.g. immediately after create()
     * or right after switching back from an external surface).
     */
    fun captureLatestBitmap(): Bitmap? {
        synchronized(cacheLock) {
            val src = cachedBitmap ?: return null
            if (src.isRecycled) return null
            val cfg = src.config ?: Bitmap.Config.ARGB_8888
            return src.copy(cfg, false)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        try {
            val hardwareBuffer = image.hardwareBuffer
            if (hardwareBuffer != null) {
                try {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        ColorSpace.get(ColorSpace.Named.SRGB)
                    )
                    val copy = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBitmap?.recycle()
                    if (copy != null) return copy
                } finally {
                    hardwareBuffer.close()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "HardwareBuffer image copy failed, falling back: ${e.message}")
        }

        return imageToBitmapViaHeapCopy(image)
    }

    private fun imageToBitmapViaHeapCopy(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            if (rowStride <= 0 || pixelStride <= 0) {
                Log.w(TAG, "Invalid image plane stride: rowStride=$rowStride pixelStride=$pixelStride")
                return null
            }
            if (pixelStride != 4 || rowStride % pixelStride != 0) {
                Log.w(TAG, "Unsupported image plane layout: rowStride=$rowStride pixelStride=$pixelStride")
                return null
            }

            val strideWidth = rowStride / pixelStride
            if (strideWidth < image.width) {
                Log.w(TAG, "Image stride narrower than width: strideWidth=$strideWidth width=${image.width}")
                return null
            }

            val byteCount = rowStride * image.height
            val src = plane.buffer.duplicate()
            src.rewind()
            if (src.remaining() < byteCount) {
                Log.w(TAG, "Image buffer too small: remaining=${src.remaining()} expected=$byteCount")
                return null
            }

            val bitmap = Bitmap.createBitmap(
                strideWidth,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            val bytes = ByteArray(byteCount)
            src.get(bytes)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))

            // Crop if there's row padding
            if (strideWidth > image.width) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Image to Bitmap via heap copy", e)
            null
        }
    }

    /**
     * Destroy the virtual display and release resources.
     */
    fun destroy() {
        if (remoteVdDisplayId > 0) {
            backend.releaseVirtualDisplay()
            remoteVdDisplayId = -1
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        readerHandlerThread?.quitSafely()
        readerHandlerThread = null
        readerHandler = null
        synchronized(cacheLock) {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }
        Log.i(TAG, "Virtual display destroyed")
    }
}
