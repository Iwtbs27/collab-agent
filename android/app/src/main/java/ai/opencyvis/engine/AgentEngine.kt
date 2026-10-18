package ai.opencyvis.engine

import android.graphics.Bitmap
import android.util.Log
import ai.opencyvis.action.Action
import ai.opencyvis.action.ActionExecutor
import ai.opencyvis.accessibility.VdAccessibilityService
import ai.opencyvis.capture.ScreenCapture
import ai.opencyvis.db.GlobalMemoryEntity
import ai.opencyvis.db.GlobalMemoryRepository
import ai.opencyvis.display.VirtualDisplayManager
import ai.opencyvis.llm.LLMClientInterface
import ai.opencyvis.llm.LLMException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Core agent engine: observe-think-act loop.
 * Ported from cli_demo.py run_instruction() and run_auto().
 */
class AgentEngine(
    private val llmClient: LLMClientInterface,
    private val actionExecutor: ActionExecutor,
    private val maxSteps: Int = 100,
    private val virtualDisplayManager: VirtualDisplayManager? = null,
    private val debugMode: Boolean = false,
    private val memoryRepository: GlobalMemoryRepository? = null,
    private val viewTreeProvider: ((Int, Int, Int) -> String?)? = null,
    private val shouldForwardScreenshot: (() -> Boolean)? = null,
    private val blacklistedPackages: Set<String> = emptySet(),
    private val onSaveRoutine: ((
        name: String, icon: String, instruction: String,
        scheduleType: String?, scheduleTime: String?, scheduleRepeat: String?,
        scheduleInterval: Int?, scheduleLocation: String?, scheduleOnEnter: Boolean?
    ) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "AgentEngine"
        val SIDE_EFFECT_ACTIONS = setOf("tap", "swipe", "type_text", "key_event", "open_app", "long_press")
        private const val MAX_HISTORY_MESSAGES = 100
        private const val VD_CAPTURE_MAX_ATTEMPTS = 8
        private const val VD_CAPTURE_RETRY_DELAY_MS = 300L
        private const val VD_IMAGE_READER_TIMEOUT_MS = 600L
        private const val VD_CAPTURE_RECOVERY_THRESHOLD = 3  // Launch home on VD after this many failures

        /**
         * System prompt. Ported from cli_demo.py SYSTEM_PROMPT (lines 286-302).
         */
        val SYSTEM_PROMPT: String get() = LlmPrompts.systemPrompt()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentJob: Job? = null
    private val actionRepeatGuard = ActionRepeatGuard()

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle())
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _stepResults = MutableSharedFlow<StepResult>(replay = 0, extraBufferCapacity = 20)
    val stepResults: SharedFlow<StepResult> = _stepResults.asSharedFlow()

    private val _stepScreenshots = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 5)
    val stepScreenshots: SharedFlow<ByteArray> = _stepScreenshots.asSharedFlow()

    @Volatile
    private var paused = false

    private var consecutiveBlackFrames = 0
    private var lastNonBlackStep = 0

    @Volatile
    private var userResponseDeferred: CompletableDeferred<String?>? = null

    @Volatile
    private var userHandoffDeferred: CompletableDeferred<String?>? = null

    private val pendingUserSupplements = mutableListOf<String>()

    /**
     * Test-only: jump the engine straight into WaitingForUser state without running the LLM loop.
     * Used by ADB test broadcasts to verify ask_user UI / overlay / heads-up plumbing.
     */
    fun debugSimulateAskUser(question: String) {
        Log.i(TAG, "debugSimulateAskUser: $question")
        _state.value = AgentState.WaitingForUser(question, 1)
    }

    fun debugSimulateHandoff(reason: String) {
        Log.i(TAG, "debugSimulateHandoff: $reason")
        _state.value = AgentState.WaitingForHandoff(reason, 1)
    }

    fun debugSimulateRunning(step: Int = 1, thought: String = "debug running") {
        Log.i(TAG, "debugSimulateRunning: step=$step thought=$thought")
        paused = false
        _state.value = AgentState.Running(step, thought)
    }

    /**
     * Start the agent with the given instruction.
     */
    fun start(instruction: String) {
        if (agentJob?.isActive == true) {
            Log.w(TAG, "Agent already running")
            return
        }
        paused = false
        agentJob = scope.launch {
            runAgentLoop(instruction)
        }
    }

    /**
     * Pause the agent loop.
     */
    fun pause() {
        paused = true
        _state.value = AgentState.Paused
    }

    /**
     * Resume the agent loop.
     */
    fun resume() {
        paused = false
        // State will be updated in the loop
    }

    /**
     * Submit user response when engine is in WaitingForUser state.
     */
    fun submitUserResponse(response: String) {
        userResponseDeferred?.complete(response)
    }

    fun completeUserHandoff(source: String = "user_returned_control") {
        userHandoffDeferred?.complete(source)
    }

    fun submitUserSupplement(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        synchronized(pendingUserSupplements) {
            pendingUserSupplements.add(trimmed)
        }
        Log.i(TAG, "submitUserSupplement queued (${trimmed.length} chars)")
    }

    /**
     * Stop the agent.
     */
    fun stop() {
        paused = false
        userResponseDeferred?.complete(null)
        userResponseDeferred = null
        userHandoffDeferred?.complete(null)
        userHandoffDeferred = null
        agentJob?.cancel()
        agentJob = null
        _state.value = AgentState.Idle()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        stop()
        llmClient.shutdown()
    }

    /**
     * Main observe-think-act loop. Ported from cli_demo.py run_instruction().
     */
    private suspend fun runAgentLoop(instruction: String) {
        consecutiveBlackFrames = 0
        lastNonBlackStep = 0

        val messages = mutableListOf<Map<String, Any>>(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT)
        )
        val notes = mutableMapOf<String, String>()
        var pendingUserAnswer: String? = null
        var pendingActionFeedback: String? = null
        var prevViewTree: String? = null
        var prevActionType: String? = null
        var prevScreenFingerprint: ScreenFingerprint? = null
        var consecutiveUnchangedScreens = 0

        try {
            // Wait for VD to stabilize after creation. Android may briefly route
            // the calling app's activity to a new VD; the keyguard dismiss thread
            // in PrivilegedService also needs ~600ms. The mirror cache drain
            // (800ms after creation) clears stale frames.
            if (virtualDisplayManager != null) {
                delay(1200)
            }

            for (step in 1..maxSteps) {
                // Check for pause
                while (paused) {
                    delay(200)
                    if (!scope.isActive) return
                }

                // === BLACKLIST GATE ===
                if (blacklistedPackages.isNotEmpty()) {
                    val displayId = virtualDisplayManager?.displayId ?: 0
                    val topPkg = VdAccessibilityService.getTopPackageOnDisplay(displayId)
                    if (topPkg != null && topPkg in blacklistedPackages) {
                        Log.i(TAG, "Blacklist gate: protected app $topPkg active at step $step")
                        val reason = "Protected app ($topPkg) is active. Returning control to user."
                        _state.value = AgentState.WaitingForHandoff(reason, step)
                        _stepResults.emit(
                            StepResult(step, "blacklist_handoff", "Protected app detected",
                                true, reason, 0, false)
                        )
                        val deferred = CompletableDeferred<String?>()
                        userHandoffDeferred = deferred
                        val handoffSource = deferred.await()
                        userHandoffDeferred = null
                        if (handoffSource == null) {
                            _state.value = AgentState.Idle()
                            return
                        }
                        pendingActionFeedback = "User returned control after protected app."
                        continue
                    }
                }

                val stepStartTime = System.currentTimeMillis()
                _state.value = AgentState.Running(step, "Capturing screenshot...")

                // === OBSERVE ===
                val t0 = System.currentTimeMillis()
                val vdm = virtualDisplayManager
                val screenshotBase64: String? = if (vdm != null) {
                    captureVirtualDisplay(vdm, step)
                } else {
                    Log.w(TAG, "No VirtualDisplayManager — capturing physical screen")
                    ScreenCapture.captureBase64()
                }
                val captureMs = System.currentTimeMillis() - t0

                // Debug: save screenshot to file for inspection
                if (debugMode && screenshotBase64 != null) {
                    try {
                        val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                        val file = java.io.File("/sdcard/opencyvis_step_${step}.jpg")
                        file.writeBytes(bytes)
                        Log.i(TAG, "Debug screenshot saved to ${file.absolutePath} (${bytes.size} bytes)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save debug screenshot", e)
                    }
                }

                if (shouldForwardScreenshot?.invoke() == true && screenshotBase64 != null) {
                    val bytes = android.util.Base64.decode(screenshotBase64, android.util.Base64.DEFAULT)
                    _stepScreenshots.tryEmit(bytes)
                }

                if (screenshotBase64 == null) {
                    _state.value = AgentState.Error("Failed to capture screenshot")
                    _stepResults.emit(
                        StepResult(step, "error", "Screenshot failed", false,
                            "Failed to capture screenshot", 0, true)
                    )
                    return
                }
                val screenFingerprint = ScreenFingerprint.fromBase64(screenshotBase64)

                // FLAG_SECURE detection: consecutive strictly-black frames indicate a
                // privacy-protected screen. Use strict check (all sampled pixels == 0x000000)
                // to avoid false positives from app transition animations which have
                // status bar / navigation bar colors.
                if (isBitmapStrictlyBlack(screenshotBase64)) {
                    consecutiveBlackFrames++
                    Log.d(TAG, "Strictly black frame detected ($consecutiveBlackFrames consecutive, lastNonBlack=$lastNonBlackStep)")
                } else {
                    consecutiveBlackFrames = 0
                    lastNonBlackStep = step
                }

                // Trigger handoff if:
                // - 3+ consecutive strictly-black frames (rules out transition animations)
                // - Had a non-black frame before (not screen-off from start)
                // - Not already on step 1 (rules out initial state issues)
                if (consecutiveBlackFrames >= 3 && lastNonBlackStep > 0) {
                    Log.i(TAG, "FLAG_SECURE handoff triggered at step $step")
                    consecutiveBlackFrames = 0  // reset to prevent re-trigger if user resumes

                    val reason = "I may not be able to see this screen — it appears to be privacy-protected. Please take over to handle this part."
                    _state.value = AgentState.WaitingForHandoff(reason, step)
                    _stepResults.emit(
                        StepResult(step, "flag_secure_handoff", "Privacy-protected screen detected",
                            true, reason, System.currentTimeMillis() - stepStartTime, false)
                    )

                    val deferred = CompletableDeferred<String?>()
                    userHandoffDeferred = deferred
                    val handoffSource = deferred.await()
                    userHandoffDeferred = null

                    if (handoffSource == null) {
                        _state.value = AgentState.Idle()
                        return
                    }

                    // After user returns control, check if still black
                    pendingActionFeedback = "User returned control after privacy-protected screen."
                    continue  // take fresh screenshot
                }

                // === VIEW TREE ===
                val viewTree: String? = if (vdm != null && viewTreeProvider != null) {
                    try {
                        viewTreeProvider.invoke(vdm.displayId, vdm.width, vdm.height)
                    } catch (e: Exception) {
                        Log.w(TAG, "ViewTree capture failed", e)
                        null
                    }
                } else null
                if (viewTree != null) {
                    Log.i(TAG, "Step $step ViewTree: ${viewTree.length} chars, ${viewTree.lines().size} nodes")
                }

                // Detect unchanged screen after side-effect action (viewTree-based)
                if (step > 1 && prevActionType in SIDE_EFFECT_ACTIONS &&
                    prevViewTree != null && viewTree != null &&
                    prevViewTree == viewTree && pendingActionFeedback == null) {
                    pendingActionFeedback = String.format(
                        LlmPrompts.agentFeedback("screen_unchanged"), prevActionType
                    )
                    Log.w(TAG, "Step $step: screen unchanged after $prevActionType action (viewTree)")
                }

                // Detect unchanged screen via ScreenFingerprint (works without accessibility service)
                if (step > 1 && prevActionType in SIDE_EFFECT_ACTIONS && screenFingerprint != null) {
                    val prev = prevScreenFingerprint
                    if (prev != null && screenFingerprint.isSimilarTo(prev)) {
                        consecutiveUnchangedScreens++
                    } else {
                        consecutiveUnchangedScreens = 0
                    }
                    if (consecutiveUnchangedScreens >= 3 && consecutiveUnchangedScreens % 3 == 0
                        && pendingActionFeedback == null) {
                        pendingActionFeedback = LlmPrompts.agentFeedback("screen_stuck")
                        Log.w(TAG, "Step $step: screen stuck for $consecutiveUnchangedScreens steps (fingerprint)")
                    }
                }
                prevScreenFingerprint = screenFingerprint

                // Build user message — on step 1, hint that the VD may be blank
                val t1 = System.currentTimeMillis()
                val effectiveInstruction = if (step == 1 && vdm != null) {
                    "$instruction\n${LlmPrompts.agentFeedback("vd_blank_hint")}"
                } else {
                    instruction
                }
                val userSupplements = drainUserSupplements()
                val globalMemories = memoryRepository?.getEnabled().orEmpty()
                val userMsg = buildUserMessage(
                    screenshotBase64 = screenshotBase64,
                    instruction = effectiveInstruction,
                    notes = notes,
                    userAnswer = pendingUserAnswer,
                    actionFeedback = pendingActionFeedback,
                    userSupplements = userSupplements,
                    globalMemories = globalMemories,
                    viewTree = viewTree
                )
                pendingUserAnswer = null
                pendingActionFeedback = null
                messages.add(userMsg)

                // Strip images from older messages
                stripImagesFromHistory(messages)
                val encodeMs = System.currentTimeMillis() - t1

                // === THINK ===
                _state.value = AgentState.Running(step, "Thinking...")
                val t2 = System.currentTimeMillis()

                val resultData: Map<String, Any?>
                try {
                    resultData = llmClient.chatWithTools(messages)
                } catch (e: LLMException) {
                    val llmMs = System.currentTimeMillis() - t2
                    Log.w(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms (ERROR)")
                    _state.value = AgentState.Error("LLM error: ${e.message}")
                    _stepResults.emit(
                        StepResult(step, "error", e.message ?: "LLM error", false,
                            "LLM API error: ${e.message}", llmMs, true)
                    )
                    return
                }
                val llmMs = System.currentTimeMillis() - t2

                Log.i(TAG, "Step $step LLM response: thought=${resultData["thought"]}, action_type=${resultData["action_type"]}, reason=${resultData["reason"]}, question=${resultData["question"]}")
                val debugInfo = if (debugMode) formatDebugInfo(resultData) else null

                val thought = resultData["thought"] as? String ?: ""
                // Infer action_type when the model omits it but includes action-specific fields
                val rawActionType = resultData["action_type"] as? String
                val actionType = when {
                    !rawActionType.isNullOrBlank() -> rawActionType
                    (resultData["question"] as? String).isNullOrBlank().not() -> "ask_user"
                    (resultData["handoff_reason"] as? String).isNullOrBlank().not() -> "handoff_user"
                    (resultData["reason"] as? String).isNullOrBlank().not() -> "fail"
                    else -> "unknown"
                }
                if (rawActionType.isNullOrBlank()) {
                    Log.w(TAG, "Step $step: action_type missing, inferred '$actionType'. resultData=$resultData")
                } else if (actionType == "unknown") {
                    Log.w(TAG, "Step $step: LLM returned unrecognized action_type. resultData=$resultData")
                }

                _state.value = AgentState.Running(step, thought)

                // Append assistant response to history
                val resultJson = JSONObject(resultData.mapValues { it.value }).toString()
                messages.add(mapOf("role" to "assistant", "content" to resultJson))

                // Check for terminal actions
                if (actionType == "finish" || actionType == "fail") {
                    val detail = if (actionType == "finish") {
                        "Task finished: $thought"
                    } else {
                        "Task failed: ${resultData["reason"] ?: thought}"
                    }
                    val totalMs = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=0ms total=${totalMs}ms [$actionType]")
                    Log.i(TAG, "Task completed at step $step [$actionType]: ${detail.take(100)}")
                    val sugName = resultData["suggested_routine_name"] as? String
                    val sugIcon = resultData["suggested_routine_icon"] as? String
                    _stepResults.emit(
                        StepResult(step, actionType, thought, actionType == "finish",
                            detail, totalMs, true, debugInfo,
                            suggestedRoutineName = sugName,
                            suggestedRoutineIcon = sugIcon)
                    )
                    _state.value = if (actionType == "finish") {
                        AgentState.Idle(resultMessage = thought)
                    } else {
                        AgentState.Error(resultData["reason"] as? String ?: thought)
                    }
                    return
                }

                // Handle ask_user: suspend until user answers
                if (actionType == "ask_user") {
                    val question = resultData["question"] as? String ?: thought
                    val totalMsAsk = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "WaitingForUser(question=$question, step=$step)")
                    _state.value = AgentState.WaitingForUser(question, step)
                    _stepResults.emit(StepResult(step, "ask_user", thought, true, "Asking: $question", totalMsAsk, false))

                    val deferred = CompletableDeferred<String?>()
                    userResponseDeferred = deferred
                    val userAnswer = deferred.await()
                    userResponseDeferred = null

                    if (userAnswer == null) {
                        _state.value = AgentState.Idle()
                        return
                    }

                    // Store answer to be merged into the next user message (with screenshot).
                    // Avoids two consecutive user messages which confuses the LLM.
                    pendingUserAnswer = userAnswer
                    continue  // go to next loop iteration to take screenshot
                }

                // Handle handoff_user: suspend while user enters sensitive data directly on device.
                if (actionType == "handoff_user") {
                    val reason = resultData["handoff_reason"] as? String
                        ?: resultData["reason"] as? String
                        ?: thought.ifBlank { LlmPrompts.agentFeedback("handoff_default_reason") }
                    val totalMsHandoff = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "WaitingForHandoff(reason=$reason, step=$step)")
                    _state.value = AgentState.WaitingForHandoff(reason, step)
                    _stepResults.emit(
                        StepResult(
                            step,
                            "handoff_user",
                            thought,
                            true,
                            "Handing off to user: $reason",
                            totalMsHandoff,
                            false,
                            debugInfo
                        )
                    )

                    val deferred = CompletableDeferred<String?>()
                    userHandoffDeferred = deferred
                    val handoffSource = deferred.await()
                    userHandoffDeferred = null

                    if (handoffSource == null) {
                        _state.value = AgentState.Idle()
                        return
                    }

                    pendingActionFeedback = String.format(LlmPrompts.agentFeedback("handoff_completed"), handoffSource)
                    continue
                }

                // === NOTE (side-effect) ===
                // Any action can carry an optional "note" field to record information
                val noteValue = resultData["note"] as? String
                if (!noteValue.isNullOrBlank()) {
                    val (noteKey, noteVal) = parseNote(noteValue)
                    // Enforce max 10 notes — evict oldest when full
                    if (notes.size >= 10 && !notes.containsKey(noteKey)) {
                        val oldest = notes.keys.first()
                        notes.remove(oldest)
                    }
                    notes[noteKey] = noteVal
                    Log.i(TAG, "Step $step note recorded: $noteKey = $noteVal (total: ${notes.size})")
                }

                val memoryKey = resultData["memory_key"] as? String
                val memoryValue = resultData["memory_value"] as? String
                if (!memoryKey.isNullOrBlank() && !memoryValue.isNullOrBlank()) {
                    val memoryCategory = resultData["memory_category"] as? String ?: ""
                    memoryRepository?.upsert(
                        key = memoryKey,
                        value = memoryValue,
                        category = memoryCategory,
                        source = GlobalMemoryEntity.SOURCE_AI
                    )
                    Log.i(TAG, "Step $step global memory recorded: $memoryKey (${memoryCategory.ifBlank { "uncategorized" }})")
                }

                // Handle standalone note action: record and continue to next observe cycle
                if (actionType == "note") {
                    val totalMsNote = System.currentTimeMillis() - stepStartTime
                    Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms [note]")
                    _stepResults.emit(StepResult(step, "note", thought, true,
                        "Note: ${noteValue ?: thought}", totalMsNote, false, debugInfo))
                    delay(200) // brief delay before next observe
                    continue
                }

                if (actionType == "remember") {
                    val totalMsRemember = System.currentTimeMillis() - stepStartTime
                    _stepResults.emit(
                        StepResult(
                            step,
                            "remember",
                            thought,
                            true,
                            "Remembered: ${memoryKey ?: thought}",
                            totalMsRemember,
                            false,
                            debugInfo
                        )
                    )
                    delay(200)
                    continue
                }

                if (actionType == "save_routine") {
                    val routineName = (resultData["routine_name"] as? String) ?: ""
                    val routineIcon = (resultData["routine_icon"] as? String) ?: "⚡"
                    val routineInstruction = (resultData["routine_instruction"] as? String) ?: instruction
                    val scheduleType = resultData["schedule_type"] as? String
                    val scheduleTime = resultData["schedule_time"] as? String
                    val scheduleRepeat = resultData["schedule_repeat"] as? String
                    val scheduleInterval = (resultData["schedule_interval"] as? Number)?.toInt()
                    val scheduleLocation = resultData["schedule_location"] as? String
                    val scheduleOnEnter = resultData["schedule_on_enter"] as? Boolean

                    onSaveRoutine?.invoke(
                        routineName, routineIcon, routineInstruction,
                        scheduleType, scheduleTime, scheduleRepeat,
                        scheduleInterval, scheduleLocation, scheduleOnEnter
                    )

                    val totalMs = System.currentTimeMillis() - stepStartTime
                    val scheduleDesc = when (scheduleType) {
                        "time" -> "@ $scheduleTime ${scheduleRepeat ?: "daily"}"
                        "interval" -> "every ${scheduleInterval}min"
                        "geofence" -> "${if (scheduleOnEnter != false) "arrive" else "leave"} $scheduleLocation"
                        else -> ""
                    }
                    val desc = if (scheduleDesc.isNotEmpty()) "$routineName ($scheduleDesc)" else routineName
                    Log.i(TAG, "Step $step: save_routine: $desc")
                    _stepResults.emit(
                        StepResult(step, actionType, thought, true,
                            "Routine created: $desc", totalMs, true, debugInfo)
                    )

                    _state.value = AgentState.Idle(resultMessage = "Routine created: $desc")
                    return
                }

                // === ACT ===
                val t3 = System.currentTimeMillis()
                val action = Action.fromMap(resultData)
                _state.value = AgentState.Running(step, "Executing: $actionType")

                when (val repeatDecision = actionRepeatGuard.evaluate(action, screenFingerprint)) {
                    is ActionRepeatGuard.Decision.Block -> {
                        val totalMs = System.currentTimeMillis() - stepStartTime
                        pendingActionFeedback = repeatDecision.feedback
                        Log.w(TAG, "Step $step action blocked by repeat guard: ${repeatDecision.feedback}")
                        _stepResults.emit(
                            StepResult(
                                step = step,
                                actionType = action.typeName,
                                thought = thought,
                                success = false,
                                detail = repeatDecision.feedback,
                                durationMs = totalMs,
                                completed = false,
                                debugInfo = debugInfo
                            )
                        )
                        delay(300)
                        continue
                    }
                    ActionRepeatGuard.Decision.Allow -> Unit
                }

                val stepResult = actionExecutor.execute(action, step)
                if (stepResult.success) {
                    actionRepeatGuard.recordExecuted(action, screenFingerprint)
                }

                // Feed action failure back to LLM in the next iteration
                if (!stepResult.success) {
                    pendingActionFeedback = String.format(LlmPrompts.agentFeedback("action_failed"), stepResult.detail)
                    Log.w(TAG, "Step $step action failed, will feed back to LLM: ${stepResult.detail}")
                } else if (actionType == "list_apps") {
                    pendingActionFeedback = stepResult.detail
                    Log.i(TAG, "Step $step list_apps result: ${stepResult.detail.take(200)}")
                }

                val actionMs = System.currentTimeMillis() - t3

                val totalMs = System.currentTimeMillis() - stepStartTime

                val finalResult = stepResult.copy(
                    completed = false,
                    durationMs = totalMs,
                    debugInfo = debugInfo
                )
                _stepResults.emit(finalResult)

                Log.i(TAG, "Step $step TIMING: capture=${captureMs}ms encode=${encodeMs}ms llm=${llmMs}ms action=${actionMs}ms total=${totalMs}ms [$actionType]")

                // Wait for screen update before next step
                prevViewTree = viewTree
                prevActionType = actionType
                delay(if (actionType == "open_app") 2000 else 1000)

                // Trim conversation history
                if (messages.size > MAX_HISTORY_MESSAGES) {
                    val systemMsg = messages[0]
                    val recent = messages.takeLast(MAX_HISTORY_MESSAGES - 1)
                    messages.clear()
                    messages.add(systemMsg)
                    messages.addAll(recent)
                }
            }

            // Max steps reached
            Log.w(TAG, "max_steps reached at step $maxSteps, stopping agent")
            _state.value = AgentState.Idle(resultMessage = String.format(LlmPrompts.agentFeedback("max_steps_reached"), maxSteps))
            _stepResults.emit(
                StepResult(maxSteps, "max_steps", "Reached max steps ($maxSteps)",
                    false, "Max steps reached", 0, true)
            )

        } catch (e: CancellationException) {
            Log.i(TAG, "Agent loop cancelled")
            _state.value = AgentState.Idle()
        } catch (e: Exception) {
            Log.e(TAG, "Agent loop error", e)
            _state.value = AgentState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Capture a frame from the virtual display.
     *
     * Strategy:
     * 1. ScreenCapture API (SurfaceFlinger, API 36+) — fast, works for most windows
     * 2. If ScreenCapture returns null (API <36) or all-black (FLAG_SECURE),
     *    fall back to ImageReader capture which reads directly from the VD's
     *    own surface and works on all API levels.
     *
     * Recovery: After 3 consecutive failures, launches home on the VD to ensure
     * an Activity is rendered (fixes empty VD state after failed app launch).
     */
    private suspend fun captureVirtualDisplay(vdm: VirtualDisplayManager, step: Int): String? {
        for (attempt in 1..VD_CAPTURE_MAX_ATTEMPTS) {
            if (vdm.hasLocalDisplay) {
                val irBitmap = withContext(Dispatchers.IO) {
                    vdm.captureViaImageReader(timeoutMs = VD_IMAGE_READER_TIMEOUT_MS)
                }
                if (irBitmap != null) {
                    Log.d(TAG, "Step $step: captured via local ImageReader (${irBitmap.width}x${irBitmap.height})")
                    if (isBitmapBlack(irBitmap)) {
                        Log.w(TAG, "ImageReader returned black frame (FLAG_SECURE?) — returning for handoff detection")
                    }
                    return ScreenCapture.captureBase64(virtualDisplayBitmap = irBitmap)
                }
            } else {
                Log.d(TAG, "Step $step: remote VD, skipping local ImageReader")
            }

            // Fallback 1: backend capture via Binder
            val backendBase64 = withContext(Dispatchers.IO) {
                Log.d(TAG, "Step $step: trying backend capture (displayId=${vdm.displayId}, backend=${ScreenCapture.backend.javaClass.simpleName})")
                ScreenCapture.captureBase64(displayId = vdm.displayId)
            }
            if (backendBase64 != null) {
                Log.d(TAG, "Step $step: captured via backend (${backendBase64.length} chars)")
                if (isBitmapLikelyBlack(backendBase64)) {
                    Log.w(TAG, "Backend capture returned black frame (FLAG_SECURE?) — returning for handoff detection")
                }
                return backendBase64
            }
            Log.w(TAG, "Step $step: backend capture returned null")

            // Recovery: after 3 failed attempts, launch home on VD to ensure content renders
            if (attempt == VD_CAPTURE_RECOVERY_THRESHOLD) {
                val topTask = vdm.getTopTaskIdOnDisplay(vdm.displayId)
                if (topTask == null || topTask <= 0) {
                    Log.w(TAG, "VD empty after $attempt capture failures, launching home")
                    withContext(Dispatchers.IO) {
                        ScreenCapture.backend.ensureVdHasContent(vdm.displayId)
                    }
                    delay(1000)  // Wait for home to render a frame
                } else {
                    Log.w(TAG, "VD has task $topTask but no new frame — using cached frame")
                }
            } else if (attempt < VD_CAPTURE_MAX_ATTEMPTS) {
                Log.d(TAG, "VD capture failed at step $step (attempt $attempt/$VD_CAPTURE_MAX_ATTEMPTS), waiting ${VD_CAPTURE_RETRY_DELAY_MS}ms...")
                delay(VD_CAPTURE_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "VD capture failed at step $step after $VD_CAPTURE_MAX_ATTEMPTS attempts")
        return null
    }

    private fun formatDebugInfo(resultData: Map<String, Any?>): String {
        return try {
            JSONObject(resultData).toString(2).take(2000)
        } catch (_: Exception) {
            resultData.toString().take(2000)
        }
    }

    /**
     * Quick check if a bitmap is all-black (or nearly so).
     * Samples a grid of pixels rather than checking every pixel.
     */
    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return true
        val sampleCount = 20
        for (i in 0 until sampleCount) {
            val x = (w * (i + 1)) / (sampleCount + 1)
            val y = (h * (i + 1)) / (sampleCount + 1)
            val pixel = bitmap.getPixel(x, y)
            // Check if pixel has any non-black color (ignore alpha)
            if ((pixel and 0x00FFFFFF) != 0) return false
        }
        return true
    }

    /**
     * Strict black check: ALL sampled pixels must be near-black (< 5 per channel).
     * FLAG_SECURE returns pure black; transition animations have status/nav bar colors.
     * Small threshold accounts for JPEG compression artifacts on pure black input.
     */
    private fun isBitmapStrictlyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                val w = bitmap.width
                val h = bitmap.height
                if (w == 0 || h == 0) return true
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r >= 5 || g >= 5 || b >= 5) return false
                }
                true
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Decode a base64 JPEG and check if the image is mostly black (90%+ dark pixels).
     * Used to detect FLAG_SECURE screens where screenshots return black frames.
     */
    private fun isBitmapLikelyBlack(base64Jpeg: String): Boolean {
        return try {
            val bytes = android.util.Base64.decode(base64Jpeg, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            try {
                // Sample 20 pixels across the image to check if it's mostly black
                val w = bitmap.width
                val h = bitmap.height
                var darkPixels = 0
                val sampleCount = 20
                for (i in 0 until sampleCount) {
                    val x = (w * (i + 1)) / (sampleCount + 1)
                    val y = (h * (i + 1)) / (sampleCount + 1)
                    val pixel = bitmap.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r < 15 && g < 15 && b < 15) darkPixels++
                }
                darkPixels >= sampleCount * 0.9  // 90%+ dark pixels = likely black
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) { false }
    }

    /**
     * Build user message with screenshot in vision-LLM format.
     * If userAnswer is provided, it is prepended to the instruction text so the LLM
     * sees the user's reply and the current screenshot in a single message — avoiding
     * two consecutive user messages which can confuse the model.
     */
    /**
     * Parse a note string into key-value pair.
     * Format: "key: value" → Pair(key, value). If no colon, uses the full string as both.
     */
    private fun parseNote(note: String): Pair<String, String> {
        val colonIndex = note.indexOf(':')
        return if (colonIndex > 0) {
            val key = note.substring(0, colonIndex).trim()
            val value = note.substring(colonIndex + 1).trim()
            key to value
        } else {
            note.trim() to note.trim()
        }
    }

    private fun drainUserSupplements(): List<String> {
        synchronized(pendingUserSupplements) {
            if (pendingUserSupplements.isEmpty()) return emptyList()
            val drained = pendingUserSupplements.toList()
            pendingUserSupplements.clear()
            return drained
        }
    }

    private fun buildUserMessage(
        screenshotBase64: String,
        instruction: String,
        notes: Map<String, String> = emptyMap(),
        userAnswer: String? = null,
        actionFeedback: String? = null,
        userSupplements: List<String> = emptyList(),
        globalMemories: List<GlobalMemoryEntity> = emptyList(),
        viewTree: String? = null
    ): Map<String, Any> {
        var text = instruction
        if (userAnswer != null) {
            text = String.format(LlmPrompts.agentFeedback("user_answer_prefix"), userAnswer) + text
        }
        if (actionFeedback != null) {
            text = String.format(LlmPrompts.agentFeedback("system_feedback_prefix"), actionFeedback) + text
        }
        if (!viewTree.isNullOrBlank()) {
            text = "$text${LlmPrompts.agentFeedback("ui_elements_header")}$viewTree"
        }
        if (userSupplements.isNotEmpty()) {
            val supplementBlock = userSupplements.joinToString("\n") { "- $it" }
            text = "$text${LlmPrompts.agentFeedback("user_supplement_header")}$supplementBlock"
        }
        if (globalMemories.isNotEmpty()) {
            val memoryBlock = globalMemories.joinToString("\n") {
                val category = if (it.category.isBlank()) "" else "[${it.category}] "
                "- $category${it.key}: ${it.value}"
            }
            text = "$text${LlmPrompts.agentFeedback("global_memory_header")}$memoryBlock"
        }
        if (notes.isNotEmpty()) {
            val notesBlock = notes.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
            text = "$text${LlmPrompts.agentFeedback("notes_header")}$notesBlock"
        }
        val content = listOf(
            mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to "data:image/jpeg;base64,$screenshotBase64")
            ),
            mapOf(
                "type" to "text",
                "text" to text
            )
        )
        return mapOf("role" to "user", "content" to content)
    }

    /**
     * Remove base64 images from older messages to reduce token usage.
     * Only keeps the image in the very last user message.
     * Ported from cli_demo.py _strip_images_from_history().
     */
    private fun stripImagesFromHistory(messages: MutableList<Map<String, Any>>) {
        if (messages.size < 2) return

        for (i in 0 until messages.size - 1) {
            val msg = messages[i]
            val content = msg["content"]
            if (content is List<*>) {
                @Suppress("UNCHECKED_CAST")
                val contentList = content as List<Map<String, Any>>
                val filtered = contentList.filter { it["type"] != "image_url" }

                val newMsg = msg.toMutableMap()
                if (filtered.size == 1 && filtered[0]["type"] == "text") {
                    // Simplify to plain string
                    newMsg["content"] = filtered[0]["text"] as? String ?: ""
                } else {
                    newMsg["content"] = filtered
                }
                messages[i] = newMsg
            }
        }
    }
}
