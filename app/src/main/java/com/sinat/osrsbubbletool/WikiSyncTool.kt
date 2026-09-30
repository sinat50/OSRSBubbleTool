package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// WikiSync: set your RuneScape name once, and the tools that use your quests, levels and diaries
// (Quest Helper, Hunter Rumours, Teleport Finder) all read it from here.
class WikiSyncTool(private val context: Context) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val PAPER = Color.parseColor("#FFF8E6")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private val FADED = Color.parseColor("#8C7B5E")
        private val WARN = Color.parseColor("#9C4A10")
    }

    private val sync = WikiSync(context)
    private lateinit var holder: FrameLayout
    private var editing = false
    private var message: String? = null
    private var loading = false

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        // opening the window: fetch fresh data if it's been a while
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (sync.needsRefresh) refresh() else show() }
            override fun onViewDetachedFromWindow(v: View) { hideKeyboard() }
        })
        return holder
    }

    private fun refresh() {
        if (sync.username.isEmpty()) return
        loading = true
        message = null
        show()
        sync.refresh { _, error ->
            loading = false
            message = error
            show()
        }
    }

    private fun show() {
        if (!::holder.isInitialized) return
        holder.removeAllViews()
        holder.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(12))
                build()
            })
        })
    }

    private fun LinearLayout.build() {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("WikiSync", 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(WikiSyncBadge.view(context, sync, onClick = null))
        }, full())
        addView(label("Reads your quests, levels and achievement diaries from WikiSync, the OSRS Wiki's service. " +
            "The Quest Helper, Hunter Rumours and Teleport Finder use it to show what you can do.", 11f)
            .apply { setTextColor(FADED) }, full(3))

        val data = sync.cached
        if (sync.username.isEmpty() || editing) {
            addView(label("Your RuneScape name", 12f, bold = true), full(12))
            val field = EditText(context).apply {
                setText(sync.username)
                hint = "e.g. Zezima"
                textSize = 14f
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(DARK_BROWN)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
            }
            val save = {
                val name = field.text.toString().trim()
                if (name.isNotEmpty()) {
                    hideKeyboard()
                    sync.username = name
                    editing = false
                    refresh()
                }
            }
            field.setOnEditorActionListener { _, _, _ -> save(); true }
            addView(field, full(3))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Save", GO_GREEN) { save() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (editing) addView(button("Cancel") { editing = false; hideKeyboard(); show() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            }, full(6))
        } else {
            // Who, and how fresh
            addView(card {
                addView(label(sync.username, 15f, bold = true))
                addView(label(when {
                    loading -> "Reading from WikiSync…"
                    data != null -> "Last read from WikiSync " + ago(data.fetchedAt)
                    else -> "Not read yet"
                }, 11f).apply { setTextColor(FADED) }, full(1))
                if (data != null) {
                    val quests = QuestBook.quests
                    val done = quests.count { data.questState(it.name) == WikiSync.FINISHED }
                    val total = data.levels.values.sum()
                    addView(stat("Quests done", "$done / ${quests.size}"), full(8))
                    addView(stat("Quest points", "${QuestBook.questPoints(data)}"), full(2))
                    QuestBook.combatLevel(data)?.let { addView(stat("Combat level", "$it"), full(2)) }
                    if (total > 0) addView(stat("Total level", "$total"), full(2))
                }
            }, full(10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("↻ Refresh") { refresh() }.apply { isEnabled = !loading; alpha = if (loading) 0.5f else 1f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
                addView(button("✎ Change name") { editing = true; show() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3) })
            }, full(6))
        }
        message?.let { addView(label(it, 12f).apply { setTextColor(WARN) }, full(8)) }

        addView(card {
            addView(label("Keeping it up to date", 12f, bold = true))
            addView(label("This app can only read WikiSync, not update it. WikiSync updates when you play on RuneLite " +
                "(on a PC) with the WikiSync plugin turned on. After that, tap Refresh here. The app also checks for " +
                "new data by itself when it's been more than 10 minutes.", 11f), full(2))
        }, full(12))
    }

    private fun stat(name: String, value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(name, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(value, 12f, bold = true))
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }

    private fun hideKeyboard() {
        if (!::holder.isInitialized) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(holder.windowToken, 0)
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), ROW_BROWN); cornerRadius = dp(6).toFloat() }
        build()
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

// The little "✓ WikiSync" (green, synced) or "✗ WikiSync" (red, not set up) tag shown by the tools that use it.
// Tapping it opens the WikiSync tool.
object WikiSyncBadge {
    private val GREEN = Color.parseColor("#3E7A2E")
    private val RED = Color.parseColor("#B03A2E")

    fun isSynced(sync: WikiSync) = sync.username.isNotEmpty() && sync.cached != null

    fun view(context: Context, sync: WikiSync, onClick: (() -> Unit)?): View = TextView(context).apply {
        val d = context.resources.displayMetrics.density
        val ok = isSynced(sync)
        text = if (ok) "✓ WikiSync" else "✗ WikiSync"
        textSize = 11f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
        background = GradientDrawable().apply { setColor(if (ok) GREEN else RED); cornerRadius = 20 * d }
        if (onClick != null) setOnClickListener { onClick() }
    }

    // The tag on its own row, on the right
    fun row(context: Context, sync: WikiSync, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(view(context, sync, onClick))
    }
}
