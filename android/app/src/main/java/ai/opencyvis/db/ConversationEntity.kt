package ai.opencyvis.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: String,   // "running", "completed", "failed", "stopped"
    val createdAt: Long,
    val updatedAt: Long
)
