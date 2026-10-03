package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
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
import android.view.WindowManager
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
import kotlin.math.sin

// ToA Puzzle Helper: the five Path of Scabaras puzzles in the Tombs of Amascut. You tap in what you see
// on a small map of the room, and the answer is marked on the same map. What you've entered is kept while
// the bubble is running, so you can close the window between puzzles. "Read the screen" fills a map in by
// itself from one picture of the screen (ToaReader); you can still tap to correct it.
class ToaPuzzleTool(
    private val context: Context,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit,
    private val setWindowCompact: (Int) -> Unit   // shrink the window into a corner, or back (BubbleService.COMPACT_…)
) {

    companion object {
        private const val HELP_SIZE_DP = 30       // the ? button
        private const val MAP_CELL_DP = 26        // a square of the boards drawn at the bottom while watching
        private const val ICON_W_DP = 64          // the little board pictures on the list of puzzles
        private const val ICON_H_DP = 44
        private const val MATCH_INTERVAL_MS = 333L        // how often Watch looks at the matching boards (3 times a second)
        private const val MATCH_QUICK_MS = 40L            // …or this soon after a look that saw a symbol not settled yet
        private const val MATCH_QUICK_LOOKS = 3           // at most this many quick looks in a row (a symbol you stand on
                                                          // that can't be made out mustn't keep it looking flat out)
        private const val MATCH_WATCH_MAX_MS = 300_000L   // Watch stops by itself after 5 minutes
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
    private var watchingSequence = false              // Watch is following the flashes
    private var sequenceSecondsLeft = 0               // seconds left to press the button (0 once the first tile flashed)
    private var sequenceCountdown: TextView? = null   // where those seconds show, next to the Stop button
    private var sequenceTurn = 0f                     // how far the map is turned to match the camera
    private var sequenceNote: String? = null          // what the last watch found
    private val obeliskOrder = mutableListOf<Int>()   // the right order, as far as it's known
    private val obeliskMisses = mutableSetOf<Int>()   // wrong guesses for the next obelisk
    private val obeliskUndo = ArrayDeque<Pair<List<Int>, Set<Int>>>()
    // what you've seen on each matching tile, which are matched, and what Watch remembers between pictures
    // (the same code the PC replay test uses)
    private val memory = ToaReader.MatchMemory()
    private var matchBrush = 0                       // what a tap does: 0-8 a symbol, 9 matched, 10 erase
    private var matchManual = false                   // the symbols to tap in by hand are showing
    private var lightManual = false                   // each page's "Solve manually" is open (its map can be tapped)
    private var additionManual = false
    private var sequenceManual = false
    private var matchWatching = false                 // Watch is on
    private var matchNote: String? = null             // what Watch last said

    // Which way the obelisk map is turned, in quarter turns clockwise (0 = the walls at the top and bottom)
    private var obeliskTurns: Int
        get() = prefs.getInt("obelisk_turns", 0)
        set(v) { prefs.edit { putInt("obelisk_turns", v) } }

    // Which way the addition map faces: how many quarter turns from north at the top. Starts with east
    // at the top, as you see the room walking in towards the exit.
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
        onScreen = false
        hideMapOverlay()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        // that may have thrown away a finished look's "give the memory back" step, so give it back now
        // (a look still being worked on gives it back again when it finishes)
        watchBusy = false
        freeWatchMemory()
    }

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The window's ◀ button: from a puzzle back to the list
    fun goBack() {
        if (page != Page.MENU) { stopMatchWatch(null); cancelReading(); leaveOnScreen(); page = Page.MENU; helpOpen = false; show(keepScroll = false) }
    }

    // The window was closed: stop watching and reading (nothing can be seen to update) and take the map off the screen
    fun onWindowClosed() {
        stopMatchWatch(null)
        cancelReading()
        if (onScreen) { onScreen = false; hideMapOverlay() }
    }

    // The phone turned: the puzzle has moved on screen, so watching where it was is no use
    fun onRotated() {
        if (watchingSequence) endWatch("Screen rotated. Tap Watch to start again.") else cancelReading()
        stopMatchWatch("Screen rotated. Tap Watch to start again.")
    }

    // Stops a screen reading or sequence watch in progress (its result is ignored when it comes in), including one
    // still waiting for screen-capture permission
    private fun cancelReading() {
        reading++
        if (watchingSequence) { watchingSequence = false; hideForPicture(false) }
        // notes that said it was busy ("Reading the screen...") would otherwise stay up for good
        fun idle(note: String?) = note?.takeUnless { it.endsWith("...") }
        lightNote = idle(lightNote); additionNote = idle(additionNote); sequenceNote = idle(sequenceNote); matchNote = idle(matchNote)
    }

    // Hides the bubble, this window and the map at the bottom while a picture is taken, so none of them is
    // mistaken for the game (or shows them again)
    private fun hideForPicture(hide: Boolean) {
        setOverlaysVisible(!hide)
        mapOverlay?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
    }

    // Shows the page's answer at the bottom of the screen (just the map) and shrinks the window to a few buttons
    // in a corner: bottom-left for the addition puzzle (its number is read from the chat, top-left), else top-left
    private fun showOnScreen() {
        onScreen = true
        setWindowCompact(if (page == Page.ADDITION) BubbleService.COMPACT_BOTTOM_LEFT else BubbleService.COMPACT_TOP_LEFT)
        showMapOverlay()
        show()
    }

    // Done: the full window back, the map off the screen
    private fun leaveOnScreen() {
        if (!onScreen) return
        onScreen = false
        hideMapOverlay()
        setWindowCompact(BubbleService.COMPACT_OFF)
        show()
    }

    private fun open(p: Page) { page = p; helpOpen = false; show(keepScroll = false) }

    // Rebuilds the page. Keeps the scroll position, so tapping the map doesn't jump the page.
    private fun show(keepScroll: Boolean = true) {
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        holder.removeAllViews()
        val s = scrolling {
            when (page) {
                Page.MENU -> menu()
                Page.LIGHT -> if (onScreen) lightSmall() else light()
                Page.ADDITION -> if (onScreen) additionSmall() else addition()
                Page.SEQUENCE -> if (onScreen) sequenceSmall() else sequence()
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { setColor(BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
                // a little picture of the puzzle's board, then its name
                addView(puzzleIcon(p), LinearLayout.LayoutParams(dp(ICON_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(p.title, 14f, bold = true).apply { setTextColor(Color.WHITE) })
                    addView(label(p.about, 11f).apply { setTextColor("#F2E3C0".toColorInt()) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { open(p) }
            }, full(6))
        }
    }

    // A small picture of a puzzle's board for the list, drawn like its map part-way through the puzzle.
    // Tapping it opens the puzzle, like the rest of the button.
    private fun puzzleIcon(p: Page): View {
        val tap = { _: Int -> open(p) }
        return when (p) {
            // the ring of eight plates, three of them lit
            Page.LIGHT -> Board(3, 3, (0 until 8).map { ToaPuzzles.lightCell(it) }, maxHeightDp = ICON_H_DP, onTap = tap) { canvas, i, r ->
                if (i == 1 || i == 4 || i == 6) litPlate(canvas, r) else plate(canvas, r)
            }
            // the floor of symbols, with a short walk lit
            Page.ADDITION -> Board(5, 5, (0 until 25).map { it / 5 to it % 5 }, maxHeightDp = ICON_H_DP, onTap = tap) { canvas, i, r ->
                val lit = i == 12 || i == 17 || i == 22
                tile(canvas, r, if (lit) TILE_LIT else TILE)
                glyph(canvas, ToaPuzzles.ADDITION_GRID[i], r, if (lit) GLYPH_LIT else YELLOW)
            }
            // the diamond of nine tiles, two of them flashing
            Page.SEQUENCE -> Board(5, 5, ToaPuzzles.SEQUENCE_CELLS, maxHeightDp = ICON_H_DP, onTap = tap) { canvas, i, r ->
                if (i == 2 || i == 6) tile(canvas, r, TILE_LIT) else plate(canvas, r)
            }
            // three obelisks on each wall, two lit pink
            Page.OBELISK -> Board(3, 3, (0 until ToaPuzzles.OBELISKS).map { o -> (if (o < 3) 0 else 2) to o % 3 },
                maxHeightDp = ICON_H_DP, onTap = tap) { canvas, o, r ->
                tile(canvas, obeliskRect(r), if (o == 0 || o == 4) OBELISK_LIT else OBELISK)
            }
            // both boards: mostly grey, two symbols showing and one matched pair glowing
            Page.MATCHING -> Board(3, 7, (0 until 18).map { t -> (t % 9) / 3 to (t % 3) + if (t < 9) 0 else 4 },
                maxHeightDp = ICON_H_DP, onTap = tap) { canvas, t, r ->
                when (t) {
                    0, 14 -> { tile(canvas, r, TILE_LIT); glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[0], r, GLYPH_LIT) }
                    4 -> { tile(canvas, r, TILE); glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[3], r, YELLOW) }
                    10 -> { tile(canvas, r, TILE); glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[6], r, YELLOW) }
                    else -> tile(canvas, r, TILE_HIDDEN)
                }
            }
            Page.MENU -> View(context)
        }
    }

    // ---------------- Light puzzle ----------------

    private fun LinearLayout.light() {
        val answer = ToaPuzzles.solveLights(lightLit)
        tip("close your chat and inventory first, so all eight plates can be seen.")
        addView(bar(button("Read the screen", SELECTED) { readLights() } to 2f,
            button("Clear") { lightLit = 0; lightEntered = false; lightTurn = 0f; lightNote = null; show() } to 1f), full(4))
        help("Stand still with all eight plates on screen and tap Read the screen (the bubble hides for a moment " +
            "while it looks). The answer appears at the bottom of the screen and this window shrinks to a corner; " +
            "Done brings it back. Or tap Solve manually and tap each plate that's lit in the game: it glows pale, with a beam of light. Then " +
            "step on the plates with a green border, in any order. Stepping on a plate switches it and the two " +
            "beside it. The map faces the direction nearest your camera. If a plate was read wrong, tap it.")
        lightNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        addView(lightBoardView(230), full(6))
        val steps = Integer.bitCount(answer)
        if (lightEntered) {
            addView(status(if (steps == 0) "All eight lit: done! ✓" else "Step on the $steps green plate${if (steps == 1) "" else "s"}, in any order."), full(6))
        }
        manualButton(lightManual) { lightManual = !lightManual }
        if (lightManual) {
            addView(label("Tap each plate that's lit in the game.", 11f), full(4))
            // the answer only shows once you've told it what's lit, so all eight don't start with borders
            if (!lightEntered) addView(button("None are lit: show the answer") { lightEntered = true; show() }.apply { textSize = 12f }, full(4))
        }
    }

    // The ring of plates: lit ones glow, the ones to step on have a green border
    private fun lightBoardView(maxHeightDp: Int): View {
        val answer = ToaPuzzles.solveLights(lightLit)
        return Board(3, 3, (0 until 8).map { ToaPuzzles.lightCell(it) }, maxHeightDp = maxHeightDp, turn = lightTurn, onTap = { i ->
            if (lightManual) { lightLit = lightLit xor (1 shl i); lightEntered = true; show() }
        }) { canvas, i, r ->
            if (lightLit shr i and 1 == 1) litPlate(canvas, r) else plate(canvas, r)
            if (lightEntered && answer shr i and 1 == 1) stepHere(canvas, r)
        }
    }

    // The window while the answer is at the bottom of the screen
    private fun LinearLayout.lightSmall() {
        addView(smallRow(smallButton("Read again", SELECTED) { readLights() }, smallButton("Done", WARN) { leaveOnScreen() }))
        lightNote?.takeIf { it.startsWith("Couldn't") }?.let { addView(label(it, 9f).apply { setTextColor(WARN) }, full(2)) }
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
            // the answer goes to the bottom of the screen
            if (onScreen) refreshMapOverlay() else showOnScreen()
        }
    }

    private fun readAdditionNumber() = readScreen({ additionNote = it }, ToaReader::readAdditionNumber) { n ->
        if (n == null) {
            additionNote = "Couldn't find the number. Open the chat box and try again."
        } else {
            additionTarget = n
            additionNote = null
            if (onScreen) refreshMapOverlay() else showOnScreen()
        }
    }

    // Takes one picture of the screen (with the bubble hidden), reads it with `read` away from the screen
    // (so the bubble doesn't stutter), then hands the result to `done` and redraws the page.
    // `note` shows what's happening meanwhile.
    private fun <T> readScreen(note: (String) -> Unit, read: (LightBoxReader.PixelSource) -> T?, done: (T?) -> Unit) {
        if (!capture.isActive) {
            note("Waiting for screen-capture permission...")
            show()
            val asked = reading
            capture.request { ok ->
                if (destroyed || asked != reading) return@request   // closed or cancelled while asking
                if (ok) readScreen(note, read, done) else { note("Couldn't turn on screen capture: ${capture.lastError}."); show() }
            }
            return
        }
        val id = ++reading
        note("Reading the screen...")
        show()
        capture.matchScreenSize()
        hideForPicture(true)
        handler.postDelayed({
            if (destroyed) return@postDelayed
            val shot = capture.grab()
            hideForPicture(false)
            if (id != reading) { shot?.recycle(); return@postDelayed }   // a newer reading has started
            if (shot == null) { note("Couldn't capture the screen. Try again."); show(); return@postDelayed }
            Thread {
                // the picture's memory is given back as soon as it's been read, even if reading goes wrong
                val result = try {
                    read(LightBoxReader.Pixels(shot)).also { if (it == null) saveForDebugging("read", shot) }
                } catch (_: Exception) { null } finally { shot.recycle() }
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
        tip("open your chat to the Game tab, so the tablet's number shows.")
        // The number, and Read to take it from the chat box
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            fun lp(weight: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(2), 0, dp(2), 0) }
            addView(button("Read the number", SELECTED) { readAdditionNumber() }, lp(2.2f))
            addView(label(if (additionTarget == 0) "–" else "$additionTarget", 20f, bold = true).apply { gravity = Gravity.CENTER }, lp(1f))
            addView(helpButton(), LinearLayout.LayoutParams(dp(HELP_SIZE_DP), dp(HELP_SIZE_DP)).apply { leftMargin = dp(2) })
        }, full(4))
        help("Read the tablet (it opens once the other puzzles are done) and open the chat box to the Game tab, so the line \"The " +
            "number … has been hastily chipped into the stone.\" shows. Then tap Read the number; Solve manually " +
            "has − and + to correct it. The walk appears at the bottom of the screen, and this window shrinks to the bottom-left corner, clear " +
            "of the chat; Done brings it back. Walk onto the green tiles one at a time, without crossing any others. If you go another way, open Solve manually and tap " +
            "the tiles that lit up and a new walk is worked out. Going over the number hurts: when the tiles go " +
            "dark, tap Clear. Most players face east here; Turn ↻ turns the map to match your camera.")
        additionNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        // By hand: − and + set the number, and tiles you've lit can be tapped on the map
        manualButton(additionManual) { additionManual = !additionManual }
        if (additionManual) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Number:", 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("−") { changeTarget(-1) }.apply { setPadding(dp(16), dp(5), dp(16), dp(5)) })
                addView(button("+") { changeTarget(1) }.apply { setPadding(dp(16), dp(5), dp(16), dp(5)) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
            }, full(4))
            if (additionTarget != 0) addView(label("Went another way? Tap the tiles you lit on the map.", 11f), full(4))
        }
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
        addView(additionBoardView(230), full(6))

        val total = ToaPuzzles.additionTotal(additionLit)
        addView(status(when {
            total == additionTarget -> "That's $additionTarget: done! ✓"
            answer == null && total > additionTarget -> "That's $total, over $additionTarget. When the tiles go dark, tap Clear."
            answer == null -> "No walk adds up from here. When the tiles go dark, tap Clear."
            else -> "Walk the green tiles one at a time."
        }), full(6))
    }

    // The floor of symbols, with the walk's tiles in green
    private fun additionBoardView(maxHeightDp: Int): View {
        val answer = ToaPuzzles.solveAddition(additionTarget, additionLit)
        val turns = additionTurns
        // Screen square (row, column) → tile number on the north-up grid
        val cells = (0 until 25).map { i ->
            // where tile i shows on screen after the turns
            var r = i / 5; var c = i % 5
            repeat(turns) { val nr = 4 - c; c = r; r = nr }
            r to c
        }
        val values = Symbol.entries.map { "${it.value}" }
        return Board(5, 5, cells, maxHeightDp = maxHeightDp, onTap = { i ->
            if (additionManual) {
                if (!additionLit.add(i)) additionLit.remove(i)
                show()
            }
        }) { canvas, i, r ->
            val sym = ToaPuzzles.ADDITION_GRID[i]
            val lit = i in additionLit
            tile(canvas, r, if (lit) TILE_LIT else TILE)
            glyph(canvas, sym, r, if (lit) GLYPH_LIT else YELLOW)
            text(canvas, values[sym.ordinal], r.left + r.width() * 0.17f, r.top + r.height() * 0.2f, r.height() * 0.22f, if (lit) DARK_BROWN else Color.WHITE)
            if (answer != null && i in answer) stepHere(canvas, r)
        }
    }

    // The window while the walk is at the bottom of the screen (bottom-left, clear of the chat box)
    private fun LinearLayout.additionSmall() {
        addView(smallRow(smallButton("−") { changeTarget(-1) },
            label("$additionTarget", 16f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0) },
            smallButton("+") { changeTarget(1) },
            smallButton("Read", SELECTED) { readAdditionNumber() },
            smallButton("Done", WARN) { leaveOnScreen() }))
        additionNote?.takeIf { it.startsWith("Couldn't") }?.let { addView(label(it, 9f).apply { setTextColor(WARN) }, full(2)) }
    }

    // − and + next to the number: from nothing, − starts at the top and + at the bottom
    private fun changeTarget(by: Int) {
        additionTarget = if (additionTarget == 0) (if (by > 0) ToaPuzzles.ADDITION_MIN else ToaPuzzles.ADDITION_MAX)
            else (additionTarget + by).coerceIn(ToaPuzzles.ADDITION_MIN, ToaPuzzles.ADDITION_MAX)
        additionNote = null
        show()
        refreshMapOverlay()
    }

    // ---------------- Sequence puzzle ----------------

    private fun LinearLayout.sequence() {
        tip("close your chat and inventory first, so all nine tiles can be seen.")
        addView(bar(button("Watch", SELECTED) { watchSequence() } to 1.4f,
            button("Clear") { sequence.clear(); sequenceTurn = 0f; sequenceNote = null; show() } to 1f), full(4))
        // Watch finds the tiles once, when tapped, then keeps looking at those spots on the screen
        addView(label("Tap Watch where you'll press the button, then keep the camera still until the tiles flash " +
            "(it waits ${WATCH_FIRST_FLASH_MS / 1000} seconds).", 10f), full(4))
        help("Stand still with all nine tiles on screen, tap Watch, then press the button in the game. Watch finds " +
            "the tiles when you tap it, so don't walk, turn or zoom the camera until they've flashed. This window " +
            "shrinks to a Stop button and the tiles are numbered at the bottom of the screen as they flash; Done " +
            "brings the window back. Or tap Solve manually and tap each tile yourself as " +
            "it flashes (it glows pale, with a beam of light). Step on them from 1 to 5. Forgot it? Press the " +
            "button again for a new order, and tap Clear first.")
        sequenceNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
        addView(sequenceBoardView(230), full(6))
        addView(status(when (sequence.size) {
            0 -> "Waiting for the first tile."
            ToaPuzzles.SEQUENCE_LENGTH -> "Step on the tiles from 1 to ${ToaPuzzles.SEQUENCE_LENGTH}."
            else -> "${sequence.size} of ${ToaPuzzles.SEQUENCE_LENGTH} tiles."
        }), full(6))
        manualButton(sequenceManual) { sequenceManual = !sequenceManual }
        if (sequenceManual) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Tap each tile as it flashes.", 11f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(button("Undo") { sequence.removeLastOrNull(); show() }.apply { textSize = 12f; setPadding(dp(10), dp(5), dp(10), dp(5)) })
            }, full(4))
        }
    }

    // The diamond of tiles, numbered in the order they lit up
    private fun sequenceBoardView(maxHeightDp: Int): View {
        // Each tile's number(s) in the order, worked out here so drawing makes nothing new
        val numbers = (0 until 9).map { t -> sequence.indices.filter { sequence[it] == t }.joinToString(",") { "${it + 1}" } }
        return Board(5, 5, ToaPuzzles.SEQUENCE_CELLS, maxHeightDp = maxHeightDp, turn = sequenceTurn, onTap = { i ->
            if (sequenceManual && sequence.size < ToaPuzzles.SEQUENCE_LENGTH) { sequence.add(i); show() }
        }) { canvas, i, r ->
            val n = numbers[i]
            if (n.isEmpty()) plate(canvas, r) else tile(canvas, r, TILE_LIT)
            if (n.isNotEmpty()) text(canvas, n, r.centerX(), r.centerY(), r.height() * if (n.length > 2) 0.3f else 0.5f, DARK_BROWN)
        }
    }

    // The window while the tiles are at the bottom of the screen: Stop while watching, then Watch again or Done
    private fun LinearLayout.sequenceSmall() {
        if (watchingSequence) {
            // the seconds left to press the button in the game, counting down
            val countdown = label(countdownText(), 12f, bold = true)
            sequenceCountdown = countdown
            addView(smallRow(smallButton("Stop", WARN) { endWatch("Stopped.") }, countdown))
        } else {
            sequenceCountdown = null
            addView(smallRow(smallButton("Watch again", SELECTED) { watchSequence() }, smallButton("Done", WARN) { leaveOnScreen() }))
            sequenceNote?.takeIf { !it.startsWith("Saw all") }?.let { addView(label(it, 9f).apply { setTextColor(WARN) }, full(2)) }
        }
    }

    // Finds the nine tiles in one picture (with the bubble hidden), then watches just those tiles (a few pixels
    // each, about 12 times a second) until five have flashed, or it gives up waiting
    private fun watchSequence() {
        if (!capture.isActive) {
            sequenceNote = "Waiting for screen-capture permission..."
            show()
            val asked = reading
            capture.request { ok ->
                if (destroyed || asked != reading) return@request   // closed or cancelled while asking
                if (ok) watchSequence() else { sequenceNote = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        val id = ++reading
        sequenceNote = "Looking for the tiles..."
        show()
        capture.matchScreenSize()
        hideForPicture(true)
        handler.postDelayed({
            if (destroyed || id != reading) { hideForPicture(false); return@postDelayed }
            val shot = capture.grab()
            if (shot == null) { endWatch("Couldn't capture the screen. Try again."); return@postDelayed }
            Thread {
                val tiles = try {
                    ToaReader.findSequenceTiles(LightBoxReader.Pixels(shot)).also { if (it == null) saveForDebugging("sequence", shot) }
                } catch (_: Exception) { null } finally { shot.recycle() }
                handler.post {
                    if (destroyed) return@post
                    // cancelled meanwhile (the phone turned, say): the bubble is still hidden, so bring it back
                    if (id != reading) { hideForPicture(false); return@post }
                    if (tiles == null) {
                        endWatch("Couldn't find the tiles. Stand still with all nine on screen, then try again.")
                        return@post
                    }
                    sequence.clear()
                    sequenceTurn = tiles.rotation
                    // the window shrinks to a Stop button and the tiles fill in at the bottom as they flash
                    hideForPicture(false)
                    watchingSequence = true
                    if (onScreen) { show(); refreshMapOverlay() } else showOnScreen()
                    watchTiles(id, tiles)
                }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    private fun countdownText() = if (sequenceSecondsLeft > 0) "${sequenceSecondsLeft}s" else ""

    private fun watchTiles(id: Int, tiles: ToaReader.SequenceTiles) {
        val started = SystemClock.uptimeMillis()
        var lastFlash = 0L
        var lastLit = -1
        sequenceSecondsLeft = (WATCH_FIRST_FLASH_MS / 1000).toInt()
        sequenceCountdown?.text = countdownText()
        val watch = object : Runnable {
            override fun run() {
                if (destroyed || id != reading) return
                val now = SystemClock.uptimeMillis()
                if (capture.gameHidden) { endWatch("You left the game, so watching stopped."); return }
                // the countdown next to Stop: seconds left to press the button, gone once a tile has flashed
                val left = if (lastFlash != 0L) 0 else ((WATCH_FIRST_FLASH_MS - (now - started) + 999) / 1000).toInt().coerceAtLeast(0)
                if (left != sequenceSecondsLeft) { sequenceSecondsLeft = left; sequenceCountdown?.text = countdownText() }
                // null = the screen hasn't changed since last time
                val lit = capture.sample { ToaReader.litSequenceTile(it, tiles) }
                if (lit != null) {
                    // a tile that has just lit up (the same tile twice goes dark in between)
                    if (lit != -1 && lit != lastLit) {
                        sequence.add(lit)
                        refreshMapOverlay()
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
                        { endWatch("No tiles lit up. Tap Watch, then press the button in the game."); return }
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
        reading++   // stops the watching
        watchingSequence = false
        hideForPicture(false)
        sequenceNote = note
        show()
        refreshMapOverlay()
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
        tip("close your chat and inventory first, so both boards can be seen.")
        // (while watching, the page is just matchingSmall's Stop button)
        addView(bar(button("Watch", SELECTED) { watchMatching() } to 1.2f,
            button("Clear") { memory.clear(); show() } to 1f), full(4))
        help("For Watch: camera facing east, looking straight down, with the zoom at 25%, so both boards and the " +
            "statue are on screen. While it watches, this window shrinks to a Stop button in the corner and the " +
            "boards are drawn at the bottom of the screen. Walk the tiles: each symbol you reveal is filled in. When " +
            "you flip a tile whose match is already known, both tiles turn green: step on the other one. Or tap " +
            "Solve manually: pick a symbol in the row under the boards, then tap the tile. ✓ marks a pair as matched; " +
            "✗ rubs a tile out. Each board has each symbol once. Solo: the line, crook, hand and bird pairs start matched.")
        matchNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }

        addView(matchBoardsView(maxHeightDp = 150), full(8))

        // Filling it in by hand, out of the way until asked for
        manualButton(matchManual) { matchManual = !matchManual }
        if (matchManual) {
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
        }

        addView(status(when {
            memory.done.size == 18 -> "All nine pairs matched: done! ✓"
            memory.activePair() != null -> "Step on the other green tile."
            else -> "Flip a tile: if its match is known, both turn green."
        }), full(8))
    }

    // ---- Watching the matching boards ----
    // 3 times a second: one picture, find the boards in it (they move as you walk), and read each tile. A symbol
    // counts once two pictures agree, so when one has just been seen Watch looks again right away (it can vanish as
    // soon as you step onto the next tile). Stops by itself when all pairs are matched.

    private val watchPixels = ReusablePixels()
    private var watchBusy = false
    private var watchStarted = 0L
    private var watchRun = 0                      // counts Watch starts, so a look from before a restart is ignored
    private var quickLooks = 0                    // quick looks in a row (see MATCH_QUICK_MS)
    private var lastBoards: ToaReader.MatchBoards? = null
    private var mapOverlay: FrameLayout? = null   // the boards drawn on their own at the bottom of the screen
    private var onScreen = false                  // the light, addition or sequence answer is shown at the bottom

    private fun watchMatching() {
        if (!capture.isActive) {
            matchNote = "Waiting for screen-capture permission..."
            show()
            val asked = reading
            capture.request { ok ->
                if (destroyed || asked != reading) return@request   // closed or cancelled while asking
                if (ok) watchMatching() else { matchNote = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        capture.matchScreenSize()
        matchWatching = true
        watchRun++
        // the window shrinks to a Stop button in the top-left corner, and the boards are drawn at the bottom
        setWindowCompact(BubbleService.COMPACT_TOP_LEFT)
        showMapOverlay()
        matchNote = "Watching. Walk the tiles."
        lastBoards = null
        quickLooks = 0
        memory.startWatch()
        watchStarted = SystemClock.uptimeMillis()
        show()
        handler.postDelayed(matchTick, MATCH_INTERVAL_MS)
    }

    private fun stopMatchWatch(note: String?) {
        if (!matchWatching) return
        matchWatching = false
        handler.removeCallbacks(matchTick)
        freeWatchMemory()
        hideMapOverlay()
        if (!destroyed) setWindowCompact(BubbleService.COMPACT_OFF)
        matchNote = note
        if (!destroyed && ::holder.isInitialized) show()
    }

    private val matchTick: Runnable = object : Runnable {
        override fun run() {
            if (!matchWatching || destroyed) return
            if (capture.gameHidden) { stopMatchWatch("You left the game, so watching stopped."); return }
            if (SystemClock.uptimeMillis() - watchStarted > MATCH_WATCH_MAX_MS) { stopMatchWatch("Stopped after 5 minutes."); return }
            handler.postDelayed(this, MATCH_INTERVAL_MS)
            if (watchBusy) return   // still working on the last picture
            val t0 = SystemClock.elapsedRealtimeNanos()
            val shot = capture.grab() ?: return   // nothing new on screen
            val t1 = SystemClock.elapsedRealtimeNanos()
            // Ignore what's under this window: it's the app, not the game
            val loc = IntArray(2)
            holder.getLocationOnScreen(loc)
            val left = loc[0]; val top = loc[1]; val right = left + holder.width; val bottom = top + holder.height
            val shown = holder.isShown
            val map = mapBounds()
            val skip = ToaReader.Skip { x, y ->
                (shown && x in left until right && y in top until bottom) ||
                    (map != null && x in map[0] until map[2] && y in map[1] until map[3])
            }
            val previous = lastBoards
            val allowed = memory.allowed
            val run = watchRun
            watchBusy = true
            Thread {
                var lookBoards: ToaReader.MatchBoards? = null
                var lookTiles: List<ToaReader.MatchTile>? = null
                try {
                    val t2 = SystemClock.elapsedRealtimeNanos()
                    var t3 = t2; var t4 = t2
                    try {
                        watchPixels.load(shot)
                        t3 = SystemClock.elapsedRealtimeNanos()
                        lookBoards = ToaReader.findMatchBoards(watchPixels, skip, previous)
                        t4 = SystemClock.elapsedRealtimeNanos()
                        if (lookBoards == null) saveForDebugging("matching", shot)
                    } finally {
                        // the picture is copied, so its memory goes back right away, even if something went wrong
                        shot.recycle()
                    }
                    val t5 = SystemClock.elapsedRealtimeNanos()
                    lookTiles = lookBoards?.let { ToaReader.readMatchTiles(watchPixels, it, skip, allowed) }
                    if (debugBuild) timeWatch(t1 - t0, t3 - t2, t4 - t3, t5 - t4, SystemClock.elapsedRealtimeNanos() - t5)
                } catch (_: Exception) {
                    lookBoards = null; lookTiles = null   // a look that went wrong counts as "couldn't see the boards"
                }
                val boards = lookBoards
                val tiles = lookTiles
                handler.post {
                    watchBusy = false
                    // Watch was stopped while this look was being worked on: give its memory back now
                    if (!matchWatching || destroyed) { freeWatchMemory(); return@post }
                    // Watch was stopped and started again meanwhile: this look belongs to the old one
                    if (run != watchRun) return@post
                    lastBoards = boards ?: previous
                    if (tiles == null || boards == null) {
                        if (previous == null) showMatchNote("Can't see both boards. Zoom out, face east, and move this window off them.")
                        return@post
                    }
                    showMatchNote("Watching. Walk the tiles.")
                    val screenW = context.resources.displayMetrics.widthPixels
                    if (memory.merge(tiles, boards, screenW, SystemClock.uptimeMillis())) { show(); refreshMapOverlay() }
                    if (memory.done.size == 18) { stopMatchWatch("All nine pairs matched: done! ✓"); return@post }
                    // a symbol not settled yet: look again now instead of at the next regular look
                    if (memory.unsure && quickLooks < MATCH_QUICK_LOOKS) {
                        quickLooks++
                        handler.removeCallbacks(matchTick)
                        handler.postDelayed(matchTick, MATCH_QUICK_MS)
                    } else quickLooks = 0
                }
            }.start()
        }
    }


    // Test builds only: how long each part of a Watch look takes, averaged over 20 looks, in the phone's log
    // (adb logcat -s ToaWatch)
    private val watchTimes = LongArray(5)
    private var watchTimed = 0
    private fun timeWatch(vararg nanos: Long) {
        synchronized(watchTimes) {
            for (i in nanos.indices) watchTimes[i] += nanos[i]
            if (++watchTimed < 20) return
            fun ms(i: Int) = watchTimes[i] / 1e6 / watchTimed
            android.util.Log.d("ToaWatch", "per look (ms): picture %.1f, copy %.1f, find boards %.1f, save failed %.1f, read tiles %.1f"
                .format(ms(0), ms(1), ms(2), ms(3), ms(4)))
            watchTimes.fill(0); watchTimed = 0
        }
    }

    private fun showMatchNote(note: String) {
        if (matchNote == note) return
        matchNote = note
        show()
        refreshMapOverlay()
    }

    // ---- The boards at the bottom of the screen while watching ----
    // Just the squares, no background: they can't be tapped (taps go through to the game).

    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }

    private fun showMapOverlay() {
        if (mapOverlay != null) return
        val frame = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(4)
        }
        windowManager.addView(frame, params)
        mapOverlay = frame
        refreshMapOverlay()
    }

    private fun hideMapOverlay() {
        val frame = mapOverlay ?: return
        mapOverlay = null
        try { windowManager.removeView(frame) } catch (_: Exception) {}
    }

    // Redraws it with what's known now (and a short line when the boards can't be seen)
    private fun refreshMapOverlay() {
        val frame = mapOverlay ?: return
        frame.removeAllViews()
        val cell = dp(MAP_CELL_DP)
        frame.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val note = matchNote
            if (page == Page.MATCHING && note != null && note.startsWith("Can't")) {
                addView(label("Looking for the boards…", 11f, bold = true).apply {
                    setTextColor(Color.WHITE)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                    background = GradientDrawable().apply { setColor("#99000000".toColorInt()); cornerRadius = dp(4).toFloat() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
            }
            when (page) {
                Page.LIGHT -> addView(lightBoardView(MAP_CELL_DP * 3), LinearLayout.LayoutParams(cell * 3, cell * 3))
                Page.ADDITION -> addView(additionBoardView(MAP_CELL_DP * 4), LinearLayout.LayoutParams(cell * 4, cell * 4))
                Page.SEQUENCE -> addView(sequenceBoardView(MAP_CELL_DP * 4), LinearLayout.LayoutParams(cell * 4, cell * 4))
                else -> addView(matchBoardsView(maxHeightDp = MAP_CELL_DP * 3), LinearLayout.LayoutParams(cell * 7, cell * 3))
            }
        })
    }

    // Where the boards are drawn on screen (left, top, right, bottom), or null
    private fun mapBounds(): IntArray? {
        val frame = mapOverlay ?: return null
        if (!frame.isShown) return null
        val loc = IntArray(2)
        frame.getLocationOnScreen(loc)
        return intArrayOf(loc[0], loc[1], loc[0] + frame.width, loc[1] + frame.height)
    }

    // Both boards: grey until a symbol is seen, purple with the symbol once seen, glowing once matched. When the
    // tile you've just flipped has a known match, both get a green border.
    private fun matchBoardsView(maxHeightDp: Int): View {
        val green = memory.activePair()
        // Left board in columns 0-2, right board in columns 4-6 (the statue sits between them)
        val cells = (0 until 18).map { t -> (t % 9) / 3 to (t % 3) + if (t < 9) 0 else 4 }
        return Board(3, 7, cells, maxHeightDp = maxHeightDp, onTap = { t -> if (matchManual) { tapMatching(t); show() } }) { canvas, t, r ->
            // grey until you've seen its symbol, purple with a yellow symbol once seen, glowing once matched
            val sym = memory.symbols[t]
            tile(canvas, r, when { t in memory.done -> TILE_LIT; sym != null -> TILE; else -> TILE_HIDDEN })
            sym?.let { glyph(canvas, it, r, if (t in memory.done) GLYPH_LIT else YELLOW) }
            if (green != null && (t == green.first || t == green.second)) stepHere(canvas, r)
        }
    }

    // The page while Watch is on: the window shrinks to just Stop (the boards are drawn at the bottom of the screen)
    private fun LinearLayout.matchingSmall() {
        addView(button("Stop", WARN) { stopMatchWatch(null) }.apply { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) })
    }

    private fun tapMatching(t: Int) {
        when (matchBrush) {
            in 0..8 -> {
                val sym = ToaPuzzles.MATCHING_SYMBOLS[matchBrush]
                if (memory.symbols[t] == sym) { memory.symbols.remove(t); memory.done.remove(t); return }
                // each symbol is on a board only once, so take it off any other tile on this board
                val board = if (t < 9) 0 until 9 else 9 until 18
                board.filter { memory.symbols[it] == sym }.forEach { memory.symbols.remove(it); memory.done.remove(it) }
                memory.symbols[t] = sym
                memory.active = t
            }
            9 -> {
                val sym = memory.symbols[t] ?: return
                val pair = (0 until 18).filter { memory.symbols[it] == sym }
                if (t in memory.done) memory.done.removeAll(pair.toSet()) else memory.done.addAll(pair)
            }
            else -> { memory.symbols.remove(t); memory.done.remove(t) }
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

    // A green border: "step here"
    private fun stepHere(canvas: Canvas, r: RectF) {
        stroke.color = STEP_GREEN
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
            // a mitten, traced from the game's symbol: the hand at the top, the thumb out to the left, the cuff below
            Symbol.HAND to Glyph(poly(0.37f, 0.16f, 0.67f, 0.12f, 0.70f, 0.46f, 0.62f, 0.69f, 0.63f, 0.90f, 0.41f, 0.90f,
                0.41f, 0.72f, 0.26f, 0.59f, 0.19f, 0.53f, 0.18f, 0.35f, 0.26f, 0.35f, 0.27f, 0.49f, 0.33f, 0.53f, 0.35f, 0.17f), false),
            Symbol.BIRD to Glyph(poly(0.18f, 0.36f, 0.36f, 0.2f, 0.5f, 0.28f, 0.86f, 0.72f, 0.5f, 0.68f, 0.34f, 0.46f).apply {
                addRect(0.47f, 0.66f, 0.56f, 0.86f, Path.Direction.CW) }, false),
            Symbol.CROOK to Glyph(poly(0.38f, 0.85f, 0.38f, 0.2f, 0.68f, 0.2f, 0.68f, 0.44f, close = false), true, 0.11f),
            Symbol.WIGGLE to Glyph(poly(0.12f, 0.42f, 0.31f, 0.6f, 0.5f, 0.42f, 0.69f, 0.6f, 0.88f, 0.42f, close = false), true, 0.09f),
            // a boot: the leg up the right, the toe out to the left along the bottom
            Symbol.FOOT to Glyph(poly(0.5f, 0.12f, 0.74f, 0.12f, 0.74f, 0.86f, 0.18f, 0.86f, 0.18f, 0.76f, 0.26f, 0.68f, 0.5f, 0.6f), false),
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

    // Gives back the memory Watch keeps between looks (about 10 MB for a full-size picture), unless a look is
    // still being worked on: then it's given back when that look finishes
    private fun freeWatchMemory() {
        if (watchBusy) return
        watchPixels.release()
        ToaReader.releaseWork()
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
        fun release() { data = IntArray(0); width = 0; height = 0 }
    }

    // ---------------- Small building blocks ----------------

    private fun scrolling(build: LinearLayout.() -> Unit) = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
            build()
        })
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

    // A short tip above a page's buttons
    private fun LinearLayout.tip(text: String) = addView(label("Tip: $text", 11f).apply { setTextColor(FADED) }, full())

    // The button that shows or hides a page's tap-it-in-yourself controls
    private fun LinearLayout.manualButton(open: Boolean, toggle: () -> Unit) =
        addView(button(if (open) "Hide manual solve" else "Solve manually") { toggle(); show() }
            .apply { textSize = 12f; setPadding(dp(8), dp(5), dp(8), dp(5)) }, full(6))

    // Buttons for the shrunk window: small, side by side
    private fun smallButton(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) =
        button(text, color, onClick).apply { textSize = 12f; setPadding(dp(10), dp(6), dp(10), dp(6)) }

    private fun smallRow(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(4) })
        }
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
