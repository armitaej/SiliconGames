package com.silicongames.aura.ai

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.silicongames.aura.DebugLog
import com.silicongames.aura.R
import com.silicongames.aura.actions.*
import com.silicongames.aura.data.ListNames

/**
 * Routes classified intents to the appropriate action handler.
 */
class ActionDispatcher(private val context: Context) {

    companion object {
        private const val TAG = "Dispatch"
        const val PREF_AUTO_ANSWER = "auto_send_questions"
    }

    data class ActionResponse(
        val title: String,
        val message: String,
        val type: ResponseType,
        val iconResId: Int = R.drawable.ic_aura_ear
    )

    enum class ResponseType {
        ANSWER, LIST_CONFIRM, REMINDER_SET, CALENDAR_ADDED,
        TEXT_SENT, WEB_RESULT, ERROR
    }

    private val questionHandler = QuestionHandler(context)
    private val listHandler = ListHandler(context)
    private val reminderHandler = ReminderHandler(context)
    private val calendarHandler = CalendarHandler(context)
    private val textMessageHandler = TextMessageHandler(context)
    private val webLookupHandler = WebLookupHandler(context)

    suspend fun dispatch(intent: IntentClassifier.ClassifiedIntent): ActionResponse? {
        DebugLog.log(TAG, "Dispatching ${intent.type}: ${intent.extractedData}")

        val autoAnswer = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_AUTO_ANSWER, false)

        return try {
            when (intent.type) {
                IntentClassifier.IntentType.QUESTION -> {
                    val query = intent.extractedData["query"] ?: intent.originalText
                    if (autoAnswer) {
                        DebugLog.log(TAG, "Auto-answer ON — answering question...")
                        val answer = questionHandler.answer(query)
                        // Save the Q+A to the questions list too so the user
                        // has a history they can scroll through.
                        listHandler.addQuestion(query, answer)
                        ActionResponse(
                            title = "Answer",
                            message = answer,
                            type = ResponseType.ANSWER,
                            iconResId = R.drawable.ic_question
                        )
                    } else {
                        DebugLog.log(TAG, "Auto-answer OFF — saving question to list (no API call)")
                        listHandler.addQuestion(query, answer = null)
                        null  // QUIET — no overlay
                    }
                }

                IntentClassifier.IntentType.LIST_ADD -> {
                    val item = intent.extractedData["item"] ?: intent.originalText
                    val listName = intent.extractedData["list"] ?: ListNames.GROCERY
                    DebugLog.log(TAG, "Adding to list: \"$item\" → $listName")
                    listHandler.addItem(item, listName)
                    null  // QUIET — no overlay for passive captures
                }

                IntentClassifier.IntentType.TODO_ADD -> {
                    val task = intent.extractedData["task"] ?: intent.originalText
                    DebugLog.log(TAG, "Adding to to-do list: \"$task\"")
                    listHandler.addItem(task, ListNames.TODO)
                    null  // QUIET — no overlay
                }

                IntentClassifier.IntentType.REMINDER -> {
                    val task = intent.extractedData["task"] ?: intent.originalText
                    val time = intent.extractedData["time"]
                    DebugLog.log(TAG, "Setting reminder: \"$task\" at $time")
                    reminderHandler.setReminder(task, time)
                    val timeMsg = if (time != null) " for $time" else ""
                    ActionResponse(
                        title = "Reminder set$timeMsg",
                        message = task,
                        type = ResponseType.REMINDER_SET,
                        iconResId = R.drawable.ic_reminder
                    )
                }

                IntentClassifier.IntentType.CALENDAR -> {
                    val event = intent.extractedData["event"] ?: intent.originalText
                    val date = intent.extractedData["date"]
                    val time = intent.extractedData["time"]
                    DebugLog.log(TAG, "Adding calendar event: \"$event\"")
                    calendarHandler.addEvent(event, date, time)
                    ActionResponse(
                        title = "Calendar event added",
                        message = "$event${date?.let { " on $it" } ?: ""}${time?.let { " at $it" } ?: ""}",
                        type = ResponseType.CALENDAR_ADDED,
                        iconResId = R.drawable.ic_calendar
                    )
                }

                IntentClassifier.IntentType.SEND_TEXT -> {
                    val recipient = intent.extractedData["recipient"] ?: "Unknown"
                    val message = intent.extractedData["message"] ?: intent.originalText
                    DebugLog.log(TAG, "Sending text to $recipient")
                    textMessageHandler.sendText(recipient, message)
                    ActionResponse(
                        title = "Text to $recipient",
                        message = message,
                        type = ResponseType.TEXT_SENT,
                        iconResId = R.drawable.ic_text
                    )
                }

                IntentClassifier.IntentType.WEB_LOOKUP -> {
                    val query = intent.extractedData["query"] ?: intent.originalText
                    if (autoAnswer) {
                        DebugLog.log(TAG, "Auto-answer ON — looking up: \"$query\"")
                        val result = webLookupHandler.lookup(query)
                        listHandler.addLookup(query, result)
                        ActionResponse(
                            title = "Web Result",
                            message = result,
                            type = ResponseType.WEB_RESULT,
                            iconResId = R.drawable.ic_web
                        )
                    } else {
                        DebugLog.log(TAG, "Auto-answer OFF — saving lookup to list (no API call)")
                        listHandler.addLookup(query, result = null)
                        null  // QUIET — no overlay
                    }
                }

                IntentClassifier.IntentType.NONE -> null
            }
        } catch (e: Exception) {
            DebugLog.log(TAG, "CRASH in handler: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Error dispatching action", e)
            ActionResponse(
                title = "Error",
                message = "Something went wrong: ${e.localizedMessage}",
                type = ResponseType.ERROR,
                iconResId = R.drawable.ic_aura_ear
            )
        }
    }
}
