package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt

// Light Box Solver, like RuneLite's: press each button once so the app can see what it
// does, then it shows which buttons turn every bulb on. Instructions are drawn right on
// the light box, and this window gets out of the way while you solve.
class LightBoxTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit,
    private val hideWindow: () -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before the first screenshot
        const val TRACK_INTERVAL_MS = 80L  // how often to look at the bulbs while solving
        const val SETTLE_MS = 160L         // a change counts as a press once seen twice, or after this long
        const val GONE_READS = 3           // light box missing this many looks in a row (~0.25 s) = closed
        const val SOLVED_SHOW_MS = 2_500L  // how long "Solved!" stays up
        private val LETTERS = "ABCDEFGH"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val guide = LightBoxGuideOverlay(context, windowManager)
    private val prefs = context.getSharedPreferences("light_box", Context.MODE_PRIVATE)
    private var status: TextView? = null
    private var startButton: TextView? = null
    private var destroyed = false

    // Solving state
    private var layout: LightBoxReader.Layout? = null
    private var tracking = false
    private var misses = 0                       // looks in a row where the light box wasn't there
    private var finished = false                 // solved, showing "Solved!" for a moment
    private var session = 0                      // changes each time solving starts or stops
    private val effects = arrayOfNulls<Int>(8)   // which bulbs each button flips, once known
    private var state = 0                        // bulbs currently on
    private var candidate = -1
    private var candidateSince = 0L
    private var candidateSeen = 0
    private var lastSeen = 0L
    private var solution: List<Int>? = null
    private var expecting = 0                    // button we asked you to press while learning

    init {
        capture.addStopListener {
            if (!destroyed && tracking) stop("Screen capture is off. Tap Solve light box to turn it back on.")
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Window contents ----------------

    fun buildView(): View {
        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor("#8B6B3E".toColorInt())
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }
        startButton = button("Solve light box") { if (tracking) stop("Stopped.") else start() }.apply {
            textSize = 13f
            setPadding(dp(6), dp(7), dp(6), dp(7))
        }
        status = TextView(context).apply {
            text = "Open a light box, then tap Solve. Instructions appear on the light box."
            textSize = 11f
            setTextColor("#3E2C12".toColorInt())
            setPadding(0, dp(6), 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#F2E3C0".toColorInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(startButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---------------- Finding the light box ----------------

    private fun start() {
        if (!capture.isActive) {
            status?.text = "Waiting for screen-capture permission..."
            capture.request { ok ->
                if (ok) start() else status?.text = "Couldn't turn on screen capture: ${capture.lastError}."
            }
            return
        }
        status?.text = "Looking for the light box..."
        val id = ++session   // a second tap, a rotation or Stop meanwhile makes this search out of date
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            if (destroyed) return@postDelayed
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (id != session) return@postDelayed
            if (shot == null) {
                status?.text = "Couldn't capture the screen. Try again."
                return@postDelayed
            }
            Thread {
                val pixels = LightBoxReader.Pixels(shot)
                // quick check where it was last time, otherwise search the whole screen
                val found = savedLayout()?.takeIf { LightBoxReader.read(pixels, it) != null }
                    ?: LightBoxReader.locate(pixels)
                val reading = found?.let { LightBoxReader.read(pixels, it) }
                handler.post {
                    if (destroyed || id != session) return@post
                    if (found == null || reading == null) {
                        status?.text = "Couldn't find the light box. Make sure it's open, not covered by " +
                            "anything, and at least one bulb is lit, then try again."
                    } else {
                        saveLayout(found)
                        begin(found, reading)
                    }
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    private fun begin(found: LightBoxReader.Layout, reading: Int) {
        layout = found
        state = reading
        effects.fill(null)
        candidate = -1
        misses = 0
        finished = false
        lastSeen = SystemClock.uptimeMillis()
        if (state == LightBoxReader.ALL_ON) {
            status?.text = "This light box is already solved!"
            return
        }
        guide.show(found)
        session++
        tracking = true
        startButton?.text = "Stop"
        expecting = 0
        update()
        handler.removeCallbacks(trackRunnable)   // never two tracking loops at once
        handler.postDelayed(trackRunnable, TRACK_INTERVAL_MS)
        hideWindow()   // out of the way; tap the bubble to bring it back
    }

    // ---------------- Following your presses ----------------

    private val trackRunnable = object : Runnable {
        override fun run() {
            if (!tracking) return
            trackOnce()
            if (tracking) handler.postDelayed(this, TRACK_INTERVAL_MS)
        }
    }

    private fun trackOnce() {
        val l = layout ?: return
        // You left the game (when only the game is shared): take the outlines off the screen and stop,
        // rather than leaving them over other apps and watching a screen that can't be seen
        if (capture.gameHidden) {
            stop("You left the game, so solving stopped. Tap Solve light box to start again.")
            return
        }
        val now = SystemClock.uptimeMillis()
        // Reads only the 750 pixels it needs straight from the capture: -1 = can't see it,
        // null = the screen hasn't changed since last time
        val reading = capture.sample { LightBoxReader.read(it, l) ?: -1 }
        if (reading == -1) {
            // closed (or covered): clear everything off the screen straight away
            if (++misses >= GONE_READS) {
                stop(if (finished) "Light box solved!" else "The light box was closed. Tap Solve light box to start again.")
            }
            return
        }
        misses = 0
        lastSeen = now
        if (finished) return   // just watching for it to close
        if (reading != null) {
            if (reading != candidate) {
                candidate = reading
                candidateSince = now
                candidateSeen = 1
            } else {
                candidateSeen++
            }
        }
        if (candidate == -1 || candidate == state) return
        // the bulbs change instantly, so two matching looks in a row is enough
        if (candidateSeen < 2 && now - candidateSince < SETTLE_MS) return
        onPressed(state xor candidate, candidate)
    }

    // The bulbs changed: work out which button that was
    private fun onPressed(flipped: Int, newState: Int) {
        state = newState
        val known = effects.indexOfFirst { it == flipped }
        var note = ""
        if (solution == null && known == -1 && effects[expecting] == null) {
            effects[expecting] = flipped   // learned what this button does
        } else if (solution == null && known != -1 && known != expecting) {
            note = "That was ${LETTERS[known]}. "
        }
        update(note)
    }

    // Works out what to show next
    private fun update(note: String = "") {
        if (state == LightBoxReader.ALL_ON) {
            guide.set("Solved!", LightBoxGuideOverlay.GREEN, emptyList(), LightBoxGuideOverlay.GREEN)
            finished = true   // keep watching only so it disappears the moment the light box closes
            val thisSession = session
            handler.postDelayed({ if (session == thisSession) stop("Light box solved!") }, SOLVED_SHOW_MS)
            status?.text = "Light box solved!"
            return
        }
        val plan = LightBoxReader.solve(state, effects)
        solution = plan
        if (plan != null) {
            val left = plan.size
            guide.set("Press the green button" + (if (left == 1) "" else "s ($left)"),
                Color.WHITE, plan, LightBoxGuideOverlay.GREEN)
            status?.text = "Press the buttons outlined in green: " + plan.joinToString(", ") { "${LETTERS[it]}" } + "."
        } else {
            val next = (0 until 8).firstOrNull { effects[it] == null }
            if (next == null) {
                // every button is known but nothing works: something was misread
                stop("Couldn't work out a solution. Tap Solve light box to try again.")
                return
            }
            expecting = next
            guide.set("${note}Press ${LETTERS[next]}", LightBoxGuideOverlay.YELLOW, listOf(next), LightBoxGuideOverlay.YELLOW)
            status?.text = "${note}Press ${LETTERS[next]} in the game so the app can see what it does."
        }
    }

    private fun stop(message: String) {
        finished = false
        misses = 0
        session++
        tracking = false
        handler.removeCallbacks(trackRunnable)
        guide.hide()
        layout = null
        solution = null
        startButton?.text = "Solve light box"
        status?.text = message
    }

    // ---------------- Remembering where it was ----------------

    private fun savedLayout(): LightBoxReader.Layout? {
        if (!prefs.contains("x0")) return null
        return LightBoxReader.Layout(prefs.getFloat("x0", 0f), prefs.getFloat("y0", 0f), prefs.getFloat("spacing", 0f))
    }

    private fun saveLayout(l: LightBoxReader.Layout) {
        prefs.edit { putFloat("x0", l.x0).putFloat("y0", l.y0).putFloat("spacing", l.spacing) }
    }

    // ---------------- Called by BubbleService ----------------

    // Closing the window yourself keeps solving going; the instructions stay on the light box.
    // Tap the bubble to bring the window back, and Stop to end it.
    fun onWindowClosed() {}

    fun onRotated() {
        session++   // a search still running was for the old layout
        if (tracking || guide.isShowing) stop("Screen rotated. Tap Solve light box to start again.")
    }

    fun destroy() {
        destroyed = true
        tracking = false
        session++
        handler.removeCallbacksAndMessages(null)
        guide.hide()
    }
}
