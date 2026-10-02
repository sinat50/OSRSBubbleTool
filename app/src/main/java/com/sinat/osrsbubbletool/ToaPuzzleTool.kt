package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.sinat.osrsbubbletool.ToaPuzzles.Symbol
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// ToA Puzzle Helper: the five Path of Scabaras puzzles in the Tombs of Amascut. You tap in what you see
// on a small map of the room, and the answer is marked on the same map. What you've entered is kept while
// the bubble is running, so you can close the window between puzzles. "Read the screen" fills a map in by
// itself from one picture of the screen (ToaReader); you can still tap to correct it.
class ToaPuzzleTool(
    private val context: Context,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit,
    private val setWindowCompact: (Int) -> Unit   // shrink the window into a bottom corner, or back (BubbleService.COMPACT_…)
) {

    companion object {
        private const val HELP_SIZE_DP = 30       // the ? button
        private const val MATCH_INTERVAL_MS = 250L        // how often Watch looks at the matching boards
        private const val MATCH_WATCH_MAX_MS = 300_000L   // Watch stops by itself after 5 minutes
        // The symbols that can turn up in a solo raid (the other four pairs start matched)
        private val SOLO_SYMBOLS = listOf(Symbol.DIAMOND, Symbol.KNIVES, Symbol.STAR, Symbol.WIGGLE, Symbol.FOOT)
        private const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before the screenshot
        private const val WATCH_INTERVAL_MS = 80L         // how often the sequence tiles are checked
        private const val WATCH_FIRST_FLASH_MS = 20_000L  // how long to wait for you to press the button
        private const val WATCH_NEXT_FLASH_MS = 5_000L    // how long to wait for the next flash
        private val PARCHMENT = "#F2E3C0".toColorInt()
        private val DARK_BROWN = "#3E2C12".toColorInt()
        private val BUTTON_BROWN = "#8B6B3E".toColorInt()
        private val SELECTED = "#3E7A2E".toColorInt()
        private val PAPER = "#FFF8E6".toColorInt()
        private val FADED = "#8C7B5E".toColorInt()
        private val WARN = "#9C4A10".toColorInt()

        // The maps, in the game's colours: plates and symbol tiles are purple with yellow on them, and
        // anything lit (a plate, a flashing tile, a matched pair) glows pale
        private val TILE = "#6A5468".toColorInt()          // a purple plate or symbol tile
        private val TILE_HIDDEN = "#8C8780".toColorInt()   // a matching tile not stepped on yet (plain grey)
        private val TILE_LIT = "#FFF1C2".toColorInt()      // lit: pale and glowing
        private val YELLOW = "#E8B828".toColorInt()        // the yellow squares and symbols
        private val GLYPH_LIT = "#B08A20".toColorInt()     // a symbol on a lit tile
        private val OBELISK = "#5A4350".toColorInt()       // an unlit obelisk (dark)
        private val OBELISK_LIT = "#F2C4D4".toColorInt()   // a lit obelisk (light pink)
        private val STEP_GREEN = "#2EBD4E".toColorInt()    // "step here"
        private val GLOW = "#FFE7A0".toColorInt()          // the glow around a lit plate
        private val GLOW_CORE = "#FFFCEE".toColorInt()     // a lit plate's bright middle
        private val MISS_GREY = "#B0A8B0".toColorInt()

        // One colour per matching pair, so the two tiles of a pair are easy to find
        private val PAIR_COLOURS = listOf("#D0312D", "#1F6FD0", "#2E9E3E", "#C0600A", "#8E3FC0",
            "#0F9A9A", "#C03A8E", "#6B6B00", "#444444").map { it.toColorInt() }
    }

    private enum class Page(val title: String, val about: String) {
        MENU("", ""),
        LIGHT("Light puzzle", "Eight pressure plates in a ring: light them all"),
        ADDITION("Addition puzzle", "Symbols on the floor that add up to the tablet's number"),
        SEQUENCE("Sequence puzzle", "Tiles light up in turn: step on them in the same order"),
        OBELISK("Obelisk puzzle", "Hit the obelisks in the right order"),
        MATCHING("Matching puzzle", "Two boards of symbols: step on matching pairs")
    }

    private val prefs = context.getSharedPreferences("toa_puzzles", Context.MODE_PRIVATE)
    private lateinit var holder: FrameLayout
    private var scroll: ScrollView? = null
    private var page = Page.MENU
    private var helpOpen = false   // the page's instructions are showing (the ? button)

    // ---------------- What you've entered (kept while the bubble runs) ----------------

    private var lightLit = 0                          // lit plates, one bit each
    private var lightEntered = false                  // you've tapped in what's lit, so show the answer
    private var lightTurn = 0f                        // how far the map is turned to match the camera
    private var lightNote: String? = null             // what the last screen reading found
    private var additionTarget = 0                    // the tablet's number, 0 = not picked yet
    private var additionNote: String? = null          // what the last screen reading found
    private val additionLit = mutableSetOf<Int>()     // tiles already lit (walked on)
    private val sequence = mutableListOf<Int>()       // the tiles that lit up, in order
    private var sequenceTurn = 0f                     // how far the map is turned to match the camera
    private var sequenceNote: String? = null          // what the last watch found
    private val obeliskOrder = mutableListOf<Int>()   // the right order, as far as it's known
    private val obeliskMisses = mutableSetOf<Int>()   // wrong guesses for the next obelisk
    private val obeliskUndo = ArrayDeque<Pair<List<Int>, Set<Int>>>()
    private val matchSymbols = HashMap<Int, Symbol>() // what you've seen on each matching tile
    private val matchDone = mutableSetOf<Int>()       // matched tiles
    private var matchBrush = 0                        // what a tap does: 0-8 a symbol, 9 matched, 10 erase
    private var matchWatching = false                 // Watch is on
    private var matchNote: String? = null             // what Watch last said
    private val matchVotes = HashMap<Int, IntArray>() // per tile: how many pictures showed each symbol

    // Which way the addition map faces: how many quarter turns from north at the top. Starts with east
    // at the top, as you see the room walking in towards the exit.
    // Which way the obelisk map is turned, in quarter turns clockwise (0 = the walls at the top and bottom)
    private var obeliskTurns: Int
        get() = prefs.getInt("obelisk_turns", 0)
        set(v) { prefs.edit { putInt("obelisk_turns", v) } }

    private var additionTurns: Int
        get() = prefs.getInt("addition_turns", 1)
        set(v) { prefs.edit { putInt("addition_turns", v) } }

    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var reading = 0   // counts screen readings, so an old one that finishes late is ignored

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // The bubble is closing
    fun destroy() {
        matchWatching = false
        destroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The window's ◀ button: from a puzzle back to the list
    fun goBack() {
        if (page != Page.MENU) { stopMatchWatch(null); page = Page.MENU; helpOpen = false; show(keepScroll = false) }
    }

    // The window was closed: stop watching (nothing can be seen to update)
    fun onWindowClosed() = stopMatchWatch(null)

    private fun open(p: Page) { page = p; helpOpen = false; show(keepScroll = false) }

    // Rebuilds the page. Keeps the scroll position, so tapping the map doesn't jump the page.
    private fun show(keepScroll: Boolean = true) {
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        holder.removeAllViews()
        val s = scrolling {
            when (page) {
                Page.MENU -> menu()
                Page.LIGHT -> light()
                Page.ADDITION -> addition()
                Page.SEQUENCE -> sequence()
                Page.OBELISK -> obelisk()
                Page.MATCHING -> matching()
            }
        }
        scroll = s
        holder.addView(s)
        if (y > 0) s.post { s.scrollTo(0, y) }
    }

    // ---------------- The list ----------------

    private fun LinearLayout.menu() {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Tombs of Amascut: Path of Scabaras", 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(helpButton(), LinearLayout.LayoutParams(dp(HELP_SIZE_DP), dp(HELP_SIZE_DP)).apply { leftMargin = dp(4) })
        }, full())
        help("Pick the puzzle you're in. The light, addition, sequence and matching puzzles can read the screen; " +
            "you can also tap in what you see, and the answer is marked on the map.\n\nFor the best results " +
            "reading the screen: camera facing east, looking straight down, with the zoom set to 25%.")
        Page.entries.drop(1).forEach { p ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply { setColor(BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
                addView(label(p.title, 14f, bold = true).apply { setTextColor(Color.WHITE) })
                addView(label(p.about, 11f).apply { setTextColor("#F2E3C0".toColorInt()) })
                setOnClickListener { open(p) }
            }, full(6))
        }
    }

    // ---------------- Light puzzle ----------------

    private fun LinearLayout.light() {
        val answer = ToaPuzzles.solveLights(lightLit)
        addView(bar(button("Read the screen", SELECTED) { readLights() } to 2f,
            button("Clear") { lightLit = 0; lightEntered = false; lightTurn = 0f; lightNote = null; show() } to 1f), full())
        help("Stand still with all eight plates on screen and tap Read the screen (the bubble hides for a moment " +
            "while it looks). Or tap each plate that's lit in the game: it glows pale, with a beam of light. Then " +
            "step on the plates with a green border, in any order. Stepping on a plate switches it and the two " +
            "beside it. The map faces the direction nearest your camera. If a plate was read wrong, tap it.")
        lightNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        addView(Board(3, 3, (0 until 8).map { ToaPuzzles.lightCell(it) }, turn = lightTurn, onTap = { i ->
            lightLit = lightLit xor (1 shl i); lightEntered = true; show()
        }) { canvas, i, r ->
            if (lightLit shr i and 1 == 1) litPlate(canvas, r) else plate(canvas, r)
            if (lightEntered && answer shr i and 1 == 1) ring(canvas, r, STEP_GREEN)
        }, full(6))
        val steps = Integer.bitCount(answer)
        if (lightEntered) {
            addView(status(if (steps == 0) "All eight lit: done! ✓" else "Step on the $steps green plate${if (steps == 1) "" else "s"}, in any order."), full(6))
        } else {
            // the answer only shows once you've told it what's lit, so all eight don't start with borders
            addView(button("None are lit: show the answer") { lightEntered = true; show() }.apply { textSize = 12f }, full(6))
        }
    }

    private fun readLights() = readScreen({ lightNote = it }, ToaReader::readLights) { result ->
        if (result == null) {
            lightNote = "Couldn't find the plates. Stand still with all eight on screen, then try again."
        } else {
            lightLit = result.lit
            lightTurn = result.rotation
            lightEntered = true
            val n = Integer.bitCount(result.lit)
            lightNote = "Read $n lit plate${if (n == 1) "" else "s"}. Wrong? Tap a plate to fix it."
        }
    }

    private fun readAdditionNumber() = readScreen({ additionNote = it }, ToaReader::readAdditionNumber) { n ->
        if (n == null) {
            additionNote = "Couldn't find the number. Open the chat box and try again."
        } else {
            additionTarget = n
            additionNote = null
        }
    }

    // Takes one picture of the screen (with the bubble hidden), reads it with `read` away from the screen
    // (so the bubble doesn't stutter), then hands the result to `done` and redraws the page.
    // `note` shows what's happening meanwhile.
    private fun <T> readScreen(note: (String) -> Unit, read: (LightBoxReader.PixelSource) -> T?, done: (T?) -> Unit) {
        if (!capture.isActive) {
            note("Waiting for screen-capture permission...")
            show()
            capture.request { ok ->
                if (ok) readScreen(note, read, done) else { note("Couldn't turn on screen capture: ${capture.lastError}."); show() }
            }
            return
        }
        val id = ++reading
        note("Reading the screen...")
        show()
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            if (destroyed) return@postDelayed
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (id != reading) return@postDelayed
            if (shot == null) { note("Couldn't capture the screen. Try again."); show(); return@postDelayed }
            Thread {
                val result = read(LightBoxReader.Pixels(shot))
                if (result == null) saveForDebugging("read", shot)
                shot.recycle()
                handler.post {
                    if (destroyed || id != reading) return@post
                    done(result)
                    show()
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    // ---------------- Addition puzzle ----------------

    private fun LinearLayout.addition() {
        // The number, with − and + to correct it, and Read to take it from the chat box
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fun lp(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(2), 0, dp(2), 0) }
            addView(button("Read the number", SELECTED) { readAdditionNumber() }, lp(2.2f))
            addView(button("−") { changeTarget(-1) }, lp(0.8f))
            addView(label(if (additionTarget == 0) "–" else "$additionTarget", 20f, bold = true).apply { gravity = Gravity.CENTER }, lp(1f))
            addView(button("+") { changeTarget(1) }, lp(0.8f))
            addView(helpButton(), LinearLayout.LayoutParams(dp(HELP_SIZE_DP), dp(HELP_SIZE_DP)).apply { leftMargin = dp(2) })
        }, full())
        help("Read the tablet (it opens once the other puzzles are done) and open the chat box, so the line \"The " +
            "number … has been hastily chipped into the stone.\" shows. Then tap Read the number; − and + correct " +
            "it. Walk onto the green tiles one at a time, without crossing any others. If you go another way, tap " +
            "the tiles that lit up and a new walk is worked out. Going over the number hurts: when the tiles go " +
            "dark, tap Clear. Most players face east here; Turn ↻ turns the map to match your camera.")
        additionNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        if (additionTarget == 0) return

        val turns = additionTurns
        val top = listOf("north", "east", "south", "west")[turns]
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Top of the map: $top", 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Turn ↻") { additionTurns = (turns + 3) % 4; show() }.apply { textSize = 12f; setPadding(dp(8), dp(5), dp(8), dp(5)) })
            addView(button("Clear") { additionLit.clear(); show() }.apply { textSize = 12f; setPadding(dp(8), dp(5), dp(8), dp(5)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        }, full(6))

        val answer = ToaPuzzles.solveAddition(additionTarget, additionLit)
        // Screen square (row, column) → tile number on the north-up grid
        val cells = (0 until 25).map { i ->
            // where tile i shows on screen after the turns
            var r = i / 5; var c = i % 5
            repeat(turns) { val nr = 4 - c; c = r; r = nr }
            r to c
        }
        val values = Symbol.entries.map { "${it.value}" }
        addView(Board(5, 5, cells, onTap = { i ->
            if (!additionLit.add(i)) additionLit.remove(i)
            show()
        }) { canvas, i, r ->
            val sym = ToaPuzzles.ADDITION_GRID[i]
            val lit = i in additionLit
            tile(canvas, r, if (lit) TILE_LIT else TILE)
            glyph(canvas, sym, r, if (lit) GLYPH_LIT else YELLOW)
            text(canvas, values[sym.ordinal], r.left + r.width() * 0.17f, r.top + r.height() * 0.2f, r.height() * 0.22f, if (lit) DARK_BROWN else Color.WHITE)
            if (answer != null && i in answer) ring(canvas, r, STEP_GREEN)
        }, full(6))

        val total = ToaPuzzles.additionTotal(additionLit)
        addView(status(when {
            total == additionTarget -> "That's $additionTarget: done! ✓"
            answer == null && total > additionTarget -> "That's $total, over $additionTarget. When the tiles go dark, tap Clear."
            answer == null -> "No walk adds up from here. When the tiles go dark, tap Clear."
            else -> "Walk the green tiles one at a time. Went another way? Tap the tiles you lit."
        }), full(6))
    }

    // − and + next to the number: from nothing, − starts at the top and + at the bottom
    private fun changeTarget(by: Int) {
        additionTarget = if (additionTarget == 0) (if (by > 0) ToaPuzzles.ADDITION_MIN else ToaPuzzles.ADDITION_MAX)
            else (additionTarget + by).coerceIn(ToaPuzzles.ADDITION_MIN, ToaPuzzles.ADDITION_MAX)
        additionNote = null
        show()
    }

    // ---------------- Sequence puzzle ----------------

    private fun LinearLayout.sequence() {
        addView(bar(button("Watch", SELECTED) { watchSequence() } to 1.4f,
            button("Undo") { sequence.removeLastOrNull(); show() } to 1f,
            button("Clear") { sequence.clear(); sequenceTurn = 0f; sequenceNote = null; show() } to 1f), full())
        help("Stand still with all nine tiles on screen, tap Watch, then press the button in the game. The bubble " +
            "hides while it watches and comes back with the tiles numbered in order. Or tap each tile yourself as " +
            "it flashes (it glows pale, with a beam of light). Step on them from 1 to 5. Forgot it? Press the " +
            "button again for a new order, and tap Clear first.")
        sequenceNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        // Each tile's number(s) in the order, worked out here so drawing makes nothing new
        val numbers = (0 until 9).map { t -> sequence.indices.filter { sequence[it] == t }.joinToString(",") { "${it + 1}" } }
        addView(Board(5, 5, ToaPuzzles.SEQUENCE_CELLS, turn = sequenceTurn, onTap = { i ->
            if (sequence.size < ToaPuzzles.SEQUENCE_LENGTH) { sequence.add(i); show() }
        }) { canvas, i, r ->
            val n = numbers[i]
            if (n.isEmpty()) plate(canvas, r) else tile(canvas, r, TILE_LIT)
            if (n.isNotEmpty()) text(canvas, n, r.centerX(), r.centerY(), r.height() * if (n.length > 2) 0.3f else 0.5f, DARK_BROWN)
        }, full(6))
        addView(status(when (sequence.size) {
            0 -> "Waiting for the first tile."
            ToaPuzzles.SEQUENCE_LENGTH -> "Step on the tiles from 1 to ${ToaPuzzles.SEQUENCE_LENGTH}."
            else -> "${sequence.size} of ${ToaPuzzles.SEQUENCE_LENGTH} tiles."
        }), full(6))
    }

    // Finds the nine tiles in one picture, then hides the bubble and watches just those tiles (a few pixels
    // each, about 12 times a second) until five have flashed, or it gives up waiting
    private fun watchSequence() {
        if (!capture.isActive) {
            sequenceNote = "Waiting for screen-capture permission..."
            show()
            capture.request { ok ->
                if (ok) watchSequence() else { sequenceNote = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        val id = ++reading
        sequenceNote = "Looking for the tiles..."
        show()
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            if (destroyed || id != reading) { setOverlaysVisible(true); return@postDelayed }
            val shot = capture.grab()
            if (shot == null) { endWatch("Couldn't capture the screen. Try again."); return@postDelayed }
            Thread {
                val tiles = ToaReader.findSequenceTiles(LightBoxReader.Pixels(shot))
                if (tiles == null) saveForDebugging("sequence", shot)
                shot.recycle()
                handler.post {
                    if (destroyed || id != reading) return@post
                    if (tiles == null) {
                        endWatch("Couldn't find the tiles. Stand still with all nine on screen, then try again.")
                        return@post
                    }
                    sequence.clear()
                    sequenceTurn = tiles.rotation
                    watchTiles(id, tiles)
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    private fun watchTiles(id: Int, tiles: ToaReader.SequenceTiles) {
        val started = SystemClock.uptimeMillis()
        var lastFlash = 0L
        var lastLit = -1
        val watch = object : Runnable {
            override fun run() {
                if (destroyed || id != reading) return
                val now = SystemClock.uptimeMillis()
                if (capture.gameHidden) { endWatch("You left the game, so watching stopped."); return }
                // null = the screen hasn't changed since last time
                val lit = capture.sample { ToaReader.litSequenceTile(it, tiles) }
                if (lit != null) {
                    // a tile that has just lit up (the same tile twice goes dark in between)
                    if (lit != -1 && lit != lastLit) {
                        sequence.add(lit)
                        lastFlash = now
                        if (sequence.size == ToaPuzzles.SEQUENCE_LENGTH) {
                            endWatch("Saw all ${ToaPuzzles.SEQUENCE_LENGTH} flashes.")
                            return
                        }
                    }
                    lastLit = lit
                }
                when {
                    lastFlash == 0L && now - started > WATCH_FIRST_FLASH_MS ->
                        { endWatch("No tiles lit up. Tap Watch the tiles, then press the button in the game."); return }
                    lastFlash != 0L && now - lastFlash > WATCH_NEXT_FLASH_MS ->
                        { endWatch("Only saw ${sequence.size} of ${ToaPuzzles.SEQUENCE_LENGTH} flashes. Tap any missing tiles, " +
                            "or tap Clear and watch again."); return }
                }
                handler.postDelayed(this, WATCH_INTERVAL_MS)
            }
        }
        handler.post(watch)
    }

    private fun endWatch(note: String) {
        setOverlaysVisible(true)
        sequenceNote = note
        show()
    }

    // ---------------- Obelisk puzzle ----------------

    private fun LinearLayout.obelisk() {
        fun remember() { obeliskUndo.addLast(obeliskOrder.toList() to obeliskMisses.toSet()) }
        addView(bar(button("Undo") { obeliskUndo.removeLastOrNull()?.let { (o, m) ->
                obeliskOrder.clear(); obeliskOrder.addAll(o); obeliskMisses.clear(); obeliskMisses.addAll(m); show() } } to 1f,
            button("Clear") { remember(); obeliskOrder.clear(); obeliskMisses.clear(); show() } to 1f,
            button("Turn ↻") { obeliskTurns = (obeliskTurns + 1) % 4; show() } to 1f), full())
        help("After each hit, tap the obelisk if it lit up (it turns light pink), or hold your finger on it if it " +
            "didn't. Numbered obelisks are the right order so far; try one marked ? next. A wrong hit puts them all " +
            "out, so hit the numbered ones again first. The map shows the three obelisks on each wall; Turn ↻ " +
            "turns it a quarter turn at a time to match your camera.")
        val choices = ToaPuzzles.obeliskChoices(obeliskOrder, obeliskMisses)
        val numbers = (0 until ToaPuzzles.OBELISKS).map { o -> obeliskOrder.indexOf(o).let { if (it < 0) "" else "${it + 1}" } }
        // Top wall on the top row, bottom wall on the bottom row, the floor between them
        // (Turn ↻ moves them round a quarter turn at a time, so the walls can be at the sides instead)
        val cells = (0 until ToaPuzzles.OBELISKS).map { o ->
            var r = if (o < 3) 0 else 2; var c = o % 3
            repeat(obeliskTurns) { val nr = c; c = 2 - r; r = nr }
            r to c
        }
        addView(Board(3, 3, cells, maxHeightDp = 180,
            onLong = { o -> if (o !in obeliskOrder) { remember(); if (!obeliskMisses.add(o)) obeliskMisses.remove(o); show() } },
            onTap = { o -> if (o !in obeliskOrder) { remember(); obeliskOrder.add(o); obeliskMisses.clear(); show() } }
        ) { canvas, o, r ->
            val tall = obeliskRect(r)
            val n = numbers[o]
            tile(canvas, tall, if (n.isNotEmpty()) OBELISK_LIT else OBELISK)
            when {
                n.isNotEmpty() -> text(canvas, n, tall.centerX(), tall.centerY(), r.height() * 0.45f, DARK_BROWN)
                o in obeliskMisses -> text(canvas, "✗", tall.centerX(), tall.centerY(), r.height() * 0.45f, MISS_GREY)
                else -> text(canvas, "?", tall.centerX(), tall.centerY(), r.height() * 0.4f, Color.WHITE)
            }
        }, full(8))
        addView(status(when {
            obeliskOrder.isEmpty() && obeliskMisses.isEmpty() -> "Hit any obelisk to start."
            choices.isEmpty() || obeliskOrder.size == ToaPuzzles.OBELISKS -> "All found: hit them in order 1 to ${obeliskOrder.size}."
            obeliskOrder.isEmpty() -> "Try one of the obelisks marked ?."
            else -> "Hit the numbered ones in order, then try one marked ?."
        }), full(8))
    }

    // An obelisk is drawn tall and thin inside its square (one shared rectangle, so drawing makes nothing new)
    private val obeliskBox = RectF()
    private fun obeliskRect(r: RectF) = obeliskBox.apply { set(r.centerX() - r.width() * 0.22f, r.top, r.centerX() + r.width() * 0.22f, r.bottom) }

    // ---------------- Matching puzzle ----------------

    private fun LinearLayout.matching() {
        if (matchWatching) { matchingSmall(); return }
        addView(bar(button(if (matchWatching) "Stop" else "Watch", if (matchWatching) WARN else SELECTED) {
                if (matchWatching) stopMatchWatch(null) else watchMatching() } to 1.2f,
            button("Clear") { matchSymbols.clear(); matchDone.clear(); matchVotes.clear(); show() } to 1f), full())
        help("For Watch: camera facing east, looking straight down, with the zoom at 25%, so both boards and the " +
            "statue are on screen. The window shrinks into a corner, away from the boards, while it watches. Walk the tiles: each " +
            "symbol you reveal is filled in, and pairs found on both boards get the same colour border: step on " +
            "both. Or fill it in yourself: pick a symbol in the row under the boards, then tap the tile. ✓ marks a " +
            "pair as matched; ✗ rubs a tile out. Each board has each symbol once. Solo: the line, crook, hand and " +
            "bird pairs start matched.")
        matchNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }

        val pairs = ToaPuzzles.matchingPairs(matchSymbols, matchDone)
        addView(matchBoardsView(maxHeightDp = 150), full(8))

        // The brushes: nine symbols, then "matched" and "erase"
        val brushes = (0 until 11).map { 0 to it }
        addView(Board(1, 11, brushes, maxHeightDp = 44, onTap = { b -> matchBrush = b; show() }) { canvas, b, r ->
            tile(canvas, r, if (b == matchBrush) SELECTED else PAPER)
            when (b) {
                in 0..8 -> glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[b], r, if (b == matchBrush) YELLOW else GLYPH_LIT)
                9 -> text(canvas, "✓", r.centerX(), r.centerY(), r.height() * 0.55f, SELECTED)
                else -> text(canvas, "✗", r.centerX(), r.centerY(), r.height() * 0.55f, WARN)
            }
        }, full(6))

        val names = pairs.joinToString(", ") { matchSymbols[it.first]!!.label.lowercase() }
        addView(status(when {
            matchDone.size == 18 -> "All nine pairs matched: done! ✓"
            pairs.isEmpty() -> "No pairs found yet. Step on tiles to see their symbols."
            else -> "Pairs to step on: $names."
        }), full(8))
    }

    // ---- Watching the matching boards ----
    // About 4 times a second: one picture, found the boards in it (they move as you walk), and read each
    // tile. A symbol counts once two pictures agree. Stops by itself when all pairs are matched.

    private val watchPixels = ReusablePixels()
    private var watchBusy = false
    private var watchStarted = 0L
    private var lastBoards: ToaReader.MatchBoards? = null
    private var soloRaid: Boolean? = null
    private var compactCorner = BubbleService.COMPACT_RIGHT
    private var cornerMovedAt = 0L   // four pairs already matched on each board when watching began

    private fun watchMatching() {
        if (!capture.isActive) {
            matchNote = "Waiting for screen-capture permission..."
            show()
            capture.request { ok ->
                if (ok) watchMatching() else { matchNote = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        capture.matchScreenSize()
        matchWatching = true
        // over the inventory (not needed for this puzzle); it moves to the other corner if a board slides under it
        compactCorner = BubbleService.COMPACT_RIGHT
        setWindowCompact(compactCorner)
        matchNote = "Watching. Walk the tiles."
        lastBoards = null
        soloRaid = null
        watchStarted = SystemClock.uptimeMillis()
        show()
        handler.postDelayed(matchTick, MATCH_INTERVAL_MS)
    }

    private fun stopMatchWatch(note: String?) {
        if (!matchWatching) return
        matchWatching = false
        handler.removeCallbacks(matchTick)
        if (!destroyed) setWindowCompact(BubbleService.COMPACT_OFF)
        matchNote = note
        if (!destroyed && ::holder.isInitialized) show()
    }

    private val matchTick = object : Runnable {
        override fun run() {
            if (!matchWatching || destroyed) return
            if (capture.gameHidden) { stopMatchWatch("You left the game, so watching stopped."); return }
            if (SystemClock.uptimeMillis() - watchStarted > MATCH_WATCH_MAX_MS) { stopMatchWatch("Stopped after 5 minutes."); return }
            handler.postDelayed(this, MATCH_INTERVAL_MS)
            if (watchBusy) return   // still working on the last picture
            val shot = capture.grab() ?: return   // nothing new on screen
            // Ignore what's under this window: it's the app, not the game
            val loc = IntArray(2)
            holder.getLocationOnScreen(loc)
            val left = loc[0]; val top = loc[1]; val right = left + holder.width; val bottom = top + holder.height
            val shown = holder.isShown
            val skip = { x: Int, y: Int -> shown && x in left until right && y in top until bottom }
            val previous = lastBoards
            val solo = soloRaid
            watchBusy = true
            Thread {
                watchPixels.load(shot)
                val boards = ToaReader.findMatchBoards(watchPixels, skip, previous)
                if (boards == null) saveForDebugging("matching", shot)
                shot.recycle()
                val allowed = if (solo == true) SOLO_SYMBOLS else ToaPuzzles.MATCHING_SYMBOLS
                val tiles = boards?.let { b -> (0 until 18).map { ToaReader.readMatchTile(watchPixels, b, it, skip, allowed) } }
                handler.post {
                    watchBusy = false
                    if (!matchWatching || destroyed) return@post
                    lastBoards = boards ?: previous
                    boards?.let { moveOffBoards(it, left, top, right, bottom, shown) }
                    if (tiles == null) {
                        if (previous == null) showMatchNote("Can't see both boards. Zoom out, face east, and move this window off them.")
                        return@post
                    }
                    showMatchNote("Watching. Walk the tiles.")
                    if (soloRaid == null) {
                        // decided from the first clear look: solo raids start with four pairs matched
                        soloRaid = (0 until 18).count { tiles[it].state == ToaReader.TILE_MATCHED } >= 7
                    }
                    if (mergeTiles(tiles)) show()
                    if (matchDone.size == 18) stopMatchWatch("All nine pairs matched: done! ✓")
                }
            }.start()
        }
    }

    // If a tile is under the shrunk window, move the window to the other bottom corner (not more than once a second)
    private fun moveOffBoards(boards: ToaReader.MatchBoards, left: Int, top: Int, right: Int, bottom: Int, shown: Boolean) {
        if (!shown) return
        val now = SystemClock.uptimeMillis()
        if (now - cornerMovedAt < 1_000L) return
        val margin = hypot(boards.colX[0], boards.colY[0]) / 2f
        fun covers(l: Float, r: Float) = (0 until 18).any { t ->
            boards.tileX(t) in (l - margin)..(r + margin) && boards.tileY(t) in (top - margin)..(bottom + margin)
        }
        if (!covers(left.toFloat(), right.toFloat())) return
        // only if the other corner is clear (otherwise it would just hop back and forth)
        val screenW = context.resources.displayMetrics.widthPixels
        if (covers((screenW - right).toFloat(), (screenW - left).toFloat())) return
        cornerMovedAt = now
        compactCorner = if (compactCorner == BubbleService.COMPACT_RIGHT) BubbleService.COMPACT_LEFT else BubbleService.COMPACT_RIGHT
        setWindowCompact(compactCorner)
    }

    private fun showMatchNote(note: String) {
        if (matchNote == note) return
        matchNote = note
        show()
    }

    // Adds what one picture showed to the map. True if anything changed.
    private fun mergeTiles(tiles: List<ToaReader.MatchTile>): Boolean {
        var changed = false
        for (t in 0 until 18) {
            val tile = tiles[t]
            when (tile.state) {
                ToaReader.TILE_MATCHED -> if (matchDone.add(t)) changed = true
                ToaReader.TILE_SYMBOL -> {
                    val sym = tile.symbol ?: continue
                    val k = ToaPuzzles.MATCHING_SYMBOLS.indexOf(sym)
                    val votes = matchVotes.getOrPut(t) { IntArray(9) }
                    votes[k]++
                    // the symbol most pictures agreed on, once at least two did (or one was very clear)
                    val best = votes.indices.maxBy { votes[it] }
                    if (votes[best] < 2 && tile.sure < 0.8f) continue
                    val chosen = ToaPuzzles.MATCHING_SYMBOLS[best]
                    if (matchSymbols[t] == chosen) continue
                    // each board has each symbol once: keep whichever tile it was seen on more
                    val board = if (t < 9) 0 until 9 else 9 until 18
                    val rival = board.firstOrNull { it != t && matchSymbols[it] == chosen }
                    if (rival != null) {
                        if ((matchVotes[rival]?.get(best) ?: 0) > votes[best]) continue
                        matchSymbols.remove(rival)
                    }
                    matchSymbols[t] = chosen
                    changed = true
                }
            }
        }
        return changed
    }

    // Both boards: grey until a symbol is seen, purple with the symbol once seen, glowing once matched; the two
    // tiles of each pair found get the same colour border
    private fun matchBoardsView(maxHeightDp: Int): View {
        val pairs = ToaPuzzles.matchingPairs(matchSymbols, matchDone)
        val pairColour = HashMap<Int, Int>()
        pairs.forEach { (a, b) -> PAIR_COLOURS[ToaPuzzles.MATCHING_SYMBOLS.indexOf(matchSymbols[a])].let { pairColour[a] = it; pairColour[b] = it } }
        // Left board in columns 0-2, right board in columns 4-6 (the statue sits between them)
        val cells = (0 until 18).map { t -> (t % 9) / 3 to (t % 3) + if (t < 9) 0 else 4 }
        return Board(3, 7, cells, maxHeightDp = maxHeightDp, onTap = { t -> tapMatching(t); show() }) { canvas, t, r ->
            // grey until you've seen its symbol, purple with a yellow symbol once seen, glowing once matched
            val sym = matchSymbols[t]
            tile(canvas, r, when { t in matchDone -> TILE_LIT; sym != null -> TILE; else -> TILE_HIDDEN })
            sym?.let { glyph(canvas, it, r, if (t in matchDone) GLYPH_LIT else YELLOW) }
            pairColour[t]?.let { ring(canvas, r, it) }
        }
    }

    // The page while Watch is on (the window is shrunk into the corner): Stop, the boards and the pairs
    private fun LinearLayout.matchingSmall() {
        addView(bar(button("Stop", WARN) { stopMatchWatch(null) } to 1f), full())
        matchNote?.let { addView(label(it, 10f).apply { setTextColor(WARN) }, full(2)) }
        addView(matchBoardsView(maxHeightDp = 80), full(4))
        val pairs = ToaPuzzles.matchingPairs(matchSymbols, matchDone)
        addView(label(if (pairs.isEmpty()) "No pairs yet." else "Pairs: " + pairs.joinToString(", ") {
            matchSymbols[it.first]!!.label.lowercase() }, 12f, bold = true), full(4))
    }

    private fun tapMatching(t: Int) {
        when (matchBrush) {
            in 0..8 -> {
                val sym = ToaPuzzles.MATCHING_SYMBOLS[matchBrush]
                if (matchSymbols[t] == sym) { matchSymbols.remove(t); matchDone.remove(t); return }
                // each symbol is on a board only once, so take it off any other tile on this board
                val board = if (t < 9) 0 until 9 else 9 until 18
                board.filter { matchSymbols[it] == sym }.forEach { matchSymbols.remove(it); matchDone.remove(it) }
                matchSymbols[t] = sym
            }
            9 -> {
                val sym = matchSymbols[t] ?: return
                val pair = (0 until 18).filter { matchSymbols[it] == sym }
                if (t in matchDone) matchDone.removeAll(pair.toSet()) else matchDone.addAll(pair)
            }
            else -> { matchSymbols.remove(t); matchDone.remove(t) }
        }
    }

    // ---------------- Drawing the maps ----------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    private fun tile(canvas: Canvas, r: RectF, colour: Int) {
        fill.color = colour
        val corner = r.width() * 0.12f
        canvas.drawRoundRect(r, corner, corner, fill)
    }

    // An unlit plate, as in the game: a solid yellow square on a purple tile
    private val plateSquare = RectF()
    private fun plate(canvas: Canvas, r: RectF) {
        tile(canvas, r, TILE)
        val inset = r.width() * 0.27f
        plateSquare.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.color = YELLOW
        canvas.drawRect(plateSquare, fill)
    }

    // A lit plate: the same plate, with its square glowing bright and a soft glow around it
    private fun litPlate(canvas: Canvas, r: RectF) {
        tile(canvas, r, TILE)
        var inset = r.width() * 0.12f
        plateSquare.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.color = GLOW
        val corner = r.width() * 0.1f
        canvas.drawRoundRect(plateSquare, corner, corner, fill)
        inset = r.width() * 0.27f
        plateSquare.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.color = GLOW_CORE
        canvas.drawRect(plateSquare, fill)
    }

    private fun ring(canvas: Canvas, r: RectF, colour: Int) {
        stroke.color = colour
        stroke.strokeWidth = r.width() * 0.1f
        val corner = r.width() * 0.12f
        canvas.drawRoundRect(r, corner, corner, stroke)
    }

    // How far the map being drawn is turned, so writing on it can be turned back to read upright
    private var drawingTurn = 0f

    private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int) {
        textPaint.color = colour
        textPaint.textSize = size
        canvas.withRotation(-drawingTurn, x, y) {
            drawText(s, x, y - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        }
    }

    // The carved symbols, drawn in a 1×1 square and scaled to the tile
    private class Glyph(val path: Path, val outline: Boolean, val width: Float = 0f)

    private val glyphs: Map<Symbol, Glyph> by lazy {
        fun poly(vararg p: Float, close: Boolean = true) = Path().apply {
            moveTo(p[0], p[1])
            for (k in 2 until p.size step 2) lineTo(p[k], p[k + 1])
            if (close) close()
        }
        mapOf(
            Symbol.LINE to Glyph(Path().apply { addRect(0.42f, 0.16f, 0.58f, 0.84f, Path.Direction.CW) }, false),
            // two knives: a blade pointing left with a straight back, and the handle running down
            Symbol.KNIVES to Glyph(poly(0.33f, 0.15f, 0.44f, 0.15f, 0.44f, 0.85f, 0.32f, 0.85f, 0.32f, 0.6f, 0.16f, 0.48f).apply {
                addPath(poly(0.73f, 0.15f, 0.84f, 0.15f, 0.84f, 0.85f, 0.72f, 0.85f, 0.72f, 0.6f, 0.56f, 0.48f)) }, false),
            Symbol.TRIANGLE to Glyph(poly(0.18f, 0.3f, 0.82f, 0.3f, 0.5f, 0.76f), false),
            Symbol.DIAMOND to Glyph(poly(0.5f, 0.22f, 0.82f, 0.5f, 0.5f, 0.78f, 0.18f, 0.5f), true, 0.1f),
            Symbol.HAND to Glyph(Path().apply {
                addRect(0.34f, 0.4f, 0.72f, 0.85f, Path.Direction.CW)    // palm
                addRect(0.38f, 0.15f, 0.70f, 0.42f, Path.Direction.CW)   // fingers
                addRect(0.18f, 0.42f, 0.36f, 0.62f, Path.Direction.CW)   // thumb
            }, false),
            Symbol.BIRD to Glyph(poly(0.18f, 0.36f, 0.36f, 0.2f, 0.5f, 0.28f, 0.86f, 0.72f, 0.5f, 0.68f, 0.34f, 0.46f).apply {
                addRect(0.47f, 0.66f, 0.56f, 0.86f, Path.Direction.CW) }, false),
            Symbol.CROOK to Glyph(poly(0.38f, 0.85f, 0.38f, 0.2f, 0.68f, 0.2f, 0.68f, 0.44f, close = false), true, 0.11f),
            Symbol.WIGGLE to Glyph(poly(0.12f, 0.42f, 0.31f, 0.6f, 0.5f, 0.42f, 0.69f, 0.6f, 0.88f, 0.42f, close = false), true, 0.09f),
            Symbol.FOOT to Glyph(poly(0.42f, 0.15f, 0.75f, 0.15f, 0.75f, 0.85f, 0.22f, 0.85f, 0.22f, 0.72f, 0.42f, 0.6f), false),
            Symbol.STAR to Glyph(poly(0.5f, 0.12f, 0.6f, 0.4f, 0.88f, 0.5f, 0.6f, 0.6f, 0.5f, 0.88f, 0.4f, 0.6f, 0.12f, 0.5f, 0.4f, 0.4f), false)
        )
    }

    private fun glyph(canvas: Canvas, s: Symbol, r: RectF, colour: Int) {
        val g = glyphs.getValue(s)
        canvas.withTranslation(r.left, r.top) {
            scale(r.width(), r.height())
            if (g.outline) {
                stroke.color = colour
                stroke.strokeWidth = g.width
                drawPath(g.path, stroke)
            } else {
                fill.color = colour
                drawPath(g.path, fill)
            }
        }
    }

    // A map of square cells. Each cell sits at a (row, column) on the grid; empty squares are left blank.
    // Tapping a cell calls onTap with its number; holding it calls onLong (if given).
    @SuppressLint("ViewConstructor")
    private inner class Board(
        private val rows: Int,
        private val cols: Int,
        private val cells: List<Pair<Int, Int>>,
        private val maxHeightDp: Int = 230,
        private val turn: Float = 0f,   // degrees clockwise, so the map can match the camera's angle
        private val onLong: ((Int) -> Unit)? = null,
        private val onTap: (Int) -> Unit,
        private val drawCell: (Canvas, Int, RectF) -> Unit
    ) : View(context) {
        private val rects = List(cells.size) { RectF() }

        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { cellAt(e)?.let(onTap); return true }
            override fun onLongPress(e: MotionEvent) { val l = onLong ?: return; cellAt(e)?.let(l) }
        }).apply { setIsLongpressEnabled(onLong != null) }

        private val turnCos = cos(Math.toRadians(turn.toDouble())).toFloat()
        private val turnSin = sin(Math.toRadians(turn.toDouble())).toFloat()

        // Which cell a touch is on (turning the touch back the other way if the map is turned)
        private fun cellAt(e: MotionEvent): Int? {
            val dx = e.x - width / 2f
            val dy = e.y - height / 2f
            val x = width / 2f + dx * turnCos + dy * turnSin
            val y = height / 2f - dx * turnSin + dy * turnCos
            return rects.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cell = minOf(w.toFloat() / cols, dp(maxHeightDp).toFloat() / rows)
            setMeasuredDimension(w, (cell * rows).toInt())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            // a turned map is drawn smaller so its corners still fit
            val fit = 1f / (abs(turnCos) + abs(turnSin))
            val cell = minOf(w.toFloat() / cols, h.toFloat() / rows) * fit
            val left = (w - cell * cols) / 2f
            val top = (h - cell * rows) / 2f
            val pad = cell * 0.06f
            cells.forEachIndexed { i, (r, c) ->
                rects[i].set(left + c * cell + pad, top + r * cell + pad, left + (c + 1) * cell - pad, top + (r + 1) * cell - pad)
            }
        }

        override fun onDraw(canvas: Canvas) {
            drawingTurn = turn
            canvas.withRotation(turn, width / 2f, height / 2f) {
                for (i in rects.indices) drawCell(this, i, rects[i])
            }
            drawingTurn = 0f
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }
    }

    // Test builds only (never the released app): keeps pictures the readers couldn't make sense of, so they can
    // be copied to a computer and looked at (adb shell run-as com.sinat.osrsbubbletool ls cache/toa_debug).
    // At most one every 2 seconds, and only the newest 30.
    private val debugBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    @Volatile private var lastDebugSave = 0L

    private fun saveForDebugging(kind: String, shot: Bitmap) {
        if (!debugBuild) return
        val now = SystemClock.uptimeMillis()
        if (now - lastDebugSave < 2_000L) return
        lastDebugSave = now
        try {
            val dir = File(context.cacheDir, "toa_debug").apply { mkdirs() }
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(29)?.forEach { it.delete() }
            File(dir, "${kind}_${System.currentTimeMillis()}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (_: Exception) {
            // only a test aid: never let it get in the way
        }
    }

    private class ReusablePixels : LightBoxReader.PixelSource {
        private var data = IntArray(0)
        override var width = 0
        override var height = 0
        fun load(b: Bitmap) {
            width = b.width; height = b.height
            if (data.size != width * height) data = IntArray(width * height)
            b.getPixels(data, 0, width, 0, 0, width, height)
        }
        override fun rgb(x: Int, y: Int) = data[y * width + x]
    }

    // ---------------- Small building blocks ----------------

    private fun scrolling(build: LinearLayout.() -> Unit) = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
            build()
        })
    }

    private fun choiceGrid(c: LinearLayout, options: List<String>, picked: Int, perRow: Int, onPick: (Int) -> Unit) {
        options.chunked(perRow).forEachIndexed { rowIndex, row ->
            c.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { j, text ->
                    val i = rowIndex * perRow + j
                    addView(button(text, if (i == picked) SELECTED else BUTTON_BROWN) { onPick(i) }
                        .apply { textSize = 11f; setPadding(dp(2), dp(7), dp(2), dp(7)) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
                repeat(perRow - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, full(3))
        }
    }

    private fun status(text: String) = label(text, 13f, bold = true)

    // A row of buttons, each with how much of the width it gets, and the ? at the end
    private fun bar(vararg items: Pair<View, Float>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        items.forEach { (v, weight) ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        addView(helpButton(), LinearLayout.LayoutParams(dp(HELP_SIZE_DP), dp(HELP_SIZE_DP)).apply { leftMargin = dp(2) })
    }

    // The small ? that shows or hides the page's instructions
    private fun helpButton() = TextView(context).apply {
        text = "?"
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (helpOpen) Color.WHITE else DARK_BROWN)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (helpOpen) BUTTON_BROWN else PAPER)
            setStroke(dp(1), BUTTON_BROWN)
        }
        setOnClickListener { helpOpen = !helpOpen; show() }
    }

    // The instructions, only while the ? is on
    private fun LinearLayout.help(text: String) {
        if (!helpOpen) return
        addView(label(text, 12f).apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
        }, full(6))
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
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
