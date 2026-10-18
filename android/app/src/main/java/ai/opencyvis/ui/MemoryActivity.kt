package ai.opencyvis.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ai.opencyvis.R
import ai.opencyvis.db.GlobalMemoryEntity
import ai.opencyvis.db.GlobalMemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MemoryActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: GlobalMemoryRepository
    private lateinit var adapter: MemoryAdapter
    private lateinit var searchInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        repository = GlobalMemoryRepository(this)
        searchInput = findViewById(R.id.edit_search)
        adapter = MemoryAdapter(
            onClick = { showEditDialog(it) },
            onLongClick = { showMemoryActions(it) }
        )

        findViewById<RecyclerView>(R.id.recycler_memories).apply {
            layoutManager = LinearLayoutManager(this@MemoryActivity)
            adapter = this@MemoryActivity.adapter
        }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_add_memory).setOnClickListener { showEditDialog(null) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                loadMemories()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        loadMemories()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadMemories() {
        val query = searchInput.text.toString()
        scope.launch {
            adapter.submitList(repository.search(query))
        }
    }

    private fun showEditDialog(memory: GlobalMemoryEntity?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val keyInput = dialogInput(getString(R.string.memory_dialog_key_hint)).apply {
            setText(memory?.key.orEmpty())
            isEnabled = memory == null
        }
        val valueInput = dialogInput(getString(R.string.memory_dialog_value_hint)).apply {
            setText(memory?.value.orEmpty())
            minLines = 2
        }
        val categoryInput = dialogInput(getString(R.string.memory_dialog_category_hint)).apply {
            setText(memory?.category.orEmpty())
        }
        container.addView(keyInput)
        container.addView(valueInput)
        container.addView(categoryInput)

        AlertDialog.Builder(this)
            .setTitle(if (memory == null) getString(R.string.memory_dialog_add_title) else getString(R.string.memory_dialog_edit_title))
            .setView(container)
            .setPositiveButton(getString(R.string.memory_dialog_save)) { _, _ ->
                val key = keyInput.text.toString().trim()
                val value = valueInput.text.toString().trim()
                val category = categoryInput.text.toString().trim()
                if (key.isEmpty() || value.isEmpty()) return@setPositiveButton
                scope.launch {
                    if (memory == null) {
                        repository.upsert(key, value, category, GlobalMemoryEntity.SOURCE_USER)
                    } else {
                        repository.update(
                            memory.copy(
                                value = value,
                                category = category,
                                source = GlobalMemoryEntity.SOURCE_USER,
                                enabled = true
                            )
                        )
                    }
                    loadMemories()
                }
            }
            .setNegativeButton(getString(R.string.memory_dialog_cancel), null)
            .show()
    }

    private fun showMemoryActions(memory: GlobalMemoryEntity) {
        val labels = arrayOf(
            if (memory.enabled) getString(R.string.memory_action_disable) else getString(R.string.memory_action_enable),
            getString(R.string.memory_action_delete)
        )
        AlertDialog.Builder(this)
            .setTitle(memory.key)
            .setItems(labels) { _, which ->
                scope.launch {
                    when (which) {
                        0 -> {
                            if (memory.enabled) {
                                repository.disable(memory.id)
                            } else {
                                repository.update(memory.copy(enabled = true))
                            }
                        }
                        1 -> repository.delete(memory.id)
                    }
                    loadMemories()
                }
            }
            .show()
    }

    private fun dialogInput(hint: String): EditText =
        EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            visibility = View.VISIBLE
        }
}
