package com.silicongames.aura.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.silicongames.aura.AuraApplication
import com.silicongames.aura.DebugLog
import com.silicongames.aura.R
import com.silicongames.aura.ai.ActionDispatcher
import com.silicongames.aura.ai.IntentClassifier
import com.silicongames.aura.overlay.OverlayService
import com.silicongames.aura.speech.SpeechProcessor
import com.silicongames.aura.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter

/**
 * Foreground service that keeps speech recognition running in the background.
 */
class ListenerService : Service() {

    companion object {
        private const val TAG = "Service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.silicongames.aura.START_LISTENING"
        const val ACTION_STOP = "com.silicongames.aura.STOP_LISTENING"
    }

    private lateinit var speechProcessor: SpeechProcessor
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var actionDispatcher: ActionDispatcher
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pipelineStarted = false

    override fun onCreate() {
        super.onCreate()
        DebugLog.log(TAG, "ListenerService created")

        speechProcessor = SpeechProcessor(applicationContext) // use app context!
        intentClassifier = IntentClassifier(applicationContext)
        actionDispatcher = ActionDispatcher(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.log(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                DebugLog.log(TAG, "Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (!startForegroundSafely()) {
                    DebugLog.log(TAG, "Could not enter foreground state, stopping service")
                    stopSelf()
                    return START_NOT_STICKY
                }
                acquireWakeLock()
                if (!pipelineStarted) {
                    startListeningPipeline()
                    pipelineStarted = true
                } else {
                    DebugLog.log(TAG, "Pipeline already running, skipping duplicate start")
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DebugLog.log(TAG, "ListenerService destroyed")
        pipelineStarted = false
        serviceScope.cancel()
        speechProcessor.stopListening()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Main pipeline: listen → classify → dispatch → display
     */
    private fun startListeningPipeline() {
        DebugLog.log(TAG, "Starting pipeline...")
        speechProcessor.startListening()

        // Process only final (non-partial) transcriptions
        // Use collect (not collectLatest) so we don't cancel in-flight API calls
        serviceScope.launch {
            DebugLog.log(TAG, "Pipeline collector started")
            speechProcessor.transcriptions
                .filter { !it.isPartial && it.text.isNotBlank() }
                .collect { result ->
                    DebugLog.log("Pipeline", "Processing: \"${result.text}\"")

                    try {
                        // Step 1: Classify the intent
                        DebugLog.log("Pipeline", "Classifying intent...")
                        val classifiedIntent = intentClassifier.classify(result.text)

                        if (classifiedIntent != null) {
                            DebugLog.log("Pipeline", "Intent: ${classifiedIntent.type} → ${classifiedIntent.extractedData}")

                            // Step 2: Execute the action
                            DebugLog.log("Pipeline", "Dispatching action...")
                            val response = actionDispatcher.dispatch(classifiedIntent)

                            // Step 3: Show result in overlay
                            if (response != null) {
                                DebugLog.log("Pipeline", "Showing overlay: ${response.title} — ${response.message}")
                                showOverlay(response)
                            } else {
                                DebugLog.log("Pipeline", "Dispatcher returned null response")
                            }
                        } else {
                            DebugLog.log("Pipeline", "No actionable intent detected")
                        }
                    } catch (e: Exception) {
                        DebugLog.log("Pipeline", "ERROR: ${e.message}")
                        Log.e(TAG, "Error processing transcription", e)
                    }
                }
        }

        // Monitor status
        serviceScope.launch {
            speechProcessor.status.collect { status ->
                DebugLog.log(TAG, "Status: $status")
            }
        }
    }

    private fun showOverlay(response: ActionDispatcher.ActionResponse) {
        try {
            val overlayIntent = Intent(this, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_TITLE, response.title)
                putExtra(OverlayService.EXTRA_MESSAGE, response.message)
                putExtra(OverlayService.EXTRA_TYPE, response.type.name)
                putExtra(OverlayService.EXTRA_ICON, response.iconResId)
            }
            // ListenerService is itself a foreground service so background-start
            // restrictions are waived, but wrap defensively anyway.
            startService(overlayIntent)
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to start overlay service: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Overlay start failed", e)
        }
    }

    /**
     * Enter foreground state in a way that satisfies Android 14's strict
     * foreground service type rules. Returns false if we can't legally do so
     * (e.g. RECORD_AUDIO permission was revoked between start and now).
     */
    private fun startForegroundSafely(): Boolean {
        // RECORD_AUDIO is required because we declared foregroundServiceType=microphone
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            DebugLog.log(TAG, "RECORD_AUDIO not granted — refusing to start foreground")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            DebugLog.log(TAG, "Entered foreground state")
            true
        } catch (e: Exception) {
            DebugLog.log(TAG, "startForeground FAILED: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ListenerService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AuraApplication.CHANNEL_LISTENER)
            .setContentTitle("Aura is listening")
            .setContentText("Tap to open settings")
            .setSmallIcon(R.drawable.ic_aura_ear)
            .setContentIntent(pendingOpen)
            .addAction(R.drawable.ic_stop, "Stop", pendingStop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "aura:listener"
        ).apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
