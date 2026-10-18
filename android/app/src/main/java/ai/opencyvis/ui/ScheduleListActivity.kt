package ai.opencyvis.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import ai.opencyvis.R
import ai.opencyvis.db.AppDatabase
import ai.opencyvis.db.RoutineEntity
import ai.opencyvis.schedule.ScheduleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleListActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private val adapter = ScheduleAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_list)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = getString(R.string.schedule_title)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        recycler = findViewById(R.id.recycler)
        emptyText = findViewById(R.id.empty_text)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        loadSchedules()
    }

    override fun onResume() {
        super.onResume()
        loadSchedules()
    }

    private fun loadSchedules() {
        scope.launch {
            val routines = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@ScheduleListActivity).routineDao().getScheduledRoutines()
            }
            adapter.submitList(routines)
            emptyText.visibility = if (routines.isEmpty()) View.VISIBLE else View.GONE
            recycler.visibility = if (routines.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private inner class ScheduleAdapter : RecyclerView.Adapter<ScheduleAdapter.VH>() {
        private var items: List<RoutineEntity> = emptyList()

        fun submitList(list: List<RoutineEntity>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_schedule_card, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val icon: TextView = itemView.findViewById(R.id.schedule_icon)
            private val name: TextView = itemView.findViewById(R.id.schedule_name)
            private val switch: SwitchMaterial = itemView.findViewById(R.id.schedule_switch)
            private val trigger: TextView = itemView.findViewById(R.id.schedule_trigger)
            private val next: TextView = itemView.findViewById(R.id.schedule_next)

            fun bind(routine: RoutineEntity) {
                icon.text = routine.icon
                val ctx = itemView.context
                val nameResId = ctx.resources.getIdentifier(routine.name, "string", ctx.packageName)
                name.text = if (nameResId != 0) ctx.getString(nameResId) else routine.name

                trigger.text = buildTriggerText(routine)
                next.text = buildNextText(routine)

                switch.setOnCheckedChangeListener(null)
                switch.isChecked = routine.scheduleEnabled
                switch.setOnCheckedChangeListener { _, checked ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val dao = AppDatabase.getInstance(ctx).routineDao()
                            dao.setScheduleEnabled(routine.id, checked)
                            val updated = dao.getById(routine.id) ?: return@withContext
                            if (checked) {
                                ScheduleManager.register(ctx, updated)
                            } else {
                                ScheduleManager.cancel(ctx, routine.id)
                            }
                        }
                        loadSchedules()
                    }
                }

                itemView.alpha = if (routine.scheduleEnabled) 1.0f else 0.4f
            }

            private fun buildTriggerText(r: RoutineEntity): String {
                val ctx = itemView.context
                return when (r.triggerType) {
                    "time" -> {
                        val time = "%02d:%02d".format(r.scheduleHour ?: 0, r.scheduleMinute ?: 0)
                        val repeat = when (r.scheduleRepeatDays) {
                            null -> ctx.getString(R.string.schedule_daily)
                            "1,2,3,4,5" -> ctx.getString(R.string.schedule_weekdays)
                            else -> r.scheduleRepeatDays!!
                        }
                        "⏰ $repeat $time"
                    }
                    "interval" -> {
                        val mins = r.intervalMinutes ?: 30
                        val interval = ctx.getString(R.string.schedule_every_n_min, mins)
                        val hours = if (r.intervalStartHour != null && r.intervalEndHour != null) {
                            " (%02d:00–%02d:00)".format(r.intervalStartHour, r.intervalEndHour)
                        } else ""
                        "🔄 $interval$hours"
                    }
                    "geofence" -> {
                        val loc = r.geoLocationName ?: "?"
                        if (r.geoTriggerOnEnter != false) {
                            "📍 " + ctx.getString(R.string.schedule_geo_arrive, loc)
                        } else {
                            "📍 " + ctx.getString(R.string.schedule_geo_leave, loc)
                        }
                    }
                    else -> ""
                }
            }

            private fun buildNextText(r: RoutineEntity): String {
                val ctx = itemView.context
                if (!r.scheduleEnabled) return ctx.getString(R.string.schedule_paused)
                val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                return when {
                    r.nextTriggerAt != null -> ctx.getString(R.string.schedule_next, fmt.format(Date(r.nextTriggerAt)))
                    r.lastTriggeredAt != null -> ctx.getString(R.string.schedule_last, fmt.format(Date(r.lastTriggeredAt)))
                    else -> ""
                }
            }
        }
    }
}
