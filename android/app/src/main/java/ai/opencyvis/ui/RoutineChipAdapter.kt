package ai.opencyvis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.RoutineEntity

class RoutineChipAdapter(
    private val onItemClick: (RoutineEntity) -> Unit,
    private val onItemLongClick: ((RoutineEntity) -> Unit)? = null
) : RecyclerView.Adapter<RoutineChipAdapter.ViewHolder>() {

    private var routines: List<RoutineEntity> = emptyList()

    fun submitList(list: List<RoutineEntity>) {
        routines = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_routine_chip, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val routine = routines[position]
        holder.bind(routine)
        holder.itemView.setOnClickListener { onItemClick(routine) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(routine)
            true
        }
    }

    override fun getItemCount(): Int = routines.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconText: TextView = itemView.findViewById(R.id.chip_icon)
        private val nameText: TextView = itemView.findViewById(R.id.chip_text)

        fun bind(routine: RoutineEntity) {
            iconText.text = routine.icon
            // Resolve string resource by key
            val context = itemView.context
            val nameResId = context.resources.getIdentifier(routine.name, "string", context.packageName)
            nameText.text = if (nameResId != 0) context.getString(nameResId) else routine.name
        }
    }
}
