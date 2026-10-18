package ai.opencyvis.ui

import android.Manifest
import android.text.format.DateUtils
import ai.opencyvis.db.ConversationEntity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.backend.SetupActivity
import ai.opencyvis.SettingsActivity
import ai.opencyvis.config.ConfigRepository
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.db.RoutineEntity
import ai.opencyvis.db.RoutineDao
import ai.opencyvis.engine.AgentEngine
import ai.opencyvis.engine.AgentState
import ai.opencyvis.voice.SherpaOnnxSpeechInputEngine
import ai.opencyvis.voice.VoiceInputController
import ai.opencyvis.voice.VoiceInputTestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Main control panel activity — chat-style interface for instructions and agent status.
 * "View" button launches ViewActivity with SurfaceView rendering VD content.
 */
class ControlPanelActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ControlPanel"
        private const val REQUEST_RECORD_AUDIO = 2001
        private const val REQUEST_NOTIFICATIONS = 2002
        private const val REQUEST_LOCATION = 2003
        private const val REQUEST_SETUP = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agentService: AgentService? = null
    private var bound = false
    private var setupLaunched = false
    private var stateCollectionJob: Job? = null
    private var stepResultCollectionJob: Job? = null
    private var engineSubscriptionJob: Job? = null
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var config: ConfigRepository

    // Views
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var inputBar: LinearLayout
    private lateinit var actionButtons: LinearLayout
    private lateinit var editInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnVoiceInput: Button
    private lateinit var btnViewOp: Button
    private lateinit var btnStop: Button
    private lateinit var routinesContainer: ScrollView
    private lateinit var textGreeting: TextView
    private lateinit var cardRecommended: LinearLayout
    private lateinit var recommendedIcon: TextView
    private lateinit var recommendedName: TextView
    private lateinit var recommendedDesc: TextView
    private lateinit var routineChipsRecycler: RecyclerView
    private lateinit var routineChipAdapter: RoutineChipAdapter
    private lateinit var labelRecent: TextView
    private lateinit var containerRecentRoutines: LinearLayout
    private lateinit var voiceInputController: VoiceInputController
    private lateinit var routineDao: RoutineDao
    private var voiceTestReceiverRegistered = false
    private var demoReceiverRegistered = false
    private var scrollPending = false
    private var lastInstruction: String? = null
    private var hasVdContent = false

    private val demoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Switch active profile via broadcast
            val profileName = intent.getStringExtra("switch_profile")
            if (profileName != null) {
                val profileRepo = ai.opencyvis.config.ProfileRepository(context)
                val success = profileRepo.switchTo(profileName)
                Log.i(TAG, "switch_profile '$profileName' → $success")
                return
            }
            // Set config directly via broadcast
            val setModel = intent.getStringExtra("set_model")
            if (setModel != null) {
                val cfg = ai.opencyvis.config.ConfigRepository(context)
                val key = intent.getStringExtra("set_key") ?: cfg.apiKey
                val url = intent.getStringExtra("set_url") ?: cfg.baseUrl
                val provider = intent.getStringExtra("set_provider") ?: cfg.apiProvider
                cfg.applyProfile(provider, key, setModel, url, "$provider/$setModel")
                Log.i(TAG, "set_config: provider=$provider model=$setModel url=$url key=${key.take(10)}…")
                return
            }
            // Run VD POC test
            if (intent.hasExtra("run_vd_poc")) {
                Log.i(TAG, "Running LocalVdPoc test...")
                Thread {
                    val result = ai.opencyvis.backend.LocalVdPoc.test(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, result.takeLast(100), android.widget.Toast.LENGTH_LONG).show()
                    }
                }.start()
                return
            }
            // Start pairing service
            if (intent.hasExtra("start_pairing")) {
                Log.i(TAG, "Starting AdbPairingService from foreground")
                try {
                    context.startForegroundService(
                        ai.opencyvis.backend.AdbPairingService.startIntent(context))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start pairing service", e)
                }
                return
            }
            // Handle pairing code for wireless ADB testing
            val pairCode = intent.getStringExtra("pair_code")
            if (pairCode != null) {
                Log.i(TAG, "Received pairing code via broadcast: $pairCode")
                val connector = ai.opencyvis.backend.DirectConnector.lastInstance
                    ?: ai.opencyvis.backend.DirectConnector(context).also {
                        ai.opencyvis.backend.DirectConnector.lastInstance = it
                    }
                connector.submitPairingCode(pairCode)
                return
            }
            val text = intent.getStringExtra("demo") ?: return
            val delay = intent.getLongExtra("char_delay", 50L)
            Log.i(TAG, "Demo broadcast: typing '$text' then sending")
            editInput.text.clear()
            editInput.requestFocus()
            var index = 0
            val handler = editInput.handler ?: return
            val typeRunnable = object : Runnable {
                override fun run() {
                    if (index < text.length) {
                        editInput.append(text[index].toString())
                        index++
                        handler.postDelayed(this, delay)
                    } else {
                        handler.postDelayed({ btnSend.performClick() }, 500L)
                    }
                }
            }
            handler.postDelayed(typeRunnable, 300L)
        }
    }

    private val voiceTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = intent.getStringExtra(VoiceInputTestBridge.EXTRA_TARGET)
            if (target != VoiceInputTestBridge.TARGET_COMMAND &&
                target != VoiceInputTestBridge.TARGET_CONTROL_ANSWER
            ) return
            val result = intent.getStringExtra(VoiceInputTestBridge.EXTRA_RESULT) ?: return
            voiceInputController.injectFinalResult(result)
            Log.i(TAG, "Voice input injected target=$target text=$result")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? AgentService.AgentBinder)?.getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")
            subscribeToEngineFlow()
            // Auto-launch setup if no backend available (standard flavor first launch)
            checkBackendAndPromptSetup(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            engineSubscriptionJob?.cancel()
            stateCollectionJob?.cancel()
            stepResultCollectionJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_control_panel)
        historyRepo = ChatHistoryRepository(this)
        config = ConfigRepository(this)

        // Bind views
        statusText = findViewById(R.id.text_status)
        chatRecycler = findViewById(R.id.recycler_chat)
        inputBar = findViewById(R.id.input_bar)
        actionButtons = findViewById(R.id.action_buttons)
        editInput = findViewById(R.id.edit_input)
        btnSend = findViewById(R.id.btn_send)
        btnVoiceInput = findViewById(R.id.btn_voice_input)
        btnViewOp = findViewById(R.id.btn_view_operation)
        btnStop = findViewById(R.id.btn_stop)

        voiceInputController = VoiceInputController(
            SherpaOnnxSpeechInputEngine(this, downloadProgressListener = ::onAsrDownloadProgress),
            editTextTarget(editInput),
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    btnVoiceInput.text = if (isListening) "■" else "🎙"
                    btnVoiceInput.isSelected = isListening
                }

                override fun onError(message: String) {
                    dismissAsrDownloadDialog()
                    Toast.makeText(this@ControlPanelActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
        registerVoiceTestReceiver()

        // Chat setup
        chatAdapter = ChatAdapter { message, view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add(0, 1, 0, R.string.routine_longpress_copy)
            popup.menu.add(0, 2, 0, R.string.routine_longpress_save)
            popup.menu.add(0, 3, 0, R.string.routine_longpress_rerun)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("instruction", message.text))
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    2 -> showSaveRoutineDialog(message)
                    3 -> {
                        val service = agentService ?: return@setOnMenuItemClickListener true
                        chatAdapter.addMessage(ChatMessage(MessageType.USER_INPUT, message.text))
                        ensureServiceStarted()
                        service.startAgent(message.text)
                    }
                }
                true
            }
            popup.show()
        }
        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        chatRecycler.adapter = chatAdapter

        // Send button — start agent or submit answer
        btnSend.setOnClickListener {
            val text = editInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val service = agentService
            if (service == null) {
                Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentState = service.stateFlow?.value
            when (currentState) {
                is AgentState.WaitingForUser -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_ANSWER, text))
                    service.submitUserResponse(text)
                    editInput.text.clear()
                    editInput.hint = getString(R.string.supplement_hint)
                }
                is AgentState.Running, is AgentState.Paused, is AgentState.WaitingForHandoff -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_SUPPLEMENT, getString(R.string.supplement_prefix, text)))
                    service.submitUserSupplement(text)
                    editInput.text.clear()
                    editInput.hint = getString(R.string.supplement_hint)
                }
                else -> {
                    // Check if backend is available before starting agent
                    val backendName = service.activeBackendName
                    if (backendName == null || backendName == "none") {
                        // No backend connected — launch setup wizard
                        setupLaunched = true
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(this, SetupActivity::class.java),
                            REQUEST_SETUP
                        )
                        return@setOnClickListener
                    }
                    // Check overlay permission before starting agent task
                    if (!android.provider.Settings.canDrawOverlays(this)) {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(R.string.overlay_permission_title)
                            .setMessage(R.string.overlay_permission_message)
                            .setPositiveButton(R.string.overlay_permission_grant) { _, _ ->
                                startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:$packageName")
                                    )
                                )
                            }
                            .setNegativeButton(R.string.routine_ai_dismiss_btn, null)
                            .show()
                    }
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_INPUT, text))
                    ensureServiceStarted()
                    lastInstruction = text
                    hasVdContent = false
                    service.startAgent(text)
                    editInput.text.clear()
                    actionButtons.visibility = View.VISIBLE
                    hideRoutines()
                }
            }
            scrollChatToBottom()
        }

        btnVoiceInput.setOnClickListener {
            if (voiceInputController.isListening) {
                voiceInputController.stop()
            } else if (ensureRecordAudioPermission()) {
                voiceInputController.start()
            }
        }

        findViewById<Button>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, ConversationHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.btn_memory).setOnClickListener {
            startActivity(Intent(this, MemoryActivity::class.java))
        }

        // Settings button
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Routines (idle state)
        routinesContainer = findViewById(R.id.routines_container)
        textGreeting = findViewById(R.id.text_greeting)
        cardRecommended = findViewById(R.id.card_recommended)
        recommendedIcon = findViewById(R.id.recommended_icon)
        recommendedName = findViewById(R.id.recommended_name)
        recommendedDesc = findViewById(R.id.recommended_desc)
        routineChipsRecycler = findViewById(R.id.recycler_routine_chips)
        labelRecent = findViewById(R.id.label_recent)
        containerRecentRoutines = findViewById(R.id.container_recent_routines)

        routineDao = AppDatabase.getInstance(this).routineDao()
        scope.launch {
            withContext(Dispatchers.IO) {
                if (routineDao.getCount() == 0) {
                    AppDatabase.ensureBuiltinRoutines(this@ControlPanelActivity)
                }
            }
        }

        routineChipAdapter = RoutineChipAdapter(
            onItemClick = { routine -> executeRoutine(routine) },
            onItemLongClick = { routine -> showRoutineManageDialog(routine) }
        )
        routineChipsRecycler.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        routineChipsRecycler.adapter = routineChipAdapter

        cardRecommended.setOnClickListener {
            val routine = cardRecommended.tag as? RoutineEntity ?: return@setOnClickListener
            executeRoutine(routine)
        }
        cardRecommended.setOnLongClickListener {
            val routine = cardRecommended.tag as? RoutineEntity ?: return@setOnLongClickListener false
            showRoutineManageDialog(routine)
            true
        }

        // View/Resume button — context-dependent
        btnViewOp.setOnClickListener {
            val service = agentService ?: return@setOnClickListener
            val currentState = service.stateFlow?.value
            if (currentState is AgentState.Paused) {
                // Resume the paused agent
                service.resumeAgent()
            } else {
                // View operation — launch ViewActivity with SurfaceView
                service.enterViewMode()
            }
        }

        // Stop agent
        btnStop.setOnClickListener { stopAgent() }

        // Start and bind AgentService
        ensureServiceStarted()

        // Show routines on idle
        scope.launch {
            val recent = historyRepo.getRecentConversations(1)
            if (recent.isEmpty()) {
                chatAdapter.addMessage(ChatMessage(MessageType.SYSTEM, "Ready. Enter an instruction to start."))
            }
            showHomepage()
        }

        // Start OverlayService so the floating pill is available when the user
        // backgrounds OpenCyvis with an active agent.
        startService(Intent(this, ai.opencyvis.OverlayService::class.java))

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }

        // Handle ask_user notification tap from cold start.
        if (intent.getBooleanExtra(AgentService.EXTRA_FOCUS_ASK, false)) {
            chatRecycler.post { scrollChatToBottom() }
        }
    }

    override fun onDestroy() {
        engineSubscriptionJob?.cancel()
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()
        voiceInputController.destroy()
        if (voiceTestReceiverRegistered) {
            unregisterReceiver(voiceTestReceiver)
            voiceTestReceiverRegistered = false
        }
        if (demoReceiverRegistered) {
            unregisterReceiver(demoReceiver)
            demoReceiverRegistered = false
        }
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun checkBackendAndPromptSetup(service: AgentService?) {
        // Delay check to allow backend detection to complete
        scope.launch {
            kotlinx.coroutines.delay(3000)
            val backendName = service?.activeBackendName
            if (backendName == null || backendName == "none") {
                // No backend — launch setup wizard on first run
                val prefs = getSharedPreferences("opencyvis_config", MODE_PRIVATE)
                val setupDismissed = prefs.getBoolean("setup_dismissed", false)
                if (!setupDismissed && !setupLaunched) {
                    Log.i(TAG, "No backend detected, launching SetupActivity")
                    setupLaunched = true
                    withContext(Dispatchers.Main) {
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(this@ControlPanelActivity, SetupActivity::class.java),
                            REQUEST_SETUP
                        )
                    }
                }
            }
        }
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun registerVoiceTestReceiver() {
        val filter = IntentFilter(VoiceInputTestBridge.ACTION)
        val demoFilter = IntentFilter("ai.opencyvis.TEST")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceTestReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(demoReceiver, demoFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(voiceTestReceiver, filter)
            registerReceiver(demoReceiver, demoFilter)
        }
        voiceTestReceiverRegistered = true
        demoReceiverRegistered = true
    }

    private fun editTextTarget(editText: EditText): VoiceInputController.TextTarget =
        object : VoiceInputController.TextTarget {
            override fun getText(): String = editText.text.toString()
            override fun setText(text: String) {
                editText.setText(text)
                editText.setSelection(editText.text.length)
            }
        }

    // ── ASR model download progress ─────────────────────────────────────

    private var asrDownloadDialog: android.app.AlertDialog? = null
    private var asrDownloadProgressBar: android.widget.ProgressBar? = null

    private fun onAsrDownloadProgress(bytesRead: Long, totalBytes: Long) {
        if (asrDownloadDialog == null) {
            val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = totalBytes <= 0
                max = 100
                setPadding(48, 32, 48, 16)
            }
            asrDownloadProgressBar = bar
            asrDownloadDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Downloading speech model…")
                .setMessage("~30 MB")
                .setView(bar)
                .setCancelable(false)
                .show()
        }
        if (totalBytes > 0) {
            asrDownloadProgressBar?.isIndeterminate = false
            asrDownloadProgressBar?.progress = ((bytesRead * 100) / totalBytes).toInt()
        }
        if (totalBytes > 0 && bytesRead >= totalBytes) {
            dismissAsrDownloadDialog()
        }
    }

    private fun dismissAsrDownloadDialog() {
        asrDownloadDialog?.dismiss()
        asrDownloadDialog = null
        asrDownloadProgressBar = null
    }

    private fun ensureServiceStarted() {
        val intent = Intent(this, AgentService::class.java)
        startForegroundService(intent)
        if (!bound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopAgent() {
        agentService?.stopAgent()
        actionButtons.visibility = View.GONE
        chatAdapter.addMessage(ChatMessage(MessageType.SYSTEM, "Agent stopped"))
        scrollChatToBottom()
    }

    // ── State collection ───────────────────────────────────────────────

    /**
     * Subscribe to AgentService.engineFlow and re-bind state collectors
     * each time a new AgentEngine is created. Multi-subscriber StateFlow
     * means we no longer fight OverlayService for the same callback slot.
     */
    private fun subscribeToEngineFlow() {
        engineSubscriptionJob?.cancel()
        val service = agentService ?: return
        engineSubscriptionJob = scope.launch {
            service.engineFlow.collect { engine ->
                if (engine != null) {
                    collectStateUpdates()
                    loadActiveConversationIfNeeded(service)
                } else {
                    stateCollectionJob?.cancel()
                    stepResultCollectionJob?.cancel()
                }
            }
        }
    }

    private fun collectStateUpdates() {
        stateCollectionJob?.cancel()
        stepResultCollectionJob?.cancel()

        stateCollectionJob = agentService?.stateFlow?.let { flow ->
            scope.launch {
                flow.collect { state ->
                    withContext(Dispatchers.Main) { onStateChanged(state) }
                }
            }
        }

        stepResultCollectionJob = agentService?.stepResultFlow?.let { flow ->
            scope.launch {
                flow.collect { result ->
                    withContext(Dispatchers.Main) {
                    // Track whether VD has meaningful content (agent executed at least one action)
                    if (result.success && result.actionType in AgentEngine.SIDE_EFFECT_ACTIONS) {
                        hasVdContent = true
                        btnViewOp.isEnabled = true
                    }
                    if (config.debugMode) {
                        val prefix = if (result.success) "" else "[FAIL] "
                        val msg = "[Step ${result.step}] ${prefix}${result.actionType}: ${result.detail}"
                        chatAdapter.addMessage(ChatMessage(MessageType.AGENT_STATUS, msg))
                        if (!result.debugInfo.isNullOrBlank()) {
                            chatAdapter.addMessage(
                                ChatMessage(
                                    MessageType.AGENT_DEBUG,
                                    "[LLM]\n${result.debugInfo}"
                                )
                            )
                        }
                    } else if (!result.completed) {
                        val userMsg = result.thought.ifBlank { result.detail }
                        if (!chatAdapter.hasCycle()) {
                            chatAdapter.startCycle()
                        }
                        chatAdapter.updateCycleText(userMsg)
                    }

                    if (result.completed) {
                        val summary = when (result.actionType) {
                            "finish" -> "✅ ${getString(R.string.task_complete, result.thought)}"
                            "fail" -> "❌ ${getString(R.string.task_failed, result.detail.removePrefix("Task failed: "))}"
                            "max_steps" -> "⚠️ ${getString(R.string.task_max_steps, result.step)}"
                            "error" -> "❌ ${getString(R.string.task_error, result.detail)}"
                            else -> "✅ ${getString(R.string.task_complete_step, result.step)}"
                        }
                        if (config.debugMode) {
                            chatAdapter.addMessage(ChatMessage(MessageType.AGENT_RESULT, summary))
                        } else {
                            chatAdapter.convertCycleToResult(summary)
                        }
                        if (result.actionType == "finish" &&
                            result.suggestedRoutineName != null &&
                            result.suggestedRoutineIcon != null) {
                            showRoutineSuggestionCard(
                                result.suggestedRoutineName,
                                result.suggestedRoutineIcon,
                                lastInstruction ?: ""
                            )
                        }
                    }
                    scrollChatToBottom()
                    }
                }
            }
        }
    }

    private fun onStateChanged(state: AgentState) {
        when (state) {
            is AgentState.Idle -> {
                stopCycleAnimation()
                val profileName = config.activeProfileName
                statusText.text = if (profileName.isNotEmpty()) "OpenCyvis · $profileName" else "OpenCyvis"
                // Keep View button visible if VD is still alive for inspection
                val hasVd = agentService?.getVirtualDisplayManager()?.isCreated == true
                actionButtons.visibility = if (hasVd) View.VISIBLE else View.GONE
                btnStop.visibility = if (hasVd) View.VISIBLE else View.GONE
                btnViewOp.text = "View"
                btnViewOp.isEnabled = hasVdContent
                editInput.hint = "Ask me anything..."
                editInput.isEnabled = true
                btnSend.text = "▲"
                // Show routines if no active conversation
                if (agentService?.currentConversationId == null && chatAdapter.itemCount <= 1) {
                    showHomepage()
                }
            }
            is AgentState.Running -> {
                hideRoutines()
                statusText.text = if (config.debugMode) {
                    "Running (${state.step}/${config.maxSteps})"
                } else {
                    "Running"
                }
                if (config.debugMode) {
                    chatAdapter.updateLastAgentStatus(state.thought)
                }
                scrollChatToBottom()
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "View"
                btnViewOp.isEnabled = hasVdContent
                editInput.hint = getString(R.string.supplement_hint)
                editInput.isEnabled = true
                btnSend.text = "▲"
            }
            is AgentState.Paused -> {
                statusText.text = "Paused"
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "Resume"
                btnStop.visibility = View.VISIBLE
                editInput.hint = getString(R.string.supplement_hint)
                editInput.isEnabled = true
            }
            is AgentState.Error -> {
                stopCycleAnimation()
                statusText.text = "Error"
                chatAdapter.addMessage(ChatMessage(MessageType.AGENT_RESULT, "Error: ${state.message}"))
                actionButtons.visibility = View.GONE
                editInput.isEnabled = true
                btnSend.text = "▲"
                scrollChatToBottom()
            }
            is AgentState.WaitingForUser -> {
                stopCycleAnimation()
                statusText.text = "Waiting for your answer..."
                chatAdapter.addMessage(ChatMessage(MessageType.AGENT_QUESTION, state.question))
                editInput.hint = "Your answer..."
                editInput.isEnabled = true
                btnSend.text = "▲"
                actionButtons.visibility = View.VISIBLE
                scrollChatToBottom()
            }
            is AgentState.WaitingForHandoff -> {
                stopCycleAnimation()
                statusText.text = "Waiting for handoff..."
                chatAdapter.addMessage(
                    ChatMessage(
                        MessageType.AGENT_STATUS,
                        getString(R.string.handoff_sensitive_input, state.reason)
                    )
                )
                editInput.hint = getString(R.string.handoff_waiting_hint)
                editInput.isEnabled = true
                btnSend.text = "▲"
                actionButtons.visibility = View.VISIBLE
                btnViewOp.text = "View"
                scrollChatToBottom()
            }
        }
    }

    private fun stopCycleAnimation() {
        chatAdapter.removeCycle()
    }

    /** Load messages from DB if an active conversation exists but chat is empty (e.g. IM-started task). */
    private fun loadActiveConversationIfNeeded(service: AgentService) {
        val convId = service.currentConversationId ?: return
        if (chatAdapter.itemCount > 1) return // already loaded
        scope.launch {
            val messages = historyRepo.getMessages(convId)
            if (messages.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                messages.forEach { entity ->
                    val type = try { MessageType.valueOf(entity.type) } catch (_: Exception) { MessageType.SYSTEM }
                    chatAdapter.addMessage(ChatMessage(type, entity.text, entity.timestamp))
                }
                hideRoutines()
                scrollChatToBottom()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AgentService.EXTRA_FOCUS_ASK, false)) {
            scrollChatToBottom()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETUP) {
            setupLaunched = false
            if (resultCode == SetupActivity.RESULT_BACKEND_READY) {
                Toast.makeText(this, "Backend ready!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val routine = pendingGeofenceRoutine ?: return
            pendingGeofenceRoutine = null
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showGeofenceLocationDialog(routine)
            } else {
                Toast.makeText(this, R.string.schedule_perm_location_denied, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // Re-check backend on resume — handles case where user enabled wireless debugging
        // while we were in the background (SetupActivity may have been destroyed)
        val service = agentService
        if (service != null) {
            val backendName = service.activeBackendName
            if (backendName == null || backendName == "none") {
                val shizukuPresent =
                    ai.opencyvis.backend.ShizukuConnector.status() !=
                        ai.opencyvis.backend.ShizukuStatus.UNAVAILABLE
                if (shizukuPresent || ai.opencyvis.backend.SetupStateDetector.isWirelessDebuggingEnabled(this)) {
                    scope.launch {
                        Log.i(TAG, "A privilege backend is available, retrying detection...")
                        val success = service.retryBackendDetection()
                        if (success) {
                            Log.i(TAG, "Backend reconnected on resume")
                            setupLaunched = false
                        } else if (!setupLaunched) {
                            Log.i(TAG, "Retry failed, launching SetupActivity")
                            setupLaunched = true
                            @Suppress("DEPRECATION")
                            startActivityForResult(
                                Intent(this@ControlPanelActivity, SetupActivity::class.java),
                                REQUEST_SETUP
                            )
                        }
                    }
                }
            }
        }

        // Refresh routines if idle
        val state = agentService?.stateFlow?.value
        if (state is AgentState.Idle || state == null) {
            if (agentService?.currentConversationId == null && chatAdapter.itemCount <= 1) {
                showHomepage()
            }
        }
    }

    private fun getGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> getString(R.string.greeting_morning)
            hour < 14 -> getString(R.string.greeting_noon)
            hour < 18 -> getString(R.string.greeting_afternoon)
            else -> getString(R.string.greeting_evening)
        }
    }

    private fun showHomepage() {
        scope.launch(Dispatchers.Main) {
            // Update greeting
            textGreeting.text = getGreeting()

            // Load data from DB on IO thread
            val recommended: RoutineEntity?
            val chips: MutableList<RoutineEntity>
            val recent: List<ConversationEntity>
            withContext(Dispatchers.IO) {
                recommended = routineDao.getPinnedRoutine()
                    ?: routineDao.getMostUsedRoutine()
                chips = routineDao.getBuiltinRoutines().filter { !it.isPinned }.toMutableList()
                if (config.showDebugRoutines) {
                    chips.addAll(routineDao.getDebugRoutines())
                }
                recent = historyRepo.getRecentConversations(3)
            }

            // Update UI on Main thread
            if (recommended != null) {
                recommendedIcon.text = recommended.icon
                val nameResId = resources.getIdentifier(recommended.name, "string", packageName)
                recommendedName.text = if (nameResId != 0) getString(nameResId) else recommended.name
                if (recommended.description != null) {
                    val descResId = resources.getIdentifier(recommended.description, "string", packageName)
                    recommendedDesc.text = if (descResId != 0) getString(descResId) else recommended.description
                    recommendedDesc.visibility = View.VISIBLE
                } else {
                    recommendedDesc.visibility = View.GONE
                }
                cardRecommended.tag = recommended
                cardRecommended.visibility = View.VISIBLE
            } else {
                cardRecommended.visibility = View.GONE
            }

            routineChipAdapter.submitList(chips)

            if (recent.isNotEmpty()) {
                labelRecent.visibility = View.VISIBLE
                containerRecentRoutines.visibility = View.VISIBLE
                containerRecentRoutines.removeAllViews()
                recent.forEach { conv ->
                    val itemView = layoutInflater.inflate(R.layout.item_routine_recent, containerRecentRoutines, false)
                    bindRecentItem(itemView, conv)
                    itemView.setOnClickListener {
                        val intent = Intent(this@ControlPanelActivity, ConversationDetailActivity::class.java).apply {
                            putExtra("conversation_id", conv.id)
                            putExtra("conversation_title", conv.title)
                            putExtra("conversation_status", conv.status)
                            putExtra("conversation_created_at", conv.createdAt)
                        }
                        startActivity(intent)
                    }
                    containerRecentRoutines.addView(itemView)
                }
            } else {
                labelRecent.visibility = View.GONE
                containerRecentRoutines.visibility = View.GONE
            }

            // Show routines container, hide chat
            routinesContainer.visibility = View.VISIBLE
            chatRecycler.visibility = View.GONE
            actionButtons.visibility = View.GONE
        }
    }

    private fun bindRecentItem(itemView: View, conv: ConversationEntity) {
        val iconText = itemView.findViewById<TextView>(R.id.recent_icon)
        val nameText = itemView.findViewById<TextView>(R.id.recent_name)
        val descText = itemView.findViewById<TextView>(R.id.recent_desc)

        iconText.text = "💬"  // 💬
        nameText.text = conv.title

        val relativeTime = DateUtils.getRelativeTimeSpanString(
            conv.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        descText.text = relativeTime
        descText.visibility = View.VISIBLE
    }

    private fun executeRoutine(routine: RoutineEntity) {
        // Handle action: prefixed instructions (debug routines that launch activities)
        if (routine.instruction.startsWith("action:")) {
            when (routine.instruction.removePrefix("action:")) {
                "open_settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            scope.launch {
                withContext(Dispatchers.IO) {
                    routineDao.incrementUseCount(routine.id, System.currentTimeMillis())
                }
            }
            return
        }

        val service = agentService
        if (service == null) {
            Toast.makeText(this, "Service not connected", Toast.LENGTH_SHORT).show()
            return
        }

        // Increment use count
        scope.launch {
            withContext(Dispatchers.IO) {
                routineDao.incrementUseCount(routine.id, System.currentTimeMillis())
            }
        }

        // Clear focus and dismiss IME before starting agent to prevent
        // focus-change cascade when hideRoutines() makes EditText visible
        editInput.clearFocus()
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editInput.windowToken, 0)

        // Resolve instruction from string resource (builtin routines store resource keys)
        val instrResId = resources.getIdentifier(routine.instruction, "string", packageName)
        val instruction = if (instrResId != 0) getString(instrResId) else routine.instruction

        // Check if backend is available before starting agent
        val backendName = service.activeBackendName
        if (backendName == null || backendName == "none") {
            setupLaunched = true
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(this, SetupActivity::class.java),
                REQUEST_SETUP
            )
            return
        }

        // Send instruction to agent
        chatAdapter.addMessage(ChatMessage(MessageType.USER_INPUT, instruction))
        ensureServiceStarted()
        lastInstruction = instruction
        hasVdContent = false
        service.startAgent(instruction)
        editInput.text.clear()
        actionButtons.visibility = View.VISIBLE
        hideRoutines()
        scrollChatToBottom()
    }

    private fun hideRoutines() {
        routinesContainer.visibility = View.GONE
        chatRecycler.visibility = View.VISIBLE
    }

    private fun showSaveRoutineDialog(message: ChatMessage) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_routine, null)
        val editName = dialogView.findViewById<EditText>(R.id.edit_routine_name)
        val editIcon = dialogView.findViewById<EditText>(R.id.edit_routine_icon)
        val editInstruction = dialogView.findViewById<EditText>(R.id.edit_routine_instruction)

        // Hide instruction field — pre-filled from message
        editInstruction.visibility = View.GONE

        editName.setText("")
        editIcon.setText("⚡")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_save_title)
            .setView(dialogView)
            .setPositiveButton(R.string.memory_dialog_save) { _, _ ->
                val name = editName.text.toString().trim()
                val icon = editIcon.text.toString().trim()
                if (name.isNotBlank()) {
                    saveRoutine(name, icon.ifBlank { "⚡" }, message.text)
                }
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private fun showRoutineSuggestionCard(name: String, icon: String, instruction: String) {
        if (instruction.isBlank()) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_routine, null)
        val editName = dialogView.findViewById<EditText>(R.id.edit_routine_name)
        val editIcon = dialogView.findViewById<EditText>(R.id.edit_routine_icon)
        val editInstruction = dialogView.findViewById<EditText>(R.id.edit_routine_instruction)

        editName.setText(name)
        editIcon.setText(icon)
        editInstruction.setText(instruction)
        editInstruction.visibility = View.GONE

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_ai_save_btn)
            .setView(dialogView)
            .setPositiveButton(R.string.memory_dialog_save) { _, _ ->
                val n = editName.text.toString().trim()
                val ic = editIcon.text.toString().trim()
                if (n.isNotBlank()) {
                    saveRoutine(n, ic.ifBlank { icon }, instruction)
                }
            }
            .setNegativeButton(R.string.routine_ai_dismiss_btn, null)
            .show()
    }

    private fun saveRoutine(name: String, icon: String, instruction: String) {
        scope.launch {
            val routine = RoutineEntity(
                name = name,
                icon = icon,
                instruction = instruction,
                description = null,
                category = "custom",
                isPinned = false,
                useCount = 0,
                lastUsedAt = null,
                createdAt = System.currentTimeMillis(),
                sortOrder = 100 // custom routines at the end
            )
            withContext(Dispatchers.IO) {
                routineDao.insertRoutine(routine)
            }
            Toast.makeText(this@ControlPanelActivity, R.string.routine_save_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRoutineManageDialog(routine: RoutineEntity) {
        val items = arrayOf(
            getString(R.string.routine_manage_edit),
            getString(R.string.routine_manage_delete),
            if (routine.isPinned) getString(R.string.routine_manage_unpin)
            else getString(R.string.routine_manage_pin),
            getString(R.string.routine_manage_schedule)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_manage_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showEditRoutineDialog(routine)
                    1 -> confirmDeleteRoutine(routine)
                    2 -> togglePin(routine)
                    3 -> showEditScheduleDialog(routine)
                }
            }
            .show()
    }

    private fun showEditRoutineDialog(routine: RoutineEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_routine, null)
        val editName = dialogView.findViewById<EditText>(R.id.edit_routine_name)
        val editIcon = dialogView.findViewById<EditText>(R.id.edit_routine_icon)
        val editInstruction = dialogView.findViewById<EditText>(R.id.edit_routine_instruction)

        val nameResId = resources.getIdentifier(routine.name, "string", packageName)
        editName.setText(if (nameResId != 0) getString(nameResId) else routine.name)
        editIcon.setText(routine.icon)
        val instrResId = resources.getIdentifier(routine.instruction, "string", packageName)
        editInstruction.setText(if (instrResId != 0) getString(instrResId) else routine.instruction)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_edit_title)
            .setView(dialogView)
            .setPositiveButton(R.string.memory_dialog_save) { _, _ ->
                val name = editName.text.toString().trim()
                val icon = editIcon.text.toString().trim()
                val instruction = editInstruction.text.toString().trim()
                if (name.isNotBlank() && instruction.isNotBlank()) {
                    updateRoutine(routine.copy(
                        name = name,
                        icon = icon.ifBlank { routine.icon },
                        instruction = instruction
                    ))
                }
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteRoutine(routine: RoutineEntity) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_delete_title)
            .setMessage(getString(R.string.routine_delete_message, routine.name))
            .setPositiveButton(R.string.routine_delete_confirm) { _, _ ->
                deleteRoutine(routine)
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private fun updateRoutine(routine: RoutineEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                routineDao.updateRoutine(routine)
            }
            showHomepage()
        }
    }

    private fun deleteRoutine(routine: RoutineEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                routineDao.deleteRoutine(routine)
            }
            showHomepage()
        }
    }

    private fun togglePin(routine: RoutineEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                routineDao.setPinned(routine.id, !routine.isPinned)
            }
            showHomepage()
        }
    }

    private fun showEditScheduleDialog(routine: RoutineEntity) {
        val types = arrayOf(
            getString(R.string.schedule_daily) + " (⏰)",
            getString(R.string.schedule_weekdays) + " (⏰)",
            getString(R.string.schedule_every_n_min, 30).toString() + " (🔄)",
            getString(R.string.schedule_geo_enter_label) + " (📍)"
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.routine_manage_schedule)
            .setItems(types) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val updated = when (which) {
                                    0 -> routine.copy(
                                        scheduleEnabled = true, triggerType = "time",
                                        scheduleHour = 8, scheduleMinute = 0, scheduleRepeatDays = null
                                    )
                                    1 -> routine.copy(
                                        scheduleEnabled = true, triggerType = "time",
                                        scheduleHour = 8, scheduleMinute = 0, scheduleRepeatDays = "1,2,3,4,5"
                                    )
                                    else -> routine.copy(
                                        scheduleEnabled = true, triggerType = "interval",
                                        intervalMinutes = 30
                                    )
                                }
                                routineDao.updateRoutine(updated)
                                ai.opencyvis.schedule.ScheduleManager.register(this@ControlPanelActivity, updated)
                            }
                            Toast.makeText(this@ControlPanelActivity, R.string.schedule_created, Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> showGeofenceSetup(routine)
                }
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private var pendingGeofenceRoutine: RoutineEntity? = null

    private fun showGeofenceSetup(routine: RoutineEntity) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingGeofenceRoutine = routine
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION
            )
            return
        }
        showGeofenceLocationDialog(routine)
    }

    private fun showGeofenceLocationDialog(routine: RoutineEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_routine, null)
        val editName = dialogView.findViewById<EditText>(R.id.edit_routine_name)
        val editIcon = dialogView.findViewById<EditText>(R.id.edit_routine_icon)
        val editInstruction = dialogView.findViewById<EditText>(R.id.edit_routine_instruction)

        editName.hint = getString(R.string.schedule_geo_location_hint)
        editName.setText("")
        editIcon.visibility = View.GONE
        editInstruction.visibility = View.GONE

        val enterLeaveItems = arrayOf(
            getString(R.string.schedule_geo_enter_label),
            getString(R.string.schedule_geo_leave_label)
        )
        var triggerOnEnter = true

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.schedule_geo_pick_title)
            .setView(dialogView)
            .setSingleChoiceItems(enterLeaveItems, 0) { _, which ->
                triggerOnEnter = which == 0
            }
            .setPositiveButton(R.string.memory_dialog_save) { _, _ ->
                val locationName = editName.text.toString().trim()
                if (locationName.isBlank()) {
                    Toast.makeText(this, R.string.schedule_geo_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveGeofenceRoutine(routine, locationName, triggerOnEnter)
            }
            .setNegativeButton(R.string.memory_dialog_cancel, null)
            .show()
    }

    private fun saveGeofenceRoutine(routine: RoutineEntity, locationName: String, triggerOnEnter: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // Use current location if available
                val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                val loc = try {
                    @Suppress("MissingPermission")
                    lm.getLastKnownLocation(android.location.LocationManager.FUSED_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                } catch (_: Exception) { null }

                val updated = routine.copy(
                    scheduleEnabled = true,
                    triggerType = "geofence",
                    geoLatitude = loc?.latitude,
                    geoLongitude = loc?.longitude,
                    geoRadiusMeters = 500f,
                    geoTriggerOnEnter = triggerOnEnter,
                    geoLocationName = locationName
                )
                routineDao.updateRoutine(updated)

                if (loc != null) {
                    ai.opencyvis.schedule.ScheduleManager.register(this@ControlPanelActivity, updated)
                }
            }
            Toast.makeText(this@ControlPanelActivity, R.string.schedule_created, Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollChatToBottom() {
        if (scrollPending) return
        scrollPending = true
        chatRecycler.post {
            scrollPending = false
            val count = chatAdapter.itemCount
            if (count > 0) chatRecycler.smoothScrollToPosition(count - 1)
        }
    }
}
