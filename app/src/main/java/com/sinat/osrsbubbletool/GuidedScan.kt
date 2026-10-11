package com.sinat.osrsbubbletool

import android.os.Handler
import android.os.Looper
import android.util.Log

// Gear Loadouts' guided scan ("Scan Current Loadout"): looks at the screen about twice a second while you open your
// inventory, equipment and rune pouch in the game, in any order, and reads whatever is showing (GameReading). Which tab
// is open is told by its red button. A rune pouch is only asked for if the inventory has one. It stops by itself when
// everything is read or after SCAN_SECONDS; the tool shows the checklist and writes each reading into the loadout.
// Each look's timings go to a test build's log (adb logcat -s GearLoadouts).
class GuidedScan(private val capture: CaptureManager, private val reading: GameReading, private val debugBuild: Boolean,
                 private val listener: Listener) {

    companion object {
        private const val FIRST_LOOK_MS = 500L   // wait after the window shrinks before the first look
        private const val LOOK_EVERY_MS = 400L   // between one look's end and the next
        private const val SCAN_SECONDS = 90
    }

    // Told on the main thread
    interface Listener {
        fun read(look: Look)   // what a look read for the first time (only the parts not read before)
        fun finished()         // everything is read, or the time is up
    }

    // What one look read for the first time; noPouch: the inventory turned out to have no rune pouch
    class Look(val book: Int?, val inventory: GameReading.InventoryReading?, val gear: GameReading.GearReading?,
               val pouch: List<Loadout.Stack>?, val noPouch: Boolean)

    // What the scan has read so far
    class Progress {
        val started = System.nanoTime()
        var looks = 0
        var book: Int? = null
        var inventory: GameReading.InventoryReading? = null
        var gear: GameReading.GearReading? = null
        var pouch: List<Loadout.Stack>? = null
        var hasPouch: Boolean? = null   // whether the inventory has a rune pouch (null until it's read)
        var emptyPouchSeen = false      // the last look saw an empty pouch (believed when the next one sees it too)
        var emptyInventorySeen = false  // the same for an empty inventory
        val done get() = book != null && inventory != null && gear != null && (pouch != null || hasPouch == false)
        val seconds get() = (System.nanoTime() - started) / 1e9
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { look() }
    private var data: ItemData? = null
    private var ends = 0L
    private var busy = false

    // The scan under way, or null
    var progress: Progress? = null
        private set

    fun start(items: ItemData) {
        if (progress != null) return
        data = items
        progress = Progress()
        ends = System.currentTimeMillis() + SCAN_SECONDS * 1000L
        handler.postDelayed(tick, FIRST_LOOK_MS)
    }

    // Stops it; what it read so far (null if none was under way)
    fun stop(): Progress? {
        val p = progress ?: return null
        progress = null
        handler.removeCallbacks(tick)
        if (debugBuild) Log.d("GearLoadouts", "scan finished at %.1f s after ${p.looks} looks".format(p.seconds))
        return p
    }

    private fun look() {
        val sc = progress ?: return
        // a stopped scan's last look still finishing: try again shortly
        if (busy) { handler.postDelayed(tick, LOOK_EVERY_MS); return }
        if (System.currentTimeMillis() > ends) { listener.finished(); return }
        val data = data ?: return
        val grabStarted = System.nanoTime()
        capture.matchScreenSize()
        val shot = capture.grab()
        if (shot == null) { handler.postDelayed(tick, LOOK_EVERY_MS); return }
        val grabMs = (System.nanoTime() - grabStarted) / 1_000_000
        busy = true
        val number = ++sc.looks
        Thread {
            var book: Int? = null; var inventory: GameReading.InventoryReading? = null; var gear: GameReading.GearReading? = null
            var pouch: List<Loadout.Stack>? = null
            // how long each part of this look takes, for a test build's log
            val times = StringBuilder("look $number at %.1f s: screenshot $grabMs ms".format((grabStarted - sc.started) / 1e9))
            var t = System.nanoTime()
            fun lap(what: String) { times.append(", $what ${(System.nanoTime() - t) / 1_000_000}"); t = System.nanoTime() }
            try {
                val pixels = LightBoxReader.Pixels(shot)
                shot.recycle()   // the pixels are copied: the screenshot isn't needed any more
                lap("copy")
                reading.preload(data)
                lap("load pictures")
                val panel = PanelFinder.findPanel(pixels)
                lap("find panel${if (panel == null) " (none)" else ""}")
                // an open rune pouch's window dims the inventory behind it: it's only read with the window closed
                val pouchWindow = if (sc.pouch == null || sc.inventory == null) PanelFinder.runePouchWindow(pixels) else null
                lap("find pouch${if (pouchWindow == null) " (none)" else ""}")
                if (panel != null) {
                    if (sc.book == null) { book = reading.spellbook(pixels, panel, data); lap("spellbook${if (book == null) " (no)" else ""}") }
                    if (sc.inventory == null && pouchWindow == null && PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.INVENTORY_TAB)) {
                        inventory = reading.inventory(pixels, panel, data)
                        lap("inventory${if (inventory == null) " (rejected)" else ""}")
                    }
                    if (sc.gear == null && PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.EQUIPMENT_TAB)) {
                        gear = reading.equipment(pixels, panel, data)
                        lap("equipment${if (gear == null) " (rejected)" else ""}")
                    }
                }
                if (sc.pouch == null && sc.hasPouch != false && pouchWindow != null) {
                    pouch = reading.runePouch(pixels, pouchWindow, data)
                    lap("pouch${if (pouch == null) " (no)" else ""}")
                }
            } catch (e: Throwable) {
                // e.g. out of memory on an older phone: this look is skipped, the next one tries again
                times.append(", failed: $e")
            }
            if (debugBuild) Log.d("GearLoadouts", times.toString())
            handler.post {
                busy = false
                if (progress !== sc) return@post   // stopped meanwhile
                if (book != null) sc.book = book
                // An empty pouch (an X in every slot) is only believed when two looks in a row see it: a window caught
                // while it's still being drawn could show its Xs before its runes
                if (pouch?.isEmpty() == true && !sc.emptyPouchSeen) { sc.emptyPouchSeen = true; pouch = null }
                else sc.emptyPouchSeen = false
                if (pouch != null) sc.pouch = pouch
                // the same for an empty inventory: a tab's button can turn red a moment before its items are drawn
                val empty = inventory?.spaces?.all { it == null } == true
                val newInventory = if (empty && !sc.emptyInventorySeen) null else inventory
                sc.emptyInventorySeen = empty && newInventory == null
                var noPouch = false
                if (newInventory != null) {
                    sc.inventory = newInventory
                    // no rune pouch in the inventory: the loadout has none (unless one was read open: then there is one,
                    // and the inventory just read it as something else)
                    sc.hasPouch = sc.pouch != null || newInventory.spaces.any { ids -> ids?.firstOrNull()?.let { data.runePouchSize(it) } != null }
                    noPouch = sc.hasPouch == false
                }
                if (gear != null) sc.gear = gear
                if (book != null || newInventory != null || gear != null || pouch != null) {
                    if (debugBuild) {
                        val ticked = listOfNotNull(book?.let { "spellbook" }, newInventory?.let { "inventory" + if (noPouch) " (no pouch)" else "" },
                            gear?.let { "equipment" }, pouch?.let { "rune pouch" })
                        Log.d("GearLoadouts", "ticked at %.1f s: ${ticked.joinToString()}".format(sc.seconds))
                    }
                    listener.read(Look(book, newInventory, gear, pouch, noPouch))
                }
                if (progress !== sc) return@post   // the listener stopped it
                if (sc.done) listener.finished() else handler.postDelayed(tick, LOOK_EVERY_MS)
            }
        }.start()
    }
}
