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
import android.util.Log
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
// Read from game fills in the spellbook and an open rune pouch (LoadoutReader), the equipment when its tab is open
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
        private const val CAPTURE_DELAY_MS = 500L   // wait after shrinking the window before the first look
        private const val SCAN_EVERY_MS = 400L      // between one look's end and the next
        private const val SCAN_SECONDS = 90
        // An inventory item fitting its picture this well is a clear match (the owner's items fit 13-34; other tabs
        // read as items fit far worse)
        private const val GOOD_FIT = 40f
        // The same for the equipment: the middle score of the worn items' best guesses must be under this
        // An equipment slot whose best guess fits worse than this is ringed after a read, to be checked (the owner's
        // gear fits 11-37; dark melee gear fits less tightly than crystal armour)
        private const val GEAR_UNSURE_FIT = 32f
        // An inventory item fitting worse than this is ringed after a read, to be checked (good fits are 8-25)
        private const val UNSURE_FIT = 30f
        // The same read with RuneLite's icons (their fits are smaller: the owner's items fit 8-20)
        private const val ICONS_MIDDLE_FIT = 17f
        private const val ICONS_KEPT = 1500
        private const val ICONS_UNSURE_FIT = 22f
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
    private var readMessage: String? = null     // what the last Read from game found, shown on the loadout once
    // The pictures the reader compares the screen with, loaded the first time Read from game is used (in the
    // background, under readerLock: a stopped scan's look can still be finishing when the next scan starts)
    private val readerLock = Any()
    private var books: List<LoadoutReader.Picture>? = null
    private var runes: List<Pair<Int, LoadoutReader.Picture>>? = null
    // The readers' other guesses after Read from game (item IDs, best first), offered first when you tap that square:
    // by slot ("head") for equipment, "inv5" for inventory space 6. Only for the loadout read, until the bubble closes.
    private var guessesFor: String? = null
    private var guesses: Map<String, List<Int>> = emptyMap()
    private var unsure: Set<String> = emptySet()   // the squares (keys as above) read without a clear fit: ringed
    // For reading the inventory: every item picture in brief, and which item each picture shows
    private var shapes: InventoryReader.Shapes? = null
    private var pictureItems: Map<String, Int>? = null
    private var notedPictureItems: Map<String, Int>? = null   // the same for items that can be noted: picture to noted ID
    private var canvases: InventoryReader.Canvases? = null     // RuneLite's icons in brief
    private var sameLook: Map<Int, Int>? = null                // an icon's ID to the item best shown for it (see readInventory)

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

        // Read the spellbook and an open rune pouch from the game
        val readRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val readStatus = label(readMessage ?: "Fills this loadout from the game: tap, then open your inventory, equipment and rune pouch.")
        readMessage = null
        readRow.addView(button("Read from game") { startScan(l, readStatus) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        readRow.addView(readStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { leftMargin = dp(8) })
        page.addView(readRow, fullWidth(8))

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
        // After Read from game, the readers' other guesses for this square (tap one if it guessed wrong)
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

    // ---------------- Reading the screen ----------------

    // ---------------- Reading from the game: the guided scan ----------------
    //
    // One tap: the window shrinks to a checklist in a corner (spellbook, inventory, equipment, rune pouch), and the
    // screen is looked at about twice a second while you open each in the game, in any order. Which tab is open is
    // told by its red button; each part ticks off as it's read. A rune pouch is only asked for if the inventory has
    // one (no pouch in the inventory: the loadout has none). It stops by itself when everything is read, when you tap
    // Done, or after SCAN_SECONDS.

    private class Scan(val l: Loadout, val ends: Long) {
        val started = System.nanoTime()   // for the timings in a test build's log
        var looks = 0
        var book: Int? = null
        var inventory: InventoryReading? = null
        var gear: GearReading? = null
        var pouch: List<Stack>? = null
        var hasPouch: Boolean? = null   // whether the inventory has a rune pouch (null until it's read)
        var busy = false
        val done get() = book != null && inventory != null && gear != null && (pouch != null || hasPouch == false)
    }

    private var scan: Scan? = null
    private val scanTick = Runnable { scanOnce() }

    private fun startScan(l: Loadout, status: TextView) {
        if (scan != null) return
        val data = items
        if (data == null) { status.text = "Still loading items, try again in a moment."; return }
        if (!capture.isActive) {
            status.text = "Waiting for screen-capture permission..."
            capture.request { ok ->
                if (ok) startScan(l, status)
                else status.text = "Couldn't turn on screen capture: ${capture.lastError}."
            }
            return
        }
        saveTyping()
        scan = Scan(l, System.currentTimeMillis() + SCAN_SECONDS * 1000L)
        showChecklist()
        setWindowCompact(BubbleService.COMPACT_TOP_LEFT)
        handler.postDelayed(scanTick, CAPTURE_DELAY_MS)
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
        val sc = scan ?: return
        val pouchLine = when {
            sc.pouch != null -> "\u2713 Rune pouch"
            sc.hasPouch == false -> "\u2713 Rune pouch: none in your inventory"
            else -> "\u2022 Rune pouch: tap it, Open"
        }
        checklistLines?.text = listOf(
            if (sc.book != null) "\u2713 Spellbook" else "\u2022 Spellbook: keep the side panel open",
            if (sc.inventory != null) "\u2713 Inventory" else "\u2022 Inventory: open its tab",
            if (sc.gear != null) "\u2713 Equipment" else "\u2022 Equipment: open its tab",
            pouchLine
        ).joinToString("\n")
    }

    private fun scanOnce() {
        val sc = scan ?: return
        if (sc.busy) return
        if (System.currentTimeMillis() > sc.ends) { finishScan(); return }
        val data = items ?: run { handler.postDelayed(scanTick, SCAN_EVERY_MS); return }
        val grabStarted = System.nanoTime()
        capture.matchScreenSize()
        val shot = capture.grab()
        if (shot == null) { handler.postDelayed(scanTick, SCAN_EVERY_MS); return }
        val grabMs = (System.nanoTime() - grabStarted) / 1_000_000
        sc.busy = true
        val look = ++sc.looks
        Thread {
            var book: Int? = null; var inventory: InventoryReading? = null; var gear: GearReading? = null
            var pouch: List<Stack>? = null
            // how long each part of this look takes, for a test build's log (adb logcat -s GearLoadouts)
            val times = StringBuilder("look $look at %.1f s: screenshot $grabMs ms".format((grabStarted - sc.started) / 1e9))
            var t = System.nanoTime()
            fun lap(what: String) { times.append(", $what ${(System.nanoTime() - t) / 1_000_000}"); t = System.nanoTime() }
            try {
                val pixels = LightBoxReader.Pixels(shot)
                shot.recycle()   // the pixels are copied: the screenshot isn't needed any more
                lap("copy")
                val (bookPictures, runePictures) = readerPictures(data)
                lap("load pictures")
                val panel = PanelFinder.findPanel(pixels)
                lap("find panel${if (panel == null) " (none)" else ""}")
                // an open rune pouch's window dims the inventory behind it: it's only read with the window closed
                val pouchWindow = if (sc.pouch == null || sc.inventory == null) PanelFinder.runePouchWindow(pixels) else null
                lap("find pouch${if (pouchWindow == null) " (none)" else ""}")
                if (panel != null) {
                    if (sc.book == null) { book = LoadoutReader.spellbook(pixels, panel, bookPictures); lap("spellbook${if (book == null) " (no)" else ""}") }
                    if (sc.inventory == null && pouchWindow == null && PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.INVENTORY_TAB)) {
                        inventory = readInventory(pixels, panel, data)
                        lap("inventory${if (inventory == null) " (rejected)" else ""}")
                    }
                    if (sc.gear == null && PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.EQUIPMENT_TAB)) {
                        gear = readEquipment(pixels, panel, data)
                        lap("equipment${if (gear == null) " (rejected)" else ""}")
                    }
                }
                if (sc.pouch == null && sc.hasPouch != false && pouchWindow != null) {
                    pouch = LoadoutReader.runePouch(pixels, pouchWindow, runePictures)
                    lap("pouch${if (pouch == null) " (no)" else ""}")
                }
            } catch (e: Throwable) {
                // e.g. out of memory on an older phone: this look is skipped, the next one tries again
                times.append(", failed: $e")
            }
            if (debugBuild) Log.d("GearLoadouts", times.toString())
            handler.post {
                sc.busy = false
                if (scan !== sc) return@post   // stopped meanwhile
                val l = sc.l
                if (book != null) { sc.book = book; l.spellbook = book }
                if (pouch != null) { sc.pouch = pouch; l.runePouch = pouch.toMutableList<Stack?>().also { while (it.size < 3) it.add(null) } }
                if (inventory != null) {
                    sc.inventory = inventory
                    applyInventory(l, inventory)
                    // no rune pouch in the inventory: the loadout has none (unless one was read open: then there is one,
                    // and the inventory just read it as something else)
                    sc.hasPouch = sc.pouch != null || l.inventory.any { s -> s != null && data.runePouchSize(s.id) != null }
                    if (sc.hasPouch == false) l.runePouch = null
                }
                if (gear != null) { sc.gear = gear; applyGear(l, gear) }
                if (book != null || inventory != null || gear != null || pouch != null) saveLoadouts()
                if (debugBuild) {
                    val ticked = listOfNotNull(book?.let { "spellbook" }, inventory?.let { "inventory" + if (sc.hasPouch == false) " (no pouch)" else "" },
                        gear?.let { "equipment" }, pouch?.let { "rune pouch" })
                    if (ticked.isNotEmpty()) Log.d("GearLoadouts", "ticked at %.1f s: ${ticked.joinToString()}".format((System.nanoTime() - sc.started) / 1e9))
                }
                updateChecklist()
                if (sc.done) finishScan() else handler.postDelayed(scanTick, SCAN_EVERY_MS)
            }
        }.start()
    }

    // Back to the loadout, saying what was read and what wasn't
    private fun finishScan() {
        val sc = scan ?: return
        scan = null
        if (debugBuild) Log.d("GearLoadouts", "scan finished at %.1f s after ${sc.looks} looks".format((System.nanoTime() - sc.started) / 1e9))
        handler.removeCallbacks(scanTick)
        checklistLines = null
        setWindowCompact(BubbleService.COMPACT_OFF)
        val read = listOfNotNull(
            sc.book?.let { "${Loadout.SPELLBOOKS[it]} spellbook" },
            sc.inventory?.let { "inventory" },
            sc.gear?.let { "equipment" },
            sc.pouch?.let { "rune pouch" }
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
            (if (missing.isEmpty()) "" else "Not read: ${missing.joinToString(", ")} (tap Read from game again).")
        if (current == sc.l && picking == null && !importing) showLoadout(sc.l)
    }

    // Stops a scan without a word (the window closed, or the bubble is closing)
    private fun stopScan() {
        if (scan == null) return
        scan = null
        handler.removeCallbacks(scanTick)
        checklistLines = null
    }

    // The spellbook pictures and the pouch runes' pictures, read from the app's files once (in the background)
    private fun readerPictures(data: ItemData): Pair<List<LoadoutReader.Picture>, List<Pair<Int, LoadoutReader.Picture>>> = synchronized(readerLock) {
        val b = books ?: (0..3).mapNotNull { assetPicture("items/spellbooks/$it.png") }.also { books = it }
        val r = runes ?: LoadoutReader.POUCH_RUNES.mapNotNull { id ->
            (iconPicture(id)?.let { trimmed(it) } ?: data[id]?.picture()?.let { assetPicture("items/icons/$it") })?.let { id to it }
        }.also { runes = it }
        b to r
    }

    // An item's icon (RuneLite's, 36 x 32) by its ID. Icons read are kept (about 4.6 KB each), so reading again
    // doesn't decode the same ones again.
    private val iconPixels = android.util.LruCache<Int, LoadoutReader.Picture>(ICONS_KEPT)

    private fun iconPicture(id: Int): LoadoutReader.Picture? {
        val key = icons.iconId(id)
        synchronized(iconPixels) { iconPixels.get(key) }?.let { return it }
        return assetPicture("items/canvas/$key.png")?.also { synchronized(iconPixels) { iconPixels.put(key, it) } }
    }

    // A picture cut down to its visible part
    private fun trimmed(p: LoadoutReader.Picture): LoadoutReader.Picture {
        var l = p.width; var t = p.height; var r = -1; var b = -1
        for (y in 0 until p.height) for (x in 0 until p.width) if (((p.argb[y * p.width + x] ushr 24) and 0xFF) > 128) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        if (r < 0) return p
        val w = r - l + 1; val h = b - t + 1
        return LoadoutReader.Picture(w, h, IntArray(w * h) { p.argb[(t + it / w) * p.width + l + it % w] })
    }

    private fun assetPicture(path: String): LoadoutReader.Picture? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.let { bmp ->
            val argb = IntArray(bmp.width * bmp.height)
            bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            LoadoutReader.Picture(bmp.width, bmp.height, argb).also { bmp.recycle() }
        }
    } catch (_: Exception) { null }

    // What the equipment tab shows: each slot's guesses (item IDs, best first; an empty list for an empty slot)
    // and the ammunition's amount. Null if the side panel isn't showing the equipment tab.
    private class GearReading(val slots: Map<String, List<Int>>, val ammoAmount: Int?, val unsure: Set<String>)

    private fun readEquipment(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData): GearReading? {
        // RuneLite's icons at each slot's exact place: a fraction of a second. The older gear reader (the DPS tool's,
        // 9-14 seconds on the owner's phone, and the phone slows down after it) only if that finds nothing at all.
        val canvasList = synchronized(readerLock) {
            canvases ?: try {
                InventoryReader.Canvases(context.assets.open("items/canvas.bin").use { it.readBytes() })
            } catch (_: Exception) { null }?.also { canvases = it }
        }
        if (canvasList != null) {
            val worn = InventoryReader.readEquipment(pixels, panel, canvasList, { id -> iconPicture(id) }) { id -> data.shown(id)?.first?.slot }
            if (worn != null && worn.slots.values.any { it.isNotEmpty() }) {
                if (debugBuild) Log.d("GearLoadouts", "equipment: " + worn.slots.entries.joinToString { (k, v) ->
                    "$k ${v.firstOrNull()?.let { (id, fit) -> data.shown(id)?.first?.name + " %.0f".format(fit) } ?: "-"}" })
                val alike = synchronized(readerLock) { lookAlikes(data) }
                val ids = worn.slots.mapValues { (_, fits) ->
                    data.chargedFirst(fits.map { alike[it.first] ?: it.first }, fits.map { it.second }).distinct()
                }
                val unsure = worn.slots.filterValues { (it.firstOrNull()?.second ?: 0f) > ICONS_UNSURE_FIT }.keys
                return GearReading(ids, worn.ammoAmount, unsure)
            }
        }
        return readEquipmentSlowly(pixels, panel)
    }

    // Items that look exactly alike share one icon (the lowest ID's): for an icon read, the one to pick (call under
    // readerLock)
    private fun lookAlikes(data: ItemData): Map<Int, Int> = sameLook ?: HashMap<Int, MutableList<Int>>().also { groups ->
        for ((a, b) in icons.sharedIcon) groups.getOrPut(b) { mutableListOf(b) }.add(a)
    }.mapValues { (_, group) -> data.preferred(group) }.also { sameLook = it }

    private fun readEquipmentSlowly(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box): GearReading? {
        val w = panel.width
        val h = panel.height
        if (w <= 0 || h <= 0 || panel.right > pixels.width || panel.bottom > pixels.height) return null
        val px = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels.data, (panel.top + y) * pixels.width + panel.left, px, y * w, w)
        val result = GearRecognizer.shared(context).recognize(px, w, h) ?: return null
        if (debugBuild) Log.d("GearLoadouts", "panel $panel, slot layout fit ${result.layoutScore}, brightness ${result.gamma}")
        if (debugBuild) for ((slot, choices) in result.slots) Log.d("GearLoadouts", "  $slot at ${result.slotRects[slot]}: " +
            choices.take(3).joinToString { "${it.item?.label ?: "empty"} (${it.item?.id}) %.1f".format(it.cost) })
        if (result.slots.isEmpty()) return null
        // (The scan only reads here when the equipment tab's button shows it's open, so another tab can't be taken for
        // it. Gear that fits only poorly is ringed for checking rather than thrown away: dark melee gear once got
        // rejected over and over, 14 seconds a time, though every guess was right.)
        val unsure = result.slots.filterValues { (it.firstOrNull()?.takeIf { c -> c.item != null }?.cost ?: 0f) > GEAR_UNSURE_FIT }.keys
        val slots = result.slots.mapValues { (_, choices) -> choices.mapNotNull { it.item?.id } }
        // the ammunition's amount is written in yellow over the top of its slot
        val ammo = result.slotRects["ammo"]?.takeIf { slots["ammo"].orEmpty().isNotEmpty() }?.let { r ->
            LoadoutReader.amountIn(pixels, PanelFinder.Box(panel.left + r.left - r.width() / 8, panel.top + r.top - r.height() / 6,
                panel.left + r.right, panel.top + r.top + r.height() / 2))
        }
        return GearReading(slots, ammo, unsure)
    }

    // What the inventory tab shows: each space's guesses (item IDs, best first; null for an empty space), amount, and
    // whether its best guess fits only poorly
    private class InventoryReading(val spaces: List<List<Int>?>, val amounts: List<Int?>, val unsure: List<Boolean>)

    private fun readInventory(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData): InventoryReading? {
        val shapeList: InventoryReader.Shapes
        val items: Map<String, Int>
        val notedItems: Map<String, Int>
        val canvasList: InventoryReader.Canvases?
        val lookAlikes: Map<Int, Int>
        synchronized(readerLock) {
            shapeList = shapes ?: try {
                InventoryReader.Shapes(context.assets.open("items/shapes.bin").use { it.readBytes() })
            } catch (_: Exception) { return null }.also { shapes = it }
            // which item a picture shows; on a bank note, among the items that can be noted (as its noted ID)
            items = pictureItems ?: data.byPicture().mapValues { it.value.id }.also { pictureItems = it }
            notedItems = notedPictureItems ?: data.byPicture { data.notedOf(it.id) != null }
                .mapValues { data.notedOf(it.value.id)!! }.also { notedPictureItems = it }
            canvasList = canvases ?: try {
                InventoryReader.Canvases(context.assets.open("items/canvas.bin").use { it.readBytes() })
            } catch (_: Exception) { null }?.also { canvases = it }
            lookAlikes = lookAlikes(data)
        }
        val found = InventoryReader.read(pixels, panel, shapeList, { name -> assetPicture("items/icons/$name") }, notedItems.keys,
            canvasList, { id -> iconPicture(id) })
        val filled = found.filterNotNull()
        if (debugBuild) Log.d("GearLoadouts", "inventory: ${found.mapIndexed { i, f -> "$i ${f?.let { it.itemId?.let { id -> data.shown(id)?.first?.name + " #$id" } ?: it.picture } ?: "-"} ${f?.cost?.let { "%.0f".format(it) } ?: ""}" }}")
        // Another tab (equipment, prayers...) or the inventory dimmed behind a window gives poor fits, or none: then the
        // inventory isn't changed. Read with RuneLite's icons, an inventory's middle fit is 10-14 (the owner's), other
        // tabs' 20 or more; read only the wiki's way, 60% of the items must fit better than GOOD_FIT.
        if (filled.isEmpty()) return null
        if (filled.any { it.itemId != null }) {
            if (filled.map { it.cost }.sorted()[filled.size / 2] > ICONS_MIDDLE_FIT) return null
        } else if (filled.count { it.cost < GOOD_FIT } < filled.size * 0.6f) return null
        return InventoryReading(
            found.map { f -> f?.let {
                if (it.itemId != null) {
                    val ids = (listOf(it.itemId) + it.otherIds).map { id -> lookAlikes[id] ?: id }
                    data.chargedFirst(ids, listOf(it.cost) + it.otherCosts).distinct()
                } else (listOf(it.picture) + it.others).mapNotNull { pic -> (if (it.noted) notedItems else items)[pic] }.distinct()
            } },
            found.map { it?.amount },
            found.map { it != null && (it.amountUnread || it.cost > (if (it.itemId != null) ICONS_UNSURE_FIT else UNSURE_FIT)) })
    }

    private fun applyGear(l: Loadout, gear: GearReading) {
        for ((slot, ids) in gear.slots) {
            val id = ids.firstOrNull()
            if (id == null) l.equipment.remove(slot)
            else l.equipment[slot] = Stack(id, if (slot == "ammo") gear.ammoAmount ?: 1 else 1)
        }
        keepGuesses(l, gear.slots.filterValues { it.size > 1 }, gear.unsure, inventory = false)
    }

    private fun applyInventory(l: Loadout, inventory: InventoryReading) {
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
