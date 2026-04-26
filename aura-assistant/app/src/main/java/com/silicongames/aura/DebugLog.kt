package com.silicongames.aura

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Global debug log that all components write to.
 * The MainActivity observes this to show a live debug panel,
 * so you can see exactly what the app is doing at each stage.
 */
object DebugLog {

    private const val MAX_ENTRIES = 100
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    fun log(tag: String, message: String) {
        val time = timeFormat.format(Date())
        val entry = "[$time] $tag: $message"
        Log.d("AuraDebug", entry)

        val current = _entries.value.toMutableList()
        current.add(0, entry) // newest first
        if (current.size > MAX_ENTRIES) {
            current.removeAt(current.lastIndex)
        }
        _entries.value = current
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
