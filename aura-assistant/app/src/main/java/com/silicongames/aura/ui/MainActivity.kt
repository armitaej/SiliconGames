package com.silicongames.aura.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.silicongames.aura.DebugLog
import com.silicongames.aura.R
import com.silicongames.aura.ai.ActionDispatcher
import com.silicongames.aura.ai.IntentClassifier
import com.silicongames.aura.databinding.ActivityMainBinding
import com.silicongames.aura.overlay.OverlayService
import com.silicongames.aura.service.ListenerService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main settings screen for Aura.
 *
 * Includes:
 * - Enable/disable listening
 * - API key configuration
 * - Permission management
 * - MANUAL TEST INPUT to test the classifier directly
 * - LIVE DEBUG LOG showing exactly what the app hears and does
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    // For manual testing the classify → dispatch → overlay pipeline
    private lateinit var testClassifier: IntentClassifier
    private lateinit var testDispatcher: ActionDispatcher

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startListenerService()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            binding.switchListening.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        testClassifier = IntentClassifier(applicationContext)
        testDispatcher = ActionDispatcher(applicationContext)

        setupUI()
        restoreState()
        observeDebugLog()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupUI() {
        // Master listening toggle
        binding.switchListening.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (hasRequiredPermissions()) {
                    startListenerService()
                } else {
                    requestRequiredPermissions()
                }
            } else {
                stopListenerService()
            }
            prefs.edit().putBoolean("listening_enabled", isChecked).apply()
        }

        // API key save button
        binding.btnSaveApiKey.setOnClickListener {
            val apiKey = binding.editApiKey.text.toString().trim()
            if (apiKey.isNotEmpty()) {
                prefs.edit().putString("api_key", apiKey).apply()
                Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show()
                binding.editApiKey.setText("")
                binding.textApiKeyStatus.text = "API key: configured"
            }
        }

        // Clear API key
        binding.btnClearApiKey.setOnClickListener {
            prefs.edit().remove("api_key").apply()
            binding.textApiKeyStatus.text = "API key: not set"
            Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
        }

        // Overlay permission button
        binding.btnOverlayPermission.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        // View lists button
        binding.btnViewLists.setOnClickListener {
            startActivity(Intent(this, ListActivity::class.java))
        }

        // === MANUAL TEST INPUT ===
        binding.btnTestInput.setOnClickListener {
            val testText = binding.editTestInput.text.toString().trim()
            if (testText.isEmpty()) {
                Toast.makeText(this, "Type something to test", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            DebugLog.log("TEST", "Manual input: \"$testText\"")
            binding.editTestInput.setText("")

            lifecycleScope.launch {
                try {
                    // Run the full pipeline on the typed text
                    DebugLog.log("TEST", "Classifying...")
                    val intent = testClassifier.classify(testText)

                    if (intent != null) {
                        DebugLog.log("TEST", "Intent: ${intent.type} → ${intent.extractedData}")
                        DebugLog.log("TEST", "Dispatching...")
                        val response = testDispatcher.dispatch(intent)

                        if (response != null) {
                            DebugLog.log("TEST", "Result: ${response.title} — ${response.message}")

                            // Show overlay if permission granted
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                val overlayIntent = Intent(this@MainActivity, OverlayService::class.java).apply {
                                    putExtra(OverlayService.EXTRA_TITLE, response.title)
                                    putExtra(OverlayService.EXTRA_MESSAGE, response.message)
                                    putExtra(OverlayService.EXTRA_TYPE, response.type.name)
                                    putExtra(OverlayService.EXTRA_ICON, response.iconResId)
                                }
                                startService(overlayIntent)
                            } else {
                                DebugLog.log("TEST", "No overlay permission — result shown in log only")
                            }
                        } else {
                            DebugLog.log("TEST", "Dispatcher returned null")
                        }
                    } else {
                        DebugLog.log("TEST", "No actionable intent detected for this input")
                    }
                } catch (e: Exception) {
                    DebugLog.log("TEST", "ERROR: ${e.message}")
                }
            }
        }

        // Clear debug log
        binding.btnClearLog.setOnClickListener {
            DebugLog.clear()
        }

        // Feature toggles
        binding.switchQuestions.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_questions", isChecked).apply()
        }
        binding.switchLists.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_lists", isChecked).apply()
        }
        binding.switchReminders.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_reminders", isChecked).apply()
        }
        binding.switchCalendar.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_calendar", isChecked).apply()
        }
        binding.switchTexts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_texts", isChecked).apply()
        }
        binding.switchWebLookup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("feature_web", isChecked).apply()
        }
        binding.switchAutoAnswer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(
                com.silicongames.aura.ai.ActionDispatcher.PREF_AUTO_ANSWER,
                isChecked
            ).apply()
            DebugLog.log("Main", "Auto-answer set to $isChecked")
        }
    }

    private fun restoreState() {
        binding.switchListening.isChecked = prefs.getBoolean("listening_enabled", false)

        val hasApiKey = !prefs.getString("api_key", null).isNullOrBlank()
        binding.textApiKeyStatus.text = if (hasApiKey) "API key: configured" else "API key: not set"

        binding.switchQuestions.isChecked = prefs.getBoolean("feature_questions", true)
        binding.switchLists.isChecked = prefs.getBoolean("feature_lists", true)
        binding.switchReminders.isChecked = prefs.getBoolean("feature_reminders", true)
        binding.switchCalendar.isChecked = prefs.getBoolean("feature_calendar", true)
        binding.switchTexts.isChecked = prefs.getBoolean("feature_texts", true)
        binding.switchWebLookup.isChecked = prefs.getBoolean("feature_web", true)
        binding.switchAutoAnswer.isChecked = prefs.getBoolean(
            com.silicongames.aura.ai.ActionDispatcher.PREF_AUTO_ANSWER,
            false  // default OFF — user has to opt in
        )
    }

    /**
     * Observe the global DebugLog and display entries in the debug panel.
     */
    private fun observeDebugLog() {
        lifecycleScope.launch {
            DebugLog.entries.collectLatest { entries ->
                binding.textDebugLog.text = entries.joinToString("\n")
            }
        }
    }

    private fun updatePermissionStatus() {
        val micOk = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)

        binding.textMicStatus.text = if (micOk) "Microphone: granted" else "Microphone: not granted"
        binding.textOverlayStatus.text = if (overlayOk) "Overlay: granted" else "Overlay: not granted"
        binding.btnOverlayPermission.isEnabled = !overlayOk

        DebugLog.log("Permissions", "mic=$micOk overlay=$overlayOk")
    }

    private fun hasRequiredPermissions(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val overlayGranted = Settings.canDrawOverlays(this)
        return micGranted && overlayGranted
    }

    private fun requestRequiredPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission first", Toast.LENGTH_LONG).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ))
            binding.switchListening.isChecked = false
            return
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions.launch(permissionsNeeded.toTypedArray())
        } else {
            startListenerService()
        }
    }

    private fun startListenerService() {
        DebugLog.log("Main", "Starting listener service...")
        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_START
        }
        startForegroundService(intent)
    }

    private fun stopListenerService() {
        DebugLog.log("Main", "Stopping listener service...")
        val intent = Intent(this, ListenerService::class.java).apply {
            action = ListenerService.ACTION_STOP
        }
        startService(intent)
    }
}
