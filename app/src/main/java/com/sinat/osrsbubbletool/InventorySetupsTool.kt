package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Inventory Setups: saves pictures of your inventory, equipment, rune pouch and spellbook
// under a name, so you can look at them later while gearing up.
class InventorySetupsTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before taking a screenshot
        private val PARCHMENT = "#F2E3C0".toColorInt()
        private val DARK_BROWN = "#3E2C12".toColorInt()
        private val BUTTON_BROWN = "#8B6B3E".toColorInt()
        private val ROW_BROWN = "#E3CFA2".toColorInt()
        private val DELETE_RED = "#A04030".toColorInt()
    }

    // The four parts of a setup. Each has its own saved area on screen.
    enum class Section(val label: String, val fileName: String, val prefsName: String) {
        INVENTORY("Inventory", "inventory.png", "region_inventory"),
        EQUIPMENT("Equipment", "equipment.png", "region_equipment"),
        RUNE_POUCH("Rune pouch", "rune_pouch.png", "region_rune_pouch"),
        SPELLBOOK("Spellbook", "spellbook.png", "region_spellbook")
    }

    private class Setup(val id: String, var name: String)

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("inventory_setups", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "setups")
    private val regions = Section.values().associateWith {
        RegionOverlay(context, windowManager, it.prefsName, it.label)
    }
    private val setups = loadSetups()

    private var root: FrameLayout? = null
    private var current: Setup? = null         // the setup being viewed, or null for the list
    private var settingArea: Section? = null   // which area's frame is showing
    private var deleteArmed = false

    // Views on the setup screen
    private var status: TextView? = null
    private var nameField: EditText? = null
    private val areaButtons = mutableMapOf<Section, TextView>()
    private val images = mutableMapOf<Section, ImageView>()
    private val placeholders = mutableMapOf<Section, TextView>()

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(): View {
        val r = FrameLayout(context).apply {
            setBackgroundColor(PARCHMENT)
            // take focus first, so the keyboard doesn't pop up until you tap the name
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root = r
        showList()
        return r
    }

    // The ◀ button: from a setup, go back to the list
    fun goBack() {
        if (current == null) return
        saveName()
        finishAreaSetup()
        showList()
    }

    fun onWindowClosed() {
        saveName()
        finishAreaSetup()
    }

    fun onRotated() {
        regions.values.forEach { it.hide() }
        settingArea?.let { areaButtons[it]?.text = "Set area" }
        settingArea = null
    }

    fun destroy() {
        regions.values.forEach { it.hide() }
        settingArea = null
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

    private fun fullWidth(topMarginDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topMarginDp) }

    private fun setContent(content: View) {
        root?.removeAllViews()
        root?.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------- The list of setups ----------------

    private fun showList() {
        current = null
        status = null
        nameField = null
        areaButtons.clear()
        images.clear()
        placeholders.clear()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        column.addView(button("+ New setup") { createSetup() }, fullWidth())

        if (setups.isEmpty()) {
            column.addView(label("No setups yet. Tap New setup, then capture your inventory, " +
                "equipment, rune pouch and spellbook from the game."), fullWidth(10))
        }
        for (setup in setups) {
            val row = TextView(context).apply {
                text = setup.name
                textSize = 15f
                setTextColor(DARK_BROWN)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(ROW_BROWN)
                    cornerRadius = dp(6).toFloat()
                }
                setOnClickListener { showDetail(setup) }
            }
            column.addView(row, fullWidth(8))
        }

        setContent(ScrollView(context).apply { addView(column) })
    }

    private fun createSetup() {
        val setup = Setup(System.currentTimeMillis().toString(), "Setup ${setups.size + 1}")
        setups.add(setup)
        saveSetups()
        showDetail(setup)
    }

    // ---------------- One setup ----------------

    private fun showDetail(setup: Setup) {
        current = setup
        deleteArmed = false
        areaButtons.clear()
        images.clear()
        placeholders.clear()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // Name (tap to edit)
        nameField = EditText(context).apply {
            setText(setup.name)
            textSize = 16f
            setTextColor(DARK_BROWN)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { view, _, _ ->
                saveName()
                view.clearFocus()
                context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(view.windowToken, 0)
                true
            }
        }
        column.addView(nameField, fullWidth())

        status = label("Tap Capture for each part while it's showing in the game. " +
            "The app finds your side panel by itself; Set area is only needed if it can't.")
        column.addView(status, fullWidth(4))

        for (section in Section.values()) {
            column.addView(label(section.label, 15f, bold = true), fullWidth(14))

            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val captureButton = button("Capture") { captureSection(section) }
            val areaButton = button("Set area") { toggleAreaSetup(section) }
            areaButtons[section] = areaButton
            buttons.addView(captureButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(areaButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
            column.addView(buttons, fullWidth(4))

            val placeholder = label("Not captured yet.")
            val image = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            placeholders[section] = placeholder
            images[section] = image
            column.addView(placeholder, fullWidth(6))
            column.addView(image, fullWidth(6))

            val saved = sectionFile(setup, section)
            val bitmap = if (saved.exists()) decodeForScreen(saved) else null
            showPicture(section, bitmap)
        }

        val deleteButton = button("Delete setup", DELETE_RED) {}
        deleteButton.setOnClickListener {
            if (!deleteArmed) {
                deleteArmed = true
                deleteButton.text = "Tap again to delete"
            } else {
                deleteSetup(setup)
            }
        }
        column.addView(deleteButton, fullWidth(20))

        setContent(ScrollView(context).apply { addView(column) })
    }

    private fun showPicture(section: Section, bitmap: Bitmap?) {
        val image = images[section] ?: return
        val placeholder = placeholders[section] ?: return
        if (bitmap == null) {
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
        } else {
            image.setImageBitmap(bitmap)
            image.visibility = View.VISIBLE
            placeholder.visibility = View.GONE
        }
    }

    private fun saveName() {
        val setup = current ?: return
        val typed = nameField?.text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty() && typed != setup.name) {
            setup.name = typed
            saveSetups()
        }
    }

    private fun deleteSetup(setup: Setup) {
        setups.remove(setup)
        saveSetups()
        File(folder, setup.id).deleteRecursively()
        current = null
        showList()
    }

    // ---------------- Areas ----------------

    private fun toggleAreaSetup(section: Section) {
        if (settingArea == section) {
            finishAreaSetup()
            status?.text = "${section.label} area saved. Tap Capture."
            return
        }
        finishAreaSetup()
        regions[section]?.show()
        settingArea = section
        areaButtons[section]?.text = "Save area"
        status?.text = "Drag the gold frame over your ${section.label.lowercase()} in the game. " +
            "Drag the corner triangle to resize. Then tap Save area."
    }

    // Saves and hides whichever area frame is showing
    private fun finishAreaSetup() {
        val section = settingArea ?: return
        regions[section]?.saveAndHide()
        areaButtons[section]?.text = "Set area"
        settingArea = null
    }

    // ---------------- Capturing ----------------

    private fun captureSection(section: Section) {
        val setup = current ?: return
        if (settingArea != null) finishAreaSetup()   // save the frame so it isn't in the picture

        // Capture is off (first capture since the bubble started): ask Android once
        if (!capture.isActive) {
            status?.text = "Waiting for screen-capture permission..."
            capture.request { ok ->
                if (ok) captureSection(section)
                else status?.text = "Couldn't turn on screen capture: ${capture.lastError}."
            }
            return
        }

        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (current !== setup) return@postDelayed   // you left this setup meanwhile
            if (shot == null) {
                status?.text = "Couldn't capture the screen. Try again."
                return@postDelayed
            }
            status?.text = "Saving…"
            val saved = regions[section]?.savedArea(shot.width, shot.height)
            // Finding the panel and saving the picture take a moment, so they run in the background
            // (on the main thread they'd make the game stutter)
            Thread {
                var message: String
                var picture: Bitmap? = null
                try {
                    // Found by itself: the inventory and equipment fill the side panel, the spellbook is
                    // the spellbook tab's picture, and the rune pouch is the runes box in its open window.
                    // An area set by hand is only used if it can't be found.
                    val pixels = LightBoxReader.Pixels(shot)
                    val found = when (section) {
                        Section.INVENTORY, Section.EQUIPMENT -> PanelFinder.find(pixels)
                        Section.SPELLBOOK -> PanelFinder.find(pixels)?.let { PanelFinder.spellbookTab(it) }
                        Section.RUNE_POUCH -> PanelFinder.runePouch(pixels)
                    }
                    val area = found ?: saved
                    val r = area?.let { Rect(it) }
                    message = when {
                        area == null -> if (section == Section.RUNE_POUCH)
                            "Couldn't find the rune pouch. Open it in the game (the window with Pouch and Inventory), then tap Capture."
                            else "Couldn't find your side panel on screen. Make sure it's showing, then try again, " +
                                "or tap Set area to mark it by hand."
                        r == null || !r.intersect(0, 0, shot.width, shot.height) || r.width() < 2 || r.height() < 2 ->
                            "The ${section.label.lowercase()} area is off the screen. Tap Set area to move it."
                        else -> {
                            val pic = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                            picture = pic
                            val file = sectionFile(setup, section)
                            file.parentFile?.mkdirs()
                            file.outputStream().use { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            "${section.label} saved."
                        }
                    }
                } catch (t: Throwable) {
                    message = "Couldn't save the picture: ${t.message}"
                } finally {
                    if (picture !== shot) shot.recycle()   // the full screenshot isn't needed any more
                }
                val pic = picture
                handler.post {
                    if (current !== setup) return@post
                    if (pic != null && message.endsWith("saved.")) showPicture(section, pic)
                    status?.text = message
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

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

    private fun sectionFile(setup: Setup, section: Section) = File(File(folder, setup.id), section.fileName)

    private fun loadSetups(): MutableList<Setup> {
        val list = mutableListOf<Setup>()
        val json = prefs.getString("list", null) ?: return list
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(Setup(item.getString("id"), item.getString("name")))
            }
        } catch (_: Exception) {
            // a damaged list is treated as empty rather than crashing the app
        }
        return list
    }

    private fun saveSetups() {
        val array = JSONArray()
        setups.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        prefs.edit { putString("list", array.toString()) }
    }
}
