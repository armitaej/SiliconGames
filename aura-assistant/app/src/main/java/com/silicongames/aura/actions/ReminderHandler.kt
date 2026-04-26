package com.silicongames.aura.actions

import android.content.Context
import android.util.Log
import androidx.work.*
import com.silicongames.aura.AuraApplication
import com.silicongames.aura.data.ReminderItem
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Handles REMINDER intents by saving reminders to the database
 * and scheduling WorkManager tasks to trigger notifications.
 */
class ReminderHandler(private val context: Context) {

    companion object {
        private const val TAG = "ReminderHandler"
    }

    private val db get() = AuraApplication.instance.database

    /**
     * Set a reminder with optional time string.
     * If no time is provided, defaults to 30 minutes from now.
     */
    suspend fun setReminder(task: String, timeStr: String? = null) {
        val cleanTask = task
            .replace(Regex("(?i)^(remind me to |don't let me forget to |i need to remember to |set a reminder to )"), "")
            .trim()
            .replaceFirstChar { it.uppercase() }

        val triggerTime = parseTime(timeStr) ?: (System.currentTimeMillis() + 30 * 60 * 1000)

        val reminder = ReminderItem(
            text = cleanTask,
            triggerTime = triggerTime,
            createdTime = System.currentTimeMillis()
        )

        val id = db.reminderDao().insert(reminder)
        Log.d(TAG, "Reminder saved: \"$cleanTask\" for ${Date(triggerTime)}")

        // Schedule the notification
        scheduleReminderWork(id, cleanTask, triggerTime)
    }

    private fun scheduleReminderWork(reminderId: Long, task: String, triggerTime: Long) {
        val delay = triggerTime - System.currentTimeMillis()
        if (delay <= 0) return

        val data = Data.Builder()
            .putLong("reminder_id", reminderId)
            .putString("reminder_text", task)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("reminder_$reminderId")
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        Log.d(TAG, "Scheduled reminder work in ${delay / 1000}s")
    }

    /**
     * Parse common time expressions into a timestamp.
     */
    private fun parseTime(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null

        val lower = timeStr.lowercase().trim()
        val cal = Calendar.getInstance()

        return try {
            when {
                // Relative: "in 5 minutes", "in 1 hour"
                lower.startsWith("in ") -> {
                    val parts = lower.removePrefix("in ").trim().split(" ")
                    val amount = parts[0].toIntOrNull() ?: return null
                    val unit = parts.getOrNull(1) ?: return null
                    when {
                        unit.startsWith("min") -> cal.add(Calendar.MINUTE, amount)
                        unit.startsWith("hour") -> cal.add(Calendar.HOUR_OF_DAY, amount)
                        unit.startsWith("sec") -> cal.add(Calendar.SECOND, amount)
                        else -> return null
                    }
                    cal.timeInMillis
                }

                // Absolute: "5:00 PM", "17:00", "5 PM"
                lower.contains(":") || lower.contains("am") || lower.contains("pm") -> {
                    val formats = listOf(
                        SimpleDateFormat("h:mm a", Locale.US),
                        SimpleDateFormat("HH:mm", Locale.US),
                        SimpleDateFormat("h a", Locale.US)
                    )
                    for (fmt in formats) {
                        try {
                            val parsed = fmt.parse(lower) ?: continue
                            val parsedCal = Calendar.getInstance().apply { time = parsed }
                            cal.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                            cal.set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                            cal.set(Calendar.SECOND, 0)
                            // If the time is in the past, schedule for tomorrow
                            if (cal.timeInMillis <= System.currentTimeMillis()) {
                                cal.add(Calendar.DAY_OF_YEAR, 1)
                            }
                            return cal.timeInMillis
                        } catch (_: Exception) { }
                    }
                    null
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse time: $timeStr", e)
            null
        }
    }
}
