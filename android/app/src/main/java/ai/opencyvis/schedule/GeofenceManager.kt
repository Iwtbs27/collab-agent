package ai.opencyvis.schedule

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import ai.opencyvis.db.RoutineEntity

object GeofenceManager {
    private const val TAG = "GeofenceManager"

    fun register(context: Context, routine: RoutineEntity): Boolean {
        val lat = routine.geoLatitude ?: return false
        val lng = routine.geoLongitude ?: return false
        val radius = routine.geoRadiusMeters ?: 500f
        val enter = routine.geoTriggerOnEnter ?: true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted, cannot register geofence for routine ${routine.id}")
            return false
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pi = makePendingIntent(context, routine.id, enter)
        return try {
            @Suppress("DEPRECATION", "MissingPermission")
            lm.addProximityAlert(lat, lng, radius, -1, pi)
            Log.i(TAG, "Registered geofence: routine=${routine.id} at $lat,$lng r=${radius}m enter=$enter")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission denied for geofence", e)
            false
        }
    }

    fun cancel(context: Context, routineId: Int) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeProximityAlert(makePendingIntent(context, routineId, true))
        lm.removeProximityAlert(makePendingIntent(context, routineId, false))
        Log.i(TAG, "Cancelled geofence for routine $routineId")
    }

    private fun makePendingIntent(context: Context, routineId: Int, enter: Boolean): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = "ai.opencyvis.GEOFENCE_TRIGGER"
            putExtra("routine_id", routineId)
            putExtra("trigger_on_enter", enter)
        }
        return PendingIntent.getBroadcast(
            context, routineId + 100000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
