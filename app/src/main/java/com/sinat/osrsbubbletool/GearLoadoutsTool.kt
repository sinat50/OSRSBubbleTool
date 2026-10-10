package com.sinat.osrsbubbletool

import android.content.Context
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
// under a name and shown like RuneLite's Inventory Setups plugin. Tap a square to put an item in it.
// (It replaced Inventory Setups, which saved pictures of the screen; those pictures are still shown.)
class GearLoadoutsTool(private val context: Context) {

    companion object {
        private const val SEARCH_WAIT_MS = 150L   // search after typing stops for this long
        private const val MAX_RESULTS = 40
        private val SLOT_LABELS = mapOf("head" to "Head", "cape" to "Cape", "neck" to "Neck", "ammo" to "Ammo",
            "weapon" to "Weapon", "body" to "Body", "shield" to "Shield", "legs" to "Legs", "hands" to "Hands",
            "feet" to "Feet", "ring" to "Ring")
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
    // (the settings file and folder keep Inventory Setups' names, so its setups carry over)
    private val prefs = context.getSharedPreferences("inventory_setups", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "setups")
    private val icons = ItemIcons(context)
    private val loadouts = loadLoadouts()
    private var items: ItemData? = null

    private var root: FrameLayout? = null
    private var current: Loadout? = null      // the loadout being viewed, or null for the list
    private var picking: Place? = null        // the square an item is being picked for
    private var deleteArmed = false
    private var nameField: EditText? = null
    private var notesField: EditText? = null
    private var pendingSearch: Runnable? = null

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
        showList()
        ItemCatalog.get(context) { data ->
            items = data
            // redraw what's showing, now with item pictures
            val l = current
            val p = picking
            when {
                l == null -> showList()
                p == null -> showLoadout(l)
                else -> showPicker(l, p)
            }
        }
        return r
    }

    // The ◀ button: from picking an item, back to the loadout; from a loadout, back to the list
    fun goBack() {
        val l = current ?: return
        if (picking != null) showLoadout(l)
        else {
            saveTyping()
            showList()
        }
    }

    fun onWindowClosed() = saveTyping()

    fun destroy() {
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
        val item = stack?.let { items?.get(it.id) }
        picture = item?.let { icons.picture(it, stack.amount) }
        amount = stack?.amount ?: 1
        label = when {
            stack == null -> emptyLabel
            item == null -> "?"        // an item the list doesn't know (newer than the app)
            else -> null
        }
        contentDescription = item?.name ?: emptyLabel
        if (onClick != null) setOnClickListener { onClick() }
    }

    // ---------------- The list of loadouts ----------------

    private fun showList() {
        current = null
        picking = null
        nameField = null
        notesField = null

        val list = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        list.addView(button("+ New loadout") { createLoadout() }, fullWidth())
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

        // Left: inventory and pouches. Right: equipment and spellbook. Side by side if the window is wide enough.
        val left = column()
        left.addView(label("Inventory", 14f, bold = true))
        val inventory = SlotGrid(context, 4, dp(44), dp(3))
        for (row in 0 until 7) inventory.addRow((0 until 4).map { c ->
            val i = row * 4 + c
            slot(l.inventory[i]) { showPicker(l, Place.Inventory(i)) }
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
        page.addView(deleteButton, fullWidth(20))

        setContent(ScrollView(context).apply { addView(page) })
    }

    // How many runes the loadout's rune pouch holds (4 for the divine one), or null if it has none
    private fun runePouchSize(l: Loadout): Int? {
        val pouch = l.inventory.filterNotNull().mapNotNull { items?.get(it.id)?.name?.lowercase() }
            .firstOrNull { "rune pouch" in it }
        if (pouch == null && l.runePouch == null) return null
        return maxOf(if (pouch != null && "divine" in pouch) 4 else 3, l.runePouch?.size ?: 0)
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
        nowRow.addView(label(now?.let { items?.get(it.id)?.name ?: "Item ${it.id}" } ?: "Empty"),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
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
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(2), dp(8), dp(2))
                    background = GradientDrawable().apply {
                        setColor(ROW_BROWN)
                        cornerRadius = dp(6).toFloat()
                    }
                    setOnClickListener {
                        hideKeyboard(search)
                        put(l, p, Stack(item.id, typedAmount()))
                        showLoadout(l)
                    }
                }
                row.addView(ItemSlotView(context).apply {
                    picture = icons.picture(item)
                    square = Color.TRANSPARENT
                }, LinearLayout.LayoutParams(dp(36), dp(36)))
                val names = column()
                names.addView(label(item.name, 14f))
                // a copy from a minigame or quest says which ("Last Man Standing")
                item.variant?.let { names.addView(label(it, 11f).apply { setTextColor(Palette.FADED) }) }
                row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(8) })
                results.addView(row, fullWidth(4))
            }
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
