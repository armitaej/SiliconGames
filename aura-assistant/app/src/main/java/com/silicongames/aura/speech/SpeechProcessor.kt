package com.silicongames.aura.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.silicongames.aura.DebugLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Manages continuous on-device speech recognition.
 *
 * IMPORTANT: SpeechRecognizer MUST be created and used on the main thread.
 * All operations are posted to the main looper handler to guarantee this.
 */
class SpeechProcessor(private val context: Context) {

    companion object {
        private const val TAG = "Speech"
        private const val MAX_RESTART_DELAY_MS = 5000L
        private const val BASE_RESTART_DELAY_MS = 500L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var consecutiveErrors = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Emits transcribed speech segments
    private val _transcriptions = MutableSharedFlow<TranscriptionResult>(extraBufferCapacity = 20)
    val transcriptions: SharedFlow<TranscriptionResult> = _transcriptions

    // Emits status updates for the UI
    private val _status = MutableSharedFlow<ListenerStatus>(extraBufferCapacity = 10)
    val status: SharedFlow<ListenerStatus> = _status

    data class TranscriptionResult(
        val text: String,
        val confidence: Float,
        val isPartial: Boolean
    )

    enum class ListenerStatus {
        IDLE, LISTENING, PROCESSING, ERROR, RESTARTING
    }

    /**
     * Start continuous speech recognition. Must be called from any thread —
     * internally posts to main thread.
     */
    fun startListening() {
        mainHandler.post {
            if (isListening) {
                DebugLog.log(TAG, "Already listening, ignoring start")
                return@post
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                DebugLog.log(TAG, "ERROR: Speech recognition not available on this device!")
                _status.tryEmit(ListenerStatus.ERROR)
                return@post
            }

            DebugLog.log(TAG, "Starting continuous speech recognition...")
            isListening = true
            createAndStartRecognizer()
        }
    }

    /**
     * Stop speech recognition and release resources.
     */
    fun stopListening() {
        mainHandler.post {
            DebugLog.log(TAG, "Stopping speech recognition")
            isListening = false
            consecutiveErrors = 0
            speechRecognizer?.apply {
                try {
                    stopListening()
                    cancel()
                    destroy()
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Error stopping: ${e.message}")
                }
            }
            speechRecognizer = null
            _status.tryEmit(ListenerStatus.IDLE)
        }
    }

    private fun createAndStartRecognizer() {
        // Must be on main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            DebugLog.log(TAG, "WARN: Not on main thread, posting")
            mainHandler.post { createAndStartRecognizer() }
            return
        }

        // Destroy previous instance
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            DebugLog.log(TAG, "Error destroying old recognizer: ${e.message}")
        }

        try {
            // Use application context to avoid service context issues
            val appContext = context.applicationContext

            // Always use the standard (Google-backed) recognizer.
            // createOnDeviceSpeechRecognizer causes ERROR_SERVER_DISCONNECTED (11)
            // on many devices where the on-device model isn't downloaded.
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                DebugLog.log(TAG, "Using standard speech recognizer")
            }

            speechRecognizer?.setRecognitionListener(createListener())

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2000L
                )
            }

            speechRecognizer?.startListening(intent)
            _status.tryEmit(ListenerStatus.LISTENING)
            DebugLog.log(TAG, "Recognizer started, waiting for speech...")
        } catch (e: Exception) {
            DebugLog.log(TAG, "FAILED to create/start recognizer: ${e.message}")
            Log.e(TAG, "Failed to start speech recognition", e)
            consecutiveErrors++
            scheduleRestart()
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _status.tryEmit(ListenerStatus.LISTENING)
            consecutiveErrors = 0
            DebugLog.log(TAG, "Ready — listening for speech")
        }

        override fun onBeginningOfSpeech() {
            DebugLog.log(TAG, "Speech detected!")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Could use for visual level meter
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _status.tryEmit(ListenerStatus.PROCESSING)
            DebugLog.log(TAG, "End of speech, processing...")
        }

        override fun onError(error: Int) {
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MISSING PERMISSIONS"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests"
                11 -> "Server disconnected (on-device model unavailable)"
                12 -> "Language not supported"
                13 -> "Language unavailable"
                else -> "Unknown error ($error)"
            }

            DebugLog.log(TAG, "Error: $errorMsg (code $error)")

            // These are normal in continuous listening — just restart silently
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                consecutiveErrors = 0
                if (isListening) scheduleRestart()
                return
            }

            // Permission error is fatal — don't retry
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                DebugLog.log(TAG, "Cannot listen without microphone permission!")
                _status.tryEmit(ListenerStatus.ERROR)
                isListening = false
                return
            }

            consecutiveErrors++
            DebugLog.log(TAG, "Consecutive errors: $consecutiveErrors")
            if (isListening) scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                val confidence = confidences?.getOrNull(0) ?: 0.5f

                DebugLog.log(TAG, "HEARD: \"$text\" (confidence: ${String.format("%.0f%%", confidence * 100)})")

                val emitted = _transcriptions.tryEmit(
                    TranscriptionResult(text = text, confidence = confidence, isPartial = false)
                )
                if (!emitted) {
                    DebugLog.log(TAG, "WARNING: Failed to emit transcription (buffer full)")
                }
            } else {
                DebugLog.log(TAG, "Results received but empty")
            }

            // Restart for continuous listening
            if (isListening) scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty() && matches[0].isNotBlank()) {
                DebugLog.log(TAG, "Partial: \"${matches[0]}\"")
                _transcriptions.tryEmit(
                    TranscriptionResult(text = matches[0], confidence = 0f, isPartial = true)
                )
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleRestart() {
        if (!isListening) return

        _status.tryEmit(ListenerStatus.RESTARTING)

        val delay = if (consecutiveErrors > 0) {
            (BASE_RESTART_DELAY_MS * (1 shl minOf(consecutiveErrors, 4)))
                .coerceAtMost(MAX_RESTART_DELAY_MS)
        } else {
            BASE_RESTART_DELAY_MS
        }

        DebugLog.log(TAG, "Restarting in ${delay}ms...")
        mainHandler.postDelayed({
            if (isListening) {
                createAndStartRecognizer()
            }
        }, delay)
    }
}
