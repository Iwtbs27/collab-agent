package ai.opencyvis.backend

import android.content.ComponentName
import android.os.IBinder
import android.util.Log
import android.view.Display
import java.lang.reflect.Method

/**
 * Reflection-based privileged operations for display/task management.
 *
 * Extracted from VirtualDisplayManager so that SystemBackend can delegate here
 * and a future RemoteBackend can implement these over IPC instead.
 */
object DisplayOps {
    private const val TAG = "DisplayOps"

    // ── ActivityTaskManager reflection ──────────────────────────────────

    private val activityTaskManager: Any? by lazy {
        try {
            Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService").invoke(null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ActivityTaskManager", e)
            null
        }
    }

    private val moveTaskMethod: Method? by lazy {
        val atm = activityTaskManager ?: return@lazy null
        for (name in listOf("moveRootTaskToDisplay", "moveTaskToDisplay")) {
            try {
                return@lazy atm.javaClass.getMethod(
                    name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                )
            } catch (_: NoSuchMethodException) { continue }
        }
        Log.e(TAG, "No moveTaskToDisplay method found")
        null
    }

    fun moveTaskToDisplay(taskId: Int, targetDisplayId: Int): Boolean {
        return try {
            moveTaskMethod?.invoke(activityTaskManager, taskId, targetDisplayId)
            Log.i(TAG, "Moved task $taskId to display $targetDisplayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "moveTaskToDisplay($taskId, $targetDisplayId) failed", e)
            false
        }
    }

    fun getTopTaskIdOnDisplay(displayId: Int, callerPackage: String): Int {
        val atm = activityTaskManager ?: return -1
        return try {
            val tasks = getTasksForDisplay(atm, displayId)
            for (t in tasks) {
                if (t == null) continue
                val taskId = readIntField(t, "taskId") ?: continue
                val baseActivity = readComponent(t, "baseActivity")
                    ?: readComponentFromGetter(t, "getBaseActivity")
                if (baseActivity?.packageName != callerPackage) return taskId
            }
            -1
        } catch (e: Exception) {
            Log.e(TAG, "getTopTaskIdOnDisplay failed", e)
            -1
        }
    }

    /**
     * Get running task info objects from ATM for given displays.
     * Returns raw TaskInfo objects that can be parsed via the field-reader helpers.
     */
    fun getRunningTaskInfos(limit: Int, vdDisplayId: Int): List<Any?> {
        val atm = activityTaskManager ?: return emptyList()
        return try {
            val result = mutableListOf<Any?>()
            result.addAll(getTasksForDisplay(atm, Display.DEFAULT_DISPLAY, limit))
            if (vdDisplayId != -1 && vdDisplayId != Display.DEFAULT_DISPLAY) {
                result.addAll(getTasksForDisplay(atm, vdDisplayId, limit))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getRunningTaskInfos failed", e)
            emptyList()
        }
    }

    fun getTasksForDisplayAll(displayId: Int): List<Any?> {
        val atm = activityTaskManager ?: return emptyList()
        return getTasksForDisplay(atm, displayId)
    }

    /**
     * Find an existing task for the given package across all displays.
     * Returns (taskId, displayId) of the first match, or null if not found.
     */
    fun findTaskByPackage(packageName: String): Pair<Int, Int>? {
        val atm = activityTaskManager ?: return null
        try {
            val tasks = getTasksForDisplay(atm, Display.DEFAULT_DISPLAY, 50)
            for (t in tasks) {
                if (t == null) continue
                val comp = readComponent(t, "baseActivity")
                    ?: readComponentFromGetter(t, "getBaseActivity")
                if (comp?.packageName == packageName) {
                    val taskId = readIntField(t, "taskId") ?: continue
                    val displayId = readIntField(t, "displayId") ?: Display.DEFAULT_DISPLAY
                    return taskId to displayId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTaskByPackage failed", e)
        }
        return null
    }

    private fun getTasksForDisplay(atm: Any, displayId: Int, limit: Int = 100): List<Any?> {
        return try {
            // 4-arg getTasks (API 36+)
            val method = atm.javaClass.getMethod(
                "getTasks", Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            (method.invoke(atm, limit, false, false, displayId) as? List<*>).orEmpty()
        } catch (_: NoSuchMethodException) {
            try {
                // 3-arg getTasks
                val m = atm.javaClass.getMethod(
                    "getTasks", Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
                )
                (m.invoke(atm, limit, false, false) as? List<*>).orEmpty()
            } catch (_: NoSuchMethodException) {
                // 1-arg fallback
                val m = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
                (m.invoke(atm, limit) as? List<*>).orEmpty()
            }
        }
    }

    // ── IWindowManager reflection (IME policy) ─────────────────────────

    fun setDisplayImePolicy(displayId: Int, policy: Int) {
        // Try IWindowManager.setDisplayImePolicy() (Android 12+)
        try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder ?: return
            val wm = Class.forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            wm!!.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(wm, displayId, policy)
            Log.i(TAG, "Set IME policy=$policy on display $displayId")
        } catch (e: Exception) {
            Log.w(TAG, "setDisplayImePolicy failed: ${e.message}")
        }
    }

    // ── Field reading helpers (for TaskInfo parsing) ────────────────────

    fun readIntField(target: Any, fieldName: String): Int? {
        return try { readField(target, fieldName) as? Int } catch (_: Exception) { null }
    }

    fun readLongField(target: Any, fieldName: String): Long? {
        return try { readField(target, fieldName) as? Long } catch (_: Exception) { null }
    }

    fun readComponent(target: Any, fieldName: String): ComponentName? {
        return try { readField(target, fieldName) as? ComponentName } catch (_: Exception) { null }
    }

    fun readComponentFromGetter(target: Any, methodName: String): ComponentName? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target) as? ComponentName
        } catch (_: Exception) { null }
    }

    private fun readField(target: Any, fieldName: String): Any? {
        val cls = target.javaClass
        return try {
            cls.getField(fieldName).get(target)
        } catch (_: NoSuchFieldException) {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(target)
        }
    }
}
