package com.silicongames.aura.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.silicongames.aura.DebugLog
import com.silicongames.aura.R

/**
 * Service that manages the floating overlay window.
 *
 * Shows a small card that slides in from the top of the screen
 * with the Aura response, then auto-dismisses after a few seconds.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "Overlay"
        const val EXTRA_TITLE = "overlay_title"
        const val EXTRA_MESSAGE = "overlay_message"
        const val EXTRA_TYPE = "overlay_type"
        const val EXTRA_ICON = "overlay_icon"
        private const val AUTO_DISMISS_MS = 8000L
        private const val ANIMATION_DURATION_MS = 300L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: return START_NOT_STICKY
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_NOT_STICKY
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: "ANSWER"
        val iconRes = intent.getIntExtra(EXTRA_ICON, R.drawable.ic_aura_ear)

        // Check overlay permission BEFORE trying to show
        if (!Settings.canDrawOverlays(this)) {
            DebugLog.log(TAG, "NO OVERLAY PERMISSION — cannot show result. Grant 'Display over other apps' in settings.")
            stopSelf()
            return START_NOT_STICKY
        }

        // WindowManager calls MUST happen on the main thread. We're already on
        // main inside Service callbacks, but post anyway to be defensive against
        // any caller that started us from a worker thread.
        handler.post {
            try {
                showOverlay(title, message, typeName, iconRes)
            } catch (e: Exception) {
                DebugLog.log(TAG, "showOverlay crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return START_NOT_STICKY
    }

    private fun showOverlay(title: String, message: String, typeName: String, iconRes: Int) {
        try {
            // Remove previous overlay if still showing
            removeOverlay()

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // overlay_card.xml uses MaterialCardView which needs the Material
            // theme attributes resolvable on the inflater's context. A bare
            // applicationContext / Service context doesn't reliably carry
            // those, which throws InflateException at runtime. Wrap explicitly
            // so the Material attributes always resolve.
            val themedContext = ContextThemeWrapper(this, R.style.Theme_Aura)
            val layoutInflater = LayoutInflater.from(themedContext)
            overlayView = try {
                layoutInflater.inflate(R.layout.overlay_card, null)
            } catch (e: android.view.InflateException) {
                Log.e(TAG, "Material inflate failed, retrying with Theme_AppCompat", e)
                DebugLog.log(TAG, "Material inflate failed: ${e.message}; falling back")
                val fallback = ContextThemeWrapper(
                    this,
                    androidx.appcompat.R.style.Theme_AppCompat_DayNight
                )
                LayoutInflater.from(fallback).inflate(R.layout.overlay_card, null)
            }

            // Configure the card content
            overlayView?.apply {
                findViewById<TextView>(R.id.overlay_title)?.text = title
                findViewById<TextView>(R.id.overlay_message)?.text = message

                // Set icon safely — catch resource not found
                try {
                    findViewById<ImageView>(R.id.overlay_icon)?.setImageResource(iconRes)
                } catch (e: Exception) {
                    DebugLog.log(TAG, "Icon resource error, using default")
                    findViewById<ImageView>(R.id.overlay_icon)?.setImageResource(R.drawable.ic_aura_ear)
                }

                // Set accent color based on response type
                val accentColor = getAccentColor(typeName)
                findViewById<View>(R.id.overlay_accent_bar)?.setBackgroundColor(accentColor)

                // Dismiss on tap
                setOnClickListener { dismissOverlay() }

                // Swipe to dismiss
                setOnTouchListener(SwipeDismissListener())
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100 // offset from top of screen
            }

            windowManager?.addView(overlayView, params)
            DebugLog.log(TAG, "Overlay shown: $title")
            animateIn()
            scheduleAutoDismiss()

        } catch (e: WindowManager.BadTokenException) {
            DebugLog.log(TAG, "BadTokenException — overlay permission may have been revoked")
        } catch (e: Exception) {
            DebugLog.log(TAG, "Failed to show overlay: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun animateIn() {
        overlayView?.apply {
            translationY = -300f
            alpha = 0f
            animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun dismissOverlay() {
        overlayView?.animate()
            ?.translationY(-300f)
            ?.alpha(0f)
            ?.setDuration(ANIMATION_DURATION_MS)
            ?.setInterpolator(AccelerateInterpolator())
            ?.withEndAction { removeOverlay() }
            ?.start()
    }

    private fun removeOverlay() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null

        overlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) {
                    windowManager?.removeView(view)
                }
            } catch (_: Exception) { }
        }
        overlayView = null
    }

    private fun scheduleAutoDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = Runnable { dismissOverlay() }
        handler.postDelayed(dismissRunnable!!, AUTO_DISMISS_MS)
    }

    private fun getAccentColor(typeName: String): Int {
        return when (typeName) {
            "ANSWER" -> 0xFF6C63FF.toInt()
            "LIST_CONFIRM" -> 0xFF4CAF50.toInt()
            "REMINDER_SET" -> 0xFFFF9800.toInt()
            "CALENDAR_ADDED" -> 0xFF2196F3.toInt()
            "TEXT_SENT" -> 0xFF00BCD4.toInt()
            "WEB_RESULT" -> 0xFF9C27B0.toInt()
            "ERROR" -> 0xFFF44336.toInt()
            else -> 0xFF6C63FF.toInt()
        }
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private inner class SwipeDismissListener : View.OnTouchListener {
        private var startY = 0f
        private var startTranslationY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startTranslationY = v.translationY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    if (deltaY < 0) {
                        v.translationY = startTranslationY + deltaY
                        v.alpha = 1f + (deltaY / 500f)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaY = event.rawY - startY
                    if (deltaY < -100) {
                        dismissOverlay()
                    } else {
                        v.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                    if (Math.abs(deltaY) < 10) {
                        v.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }
}
