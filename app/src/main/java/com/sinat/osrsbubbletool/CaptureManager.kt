package com.sinat.osrsbubbletool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.content.IntentCompat

// Screen capture shared by every tool. Android asks for permission once, and capture
// then stays on until the bubble is closed (or you stop it from the status bar).
class CaptureManager(
    private val context: Context,
    private val onActiveChanged: (Boolean) -> Unit   // lets the bubble update its notification
) {
    companion object {
        private const val RETRY_ASK_MS = 5_000L  // ask again if an earlier request got no answer
    }

    private val capturer = ScreenCapturer(context) { handleStopped() }
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private val stopListeners = mutableListOf<() -> Unit>()
    private var lastAskedAt = 0L
    private var shuttingDown = false

    val isActive: Boolean get() = capturer.isActive

    // Why the last request failed, to show to you
    var lastError: String? = null
        private set

    // Makes sure capture is on, asking Android for permission if needed.
    // onResult(true) means screenshots can be taken now.
    fun request(onResult: (Boolean) -> Unit) {
        if (capturer.isActive) {
            onResult(true)
            return
        }
        waiting.add(onResult)
        val now = SystemClock.uptimeMillis()
        if (waiting.size == 1 || now - lastAskedAt > RETRY_ASK_MS) {
            lastAskedAt = now
            context.startActivity(
                Intent(context, CapturePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // Tools register here to hear when capture turns off
    fun addStopListener(listener: () -> Unit) {
        stopListeners.add(listener)
    }

    // Called by the bubble when Android answers the permission question
    fun onPermissionResult(intent: Intent) {
        val resultCode = intent.getIntExtra(BubbleService.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, BubbleService.EXTRA_RESULT_DATA, Intent::class.java)
        var ok = false
        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                onActiveChanged(true)   // Android requires this before capture starts
                capturer.start(resultCode, data)
                ok = true
                lastError = null
            } catch (e: Exception) {
                onActiveChanged(false)
                lastError = e.message ?: "unknown error"
            }
        } else {
            lastError = "permission wasn't given"
        }
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(ok) }
    }

    fun matchScreenSize() = capturer.matchScreenSize()

    // The most recent screen image, or null if nothing new is ready
    fun grab(): Bitmap? = capturer.grab()

    // Turns capture off for good (when the bubble closes)
    fun shutdown() {
        shuttingDown = true
        waiting.clear()
        capturer.stop()
    }

    private fun handleStopped() {
        if (shuttingDown) return
        onActiveChanged(false)
        stopListeners.forEach { it() }
    }
}
