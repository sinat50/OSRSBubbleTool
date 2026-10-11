package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import org.json.JSONObject

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

        // Reading with RuneLite's icons (Gear Loadouts' reader): good fits are 8-20, worse than this is flagged for
        // checking; your earlier pick goes first if it fits within PICK_MARGIN of the best; guesses kept per slot
        private const val UNSURE_FIT = 22f
        private const val PICK_MARGIN = 3f
        private const val MAX_GUESSES = 5

        // Puts gear into the calculator's saved session, then reloads the page to show it.
        // __GEAR__ is replaced with {"head": 1234, "cape": null, ...}
        private const val LOAD_GEAR_JS = """
            (async function() { try {
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
                if (!loadout) { ix = 0; state.selectedLoadout = 0; loadout = state.loadouts[0]; }   // (shows the one filled in)
                if (!loadout) { alert('Open the calculator once, then try again.'); return; }
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
            } catch (e) { alert("Couldn't load your gear into the calculator. Open the calculator once, then try again."); } })();
        """
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = GearRecognizer.shared(context)
    // Gear Loadouts' reader: RuneLite's icons at each slot's exact place (quicker and surer than the recognizer above,
    // which is the backup: for an area set by hand, or when this reads nothing)
    private val icons = ItemIcons(context)   // RuneLite's item icons, as the game draws them
    private val reading = GameReading(context, icons, debugBuild = false)
    // The equipment area set by hand (the same saved area the older Inventory Setups used)
    private val equipmentArea = RegionOverlay(context, windowManager, "region_equipment", "Equipment")

    private var web: WebView? = null
    private var status: TextView? = null
    private var areaButton: TextView? = null
    private var resultsPanel: ScrollView? = null
    private var resultActions: View? = null   // Load into calculator and Cancel, shown with the results
    private var busy = false
    // Items you picked yourself, remembered per slot (slot to item id)
    private val picksPrefs = context.getSharedPreferences("gear_import_picks", Context.MODE_PRIVATE)

    // The guesses for each slot, and which guess is picked
    private var choices: Map<String, List<GearRecognizer.Choice>> = emptyMap()
    private var notes: Map<String, String> = emptyMap()   // slots to double-check
    private val picked = HashMap<String, Int>()
    // The grid of slots on the results panel, the slot last tapped, and the lines under the grid about it
    private val slotViews = HashMap<String, ItemSlotView>()
    private var selectedSlot: String? = null
    private var detailText: TextView? = null
    private var noteText: TextView? = null

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(webView: WebView): View {
        web = webView
        // The script that loads your gear says what went wrong in an alert: show it in the status line
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                showStatus(message)
                result?.confirm()
                return true
            }
            // ("leave this page?": always yes, as before this client was set, or the reload that shows the
            // imported gear could be cancelled)
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }
        }
        // Load the item icons now, in the background, so Import doesn't have to wait for them
        // (Throwable, not Exception: running out of memory on an older phone would otherwise close the whole
        // app. If it fails, Import tries again and says so if it can't.)
        Thread { try { recognizer.warmUp() } catch (_: Throwable) { } }.start()

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

        // While you check a scan's gear: Load into calculator and Cancel, under Import my gear
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(4))
            setBackgroundColor(PARCHMENT)
            visibility = View.GONE
            addView(button("Load into calculator") { loadIntoCalculator() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
            addView(button("Cancel") { closeResults() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(6) })
        }
        resultActions = actions

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
            addView(actions, LinearLayout.LayoutParams(
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
            // (the item list is loaded first, in the background, if no tool has loaded it yet)
            ItemCatalog.get(context) { data -> Thread {
                try {
                    // Find the side panel by itself; the area set by hand is a backup, also tried when the panel
                    // found by itself has no equipment slots in it (it found some other window)
                    val pixels = LightBoxReader.Pixels(shot)
                    val panel = PanelFinder.findPanel(pixels)
                    val found = panel?.toRect()
                    val saved = equipmentArea.savedArea(shot.width, shot.height)
                    var tab = cutArea(shot, found ?: saved, 40, 60)
                    // With the equipment tab open, Gear Loadouts' reader first; the older recognizer if it reads nothing
                    var result = if (tab != null && panel != null && PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.EQUIPMENT_TAB))
                        try { readByIcons(pixels, panel, data, preferred) } catch (e: Exception) { null } else null
                    if (result == null) result = tab?.let { t -> try { recognizer.recognize(t, preferred) } catch (e: Exception) { null } }
                    if ((result == null || result.slots.isEmpty()) && found != null && saved != null && saved != found) {
                        val other = cutArea(shot, saved, 40, 60)
                        val second = other?.let { t -> try { recognizer.recognize(t, preferred) } catch (e: Exception) { null } }
                        if (other != null && second != null && second.slots.isNotEmpty()) {
                            if (tab != null && tab !== shot) tab.recycle()
                            tab = other; result = second
                        } else if (other != null && other !== shot) other.recycle()
                    }
                    // the pictures aren't needed any more
                    if (tab != null && tab !== shot) tab.recycle()
                    shot.recycle()
                    val reading = result
                    if (tab == null) {
                        handler.post {
                            busy = false
                            showStatus("Couldn't find your equipment tab. Make sure it's open and not covered, " +
                                "then try again. If it still isn't found, use Set area.")
                        }
                        return@Thread
                    }
                    handler.post {
                        busy = false
                        showRecognition(reading)
                    }
                } catch (t: Throwable) {
                    // e.g. out of memory on an older phone: say so, and let Import be tapped again
                    handler.post { busy = false; showStatus("Couldn't read your gear. Try again.") }
                }
            }.start() }
        }, CAPTURE_DELAY_MS)
    }

    // The equipment read the way Gear Loadouts reads it, as the calculator's items: each slot's guesses (the item you
    // picked last time first, if it fits about as well as the best), and "Nothing equipped" last. Null if nothing
    // was read.
    private fun readByIcons(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData,
                            preferred: Map<String, Int>): GearRecognizer.Result? {
        val worn = reading.wornGear(pixels, panel, data) ?: return null
        val slots = LinkedHashMap<String, List<GearRecognizer.Choice>>()
        val notes = HashMap<String, String>()
        for (slot in GearRecognizer.SLOT_ORDER) {
            val fits = worn.slots[slot] ?: continue
            val options = ArrayList<GearRecognizer.Choice>()
            for ((id, fit) in fits) {
                val item = data[id] ?: continue
                val calc = recognizer.calculatorItem(id, slot, item.name, item.pictures.firstOrNull()) ?: continue
                if (options.none { it.item?.id == calc.id }) options.add(GearRecognizer.Choice(calc, fit))
            }
            if (fits.isNotEmpty() && options.isEmpty()) {
                notes[slot] = "Read as ${data[fits[0].first]?.name ?: "an item"}, which the calculator doesn't have"
            }
            // the item you switched to here last time comes first again, if it's about as close
            val pick = preferred[slot]
            val k = options.indexOfFirst { it.item?.id == pick }
            if (k > 0 && options[k].cost - options[0].cost < PICK_MARGIN) {
                options.add(0, options.removeAt(k))
                notes[slot] = "Starting with ${options[0].item?.label} because you picked it last time"
            } else if (options.isNotEmpty() && options[0].cost > UNSURE_FIT) {
                notes[slot] = "Check this one: it's not a clear match"
            }
            slots[slot] = options.take(MAX_GUESSES) + GearRecognizer.Choice(null, 0f)
        }
        return GearRecognizer.Result(slots, 1f, emptyMap(), notes, worn.gamma)
    }

    private fun showRecognition(result: GearRecognizer.Result?) {
        if (result == null || result.slots.isEmpty()) {
            closeResults(keepMessage = true)
            choices = emptyMap()
            notes = emptyMap()
            showStatus("Couldn't find your equipment slots. Make sure the equipment tab is open and not covered, " +
                "then try again. If you set an area by hand, check it covers the whole equipment window.")
            return
        }
        choices = result.slots
        notes = result.notes
        picked.clear()
        showStatus(null)
        buildResultsPanel()
    }

    // ---------------- Checking the guesses ----------------

    private fun buildResultsPanel() {
        val panel = resultsPanel ?: return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // What was read, laid out like the game's equipment tab (and Gear Loadouts')
        column.addView(equipmentGrid(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // the slot you tapped: its name, which guess it is, and anything to check
        val detail = TextView(context).apply { textSize = 13f; setTextColor(DARK_BROWN) }
        val note = TextView(context).apply {
            textSize = 11f
            setTextColor(Palette.ORANGE)
            setTypeface(typeface, Typeface.BOLD)
        }
        detailText = detail
        noteText = note
        column.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        column.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showDetail(null)

        resultActions?.visibility = View.VISIBLE
        panel.removeAllViews()
        panel.addView(column)
        panel.visibility = View.VISIBLE
    }

    // The slots read, laid out as in the game (and in Gear Loadouts). Tap a slot to see what it is; tap it again for
    // the next guess. Slots to check have an amber ring.
    private fun equipmentGrid(): View {
        slotViews.clear()
        selectedSlot = null
        val grid = SlotGrid(context, 3, dp(44), dp(3))
        fun s(slot: String): View = ItemSlotView(context).apply {
            label = GearRecognizer.SLOT_LABELS[slot] ?: slot
            unsure = notes[slot]?.let { !it.startsWith("Starting with") } == true
            slotViews[slot] = this
            showPick(slot)
            val options = choices[slot] ?: return@apply
            setOnClickListener {
                // the first tap shows what's there; the next ones switch to the next guess
                if (selectedSlot == slot && options.size > 1) picked[slot] = ((picked[slot] ?: 0) + 1) % options.size
                showDetail(slot)
            }
        }
        grid.addRow(listOf(null, s("head"), null))
        grid.addRow(listOf(s("cape"), s("neck"), s("ammo")))
        grid.addRow(listOf(s("weapon"), s("body"), s("shield")))
        grid.addRow(listOf(null, s("legs"), null))
        grid.addRow(listOf(s("hands"), s("feet"), s("ring")))
        return grid
    }

    // A slot's square shows the guess picked for it (RuneLite's icon, as the game draws it; the calculator's picture
    // if there's none); empty, its name
    private fun showPick(slot: String) {
        val view = slotViews[slot] ?: return
        val item = choices[slot]?.getOrNull(picked[slot] ?: 0)?.item
        view.picture = item?.let { icons.canvas(it.id) ?: recognizer.iconBitmap(it) }
        view.contentDescription = "${view.label}: ${item?.label ?: "nothing equipped"}"
    }

    // Under the grid: the tapped slot's item, which guess it is and its note; before any tap, how to use the grid
    private fun showDetail(slot: String?) {
        selectedSlot?.let { slotViews[it]?.apply { square = Palette.CELL; invalidate() } }
        selectedSlot = slot
        val detail = detailText ?: return
        val note = noteText ?: return
        if (slot == null) {
            detail.text = "Tap a slot to see what was read; tap it again for the next guess." +
                if (slotViews.values.any { it.unsure }) " Amber rings: please check those." else ""
            note.visibility = View.GONE
            return
        }
        slotViews[slot]?.apply { square = Palette.CURRENT; invalidate() }
        showPick(slot)
        val options = choices[slot].orEmpty()
        val index = picked[slot] ?: 0
        val name = options.getOrNull(index)?.item?.label ?: "Nothing equipped"
        val more = if (options.size > 1) " (${index + 1} of ${options.size}: tap again for the next)" else ""
        detail.text = "${GearRecognizer.SLOT_LABELS[slot] ?: slot}: $name$more"
        note.text = notes[slot].orEmpty()
        note.visibility = if (notes[slot] == null) View.GONE else View.VISIBLE
    }

    // (keepMessage: the window was closed while an import that failed was worked on: its message stays for when
    // the window opens again)
    private fun closeResults(keepMessage: Boolean = false) {
        resultsPanel?.visibility = View.GONE
        resultsPanel?.removeAllViews()
        resultActions?.visibility = View.GONE
        slotViews.clear()
        selectedSlot = null
        detailText = null
        noteText = null
        if (!keepMessage) showStatus(null)
    }

    private fun loadIntoCalculator() {
        val gear = JSONObject()
        picksPrefs.edit {
            for ((slot, options) in choices) {
                val index = picked[slot] ?: 0
                val item = options[index].item
                gear.put(slot, item?.id ?: JSONObject.NULL)
                // you switched this slot to another guess: remember it for next time; you kept a different item
                // than the one remembered: forget it, so it isn't put first again. (An empty slot says nothing about
                // which look-alike you prefer, so the remembered one stays.)
                if (index != 0 && item != null) putInt(slot, item.id)
                else if (item != null && picksPrefs.contains(slot) && picksPrefs.getInt(slot, 0) != item.id) remove(slot)
            }
        }
        closeResults()
        val loading = "Loading your gear into the calculator..."
        showStatus(loading)
        web?.evaluateJavascript(LOAD_GEAR_JS.replace("__GEAR__", gear.toString()), null)
        // clear it after a moment, unless something else is showing by then (an error, or a new import)
        handler.postDelayed({ if (status?.text?.toString() == loading) showStatus(null) }, 3000)
    }
}
