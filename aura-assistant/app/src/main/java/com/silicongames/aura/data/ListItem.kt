package com.silicongames.aura.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "list_items")
data class ListItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val text: String,
    val listName: String = "grocery",
    val isChecked: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Optional answer text. Used by the "questions" list — null until the
     * user taps the question to send it to Claude, then stores Claude's reply.
     */
    val answer: String? = null
)

/**
 * Canonical list names used across the app. Anything else is allowed but
 * these are the ones the UI knows how to display in dedicated sections.
 */
object ListNames {
    const val GROCERY = "grocery"
    const val TODO = "todo"
    const val SHOPPING = "shopping"
    const val QUESTIONS = "questions"
    const val LOOKUPS = "lookups"
}
