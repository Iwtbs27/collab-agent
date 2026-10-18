package ai.opencyvis.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R

class ChatAdapter(
    private val onMessageLongClick: ((ChatMessage, View) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val data = ChatMessageList()

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AGENT = 1
        private const val TYPE_SYSTEM = 2
        private const val TYPE_CYCLE = 3
    }

    fun addMessage(message: ChatMessage) {
        data.addMessage(message).notify()
    }

    fun startCycle() {
        data.startCycle()?.notify()
    }

    fun updateCycleText(text: String) {
        data.updateCycleText(text)?.notify()
    }

    fun removeCycle() {
        data.removeCycle()?.notify()
    }

    fun hasCycle(): Boolean = data.hasCycle()

    fun convertCycleToResult(summary: String) {
        data.convertCycleToResult(summary).notify()
    }

    fun updateLastAgentStatus(text: String) {
        data.updateLastAgentStatus(text).notify()
    }

    fun clear() {
        val change = data.clear()
        notifyItemRangeRemoved(0, change.index)
    }

    override fun getItemCount() = data.size

    override fun getItemViewType(position: Int): Int {
        return when (data.get(position).type) {
            MessageType.USER_INPUT, MessageType.USER_ANSWER, MessageType.USER_SUPPLEMENT -> TYPE_USER
            MessageType.AGENT_CYCLE -> TYPE_CYCLE
            MessageType.AGENT_STATUS, MessageType.AGENT_DEBUG, MessageType.AGENT_QUESTION,
            MessageType.AGENT_RESULT -> TYPE_AGENT
            MessageType.SYSTEM -> TYPE_SYSTEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_CYCLE -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_cycle, parent, false)
                CycleViewHolder(view)
            }
            TYPE_AGENT -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_agent, parent, false)
                TextViewHolder(view)
            }
            TYPE_SYSTEM -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_system, parent, false)
                TextViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_chat_user, parent, false)
                TextViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CycleViewHolder -> holder.bind(data.get(position))
            is TextViewHolder -> {
                holder.bind(data.get(position))
                val message = data.get(position)
                // Add long-click listener for agent messages
                if (message.type == MessageType.USER_INPUT) {
                    holder.itemView.setOnLongClickListener { view ->
                        onMessageLongClick?.invoke(message, view)
                        true
                    }
                } else {
                    holder.itemView.setOnLongClickListener(null)
                }
            }
        }
    }

    private fun ChatMessageList.Change.notify() {
        when (type) {
            ChatMessageList.ChangeType.INSERTED -> notifyItemInserted(index)
            ChatMessageList.ChangeType.CHANGED -> notifyItemChanged(index)
            ChatMessageList.ChangeType.REMOVED -> notifyItemRemoved(index)
            ChatMessageList.ChangeType.RANGE_REMOVED -> notifyItemRangeRemoved(0, index)
        }
    }

    class TextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_message)
        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }

    class CycleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TypewriterTextView = itemView.findViewById(R.id.cycle_text)
        fun bind(message: ChatMessage) {
            textView.animateText(message.text)
        }
    }
}
