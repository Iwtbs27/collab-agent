package ai.opencyvis.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY sortOrder ASC")
    fun getAllRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE isPinned = 1 ORDER BY useCount DESC LIMIT 1")
    fun getPinnedRoutine(): RoutineEntity?

    @Query("SELECT * FROM routines WHERE isPinned = 0 ORDER BY useCount DESC LIMIT 1")
    fun getTopUsedRoutine(): RoutineEntity?

    @Query("SELECT * FROM routines ORDER BY useCount DESC LIMIT 1")
    fun getMostUsedRoutine(): RoutineEntity?

    @Query("SELECT * FROM routines WHERE lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :limit")
    fun getRecentRoutines(limit: Int): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE category = 'builtin' ORDER BY sortOrder ASC")
    fun getBuiltinRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE category = 'custom' ORDER BY lastUsedAt DESC")
    fun getCustomRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE category = 'debug' ORDER BY sortOrder ASC")
    fun getDebugRoutines(): List<RoutineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRoutine(routine: RoutineEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertRoutines(routines: List<RoutineEntity>)

    @Update
    fun updateRoutine(routine: RoutineEntity)

    @Delete
    fun deleteRoutine(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :routineId")
    fun deleteById(routineId: Int)

    @Query("UPDATE routines SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :routineId")
    fun incrementUseCount(routineId: Int, now: Long)

    @Query("UPDATE routines SET isPinned = :pinned WHERE id = :routineId")
    fun setPinned(routineId: Int, pinned: Boolean)

    @Query("UPDATE routines SET sortOrder = :order WHERE id = :routineId")
    fun updateSortOrder(routineId: Int, order: Int)

    @Query("SELECT COUNT(*) FROM routines")
    fun getCount(): Int

    @Query("SELECT * FROM routines WHERE scheduleEnabled = 1")
    fun getScheduledRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE scheduleEnabled = 1 AND triggerType = 'geofence'")
    fun getGeofenceRoutines(): List<RoutineEntity>

    @Query("UPDATE routines SET lastTriggeredAt = :now, nextTriggerAt = :next WHERE id = :id")
    fun updateTriggerTimes(id: Int, now: Long, next: Long?)

    @Query("UPDATE routines SET scheduleEnabled = :enabled WHERE id = :id")
    fun setScheduleEnabled(id: Int, enabled: Boolean)

    @Query("SELECT * FROM routines WHERE id = :id")
    fun getById(id: Int): RoutineEntity?
}
