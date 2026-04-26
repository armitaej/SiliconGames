package com.silicongames.aura.actions

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.silicongames.aura.AuraApplication
import com.silicongames.aura.R

/**
 * WorkManager worker that fires reminder notifications at the scheduled time.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ReminderWorker"
        private const val NOTIFICATION_BASE_ID = 5000
    }

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong("reminder_id", -1)
        val reminderText = inputData.getString("reminder_text") ?: "Reminder"

        Log.d(TAG, "Firing reminder #$reminderId: $reminderText")

        // Mark as triggered in database
        if (reminderId > 0) {
            val db = AuraApplication.instance.database
            val reminder = db.reminderDao().getById(reminderId)
            if (reminder != null) {
                db.reminderDao().update(reminder.copy(isTriggered = true))
            }
        }

        // Show notification
        val notification = NotificationCompat.Builder(applicationContext, AuraApplication.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("Aura Reminder")
            .setContentText(reminderText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val notificationManager = applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_BASE_ID + reminderId.toInt(),
            notification
        )

        return Result.success()
    }
}
