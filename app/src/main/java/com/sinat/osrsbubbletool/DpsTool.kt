package com.sinat.osrsbubbletool

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

// The DPS Calculator tool: the wiki's calculator, plus "Import my gear", which reads
// your equipment tab from the screen and loads it into the calculator.
class DpsTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")

        // Puts gear into the calculator's saved session, then reloads the page to show it.
        // __GEAR__ is replaced with {"head": 1234, "cape": null, ...}
        private const val LOAD_GEAR_JS = """
            (async function() {
                var gear = __GEAR__;
                var db = await new Promise(function(res, rej) {
                    var r = indexedDB.open('localforage');
                    r.onsuccess = function() { res(r.result); };
                    r.onerror = function() { rej(r.error); };
                });
                function store() {
                    return db.transaction('keyvaluepairs', 'readwrite').objectStore('keyvaluepairs');
                }
                var state = await new Promise(function(res, rej) {
                    var q = store().get('dps-calc-state');
                    q.onsuccess = function() { res(q.result); };
                    q.onerror = function() { rej(q.error); };
                });
                if (!state || !state.loadouts) { alert('Open the calculator once, then try again.'); return; }
                var ix = state.selectedLoadout || 0;
                var loadout = state.loadouts[ix];
                if (!loadout.equipment) loadout.equipment = {};
                for (var slot in gear) {
                    loadout.equipment[slot] = gear[slot] === null ? null : { id: gear[slot] };
                }
                await new Promise(function(res, rej) {
                    var q = store().put(state, 'dps-calc-state');
                    q.onsuccess = function() { res(); };
                    q.onerror = function() { rej(q.error); };
                });
                location.reload();
            })();
        """
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = GearRecognizer(context)
    // Same saved area as Inventory Setups' Equipment, so setting it in either tool works for both
    private val equipmentArea = RegionOverlay(context, windowManager, "region_equipment", "Equipment")

    private var web: WebView? = null
    private var status: TextView? = null
    private var areaButton: TextView? = null
    private var resultsPanel: ScrollView? = null
    private var busy = false
    private var lastCapture: Bitmap? = null      // the equipment window from the last import
    // Items you picked yourself, remembered per slot (slot to item id)
    private val picksPrefs = context.getSharedPreferences("gear_import_picks", Context.MODE_PRIVATE)

    // The guesses for each slot, and which guess is picked
    private var choices: Map<String, List<GearRecognizer.Choice>> = emptyMap()
    private var notes: Map<String, String> = emptyMap()   // slots to double-check
    private val picked = HashMap<String, Int>()

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(webView: WebView): View {
        web = webView
        // Load the item icons now, in the background, so Import doesn't have to wait for them
        Thread { try { recognizer.warmUp() } catch (_: Exception) { } }.start()

        val importButton = button("Import my gear") { importGear() }
        val setArea = button("Set area") { toggleAreaSetup() }
        areaButton = setArea

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(PARCHMENT)
            addView(importButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            addView(setArea, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
        }

        val statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(DARK_BROWN)
            setBackgroundColor(PARCHMENT)
            setPadding(dp(8), 0, dp(8), dp(4))
            visibility = View.GONE
        }
        status = statusView

        val panel = ScrollView(context).apply {
            setBackgroundColor(PARCHMENT)
            visibility = View.GONE
        }
        resultsPanel = panel

        val content = FrameLayout(context).apply {
            addView(webView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    fun onWindowClosed() {
        finishAreaSetup()
    }

    fun onRotated() {
        equipmentArea.hide()
        areaButton?.text = "Set area"
    }

    fun destroy() {
        equipmentArea.hide()
    }

    // ---------------- Small building blocks ----------------

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(BUTTON_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun showStatus(text: String?) {
        status?.text = text ?: ""
        status?.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---------------- Equipment area ----------------

    private fun toggleAreaSetup() {
        if (equipmentArea.isShowing) {
            finishAreaSetup()
            showStatus("Equipment area saved. Open your equipment tab in the game and tap Import my gear.")
        } else {
            equipmentArea.show()
            areaButton?.text = "Save area"
            showStatus("Only needed if Import can't find your equipment tab by itself. Drag the gold frame " +
                "over the whole equipment window, from its top edge down to the four buttons at the bottom. " +
                "Drag the corner to resize, then tap Save area.")
        }
    }

    private fun finishAreaSetup() {
        if (!equipmentArea.isShowing) return
        equipmentArea.saveAndHide()
        areaButton?.text = "Set area"
    }

    // ---------------- Importing ----------------

    private fun importGear() {
        if (busy) return
        finishAreaSetup()
        if (!capture.isActive) {
            showStatus("Waiting for screen-capture permission...")
            capture.request { ok ->
                if (ok) importGear()
                else showStatus("Couldn't turn on screen capture: ${capture.lastError}.")
            }
            return
        }

        busy = true
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (shot == null) {
                busy = false
                showStatus("Couldn't capture the screen. Try again.")
                return@postDelayed
            }
            showStatus("Recognizing your gear...")
            val preferred = GearRecognizer.SLOT_ORDER
                .filter { picksPrefs.contains(it) }
                .associateWith { picksPrefs.getInt(it, 0) }
            Thread {
                try {
                    // Find the side panel by itself; the area set by hand is only a backup
                    val area = PanelFinder.find(LightBoxReader.Pixels(shot))
                        ?: equipmentArea.savedArea(shot.width, shot.height)
                    val r = area?.let { Rect(it) }
                    if (r == null || !r.intersect(0, 0, shot.width, shot.height) || r.width() < 40 || r.height() < 60) {
                        shot.recycle()
                        handler.post {
                            busy = false
                            showStatus("Couldn't find your equipment tab. Make sure it's open and not covered, " +
                                "then try again. If it still isn't found, use Set area.")
                        }
                        return@Thread
                    }
                    val tab = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                    if (tab !== shot) shot.recycle()   // the full screenshot isn't needed any more
                    val result = try { recognizer.recognize(tab, preferred) } catch (e: Exception) { null }
                    handler.post {
                        busy = false
                        lastCapture = tab
                        showRecognition(result, tab)
                    }
                } catch (t: Throwable) {
                    // e.g. out of memory on an older phone: say so, and let Import be tapped again
                    handler.post { busy = false; showStatus("Couldn't read your gear. Try again.") }
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    // The captured window with each found slot outlined in gold
    private fun drawSlots(tab: Bitmap, rects: Map<String, Rect>): Bitmap {
        val out = tab.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            color = Color.parseColor("#E8C766")
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, out.width / 150f)
        }
        rects.values.forEach { canvas.drawRect(it, paint) }
        return out
    }

    private fun showRecognition(result: GearRecognizer.Result?, tab: Bitmap) {
        val preview = if (result != null) drawSlots(tab, result.slotRects) else tab
        if (result == null || result.slots.isEmpty()) {
            val score = result?.let { String.format(Locale.US, " (fit %.2f)", it.layoutScore) } ?: ""
            showStatus("Couldn't find the equipment slots$score. The picture shows what was captured and " +
                "where the slots were expected. Make sure the equipment tab is open and the frame " +
                "covers the whole equipment window.")
            choices = emptyMap()
            notes = emptyMap()
            buildResultsPanel(preview)
            return
        }
        choices = result.slots
        notes = result.notes
        picked.clear()
        showStatus(String.format(Locale.US, "Check each slot (game brightness measured as %.1f). Tap an " +
            "item to switch to the next guess, then tap Load into calculator.", result.gamma))
        buildResultsPanel(preview)
    }

    // ---------------- Checking the guesses ----------------

    private fun buildResultsPanel(preview: Bitmap) {
        val panel = resultsPanel ?: return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // What was captured, with the slots it found outlined
        column.addView(ImageView(context).apply {
            setImageBitmap(preview)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160)))

        for (slot in GearRecognizer.SLOT_ORDER) {
            val options = choices[slot] ?: continue
            column.addView(slotRow(slot, options), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        // Full-width Load button on top, then Save picture and Cancel side by side
        if (choices.isNotEmpty()) {
            column.addView(button("Load into calculator") { loadIntoCalculator() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) })
        }

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // Saves the uncompressed capture, for checking how well recognition works
        actions.addView(button("Save picture to phone") { saveCapture() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        actions.addView(button(if (choices.isEmpty()) "Close" else "Cancel") { closeResults() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })

        column.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(if (choices.isNotEmpty()) 14 else 8) })

        panel.removeAllViews()
        panel.addView(column)
        panel.visibility = View.VISIBLE
    }

    private fun slotRow(slot: String, options: List<GearRecognizer.Choice>): View {
        val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val nameView = TextView(context).apply {
            textSize = 14f
            setTextColor(DARK_BROWN)
        }
        val slotLabel = TextView(context).apply {
            text = GearRecognizer.SLOT_LABELS[slot] ?: slot
            textSize = 11f
            setTextColor(DARK_BROWN)
            setTypeface(typeface, Typeface.BOLD)
        }

        fun refresh() {
            val index = picked[slot] ?: 0
            val choice = options[index]
            val item = choice.item
            val more = if (options.size > 1) "  (${index + 1}/${options.size}) ▸" else ""
            nameView.text = (item?.label ?: "Nothing equipped") + more
            iconView.setImageBitmap(item?.let { recognizer.iconBitmap(it) })
        }
        refresh()

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(slotLabel)
            addView(nameView)
            // look-alike items that were close: ask you to check
            notes[slot]?.let { note ->
                addView(TextView(context).apply {
                    text = note
                    textSize = 11f
                    setTextColor(Color.parseColor("#C0600A"))
                    setTypeface(typeface, Typeface.BOLD)
                })
            }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(ROW_BROWN)
                cornerRadius = dp(6).toFloat()
            }
            addView(iconView, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10) })
            // tap to switch to the next guess
            setOnClickListener {
                picked[slot] = ((picked[slot] ?: 0) + 1) % options.size
                refresh()
            }
        }
    }

    private fun closeResults() {
        resultsPanel?.visibility = View.GONE
        resultsPanel?.removeAllViews()
        lastCapture = null   // only "Save picture" (on the panel just closed) used it
        showStatus(null)
    }

    // Saves the last capture as an uncompressed PNG in Pictures/OSRS Bubble Tool
    private fun saveCapture() {
        val picture = lastCapture ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showStatus("Saving pictures needs Android 10 or newer.")
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "equipment-${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OSRS Bubble Tool")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("couldn't create the file")
            resolver.openOutputStream(uri)?.use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            showStatus("Saved to Pictures/OSRS Bubble Tool.")
        } catch (e: Exception) {
            showStatus("Couldn't save the picture: ${e.message}")
        }
    }

    private fun loadIntoCalculator() {
        val gear = JSONObject()
        val remember = picksPrefs.edit()
        for ((slot, options) in choices) {
            val index = picked[slot] ?: 0
            val item = options[index].item
            gear.put(slot, item?.id ?: JSONObject.NULL)
            // you switched this slot to another guess: remember it for next time
            if (index != 0 && item != null) remember.putInt(slot, item.id)
        }
        remember.apply()
        closeResults()
        showStatus("Loading your gear into the calculator...")
        web?.evaluateJavascript(LOAD_GEAR_JS.replace("__GEAR__", gear.toString()), null)
        handler.postDelayed({ showStatus(null) }, 3000)
    }
}
