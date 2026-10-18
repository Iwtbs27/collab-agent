package ai.opencyvis.ui

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.GlobalMemoryEntity

class MemoryAdapter(
    private val onClick: (GlobalMemoryEntity) -> Unit,
    private val onLongClick: (GlobalMemoryEntity) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.ViewHolder>() {

    private val items = mutableListOf<GlobalMemoryEntity>()

    fun submitList(memories: List<GlobalMemoryEntity>) {
        items.clear()
        items.addAll(memories)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false)
        return ViewHolder(view, onClick, onLongClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class ViewHolder(
        itemView: View,
        private val onClick: (GlobalMemoryEntity) -> Unit,
        private val onLongClick: (GlobalMemoryEntity) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val keyText: TextView = itemView.findViewById(R.id.text_key)
        private val valueText: TextView = itemView.findViewById(R.id.text_value)
        private val metaText: TextView = itemView.findViewById(R.id.text_meta)
        private val enabledText: TextView = itemView.findViewById(R.id.text_enabled)

        fun bind(memory: GlobalMemoryEntity) {
            val ctx = itemView.context
            keyText.text = memory.key
            valueText.text = memory.value
            enabledText.text = if (memory.enabled) ctx.getString(R.string.memory_status_enabled) else ctx.getString(R.string.memory_status_disabled)
            enabledText.setTextColor(
                ContextCompat.getColor(ctx, if (memory.enabled) R.color.color_success else R.color.text_subtitle)
            )
            val source = if (memory.source == GlobalMemoryEntity.SOURCE_AI) ctx.getString(R.string.memory_source_ai) else ctx.getString(R.string.memory_source_user)
            val category = if (memory.category.isBlank()) ctx.getString(R.string.memory_uncategorized) else memory.category
            val updated = DateFormat.format("yyyy-MM-dd HH:mm", memory.updatedAt)
            metaText.text = "$source · $category · $updated"
            itemView.setOnClickListener { onClick(memory) }
            itemView.setOnLongClickListener {
                onLongClick(memory)
                true
            }
        }
    }
}
