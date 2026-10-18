package ai.opencyvis.backend

import android.os.Looper
import android.util.Log

/**
 * Minimal test for VD app launch with pre-move fix.
 *
 * Run via:
 *   APK=$(adb shell pm path ai.opencyvis.standard | head -1 | sed 's/package://')
 *   adb shell "CLASSPATH=$APK app_process /system/bin ai.opencyvis.backend.VdLaunchTest"
 */
object VdLaunchTest {
    private const val TAG = "VdLaunchTest"

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        Log.i(TAG, "═══ VD Launch Test (uid=${android.os.Process.myUid()}) ═══")
        println("═══ VD Launch Test (uid=${android.os.Process.myUid()}) ═══")

        val svc = PrivilegedService()

        try {
            // Step 1: Create VD
            println("[1] Creating VD...")
            val displayId = svc.createVirtualDisplay("OpenCyvis", 1080, 2400, 420, 0)
            if (displayId <= 0) {
                println("FAIL: VD creation failed (id=$displayId)")
                return
            }
            println("[1] VD created: displayId=$displayId")
            Thread.sleep(1500) // let VD settle

            // Step 2: Ensure WeChat has a task on Display 0
            println("[2] Launching WeChat on Display 0...")
            val proc = Runtime.getRuntime().exec(arrayOf("am", "start", "-n", "com.tencent.mm/.ui.LauncherUI"))
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            Thread.sleep(2000)

            // Check WeChat task before
            val beforeTask = DisplayOps.findTaskByPackage("com.tencent.mm")
            println("[2] WeChat task before: taskId=${beforeTask?.first}, displayId=${beforeTask?.second}")
            if (beforeTask == null) {
                println("FAIL: WeChat not found as running task")
                return
            }

            // Step 3: Launch WeChat on VD via startActivityOnDisplay (tests pre-move)
            println("[3] Calling startActivityOnDisplay for WeChat on display $displayId...")
            val intentUri = "intent:#Intent;component=com.tencent.mm/.ui.LauncherUI;launchFlags=0x18840000;end"
            val result = svc.startActivityOnDisplay(intentUri, displayId)
            println("[3] startActivityOnDisplay returned: $result")
            Thread.sleep(1000)

            // Step 4: Check where WeChat ended up
            val afterTask = DisplayOps.findTaskByPackage("com.tencent.mm")
            println("[4] WeChat task after: taskId=${afterTask?.first}, displayId=${afterTask?.second}")

            // Also check VD tasks
            val vdTasks = DisplayOps.getTasksForDisplayAll(displayId)
            val wechatOnVd = vdTasks.any { t ->
                if (t == null) return@any false
                val comp = DisplayOps.readComponent(t, "baseActivity")
                    ?: DisplayOps.readComponentFromGetter(t, "getBaseActivity")
                comp?.packageName == "com.tencent.mm"
            }
            println("[4] WeChat on VD (display $displayId): $wechatOnVd")

            // Verdict
            if (wechatOnVd) {
                println("═══ PASS: WeChat successfully moved to VD ═══")
            } else {
                println("═══ FAIL: WeChat NOT on VD (still on display ${afterTask?.second}) ═══")
            }

        } finally {
            svc.releaseVirtualDisplay()
            println("[cleanup] VD released")
        }
    }
}
