package ai.opencyvis.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,           // string resource key for display name
    val icon: String,           // emoji icon
    val instruction: String,    // instruction template to send to agent
    val description: String?,   // string resource key for description (optional)
    val category: String,       // "builtin" or "custom"
    val isPinned: Boolean,      // whether pinned for recommended slot
    val useCount: Int,          // usage count
    val lastUsedAt: Long?,      // last used timestamp
    val createdAt: Long,
    val sortOrder: Int,          // sort order for display
    // Schedule fields (v5)
    val scheduleEnabled: Boolean = false,
    val triggerType: String? = null,         // "time" | "interval" | "geofence"
    val scheduleHour: Int? = null,           // 0-23
    val scheduleMinute: Int? = null,         // 0-59
    val scheduleRepeatDays: String? = null,  // "1,2,3,4,5" Mon=1..Sun=7, null=daily
    val intervalMinutes: Int? = null,
    val intervalStartHour: Int? = null,
    val intervalEndHour: Int? = null,
    val geoLatitude: Double? = null,
    val geoLongitude: Double? = null,
    val geoRadiusMeters: Float? = null,
    val geoTriggerOnEnter: Boolean? = null,
    val geoLocationName: String? = null,
    val lastTriggeredAt: Long? = null,
    val nextTriggerAt: Long? = null
)
