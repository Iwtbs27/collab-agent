package ai.opencyvis.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.ConversationEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class ConversationHistoryAdapter(
    private val onClick: (ConversationEntity) -> Unit,
    private val onLongClick: (ConversationEntity) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1
    }

    private sealed class ListItem {
        data class Header(val label: String) : ListItem()
        data class Item(val conversation: ConversationEntity) : ListItem()
    }

    private val items = mutableListOf<ListItem>()

    fun submitList(conversations: List<ConversationEntity>) {
        items.clear()
        val grouped = conversations.groupBy { getDateLabel(it.updatedAt) }
        for ((label, convs) in grouped) {
            items.add(ListItem.Header(label))
            convs.forEach { items.add(ListItem.Item(it)) }
        }
        notifyDataSetChanged()
    }

    fun removeConversation(convId: Long) {
        val idx = items.indexOfFirst { it is ListItem.Item && it.conversation.id == convId }
        if (idx < 0) return
        items.removeAt(idx)
        if (idx > 0 && items[idx - 1] is ListItem.Header) {
            val nextIsHeaderOrEnd = idx >= items.size || items[idx] is ListItem.Header
            if (nextIsHeaderOrEnd) {
                items.removeAt(idx - 1)
                notifyItemRangeRemoved(idx - 1, 2)
                return
            }
        }
        notifyItemRemoved(idx)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ListItem.Header -> TYPE_HEADER
        is ListItem.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_conversation_date_header, parent, false))
        } else {
            ItemViewHolder(inflater.inflate(R.layout.item_conversation, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item.label)
            is ListItem.Item -> (holder as ItemViewHolder).bind(item.conversation)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val text: TextView = itemView.findViewById(R.id.text_date_header)
        fun bind(label: String) { text.text = label }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.text_title)
        private val subtitle: TextView = itemView.findViewById(R.id.text_subtitle)
        private val status: TextView = itemView.findViewById(R.id.text_status)

        fun bind(conv: ConversationEntity) {
            title.text = conv.title
            subtitle.text = formatTime(conv.updatedAt)
            status.text = statusIcon(conv.status)
            itemView.setOnClickListener { onClick(conv) }
            itemView.setOnLongClickListener { onLongClick(conv); true }
        }
    }

    private fun statusIcon(status: String): String = when (status) {
        "completed" -> "\u2705"
        "failed" -> "\u274C"
        "stopped" -> "\u26A0\uFE0F"
        "running" -> "\u25B6\uFE0F"
        else -> ""
    }

    private fun formatTime(timestamp: Long): String {
        return DateUtils.getRelativeTimeSpanString(
            timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    private fun getDateLabel(timestamp: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        return when {
            isSameDay(cal, today) -> "Today"
            isSameDay(cal, yesterday) -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
        }
    }

    private fun isSameDay(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
