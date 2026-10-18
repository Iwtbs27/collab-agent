package ai.opencyvis.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "global_memories",
    indices = [Index(value = ["key"], unique = true)]
)
data class GlobalMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val category: String = "",
    val source: String = SOURCE_USER,
    val createdAt: Long,
    val updatedAt: Long,
    val enabled: Boolean = true
) {
    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_AI = "ai"
    }
}
