package ai.opencyvis

import android.app.Activity
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.overlay.OverlayWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the slim floating overlay (chat-head ⇄ pill).
 *
 * Visibility rule (single source of truth): the overlay is attached to
 * the WindowManager iff
 *   (OpenCyvis is in background)  AND  (agent is Running or WaitingForUser
 *   or Paused-while-takeover).
 *
 * All visibility transitions go through `evaluateVisibility()` so foreground/
 * background flips, agent state changes, and engine swaps converge to the
 * same single decision. We never call `WindowManager.addView` from anywhere
 * else — `OverlayWindow.updateState()` only refreshes cached text/colors.
 *
 * Routes the user back to their last-foreground Activity (ControlPanel or
 * ViewActivity) when they tap the pill body or the heads-up notification.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        @Volatile var lastForegroundActivityClass: Class<out Activity>? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayWindow: OverlayWindow? = null
    private var agentService: AgentService? = null
    private var bound = false
    private var engineSubscriptionJob: Job? = null
    private var stateCollectionJob: Job? = null
    private var stepResultCollectionJob: Job? = null
    private var isAppInForeground = false

    private val processLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isAppInForeground = true
            Log.d(TAG, "App foreground")
            evaluateVisibility()
        }

        override fun onStop(owner: LifecycleOwner) {
            isAppInForeground = false
            Log.d(TAG, "App background")
            evaluateVisibility()
        }
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {
            if (a.javaClass.`package`?.name?.startsWith("ai.opencyvis") == true) {
                lastForegroundActivityClass = a.javaClass
                Log.d(TAG, "lastForegroundActivity = ${a.javaClass.simpleName}")
            }
        }
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            subscribeToEngineFlow()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            engineSubscriptionJob?.cancel()
            stateCollectionJob?.cancel()
            stepResultCollectionJob?.cancel()
            evaluateVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OverlayService created")

        val config = ConfigRepository(this)
        overlayWindow = OverlayWindow(this).apply {
            maxSteps = config.maxSteps
            callback = object : OverlayWindow.Callback {
                override fun onReturnToApp() {
                    val cls = lastForegroundActivityClass
                        ?: ai.opencyvis.ui.ControlPanelActivity::class.java
                    val intent = Intent(this@OverlayService, cls).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER) {
                            putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
                        }
                    }
                    startActivity(intent)
                }
                override fun onStop() {
                    agentService?.stopAgent()
                }
            }
            // Inflate the views and wire callbacks, but DO NOT attach to the
            // WindowManager yet — `evaluateVisibility()` is the only path
            // that decides whether we should be visible.
            prepare()
        }

        application.registerActivityLifecycleCallbacks(activityCallbacks)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        val intent = Intent(this, AgentService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        overlayWindow?.dismiss()
        overlayWindow = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        Log.i(TAG, "OverlayService destroyed")
        super.onDestroy()
    }

    /**
     * Subscribe to AgentService.engineFlow. Each time the engine instance
     * changes, re-bind state/stepResult collectors against the new flow
     * objects (since `engineFlow` swaps both `stateFlow` and
     * `stepResultFlow` underneath).
     */
    private fun subscribeToEngineFlow() {
        engineSubscriptionJob?.cancel()
        val service = agentService ?: return
        engineSubscriptionJob = scope.launch {
            service.engineFlow.collect { engine ->
                if (engine != null) {
                    val cfg = ConfigRepository(this@OverlayService)
                    overlayWindow?.maxSteps = cfg.maxSteps
                    overlayWindow?.currentInstruction = service.currentInstruction
                    collectStateUpdates()
                } else {
                    stateCollectionJob?.cancel()
                    stepResultCollectionJob?.cancel()
                }
                evaluateVisibility()
            }
        }
    }

    private fun collectStateUpdates() {
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()

        stateCollectionJob = agentService?.stateFlow?.let { stateFlow ->
            scope.launch {
                var wasRunning = false
                stateFlow.collect { state ->
                    overlayWindow?.updateState(state)
                    if (state is AgentState.Running) wasRunning = true
                    if (wasRunning && (state is AgentState.Idle || state is AgentState.Error)) {
                        kotlinx.coroutines.delay(3000)
                        wasRunning = false
                    }
                    evaluateVisibility()
                }
            }
        }

        stepResultCollectionJob = agentService?.stepResultFlow?.let { resultFlow ->
            scope.launch {
                resultFlow.collect { result ->
                    overlayWindow?.addStepResult(result)
                }
            }
        }
    }

    /**
     * Single source of truth for whether the overlay should be on screen.
     * Combines: (1) is OpenCyvis in the background, (2) is the agent in an
     * "active" state (Running / WaitingForUser / Paused-takeover).
     */
    private fun evaluateVisibility() {
        val service = agentService
        val state = service?.stateFlow?.value
        val active = service?.isOverlayActiveState(state) == true
        val shouldShow = !isAppInForeground && active
        Log.d(TAG, "evaluateVisibility: fg=$isAppInForeground active=$active → show=$shouldShow")
        if (shouldShow) overlayWindow?.attach() else overlayWindow?.detach()
    }
}
