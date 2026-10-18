package ai.opencyvis.remoteim

import android.util.Log
import ai.opencyvis.AgentService
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.StepResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImAgentBridge(
    private val manager: ImChannelManager,
    private val config: ConfigRepository,
    private val stringProvider: ImStringProvider
) {
    companion object {
        private const val TAG = "ImAgentBridge"
        private const val DEBOUNCE_MS = 800L
        private const val TYPING_INTERVAL_MS = 4000L // Telegram typing expires after ~5s
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var agentService: AgentService? = null

    private var activeChannelId: String? = null
    private var activeChatId: String? = null

    private val _state = MutableStateFlow<AgentState?>(null)
    val state: StateFlow<AgentState?> = _state.asStateFlow()

    private var debounceJob: kotlinx.coroutines.Job? = null
    private var typingJob: kotlinx.coroutines.Job? = null
    private var previousState: AgentState? = null

    fun bind(agentService: AgentService) {
        this.agentService = agentService
        observeEngine(agentService)
    }

    fun unbind() {
        agentService = null
        stopTypingIndicator()
        scope.launch { _state.value = null }
    }

    fun bindActiveSession(channelId: String, chatId: String) {
        activeChannelId = channelId
        activeChatId = chatId
    }

    fun statusText(): String {
        val svc = agentService ?: return stringProvider.statusNoService()
        val st = _state.value

        val statusLine = when (st) {
            is AgentState.Idle -> stringProvider.statusIdle(st.resultMessage)
            is AgentState.Running -> stringProvider.statusRunning(st.step, st.thought)
            is AgentState.WaitingForUser -> stringProvider.statusWaitingUser(st.question)
            is AgentState.WaitingForHandoff -> stringProvider.statusWaitingHandoff(st.reason)
            is AgentState.Error -> stringProvider.statusError(st.message)
            is AgentState.Paused -> stringProvider.statusPaused()
            null -> stringProvider.statusIdle(null)
        }

        val deviceLine = stringProvider.statusDevice(
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE
        )
        val phoneLine = buildPhoneLine(svc)
        val llmLine = stringProvider.statusLlm(config.apiProvider, config.model)

        return listOfNotNull(statusLine, deviceLine, phoneLine, llmLine).joinToString("\n")
    }

    private fun buildPhoneLine(svc: AgentService): String? {
        val vdm = svc.getVirtualDisplayManager() ?: return null
        val tasks = vdm.getRunningTasks(limit = 5)
        val topTask = tasks.firstOrNull { it.displayId == vdm.displayId }
            ?: tasks.firstOrNull()
        val app = topTask?.topPackage ?: topTask?.basePackage ?: return null
        return stringProvider.statusPhone(app)
    }

    fun stop() {
        agentService?.stopAgent()
    }

    suspend fun answerAskUser(text: String): Boolean {
        return agentService?.let {
            it.submitUserResponse(text)
            true
        } ?: false
    }

    suspend fun startTask(instruction: String) {
        agentService?.startAgent(instruction)
    }

    private fun observeEngine(svc: AgentService) {
        scope.launch {
            svc.engineFlow.collect { engine ->
                if (engine == null) {
                    _state.value = null
                    return@collect
                }
                launch {
                    engine.state.collect { st ->
                        _state.value = st
                        onStateChanged(st)
                    }
                }
                launch {
                    engine.stepResults.collect { result ->
                        onStepResult(result)
                    }
                }
                launch {
                    engine.stepScreenshots.collect { bytes ->
                        sendScreenshot(bytes)
                    }
                }
            }
        }
    }

    private suspend fun onStateChanged(state: AgentState) {
        val prev = previousState
        previousState = state

        when (state) {
            is AgentState.Running -> startTypingIndicator()
            is AgentState.WaitingForUser -> {
                stopTypingIndicator()
                sendForced(text = stringProvider.notifyAskUser(state.question))
            }
            is AgentState.WaitingForHandoff -> {
                stopTypingIndicator()
                sendForced(text = stringProvider.notifyHandoff(state.reason))
            }
            is AgentState.Paused -> stopTypingIndicator()
            is AgentState.Idle -> {
                stopTypingIndicator()
                // Only notify completion when transitioning from a running/waiting state,
                // not from the initial Idle when a new engine is created.
                if (prev is AgentState.Running || prev is AgentState.WaitingForUser
                    || prev is AgentState.WaitingForHandoff || prev is AgentState.Paused) {
                    sendForced(text = stringProvider.notifyTaskComplete(state.resultMessage))
                }
            }
            is AgentState.Error -> {
                stopTypingIndicator()
                sendForced(text = stringProvider.notifyError(state.message))
            }
        }
    }

    private fun startTypingIndicator() {
        if (typingJob?.isActive == true) return
        val ch = activeChannelId ?: return
        val chat = activeChatId ?: return
        typingJob = scope.launch {
            while (true) {
                try {
                    manager.sendTyping(ch, chat)
                } catch (e: Exception) {
                    Log.w(TAG, "typing indicator failed: ${e.message}")
                }
                delay(TYPING_INTERVAL_MS)
            }
        }
    }

    private fun stopTypingIndicator() {
        typingJob?.cancel()
        typingJob = null
    }

    fun onStepResult(result: StepResult) {
        // Skip final step — completion is handled by onStateChanged(Idle)
        if (result.completed) return

        val thought = result.thought
        if (thought.isBlank()) return

        // debounce: only send the last message within the debounce window
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            sendTextSafe(thought)
        }
    }

    private suspend fun sendForced(text: String) {
        val ch = activeChannelId ?: return
        val chat = activeChatId ?: return
        try {
            manager.sendText(ch, chat, text)
        } catch (e: Exception) {
            Log.e(TAG, "sendForced failed, retrying once", e)
            try {
                delay(1000)
                manager.sendText(ch, chat, text)
            } catch (e2: Exception) {
                Log.e(TAG, "sendForced retry failed", e2)
            }
        }
    }

    private suspend fun sendScreenshot(bytes: ByteArray) {
        val ch = activeChannelId ?: return
        val chat = activeChatId ?: return
        try {
            manager.sendPhoto(ch, chat, bytes, "")
        } catch (e: Exception) {
            Log.w(TAG, "sendScreenshot failed: ${e.message}")
        }
    }

    private suspend fun sendTextSafe(text: String) {
        val ch = activeChannelId ?: return
        val chat = activeChatId ?: return
        try {
            manager.sendText(ch, chat, text)
        } catch (e: Exception) {
            Log.w(TAG, "sendText skipped: ${e.message}")
        }
    }
}
