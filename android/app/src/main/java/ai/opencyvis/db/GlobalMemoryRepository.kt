package ai.opencyvis.db

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GlobalMemoryRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).globalMemoryDao()

    suspend fun upsert(
        key: String,
        value: String,
        category: String = "",
        source: String = GlobalMemoryEntity.SOURCE_USER
    ): Long = withContext(Dispatchers.IO) {
        val trimmedKey = key.trim()
        val trimmedValue = value.trim()
        val now = System.currentTimeMillis()
        val updated = dao.updateByKey(
            key = trimmedKey,
            value = trimmedValue,
            category = category.trim(),
            source = source,
            updatedAt = now
        )
        if (updated > 0) {
            dao.getByKey(trimmedKey)?.id ?: 0L
        } else {
            dao.insert(
                GlobalMemoryEntity(
                    key = trimmedKey,
                    value = trimmedValue,
                    category = category.trim(),
                    source = source,
                    createdAt = now,
                    updatedAt = now,
                    enabled = true
                )
            )
        }
    }

    suspend fun update(memory: GlobalMemoryEntity) = withContext(Dispatchers.IO) {
        dao.update(memory.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun getAll(): List<GlobalMemoryEntity> =
        withContext(Dispatchers.IO) { dao.getAll() }

    suspend fun getEnabled(): List<GlobalMemoryEntity> =
        withContext(Dispatchers.IO) { dao.getEnabled() }

    suspend fun search(query: String): List<GlobalMemoryEntity> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) dao.getAll() else dao.search(query.trim())
        }

    suspend fun disable(id: Long) = withContext(Dispatchers.IO) {
        dao.disable(id, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }
}
