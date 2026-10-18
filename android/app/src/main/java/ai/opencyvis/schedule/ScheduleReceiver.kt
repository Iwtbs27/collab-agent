package ai.opencyvis.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ai.opencyvis.AgentService
import ai.opencyvis.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ScheduleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val routineId = intent.getIntExtra("routine_id", -1)
        if (routineId == -1) return
        Log.i(TAG, "Schedule triggered for routine $routineId")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).routineDao()
                val routine = dao.getById(routineId) ?: run {
                    Log.w(TAG, "Routine $routineId not found")
                    return@launch
                }
                if (!routine.scheduleEnabled) {
                    Log.w(TAG, "Routine $routineId schedule disabled, skipping")
                    return@launch
                }

                val instrResId = context.resources.getIdentifier(
                    routine.instruction, "string", context.packageName
                )
                val instruction = if (instrResId != 0) {
                    context.getString(instrResId)
                } else {
                    routine.instruction
                }

                val serviceIntent = Intent(context, AgentService::class.java).apply {
                    action = "ai.opencyvis.START_SCHEDULED"
                    putExtra("instruction", instruction)
                    putExtra("routine_id", routineId)
                }
                context.startForegroundService(serviceIntent)

                val now = System.currentTimeMillis()
                val next = ScheduleManager.calculateNextTrigger(routine)
                dao.updateTriggerTimes(routineId, now, next)
                dao.incrementUseCount(routineId, now)

                if (next != null) {
                    ScheduleManager.register(context, routine.copy(lastTriggeredAt = now))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
