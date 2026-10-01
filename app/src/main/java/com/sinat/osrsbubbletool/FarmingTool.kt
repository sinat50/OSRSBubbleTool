package com.sinat.osrsbubbletool

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import java.util.Calendar
import java.util.Date

// Farming Timers: tap "Planted" after a run and get a notification when it's ready.
class FarmingTool(private val context: Context) {

    companion object {
        private val PARCHMENT = "#F2E3C0".toColorInt()
        private val DARK_BROWN = "#3E2C12".toColorInt()
        private val BUTTON_BROWN = "#8B6B3E".toColorInt()
        private val ROW_BROWN = "#E3CFA2".toColorInt()
        private val GO_GREEN = "#3E7A2E".toColorInt()
        private val READY_GREEN = "#2E8B2E".toColorInt()
        private val WARN_ORANGE = "#C0600A".toColorInt()
        private val STOP_RED = "#A04030".toColorInt()
        private const val REFRESH_MS = 20_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private var choosingCropFor = -1     // timer whose crop list is open
    private var addingTimer = false      // "+ Add timer" list open
    private var showing = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!showing) return
            rebuild()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        val scroll = ScrollView(context).apply {
            setBackgroundColor(PARCHMENT)
            addView(list)
        }
        // refresh the countdowns only while the window is on screen
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                showing = true
                handler.removeCallbacks(ticker)
                ticker.run()
            }
            override fun onViewDetachedFromWindow(v: View) {
                showing = false
                handler.removeCallbacks(ticker)
            }
        })
        rebuild()
        return scroll
    }

    fun onWindowClosed() {
        choosingCropFor = -1
        addingTimer = false
    }

    fun destroy() {
        showing = false
        handler.removeCallbacks(ticker)
    }

    // ---------------- Screen ----------------

    private fun rebuild() {
        list.removeAllViews()

        // Permission problems, shown only when there is one
        if (!FarmingTimers.notificationsAllowed(context)) {
            add(warning("Notifications are off for this app. Tap to turn them on.") {
                openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }, 0)
        }
        if (!FarmingTimers.canUseExactAlarms(context)) {   // (always allowed before Android 12, so this only shows on 12+)
            add(warning("Alerts may be a few minutes late. Tap and allow \"Alarms & reminders\" for on-time alerts.") {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()))
            }, 0)
        }

        val now = System.currentTimeMillis()
        val timers = FarmingTimers.load(context)
        for (t in timers) add(timerRow(t, now), 6)
        if (timers.isEmpty()) add(label("No timers yet. Tap + Add timer.", 13f), 6)

        // Adding timers
        add(button(if (addingTimer) "Cancel" else "+ Add timer") {
            addingTimer = !addingTimer
            rebuild()
        }, 10)
        if (addingTimer) {
            for (kind in FarmingTimers.KINDS) {
                add(chip(kind.label) {
                    FarmingTimers.add(context, kind.key)
                    addingTimer = false
                    rebuild()
                }, 3)
            }
        }

        add(offsetRow(), 14)
    }

    private fun timerRow(t: FarmingTimers.Timer, now: Long): View {
        val kind = t.kindInfo()
        val crop = t.cropInfo()
        val running = t.end > 0
        val ready = running && t.end <= now

        val name = label(kind.label, 14f, bold = true)
        val status = label(
            when {
                ready -> "Ready!"
                running -> "${remaining(t.end - now)}\n${clock(t.end)}"
                else -> "Not running"
            }, 12f, bold = ready
        ).apply {
            gravity = Gravity.END
            if (ready) setTextColor(READY_GREEN)
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(status)
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            fun addButton(v: View, weight: Float) = addView(v,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { rightMargin = dp(4) })
            addButton(button(if (running) "✓ Replanted" else "✓ Planted", GO_GREEN) {
                FarmingTimers.start(context, t.id)
                choosingCropFor = -1
                rebuild()
            }, 1.4f)
            if (kind.crops.size > 1) {
                addButton(button("${crop.name} ▾") {
                    choosingCropFor = if (choosingCropFor == t.id) -1 else t.id
                    rebuild()
                }, 1.2f)
            }
            addButton(button(if (running) "Stop" else "Remove", STOP_RED) {
                if (running) FarmingTimers.stop(context, t.id) else FarmingTimers.remove(context, t.id)
                rebuild()
            }, 0.9f)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(4), dp(6))
            background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(6).toFloat() }
            addView(top)
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })

            if (choosingCropFor == t.id) {
                kind.crops.forEachIndexed { i, c ->
                    val chip = chip((if (i == t.crop) "● " else "") + c.name + "   " + duration(kind, c)) {
                        FarmingTimers.setCrop(context, t.id, i)
                        choosingCropFor = -1
                        rebuild()
                    }
                    addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3); rightMargin = dp(4) })
                }
            }
        }
    }

    private fun offsetRow(): View {
        val offset = FarmingTimers.tickOffset(context)
        val value = label("Tick offset: $offset min", 12f)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallButton("−") { FarmingTimers.setTickOffset(context, (offset - 1).coerceAtLeast(0)); rebuild() })
            addView(smallButton("+") { FarmingTimers.setTickOffset(context, (offset + 1).coerceAtMost(59)); rebuild() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(label("Leave at 0 unless your timers are always early. Changes apply to timers you start after.", 10f))
        }
    }

    // ---------------- Helpers ----------------

    private fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun remaining(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        val d = minutes / (60 * 24)
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    // "3:45 PM", or "Sun 3:45 PM" if it's not today
    private fun clock(time: Long): String {
        val date = Date(time)
        val timeText = android.text.format.DateFormat.getTimeFormat(context).format(date)
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) timeText
               else android.text.format.DateFormat.format("EEE", date).toString() + " " + timeText
    }

    private fun duration(kind: FarmingTimers.Kind, crop: FarmingTimers.Crop): String {
        val minutes = if (kind.fixedMinutes > 0) kind.fixedMinutes else crop.stageMinutes * (crop.stages - 1)
        return remaining(minutes * 60_000L)
    }

    private fun add(v: View, topMargin: Int) {
        list.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = dp(topMargin)
        })
    }

    private fun warning(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { setColor(WARN_ORANGE); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun button(label: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(4), dp(7), dp(4), dp(7))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = button(label, onClick = onClick).apply {
        textSize = 15f
        setPadding(dp(14), dp(2), dp(14), dp(2))
    }

    private fun chip(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(DARK_BROWN)
        setPadding(dp(8), dp(5), dp(8), dp(5))
        background = GradientDrawable().apply {
            setColor(PARCHMENT)
            setStroke(dp(1), BUTTON_BROWN)
            cornerRadius = dp(5).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 13f, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
