package ai.opencyvis.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.ChatHistoryRepository
import ai.opencyvis.db.ConversationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConversationHistoryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var historyRepo: ChatHistoryRepository
    private lateinit var adapter: ConversationHistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)

        historyRepo = ChatHistoryRepository(this)

        adapter = ConversationHistoryAdapter(
            onClick = { conv -> openDetail(conv) },
            onLongClick = { conv -> confirmDelete(conv) }
        )

        findViewById<RecyclerView>(R.id.recycler_conversations).apply {
            layoutManager = LinearLayoutManager(this@ConversationHistoryActivity)
            adapter = this@ConversationHistoryActivity.adapter
        }

        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }

        loadConversations()
    }

    override fun onResume() {
        super.onResume()
        loadConversations()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadConversations() {
        scope.launch {
            val conversations = historyRepo.getAllConversations()
            adapter.submitList(conversations)
        }
    }

    private fun openDetail(conv: ConversationEntity) {
        val intent = Intent(this, ConversationDetailActivity::class.java).apply {
            putExtra("conversation_id", conv.id)
            putExtra("conversation_title", conv.title)
            putExtra("conversation_status", conv.status)
            putExtra("conversation_created_at", conv.createdAt)
        }
        startActivity(intent)
    }

    private fun confirmDelete(conv: ConversationEntity) {
        AlertDialog.Builder(this)
            .setTitle("Delete conversation?")
            .setMessage(conv.title)
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    historyRepo.deleteConversation(conv.id)
                    adapter.removeConversation(conv.id)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
