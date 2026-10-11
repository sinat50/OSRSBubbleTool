package com.sinat.osrsbubbletool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isNotEmpty
import com.sinat.osrsbubbletool.Loadout.Stack
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.DEEP_RED
import com.sinat.osrsbubbletool.Palette.PAPER
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import com.sinat.osrsbubbletool.Palette.ROW_BROWN
import java.io.File
import org.json.JSONArray

// Gear Loadouts: your inventory, equipment, rune pouch, bolt pouch, quiver and spellbook for a trip, saved
// under a name and shown like RuneLite's Inventory Setups plugin. Tap a square to put an item in it. Setups
// exported from that plugin can be imported, and loadouts exported back to it.
// (It replaced Inventory Setups, which saved pictures of the screen; those pictures are still shown.)
// Scan Current Loadout fills in the spellbook and an open rune pouch (LoadoutReader), the equipment when its tab is open
// (GearRecognizer, shared with the DPS Calculator) and the inventory when its tab is open (InventoryReader), in a
// guided scan (see below).
// hideWindow and showWindow close and reopen this tool's window, so Android's file chooser and share menu
// (which open under the floating windows) can be seen; setWindowCompact shrinks the window to a checklist in a
// corner while the scan looks at the game.
class GearLoadoutsTool(
    private val context: Context,
    private val capture: CaptureManager,
    private val setWindowCompact: (Int) -> Unit,
    private val hideWindow: () -> Unit,
    private val showWindow: () -> Unit
) {

    companion object {
        private const val SEARCH_WAIT_MS = 150L   // search after typing stops for this long
        private const val MAX_RESULTS = 40
        private const val EXPORT_FILE_NAME = "inventory_setups_gear_loadouts.json"
        private val SLOT_LABELS = Loadout.SLOT_LABELS
    }

    // The pictures Inventory Setups saved for a setup, in files/setups/<id>/
    private enum class OldPicture(val label: String, val fileName: String) {
        INVENTORY("Inventory", "inventory.png"),
        EQUIPMENT("Equipment", "equipment.png"),
        RUNE_POUCH("Rune pouch", "rune_pouch.png"),
        SPELLBOOK("Spellbook", "spellbook.png")
    }

    // A square in a loadout that can hold an item
    private sealed class Place {
        data class Inventory(val index: Int) : Place()
        data class Worn(val slot: String) : Place()
        data class RunePouch(val index: Int) : Place()
        data class BoltPouch(val index: Int) : Place()
        data object Quiver : Place()
    }

    private val handler = Handler(Looper.getMainLooper())
    // the readers' details go to the phone's log (adb logcat -s GearLoadouts) only in a debug build
    private val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // (the settings file and folder keep Inventory Setups' names, so its setups carry over)
    private val prefs = context.getSharedPreferences("inventory_setups", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "setups")
    private val icons = ItemIcons(context)
    private val loadouts = loadLoadouts()
    private var items: ItemData? = null

    private var root: FrameLayout? = null
    private var current: Loadout? = null      // the loadout being viewed, or null for the list
    private var picking: Place? = null        // the square an item is being picked for
    private var importing = false             // showing the RuneLite import page
    private var listMessage: String? = null   // shown once at the top of the list (after an import or a save)
    private var deleteArmed = false
    private var nameField: EditText? = null
    private var notesField: EditText? = null
    private var pendingSearch: Runnable? = null
    private var readMessage: String? = null     // what the last Scan Current Loadout found, shown on the loadout once
    // The readers' other guesses after Scan Current Loadout (item IDs, best first), offered first when you tap that square:
    // by slot ("head") for equipment, "inv5" for inventory space 6. Only for the loadout read, until the bubble closes.
    private var guessesFor: String? = null
    private var guesses: Map<String, List<Int>> = emptyMap()
    private var unsure: Set<String> = emptySet()   // the squares (keys as above) read without a clear fit: ringed

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(): View {
        val r = FrameLayout(context).apply {
            setBackgroundColor(PARCHMENT)
            // take focus first, so the keyboard doesn't pop up until you tap a text box
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root = r
        LoadoutFileActivity.listener = fileAnswers
        showList()
        ItemCatalog.get(context) { data ->
            items = data
            // redraw what's showing, now with item pictures
            val l = current
            val p = picking
            when {
                importing -> {}
                l == null -> showList()
                p == null -> showLoadout(l)
                else -> showPicker(l, p)
            }
        }
        return r
    }

    // The ◀ button: from picking an item, back to the loadout; from a loadout or the import page, back to the list
    fun goBack() {
        if (importing) { showList(); return }
        val l = current ?: return
        if (picking != null) showLoadout(l)
        else {
            saveTyping()
            showList()
        }
    }

    fun onWindowClosed() {
        stopScan()
        saveTyping()
    }

    fun destroy() {
        if (LoadoutFileActivity.listener === fileAnswers) LoadoutFileActivity.listener = null
        stopScan()
        pendingSearch?.let { handler.removeCallbacks(it) }
        saveTyping()
    }

    // ---------------- Small building blocks ----------------

    private fun button(label: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 13f, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun textBox(hint: String) = EditText(context).apply {
        this.hint = hint
        setHintTextColor(Palette.FADED)
        setTextColor(DARK_BROWN)
        textSize = 15f
        background = GradientDrawable().apply {
            setColor(PAPER)
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun hideKeyboard(view: View) {
        view.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun fullWidth(topMarginDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topMarginDp) }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun setContent(content: View) {
        root?.removeAllViews()
        root?.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // A square showing one stack (or empty, with a faint label)
    private fun slot(stack: Stack?, emptyLabel: String? = null, onClick: (() -> Unit)? = null) = ItemSlotView(context).apply {
        val shown = stack?.let { items?.shown(it.id) }
        val item = shown?.first
        picture = item?.let { icons.forStack(stack.id, it, stack.amount, shown.second) }
        // a noted item's icon is drawn on its note already; without one, the note is drawn round the item's picture
        noted = shown?.second == true && stack?.let { icons.canvas(it.id) } == null
        amount = stack?.amount ?: 1
        label = when {
            stack == null -> emptyLabel
            item == null -> "?"        // an item the list doesn't know (newer than the app)
            else -> null
        }
        contentDescription = stack?.let { stackName(it) } ?: emptyLabel
        if (onClick != null) setOnClickListener { onClick() }
    }

    // A stack's item name, "(noted)" after it for a noted item
    private fun stackName(s: Stack): String {
        val (item, noted) = items?.shown(s.id) ?: return "Item ${s.id}"
        return if (noted) "${item.name} (noted)" else item.name
    }

    // ---------------- The list of loadouts ----------------

    private fun showList() {
        current = null
        picking = null
        importing = false
        nameField = null
        notesField = null

        val list = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        listMessage?.let { list.addView(label(it, bold = true), fullWidth()) }
        listMessage = null
        list.addView(button("+ New loadout") { createLoadout() }, fullWidth(if (list.isNotEmpty()) 8 else 0))
        val runeLite = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        runeLite.addView(button("Import from RuneLite") { showImport() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (loadouts.any { it.hasItems }) {
            runeLite.addView(button("Save all as a file") { saveAllToFile() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
        }
        list.addView(runeLite, fullWidth(6))
        if (loadouts.isEmpty()) {
            list.addView(label("No loadouts yet. Tap New loadout, then tap a square to put an item in it."), fullWidth(10))
        }
        for (l in loadouts) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(ROW_BROWN)
                    cornerRadius = dp(6).toFloat()
                }
                setOnClickListener { showLoadout(l) }
            }
            // its weapon, or else the first thing in its inventory, as its picture
            val shown = l.equipment["weapon"] ?: l.inventory.firstOrNull { it != null }
            row.addView(slot(shown).apply { square = Color.TRANSPARENT }, LinearLayout.LayoutParams(dp(36), dp(36)))
            row.addView(label(l.name, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8) })
            list.addView(row, fullWidth(8))
        }
        setContent(ScrollView(context).apply { addView(list) })
    }

    private fun createLoadout() {
        val l = Loadout(System.currentTimeMillis().toString(), "Loadout ${loadouts.size + 1}")
        loadouts.add(l)
        saveLoadouts()
        showLoadout(l)
    }

    // ---------------- One loadout ----------------

    private fun showLoadout(l: Loadout) {
        current = l
        picking = null
        importing = false
        deleteArmed = false
        pendingSearch?.let { handler.removeCallbacks(it) }

        val page = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }

        nameField = textBox("Name").apply {
            setText(l.name)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { view, _, _ -> saveTyping(); hideKeyboard(view); true }
        }
        page.addView(nameField, fullWidth())

        if (items == null) page.addView(label("Loading items…"), fullWidth(8))

        // Fill this loadout from the game (the guided scan); a line under it says what happened, when there's news
        val readStatus = label(readMessage ?: "").apply { visibility = if (readMessage == null) View.GONE else View.VISIBLE }
        readMessage = null
        page.addView(button("Scan Current Loadout") { startScan(l, readStatus) }, fullWidth(8))
        page.addView(readStatus, fullWidth(4))

        // Left: inventory and pouches. Right: equipment and spellbook. Side by side if the window is wide enough.
        val left = column()
        left.addView(label("Inventory", 14f, bold = true))
        val inventory = SlotGrid(context, 4, dp(44), dp(3))
        for (row in 0 until 7) inventory.addRow((0 until 4).map { c ->
            val i = row * 4 + c
            slot(l.inventory[i]) { showPicker(l, Place.Inventory(i)) }.apply { unsure = guessesFor == l.id && "inv$i" in this@GearLoadoutsTool.unsure }
        })
        left.addView(inventory, fullWidth(4))
        runePouchSize(l)?.let { size ->
            left.addView(label("Rune pouch", 14f, bold = true), fullWidth(10))
            val grid = SlotGrid(context, 4, dp(44), dp(3))
            grid.addRow((0 until size).map { i -> slot(l.runePouch?.getOrNull(i)) { showPicker(l, Place.RunePouch(i)) } })
            left.addView(grid, fullWidth(4))
        }
        boltPouchSize(l)?.let { size ->
            left.addView(label("Bolt pouch", 14f, bold = true), fullWidth(10))
            val grid = SlotGrid(context, 4, dp(44), dp(3))
            grid.addRow((0 until size).map { i -> slot(l.boltPouch?.getOrNull(i)) { showPicker(l, Place.BoltPouch(i)) } })
            left.addView(grid, fullWidth(4))
        }

        val right = column()
        right.addView(label("Equipment", 14f, bold = true))
        val worn = SlotGrid(context, 3, dp(44), dp(3))
        fun w(slot: String) = slot(l.equipment[slot], SLOT_LABELS[slot]) { showPicker(l, Place.Worn(slot)) }
            .apply { unsure = guessesFor == l.id && slot in this@GearLoadoutsTool.unsure }
        val quiver = if (hasQuiver(l)) slot(l.quiver, "Quiver") { showPicker(l, Place.Quiver) } else null
        worn.addRow(listOf(null, w("head"), quiver))
        worn.addRow(listOf(w("cape"), w("neck"), w("ammo")))
        worn.addRow(listOf(w("weapon"), w("body"), w("shield")))
        worn.addRow(listOf(null, w("legs"), null))
        worn.addRow(listOf(w("hands"), w("feet"), w("ring")))
        right.addView(worn, fullWidth(4))

        right.addView(label("Spellbook", 14f, bold = true), fullWidth(10))
        val bookRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bookSlot = ItemSlotView(context)
        val bookName = label("")
        fun showBook() {
            val b = l.spellbook
            bookSlot.picture = b?.let { icons.spellbook(it) }
            bookSlot.label = if (b == null) "–" else null
            bookName.text = (b?.let { Loadout.SPELLBOOKS[it] } ?: "Not set") + " (tap to change)"
        }
        showBook()
        val nextBook = View.OnClickListener {
            // not set → Standard → Ancient → Lunar → Arceuus → not set
            l.spellbook = when (val b = l.spellbook) {
                null -> 0
                Loadout.SPELLBOOKS.lastIndex -> null
                else -> b + 1
            }
            saveLoadouts()
            showBook()
        }
        bookSlot.setOnClickListener(nextBook)
        bookName.setOnClickListener(nextBook)
        bookRow.addView(bookSlot, LinearLayout.LayoutParams(dp(44), dp(44)))
        bookRow.addView(bookName, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(8) })
        right.addView(bookRow, fullWidth(4))

        page.addView(TwoColumns(context, left, right, dp(130), dp(12)), fullWidth(10))

        page.addView(label("Notes", 14f, bold = true), fullWidth(12))
        notesField = textBox("Anything to remember for this trip").apply {
            setText(l.notes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        page.addView(notesField, fullWidth(4))

        // Pictures saved by Inventory Setups, before it became Gear Loadouts
        val old = OldPicture.entries.mapNotNull { p -> oldPictureFile(l, p).takeIf { it.exists() }?.let { p to it } }
        if (old.isNotEmpty()) {
            page.addView(label("Saved pictures", 14f, bold = true), fullWidth(14))
            for ((p, file) in old) {
                page.addView(label(p.label), fullWidth(6))
                page.addView(ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(decodeForScreen(file))
                }, fullWidth(4))
            }
        }

        val deleteButton = button("Delete loadout", DEEP_RED) {}
        deleteButton.setOnClickListener {
            if (!deleteArmed) {
                deleteArmed = true
                deleteButton.text = "Tap again to delete"
            } else {
                deleteLoadout(l)
            }
        }
        // Back to RuneLite: its Inventory Setups panel imports a setup from the computer's clipboard
        if (l.hasItems) {
            page.addView(label("To RuneLite", 14f, bold = true), fullWidth(14))
            val sendRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val sendNote = label("")
            sendRow.addView(button("Copy") {
                saveTyping()
                copyToClipboard(l.name, l.toRuneLite().toString())
                sendNote.text = "Copied. Get it to your computer (a message to yourself works), copy it there, then " +
                    "click the import button in RuneLite's Inventory Setups panel."
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sendRow.addView(button("Share") { saveTyping(); share(l) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
            page.addView(sendRow, fullWidth(4))
            page.addView(sendNote, fullWidth(4))
        }

        page.addView(deleteButton, fullWidth(20))

        setContent(ScrollView(context).apply { addView(page) })
    }

    // How many runes the loadout's rune pouch holds (4 for the divine one), or null if it has none
    private fun runePouchSize(l: Loadout): Int? {
        val pouch = l.inventory.filterNotNull().firstNotNullOfOrNull { items?.runePouchSize(it.id) }
        if (pouch == null && l.runePouch == null) return null
        return maxOf(pouch ?: 3, l.runePouch?.size ?: 0)
    }

    private fun boltPouchSize(l: Loadout): Int? {
        val has = l.inventory.filterNotNull().any { items?.get(it.id)?.name?.lowercase()?.contains("bolt pouch") == true }
        return if (has || l.boltPouch != null) maxOf(4, l.boltPouch?.size ?: 0) else null
    }

    private fun hasQuiver(l: Loadout) = l.quiver != null ||
        (l.equipment.values + l.inventory.filterNotNull()).any { items?.get(it.id)?.name?.lowercase()?.contains("quiver") == true }

    // Name and notes are saved when you leave the loadout (and when Done is tapped on the name)
    private fun saveTyping() {
        val l = current ?: return
        val typedName = nameField?.text?.toString()?.trim().orEmpty()
        val typedNotes = notesField?.text?.toString()?.trimEnd() ?: l.notes
        if ((typedName.isNotEmpty() && typedName != l.name) || typedNotes != l.notes) {
            if (typedName.isNotEmpty()) l.name = typedName
            l.notes = typedNotes
            saveLoadouts()
        }
    }

    private fun deleteLoadout(l: Loadout) {
        loadouts.remove(l)
        saveLoadouts()
        File(folder, l.id).deleteRecursively()
        current = null
        showList()
    }

    // ---------------- Putting an item in a square ----------------

    private fun stackAt(l: Loadout, p: Place): Stack? = when (p) {
        is Place.Inventory -> l.inventory[p.index]
        is Place.Worn -> l.equipment[p.slot]
        is Place.RunePouch -> l.runePouch?.getOrNull(p.index)
        is Place.BoltPouch -> l.boltPouch?.getOrNull(p.index)
        Place.Quiver -> l.quiver
    }

    private fun put(l: Loadout, p: Place, s: Stack?) {
        // you've checked it
        if (p is Place.Inventory) unsure = unsure - "inv${p.index}"
        if (p is Place.Worn) unsure = unsure - p.slot
        fun MutableList<Stack?>?.with(i: Int): MutableList<Stack?> =
            (this ?: mutableListOf()).also { list -> while (list.size <= i) list.add(null); list[i] = s }
        when (p) {
            is Place.Inventory -> l.inventory[p.index] = s
            is Place.Worn -> if (s == null) l.equipment.remove(p.slot) else l.equipment[p.slot] = s
            is Place.RunePouch -> l.runePouch = l.runePouch.with(p.index)
            is Place.BoltPouch -> l.boltPouch = l.boltPouch.with(p.index)
            Place.Quiver -> l.quiver = s
        }
        saveLoadouts()
    }

    private fun placeName(p: Place) = when (p) {
        is Place.Inventory -> "Inventory space ${p.index + 1}"
        is Place.Worn -> "${SLOT_LABELS[p.slot]} slot"
        is Place.RunePouch -> "Rune pouch"
        is Place.BoltPouch -> "Bolt pouch"
        Place.Quiver -> "Quiver"
    }

    // Worn squares only offer items worn there (a quiver holds ammunition)
    private fun slotFilter(p: Place) = when (p) {
        is Place.Worn -> p.slot
        Place.Quiver -> "ammo"
        else -> null
    }

    private fun showPicker(l: Loadout, p: Place) {
        saveTyping()
        current = l
        picking = p
        nameField = null
        notesField = null

        val page = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        page.addView(label(placeName(p), 15f, bold = true))

        val now = stackAt(l, p)
        val amountBox = textBox("Amount").apply {
            setText((now?.amount ?: 1).toString())
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { view, _, _ -> hideKeyboard(view); true }
        }
        fun typedAmount() = Loadout.parseAmount(amountBox.text.toString()) ?: 1

        // What's there now, with its amount
        val nowRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nowRow.addView(slot(now), LinearLayout.LayoutParams(dp(44), dp(44)))
        nowRow.addView(label(now?.let { stackName(it) } ?: "Empty"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        // an item that can be noted (in the inventory): switch between it and its noted version
        val other = now?.takeIf { p is Place.Inventory }?.let { s -> items?.let { it.notedOf(s.id) ?: it.plainOf(s.id) } }
        if (now != null && other != null) {
            val isNoted = items?.plainOf(now.id) != null
            nowRow.addView(button(if (isNoted) "Unnote" else "Note") { put(l, p, Stack(other, now.amount)); showPicker(l, p) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) })
        }
        page.addView(nowRow, fullWidth(6))

        val amountRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        amountRow.addView(label("Amount"))
        amountRow.addView(amountBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
        if (now != null) {
            amountRow.addView(button("Set amount") { put(l, p, Stack(now.id, typedAmount())); showLoadout(l) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) })
            amountRow.addView(button("Empty", DEEP_RED) { put(l, p, null); showLoadout(l) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { leftMargin = dp(6) })
        }
        page.addView(amountRow, fullWidth(6))

        val filter = slotFilter(p)
        val search = textBox(if (filter != null) "Search ${SLOT_LABELS[filter]?.lowercase()} items" else "Search items").apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { view, _, _ -> hideKeyboard(view); true }
        }
        page.addView(search, fullWidth(10))

        val results = column()
        page.addView(results, fullWidth(6))
        fun showResults(text: String) {
            results.removeAllViews()
            val data = items
            if (data == null) { results.addView(label("Loading items…")); return }
            if (text.isBlank()) { results.addView(label("Type part of an item's name.")); return }
            val found = data.search(text, filter, MAX_RESULTS)
            if (found.isEmpty()) results.addView(label("No items found."))
            for (item in found) {
                results.addView(itemRow(item) {
                    hideKeyboard(search)
                    put(l, p, Stack(item.id, typedAmount()))
                    showLoadout(l)
                }, fullWidth(4))
            }
        }
        // After Scan Current Loadout, the readers' other guesses for this square (tap one if it guessed wrong)
        val key = when (p) { is Place.Worn -> p.slot; is Place.Inventory -> "inv${p.index}"; else -> null }
        val others = if (key != null && guessesFor == l.id) guesses[key].orEmpty() else emptyList()
        val data = items
        if (others.isNotEmpty() && data != null) {
            val guessed = column()
            guessed.addView(label("Other guesses from the screen", 13f, bold = true))
            for (id in others) {
                val item = data[id] ?: continue
                if (item.id == now?.id) continue
                guessed.addView(itemRow(item) {
                    put(l, p, Stack(item.id, typedAmount()))
                    showLoadout(l)
                }, fullWidth(4))
            }
            page.addView(guessed, page.indexOfChild(search), fullWidth(8))
        }
        showResults("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pendingSearch?.let { handler.removeCallbacks(it) }
                val text = s?.toString().orEmpty()
                pendingSearch = Runnable { if (picking == p) showResults(text) }.also { handler.postDelayed(it, SEARCH_WAIT_MS) }
            }
        })

        setContent(ScrollView(context).apply { addView(page) })
    }

    // ---------------- Reading from the game ----------------
    //
    // One tap: the window shrinks to a checklist in a corner (spellbook, inventory, rune pouch, equipment) while the
    // guided scan (GuidedScan) looks at the game; each part ticks off as it's read and goes straight into the loadout.

    private val reading = GameReading(context, icons, debugBuild)
    private var scanning: Loadout? = null   // the loadout being filled by the scan
    private val scan = GuidedScan(capture, reading, debugBuild, object : GuidedScan.Listener {
        override fun read(look: GuidedScan.Look) {
            val l = scanning ?: return
            look.book?.let { l.spellbook = it }
            look.pouch?.let { pouch -> l.runePouch = pouch.toMutableList<Stack?>().also { while (it.size < 3) it.add(null) } }
            look.inventory?.let { applyInventory(l, it) }
            if (look.noPouch) l.runePouch = null
            look.gear?.let { applyGear(l, it) }
            saveLoadouts()
            updateChecklist()
        }

        override fun finished() = finishScan()
    })

    private fun startScan(l: Loadout, status: TextView) {
        if (scan.progress != null) return
        fun say(text: String) { status.text = text; status.visibility = View.VISIBLE }
        val data = items
        if (data == null) { say("Still loading items, try again in a moment."); return }
        if (!capture.isActive) {
            say("Waiting for screen-capture permission...")
            capture.request { ok ->
                if (ok) startScan(l, status)
                else say("Couldn't turn on screen capture: ${capture.lastError}.")
            }
            return
        }
        saveTyping()
        scanning = l
        scan.start(data)
        showChecklist()
        setWindowCompact(BubbleService.COMPACT_TOP_LEFT)
    }

    // The checklist shown while scanning: each part, ticked once read, or what to open for it
    private var checklistLines: TextView? = null

    private fun showChecklist() {
        val page = column().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        page.addView(label("Reading from the game", 14f, bold = true))
        val lines = label("", 13f)
        checklistLines = lines
        page.addView(lines, fullWidth(4))
        page.addView(button("Done") { finishScan() }, fullWidth(6))
        root?.removeAllViews()
        root?.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateChecklist()
    }

    private fun updateChecklist() {
        val sc = scan.progress ?: return
        val pouchLine = when {
            sc.pouch?.isEmpty() == true -> "\u2713 Rune pouch: empty"
            sc.pouch != null -> "\u2713 Rune pouch"
            sc.hasPouch == false -> "\u2713 Rune pouch: none in your inventory"
            else -> "\u2022 Rune pouch: tap it, Open"
        }
        checklistLines?.text = listOf(
            if (sc.book != null) "\u2713 Spellbook" else "\u2022 Spellbook: keep the side panel open",
            if (sc.inventory != null) "\u2713 Inventory" else "\u2022 Inventory: open its tab",
            pouchLine,
            if (sc.gear != null) "\u2713 Equipment" else "\u2022 Equipment: open its tab"
        ).joinToString("\n")
    }

    // Back to the loadout, saying what was read and what wasn't
    private fun finishScan() {
        val sc = scan.stop() ?: return
        val l = scanning ?: return
        scanning = null
        checklistLines = null
        setWindowCompact(BubbleService.COMPACT_OFF)
        val read = listOfNotNull(
            sc.book?.let { "${Loadout.SPELLBOOKS[it]} spellbook" },
            sc.inventory?.let { "inventory" },
            sc.gear?.let { "equipment" },
            sc.pouch?.let { if (it.isEmpty()) "rune pouch (empty)" else "rune pouch" }
        )
        val missing = listOfNotNull(
            if (sc.book == null) "spellbook" else null,
            if (sc.inventory == null) "inventory" else null,
            if (sc.gear == null) "equipment" else null,
            if (sc.pouch == null && sc.hasPouch != false) "rune pouch" else null
        )
        val toCheck = (sc.inventory?.unsure?.count { it } ?: 0) + (sc.gear?.unsure?.size ?: 0)
        readMessage = (if (read.isEmpty()) "Nothing read. " else "Read: ${read.joinToString(", ")}. ") +
            (if (toCheck > 0) "Check the $toCheck ringed square${if (toCheck == 1) "" else "s"}: tap one for other guesses. " else "") +
            (if (missing.isEmpty()) "" else "Not read: ${missing.joinToString(", ")} (tap Scan Current Loadout again).")
        if (current == l && picking == null && !importing) showLoadout(l)
    }

    // Stops a scan without a word (the window closed, or the bubble is closing)
    private fun stopScan() {
        scan.stop()
        scanning = null
        checklistLines = null
    }

    private fun applyGear(l: Loadout, gear: GameReading.GearReading) {
        for ((slot, ids) in gear.slots) {
            val id = ids.firstOrNull()
            if (id == null) l.equipment.remove(slot)
            else l.equipment[slot] = Stack(id, if (slot == "ammo") gear.ammoAmount ?: 1 else 1)
        }
        keepGuesses(l, gear.slots.filterValues { it.size > 1 }, gear.unsure, inventory = false)
    }

    private fun applyInventory(l: Loadout, inventory: GameReading.InventoryReading) {
        for (i in 0 until Loadout.INVENTORY_SIZE) {
            val id = inventory.spaces.getOrNull(i)?.firstOrNull()
            l.inventory[i] = id?.let { Stack(it, inventory.amounts[i] ?: 1) }
        }
        keepGuesses(l, inventory.spaces.withIndex().filter { (it.value?.size ?: 0) > 1 }.associate { "inv${it.index}" to it.value!! },
            inventory.unsure.withIndex().filter { it.value }.map { "inv${it.index}" }.toSet(), inventory = true)
    }

    // Adds a reading's other guesses to the ones kept for this loadout (an inventory read keeps the equipment's), and
    // which squares it wasn't sure of (a new reading of the same squares replaces those)
    private fun keepGuesses(l: Loadout, more: Map<String, List<Int>>, notSure: Set<String>, inventory: Boolean) {
        val same = guessesFor == l.id
        guesses = (if (same) guesses else emptyMap()) + more
        val replaced = unsure.filter { it.startsWith("inv") != inventory }   // the other reading's rings stay
        unsure = (if (same) replaced.toSet() else emptySet()) + notSure
        guessesFor = l.id
    }

    // ---------------- RuneLite: import and export ----------------

    private fun showImport() {
        importing = true
        current = null
        picking = null

        val page = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        page.addView(label("Import from RuneLite", 15f, bold = true))
        page.addView(label("One setup: in RuneLite's Inventory Setups panel, right-click the setup and choose Export " +
            "Setup. Send the copied text to your phone (a message to yourself works), then paste it here.\n\n" +
            "Every setup: right-click the panel's import button, choose Mass Export Setups, put the file on your phone " +
            "and pick it with Pick a file."), fullWidth(6))

        val status = label("", bold = true)
        val box = textBox("Paste the exported setup here").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        page.addView(box, fullWidth(10))

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(b: View) = buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { if (buttons.isNotEmpty()) leftMargin = dp(6) })
        add(button("Paste") {
            val text = context.getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            if (text.isNullOrBlank()) status.text = "Nothing to paste (or Android didn't allow it): long-press the box and choose Paste."
            else { box.setText(text); status.text = "" }
        })
        add(button("Import") {
            hideKeyboard(box)
            if (!importText(box.text.toString())) status.text = "That isn't a setup exported from RuneLite's Inventory Setups."
        })
        page.addView(buttons, fullWidth(6))
        page.addView(button("Pick a file") { pickFile() }, fullWidth(6))
        importProblem?.let { status.text = it; importProblem = null }
        page.addView(status, fullWidth(8))

        setContent(ScrollView(context).apply { addView(page) })
    }

    // Adds every setup in the text; false if there were none. One setup opens; several go to the list.
    private fun importText(text: String): Boolean {
        val stamp = System.currentTimeMillis()
        val found = try { Loadout.fromRuneLite(text) { i -> "$stamp-$i" } } catch (_: Exception) { return false }
        loadouts.addAll(found)
        saveLoadouts()
        if (found.size == 1) showLoadout(found[0])
        else {
            listMessage = "Imported ${found.size} setups from RuneLite."
            showList()
        }
        return true
    }

    private fun pickFile() {
        hideWindow()
        LoadoutFileActivity.open(context)
    }

    // The file chooser's answers (see LoadoutFileActivity: they come to whichever tool is there by then)
    private var importProblem: String? = null   // shown once on the import page
    private val fileAnswers = object : LoadoutFileActivity.Listener {
        override fun opened(text: String?) {
            showWindow()
            if (text != null && !importText(text)) {
                importProblem = "That file isn't a RuneLite Inventory Setups export."
                showImport()
            }
        }

        override fun saved(ok: Boolean) {
            if (ok) listMessage = "Saved your loadouts. On your computer, right-click the import button in " +
                "RuneLite's Inventory Setups panel and choose Mass Import Setups."
            if (current == null) showList()
            showWindow()
        }
    }

    // Every loadout with items in it, as one file RuneLite's Inventory Setups can import (right-click its
    // import button, Mass Import Setups)
    private fun saveAllToFile() {
        val chosen = loadouts.filter { it.hasItems }
        hideWindow()
        LoadoutFileActivity.save(context, EXPORT_FILE_NAME, Loadout.toRuneLite(chosen).toString())
    }

    private fun copyToClipboard(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // Android's share menu (a message, an email, a cloud drive...), to get the setup to your computer
    private fun share(l: Loadout) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "${l.name} (Inventory Setups)")
            .putExtra(Intent.EXTRA_TEXT, l.toRuneLite().toString())
        hideWindow()
        try {
            context.startActivity(Intent.createChooser(send, "Send ${l.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            showWindow()
        }
    }

    // One item to pick: its picture and name
    private fun itemRow(item: ItemData.Item, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply {
            setColor(ROW_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
        addView(ItemSlotView(context).apply {
            picture = icons.forStack(item.id, item, 1, false)
            square = Color.TRANSPARENT
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val names = column()
        names.addView(label(item.name, 14f))
        // a copy from a minigame or quest says which ("Last Man Standing")
        item.variant?.let { names.addView(label(it, 11f).apply { setTextColor(Palette.FADED) }) }
        addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    }

    // ---------------- Inventory Setups' old pictures ----------------

    private fun oldPictureFile(l: Loadout, p: OldPicture) = File(File(folder, l.id), p.fileName)

    // Saved pictures are full size; shown on screen, half size is plenty on high-resolution phones
    private fun decodeForScreen(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        val maxWidth = context.resources.displayMetrics.widthPixels
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------- Saving the list ----------------

    private fun loadLoadouts(): MutableList<Loadout> {
        val list = mutableListOf<Loadout>()
        val json = prefs.getString("list", null) ?: return list
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                try { list.add(Loadout.fromJson(array.getJSONObject(i))) } catch (_: Exception) { }   // a damaged one is skipped
            }
        } catch (_: Exception) {
            // a damaged list is treated as empty rather than crashing the app
        }
        return list
    }

    private fun saveLoadouts() {
        val array = JSONArray()
        loadouts.forEach { array.put(it.toJson()) }
        prefs.edit { putString("list", array.toString()) }
    }
}
