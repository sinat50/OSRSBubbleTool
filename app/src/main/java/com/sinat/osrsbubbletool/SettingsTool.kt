package com.sinat.osrsbubbletool

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit

// The app's settings, saved on the phone. The bubble watches them (BubbleService.settingsChanged), so a change
// made on the app's screen reaches the bubble and its tools straight away.
object AppSettings {
    private const val PREFS = "settings"
    const val DARK_MODE = "dark_mode"          // OFF, ON or MATCH_PHONE
    const val TEXT_SIZE = "web_text_size"      // web page text size, in percent of normal
    const val TOOL_TEXT_SIZE = "tool_text_size" // the tools' own text size, in percent of normal
    const val BUBBLE_SIZE = "bubble_size"      // the bubble's size in dp
    const val BUBBLE_FADE = "bubble_fade"      // the bubble fades while you're not using it
    const val LONG_PRESS = "long_press_ms"     // how long to hold the bubble for the menu
    const val VIBRATE = "vibrate"              // a little buzz when the menu opens
    const val WINDOW_WIDTH = "window_width"    // tool windows' width in landscape, in hundredths of an inch
    const val HIDDEN_TOOLS = "hidden_tools"    // tools left out of the menu (their names)
    const val CHECK_UPDATES = "check_updates"  // look for a newer version when the app opens
    const val WINDOWS_RESET = "windows_reset"  // changed when "Reset windows" is tapped, so the bubble hears of it
    const val OFF = 0
    const val ON = 1
    const val MATCH_PHONE = 2
    const val TEXT_NORMAL = 100
    const val TEXT_MIN = 70
    const val TEXT_MAX = 200
    const val TEXT_STEP = 10
    const val TOOL_TEXT_MIN = 80
    const val TOOL_TEXT_MAX = 150

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun darkMode(context: Context) = prefs(context).getInt(DARK_MODE, OFF)

    // Whether the app should be dark right now (with "Match phone", that's whatever the phone is set to)
    fun isDark(context: Context) = when (darkMode(context)) {
        ON -> true
        MATCH_PHONE -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    fun textSize(context: Context) = prefs(context).getInt(TEXT_SIZE, TEXT_NORMAL).coerceIn(TEXT_MIN, TEXT_MAX)
    fun toolTextSize(context: Context) = prefs(context).getInt(TOOL_TEXT_SIZE, TEXT_NORMAL).coerceIn(TOOL_TEXT_MIN, TOOL_TEXT_MAX)
    fun bubbleSizeDp(context: Context) = prefs(context).getInt(BUBBLE_SIZE, 36)
    fun bubbleFades(context: Context) = prefs(context).getBoolean(BUBBLE_FADE, false)
    fun longPressMs(context: Context) = prefs(context).getInt(LONG_PRESS, 600).toLong()
    fun vibrates(context: Context) = prefs(context).getBoolean(VIBRATE, true)
    fun windowWidthInches(context: Context) = prefs(context).getInt(WINDOW_WIDTH, 200) / 100f
    fun hiddenTools(context: Context): Set<String> = prefs(context).getStringSet(HIDDEN_TOOLS, emptySet()) ?: emptySet()
    fun checksUpdates(context: Context) = prefs(context).getBoolean(CHECK_UPDATES, true)
}

// The Settings page: the bubble's Settings window, and the Settings screen in the app. Both show the same
// settings. onRedraw: the page needs building again (dark mode or the tools' text size changed; in the bubble,
// the bubble rebuilds its windows on its own).
class SettingsTool(private val context: Context, private val onRedraw: () -> Unit = {}) {

    companion object {
        private var savedScroll = 0   // where the bubble's Settings window was scrolled to (kept when it's rebuilt)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private val prefs = AppSettings.prefs(context)

    // For the bubble's window: the settings on a scrolling page
    fun buildView(): View = ScrollView(context).apply {
        setBackgroundColor(Palette.PARCHMENT)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            addView(label("Settings", 16f, bold = true))
            addSettings(this)
        })
        val saved = savedScroll   // (not scrollY: in here that would be the page's own)
        post { scrollTo(0, saved) }
        setOnScrollChangeListener { _, _, y, _, _ -> savedScroll = y }
    }

    // The settings themselves, added to a column (the app's screen adds its own title and Back button)
    fun addSettings(column: LinearLayout) = column.apply {
        // ---- Look ----
        section("Look")
        heading("Dark mode")
        addView(choices(listOf(AppSettings.OFF to "Off", AppSettings.ON to "On", AppSettings.MATCH_PHONE to "Match phone"),
            AppSettings.darkMode(context), wide = AppSettings.MATCH_PHONE) {
            prefs.edit { putInt(AppSettings.DARK_MODE, it) }
            onRedraw()
        }, full(4))
        note("Websites in the tools (the Wiki, GE Prices...) turn dark too, if they have a dark look of their own.")

        heading("Tool text size")
        addView(stepper({ AppSettings.toolTextSize(context) }, AppSettings.TOOL_TEXT_MIN, AppSettings.TOOL_TEXT_MAX) {
            prefs.edit { putInt(AppSettings.TOOL_TEXT_SIZE, it) }
            onRedraw()
        }, full(4))
        note("The writing in the app's own tools, like the Quest Helper and Notepad.")

        heading("Web page text size")
        addView(stepper({ AppSettings.textSize(context) }, AppSettings.TEXT_MIN, AppSettings.TEXT_MAX) {
            prefs.edit { putInt(AppSettings.TEXT_SIZE, it) }
        }, full(4))
        note("For the Wiki, DPS Calculator, XP Calculator, GE Prices and Shooting Star Tracker. You can also pinch a page to zoom it.")

        // ---- Bubble ----
        section("Bubble")
        heading("Size")
        addView(choices(listOf(30 to "Small", 36 to "Normal", 46 to "Large"), AppSettings.bubbleSizeDp(context)) {
            prefs.edit { putInt(AppSettings.BUBBLE_SIZE, it) }
        }, full(4))
        heading("Fade when not in use")
        addView(onOff(AppSettings.bubbleFades(context)) { prefs.edit { putBoolean(AppSettings.BUBBLE_FADE, it) } }, full(4))
        note("The bubble turns see-through a few seconds after you last touched it, so it hides less of the game.")
        heading("Hold time for the menu")
        addView(choices(listOf(400 to "Short", 600 to "Normal", 900 to "Long"), AppSettings.longPressMs(context).toInt()) {
            prefs.edit { putInt(AppSettings.LONG_PRESS, it) }
        }, full(4))
        heading("Vibrate when the menu opens")
        addView(onOff(AppSettings.vibrates(context)) { prefs.edit { putBoolean(AppSettings.VIBRATE, it) } }, full(4))

        // ---- Windows ----
        section("Windows")
        heading("Width in landscape")
        addView(choices(listOf(160 to "Narrow", 200 to "Normal", 250 to "Wide"), (AppSettings.windowWidthInches(context) * 100).toInt()) {
            prefs.edit { putInt(AppSettings.WINDOW_WIDTH, it) }
        }, full(4))
        note("For windows you haven't resized yourself.")
        val reset = button("Reset all window sizes and places", Palette.BUTTON_BROWN) {}
        reset.setOnClickListener {
            context.getSharedPreferences("window_sizes", Context.MODE_PRIVATE).edit { clear() }
            prefs.edit { putLong(AppSettings.WINDOWS_RESET, System.currentTimeMillis()) }
            reset.text = "✓ Windows reset"
        }
        addView(reset, full(10))

        // ---- Menu ----
        section("Tools in the menu")
        note("Untick the tools you don't use to keep the bubble's menu short.")
        addView(toolTicks(), full(4))

        // ---- Updates ----
        section("Updates")
        heading("Check for a new version when the app opens")
        addView(onOff(AppSettings.checksUpdates(context)) { prefs.edit { putBoolean(AppSettings.CHECK_UPDATES, it) } }, full(4))
        note("Asks GitHub for the latest version number. Nothing about you is sent.")
    }

    // The tools, two to a row: green with a tick when they're in the menu, faded when they're not
    private fun toolTicks(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val tools = BubbleService.Tool.entries.filter { it.inMenu }
        for (pair in tools.chunked(2)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((i, tool) in pair.withIndex()) {
                    val b = button("", Palette.GO_GREEN) {}
                    fun look() {
                        val shown = tool.name !in AppSettings.hiddenTools(context)
                        b.text = (if (shown) "✓ " else "") + tool.label
                        (b.background as GradientDrawable).setColor(if (shown) Palette.GO_GREEN else Palette.LIGHT_BUTTON)
                    }
                    b.setOnClickListener {
                        val hidden = AppSettings.hiddenTools(context).toMutableSet()
                        if (!hidden.remove(tool.name)) hidden.add(tool.name)
                        prefs.edit { putStringSet(AppSettings.HIDDEN_TOOLS, hidden) }
                        look()
                    }
                    b.textSize = 11f
                    look()
                    addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(4) })
                }
                if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(4) })
            }, full(4))
        }
    }

    // A row of buttons to pick one of; the picked one is green
    private fun choices(options: List<Pair<Int, String>>, current: Int, wide: Int? = null, onPick: (Int) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val buttons = mutableListOf<TextView>()
            for ((i, option) in options.withIndex()) {
                val (value, name) = option
                val b = button(name, if (value == current) Palette.GO_GREEN else Palette.LIGHT_BUTTON) {}
                b.setOnClickListener {
                    for ((j, other) in buttons.withIndex())
                        (other.background as GradientDrawable).setColor(if (j == i) Palette.GO_GREEN else Palette.LIGHT_BUTTON)
                    onPick(value)
                }
                buttons.add(b)
                addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (value == wide) 1.6f else 1f)
                    .apply { if (i > 0) leftMargin = dp(4) })
            }
        }

    private fun onOff(on: Boolean, onChange: (Boolean) -> Unit) =
        choices(listOf(0 to "Off", 1 to "On"), if (on) 1 else 0) { onChange(it == 1) }

    // − 100% +
    private fun stepper(get: () -> Int, min: Int, max: Int, onChange: (Int) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val sizeText = label("", 15f, bold = true).apply { gravity = Gravity.CENTER }
        fun showSize() {
            val size = get()
            sizeText.text = if (size == AppSettings.TEXT_NORMAL) "$size% (normal)" else "$size%"
        }
        fun change(by: Int) {
            val size = (get() + by).coerceIn(min, max)
            if (size == get()) return
            onChange(size)
            showSize()
        }
        showSize()
        addView(button("−", Palette.BUTTON_BROWN) { change(-AppSettings.TEXT_STEP) }.apply { textSize = 18f },
            LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(sizeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(button("+", Palette.BUTTON_BROWN) { change(AppSettings.TEXT_STEP) }.apply { textSize = 18f },
            LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.section(title: String) {
        addView(label(title, 15f, bold = true).apply { setTextColor(Palette.GO_GREEN) }, full(16))
        addView(View(context).apply { setBackgroundColor(Palette.ROW_BROWN) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(2) })
    }

    private fun LinearLayout.heading(text: String) = addView(label(text, 13f, bold = true), full(10))

    private fun LinearLayout.note(text: String) = addView(label(text, 11f).apply { setTextColor(Palette.FADED) }, full(4))

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Palette.DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
