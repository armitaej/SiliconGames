package com.silicongames.aura.actions

import android.content.Context
import com.silicongames.aura.AuraApplication
import com.silicongames.aura.DebugLog
import com.silicongames.aura.data.ListItem
import com.silicongames.aura.data.ListNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles LIST_ADD intents by adding items to the local Room database.
 * Supports multiple named lists (grocery, todo, shopping, etc.)
 */
class ListHandler(private val context: Context) {

    companion object {
        private const val TAG = "ListHandler"
    }

    /**
     * Add an item to a named list.
     */
    suspend fun addItem(item: String, listName: String = "grocery") {
        withContext(Dispatchers.IO) {
            try {
                val db = AuraApplication.instance.database

                val cleanItem = item.trim()
                    .replaceFirst(Regex("(?i)^(some |more |a |an |the )"), "")
                    .trim()
                    .replaceFirstChar { it.uppercase() }

                if (cleanItem.isBlank()) {
                    DebugLog.log(TAG, "Empty item after cleaning, skipping")
                    return@withContext
                }

                // Check for duplicates
                val existing = db.listItemDao().getItemsByList(listName)
                if (existing.any { it.text.equals(cleanItem, ignoreCase = true) && !it.isChecked }) {
                    DebugLog.log(TAG, "\"$cleanItem\" already on $listName list, skipping duplicate")
                    return@withContext
                }

                val listItem = ListItem(
                    text = cleanItem,
                    listName = listName,
                    timestamp = System.currentTimeMillis()
                )

                db.listItemDao().insert(listItem)
                DebugLog.log(TAG, "Added \"$cleanItem\" to $listName list")
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR adding item: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Save a captured question to the "questions" list. If [answer] is non-null
     * it's stored too (auto-answer mode); otherwise the question waits in the
     * list until the user taps it to send to Claude.
     *
     * Skips duplicates of the most recent unanswered question with the same text.
     */
    suspend fun addQuestion(question: String, answer: String?) {
        withContext(Dispatchers.IO) {
            try {
                val db = AuraApplication.instance.database
                val text = question.trim().replaceFirstChar { it.uppercase() }
                if (text.isBlank()) return@withContext

                val existing = db.listItemDao().getItemsByList(ListNames.QUESTIONS)
                if (existing.any { it.text.equals(text, ignoreCase = true) && it.answer == null }) {
                    DebugLog.log(TAG, "Question \"$text\" already pending, skipping duplicate")
                    return@withContext
                }

                db.listItemDao().insert(
                    ListItem(
                        text = text,
                        listName = ListNames.QUESTIONS,
                        timestamp = System.currentTimeMillis(),
                        answer = answer
                    )
                )
                DebugLog.log(TAG, "Saved question${if (answer != null) " (with answer)" else ""}: \"$text\"")
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR saving question: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Save a captured web lookup to the "lookups" list. Same shape as questions.
     */
    suspend fun addLookup(query: String, result: String?) {
        withContext(Dispatchers.IO) {
            try {
                val db = AuraApplication.instance.database
                val text = query.trim().replaceFirstChar { it.uppercase() }
                if (text.isBlank()) return@withContext

                val existing = db.listItemDao().getItemsByList(ListNames.LOOKUPS)
                if (existing.any { it.text.equals(text, ignoreCase = true) && it.answer == null }) {
                    DebugLog.log(TAG, "Lookup \"$text\" already pending, skipping duplicate")
                    return@withContext
                }

                db.listItemDao().insert(
                    ListItem(
                        text = text,
                        listName = ListNames.LOOKUPS,
                        timestamp = System.currentTimeMillis(),
                        answer = result
                    )
                )
                DebugLog.log(TAG, "Saved lookup${if (result != null) " (with result)" else ""}: \"$text\"")
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERROR saving lookup: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Get all items in a named list.
     */
    suspend fun getItems(listName: String): List<ListItem> {
        return withContext(Dispatchers.IO) {
            AuraApplication.instance.database.listItemDao().getItemsByList(listName)
        }
    }

    /**
     * Toggle checked status of an item.
     */
    suspend fun toggleItem(itemId: Long) {
        withContext(Dispatchers.IO) {
            val db = AuraApplication.instance.database
            val item = db.listItemDao().getById(itemId) ?: return@withContext
            db.listItemDao().update(item.copy(isChecked = !item.isChecked))
        }
    }

    /**
     * Remove all checked items from a list.
     */
    suspend fun clearChecked(listName: String) {
        withContext(Dispatchers.IO) {
            AuraApplication.instance.database.listItemDao().deleteCheckedItems(listName)
        }
    }
}
