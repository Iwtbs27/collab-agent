package ai.opencyvis.display

import android.app.ActivityManager
import android.app.TaskStackListener
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object TaskDisplayGuardPolicy {
    fun shouldRescue(
        task: TaskSnapshot,
        controlledTaskIds: Set<Int>,
        @Suppress("UNUSED_PARAMETER") controlledPackages: Set<String>
    ): Boolean {
        if (task.displayId != Display.DEFAULT_DISPLAY) return false
        return task.taskId in controlledTaskIds
    }
}

/**
 * Detects when a VD-controlled task escapes to Display 0.
 *
 * Two modes of operation:
 * 1. **Launch tracking** — after `open_app`, silently reparent the task back to
 *    VD if it lands on Display 0 (Task Reuse makes `setLaunchDisplayId` unreliable).
 * 2. **Escape detection** — for tasks that genuinely escape (user manually switches),
 *    dispatch [onEscape] so the caller can handle it.
 *
 * TaskStackListener is the primary path. A low-frequency scan is kept as a
 * fallback for ROM/API differences in hidden listener registration.
 */
class TaskDisplayGuard(
    private val context: Context,
    private val vdm: VirtualDisplayManager,
    private val scope: CoroutineScope,
    private val onEscape: (TaskSnapshot) -> Unit
) {
    companion object {
        private const val TAG = "TaskDisplayGuard"
        private const val FALLBACK_SCAN_MS = 800L
        private const val PENDING_LAUNCH_MS = 3000L
        private const val RESCUE_COOLDOWN_MS = 2000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val controlledTaskIds = mutableSetOf<Int>()
    private val controlledPackages = mutableSetOf<String>()
    private val recentRescues = mutableMapOf<Int, Long>()

    /** Packages recently launched via `open_app` — silent reparent if found on Display 0. */
    private val pendingLaunchPackages = mutableSetOf<String>()
    private var pendingLaunchDeadline = 0L
    private var pendingLaunchJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var dispatchingEscape = false

    private var listener: TaskStackListener? = null
    private var fallbackJob: Job? = null

    fun start() {
        if (running) return
        running = true
        val listenerRegistered = registerTaskStackListener()
        if (!listenerRegistered) {
            startFallbackScan()
        } else {
            // Keep a very low-cost correction path even if callbacks miss a
            // task created by notification/deeplink flows.
            startFallbackScan()
        }
    }

    fun stop() {
        running = false
        dispatchingEscape = false
        fallbackJob?.cancel()
        fallbackJob = null
        pendingLaunchJob?.cancel()
        pendingLaunchJob = null
        unregisterTaskStackListener()
        synchronized(lock) {
            controlledTaskIds.clear()
            controlledPackages.clear()
            recentRescues.clear()
            pendingLaunchPackages.clear()
        }
    }

    /**
     * Called after `open_app` to enter launch-tracking mode for [packageName].
     * During the tracking window (3 s), if this package appears on Display 0 it
     * will be silently reparented to VD — no [onEscape] callback, no UI disruption.
     */
    fun trackLaunch(packageName: String) {
        synchronized(lock) {
            pendingLaunchPackages.add(packageName)
            pendingLaunchDeadline = SystemClock.elapsedRealtime() + PENDING_LAUNCH_MS
        }
        Log.i(TAG, "Tracking launch: $packageName (window=${PENDING_LAUNCH_MS}ms)")
        pendingLaunchJob?.cancel()
        pendingLaunchJob = scope.launch {
            delay(PENDING_LAUNCH_MS)
            synchronized(lock) {
                pendingLaunchPackages.clear()
                pendingLaunchDeadline = 0
            }
            Log.d(TAG, "Launch tracking window expired")
        }
    }

    fun addControlledTask(task: TaskSnapshot) {
        if (task.taskId <= 0) return
        synchronized(lock) {
            controlledTaskIds.add(task.taskId)
            task.basePackage?.let { controlledPackages.add(it) }
            task.topPackage?.let { controlledPackages.add(it) }
        }
        Log.i(TAG, "Tracking controlled task: $task")
    }

    fun addControlledTaskId(taskId: Int) {
        if (taskId <= 0) return
        synchronized(lock) {
            controlledTaskIds.add(taskId)
        }
        Log.i(TAG, "Tracking controlled task id: $taskId")
    }

    fun addControlledTasks(tasks: Collection<TaskSnapshot>) {
        tasks.forEach { addControlledTask(it) }
    }

    fun removeControlledTask(taskId: Int) {
        synchronized(lock) {
            controlledTaskIds.remove(taskId)
        }
    }

    fun controlledTaskIdsSnapshot(): Set<Int> = synchronized(lock) { controlledTaskIds.toSet() }

    fun controlledPackagesSnapshot(): Set<String> = synchronized(lock) { controlledPackages.toSet() }

    private fun registerTaskStackListener(): Boolean {
        return try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null)
            val listenerType = Class.forName("android.app.ITaskStackListener")
            val method = atm.javaClass.getMethod("registerTaskStackListener", listenerType)
            listener = object : TaskStackListener() {
                override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
                    vdm.toTaskSnapshot(taskInfo)?.let { maybeDispatchEscape(it) }
                }

                override fun onTaskDisplayChanged(taskId: Int, newDisplayId: Int) {
                    if (newDisplayId != Display.DEFAULT_DISPLAY) return
                    vdm.getTaskSnapshot(taskId)?.let { maybeDispatchEscape(it) }
                }

                override fun onTaskCreated(taskId: Int, componentName: ComponentName?) {
                    val packageName = componentName?.packageName ?: return
                    if (!isControlledPackage(packageName)) return
                    vdm.getTaskSnapshot(taskId)?.let { maybeDispatchEscape(it) }
                }

                override fun onTaskRemoved(taskId: Int) {
                    removeControlledTask(taskId)
                }
            }
            method.invoke(atm, listener)
            Log.i(TAG, "TaskStackListener registered")
            true
        } catch (e: Exception) {
            Log.w(TAG, "TaskStackListener registration failed; using fallback scan: ${e.message}")
            listener = null
            false
        }
    }

    private fun unregisterTaskStackListener() {
        val current = listener ?: return
        try {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getMethod("getService")
                .invoke(null)
            val listenerType = Class.forName("android.app.ITaskStackListener")
            val method = atm.javaClass.getMethod("unregisterTaskStackListener", listenerType)
            method.invoke(atm, current)
            Log.i(TAG, "TaskStackListener unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "TaskStackListener unregister failed: ${e.message}")
        } finally {
            listener = null
        }
    }

    private fun startFallbackScan() {
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            while (isActive && running) {
                delay(FALLBACK_SCAN_MS)
                scanForEscapedTasks()
            }
        }
    }

    private fun scanForEscapedTasks() {
        val taskIds = controlledTaskIdsSnapshot()
        if (taskIds.isEmpty()) return
        // Use backend (shell process) to query Display 0 top task — app process cannot
        // call ActivityTaskManager.getService() due to hidden API restrictions.
        val topTaskOnD0 = vdm.getTopTaskIdOnDisplay(Display.DEFAULT_DISPLAY) ?: return
        if (topTaskOnD0 in taskIds) {
            val snapshot = TaskSnapshot(
                taskId = topTaskOnD0,
                displayId = Display.DEFAULT_DISPLAY,
                topPackage = null,
                basePackage = null,
                lastActiveTime = 0
            )
            maybeDispatchEscape(snapshot)
        }
    }

    /**
     * Central escape dispatch. Three paths:
     * 1. **Pending launch** — silently reparent to VD, no callback.
     * 2. **Launcher on top** — user pressed HOME, skip.
     * 3. **Genuine escape** — call [onEscape] (rescue with cooldown).
     */
    private fun maybeDispatchEscape(task: TaskSnapshot) {
        if (!running) return
        val taskIds = controlledTaskIdsSnapshot()
        val packages = controlledPackagesSnapshot()
        if (!TaskDisplayGuardPolicy.shouldRescue(task, taskIds, packages)) return
        if (task.containsPackage(context.packageName)) return
        if (dispatchingEscape) return

        // Path 1: recently launched — silent reparent, no UI disruption
        val isPendingLaunch = synchronized(lock) {
            task.topPackage in pendingLaunchPackages &&
                    SystemClock.elapsedRealtime() < pendingLaunchDeadline
        }
        if (isPendingLaunch) {
            Log.i(TAG, "Silent reparent (launch tracking): ${task.topPackage} task=${task.taskId}")
            synchronized(lock) { pendingLaunchPackages.remove(task.topPackage) }
            vdm.moveTaskToDisplay(task.taskId, vdm.displayId)
            addControlledTask(task.copy(displayId = vdm.displayId))
            return
        }

        // Path 2: user pressed HOME — launcher is Display 0 top task
        if (isLauncherTopOnDefaultDisplay()) {
            Log.d(TAG, "Skipping — launcher is top on Display 0")
            return
        }

        // Path 3: controlled task escaped — silent reparent back to VD immediately
        val now = SystemClock.elapsedRealtime()
        val lastRescue = synchronized(lock) { recentRescues[task.taskId] ?: 0 }
        if (now - lastRescue < RESCUE_COOLDOWN_MS) {
            Log.d(TAG, "Skipping — cooldown active for task ${task.taskId}")
            return
        }

        synchronized(lock) { recentRescues[task.taskId] = now }
        Log.i(TAG, "Silent reparent (escape rescue): ${task.topPackage} task=${task.taskId} → VD ${vdm.displayId}")
        vdm.moveTaskToDisplay(task.taskId, vdm.displayId)
        addControlledTask(task.copy(displayId = vdm.displayId))

        dispatchingEscape = true
        mainHandler.post {
            try {
                if (running) onEscape(task)
            } finally {
                dispatchingEscape = false
            }
        }
    }

    private fun isControlledPackage(packageName: String): Boolean {
        return synchronized(lock) { packageName in controlledPackages }
    }

    private fun isLauncherTopOnDefaultDisplay(): Boolean {
        // Cannot reliably query from app process (ActivityTaskManager hidden API blocked).
        // During agent execution, controlled tasks shouldn't be on Display 0 at all,
        // so skip this check — rescue is always appropriate.
        return false
    }

    private fun homePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    }
}
