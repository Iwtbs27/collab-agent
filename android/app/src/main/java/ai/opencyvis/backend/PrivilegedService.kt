package ai.opencyvis.backend

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.ByteArrayOutputStream

class PrivilegedService : IPrivilegedService.Stub() {

    companion object {
        private const val TAG = "PrivilegedService"

        fun pngToJpeg(png: ByteArray, maxWidth: Int, quality: Int): ByteArray? {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            val scaled = if (maxWidth > 0 && bmp.width > maxWidth) {
                val r = maxWidth.toFloat() / bmp.width
                Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * r).toInt(), true)
                    .also { bmp.recycle() }
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            scaled.recycle()
            return out.toByteArray()
        }
    }

    // ── Caller authorization (only the app's uid is allowed) ──
    private var authorizedCallerUid: Int = -1

    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (authorizedCallerUid == -1) {
            authorizedCallerUid = caller
            Log.i(TAG, "Authorized caller uid: $caller")
        } else if (caller != authorizedCallerUid) {
            throw SecurityException("Unauthorized caller uid=$caller, expected=$authorizedCallerUid")
        }
    }

    override fun getServiceUid(): Int = android.os.Process.myUid()

    // ── Input injection (delegates to InputOps) ──

    override fun injectMotionEvent(parceledEvent: ByteArray, displayId: Int, mode: Int): Boolean {
        checkCaller()
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(parceledEvent, 0, parceledEvent.size)
            parcel.setDataPosition(0)
            val event = MotionEvent.CREATOR.createFromParcel(parcel)
            val result = InputOps.injectInputEvent(event, displayId, mode)
            event.recycle()
            result
        } catch (e: Exception) {
            Log.e(TAG, "injectMotionEvent failed", e)
            false
        } finally {
            parcel.recycle()
        }
    }

    override fun injectKeyEvent(action: Int, keyCode: Int, repeat: Int, metaState: Int, displayId: Int, mode: Int): Boolean {
        checkCaller()
        return InputOps.injectKeyEvent(action, keyCode, repeat, metaState, displayId, mode)
    }

    // ── Screen capture (returns SharedMemory to avoid 1MB Binder limit) ──

    override fun captureScreen(displayId: Int, maxWidth: Int, quality: Int): SharedMemory? {
        checkCaller()
        Log.d(TAG, "captureScreen: displayId=$displayId, vdDisplayId=$vdDisplayId, hasImageReader=${vdImageReader != null}")
        val jpeg = if (displayId == vdDisplayId && vdImageReader != null) {
            val ir = captureFromImageReader(maxWidth, quality)
            if (ir != null) Log.d(TAG, "captureScreen: got ${ir.size} bytes from ImageReader")
            else Log.d(TAG, "captureScreen: ImageReader returned null, falling back to CaptureOps")
            ir
        } else {
            Log.d(TAG, "captureScreen: displayId mismatch or no ImageReader, using CaptureOps")
            null
        } ?: CaptureOps.captureScreen(displayId, maxWidth, quality) ?: return null
        val shm = SharedMemory.create("screenshot", jpeg.size)
        try {
            val buffer = shm.mapReadWrite()
            buffer.put(jpeg)
            SharedMemory.unmap(buffer)
            shm.setProtect(OsConstants.PROT_READ)
            return shm
        } catch (e: Exception) {
            shm.close()
            throw e
        }
    }

    private fun captureFromImageReader(maxWidth: Int, quality: Int): ByteArray? {
        // Prefer mirror VD's cached frame — the listener continuously caches the latest
        // frame from the mirror ImageReader, so this works regardless of the main VD's
        // surface state (SurfaceView vs ImageReader).
        val mirrorJpeg = cachedMirrorFrame
        if (mirrorJpeg != null) {
            Log.d(TAG, "captureFromImageReader: using mirror frame (${mirrorJpeg.size} bytes)")
            if (maxWidth > 0) {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(mirrorJpeg, 0, mirrorJpeg.size)
                    ?: return mirrorJpeg
                if (bitmap.width > maxWidth) {
                    val scaled = bitmapToJpeg(bitmap, maxWidth, quality)
                    bitmap.recycle()
                    return scaled
                }
                bitmap.recycle()
            }
            return mirrorJpeg
        }

        // Fallback: main VD's ImageReader (works when surface is not on SurfaceView)
        val reader = vdImageReader ?: return null
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (image != null) {
            try {
                val bitmap = imageToBitmap(image) ?: return cachedVdFrame
                val jpeg = bitmapToJpeg(bitmap, maxWidth, quality)
                bitmap.recycle()
                cachedVdFrame = jpeg
                return jpeg
            } finally {
                image.close()
            }
        }
        return cachedVdFrame
    }

    private fun imageToBitmap(image: android.media.Image): android.graphics.Bitmap? {
        val hardwareBuffer = image.hardwareBuffer ?: return imageToBitmapHeapCopy(image)
        try {
            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                hardwareBuffer, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
            )
            val copy = hwBitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            hwBitmap?.recycle()
            return copy ?: imageToBitmapHeapCopy(image)
        } finally {
            hardwareBuffer.close()
        }
    }

    private fun imageToBitmapHeapCopy(image: android.media.Image): android.graphics.Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (pixelStride != 4) return null
        val strideWidth = rowStride / pixelStride
        val bitmap = android.graphics.Bitmap.createBitmap(strideWidth, image.height, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (strideWidth > image.width) {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also { bitmap.recycle() }
        } else bitmap
    }

    private fun bitmapToJpeg(bitmap: android.graphics.Bitmap, maxWidth: Int, quality: Int): ByteArray {
        val scaled = if (maxWidth > 0 && bitmap.width > maxWidth) {
            val r = maxWidth.toFloat() / bitmap.width
            android.graphics.Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * r).toInt(), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    // ── Virtual Display (via IDisplayManager hidden API) ──
    //
    // We avoid DisplayManager.createVirtualDisplay() because on API 34+
    // it calls getDisplayIdToMirror() which requires UserManager and WindowManager
    // from the Context -- too many service dependencies for our FakeContext shell process.
    //
    // Instead, we call DisplayManagerGlobal.createVirtualDisplay() directly,
    // which is what DisplayManager delegates to internally but without the
    // Context-dependent checks.

    private val displayManager: DisplayManager by lazy {
        @SuppressLint("PrivateApi")
        val ctor = DisplayManager::class.java.getDeclaredConstructor(android.content.Context::class.java)
        ctor.isAccessible = true
        ctor.newInstance(FakeContext.instance)
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var vdImageReader: ImageReader? = null
    private var vdDisplayId: Int = -1
    @Volatile private var cachedVdFrame: ByteArray? = null  // Last successful JPEG capture from VD ImageReader

    // Mirror VD: always-on capture source that mirrors the main VD.
    // The main VD's surface switches between ImageReader (background) and SurfaceView (VIEW mode).
    // The mirror VD's surface is permanently attached to its own ImageReader, so agent
    // screenshots always get fresh frames regardless of the main VD's surface state.
    private var mirrorVd: VirtualDisplay? = null
    private var mirrorImageReader: ImageReader? = null
    private var mirrorHandlerThread: android.os.HandlerThread? = null
    @Volatile private var cachedMirrorFrame: ByteArray? = null
    @Volatile private var mirrorReady = false

    override fun createVirtualDisplay(name: String, width: Int, height: Int, dpi: Int, flags: Int): Int {
        checkCaller()
        return try {
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
            val vd = createVdViaGlobal(name, width, height, dpi, reader.surface, flags)
            if (vd == null) {
                reader.close()
                return -1
            }
            val displayId = vd.display?.displayId ?: -1
            if (displayId <= 0) {
                vd.release()
                reader.close()
                return -1
            }
            virtualDisplay = vd
            vdImageReader = reader
            vdDisplayId = displayId
            cachedVdFrame = null
            mirrorReady = false
            Log.i(TAG, "VD created: displayId=$displayId ${width}x${height}")

            // Create a mirror VD for always-on capture.
            // Uses DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface)
            // — a static @SystemApi that shell uid can call (CAPTURE_VIDEO_OUTPUT granted).
            createMirrorVd(displayId, width, height)

            // Drain any stale frames from ImageReader (Android may briefly route
            // the calling app's activity to the new VD before settling)
            Thread {
                try {
                    Thread.sleep(800)
                    cachedVdFrame = null
                    cachedMirrorFrame = null
                    var drained = 0
                    while (true) {
                        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: break
                        img.close()
                        drained++
                    }
                    if (drained > 0) Log.i(TAG, "Drained $drained stale frames from VD ImageReader")

                    // Move any OpenCyvis tasks off the VD. Android may route the calling
                    // app's activity to a newly created VD. Use shell-uid moveTaskToDisplay
                    // instead of HOME intent (which can affect Display 0 on some devices).
                    val tasks = DisplayOps.getTasksForDisplayAll(displayId)
                    for (task in tasks) {
                        if (task == null) continue
                        val taskId = DisplayOps.readIntField(task, "taskId") ?: continue
                        val baseActivity = DisplayOps.readComponent(task, "baseActivity")
                            ?: DisplayOps.readComponentFromGetter(task, "getBaseActivity")
                        val pkg = baseActivity?.packageName ?: ""
                        if (pkg.startsWith("ai.opencyvis")) {
                            DisplayOps.moveTaskToDisplay(taskId, 0)
                            Log.i(TAG, "VD init: moved OpenCyvis task $taskId ($pkg) back to Display 0")
                        }
                    }

                    // Now allow mirror listener to cache frames — VD should have
                    // non-OpenCyvis content (or be empty) at this point.
                    Thread.sleep(300)
                    cachedMirrorFrame = null
                    mirrorReady = true
                    Log.i(TAG, "VD init: mirror ready")
                } catch (_: Exception) {}
            }.start()

            displayId
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed: ${e.message}", e)
            -1
        }
    }

    /**
     * Create VirtualDisplay by calling DisplayManagerGlobal.createVirtualDisplay directly.
     * This bypasses DisplayManager.getDisplayIdToMirror() which requires UserManager.
     *
     * On API 35, the method signature uses VirtualDisplayConfig:
     *   VirtualDisplay createVirtualDisplay(Context, MediaProjection, VirtualDisplayConfig, Callback, Executor)
     *
     * On API 30-33, the method uses individual params:
     *   VirtualDisplay createVirtualDisplay(Context, MediaProjection, String, int, int, int, Surface, int, Callback, Handler, String)
     */
    @SuppressLint("PrivateApi")
    private fun createVdViaGlobal(
        name: String, width: Int, height: Int, dpi: Int,
        surface: Surface, flags: Int
    ): VirtualDisplay? {
        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val dmg = dmgClass.getMethod("getInstance").invoke(null)

            val methods = dmgClass.declaredMethods
                .filter { it.name == "createVirtualDisplay" }
                .sortedByDescending { it.parameterCount }

            for (method in methods) {
                val paramTypes = method.parameterTypes
                Log.d(TAG, "Trying DMG.createVirtualDisplay with ${paramTypes.size} params: " +
                    paramTypes.joinToString { it.simpleName })

                // Check if this overload uses VirtualDisplayConfig
                val hasConfig = paramTypes.any { it.name.contains("VirtualDisplayConfig") }
                if (hasConfig) {
                    // Build VirtualDisplayConfig via its Builder
                    val config = buildVirtualDisplayConfig(name, width, height, dpi, surface, flags)
                    if (config == null) {
                        Log.w(TAG, "Failed to build VirtualDisplayConfig, skipping this overload")
                        continue
                    }

                    method.isAccessible = true
                    val args = paramTypes.map { type ->
                        when {
                            type == android.content.Context::class.java -> FakeContext.instance
                            type.name.contains("MediaProjection") -> null
                            type.name.contains("VirtualDisplayConfig") -> config
                            type.name.contains("Callback") -> null
                            type == java.util.concurrent.Executor::class.java -> null
                            else -> null
                        }
                    }.toTypedArray()

                    try {
                        val result = method.invoke(dmg, *args)
                        if (result is VirtualDisplay) {
                            Log.i(TAG, "VD created via DMG+VirtualDisplayConfig")
                            return result
                        }
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        val cause = e.cause
                        Log.w(TAG, "DMG+Config threw: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    }
                } else {
                    // Old-style individual params
                    method.isAccessible = true
                    val args = mutableListOf<Any?>()
                    var intCounter = 0

                    for (type in paramTypes) {
                        val arg: Any? = when {
                            type == android.content.Context::class.java -> FakeContext.instance
                            type.name.contains("MediaProjection") -> null
                            type == String::class.java -> {
                                when (args.count { it is String }) {
                                    0 -> name
                                    else -> FakeContext.PACKAGE
                                }
                            }
                            type == Int::class.javaPrimitiveType || type == Int::class.java -> {
                                when (intCounter++) {
                                    0 -> width
                                    1 -> height
                                    2 -> dpi
                                    3 -> flags
                                    else -> 0
                                }
                            }
                            type == Surface::class.java -> surface
                            type == android.os.Handler::class.java -> null
                            type.name.contains("Callback") -> null
                            type == Long::class.javaPrimitiveType || type == Long::class.java -> 0L
                            type == Boolean::class.javaPrimitiveType || type == Boolean::class.java -> false
                            else -> null
                        }
                        args.add(arg)
                    }

                    try {
                        val result = method.invoke(dmg, *args.toTypedArray())
                        if (result is VirtualDisplay) {
                            Log.i(TAG, "VD created via DMG old-style (${paramTypes.size} params)")
                            return result
                        }
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        val cause = e.cause
                        Log.w(TAG, "DMG(${paramTypes.size}p) threw: ${cause?.javaClass?.simpleName}: ${cause?.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DisplayManagerGlobal approach failed entirely: ${e.message}")
        }

        // Ultimate fallback: try DisplayManager directly
        Log.w(TAG, "All Global paths failed, trying DisplayManager directly")
        return displayManager.createVirtualDisplay(name, width, height, dpi, surface, flags)
    }

    /**
     * Create a mirror VD that mirrors the main VD onto a dedicated ImageReader.
     * Uses the static DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface).
     * Shell uid has CAPTURE_VIDEO_OUTPUT permission, which is required for mirror VDs.
     */
    @SuppressLint("PrivateApi")
    private fun createMirrorVd(displayIdToMirror: Int, width: Int, height: Int) {
        try {
            // Half resolution — mirror is for agent screenshots, not user display.
            // Reduces GPU/CPU overhead significantly vs full-res encoding every frame.
            val mw = width / 2
            val mh = height / 2
            val mReader = ImageReader.newInstance(mw, mh, PixelFormat.RGBA_8888, 4)

            val ht = android.os.HandlerThread("MirrorImageReader").also { it.start() }
            val handler = android.os.Handler(ht.looper)
            mReader.setOnImageAvailableListener({ reader ->
                if (!mirrorReady) {
                    try { reader.acquireLatestImage()?.close() } catch (_: Exception) {}
                    return@setOnImageAvailableListener
                }
                val img = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val bitmap = imageToBitmap(img)
                    if (bitmap != null) {
                        val jpeg = bitmapToJpeg(bitmap, 0, 80)
                        bitmap.recycle()
                        cachedMirrorFrame = jpeg
                    }
                } finally {
                    img.close()
                }
            }, handler)

            val dmClass = android.hardware.display.DisplayManager::class.java

            // static VirtualDisplay createVirtualDisplay(String, int, int, int, Surface)
            val method = try {
                dmClass.getMethod(
                    "createVirtualDisplay",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Surface::class.java
                )
            } catch (_: NoSuchMethodException) {
                Log.w(TAG, "Mirror VD: static createVirtualDisplay not available")
                mReader.close()
                return
            }

            val mVd = method.invoke(
                null, "OpenCyvis-Mirror", width, height, displayIdToMirror, mReader.surface
            ) as? VirtualDisplay

            if (mVd == null) {
                Log.w(TAG, "Mirror VD: createVirtualDisplay returned null")
                mReader.close()
                return
            }

            mirrorVd = mVd
            mirrorImageReader = mReader
            mirrorHandlerThread = ht
            cachedMirrorFrame = null
            Log.i(TAG, "Mirror VD created for display $displayIdToMirror (mirror displayId=${mVd.display?.displayId})")
        } catch (e: Exception) {
            Log.w(TAG, "Mirror VD creation failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Build a VirtualDisplayConfig object using its Builder (API 33+).
     * VirtualDisplayConfig.Builder(String name, int width, int height, int densityDpi)
     *   .setFlags(int)
     *   .setSurface(Surface)
     *   .build()
     */
    @SuppressLint("PrivateApi")
    private fun buildVirtualDisplayConfig(
        name: String, width: Int, height: Int, dpi: Int,
        surface: Surface, flags: Int
    ): Any? {
        return try {
            val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
            val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")

            // Builder(String name, int width, int height, int densityDpi)
            val builder = builderClass.getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).newInstance(name, width, height, dpi)

            // setFlags(int)
            try {
                builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                    .invoke(builder, flags)
            } catch (_: NoSuchMethodException) {}

            // setSurface(Surface)
            try {
                builderClass.getMethod("setSurface", Surface::class.java)
                    .invoke(builder, surface)
            } catch (_: NoSuchMethodException) {}

            // build()
            builderClass.getMethod("build").invoke(builder)
        } catch (e: Exception) {
            Log.w(TAG, "buildVirtualDisplayConfig failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Launch the home/launcher on the VD to ensure something renders.
     * Called when VD has no Activity (e.g. after a failed am start) and
     * the ImageReader produces no new frames.
     */
    override fun ensureVdHasContent(displayId: Int) {
        checkCaller()
        val cmd = arrayOf(
            "am", "start", "--display", displayId.toString(),
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME"
        )
        try {
            val process = Runtime.getRuntime().exec(cmd)
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Log.i(TAG, "ensureVdHasContent: launched home on display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "ensureVdHasContent failed: ${e.message}")
        }
    }

    override fun forceStopPackage(packageName: String) {
        checkCaller()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Log.i(TAG, "forceStopPackage: $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "forceStopPackage failed: ${e.message}")
        }
    }

    override fun releaseVirtualDisplay() {
        try {
            mirrorVd?.release()
            mirrorImageReader?.close()
            mirrorHandlerThread?.quitSafely()
            mirrorVd = null
            mirrorImageReader = null
            mirrorHandlerThread = null
            cachedMirrorFrame = null
            mirrorReady = false
            virtualDisplay?.release()
            vdImageReader?.close()
            virtualDisplay = null
            vdImageReader = null
            vdDisplayId = -1
            cachedVdFrame = null
            Log.i(TAG, "VD released")
        } catch (e: Exception) {
            Log.e(TAG, "releaseVirtualDisplay failed", e)
        }
    }

    override fun setVirtualDisplaySurface(surface: Surface?) {
        try {
            val vd = virtualDisplay ?: return
            val targetSurface = surface ?: vdImageReader?.surface ?: return
            vd.surface = targetSurface
        } catch (e: Exception) {
            Log.e(TAG, "setVirtualDisplaySurface failed", e)
        }
    }

    // ── Activity launch on display (shell uid can start on any display) ──

    override fun startActivityOnDisplay(intentUri: String, displayId: Int): Boolean {
        checkCaller()
        return try {
            val intent = android.content.Intent.parseUri(intentUri, android.content.Intent.URI_INTENT_SCHEME)
            val pkg = intent.component?.packageName

            // Pre-move: if the app already has a task on a different display,
            // move it to the target display BEFORE launching. This prevents
            // Android from bringing the old task to front on Display 0.
            if (pkg != null) {
                val existing = DisplayOps.findTaskByPackage(pkg)
                if (existing != null && existing.second != displayId) {
                    val (taskId, fromDisplay) = existing
                    Log.i(TAG, "startActivityOnDisplay: pre-moving $pkg task $taskId from display $fromDisplay to $displayId")
                    DisplayOps.moveTaskToDisplay(taskId, displayId)
                    Thread.sleep(400)
                }
            }

            // Build am start command
            val args = mutableListOf("am", "start", "--display", displayId.toString())
            intent.component?.let {
                args.addAll(listOf("-n", it.flattenToString()))
            }
            intent.action?.let {
                args.addAll(listOf("-a", it))
            }
            intent.data?.let {
                args.addAll(listOf("-d", it.toString()))
            }
            intent.type?.let {
                args.addAll(listOf("-t", it))
            }
            intent.categories?.forEach { cat ->
                args.addAll(listOf("-c", cat))
            }
            val flags = intent.flags
            if (flags != 0) {
                args.addAll(listOf("-f", "0x${Integer.toHexString(flags)}"))
            }

            Log.i(TAG, "startActivityOnDisplay: ${args.joinToString(" ")}")
            val process = Runtime.getRuntime().exec(args.toTypedArray())
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                Log.w(TAG, "am start timed out")
                return false
            }
            val exitCode = process.exitValue()
            val error = errors
            if (exitCode != 0 || error.contains("Error")) {
                Log.e(TAG, "am start failed (exit=$exitCode): $error $output")
                false
            } else {
                Log.i(TAG, "startActivityOnDisplay success: $output")
                // Post-launch verification: if the task still didn't land on target
                // (fallback for edge cases where pre-move wasn't enough)
                if (pkg != null) {
                    Thread.sleep(200)
                    val onTarget = DisplayOps.getTasksForDisplayAll(displayId).any { t ->
                        if (t == null) return@any false
                        val comp = DisplayOps.readComponent(t, "baseActivity")
                            ?: DisplayOps.readComponentFromGetter(t, "getBaseActivity")
                        comp?.packageName == pkg
                    }
                    if (!onTarget) {
                        val existing = DisplayOps.findTaskByPackage(pkg)
                        if (existing != null && existing.second != displayId) {
                            val (taskId, fromDisplay) = existing
                            DisplayOps.moveTaskToDisplay(taskId, displayId)
                            Log.i(TAG, "startActivityOnDisplay: post-moved $pkg task $taskId from display $fromDisplay to $displayId")
                            Thread.sleep(200)
                            val relaunch = Runtime.getRuntime().exec(args.toTypedArray())
                            relaunch.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "startActivityOnDisplay failed", e)
            false
        }
    }

    // ── Task management (delegates to DisplayOps) ──

    override fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int {
        checkCaller()
        return DisplayOps.getTopTaskIdOnDisplay(displayId, callerPackage)
    }

    override fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        checkCaller()
        return DisplayOps.moveTaskToDisplay(taskId, targetDisplayId)
    }

    // ── IME policy (delegates to DisplayOps) ──

    override fun setDisplayImePolicy(displayId: Int, policy: Int) {
        checkCaller()
        DisplayOps.setDisplayImePolicy(displayId, policy)
    }

    // ── Capability probing ──

    override fun probeCapabilities(): Int {
        var mask = 0
        // Probe input injection
        try {
            if (InputOps.injectKeyEvent(
                    KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN, 0, 0, 0, 0
                )) {
                // If injecting UNKNOWN key succeeds, input injection works
            }
            // Even if the inject returns false, the method didn't throw — capability exists
            mask = mask or BackendCapabilities.CAP_INJECT_INPUT
        } catch (_: Exception) {}
        // Probe screen capture
        try {
            val bytes = CaptureOps.captureScreen(0, 100, 30)
            if (bytes != null) mask = mask or BackendCapabilities.CAP_CAPTURE_SCREEN
            // Secure capture depends on uid
            if (android.os.Process.myUid() <= 1000) mask = mask or BackendCapabilities.CAP_CAPTURE_SECURE
        } catch (_: Exception) {}
        // Probe VD creation (assume available if IDisplayManager is accessible)
        try {
            displayManager.getDisplay(0)
            mask = mask or BackendCapabilities.CAP_CREATE_VD
        } catch (_: Exception) {}
        return mask
    }

    override fun destroy() {
        releaseVirtualDisplay()
        Log.i(TAG, "PrivilegedService destroyed")
    }
}
