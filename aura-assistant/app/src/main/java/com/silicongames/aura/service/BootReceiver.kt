package com.silicongames.aura.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager

/**
 * Restarts the ListenerService after device boot if it was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val wasEnabled = prefs.getBoolean("listening_enabled", false)

            if (wasEnabled) {
                Log.d("BootReceiver", "Restarting Aura listener after boot")
                val serviceIntent = Intent(context, ListenerService::class.java).apply {
                    action = ListenerService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
