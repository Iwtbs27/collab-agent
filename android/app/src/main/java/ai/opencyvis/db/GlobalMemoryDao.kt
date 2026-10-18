package ai.opencyvis.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface GlobalMemoryDao {
    @Query("SELECT * FROM global_memories ORDER BY updatedAt DESC")
    fun getAll(): List<GlobalMemoryEntity>

    @Query(
        """
        SELECT * FROM global_memories
        WHERE enabled = 1
        ORDER BY updatedAt DESC
        """
    )
    fun getEnabled(): List<GlobalMemoryEntity>

    @Query(
        """
        SELECT * FROM global_memories
        WHERE key LIKE '%' || :query || '%'
           OR value LIKE '%' || :query || '%'
           OR category LIKE '%' || :query || '%'
        ORDER BY updatedAt DESC
        """
    )
    fun search(query: String): List<GlobalMemoryEntity>

    @Query("SELECT * FROM global_memories WHERE key = :key LIMIT 1")
    fun getByKey(key: String): GlobalMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(memory: GlobalMemoryEntity): Long

    @Update
    fun update(memory: GlobalMemoryEntity)

    @Query(
        """
        UPDATE global_memories
        SET value = :value,
            category = :category,
            source = :source,
            updatedAt = :updatedAt,
            enabled = 1
        WHERE key = :key
        """
    )
    fun updateByKey(
        key: String,
        value: String,
        category: String,
        source: String,
        updatedAt: Long
    ): Int

    @Query("UPDATE global_memories SET enabled = 0, updatedAt = :updatedAt WHERE id = :id")
    fun disable(id: Long, updatedAt: Long): Int

    @Query("DELETE FROM global_memories WHERE id = :id")
    fun delete(id: Long): Int
}
