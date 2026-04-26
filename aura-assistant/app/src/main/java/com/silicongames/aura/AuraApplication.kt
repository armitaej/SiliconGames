package com.silicongames.aura

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.silicongames.aura.data.AppDatabase

class AuraApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Foreground service channel (persistent, low priority)
            val listenerChannel = NotificationChannel(
                CHANNEL_LISTENER,
                "Listener Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Aura listening in the background"
                setShowBadge(false)
            }

            // Responses channel (answers, confirmations)
            val responsesChannel = NotificationChannel(
                CHANNEL_RESPONSES,
                "Aura Responses",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Answers and action confirmations from Aura"
            }

            // Reminders channel
            val remindersChannel = NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders set through Aura"
            }

            manager.createNotificationChannels(
                listOf(listenerChannel, responsesChannel, remindersChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_LISTENER = "aura_listener"
        const val CHANNEL_RESPONSES = "aura_responses"
        const val CHANNEL_REMINDERS = "aura_reminders"

        lateinit var instance: AuraApplication
            private set
    }
}
