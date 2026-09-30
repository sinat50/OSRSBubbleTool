package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Hunters' Rumours: pick your tier (and guild hunter), pick the rumour you're on, and see where the
// creature is, how to get there and what to bring. Everything is built in, so it works offline.
class HunterRumourTool(private val context: Context, private val openWikiSync: () -> Unit = {}) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val LIGHT_BUTTON = Color.parseColor("#B89A63")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val PAPER = Color.parseColor("#FFF8E6")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private val FADED = Color.parseColor("#8C7B5E")
        private val WARN = Color.parseColor("#9C4A10")
    }

    private val prefs = context.getSharedPreferences("hunter_rumours", Context.MODE_PRIVATE)
    private val sync = WikiSync(context)   // only what's already saved: your Hunter level, if you've set up WikiSync
    private lateinit var holder: FrameLayout

    // What you picked, remembered for next time
    private var tier: String
        get() = prefs.getString("tier", null)?.takeIf { it in HunterRumours.TIERS } ?: "Novice"
        set(v) { prefs.edit().putString("tier", v).apply() }
    private var hunter: HunterRumours.Hunter
        get() = HunterRumours.huntersFor(tier).let { list ->
            list.firstOrNull { it.name == prefs.getString("hunter_$tier", null) } ?: list.first()
        }
        set(v) { prefs.edit().putString("hunter_$tier", v.name).apply() }
    private var current: HunterRumours.Rumour?   // the rumour you're on
        get() = prefs.getString("current", null)?.let { HunterRumours.byName(it) }
        set(v) { prefs.edit().putString("current", v?.name).apply() }

    private var showing: HunterRumours.Rumour? = null   // null = the list

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        showing = null
        show()
        // back from the WikiSync tool: redraw so the tag and faded rumours are up to date
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (syncState() != shownSyncState) show() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return holder
    }

    // The window's ◀ button: from a rumour back to the list
    fun goBack() {
        if (showing != null) { showing = null; show() }
    }

    private var shownSyncState: String? = null
    private fun syncState() = sync.username + "|" + (sync.cached?.fetchedAt ?: 0)

    private fun show() {
        shownSyncState = syncState()
        holder.removeAllViews()
        val r = showing
        holder.addView(if (r == null) list() else detail(r))
    }

    private fun hunterLevel(): Int? = sync.cached?.level("Hunter")

    // ---------------- The list ----------------

    private fun list(): View = scrolling {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Hunters' Rumours", 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(WikiSyncBadge.view(context, sync) { openWikiSync() })
        }, full())

        // Your current rumour, one tap away
        current?.let { cur ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { setColor(GO_GREEN); cornerRadius = dp(6).toFloat() }
                addView(label("Your rumour: ${cur.name}", 13f, bold = true).apply { setTextColor(Color.WHITE) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label("Open ▸", 12f, bold = true).apply { setTextColor(Color.WHITE) })
                setOnClickListener { showing = cur; show() }
            }, full(6))
        }

        // Tier, then which hunter (Adept and Expert each have two, with different rumours)
        addView(label("Your tier", 11f, bold = true).apply { setTextColor(FADED) }, full(10))
        addView(choiceRow(HunterRumours.TIERS, tier) { tier = it; show() }, full(3))
        val hunters = HunterRumours.huntersFor(tier)
        if (hunters.size > 1) {
            addView(label("Guild hunter", 11f, bold = true).apply { setTextColor(FADED) }, full(8))
            addView(choiceRow(hunters.map { it.label.substringAfterLast(' ') }, hunter.label.substringAfterLast(' ')) { name ->
                hunters.firstOrNull { it.label.endsWith(name) }?.let { hunter = it; show() }
            }, full(3))
        }
        val h = hunter
        val level = hunterLevel()
        addView(label("${h.label} · needs ${h.level} Hunter" +
            (if (h.tier == "Master") " and At First Light" else "") +
            (if (h == HunterRumours.Hunter.GILMAN) " · can give every rumour" else ""), 11f).apply { setTextColor(FADED) }, full(4))

        addView(label("Tap the rumour you're on:", 12f, bold = true), full(10))
        for (r in HunterRumours.ALL.filter { h in it.hunters }) {
            val tooHigh = level != null && level < r.level
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply {
                    setColor(ROW_BROWN); cornerRadius = dp(6).toFloat()
                    if (r == current) setStroke(dp(2), GO_GREEN)
                }
                if (tooHigh) alpha = 0.5f
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(r.name, 13f, bold = true))
                    addView(label("${r.method.name}" + (r.requires?.let { " · needs $it" } ?: ""), 10f).apply { setTextColor(FADED) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(label("Lv ${r.level}", 11f, bold = true).apply { setTextColor(FADED) })
                setOnClickListener {
                    current = r
                    showing = r
                    show()
                }
            }, full(4))
        }
        if (level != null) addView(label("Faded rumours need a higher Hunter level than your $level (from WikiSync).", 10f)
            .apply { setTextColor(FADED) }, full(6))
        addView(label("Rumour lists, travel and equipment from the OSRS Wiki.", 9f).apply { setTextColor(FADED) }, full(10))
    }

    // ---------------- One rumour ----------------

    private fun detail(r: HunterRumours.Rumour): View = scrolling {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(r.name, 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("Lv ${r.level}", 12f, bold = true).apply { setTextColor(FADED) })
        }, full())
        addView(label("${r.method.name} · given by " + r.hunters.joinToString(", ") { it.label.substringAfterLast(' ') }, 11f)
            .apply { setTextColor(FADED) }, full(2))
        r.requires?.let { addView(label("Needs: $it", 12f, bold = true).apply { setTextColor(WARN) }, full(6)) }

        // Where, and how to get there
        addView(heading("Where to find them"), full(12))
        for (p in r.places) {
            addView(card {
                addView(label(p.name, 13f, bold = true))
                p.note?.let { addView(label(it, 10f).apply { setTextColor(WARN) }, full(1)) }
                for (t in p.travel) addView(label("•  $t", 12f), full(2))
            }, full(5))
        }

        // What to bring
        addView(heading("What to bring"), full(12))
        addView(card {
            addView(label("Required", 12f, bold = true))
            for (item in r.method.required) addView(label("•  $item", 12f), full(2))
            val level = hunterLevel()
            if (r.method in listOf(HunterRumours.BOX_TRAP, HunterRumours.BIRD_SNARE, HunterRumours.NET_TRAP) && level != null) {
                val n = HunterRumours.trapsAt(level)
                addView(label("At level $level you can lay $n trap${if (n == 1) "" else "s"} (one more in the Wilderness).", 11f)
                    .apply { setTextColor(FADED) }, full(3))
            }
        }, full(5))
        if (r.method.usesLogs) {
            // The traps use logs: most people cut them at the hunting spot
            addView(card {
                addView(label("You'll need logs for every trap", 12f, bold = true).apply { setTextColor(GO_GREEN) })
                addView(label("Cut them at the hunting spot with your axe. Wear Kandarin headgear (from the Kandarin Diary) to get " +
                    "double logs from normal trees.", 12f), full(2))
            }, full(5))
        }
        val useful = r.method.useful.filter { !(r.method.usesLogs && (it.startsWith("Any axe") || it.startsWith("Kandarin"))) }
        if (useful.isNotEmpty()) addView(card {
            addView(label("Useful", 12f, bold = true))
            for (item in useful) addView(label("•  $item", 12f), full(2))
        }, full(5))
        addView(card {
            addView(label("Good for every rumour", 12f, bold = true))
            for (item in HunterRumours.ALWAYS_USEFUL) addView(label("•  $item", 11f), full(2))
        }, full(5))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("◀ All rumours") { showing = null; show() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            addView(button("✓ Done with it", GO_GREEN) { current = null; showing = null; show() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3) })
        }, full(12))
        addView(label("From the OSRS Wiki's Hunters' Rumours pages.", 9f).apply { setTextColor(FADED) }, full(8))
    }

    // ---------------- Small building blocks ----------------

    private fun choiceRow(options: List<String>, selected: String, onPick: (String) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        options.forEachIndexed { i, o ->
            addView(button(o, if (o == selected) BUTTON_BROWN else LIGHT_BUTTON) { onPick(o) }.apply {
                textSize = 11f
                setPadding(dp(2), dp(7), dp(2), dp(7))
                if (o != selected) setTypeface(null, Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(3)
            })
        }
    }

    private fun heading(text: String) = label(text, 13f, bold = true)

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), ROW_BROWN); cornerRadius = dp(6).toFloat() }
        build()
    }

    private fun scrolling(build: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            build()
        })
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
