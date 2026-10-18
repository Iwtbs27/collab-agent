package ai.opencyvis.db

import android.content.Context
import ai.opencyvis.ui.MessageType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatHistoryRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).chatDao()

    suspend fun startConversation(instruction: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.insertConversation(
            ConversationEntity(
                title = instruction,
                status = "running",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun addMessage(conversationId: Long, type: MessageType, text: String) =
        withContext(Dispatchers.IO) {
            dao.insertMessage(
                ChatMessageEntity(
                    conversationId = conversationId,
                    type = type.name,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

    suspend fun updateStatus(conversationId: Long, status: String) =
        withContext(Dispatchers.IO) {
            dao.updateStatus(conversationId, status, System.currentTimeMillis())
        }

    suspend fun getAllConversations(): List<ConversationEntity> =
        withContext(Dispatchers.IO) { dao.getAllConversations() }

    suspend fun getRecentConversations(limit: Int = 3): List<ConversationEntity> =
        withContext(Dispatchers.IO) { dao.getRecentConversations(limit) }

    suspend fun getMessages(conversationId: Long): List<ChatMessageEntity> =
        withContext(Dispatchers.IO) { dao.getMessages(conversationId) }

    suspend fun getStepCount(conversationId: Long): Int =
        withContext(Dispatchers.IO) { dao.getStepCount(conversationId) }

    suspend fun deleteConversation(conversationId: Long) =
        withContext(Dispatchers.IO) { dao.deleteConversation(conversationId) }

    suspend fun updateLastAgentStatus(conversationId: Long, text: String) =
        withContext(Dispatchers.IO) {
            dao.updateLastAgentStatus(conversationId, text, System.currentTimeMillis())
        }

    suspend fun appendInbound(
        conversationId: Long,
        text: String,
        source: String,
        remoteChatId: String
    ) = withContext(Dispatchers.IO) {
        dao.insertMessage(
            ChatMessageEntity(
                conversationId = conversationId,
                type = MessageType.USER_INPUT.name,
                text = text,
                timestamp = System.currentTimeMillis(),
                source = source,
                remoteChatId = remoteChatId
            )
        )
    }

    suspend fun updateLastAgentStatusToResult(conversationId: Long) =
        withContext(Dispatchers.IO) {
            dao.updateLastMessageType(
                conversationId,
                MessageType.AGENT_STATUS.name,
                MessageType.AGENT_RESULT.name,
                System.currentTimeMillis()
            )
        }

    suspend fun markStaleRunningAsStopped() = withContext(Dispatchers.IO) {
        dao.markStaleRunningAsStopped(System.currentTimeMillis())
    }
}
