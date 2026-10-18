package ai.opencyvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.backend.BackendDetector
import ai.opencyvis.backend.ConnectionState
import ai.opencyvis.backend.DetectionResult
import ai.opencyvis.backend.PrivilegeBackend
import ai.opencyvis.backend.RemoteBackend
import ai.opencyvis.backend.SetupStateDetector
import ai.opencyvis.backend.SystemBackend
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.db.GlobalMemoryRepository
import ai.opencyvis.display.TaskDisplayGuard
import ai.opencyvis.display.TaskSnapshot
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.engine.ActionRepeatGuard
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.HandoffUiState
import ai.opencyvis.engine.HandoffVisualDetector
import ai.opencyvis.engine.ScreenFingerprint
import ai.opencyvis.engine.StepResult
import ai.opencyvis.llm.AnthropicClient
import ai.opencyvis.llm.LLMClient
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.OllamaClient
import ai.opencyvis.overlay.OverlayStatePolicy
import ai.opencyvis.schedule.ScheduleManager
import ai.opencyvis.ui.MessageType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that hosts the AgentEngine.
 * Manages display state transitions (CHAT/VIEW/TAKEOVER) via task reparenting.
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "opencyvis_agent"
        private const val CHANNEL_ASK = "opencyvis_ask_user"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_ASK_ID = 1002
        private const val NOTIFICATION_COMPLETED_ID = 1003
        private const val HANDOFF_TIMEOUT_MS = 30_000L
        private const val HANDOFF_COUNTDOWN_SECONDS = 10
        private const val HANDOFF_SAMPLE_INTERVAL_MS = 1_000L
        const val EXTRA_FOCUS_ASK = "focus_ask_user"
        const val EXTRA_SHOW_HANDOFF = "show_handoff"
    }

    // ── Display state management ────────────────────────────────────────

    enum class DisplayState { CHAT, VIEW, TAKEOVER }

    var displayState = DisplayState.CHAT
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private var engine: AgentEngine? = null

    /**
     * Multi-subscriber flow that emits whenever the active AgentEngine changes
     * (or is cleared). Replaces the previous single-listener `onEngineCreated`
     * var, which silently overwrote prior listeners when both
     * `OverlayService` and `ControlPanelActivity` registered against it.
     */
    private val _engineFlow = MutableStateFlow<AgentEngine?>(null)
    val engineFlow: StateFlow<AgentEngine?> = _engineFlow.asStateFlow()
    private val _handoffUiState = MutableStateFlow<HandoffUiState>(HandoffUiState.Idle)
    val handoffUiState: StateFlow<HandoffUiState> = _handoffUiState.asStateFlow()
    private var virtualDisplayManager: VirtualDisplayManager? = null
    private var taskDisplayGuard: TaskDisplayGuard? = null
    private var taskRescueInProgress = false
    private var agentWakeLock: PowerManager.WakeLock? = null
    private lateinit var config: ConfigRepository
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var memoryRepo: GlobalMemoryRepository
    var currentConversationId: Long? = null
        private set
    var currentInstruction: String = ""
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backendDeferred = CompletableDeferred<PrivilegeBackend>()
    private var backend: PrivilegeBackend? = null

    /** The name of the currently active backend (e.g. "system", "shizuku", "adb-direct"), or null. */
    val activeBackendName: String?
        get() = backend?.capabilities?.name

    /** Disconnect the privilege backend. The user can reconnect later from the setup flow. */
    fun revokeBackend() {
        backend?.destroy()
        backend = null
        Log.i(TAG, "Backend revoked by user")
    }

    /** Update the backend after pairing/reconnection (called by AdbPairingService). */
    fun updateBackend(newBackend: PrivilegeBackend) {
        val previousBackend = backend
        backend = newBackend
        ScreenCapture.backend = newBackend
        observeConnectorState(newBackend)
        if (previousBackend !== newBackend) {
            previousBackend?.destroy()
        }
        Log.i(TAG, "Backend updated: ${newBackend.capabilities.name}")
    }
    private var stateObserverJob: Job? = null
    private var historyObserverJob: Job? = null
    private var lastHistoryStatus: String? = null
    private var handoffObserverJob: Job? = null
    private var handoffMonitorJob: Job? = null
    private var activeHandoffStep: Int? = null
    private var activeHandoffReason: String = ""
    private var disconnectTimestamp: Long? = null
    private var disconnectObserverJob: Job? = null

    inner class AgentBinder : Binder() {
        fun getService(): AgentService = this@AgentService
    }

    private val binder = AgentBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        App.agentService = this
        config = ConfigRepository(this)
        // Auto-apply active profile to ensure config values match saved profile
        val activeProfile = config.activeProfileName
        if (activeProfile.isNotEmpty()) {
            val profileRepo = ai.opencyvis.config.ProfileRepository(config, this)
            profileRepo.switchTo(activeProfile)
        }
        historyRepo = ChatHistoryRepository(this)
        memoryRepo = GlobalMemoryRepository(this)
        createNotificationChannel()
        ensureAccessibilityServiceEnabled()
        // Mark stale "running" conversations as stopped — agent can't survive process restart
        scope.launch {
            historyRepo.markStaleRunningAsStopped()
        }
        // Detect privilege backend (async for Shizuku, instant for system app)
        scope.launch {
            when (val result = BackendDetector.detect(this@AgentService)) {
                is DetectionResult.Ready -> {
                    backend = result.backend
                    ScreenCapture.backend = result.backend
                    backendDeferred.complete(result.backend)
                    observeConnectorState(result.backend)
                    Log.i(TAG, "Backend ready: ${result.backend.capabilities.name}")
                }
                is DetectionResult.SetupRequired -> {
                    // Don't fall back to SystemBackend on standard flavor — it won't work
                    backendDeferred.complete(SystemBackend()) // unblock startAgent (will fail gracefully)
                    Log.w(TAG, "Backend setup required — user needs to complete setup wizard")
                }
                is DetectionResult.NoneAvailable -> {
                    backendDeferred.complete(SystemBackend()) // unblock startAgent
                    Log.w(TAG, "No backend available — wireless debugging not enabled?")
                }
            }
        }
        Log.i(TAG, "AgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("OpenCyvis agent ready"))

        if (intent?.action == "ai.opencyvis.START_SCHEDULED") {
            val instruction = intent.getStringExtra("instruction") ?: return START_STICKY
            Log.i(TAG, "Scheduled task triggered: $instruction")
            startAgent(instruction)
            return START_STICKY
        }

        // Support starting agent via intent extra (e.g. from adb)
        val instruction = intent?.getStringExtra("instruction")
        if (!instruction.isNullOrBlank()) {
            Log.i(TAG, "Starting agent from intent: $instruction")
            startAgent(instruction)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        App.agentService = null
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        disconnectObserverJob?.cancel()
        releaseAgentWakeLock()
        engine?.destroy()
        engine = null
        _engineFlow.value = null
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null
        backend?.destroy()
        backend = null
        Log.i(TAG, "AgentService destroyed")
        super.onDestroy()
    }

    private fun observeConnectorState(b: PrivilegeBackend) {
        disconnectObserverJob?.cancel()
        if (b !is RemoteBackend) return

        disconnectObserverJob = scope.launch {
            b.connector.state.collect { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        val isEngineRunning = engine?.state?.value is AgentState.Running
                        if (isEngineRunning) {
                            Log.w(TAG, "Backend disconnected during active task, pausing")
                            engine?.pause()
                            disconnectTimestamp = System.currentTimeMillis()
                            updateNotification("⏸ Paused — reconnecting...")
                        } else {
                            Log.w(TAG, "Backend disconnected while idle")
                        }

                        // Shizuku is independent of wireless debugging. Only the direct
                        // ADB connector should use adb_wifi_enabled as a retry condition.
                        val wirelessOn = SetupStateDetector.isWirelessDebuggingEnabled(this@AgentService)
                        val canRetry = b.connector.name == "shizuku" || wirelessOn
                        if (canRetry) {
                            Log.i(TAG, "Attempting ${b.connector.name} backend reconnect...")
                            var reconnected = false
                            for (attempt in 1..5) {
                                delay(3000L * attempt)
                                if (retryBackendDetection()) {
                                    Log.i(TAG, "Auto-reconnect succeeded on attempt $attempt")
                                    reconnected = true
                                    break
                                }
                            }
                            if (!reconnected) {
                                Log.w(TAG, "Auto-reconnect failed after 5 attempts")
                                backend = null
                                ScreenCapture.backend = SystemBackend()
                            }
                        } else {
                            Log.i(TAG, "Wireless debugging is off for ADB backend, clearing backend")
                            backend = null
                            ScreenCapture.backend = SystemBackend()
                        }
                    }
                    is ConnectionState.Connected -> {
                        val wasDisconnected = disconnectTimestamp != null
                        val isEnginePaused = engine?.state?.value is AgentState.Paused
                        if (wasDisconnected && isEnginePaused) {
                            val elapsed = System.currentTimeMillis() - disconnectTimestamp!!
                            disconnectTimestamp = null

                            if (elapsed > 10_000) {
                                Log.i(TAG, "Long disconnect (${elapsed}ms), resuming with fresh state")
                            }
                            engine?.resume()
                            updateNotification("Running: ${currentInstruction.take(30)}...")
                            Log.i(TAG, "Backend reconnected after ${elapsed}ms, resumed agent")
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun ensureAccessibilityServiceEnabled() {
        try {
            val componentName = "${packageName}/${packageName}.accessibility.VdAccessibilityService"
            val existing = android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (componentName in existing) return

            val updated = if (existing.isBlank()) componentName else "$existing:$componentName"
            android.provider.Settings.Secure.putString(
                contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated
            )
            android.provider.Settings.Secure.putInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1
            )
            Log.i(TAG, "Auto-enabled VdAccessibilityService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-enable accessibility service (requires system app)", e)
        }
    }

    // ── Agent lifecycle ─────────────────────────────────────────────────

    /**
     * Create and start the agent with the given instruction.
     */
    fun startAgent(instruction: String) {
        // If in VIEW/TAKEOVER, return to CHAT first
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        lastHistoryStatus = null
        cancelCompletionNotification()
        // Create or re-create engine with current config
        engine?.destroy()
        stateObserverJob?.cancel()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        taskRescueInProgress = false
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        Log.i(TAG, "startAgent config: provider=${config.apiProvider} model=${config.model} baseUrl=${config.baseUrl} key=${config.apiKey.take(10)}...")
        val llmTemperature = when (config.apiProvider) {
            ConfigRepository.PROVIDER_KIMI -> 0.6
            else -> 0.1
        }
        val llmClient: LLMClientInterface = when (config.apiProvider) {
            ConfigRepository.PROVIDER_ANTHROPIC -> AnthropicClient(
                apiKey = config.apiKey,
                model = config.model,
                baseUrl = config.baseUrl
            )
            ConfigRepository.PROVIDER_OLLAMA -> OllamaClient(
                model = config.model,
                baseUrl = config.baseUrl
            )
            else -> LLMClient(
                apiKey = config.apiKey,
                model = config.model,
                baseUrl = config.baseUrl,
                temperature = llmTemperature,
                disableThinking = config.apiProvider == ConfigRepository.PROVIDER_KIMI
            )
        }

        // Await backend detection, then create VDM + engine in coroutine
        scope.launch {
            val b = backend ?: backendDeferred.await()

            // Always use VirtualDisplay — reuse existing or create new
            var vdm = virtualDisplayManager
            var displayId: Int
            var displaySize: Point?

            if (vdm != null && vdm.isCreated) {
                displayId = vdm.displayId
                displaySize = Point(vdm.width, vdm.height)
                Log.i(TAG, "Reusing existing virtual display $displayId")
            } else {
                vdm?.destroy()
                vdm = VirtualDisplayManager(this@AgentService, b)
                // Use physical screen resolution so VD matches the real display
                val dm = resources.displayMetrics
                val id = vdm.create(width = dm.widthPixels, height = dm.heightPixels, dpi = dm.densityDpi)
                if (id != -1) {
                    displayId = id
                    displaySize = Point(vdm.width, vdm.height)
                    virtualDisplayManager = vdm
                    Log.i(TAG, "Virtual display created: $id (${vdm.width}x${vdm.height})")
                } else {
                    Log.e(TAG, "Failed to create virtual display")
                    vdm.destroy()
                    virtualDisplayManager = null
                    updateNotification("Error: failed to create virtual display")
                    return@launch
                }
            }

            acquireAgentWakeLock()

            val actionExecutor = ActionExecutor(
                this@AgentService,
                displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = config.blacklistedPackages
            )

            val newEngine = AgentEngine(
                llmClient,
                actionExecutor,
                config.maxSteps,
                vdm,
                config.debugMode,
                memoryRepo,
                viewTreeProvider = { displayId, w, h ->
                    VdAccessibilityService.captureViewTree(displayId, w, h)
                },
                shouldForwardScreenshot = { config.imSendStepScreenshots },
                blacklistedPackages = config.blacklistedPackages,
                onSaveRoutine = { name, icon, instr, schedType, schedTime, schedRepeat,
                                  schedInterval, schedLocation, schedOnEnter ->
                    scope.launch(Dispatchers.IO) {
                        val dao = AppDatabase.getInstance(this@AgentService).routineDao()
                        val routine = ai.opencyvis.db.RoutineEntity(
                            name = name,
                            icon = icon,
                            instruction = instr,
                            description = null,
                            category = "custom",
                            isPinned = false,
                            useCount = 0,
                            lastUsedAt = null,
                            createdAt = System.currentTimeMillis(),
                            sortOrder = 100,
                            scheduleEnabled = schedType != null,
                            triggerType = schedType,
                            scheduleHour = schedTime?.split(":")?.getOrNull(0)?.toIntOrNull(),
                            scheduleMinute = schedTime?.split(":")?.getOrNull(1)?.toIntOrNull(),
                            scheduleRepeatDays = when (schedRepeat) {
                                "daily", null -> null
                                "weekdays" -> "1,2,3,4,5"
                                else -> schedRepeat
                            },
                            intervalMinutes = schedInterval,
                            geoLocationName = schedLocation,
                            geoTriggerOnEnter = schedOnEnter
                        )
                        val id = dao.insertRoutine(routine).toInt()
                        if (schedType != null) {
                            ScheduleManager.register(this@AgentService, routine.copy(id = id))
                        }
                        Log.i(TAG, "Saved routine '$name' (id=$id, schedule=$schedType)")
                    }
                }
            )
            engine = newEngine
            currentInstruction = instruction
            _engineFlow.value = newEngine
            observeConnectorState(b)
            newEngine.start(instruction)

            updateNotification("Running: ${instruction.take(30)}...")
            Log.i(TAG, "Agent started with instruction: $instruction")

            // Persist conversation to history DB
            val convId = historyRepo.startConversation(instruction)
            currentConversationId = convId
            historyRepo.addMessage(convId, MessageType.USER_INPUT, instruction)

            observeHistoryWrites()
            observeStateForVdLifecycle()
            observeAskUserNotifications()
            observeHandoffRequests()
            startTaskDisplayGuard(vdm)
        }
    }

    private class DebugLLMClient : LLMClientInterface {
        override suspend fun chatWithTools(messages: List<Map<String, Any>>): Map<String, Any?> =
            mapOf(
                "action_type" to "wait",
                "thought" to "debug wait",
            )

        override fun shutdown() {}
    }

    internal fun debugStartRunningAgent() {
        if (displayState != DisplayState.CHAT) {
            returnToChat()
        }

        engine?.destroy()
        stateObserverJob?.cancel()
        historyObserverJob?.cancel()
        askObserverJob?.cancel()
        handoffObserverJob?.cancel()
        handoffMonitorJob?.cancel()
        releaseAgentWakeLock()

        val b = backend ?: SystemBackend()
        var vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            vdm?.destroy()
            vdm = VirtualDisplayManager(this, b)
            val dm = resources.displayMetrics
            val displayId = vdm.create(
                width = dm.widthPixels,
                height = dm.heightPixels,
                dpi = dm.densityDpi
            )
            if (displayId == -1) {
                Log.e(TAG, "debugStartRunningAgent: failed to create VD")
                return
            }
            virtualDisplayManager = vdm
        }

        acquireAgentWakeLock()

        val displaySize = Point(vdm.width, vdm.height)
        val newEngine = AgentEngine(
            DebugLLMClient(),
            ActionExecutor(
                this,
                vdm.displayId,
                displaySize,
                onOpenAppSuccess = { launchedPkg ->
                    taskDisplayGuard?.trackLaunch(launchedPkg)
                    mainHandler.postDelayed({ refreshControlledTasksFromVd("debug_open_app_success") }, 250)
                },
                backend = b,
                blacklistedPackages = config.blacklistedPackages
            ),
            config.maxSteps,
            vdm,
            debugMode = true,
            memoryRepository = memoryRepo,
            viewTreeProvider = { displayId, w, h ->
                VdAccessibilityService.captureViewTree(displayId, w, h)
            },
            blacklistedPackages = config.blacklistedPackages
        )
        engine = newEngine
        currentInstruction = "debug takeover verification"
        currentConversationId = null
        displayState = DisplayState.CHAT
        _engineFlow.value = newEngine
        newEngine.debugSimulateRunning()
        updateNotification("Debug agent running")
        observeStateForVdLifecycle()
        observeHandoffRequests()
        startTaskDisplayGuard(vdm)
        Log.i(TAG, "Debug running agent ready on display ${vdm.displayId}")
    }

    internal fun debugRepeatGuardTypeText() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x0f0f0f0f0f0f0f0fL)
        guard.recordExecuted(ai.opencyvis.action.Action.TypeText("京东"), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.TypeText("京东"), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard type_text decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard type_text decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapBlocked() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(ai.opencyvis.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.Tap(520, 590), screen)) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap decision=ALLOW")
        }
    }

    internal fun debugRepeatGuardTapAllowed() {
        val guard = ActionRepeatGuard()
        val screen = ScreenFingerprint(0x1111111111111111L)
        guard.recordExecuted(ai.opencyvis.action.Action.Tap(500, 600), screen)
        when (val decision = guard.evaluate(ai.opencyvis.action.Action.Tap(520, 590), ScreenFingerprint(-1L))) {
            is ActionRepeatGuard.Decision.Block ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=BLOCK feedback=${decision.feedback}")
            ActionRepeatGuard.Decision.Allow ->
                Log.i(TAG, "TEST repeat_guard tap_changed decision=ALLOW")
        }
    }

    private var askObserverJob: Job? = null

    /**
     * Watch for WaitingForUser state and post a high-priority heads-up notification
     * so the user sees the prompt even when OpenCyvis is in the background.
     */
    private fun observeAskUserNotifications() {
        askObserverJob?.cancel()
        val flow = engine?.state ?: return
        askObserverJob = scope.launch {
            var wasAsking = false
            flow.collect { state ->
                val asking = state is AgentState.WaitingForUser
                if (asking && !wasAsking) {
                    val q = (state as AgentState.WaitingForUser).question
                    postAskNotification(q)
                } else if (!asking && wasAsking) {
                    cancelAskNotification()
                }
                wasAsking = asking
            }
        }
    }

    private fun postAskNotification(question: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: ai.opencyvis.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_FOCUS_ASK, true)
        }
        val pi = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(getString(R.string.notif_needs_answer))
            .setContentText(question)
            .setStyle(Notification.BigTextStyle().bigText(question))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.notif_open_app), pi
                ).build()
            )
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ASK_ID, notif)
    }

    private fun cancelAskNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ASK_ID)
    }

    private fun postCompletionNotification(title: String, body: String) {
        val cls = OverlayService.lastForegroundActivityClass
            ?: ai.opencyvis.ui.ControlPanelActivity::class.java
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pi = PendingIntent.getActivity(
            this, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = Notification.Builder(this, CHANNEL_ASK)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_COMPLETED_ID, notif)
    }

    private fun cancelCompletionNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_COMPLETED_ID)
    }

    private fun observeHandoffRequests() {
        handoffObserverJob?.cancel()
        val flow = engine?.state ?: return
        handoffObserverJob = scope.launch {
            flow.collect { state ->
                if (state is AgentState.WaitingForHandoff) {
                    if (activeHandoffStep != state.step) {
                        activeHandoffStep = state.step
                        activeHandoffReason = state.reason
                        startUserHandoff(state.reason)
                    }
                } else if (activeHandoffStep != null && state !is AgentState.Paused) {
                    activeHandoffStep = null
                    activeHandoffReason = ""
                    handoffMonitorJob?.cancel()
                    _handoffUiState.value = HandoffUiState.Idle
                }
            }
        }
    }

    private fun startUserHandoff(reason: String) {
        Log.i(TAG, "Starting user handoff: $reason")
        _handoffUiState.value = HandoffUiState.Active(reason)
        enterViewMode(showHandoff = true)
        enterTakeoverMode()
        startHandoffVisualMonitor(reason)
        updateNotification("Waiting for sensitive input")
    }

    private fun startHandoffVisualMonitor(reason: String) {
        handoffMonitorJob?.cancel()
        val vdm = virtualDisplayManager ?: return
        val detector = HandoffVisualDetector()
        handoffMonitorJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var baseline: HandoffVisualDetector.Fingerprint? = null
            var stableTransitionCount = 0

            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                _handoffUiState.value = HandoffUiState.Active(reason, elapsed)

                val fingerprint = withContext(Dispatchers.IO) {
                    val bitmap = vdm.captureViaImageReader(timeoutMs = 250)
                    try {
                        if (bitmap != null) detector.fingerprint(bitmap) else null
                    } finally {
                        bitmap?.recycle()
                    }
                }

                if (fingerprint != null) {
                    val base = baseline
                    if (base == null || base.width != fingerprint.width || base.height != fingerprint.height) {
                        baseline = fingerprint
                        stableTransitionCount = 0
                    } else if (detector.isHighConfidenceTransition(base, fingerprint)) {
                        stableTransitionCount++
                        if (stableTransitionCount >= 2) {
                            Log.i(TAG, "Handoff visual monitor detected high-confidence transition")
                            _handoffUiState.value = HandoffUiState.PendingReturn(
                                reason = getString(R.string.handoff_page_changed),
                                countdownSeconds = 0,
                                source = "visual_change_stable"
                            )
                            delay(900L)
                            completeUserHandoff("visual_change_stable")
                            return@launch
                        }
                    } else {
                        stableTransitionCount = 0
                    }
                }

                if (elapsed >= HANDOFF_TIMEOUT_MS) {
                    showPendingHandoffReturn(reason, "timeout")
                    return@launch
                }

                delay(HANDOFF_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun showPendingHandoffReturn(reason: String, source: String) {
        handoffMonitorJob?.cancel()
        handoffMonitorJob = scope.launch {
            for (remaining in HANDOFF_COUNTDOWN_SECONDS downTo 1) {
                _handoffUiState.value = HandoffUiState.PendingReturn(reason, remaining, source)
                delay(1_000L)
            }
            completeUserHandoff("${source}_countdown_elapsed")
        }
    }

    fun completeUserHandoff(source: String = "manual_return_control") {
        Log.i(TAG, "Completing user handoff: $source")
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        activeHandoffStep = null
        _handoffUiState.value = HandoffUiState.Idle
        if (displayState == DisplayState.TAKEOVER) {
            displayState = DisplayState.VIEW
        }
        engine?.completeUserHandoff(source)
        updateNotification("Agent running")
    }

    fun cancelPendingHandoffReturn() {
        val reason = activeHandoffReason.ifBlank { getString(R.string.handoff_reason_default) }
        Log.i(TAG, "Cancelling pending handoff return")
        startHandoffVisualMonitor(reason)
    }

    /**
     * Observe step results and state changes to persist chat history.
     */
    private fun observeHistoryWrites() {
        historyObserverJob?.cancel()
        val eng = engine ?: return

        // Observe step results
        historyObserverJob = scope.launch {
            eng.stepResults.collect { result ->
                if (result.success && !result.completed && shouldRefreshControlledTasks(result)) {
                    refreshControlledTasksFromVd("step_${result.step}_${result.actionType}")
                }

                val convId = currentConversationId ?: return@collect
                if (config.debugMode) {
                    val prefix = if (result.success) "" else "[FAIL] "
                    val msg = "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}"
                    historyRepo.addMessage(convId, MessageType.AGENT_STATUS, msg)
                    if (!result.debugInfo.isNullOrBlank()) {
                        historyRepo.addMessage(convId, MessageType.AGENT_DEBUG, "[LLM]\n${result.debugInfo}")
                    }
                } else if (!result.completed) {
                    val userMsg = result.thought.ifBlank { result.detail }
                    if (userMsg != lastHistoryStatus) {
                        if (lastHistoryStatus == null) {
                            historyRepo.addMessage(convId, MessageType.AGENT_STATUS, userMsg)
                        } else {
                            historyRepo.updateLastAgentStatus(convId, userMsg)
                        }
                        lastHistoryStatus = userMsg
                    }
                }

                if (result.completed) {
                    val summary = when (result.actionType) {
                        "finish" -> "✅ ${getString(R.string.task_complete, result.thought)}"
                        "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                        "max_steps" -> "⚠️ ${getString(R.string.task_max_steps, result.step)}"
                        "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                        else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                    }
                    if (!config.debugMode && lastHistoryStatus != null) {
                        historyRepo.updateLastAgentStatus(convId, summary)
                        historyRepo.updateLastAgentStatusToResult(convId)
                    } else {
                        historyRepo.addMessage(convId, MessageType.AGENT_RESULT, summary)
                    }
                    lastHistoryStatus = null
                    val status = when (result.actionType) {
                        "finish" -> "completed"
                        "fail", "error" -> "failed"
                        else -> "completed"
                    }
                    historyRepo.updateStatus(convId, status)
                }
            }
        }

        // Observe ask_user state
        scope.launch {
            eng.state.collect { state ->
                val convId = currentConversationId ?: return@collect
                if (state is AgentState.WaitingForUser) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(convId, MessageType.AGENT_QUESTION, state.question)
                } else if (state is AgentState.WaitingForHandoff) {
                    if (!config.debugMode) {
                        lastHistoryStatus = null
                    }
                    historyRepo.addMessage(
                        convId,
                        MessageType.AGENT_STATUS,
                        getString(R.string.handoff_sensitive_input, state.reason)
                    )
                }
            }
        }
    }

    /**
     * Watch for agent completion. Keep VD alive for user inspection.
     */
    private fun observeStateForVdLifecycle() {
        stateObserverJob?.cancel()
        val flow = engine?.state ?: return

        stateObserverJob = scope.launch {
            var wasRunning = false
            flow.collect { state ->
                if (state is AgentState.Running) {
                    wasRunning = true
                }
                if (wasRunning && (state is AgentState.Idle || state is AgentState.Error)) {
                    Log.i(TAG, "Task completed, keeping virtual display for user inspection")
                    taskDisplayGuard?.stop()
                    taskDisplayGuard = null
                    releaseAgentWakeLock()

                    // Update foreground notification + post completion notification if in background
                    val isBackground = !androidx.lifecycle.ProcessLifecycleOwner.get()
                        .lifecycle.currentState.isAtLeast(
                            androidx.lifecycle.Lifecycle.State.STARTED)
                    when (state) {
                        is AgentState.Idle -> {
                            updateNotification(getString(R.string.notif_task_completed))
                            if (isBackground) {
                                val body = state.resultMessage ?: currentInstruction
                                postCompletionNotification(
                                    getString(R.string.notif_task_completed), body)
                            }
                        }
                        is AgentState.Error -> {
                            updateNotification(getString(R.string.notif_task_failed_title))
                            if (isBackground) {
                                postCompletionNotification(
                                    getString(R.string.notif_task_failed_title), state.message)
                            }
                        }
                        else -> {}
                    }

                    stateObserverJob?.cancel()
                }
            }
        }
    }

    fun pauseAgent() {
        engine?.pause()
        updateNotification("Agent paused")
    }

    fun resumeAgent() {
        engine?.resume()
        updateNotification("Agent running")
    }

    suspend fun retryBackendDetection(): Boolean {
        val result = BackendDetector.detect(this@AgentService)
        if (result is DetectionResult.Ready) {
            backend = result.backend
            ScreenCapture.backend = result.backend
            observeConnectorState(result.backend)
            // New PrivilegedService won't have the old VD's ImageReader — destroy stale VDM
            virtualDisplayManager?.destroy()
            virtualDisplayManager = null
            Log.i(TAG, "Backend re-detected: ${result.backend.capabilities.name}")
            return true
        }
        Log.w(TAG, "Backend re-detection failed: $result")
        return false
    }

    fun stopAgent() {
        taskDisplayGuard?.stop()
        taskDisplayGuard = null
        taskRescueInProgress = false
        handoffMonitorJob?.cancel()
        handoffMonitorJob = null
        handoffObserverJob?.cancel()
        activeHandoffStep = null
        activeHandoffReason = ""
        _handoffUiState.value = HandoffUiState.Idle
        releaseAgentWakeLock()

        // Resume agent if paused (for clean stop)
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }

        engine?.stop()
        historyObserverJob?.cancel()
        askObserverJob?.cancel()
        cancelAskNotification()

        currentConversationId?.let { convId ->
            scope.launch { historyRepo.updateStatus(convId, "stopped") }
            currentConversationId = null
        }
        currentInstruction = ""

        // Capture task ID before destroying VD
        val vdm = virtualDisplayManager
        var rescueTaskId: Int? = null
        if (vdm != null && vdm.isCreated) {
            rescueTaskId = vdm.getTopTaskIdOnDisplay(vdm.displayId)
        }

        // Destroy VD first so it stops rendering
        virtualDisplayManager?.destroy()
        virtualDisplayManager = null

        // Move task to Display 0 on next frame — deferred so the foreground
        // activity is fully composited and the controlled app won't flash.
        // Bring OpenCyvis UI to front BEFORE the move to prevent z-order flicker.
        if (rescueTaskId != null) {
            Handler(Looper.getMainLooper()).post {
                bringOpenCyvisUiToFront()
                vdm?.moveTaskToDisplay(rescueTaskId, 0)
                Log.i(TAG, "Deferred move of task $rescueTaskId to Display 0")
            }
        }

        displayState = DisplayState.CHAT
        updateNotification("Agent stopped")
        Log.i(TAG, "Agent stopped, app moved to Display 0")
    }

    @Suppress("DEPRECATION")
    private fun acquireAgentWakeLock() {
        val current = agentWakeLock
        if (current?.isHeld == true) return

        try {
            val powerManager = getSystemService(PowerManager::class.java)
            agentWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:Agent"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L)
            }
            Log.i(TAG, "Agent wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire agent wake lock: ${e.message}")
            agentWakeLock = null
        }
    }

    private fun releaseAgentWakeLock() {
        val wakeLock = agentWakeLock ?: return
        try {
            if (wakeLock.isHeld) wakeLock.release()
            Log.i(TAG, "Agent wake lock released")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release agent wake lock: ${e.message}")
        } finally {
            agentWakeLock = null
        }
    }

    fun submitUserResponse(answer: String) {
        engine?.submitUserResponse(answer)
        updateNotification("Agent running")
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_ANSWER, answer) }
        }
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "submitUserSupplement(${trimmed.length} chars)")
        engine?.submitUserSupplement(trimmed)
        updateNotification("Agent running")
        currentConversationId?.let { convId ->
            scope.launch { historyRepo.addMessage(convId, MessageType.USER_SUPPLEMENT, trimmed) }
        }
    }

    // ── Display state transitions (SurfaceView-based) ────────────────────

    /**
     * Enter VIEW mode: launch ViewActivity with SurfaceView rendering VD content.
     * Agent keeps running — user can watch the agent work in real-time.
     * App stays on VD (no task reparenting).
     */
    fun enterViewMode(showHandoff: Boolean = false): Boolean {
        val vdm = virtualDisplayManager
        if (vdm == null || !vdm.isCreated) {
            mainHandler.post {
                Toast.makeText(this, "No virtual display", Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Prevent infinite recursion: if VD shows our own app, move it off the VD.
        // Without this, SurfaceView → VD → OpenCyvis UI → SurfaceView → ... (套娃)
        // Use backend (shell uid) for task queries — main process can't access hidden APIs.
        // getTopTaskIdOnDisplay returns -1 if only OpenCyvis tasks are on VD.
        // In that case we just proceed — the VD will show whatever was last rendered.

        // Launch ViewActivity — SurfaceView will connect to VD via setSurface()
        val intent = Intent(this, ai.opencyvis.ui.ViewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (showHandoff) {
                putExtra(EXTRA_SHOW_HANDOFF, true)
                putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        startActivity(intent)

        displayState = DisplayState.VIEW
        updateNotification("Viewing agent operation")
        Log.i(TAG, "Entered VIEW mode (agent keeps running)")
        return true
    }

    /**
     * Enter TAKEOVER mode: pause agent, let user interact via touch forwarding.
     * Called by ViewActivity when user taps "Takeover".
     */
    fun enterTakeoverMode() {
        if (displayState != DisplayState.VIEW) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.pause()
        }
        displayState = DisplayState.TAKEOVER
        updateNotification("Takeover mode")
        Log.i(TAG, "Entered TAKEOVER mode (agent paused)")
    }

    /**
     * Exit TAKEOVER mode back to VIEW mode: resume agent.
     * Called by ViewActivity when user taps "Return Control".
     */
    fun exitTakeoverMode() {
        if (displayState != DisplayState.TAKEOVER) return
        if (engine?.state?.value !is AgentState.WaitingForHandoff) {
            engine?.resume()
        }
        displayState = DisplayState.VIEW
        updateNotification("Viewing agent operation")
        Log.i(TAG, "Exited TAKEOVER → VIEW mode (agent resumed)")
    }

    /**
     * Return to CHAT mode: close ViewActivity, bring ControlPanelActivity to front.
     * Agent keeps running on VD.
     */
    fun returnToChat() {
        // Resume agent if it was paused for TAKEOVER
        if (displayState == DisplayState.TAKEOVER) {
            engine?.resume()
        }

        // Bring ControlPanelActivity to front
        val intent = Intent(this, ai.opencyvis.ui.ControlPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)

        displayState = DisplayState.CHAT
        updateNotification("OpenCyvis agent running")
        Log.i(TAG, "Returned to CHAT mode")
    }

    // ── VD task escape guard ─────────────────────────────────────────────

    private fun startTaskDisplayGuard(vdm: VirtualDisplayManager) {
        taskDisplayGuard?.stop()
        taskDisplayGuard = TaskDisplayGuard(
            context = this,
            vdm = vdm,
            scope = scope,
            onEscape = { task -> handleControlledTaskEscape(task) }
        ).also { it.start() }
    }

    private fun refreshControlledTasksFromVd(reason: String) {
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        // Use backend (shell uid) for task queries — main process can't access hidden APIs on real devices
        val b = backend ?: return
        val taskId = b.getTopTaskIdOnDisplay(vdm.displayId, packageName)
        if (taskId <= 0) {
            Log.i(TAG, "No controlled VD task found while refreshing ($reason)")
            return
        }
        taskDisplayGuard?.addControlledTaskId(taskId)
        Log.d(TAG, "Refreshed controlled VD task ($reason): #$taskId")
    }

    private fun TaskSnapshot.isHomeTask(): Boolean {
        val packages = setOfNotNull(basePackage, topPackage)
        if (packages.isEmpty()) return false
        return packages.any { it in homePackages() }
    }

    private fun homePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return setOfNotNull(packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName)
    }

    private fun shouldRefreshControlledTasks(result: StepResult): Boolean {
        if (result.actionType == "open_app") return true
        return taskDisplayGuard?.controlledTaskIdsSnapshot()?.isNotEmpty() == true
    }

    private fun handleControlledTaskEscape(task: TaskSnapshot) {
        if (taskRescueInProgress) return
        val vdm = virtualDisplayManager ?: return
        if (!vdm.isCreated) return
        if (task.containsPackage(packageName)) return

        taskRescueInProgress = true
        try {
            // TaskDisplayGuard already did moveTaskToDisplay; just bring our UI back to front
            Log.i(TAG, "Escape callback for task ${task.taskId}, bringing UI to front on Display 0")
            bringOpenCyvisUiToFront()
        } finally {
            taskRescueInProgress = false
        }
    }

    private fun bringOpenCyvisUiToFrontAfterRescue() {
        bringOpenCyvisUiToFront()
    }

    private fun bringOpenCyvisUiToFront() {
        val cls = when (displayState) {
            DisplayState.VIEW, DisplayState.TAKEOVER -> ai.opencyvis.ui.ViewActivity::class.java
            DisplayState.CHAT -> ai.opencyvis.ui.ControlPanelActivity::class.java
        }
        Log.i(TAG, "bringOpenCyvisUiToFront: ${cls.simpleName} on Display 0")
        val intent = Intent(this, cls).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (cls == ai.opencyvis.ui.ViewActivity::class.java) {
                putExtra(ai.opencyvis.ui.ViewActivity.EXTRA_SHOW_CONTROLS, true)
            }
        }
        val options = android.app.ActivityOptions.makeBasic()
        try {
            options.setLaunchDisplayId(0)
        } catch (_: Exception) {}
        startActivity(intent, options.toBundle())
    }

    // ── Public accessors ────────────────────────────────────────────────

    val stateFlow: StateFlow<AgentState>?
        get() = engine?.state

    /** Non-null StateFlow for ImAgentBridge subscription (null value = no engine) */
    val decisionFlow: StateFlow<AgentState?>
        get() = engine?.state ?: MutableStateFlow(null)

    val stepResultFlow: SharedFlow<StepResult>?
        get() = engine?.stepResults

    fun getEngine(): AgentEngine? = engine

    fun getVirtualDisplayManager(): VirtualDisplayManager? = virtualDisplayManager

    fun isOverlayActiveState(state: AgentState?): Boolean {
        return OverlayStatePolicy.isActive(state, displayState)
    }

    /**
     * Launch the secondary home/launcher on the given display so it's not blank.
     * Uses SECONDARY_HOME category — Android's designated launcher for non-default displays.
     */
    private fun launchHomeOnDisplay(displayId: Int) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_SECONDARY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val b = backend
            if (b is RemoteBackend) {
                val intentUri = homeIntent.toUri(Intent.URI_INTENT_SCHEME)
                b.startActivityOnDisplay(intentUri, displayId)
                Log.i(TAG, "Launched secondary home on display $displayId via backend")
            } else {
                val options = android.app.ActivityOptions.makeBasic()
                    .setLaunchDisplayId(displayId)
                startActivity(homeIntent, options.toBundle())
                Log.i(TAG, "Launched secondary home on display $displayId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch home on display $displayId: ${e.message}")
            // Fallback: try regular home
            try {
                val fallbackIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                val b = backend
                if (b is RemoteBackend) {
                    val intentUri = fallbackIntent.toUri(Intent.URI_INTENT_SCHEME)
                    b.startActivityOnDisplay(intentUri, displayId)
                    Log.i(TAG, "Launched home (fallback) on display $displayId via backend")
                } else {
                    val opts = android.app.ActivityOptions.makeBasic()
                        .setLaunchDisplayId(displayId)
                    startActivity(fallbackIntent, opts.toBundle())
                    Log.i(TAG, "Launched home (fallback) on display $displayId")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to launch home fallback on display $displayId: ${e2.message}")
            }
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OpenCyvis Agent",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AI phone control agent service" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ASK,
                getString(R.string.notif_channel_ask),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_ask_desc)
                enableVibration(true)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ai.opencyvis.ui.ControlPanelActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenCyvis")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
