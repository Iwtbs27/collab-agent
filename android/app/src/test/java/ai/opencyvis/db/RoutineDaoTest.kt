package ai.opencyvis.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoutineDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RoutineDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.routineDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun createRoutine(
        name: String = "test_routine",
        icon: String = "⚡",
        instruction: String = "test instruction",
        category: String = "custom",
        isPinned: Boolean = false,
        useCount: Int = 0,
        sortOrder: Int = 0
    ) = RoutineEntity(
        name = name,
        icon = icon,
        instruction = instruction,
        description = null,
        category = category,
        isPinned = isPinned,
        useCount = useCount,
        lastUsedAt = null,
        createdAt = System.currentTimeMillis(),
        sortOrder = sortOrder
    )

    @Test
    fun insertAndRetrieve() = runBlocking {
        val routine = createRoutine(name = "coffee")
        val id = dao.insertRoutine(routine).toInt()
        assertTrue(id > 0)

        val all = dao.getAllRoutines()
        assertEquals(1, all.size)
        assertEquals("coffee", all[0].name)
    }

    @Test
    fun getBuiltinRoutines() = runBlocking {
        dao.insertRoutine(createRoutine(name = "builtin1", category = "builtin", sortOrder = 0))
        dao.insertRoutine(createRoutine(name = "builtin2", category = "builtin", sortOrder = 1))
        dao.insertRoutine(createRoutine(name = "custom1", category = "custom"))

        val builtins = dao.getBuiltinRoutines()
        assertEquals(2, builtins.size)
        assertEquals("builtin1", builtins[0].name)
    }

    @Test
    fun getMostUsedRoutine() = runBlocking {
        dao.insertRoutine(createRoutine(name = "rare", useCount = 1))
        dao.insertRoutine(createRoutine(name = "popular", useCount = 10))

        val mostUsed = dao.getMostUsedRoutine()
        assertNotNull(mostUsed)
        assertEquals("popular", mostUsed!!.name)
    }

    @Test
    fun incrementUseCount() = runBlocking {
        val id = dao.insertRoutine(createRoutine(name = "r", useCount = 0)).toInt()
        dao.incrementUseCount(id, System.currentTimeMillis())

        val routine = dao.getAllRoutines().first { it.id == id }
        assertEquals(1, routine.useCount)
        assertNotNull(routine.lastUsedAt)
    }

    @Test
    fun pinAndUnpin() = runBlocking {
        val id = dao.insertRoutine(createRoutine(name = "r")).toInt()
        dao.setPinned(id, true)

        val pinned = dao.getPinnedRoutine()
        assertNotNull(pinned)
        assertEquals(id, pinned!!.id)

        dao.setPinned(id, false)
        assertNull(dao.getPinnedRoutine())
    }

    @Test
    fun deleteRoutine() = runBlocking {
        val routine = createRoutine(name = "to_delete")
        val id = dao.insertRoutine(routine).toInt()
        assertEquals(1, dao.getCount())

        dao.deleteById(id)
        assertEquals(0, dao.getCount())
    }

    @Test
    fun getRecentRoutines() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insertRoutine(createRoutine(name = "old").copy(lastUsedAt = now - 10000))
        dao.insertRoutine(createRoutine(name = "new").copy(lastUsedAt = now))

        val recent = dao.getRecentRoutines(1)
        assertEquals(1, recent.size)
        assertEquals("new", recent[0].name)
    }

    @Test
    fun getScheduledRoutines() = runBlocking {
        dao.insertRoutine(createRoutine(name = "unscheduled"))
        dao.insertRoutine(
            createRoutine(name = "scheduled_time").copy(
                scheduleEnabled = true,
                triggerType = "time",
                scheduleHour = 8,
                scheduleMinute = 30
            )
        )
        dao.insertRoutine(
            createRoutine(name = "scheduled_geo").copy(
                scheduleEnabled = true,
                triggerType = "geofence",
                geoLatitude = 39.9,
                geoLongitude = 116.4,
                geoRadiusMeters = 200f,
                geoTriggerOnEnter = true
            )
        )

        val scheduled = dao.getScheduledRoutines()
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.scheduleEnabled })

        val geofence = dao.getGeofenceRoutines()
        assertEquals(1, geofence.size)
        assertEquals("scheduled_geo", geofence[0].name)
    }

    @Test
    fun setScheduleEnabled() = runBlocking {
        val id = dao.insertRoutine(createRoutine(name = "r")).toInt()
        assertFalse(dao.getById(id)!!.scheduleEnabled)

        dao.setScheduleEnabled(id, true)
        assertTrue(dao.getById(id)!!.scheduleEnabled)

        dao.setScheduleEnabled(id, false)
        assertFalse(dao.getById(id)!!.scheduleEnabled)
    }

    @Test
    fun updateTriggerTimes() = runBlocking {
        val id = dao.insertRoutine(
            createRoutine(name = "scheduled").copy(
                scheduleEnabled = true,
                triggerType = "time",
                scheduleHour = 9
            )
        ).toInt()

        val now = System.currentTimeMillis()
        val next = now + 86400000L
        dao.updateTriggerTimes(id, now, next)

        val routine = dao.getById(id)!!
        assertEquals(now, routine.lastTriggeredAt)
        assertEquals(next, routine.nextTriggerAt)
    }
}
