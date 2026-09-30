package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A notepad: a list of notes, each with a title and text. Notes save by themselves as you type.
// While you're typing, the keyboard covers about half the screen, so the note sits in the top half.
class NotesTool(private val context: Context) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val PAPER = Color.parseColor("#FFF8E6")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private val DELETE_RED = Color.parseColor("#B03A2E")
        private val FADED = Color.parseColor("#8C7B5E")
        private const val SAVE_DELAY_MS = 600L

        // How far down the Notepad window sits: below the status bar (or 5% of the screen, whichever is more)
        fun topOffset(context: Context): Int {
            val res = context.resources
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            val bar = if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
            return maxOf(bar, (res.displayMetrics.heightPixels * 0.05f).toInt())
        }
    }

    class Note(val id: Long, var title: String, var body: String, var updated: Long)

    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val notes: MutableList<Note> = load()
    private var open: Note? = null           // the note being read or edited, or null for the list
    private var editing = false              // false = reading the note, true = typing in it
    private var confirmDelete = false
    private lateinit var holder: FrameLayout

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The window's ◀ button: from typing back to reading, and from reading back to the list
    fun goBack() {
        when {
            open != null && editing -> closeNote()
            open != null -> { open = null; show() }
        }
    }

    // The window was closed (✕): save straight away
    fun onWindowClosed() {
        saveNow()
        hideKeyboard()
    }

    private fun show() {
        holder.removeAllViews()
        val note = open
        holder.addView(when {
            note == null -> list()
            editing -> editor(note)
            else -> reader(note)
        })
    }

    // ---------------- The list of notes ----------------

    private fun list(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            addView(button("+ New note", GO_GREEN) {
                val note = Note(System.currentTimeMillis(), "", "", System.currentTimeMillis())
                notes.add(0, note)
                openNote(note, edit = true)
            }, full())
            if (notes.isEmpty()) {
                addView(label("No notes yet. Tap New note to write one: a shopping list, a drop you're chasing, " +
                    "what to do next time you log in…", 12f).apply { setTextColor(FADED) }, full(10))
            }
            for (note in notes.sortedByDescending { it.updated }) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(6).toFloat() }
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(label(titleOf(note), 13f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(label(dateOf(note.updated), 10f).apply { setTextColor(FADED) })
                    })
                    val preview = note.body.lines().firstOrNull { it.isNotBlank() && it.trim() != note.title.trim() }
                    if (preview != null) addView(label(preview.trim(), 11f).apply {
                        setTextColor(FADED); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, full(2))
                    setOnClickListener { openNote(note) }
                }, full(5))
            }
        })
    }

    private fun titleOf(n: Note) = n.title.trim().ifEmpty { n.body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Untitled" }

    private fun dateOf(time: Long): String {
        val now = System.currentTimeMillis()
        val pattern = if (now - time < 24 * 3600_000L && SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let { it.format(Date(now)) == it.format(Date(time)) })
            "h:mm a" else "MMM d"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
    }

    // ---------------- Writing a note ----------------

    private fun openNote(note: Note, edit: Boolean = false) {
        open = note
        editing = edit
        confirmDelete = false
        show()
    }

    // Done typing: show the note plainly (or go back to the list if nothing was written)
    private fun closeNote() {
        val note = open ?: return
        saveNow()
        hideKeyboard()
        editing = false
        confirmDelete = false
        if (note.title.isBlank() && note.body.isBlank()) {   // nothing written: don't keep it
            notes.remove(note); persist()
            open = null
        }
        show()
    }

    // ---------------- Reading a note ----------------

    private fun reader(note: Note): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(titleOf(note), 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("✎ Edit") { openNote(note, edit = true) }.apply { setPadding(dp(10), dp(5), dp(10), dp(5)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            })
            addView(label("Edited " + dateOf(note.updated), 10f).apply { setTextColor(FADED) }, full(1))
            addView(label(note.body.ifBlank { "(no text)" }, 14f).apply {
                setTextIsSelectable(true)   // press and hold to copy
                setLineSpacing(0f, 1.15f)
                if (note.body.isBlank()) setTextColor(FADED)
            }, full(10))
        })
    }

    private fun editor(note: Note): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))

        // Title and Done on one row, so the note itself gets as much room as possible
        val title = EditText(context).apply {
            setText(note.title)
            hint = "Title"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setSingleLine()
            setTextColor(DARK_BROWN)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(5).toFloat() }
        }
        val deleteButton = button(if (confirmDelete) "Delete?" else "🗑", DELETE_RED) {
            if (confirmDelete) {
                handler.removeCallbacksAndMessages(null)
                notes.remove(note); persist()
                hideKeyboard()
                open = null
                show()
            } else { confirmDelete = true; show() }
        }.apply { textSize = if (confirmDelete) 11f else 13f; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
            addView(deleteButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(4) })
            addView(button("Done", GO_GREEN) { closeNote() }.apply { setPadding(dp(10), dp(4), dp(10), dp(4)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { leftMargin = dp(4) })
        }, full())

        // The note. It's sized to stay above the keyboard (about half the screen) and scrolls inside.
        val body = EditText(context).apply {
            setText(note.body)
            hint = "Write your note…"
            textSize = 13f
            setTextColor(DARK_BROWN)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            isVerticalScrollBarEnabled = true
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(5).toFloat() }
        }
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bodyHeight()).apply { topMargin = dp(5) })
        addView(label("Saved as you type", 10f).apply { setTextColor(FADED); gravity = Gravity.END }, full(2))

        fun changed() {
            note.title = title.text.toString()
            note.body = body.text.toString()
            note.updated = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ persist() }, SAVE_DELAY_MS)
        }
        title.addTextChangedListener(watcher { changed() })
        body.addTextChangedListener(watcher { changed() })

        // a new note: start typing straight away
        if (note.title.isEmpty() && note.body.isEmpty()) post { title.requestFocus(); showKeyboard(title) }
    }

    // Tall enough to use the space above the keyboard: the window sits at the top of the screen,
    // and the keyboard takes about the bottom half
    private fun bodyHeight(): Int {
        val screenH = context.resources.displayMetrics.heightPixels
        val aboveKeyboard = (screenH * 0.48f).toInt() - topOffset(context)
        return (aboveKeyboard - dp(28) - dp(36) - dp(30)).coerceAtLeast(dp(70))   // minus the window bar, the title row and padding
    }

    // ---------------- Saving ----------------

    private fun saveNow() {
        handler.removeCallbacksAndMessages(null)
        persist()
    }

    private fun persist() {
        val a = JSONArray()
        for (n in notes) a.put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("updated", n.updated))
        prefs.edit().putString("notes", a.toString()).apply()
    }

    private fun load(): MutableList<Note> {
        val text = prefs.getString("notes", null) ?: return mutableListOf()
        val a = try { JSONArray(text) } catch (e: Exception) {
            // unreadable: keep a copy before anything overwrites it, so the notes aren't lost for good
            prefs.edit().putString("notes_backup_" + System.currentTimeMillis(), text).apply()
            return mutableListOf()
        }
        // each note on its own, so one damaged note doesn't lose the others
        val list = mutableListOf<Note>()
        for (i in 0 until a.length()) {
            try {
                val o = a.getJSONObject(i)
                list.add(Note(o.getLong("id"), o.optString("title"), o.optString("body"), o.optLong("updated")))
            } catch (e: Exception) { }
        }
        return list
    }

    // ---------------- Keyboard ----------------

    private fun showKeyboard(v: View) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        if (!::holder.isInitialized) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(holder.windowToken, 0)
    }

    // ---------------- Small building blocks ----------------

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange() }
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
