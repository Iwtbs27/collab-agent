package ai.opencyvis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        GlobalMemoryEntity::class,
        RoutineEntity::class
    ],
    version = 5
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun globalMemoryDao(): GlobalMemoryDao
    abstract fun routineDao(): RoutineDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `global_memories` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `key` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_global_memories_key` ON `global_memories` (`key`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN source TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN remoteChatId TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `routines` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `icon` TEXT NOT NULL,
                        `instruction` TEXT NOT NULL,
                        `description` TEXT,
                        `category` TEXT NOT NULL,
                        `isPinned` INTEGER NOT NULL,
                        `useCount` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `sortOrder` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE routines ADD COLUMN triggerType TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleMinute INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN scheduleRepeatDays TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalMinutes INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalStartHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN intervalEndHour INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLatitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLongitude REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoRadiusMeters REAL")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoTriggerOnEnter INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN geoLocationName TEXT")
                db.execSQL("ALTER TABLE routines ADD COLUMN lastTriggeredAt INTEGER")
                db.execSQL("ALTER TABLE routines ADD COLUMN nextTriggerAt INTEGER")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "opencyvis.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Pre-fill builtin routines on first creation
                            populateBuiltinRoutines(db)
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            db.query("SELECT COUNT(*) FROM routines").use { cursor ->
                                if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                                    populateBuiltinRoutines(db)
                                }
                            }
                            // Ensure debug routines exist (added in later version)
                            db.query("SELECT COUNT(*) FROM routines WHERE category = 'debug'").use { cursor ->
                                if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                                    populateDebugRoutines(db)
                                }
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }

        private fun populateBuiltinRoutines(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            val routines = listOf(
                Triple("routine_coffee", "☕", "routine_coffee_instruction"),
                Triple("routine_email", "📧", "routine_email_instruction"),
                Triple("routine_calendar", "📅", "routine_calendar_instruction"),
                Triple("routine_weather", "🌤️", "routine_weather_instruction"),
                Triple("routine_open_app", "📱", "routine_open_app_instruction")
            )
            routines.forEachIndexed { index, (name, icon, instruction) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO routines (name, icon, instruction, description, category, isPinned, useCount, lastUsedAt, createdAt, sortOrder, scheduleEnabled)
                    VALUES (?, ?, ?, ?, 'builtin', 0, 0, NULL, ?, ?, 0)
                    """.trimIndent(),
                    arrayOf(name, icon, instruction, "${name}_desc", now, index)
                )
            }
            // Debug routines (category = 'debug', shown only when show_debug_routines is on)
            populateDebugRoutines(db)
        }

        private fun populateDebugRoutines(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            val debugRoutines = listOf(
                Triple("routine_open_settings", "⚙", "action:open_settings")
            )
            debugRoutines.forEachIndexed { index, (name, icon, instruction) ->
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO routines (name, icon, instruction, description, category, isPinned, useCount, lastUsedAt, createdAt, sortOrder, scheduleEnabled)
                    VALUES (?, ?, ?, ?, 'debug', 0, 0, NULL, ?, ?, 0)
                    """.trimIndent(),
                    arrayOf(name, icon, instruction, "${name}_desc", now, 100 + index)
                )
            }
        }

        fun ensureBuiltinRoutines(context: Context) {
            val db = getInstance(context).openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM routines").use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    populateBuiltinRoutines(db)
                }
            }
        }
    }
}
