package com.silicongames.aura.actions

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * Handles CALENDAR intents by creating events in the device's calendar.
 * Uses the Android Calendar Provider API.
 */
class CalendarHandler(private val context: Context) {

    companion object {
        private const val TAG = "CalendarHandler"
    }

    /**
     * Add an event to the default calendar.
     */
    suspend fun addEvent(title: String, dateStr: String?, timeStr: String?) {
        val cleanTitle = title
            .replace(Regex("(?i)^(schedule |add |create )?(a |an |the )?"), "")
            .trim()
            .replaceFirstChar { it.uppercase() }

        val startTime = parseDateTime(dateStr, timeStr)

        try {
            // Find the primary calendar ID
            val calendarId = getPrimaryCalendarId() ?: run {
                Log.e(TAG, "No calendar found on device")
                return
            }

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, cleanTitle)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, startTime + 60 * 60 * 1000) // 1 hour default
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.DESCRIPTION, "Created by Aura Assistant")
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.d(TAG, "Calendar event created: $uri")
        } catch (e: SecurityException) {
            Log.e(TAG, "Calendar permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create calendar event", e)
        }
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )

        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = cursor.getInt(1) == 1
                    if (isPrimary) return id
                }
                // Fallback: use first calendar
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No calendar permission", e)
        }
        return null
    }

    /**
     * Parse date and time strings into a timestamp.
     */
    private fun parseDateTime(dateStr: String?, timeStr: String?): Long {
        val cal = Calendar.getInstance()

        // Parse date
        if (!dateStr.isNullOrBlank()) {
            val lower = dateStr.lowercase().trim()
            when {
                lower == "today" -> { /* already today */ }
                lower == "tomorrow" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                lower == "monday" || lower == "mon" -> setNextDayOfWeek(cal, Calendar.MONDAY)
                lower == "tuesday" || lower == "tue" -> setNextDayOfWeek(cal, Calendar.TUESDAY)
                lower == "wednesday" || lower == "wed" -> setNextDayOfWeek(cal, Calendar.WEDNESDAY)
                lower == "thursday" || lower == "thu" -> setNextDayOfWeek(cal, Calendar.THURSDAY)
                lower == "friday" || lower == "fri" -> setNextDayOfWeek(cal, Calendar.FRIDAY)
                lower == "saturday" || lower == "sat" -> setNextDayOfWeek(cal, Calendar.SATURDAY)
                lower == "sunday" || lower == "sun" -> setNextDayOfWeek(cal, Calendar.SUNDAY)
                else -> {
                    // Try parsing as a date
                    try {
                        val fmt = SimpleDateFormat("MMM d", Locale.US)
                        val parsed = fmt.parse(lower)
                        if (parsed != null) {
                            val parsedCal = Calendar.getInstance().apply { time = parsed }
                            cal.set(Calendar.MONTH, parsedCal.get(Calendar.MONTH))
                            cal.set(Calendar.DAY_OF_MONTH, parsedCal.get(Calendar.DAY_OF_MONTH))
                        }
                    } catch (_: Exception) { }
                }
            }
        }

        // Parse time
        if (!timeStr.isNullOrBlank()) {
            val formats = listOf(
                SimpleDateFormat("h:mm a", Locale.US),
                SimpleDateFormat("HH:mm", Locale.US),
                SimpleDateFormat("h a", Locale.US)
            )
            for (fmt in formats) {
                try {
                    val parsed = fmt.parse(timeStr.trim()) ?: continue
                    val parsedCal = Calendar.getInstance().apply { time = parsed }
                    cal.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                    cal.set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                    cal.set(Calendar.SECOND, 0)
                    break
                } catch (_: Exception) { }
            }
        }

        return cal.timeInMillis
    }

    private fun setNextDayOfWeek(cal: Calendar, dayOfWeek: Int) {
        val current = cal.get(Calendar.DAY_OF_WEEK)
        var daysAhead = dayOfWeek - current
        if (daysAhead <= 0) daysAhead += 7
        cal.add(Calendar.DAY_OF_YEAR, daysAhead)
    }
}
