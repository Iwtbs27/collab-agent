package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.AgentService
import ai.opencyvis.R
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.engine.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var historyRepo: ChatHistoryRepository
    private var conversationId: Long = -1
    private var conversationStatus: String = ""

    // AgentService binding for active conversations
    private var agentService: AgentService? = null
    private var bound = false
    private var stateJob: Job? = null

    // Active controls
    private lateinit var activeControls: LinearLayout
    private lateinit var activeActionButtons: LinearLayout
    private lateinit var editActiveInput: EditText
    private lateinit var btnActiveSend: Button
    private lateinit var btnActiveView: Button
    private lateinit var btnActiveStop: Button
    private lateinit var chatAdapter: ChatAdapter

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            agentService = (binder as? AgentService.AgentBinder)?.getService()
            bound = true
            setupActiveControls()
            observeActiveState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            agentService = null
            bound = false
            stateJob?.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_detail)

        historyRepo = ChatHistoryRepository(this)
        conversationId = intent.getLongExtra("conversation_id", -1)
        val title = intent.getStringExtra("conversation_title") ?: ""
        conversationStatus = intent.getStringExtra("conversation_status") ?: ""
        val createdAt = intent.getLongExtra("conversation_created_at", 0)

        findViewById<TextView>(R.id.text_title).text = title
        findViewById<TextView>(R.id.text_subtitle).text = buildSubtitle(createdAt, conversationStatus)
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        // Chat messages
        chatAdapter = ChatAdapter()
        findViewById<RecyclerView>(R.id.recycler_messages).apply {
            layoutManager = LinearLayoutManager(this@ConversationDetailActivity)
            adapter = chatAdapter
        }

        // Active controls
        activeControls = findViewById(R.id.active_controls)
        activeActionButtons = findViewById(R.id.active_action_buttons)
        editActiveInput = findViewById(R.id.edit_active_input)
        btnActiveSend = findViewById(R.id.btn_active_send)
        btnActiveView = findViewById(R.id.btn_active_view)
        btnActiveStop = findViewById(R.id.btn_active_stop)

        // Load messages
        scope.launch {
            val messages = historyRepo.getMessages(conversationId)
            messages.forEach { entity ->
                val type = try { MessageType.valueOf(entity.type) } catch (_: Exception) { MessageType.SYSTEM }
                chatAdapter.addMessage(ChatMessage(type, entity.text, entity.timestamp))
            }
        }

        // Bind to AgentService for running conversations to show controls
        if (conversationStatus == "running") {
            tryBindAgentService()
        }
    }

    private fun tryBindAgentService() {
        bindService(
            Intent(this, AgentService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun setupActiveControls() {
        val svc = agentService ?: return
        activeControls.visibility = View.VISIBLE
        activeActionButtons.visibility = View.VISIBLE

        btnActiveSend.setOnClickListener {
            val text = editActiveInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val state = svc.stateFlow?.value
            when (state) {
                is AgentState.WaitingForUser -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_ANSWER, text))
                    svc.submitUserResponse(text)
                }
                is AgentState.Running, is AgentState.Paused, is AgentState.WaitingForHandoff -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_SUPPLEMENT, text))
                    svc.submitUserSupplement(text)
                }
                else -> {
                    chatAdapter.addMessage(ChatMessage(MessageType.USER_INPUT, text))
                    svc.startAgent(text)
                }
            }
            editActiveInput.text.clear()
            scrollChatToBottom()
        }

        btnActiveView.setOnClickListener {
            val state = svc.stateFlow?.value
            if (state is AgentState.Paused) {
                svc.resumeAgent()
            } else {
                svc.enterViewMode()
            }
        }

        btnActiveStop.setOnClickListener {
            svc.stopAgent()
            activeActionButtons.visibility = View.GONE
            chatAdapter.addMessage(ChatMessage(MessageType.SYSTEM, "Agent stopped"))
            scrollChatToBottom()
        }
    }

    private fun observeActiveState() {
        stateJob?.cancel()
        val svc = agentService ?: return
        val flow = svc.stateFlow ?: return
        stateJob = scope.launch {
            flow.collect { state ->
                val isThisConversation = svc.currentConversationId == conversationId
                if (!isThisConversation) {
                    // This conversation is no longer the active one — show read-only
                    activeControls.visibility = View.GONE
                    return@collect
                }
                when (state) {
                    is AgentState.Idle -> {
                        activeActionButtons.visibility = View.GONE
                        val hasVd = svc.getVirtualDisplayManager()?.isCreated == true
                        if (hasVd) {
                            btnActiveView.text = "View"
                            activeActionButtons.visibility = View.VISIBLE
                        }
                        editActiveInput.hint = "Ask me anything..."
                        editActiveInput.isEnabled = true
                    }
                    is AgentState.Running -> {
                        activeActionButtons.visibility = View.VISIBLE
                        btnActiveView.text = "View"
                        editActiveInput.hint = "Add info..."
                        editActiveInput.isEnabled = true
                    }
                    is AgentState.WaitingForUser -> {
                        activeActionButtons.visibility = View.VISIBLE
                        chatAdapter.addMessage(ChatMessage(MessageType.AGENT_QUESTION, state.question))
                        editActiveInput.hint = "Your answer..."
                        editActiveInput.isEnabled = true
                        scrollChatToBottom()
                    }
                    is AgentState.WaitingForHandoff -> {
                        activeActionButtons.visibility = View.VISIBLE
                        editActiveInput.hint = "Waiting for handoff..."
                        editActiveInput.isEnabled = true
                    }
                    is AgentState.Paused -> {
                        activeActionButtons.visibility = View.VISIBLE
                        btnActiveView.text = "Resume"
                    }
                    is AgentState.Error -> {
                        activeActionButtons.visibility = View.GONE
                        chatAdapter.addMessage(ChatMessage(MessageType.AGENT_RESULT, "Error: ${state.message}"))
                        scrollChatToBottom()
                    }
                    null -> {
                        activeControls.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildSubtitle(createdAt: Long, status: String): String {
        val time = DateUtils.getRelativeTimeSpanString(
            createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val statusLabel = when (status) {
            "completed" -> "Completed"
            "failed" -> "Failed"
            "stopped" -> "Stopped"
            "running" -> "Running"
            else -> status
        }
        return "$time · $statusLabel"
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete this conversation?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    historyRepo.deleteConversation(conversationId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scrollChatToBottom() {
        val recycler = findViewById<RecyclerView>(R.id.recycler_messages)
        recycler.post {
            val count = chatAdapter.itemCount
            if (count > 0) recycler.smoothScrollToPosition(count - 1)
        }
    }
}
