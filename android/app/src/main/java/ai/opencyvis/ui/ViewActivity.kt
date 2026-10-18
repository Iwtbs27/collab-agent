package ai.opencyvis.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.engine.AgentState
import ai.opencyvis.engine.HandoffUiState
import ai.opencyvis.input.InputInjector
import androidx.core.content.ContextCompat
import ai.opencyvis.voice.SherpaOnnxSpeechInputEngine
import ai.opencyvis.voice.VoiceInputController
import ai.opencyvis.voice.VoiceInputTestBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Full-screen activity that renders VD content via SurfaceView.
 *
 * - VIEW mode: SurfaceView shows VD content, touches blocked, agent keeps running
 * - TAKEOVER mode: Touches and key events forwarded to VD, agent paused.
 *
 * Bottom controls: collapsible FAB that expands into a control panel.
 * Auto-expands with input field when agent asks user a question.
 * Shows banner when task completes or fails.
 */
class ViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ViewActivity"
        private const val BANNER_DISMISS_MS = 4000L
        private const val REQUEST_RECORD_AUDIO = 2002
        const val EXTRA_SHOW_CONTROLS = "ai.opencyvis.ui.ViewActivity.SHOW_CONTROLS"
    }

    private var agentService: AgentService? = null
    private var bound = false
    private var isTakeoverMode = false
    private var isPanelExpanded = false
    private var stateJob: Job? = null
    private var handoffUiJob: Job? = null
    private var wasRunning = false

    private lateinit var surfaceView: SurfaceView
    private lateinit var touchInterceptor: View
    private lateinit var glowBorder: GlowBorderView
    private lateinit var fab: FrameLayout
    private lateinit var fabIcon: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var askUserSection: LinearLayout
    private lateinit var supplementSection: LinearLayout
    private lateinit var panelDivider: View
    private lateinit var questionText: TextView
    private lateinit var answerInput: EditText
    private lateinit var supplementInput: EditText
    private lateinit var takeoverKeyboardProxy: EditText
    private lateinit var btnSendAnswer: Button
    private lateinit var btnSendSupplement: Button
    private lateinit var btnVoiceAnswer: Button
    private lateinit var btnTakeover: Button
    private lateinit var statusBanner: TextView
    private lateinit var handoffPanel: LinearLayout
    private lateinit var handoffReason: TextView
    private lateinit var handoffCountdownText: TextView
    private lateinit var handoffPendingActions: LinearLayout
    private lateinit var btnHandoffNow: Button
    private lateinit var btnHandoffCancel: Button
    private lateinit var controlButtons: LinearLayout
    private lateinit var resultPanel: LinearLayout
    private lateinit var resultIcon: TextView
    private lateinit var resultMessage: TextView
    private var isResultPanelVisible = false
    private lateinit var voiceAnswerController: VoiceInputController
    private var voiceTestReceiverRegistered = false
    private var proxyTextMutation = false
    private var takeoverTouchDownX = 0f
    private var takeoverTouchDownY = 0f
    private var pendingShowControls = false
    private var currentAskQuestion: String? = null

    private val voiceTestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val target = intent.getStringExtra(VoiceInputTestBridge.EXTRA_TARGET)
            if (target != VoiceInputTestBridge.TARGET_VIEW_ANSWER) return
            val result = intent.getStringExtra(VoiceInputTestBridge.EXTRA_RESULT) ?: return
            voiceAnswerController.injectFinalResult(result)
            Log.i(TAG, "Voice input injected target=$target text=$result")
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bannerDismissRunnable = Runnable { hideBanner() }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as AgentService.AgentBinder).getService()
            agentService = service
            bound = true
            Log.i(TAG, "Bound to AgentService")

            // Connect SurfaceView to VD if surface is already ready
            if (surfaceView.holder.surface.isValid) {
                service.getVirtualDisplayManager()?.setSurface(surfaceView.holder.surface)
            }

            observeAgentState(service)
            observeHandoffUiState(service)
            syncTakeoverModeFromService()
            if (pendingShowControls) {
                pendingShowControls = false
                expandPanel()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            stateJob?.cancel()
            handoffUiJob?.cancel()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_view)

        surfaceView = findViewById(R.id.surface_view)
        touchInterceptor = findViewById(R.id.touch_interceptor)
        glowBorder = findViewById(R.id.glow_border)
        fab = findViewById(R.id.fab)
        fabIcon = findViewById(R.id.fab_icon)
        bottomPanel = findViewById(R.id.bottom_panel)
        askUserSection = findViewById(R.id.ask_user_section)
        supplementSection = findViewById(R.id.supplement_section)
        panelDivider = findViewById(R.id.panel_divider)
        questionText = findViewById(R.id.question_text)
        answerInput = findViewById(R.id.answer_input)
        supplementInput = findViewById(R.id.supplement_input)
        takeoverKeyboardProxy = findViewById(R.id.takeover_keyboard_proxy)
        btnSendAnswer = findViewById(R.id.btn_send_answer)
        btnSendSupplement = findViewById(R.id.btn_send_supplement)
        btnVoiceAnswer = findViewById(R.id.btn_voice_answer)
        btnTakeover = findViewById(R.id.btn_takeover)
        statusBanner = findViewById(R.id.status_banner)
        handoffPanel = findViewById(R.id.handoff_panel)
        handoffReason = findViewById(R.id.handoff_reason)
        handoffCountdownText = findViewById(R.id.handoff_countdown_text)
        handoffPendingActions = findViewById(R.id.handoff_pending_actions)
        btnHandoffNow = findViewById(R.id.btn_handoff_now)
        btnHandoffCancel = findViewById(R.id.btn_handoff_cancel)
        controlButtons = findViewById(R.id.control_buttons)
        resultPanel = findViewById(R.id.result_panel)
        resultIcon = findViewById(R.id.result_icon)
        resultMessage = findViewById(R.id.result_message)

        voiceAnswerController = VoiceInputController(
            SherpaOnnxSpeechInputEngine(this, downloadProgressListener = ::onAsrDownloadProgress),
            editTextTarget(answerInput),
            object : VoiceInputController.Listener {
                override fun onListeningChanged(isListening: Boolean) {
                    btnVoiceAnswer.text = if (isListening) "■" else "🎙"
                    btnVoiceAnswer.isSelected = isListening
                }

                override fun onError(message: String) {
                    dismissAsrDownloadDialog()
                    android.widget.Toast.makeText(this@ViewActivity, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
        registerVoiceTestReceiver()
        setupTakeoverKeyboardProxy()

        // Start glow effect — agent is running in VIEW mode
        glowBorder.startGlow()

        // Apply nav bar insets to bottom panel and FAB
        ViewCompat.setOnApplyWindowInsetsListener(bottomPanel) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop, view.paddingRight,
                navBarInsets.bottom
            )
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(fab) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin = 24.dpToPx() + navBarInsets.bottom
            view.layoutParams = lp
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(resultPanel) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop, view.paddingRight,
                navBarInsets.bottom
            )
            insets
        }

        // SurfaceView callback: connect/disconnect VD surface
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created, connecting to VD")
                agentService?.getVirtualDisplayManager()?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface changed: ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed, switching VD back to ImageReader")
                agentService?.getVirtualDisplayManager()?.setSurface(null)
            }
        })

        // Touch interceptor: blocks touches in VIEW mode, also collapses panel
        touchInterceptor.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (isPanelExpanded) collapsePanel()
                if (isResultPanelVisible) hideResultPanel()
            }
            true
        }

        // SurfaceView touch: forwards to VD in TAKEOVER mode
        surfaceView.setOnTouchListener { _, event ->
            if (isTakeoverMode) {
                forwardTouchToVD(event)
                handleTakeoverTouchForKeyboard(event)
            }
            true
        }

        // FAB click: toggle panel
        fab.setOnClickListener {
            if (isPanelExpanded) collapsePanel() else expandPanel()
        }

        // Control buttons
        findViewById<Button>(R.id.btn_back).setOnClickListener {
            onBackToChat()
        }

        findViewById<Button>(R.id.btn_minimize).setOnClickListener {
            collapsePanel()
        }

        btnTakeover.setOnClickListener {
            toggleTakeover()
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopAndReturn()
        }

        // Answer input: send on IME action
        answerInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitAnswer()
                true
            } else false
        }
        supplementInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitSupplement()
                true
            } else false
        }

        btnSendAnswer.setOnClickListener {
            submitAnswer()
        }
        btnSendSupplement.setOnClickListener {
            submitSupplement()
        }
        btnHandoffNow.setOnClickListener {
            agentService?.completeUserHandoff("manual_confirm_auto_return")
            setTakeoverMode(false)
        }
        btnHandoffCancel.setOnClickListener {
            agentService?.cancelPendingHandoffReturn()
        }

        btnVoiceAnswer.setOnClickListener {
            if (voiceAnswerController.isListening) {
                voiceAnswerController.stop()
            } else if (ensureRecordAudioPermission()) {
                voiceAnswerController.start()
            }
        }

        // Result panel buttons
        findViewById<TextView>(R.id.btn_result_close).setOnClickListener {
            hideResultPanel()
        }
        findViewById<Button>(R.id.btn_result_back).setOnClickListener {
            onBackToChat()
        }
        findViewById<Button>(R.id.btn_result_takeover).setOnClickListener {
            hideResultPanel()
            toggleTakeover()
        }
        findViewById<Button>(R.id.btn_result_stop).setOnClickListener {
            stopAndReturn()
        }

        // Bind to AgentService
        Intent(this, AgentService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        handleIntent(intent)
    }

    private fun observeAgentState(service: AgentService) {
        stateJob?.cancel()
        val flow = service.stateFlow ?: return

        stateJob = lifecycleScope.launch {
            flow.collect { state ->
                onAgentStateChanged(state)
            }
        }
    }

    private fun observeHandoffUiState(service: AgentService) {
        handoffUiJob?.cancel()
        handoffUiJob = lifecycleScope.launch {
            service.handoffUiState.collect { state ->
                onHandoffUiStateChanged(state)
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_CONTROLS, false) == true) {
            pendingShowControls = true
            mainHandler.post {
                if (bound) {
                    syncTakeoverModeFromService()
                    pendingShowControls = false
                    expandPanel()
                }
            }
        }
        if (intent?.getBooleanExtra(AgentService.EXTRA_SHOW_HANDOFF, false) == true) {
            handoffPanel.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun onAgentStateChanged(state: AgentState) {
        Log.d(TAG, "Agent state: $state")

        when (state) {
            is AgentState.Running -> {
                wasRunning = true
                updateFabIcon("▶", "#FFFFFF")
                glowBorder.startGlow()
                clearAskUserSection()
                showSupplementSection()
                hideResultPanel()
            }
            is AgentState.WaitingForUser -> {
                hideSupplementSection()
                showAskUser(state.question)
                updateFabIcon("❓", "#FF6D00")
            }
            is AgentState.WaitingForHandoff -> {
                hideSupplementSection()
                clearAskUserSection()
                updateFabIcon("⚠", "#FFCBA135")
                showHandoffActive(state.reason)
            }
            is AgentState.Idle -> {
                glowBorder.stopGlow()
                updateFabIcon("✓", "#4CAF50")
                clearAskUserSection()
                hideSupplementSection()
                hideHandoffPanel()
                if (!state.resultMessage.isNullOrBlank()) {
                    showResultPanel(state.resultMessage, isError = false)
                }
                wasRunning = false
            }
            is AgentState.Error -> {
                glowBorder.stopGlow()
                updateFabIcon("✗", "#F44336")
                clearAskUserSection()
                hideSupplementSection()
                hideHandoffPanel()
                showResultPanel(state.message, isError = true)
                wasRunning = false
            }
            is AgentState.Paused -> {
                updateFabIcon("⏸", "#FFFFFF")
            }
        }
    }

    private fun onHandoffUiStateChanged(state: HandoffUiState) {
        when (state) {
            HandoffUiState.Idle -> {
                hideHandoffPanel()
                if (isTakeoverMode &&
                    agentService?.displayState != AgentService.DisplayState.TAKEOVER) {
                    setTakeoverMode(false)
                }
            }
            is HandoffUiState.Active -> showHandoffActive(state.reason)
            is HandoffUiState.PendingReturn -> showHandoffPending(state)
        }
    }

    private fun showHandoffActive(reason: String) {
        hideSupplementSection()
        hideAskUserSection()
        handoffPanel.visibility = View.VISIBLE
        handoffReason.text = "${getString(R.string.handoff_active_message)}\n\n$reason"
        handoffCountdownText.visibility = View.GONE
        handoffPendingActions.visibility = View.GONE
        panelDivider.visibility = View.VISIBLE
        expandPanel()
    }

    private fun showHandoffPending(state: HandoffUiState.PendingReturn) {
        hideSupplementSection()
        hideAskUserSection()
        handoffPanel.visibility = View.VISIBLE
        handoffReason.text = state.reason
        if (state.countdownSeconds > 0) {
            handoffCountdownText.text = getString(R.string.handoff_pending_message, state.countdownSeconds)
            handoffCountdownText.visibility = View.VISIBLE
            handoffPendingActions.visibility = View.VISIBLE
        } else {
            handoffCountdownText.visibility = View.GONE
            handoffPendingActions.visibility = View.GONE
        }
        panelDivider.visibility = View.VISIBLE
        expandPanel()
    }

    private fun hideHandoffPanel() {
        handoffPanel.visibility = View.GONE
        handoffCountdownText.visibility = View.GONE
        handoffPendingActions.visibility = View.GONE
        if (currentAskQuestion == null && supplementSection.visibility != View.VISIBLE) {
            panelDivider.visibility = View.GONE
        }
    }

    // ── FAB and Panel ───────────────────────────────────────────────────

    private fun updateFabIcon(icon: String, color: String) {
        fabIcon.text = icon
        fabIcon.setTextColor(android.graphics.Color.parseColor(color))
    }

    private fun expandPanel() {
        if (isPanelExpanded) return
        isPanelExpanded = true
        Log.i(TAG, "Bottom panel expanded")
        if (handoffPanel.visibility != View.VISIBLE) {
            restoreAskUserSection()
        }
        fab.visibility = View.GONE
        bottomPanel.visibility = View.VISIBLE
        bottomPanel.translationY = bottomPanel.height.toFloat().coerceAtLeast(300f)
        bottomPanel.animate()
            .translationY(0f)
            .setDuration(200)
            .start()
    }

    private fun collapsePanel() {
        if (!isPanelExpanded) return
        if (handoffPanel.visibility == View.VISIBLE) return
        bottomPanel.animate()
            .translationY(bottomPanel.height.toFloat())
            .setDuration(200)
            .withEndAction {
                bottomPanel.visibility = View.GONE
                if (currentAskQuestion == null) {
                    hideAskUserSection()
                }
                fab.visibility = View.VISIBLE
                isPanelExpanded = false
                Log.i(TAG, "Bottom panel collapsed")
            }
            .start()
    }

    // ── Ask User ────────────────────────────────────────────────────────

    private fun showAskUser(question: String) {
        currentAskQuestion = question
        questionText.text = question
        askUserSection.visibility = View.VISIBLE
        panelDivider.visibility = View.VISIBLE
        answerInput.text.clear()
        answerInput.requestFocus()
        expandPanel()
    }

    private fun hideAskUserSection() {
        askUserSection.visibility = View.GONE
        panelDivider.visibility = View.GONE
    }

    private fun clearAskUserSection() {
        currentAskQuestion = null
        hideAskUserSection()
    }

    private fun restoreAskUserSection() {
        val question = currentAskQuestion ?: return
        questionText.text = question
        askUserSection.visibility = View.VISIBLE
        panelDivider.visibility = View.VISIBLE
        Log.i(TAG, "Ask user section restored")
    }

    private fun submitAnswer() {
        val text = answerInput.text.toString().trim()
        if (text.isEmpty()) return
        agentService?.submitUserResponse(text)
        answerInput.text.clear()
        currentAskQuestion = null
        collapsePanel()
    }

    private fun showSupplementSection() {
        if (currentAskQuestion != null || handoffPanel.visibility == View.VISIBLE) return
        supplementSection.visibility = View.VISIBLE
        panelDivider.visibility = View.VISIBLE
    }

    private fun hideSupplementSection() {
        supplementSection.visibility = View.GONE
        if (currentAskQuestion == null) {
            panelDivider.visibility = View.GONE
        }
    }

    private fun submitSupplement() {
        val text = supplementInput.text.toString().trim()
        if (text.isEmpty()) return
        agentService?.submitUserSupplement(text)
        supplementInput.text.clear()
        collapsePanel()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        android.widget.Toast.makeText(
            this,
            getString(R.string.toast_microphone_permission_required),
            android.widget.Toast.LENGTH_SHORT
        ).show()
        return false
    }

    private fun registerVoiceTestReceiver() {
        val filter = IntentFilter(VoiceInputTestBridge.ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(voiceTestReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(voiceTestReceiver, filter)
        }
        voiceTestReceiverRegistered = true
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

    // ── Result Panel ─────────────────────────────────────────────────────

    private fun showResultPanel(message: String, isError: Boolean) {
        if (isPanelExpanded) collapsePanel()
        fab.visibility = View.GONE
        resultMessage.text = message
        if (isError) {
            resultIcon.text = "✗"
            resultIcon.setTextColor(ContextCompat.getColor(this, R.color.color_danger))
        } else {
            resultIcon.text = "✓"
            resultIcon.setTextColor(ContextCompat.getColor(this, R.color.color_success))
        }
        resultPanel.visibility = View.VISIBLE
        resultPanel.translationY = resultPanel.height.toFloat().coerceAtLeast(300f)
        resultPanel.animate()
            .translationY(0f)
            .setDuration(250)
            .start()
        isResultPanelVisible = true
    }

    private fun hideResultPanel() {
        if (!isResultPanelVisible) return
        resultPanel.animate()
            .translationY(resultPanel.height.toFloat())
            .setDuration(200)
            .withEndAction {
                resultPanel.visibility = View.GONE
                fab.visibility = View.VISIBLE
            }
            .start()
        isResultPanelVisible = false
    }

    // ── Banner ──────────────────────────────────────────────────────────

    private fun showBanner(message: String, color: String) {
        mainHandler.removeCallbacks(bannerDismissRunnable)
        statusBanner.text = message
        statusBanner.background.setTint(android.graphics.Color.parseColor(color))
        statusBanner.visibility = View.VISIBLE
        statusBanner.alpha = 0f
        statusBanner.animate().alpha(1f).setDuration(200).start()
        mainHandler.postDelayed(bannerDismissRunnable, BANNER_DISMISS_MS)
    }

    private fun hideBanner() {
        statusBanner.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { statusBanner.visibility = View.GONE }
            .start()
    }

    // ── Takeover ────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTakeoverMode) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || shouldHandleKeyLocally()) {
                return super.dispatchKeyEvent(event)
            }
            val vdm = agentService?.getVirtualDisplayManager() ?: return super.dispatchKeyEvent(event)
            val displayId = vdm.displayId
            if (displayId != -1) {
                val result = InputInjector.injectKeyToDisplay(this, event, displayId)
                if (result) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        syncTakeoverModeFromService()
        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER &&
            agentService?.stateFlow?.value !is AgentState.WaitingForHandoff) {
            mainHandler.post { expandPanel() }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(bannerDismissRunnable)
        stateJob?.cancel()
        handoffUiJob?.cancel()
        voiceAnswerController.destroy()
        if (voiceTestReceiverRegistered) {
            unregisterReceiver(voiceTestReceiver)
            voiceTestReceiverRegistered = false
        }
        agentService?.getVirtualDisplayManager()?.setSurface(null)

        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }

    private fun onBackToChat() {
        if (isTakeoverMode) {
            setTakeoverMode(false)
            agentService?.exitTakeoverMode()
        }
        agentService?.returnToChat()
        finish()
    }

    private fun stopAndReturn() {
        if (isTakeoverMode) {
            setTakeoverMode(false)
        }
        val intent = Intent(this, ControlPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        agentService?.stopAgent()
        finish()
    }

    private fun toggleTakeover() {
        if (isTakeoverMode && agentService?.stateFlow?.value is AgentState.WaitingForHandoff) {
            agentService?.completeUserHandoff("manual_return_control")
            setTakeoverMode(false)
            return
        }
        val newMode = !isTakeoverMode
        setTakeoverMode(newMode)
        if (newMode) {
            agentService?.enterTakeoverMode()
        } else {
            agentService?.exitTakeoverMode()
        }
    }

    private fun setTakeoverMode(takeover: Boolean) {
        isTakeoverMode = takeover
        if (takeover) {
            touchInterceptor.visibility = View.GONE
            glowBorder.stopGlow()
            btnTakeover.text = getString(R.string.vd_btn_return_control)
            takeoverKeyboardProxy.isEnabled = true
            collapsePanel()
        } else {
            hideTakeoverKeyboard()
            takeoverKeyboardProxy.isEnabled = false
            touchInterceptor.visibility = View.VISIBLE
            glowBorder.startGlow()
            btnTakeover.text = getString(R.string.vd_btn_take_over)
        }
        Log.i(TAG, "Takeover mode: $takeover")
    }

    private fun syncTakeoverModeFromService() {
        if (agentService?.displayState == AgentService.DisplayState.TAKEOVER && !isTakeoverMode) {
            setTakeoverMode(true)
        }
    }

    private fun forwardTouchToVD(event: MotionEvent) {
        val vdm = agentService?.getVirtualDisplayManager() ?: return
        val displayId = vdm.displayId
        if (displayId == -1) return

        val injected = MotionEvent.obtain(event)
        val result = InputInjector.injectToDisplay(this, injected, displayId)
        Log.d(TAG, "Injected touch to display $displayId: result=$result action=${event.action} x=${event.x} y=${event.y}")
        injected.recycle()
    }

    private fun setupTakeoverKeyboardProxy() {
        takeoverKeyboardProxy.isEnabled = false
        takeoverKeyboardProxy.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (proxyTextMutation || !isTakeoverMode) return
                val inserted = s?.subSequence(start, start + count)?.toString().orEmpty()
                val displayId = agentService?.getVirtualDisplayManager()?.displayId ?: return
                if (displayId == -1) return

                if (before > count) {
                    repeat(before - count) {
                        InputInjector.injectKeyToDisplay(
                            this@ViewActivity,
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
                            displayId
                        )
                        InputInjector.injectKeyToDisplay(
                            this@ViewActivity,
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL),
                            displayId
                        )
                    }
                }
                if (inserted.isNotEmpty()) {
                    Log.i(TAG, "Takeover keyboard proxy forwarding text: ${inserted.length} chars")
                    lifecycleScope.launch {
                        InputInjector(this@ViewActivity, displayId).typeText(inserted)
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (proxyTextMutation || s.isNullOrEmpty()) return
                proxyTextMutation = true
                s.clear()
                proxyTextMutation = false
            }
        })
    }

    private fun handleTakeoverTouchForKeyboard(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                takeoverTouchDownX = event.x
                takeoverTouchDownY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - takeoverTouchDownX
                val dy = event.y - takeoverTouchDownY
                if (dx * dx + dy * dy < (12.dpToPx() * 12.dpToPx())) {
                    showTakeoverKeyboard()
                }
            }
        }
    }

    private fun showTakeoverKeyboard() {
        if (!isTakeoverMode || isPanelExpanded || isResultPanelVisible) return
        takeoverKeyboardProxy.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(takeoverKeyboardProxy, InputMethodManager.SHOW_IMPLICIT)
        Log.i(TAG, "Takeover keyboard proxy requested IME")
    }

    private fun hideTakeoverKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(takeoverKeyboardProxy.windowToken, 0)
        takeoverKeyboardProxy.clearFocus()
    }

    private fun shouldHandleKeyLocally(): Boolean {
        val focus = currentFocus ?: return false
        return focus == answerInput || focus == btnSendAnswer || focus == btnVoiceAnswer ||
                focus == supplementInput || focus == btnSendSupplement ||
                focus == btnTakeover || focus == btnHandoffNow || focus == btnHandoffCancel ||
                isPanelExpanded || isResultPanelVisible || handoffPendingActions.visibility == View.VISIBLE
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
