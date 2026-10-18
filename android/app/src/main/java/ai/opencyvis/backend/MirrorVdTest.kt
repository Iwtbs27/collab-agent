package ai.opencyvis.backend

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Looper
import android.os.SharedMemory
import android.util.Log

/**
 * Integration test for mirror VD capture.
 *
 * Run via:
 *   APK=$(adb shell pm path ai.opencyvis.standard | head -1 | sed 's/package://')
 *   adb shell "CLASSPATH=$APK app_process /system/bin ai.opencyvis.backend.MirrorVdTest"
 *
 * Requires FLAG_SECURE demo app installed:
 *   ai.opencyvis.test.flagsecure/.SecureActivity
 */
object MirrorVdTest {
    private const val TAG = "MirrorVdTest"

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        Log.i(TAG, "═══ Mirror VD Integration Test (uid=${android.os.Process.myUid()}) ═══")

        val svc = PrivilegedService()

        try {
            testCreation(svc)
            testCaptureBackground(svc)
            testCaptureViewMode(svc)
            testCaptureAfterView(svc)
            testFlagSecureBlack(svc)
            testFlagSecureViewMode(svc)
            testHandoffMoveToDisplay0(svc)
            testHandoffAutoDetect(svc)
            testHandoffMoveBack(svc)
        } finally {
            svc.releaseVirtualDisplay()
        }

        Log.i(TAG, "═══ Results: $passed passed, $failed failed ═══")
        if (failed > 0) {
            Log.e(TAG, "TEST SUITE FAILED")
            System.exit(1)
        } else {
            Log.i(TAG, "ALL TESTS PASSED")
            System.exit(0)
        }
    }

    // ── Helpers ──

    private fun assert(name: String, condition: Boolean, detail: String = "") {
        val msg = if (detail.isNotEmpty()) "$name ($detail)" else name
        if (condition) { passed++; Log.i(TAG, "  PASS: $msg") }
        else { failed++; Log.e(TAG, "  FAIL: $msg") }
    }

    private fun captureBytes(svc: PrivilegedService, displayId: Int): ByteArray? {
        val shm = svc.captureScreen(displayId, 540, 80) ?: return null
        return try {
            val buf = shm.mapReadOnly()
            val bytes = ByteArray(shm.size)
            buf.get(bytes)
            SharedMemory.unmap(buf)
            bytes
        } finally {
            shm.close()
        }
    }

    private fun isBlack(jpeg: ByteArray, threshold: Int = 18, samples: Int = 20): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return false
        var blackCount = 0
        for (i in 0 until samples) {
            val x = (bitmap.width * (i + 1)) / (samples + 1)
            val pixel = bitmap.getPixel(x, bitmap.height / 2)
            if (Color.red(pixel) < 10 && Color.green(pixel) < 10 && Color.blue(pixel) < 10) {
                blackCount++
            }
        }
        bitmap.recycle()
        return blackCount >= threshold
    }

    private fun launchOnDisplay(displayId: Int, component: String) {
        Runtime.getRuntime().exec(arrayOf(
            "am", "start", "--display", displayId.toString(), "-n", component
        )).waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun getTopTask(svc: PrivilegedService, displayId: Int): Int {
        return svc.getTopTaskIdOnDisplay(displayId, "com.android.shell")
    }

    private fun consecutiveBlackFrames(svc: PrivilegedService, displayId: Int, count: Int): Int {
        var consecutive = 0
        for (i in 0 until count) {
            val jpeg = captureBytes(svc, displayId)
            if (jpeg != null && isBlack(jpeg)) consecutive++ else consecutive = 0
            Thread.sleep(200)
        }
        return consecutive
    }

    // ── Test cases ──

    private var displayId = -1
    private lateinit var fakeReader: ImageReader

    private fun testCreation(svc: PrivilegedService) {
        Log.i(TAG, "── Test 1: VD + Mirror creation ──")
        displayId = svc.createVirtualDisplay("TestVD", 1080, 1920, 480, 0)
        assert("Main VD created", displayId > 0, "displayId=$displayId")

        launchOnDisplay(displayId, "com.android.settings/.Settings")
        Thread.sleep(3000)

        fakeReader = ImageReader.newInstance(1080, 1920, PixelFormat.RGBA_8888, 2)
    }

    private fun testCaptureBackground(svc: PrivilegedService) {
        Log.i(TAG, "── Test 2: Capture in background mode (mirror) ──")
        val jpeg = captureBytes(svc, displayId)
        assert("Capture in background mode", jpeg != null, "${jpeg?.size ?: 0} bytes")
        if (jpeg != null) {
            assert("Frame is not black (Settings visible)", !isBlack(jpeg))
        }
    }

    private fun testCaptureViewMode(svc: PrivilegedService) {
        Log.i(TAG, "── Test 3: Capture during VIEW mode (surface on SurfaceView) ──")
        svc.setVirtualDisplaySurface(fakeReader.surface)
        Thread.sleep(500)
        val jpeg = captureBytes(svc, displayId)
        assert("Capture during VIEW mode", jpeg != null, "${jpeg?.size ?: 0} bytes")
        if (jpeg != null) {
            assert("VIEW mode frame is not black", !isBlack(jpeg))
        }
    }

    private fun testCaptureAfterView(svc: PrivilegedService) {
        Log.i(TAG, "── Test 4: Capture after returning from VIEW mode ──")
        svc.setVirtualDisplaySurface(null)
        Thread.sleep(500)
        val jpeg = captureBytes(svc, displayId)
        assert("Capture after VIEW exit", jpeg != null, "${jpeg?.size ?: 0} bytes")
    }

    private fun testFlagSecureBlack(svc: PrivilegedService) {
        Log.i(TAG, "── Test 5: FLAG_SECURE app produces black frames ──")
        launchOnDisplay(displayId, "ai.opencyvis.test.flagsecure/.SecureActivity")
        Thread.sleep(2000)
        val jpeg = captureBytes(svc, displayId)
        assert("FLAG_SECURE capture not null", jpeg != null, "${jpeg?.size ?: 0} bytes")
        if (jpeg != null) {
            assert("FLAG_SECURE frame is black", isBlack(jpeg))
        }
    }

    private fun testFlagSecureViewMode(svc: PrivilegedService) {
        Log.i(TAG, "── Test 6: FLAG_SECURE + VIEW mode (mirror still captures) ──")
        svc.setVirtualDisplaySurface(fakeReader.surface)
        Thread.sleep(500)
        val jpeg = captureBytes(svc, displayId)
        assert("FLAG_SECURE in VIEW mode not null", jpeg != null, "${jpeg?.size ?: 0} bytes")
        svc.setVirtualDisplaySurface(null)
        fakeReader.close()
    }

    // ── FLAG_SECURE handoff tests (Bug 2 mechanics) ──

    private var movedTaskId = -1

    private fun testHandoffMoveToDisplay0(svc: PrivilegedService) {
        Log.i(TAG, "── Test 7: Consecutive black frames trigger + move task to Display 0 ──")

        // FLAG_SECURE app should still be on VD from Test 5
        val blacks = consecutiveBlackFrames(svc, displayId, 4)
        assert("3+ consecutive black frames detected", blacks >= 3, "$blacks consecutive")

        // Record the task on VD, then move it to Display 0
        movedTaskId = getTopTask(svc, displayId)
        assert("Got top task on VD", movedTaskId > 0, "taskId=$movedTaskId")

        val moved = svc.moveTaskToDisplay(movedTaskId, 0)
        assert("moveTaskToDisplay(0) succeeded", moved)
        Thread.sleep(1000)

        // VD should no longer have the FLAG_SECURE task as top
        val vdTopAfter = getTopTask(svc, displayId)
        assert("VD top task changed after move", vdTopAfter != movedTaskId,
            "vdTop=$vdTopAfter, moved=$movedTaskId")

        // VD capture should no longer be black (home or empty)
        val jpegAfterMove = captureBytes(svc, displayId)
        if (jpegAfterMove != null) {
            assert("VD frame not black after move", !isBlack(jpegAfterMove))
        }
    }

    private fun testHandoffAutoDetect(svc: PrivilegedService) {
        Log.i(TAG, "── Test 8: Auto-detect task leaving Display 0 ──")

        // Task should be on Display 0 now
        val d0Top = getTopTask(svc, 0)
        Log.i(TAG, "Display 0 top task: $d0Top, moved task: $movedTaskId")

        // Simulate user pressing Home on Display 0 (launches launcher over FLAG_SECURE app)
        Runtime.getRuntime().exec(arrayOf(
            "input", "keyevent", "KEYCODE_HOME"
        )).waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(1500)

        // After Home press, the moved task should no longer be top on Display 0
        val d0TopAfter = getTopTask(svc, 0)
        assert("Task no longer top on Display 0 after Home",
            d0TopAfter != movedTaskId || movedTaskId <= 0,
            "d0Top=$d0TopAfter, moved=$movedTaskId")
    }

    private fun testHandoffMoveBack(svc: PrivilegedService) {
        Log.i(TAG, "── Test 9: Move task back to VD ──")
        if (movedTaskId <= 0) {
            assert("Skipped (no task to move back)", true, "movedTaskId=$movedTaskId")
            return
        }

        val moved = svc.moveTaskToDisplay(movedTaskId, displayId)
        assert("moveTaskToDisplay(VD) succeeded", moved)
        Thread.sleep(1000)

        val vdTop = getTopTask(svc, displayId)
        assert("Task back on VD", vdTop == movedTaskId,
            "vdTop=$vdTop, expected=$movedTaskId")
    }
}
