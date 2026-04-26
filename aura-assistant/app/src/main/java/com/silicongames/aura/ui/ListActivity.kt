package com.silicongames.aura.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.silicongames.aura.AuraApplication
import com.silicongames.aura.DebugLog
import com.silicongames.aura.R
import com.silicongames.aura.actions.QuestionHandler
import com.silicongames.aura.actions.WebLookupHandler
import com.silicongames.aura.data.ListItem
import com.silicongames.aura.data.ListNames
import com.silicongames.aura.data.ReminderItem
import com.silicongames.aura.databinding.ActivityListBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Aggregate view of every list Aura captures: grocery, to-do, questions,
 * web lookups, and reminders. Questions and lookups support tap-to-resolve:
 * an unanswered item, when tapped, fires the appropriate Claude call and
 * stores the answer back into the row so the user has a permanent record.
 */
class ListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListBinding
    private val db get() = AuraApplication.instance.database
    private val questionHandler by lazy { QuestionHandler(applicationContext) }
    private val lookupHandler by lazy { WebLookupHandler(applicationContext) }

    // Adapters for the five sections
    private val groceryAdapter = SimpleListAdapter { toggleChecked(it) }
    private val todoAdapter = SimpleListAdapter { toggleChecked(it) }
    private val questionsAdapter = QnaAdapter { onQuestionTapped(it) }
    private val lookupsAdapter = QnaAdapter { onLookupTapped(it) }
    private val reminderAdapter = ReminderAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            title = "Aura Lists"
            setDisplayHomeAsUpEnabled(true)
        }

        wireRecycler(binding.recyclerGrocery, groceryAdapter)
        wireRecycler(binding.recyclerTodo, todoAdapter)
        wireRecycler(binding.recyclerQuestions, questionsAdapter)
        wireRecycler(binding.recyclerLookups, lookupsAdapter)
        wireRecycler(binding.recyclerReminders, reminderAdapter)

        binding.btnClearGrocery.setOnClickListener {
            lifecycleScope.launch { db.listItemDao().deleteCheckedItems(ListNames.GROCERY) }
        }
        binding.btnClearTodo.setOnClickListener {
            lifecycleScope.launch { db.listItemDao().deleteCheckedItems(ListNames.TODO) }
        }
        binding.btnClearQuestions.setOnClickListener {
            lifecycleScope.launch { db.listItemDao().deleteResolvedItems(ListNames.QUESTIONS) }
        }
        binding.btnClearLookups.setOnClickListener {
            lifecycleScope.launch { db.listItemDao().deleteResolvedItems(ListNames.LOOKUPS) }
        }
        binding.btnClearReminders.setOnClickListener {
            lifecycleScope.launch { db.reminderDao().deleteTriggered() }
        }

        observeData()
    }

    private fun wireRecycler(rv: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
    }

    private fun toggleChecked(item: ListItem) {
        lifecycleScope.launch {
            db.listItemDao().update(item.copy(isChecked = !item.isChecked))
        }
    }

    /**
     * Tap behavior on a question: if no answer yet, send to Claude. If
     * answer exists, toggle the row checked (review-then-clear flow).
     */
    private fun onQuestionTapped(item: ListItem) {
        if (item.answer != null) {
            toggleChecked(item)
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@ListActivity, "Asking Claude…", Toast.LENGTH_SHORT).show()
            DebugLog.log("Lists", "Resolving question: \"${item.text}\"")
            val answer = questionHandler.answer(item.text)
            db.listItemDao().update(item.copy(answer = answer))
        }
    }

    private fun onLookupTapped(item: ListItem) {
        if (item.answer != null) {
            toggleChecked(item)
            return
        }
        lifecycleScope.launch {
            Toast.makeText(this@ListActivity, "Looking up…", Toast.LENGTH_SHORT).show()
            DebugLog.log("Lists", "Resolving lookup: \"${item.text}\"")
            val result = lookupHandler.lookup(item.text)
            db.listItemDao().update(item.copy(answer = result))
        }
    }

    private fun observeData() {
        // Grocery
        lifecycleScope.launch {
            db.listItemDao().getItemsByListFlow(ListNames.GROCERY).collectLatest { items ->
                groceryAdapter.submitList(items)
                binding.textGroceryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // To-do
        lifecycleScope.launch {
            db.listItemDao().getItemsByListFlow(ListNames.TODO).collectLatest { items ->
                todoAdapter.submitList(items)
                binding.textTodoEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // Questions
        lifecycleScope.launch {
            db.listItemDao().getItemsByListFlow(ListNames.QUESTIONS).collectLatest { items ->
                questionsAdapter.submitList(items)
                binding.textQuestionsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // Lookups
        lifecycleScope.launch {
            db.listItemDao().getItemsByListFlow(ListNames.LOOKUPS).collectLatest { items ->
                lookupsAdapter.submitList(items)
                binding.textLookupsEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        // Reminders
        lifecycleScope.launch {
            db.reminderDao().getAllFlow().collectLatest { reminders ->
                reminderAdapter.submitList(reminders)
                binding.textRemindersEmpty.visibility = if (reminders.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ---------------------------------------------------------------
    // Adapters
    // ---------------------------------------------------------------

    /** Plain checkbox+text rows for grocery and to-do. */
    class SimpleListAdapter(
        private val onToggle: (ListItem) -> Unit
    ) : RecyclerView.Adapter<SimpleListAdapter.VH>() {

        private var items = listOf<ListItem>()

        fun submitList(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox_item)
            val text: TextView = view.findViewById(R.id.text_item)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_list, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.text.text = item.text
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = item.isChecked
            holder.checkbox.setOnClickListener { onToggle(item) }
            holder.itemView.setOnClickListener { onToggle(item) }
        }

        override fun getItemCount() = items.size
    }

    /** Question/lookup rows that show their answer when present. */
    class QnaAdapter(
        private val onTap: (ListItem) -> Unit
    ) : RecyclerView.Adapter<QnaAdapter.VH>() {

        private var items = listOf<ListItem>()

        fun submitList(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val status: TextView = view.findViewById(R.id.text_question_status)
            val text: TextView = view.findViewById(R.id.text_question)
            val answer: TextView = view.findViewById(R.id.text_answer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_question, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.text.text = item.text
            if (item.answer != null) {
                holder.status.text = if (item.isChecked) "✓" else "•"
                holder.status.setTextColor(0xFF4CAF50.toInt())
                holder.answer.text = item.answer
                holder.answer.visibility = View.VISIBLE
            } else {
                holder.status.text = "?"
                holder.status.setTextColor(0xFF6C63FF.toInt())
                holder.answer.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onTap(item) }
        }

        override fun getItemCount() = items.size
    }

    /** Reminders. */
    class ReminderAdapter : RecyclerView.Adapter<ReminderAdapter.VH>() {

        private var items = listOf<ReminderItem>()
        private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun submitList(newItems: List<ReminderItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(R.id.text_reminder)
            val time: TextView = view.findViewById(R.id.text_reminder_time)
            val status: TextView = view.findViewById(R.id.text_reminder_status)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_reminder, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.text.text = item.text
            holder.time.text = dateFormat.format(Date(item.triggerTime))
            holder.status.text = if (item.isTriggered) "Done" else "Pending"
            holder.status.setTextColor(
                if (item.isTriggered) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt()
            )
        }

        override fun getItemCount() = items.size
    }
}
