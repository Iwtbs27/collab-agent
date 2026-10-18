package ai.opencyvis.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.RoutineEntity
import java.util.Calendar

object ScheduleManager {
    private const val TAG = "ScheduleManager"

    fun register(context: Context, routine: RoutineEntity) {
        if (!routine.scheduleEnabled) return
        when (routine.triggerType) {
            "time" -> registerTimeAlarm(context, routine)
            "interval" -> registerIntervalAlarm(context, routine)
            "geofence" -> GeofenceManager.register(context, routine)
        }
    }

    fun cancel(context: Context, routineId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(makePendingIntent(context, routineId))
        GeofenceManager.cancel(context, routineId)
        Log.i(TAG, "Cancelled schedule for routine $routineId")
    }

    fun rescheduleAll(context: Context) {
        val dao = AppDatabase.getInstance(context).routineDao()
        val routines = dao.getScheduledRoutines()
        Log.i(TAG, "Rescheduling ${routines.size} routines")
        for (r in routines) {
            register(context, r)
        }
    }

    fun calculateNextTrigger(routine: RoutineEntity): Long? {
        return when (routine.triggerType) {
            "time" -> calculateNextTimeMs(
                routine.scheduleHour ?: return null,
                routine.scheduleMinute ?: 0,
                routine.scheduleRepeatDays
            )
            "interval" -> System.currentTimeMillis() + (routine.intervalMinutes ?: 30) * 60_000L
            else -> null
        }
    }

    private fun registerTimeAlarm(context: Context, routine: RoutineEntity) {
        val hour = routine.scheduleHour ?: return
        val minute = routine.scheduleMinute ?: 0
        val triggerMs = calculateNextTimeMs(hour, minute, routine.scheduleRepeatDays) ?: return
        setAlarm(context, routine.id, triggerMs)
        updateNextTrigger(context, routine.id, triggerMs)
        Log.i(TAG, "Registered time alarm: routine=${routine.id} at $hour:${"%02d".format(minute)}")
    }

    private fun registerIntervalAlarm(context: Context, routine: RoutineEntity) {
        val intervalMs = (routine.intervalMinutes ?: 30) * 60_000L
        val triggerMs = System.currentTimeMillis() + intervalMs
        setAlarm(context, routine.id, triggerMs)
        updateNextTrigger(context, routine.id, triggerMs)
        Log.i(TAG, "Registered interval alarm: routine=${routine.id} every ${routine.intervalMinutes}min")
    }

    private fun setAlarm(context: Context, routineId: Int, triggerAtMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = makePendingIntent(context, routineId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            Log.w(TAG, "Using inexact alarm for routine $routineId (no SCHEDULE_EXACT_ALARM)")
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    private fun makePendingIntent(context: Context, routineId: Int): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = "ai.opencyvis.SCHEDULE_TRIGGER"
            putExtra("routine_id", routineId)
        }
        return PendingIntent.getBroadcast(
            context, routineId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun calculateNextTimeMs(hour: Int, minute: Int, repeatDays: String?): Long? {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (repeatDays != null) {
            val days = repeatDays.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (days.isNotEmpty()) {
                for (i in 0..7) {
                    val calDay = target.get(Calendar.DAY_OF_WEEK)
                    val isoDay = if (calDay == Calendar.SUNDAY) 7 else calDay - 1
                    if (isoDay in days) return target.timeInMillis
                    target.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
        return target.timeInMillis
    }

    private fun updateNextTrigger(context: Context, routineId: Int, nextMs: Long) {
        val dao = AppDatabase.getInstance(context).routineDao()
        val current = dao.getById(routineId)
        dao.updateTriggerTimes(routineId, current?.lastTriggeredAt ?: 0, nextMs)
    }
}
