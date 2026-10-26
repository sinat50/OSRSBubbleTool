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
import android.util.Log
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
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.FADED
import com.sinat.osrsbubbletool.Palette.GO_GREEN
import com.sinat.osrsbubbletool.Palette.PAPER
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import com.sinat.osrsbubbletool.Palette.WARN
import com.sinat.osrsbubbletool.ToaPuzzles.Symbol
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ToA Puzzle Helper: the five Path of Scabaras puzzles in the Tombs of Amascut, and the Path of Het's mirror
// puzzle (Watch reads the room and its mirrors, and a map shows the way through). For the others, you tap in what you see
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
        private const val HET_MAP_DP = 147        // the Path of Het map drawn at the bottom of the screen (7 dp a square)
        private const val MATCH_INTERVAL_MS = 333L        // how often Watch looks at the matching boards (3 times a second)
        private const val MATCH_QUICK_MS = 40L            // …or this soon after a look that saw a symbol not settled yet
        private const val MATCH_QUICK_LOOKS = 3           // at most this many quick looks in a row (a symbol you stand on
                                                          // that can't be made out mustn't keep it looking flat out)
        private const val MATCH_WATCH_MAX_MS = 300_000L   // Watch stops by itself after 5 minutes
        private const val HET_INTERVAL_MS = 1000L         // how often the Path of Het's Watch looks (once a second)
        private const val HET_SETTLED_MS = 2000L          // ...every 2 seconds once the whole way has been seen
        private const val HET_WATCH_MAX_MS = 300_000L     // ...and stops by itself after 5 minutes
        private const val HET_BAR_WAIT_MS = 10_000L       // how long it waits for Het's seal's bar before showing a room
        private const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before the screenshot
        private const val WATCH_INTERVAL_MS = 80L         // how often the sequence tiles are checked
        private const val WATCH_FIRST_FLASH_MS = 20_000L  // how long to wait for you to press the button
        private const val WATCH_NEXT_FLASH_MS = 5_000L    // how long to wait for the next flash

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
        private val INK = "#3E2C12".toColorInt()          // writing on a lit tile (the same in dark mode)
        // The Path of Het map: the sandy floor, the grey statues, Het's pale seal, the white beam and gold mirrors
        private val HET_FLOOR = "#C2B28C".toColorInt()
        private val HET_LINES = "#AD9D78".toColorInt()     // the squares on the floor
        private val HET_STONE = "#5E6372".toColorInt()
        private val HET_SEAL = "#DCD6E8".toColorInt()
        private val HET_GLOW = "#B0FFF1B8".toColorInt()    // the beam's glow (see-through)
        private val HET_SHINE = "#FFF8E0".toColorInt()     // a mirror's shiny side
        private val HET_WALL = "#8A8F9C".toColorInt()      // a smooth stone wall (light)
        private val HET_CRACKED = "#3C414E".toColorInt()   // a cracked wall, which can be mined (dark)
        private val HET_FIXED = "#7A7F8E".toColorInt()     // a fixed mirror (grey, with a dark border)
        private val HET_RIM = "#9C96AC".toColorInt()       // the room's outer wall (pale lavender stone)
        private val HET_MINE = "#E8702A".toColorInt()      // the border on a cracked wall to mine
    }

    private enum class Page(val title: String, val about: String) {
        MENU("", ""),
        LIGHT("Light puzzle", "Eight pressure plates in a ring: light them all"),
        ADDITION("Addition puzzle", "Symbols on the floor that add up to the tablet's number"),
        SEQUENCE("Sequence puzzle", "Tiles light up in turn: step on them in the same order"),
        OBELISK("Obelisk puzzle", "Hit the obelisks in the right order"),
        MATCHING("Matching puzzle", "Two boards of symbols: step on matching pairs"),
        HET("Path of Het: mirrors", "Mirrors turn the beam onto the statue")
    }

    private val prefs = context.getSharedPreferences("toa_puzzles", Context.MODE_PRIVATE)
    private lateinit var holder: FrameLayout
    private var scroll: ScrollView? = null
    private var page = Page.MENU
    private var helpOpen = false   // the page's instructions are showing (the ? button)

    // ---------------- What you've entered (kept while the bubble runs) ----------------

    private var lightLit = 0                          // lit plates, one bit each
    private var lightEntered = false                  // you've tapped in what's lit, so show the answer
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
    private var hetAnswer = -1                        // the Path of Het way picked (0-8), -1 = none yet
    private var hetRoom = -1                          // the room picked (ToaPuzzles.HET_ROOMS), -1 = none
    private var hetCracked: BooleanArray? = null      // which of its walls are cracked this raid, as read (null: as usual)
    private var hetSeen: List<Int> = emptyList()       // fixed mirrors read on screen this raid (squares, x * 21 + y)
    private var hetUnseen: List<Int> = emptyList()     // open squares no read could see yet (behind a statue or wall)
    private var hetFacing: Map<Int, ToaPuzzles.Facing> = emptyMap()   // which way the seen ones face, where it was read
    private var hetWatching = false                    // the Path of Het's Watch is on
    private val hetMemory = HetReader.Memory()         // what its looks have made out so far
    private var hetWatchStarted = 0L
    private var hetTracking = false                    // Watch's last look saw the room (else lost, or the walls down)
    private var hetNote: String? = null               // what the last screen reading found

    // Which way the obelisk map is turned, in quarter turns clockwise (0 = the walls at the top and bottom)
    private var obeliskTurns: Int
        get() = prefs.getInt("obelisk_turns", 0)
        set(v) { prefs.edit { putInt("obelisk_turns", v) } }

    // Which way the addition map faces: how many quarter turns from north at the top. Starts with east
    // at the top, as you see the room walking in towards the exit.
    private var additionTurns: Int
        get() = prefs.getInt("addition_turns", 1)
        set(v) { prefs.edit { putInt("addition_turns", v) } }

    // Which way the Path of Het maps face, the same way: 0 north at the top, 1 east, 2 south, 3 west (as you walk
    // in: the room is entered from the east, so the caster is at the far end)
    private var hetTop: Int
        get() = prefs.getInt("het_top", 3)
        set(v) { prefs.edit { putInt("het_top", v) } }

    private val handler = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var reading = 0   // counts screen readings, so an old one that finishes late is ignored

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // The bubble is closing
    fun destroy() {
        matchWatching = false
        hetWatching = false
        onScreen = false
        hideMapOverlay()
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        endWatchWorker()
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
        if (page != Page.MENU) { stopMatchWatch(null); stopHetWatch(null); cancelReading(); leaveOnScreen(); page = Page.MENU; helpOpen = false; show(keepScroll = false) }
    }

    // The window was closed: stop watching and reading (nothing can be seen to update) and take the map off the screen
    fun onWindowClosed() {
        stopMatchWatch(null)
        stopHetWatch(null)
        cancelReading()
        // (and the full page back, since the window opens at its full size again; the small row's Done would do nothing)
        if (onScreen) { onScreen = false; hideMapOverlay(); show() }
    }

    // The phone turned: the puzzle has moved on screen, so watching where it was is no use
    fun onRotated() {
        if (watchingSequence) endWatch("Screen rotated. Tap Watch to start again.") else cancelReading()
        stopMatchWatch("Screen rotated. Tap Watch to start again.")
        stopHetWatch("Screen rotated. Tap Watch to start again.")
    }

    // Watching a puzzle (with the window shrunk to a corner): a dark-mode switch waits until it's done
    fun isWatching() = matchWatching || hetWatching

    // Stops a screen reading or sequence watch in progress (its result is ignored when it comes in), including one
    // still waiting for screen-capture permission
    private fun cancelReading() {
        reading++
        if (watchingSequence) { watchingSequence = false; hideForPicture(false) }
        // notes that said it was busy ("Reading the screen...") would otherwise stay up for good
        fun idle(note: String?) = note?.takeUnless { it.endsWith("...") }
        lightNote = idle(lightNote); additionNote = idle(additionNote); sequenceNote = idle(sequenceNote); matchNote = idle(matchNote)
        hetNote = idle(hetNote)
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
        stopHetWatch(null)
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
                Page.HET -> if (onScreen) hetSmall() else het()
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
            addView(label("Tombs of Amascut puzzles", 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(helpButton(), LinearLayout.LayoutParams(dp(HELP_SIZE_DP), dp(HELP_SIZE_DP)).apply { leftMargin = dp(4) })
        }, full())
        help("Pick the puzzle you're in: the first five are on the Path of Scabaras, the mirrors on the Path of Het. " +
            "The light, addition, sequence and matching puzzles and the Path of Het can read the screen; " +
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
            // the room with one way through it
            Page.HET -> HetMap(ToaPuzzles.HET_ROOMS[0].way, 0, small = true, maxHeightDp = ICON_H_DP) { open(p) }
            Page.MENU -> View(context)
        }
    }

    // ---------------- Light puzzle ----------------

    private fun LinearLayout.light() {
        val answer = ToaPuzzles.solveLights(lightLit)
        tip("close your chat and inventory first, so all eight plates can be seen.")
        addView(bar(button("Read the screen", GO_GREEN) { readLights() } to 2f,
            button("Clear") { lightLit = 0; lightEntered = false; lightNote = null; show() } to 1f), full(4))
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
        return Board(3, 3, (0 until 8).map { ToaPuzzles.lightCell(it) }, maxHeightDp = maxHeightDp, onTap = { i ->
            if (lightManual) { lightLit = lightLit xor (1 shl i); lightEntered = true; show() }
        }) { canvas, i, r ->
            if (lightLit shr i and 1 == 1) litPlate(canvas, r) else plate(canvas, r)
            if (lightEntered && answer shr i and 1 == 1) stepHere(canvas, r)
        }
    }

    // The window while the answer is at the bottom of the screen
    private fun LinearLayout.lightSmall() {
        addView(smallRow(smallButton("Read again", GO_GREEN) { readLights() }, smallButton("Done", WARN) { leaveOnScreen() }))
        lightNote?.takeIf { it.startsWith("Couldn't") }?.let { addView(label(it, 9f).apply { setTextColor(WARN) }, full(2)) }
    }

    private fun readLights() = readScreen({ lightNote = it }, ToaReader::readLights) { result ->
        if (result == null) {
            lightNote = "Couldn't find the plates. Stand still with all eight on screen, then try again."
        } else {
            lightLit = result
            lightEntered = true
            val n = Integer.bitCount(result)
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
            addView(button("Read the number", GO_GREEN) { readAdditionNumber() }, lp(2.2f))
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
            text(canvas, values[sym.ordinal], r.left + r.width() * 0.17f, r.top + r.height() * 0.2f, r.height() * 0.22f, if (lit) INK else Color.WHITE)
            if (answer != null && i in answer) stepHere(canvas, r)
        }
    }

    // The window while the walk is at the bottom of the screen (bottom-left, clear of the chat box)
    private fun LinearLayout.additionSmall() {
        addView(smallRow(smallButton("−") { changeTarget(-1) },
            label("$additionTarget", 16f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(6), 0, dp(6), 0) },
            smallButton("+") { changeTarget(1) },
            smallButton("Read", GO_GREEN) { readAdditionNumber() },
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
        addView(bar(button("Watch", GO_GREEN) { watchSequence() } to 1.4f,
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
            if (n.isNotEmpty()) text(canvas, n, r.centerX(), r.centerY(), r.height() * if (n.length > 2) 0.3f else 0.5f, INK)
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
            addView(smallRow(smallButton("Watch again", GO_GREEN) { watchSequence() }, smallButton("Done", WARN) { leaveOnScreen() }))
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
                n.isNotEmpty() -> text(canvas, n, tall.centerX(), tall.centerY(), r.height() * 0.45f, INK)
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

    // ---------------- Path of Het: mirrors ----------------

    private fun LinearLayout.het() {
        tip("stand in the doorway looking down at the room, zoomed out so most of it shows, then tap Watch.")
        addView(bar(button("Watch", GO_GREEN) { watchHet() } to 1f), full(4))
        help("Watch looks at the screen once a second as you walk through the room: it finds which of the three " +
            "rooms you're in, this raid's cracked walls and the fixed mirrors standing about, and fills in what the " +
            "statues and walls hide as they come into view.\n\n" +
            "The map at the bottom of the screen shows where to put each mirror (gold), its shiny side facing as drawn " +
            "(the beam comes in and goes out on that side), and the cracked walls to mine (orange border). A grey " +
            "mirror is a fixed one already standing there. ◀ ▶ change the way, Done brings this window back.\n\n" +
            "Keep it on while you mine the obelisk: it watches Het's seal's health bar (top left), clears the map as " +
            "soon as the seal starts losing health, and if it isn't broken reads the new layout as soon as its walls " +
            "rise. It stops once the seal is broken, after 5 minutes, or when you tap Stop.")
        hetNote?.let { addView(label(it, 11f).apply { setTextColor(WARN) }, full(4)) }
    }

    // The window while the map is at the bottom of the screen: ◀ ▶ try the other ways
    private fun LinearLayout.hetSmall() {
        fun step(by: Int) { hetAnswer = (hetAnswer + by + ToaPuzzles.HET_ANSWERS.size) % ToaPuzzles.HET_ANSWERS.size; show(); refreshMapOverlay() }
        addView(smallRow(if (hetWatching) smallButton("Stop", WARN) { stopHetWatch("Stopped watching.") }
            else smallButton("Watch", GO_GREEN) { watchHet() }, smallButton("◀") { step(-1) },
            label(if (hetAnswer < 0) "–" else "${hetAnswer + 1}/${ToaPuzzles.HET_ANSWERS.size}", 14f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0) },
            smallButton("▶") { step(1) },
            smallButton("Done", WARN) { leaveOnScreen() }))
        hetNote?.takeIf { it.startsWith("Couldn't") || it.startsWith("Watch") }?.let { addView(label(it, 9f).apply { setTextColor(WARN) }, full(2)) }
    }

    // Watch: a look about once a second as you walk through the room. The first finds the room; the next ones follow
    // the camera from the last look (HetReader.watchLook, several times quicker than a full read), and each
    // adds what it can see to hetMemory: squares behind the caster and the walls fill in as you walk, and a fixed
    // mirror only counts once two looks agree. The map at the bottom updates as it goes. It keeps watching through
    // the round (HetReader.watchLook): when the walls go down it clears the map, and reads the next layout when it
    // rises. Stops after 5 minutes, or if you leave the game.
    private fun watchHet() {
        if (!capture.isActive) {
            hetNote = "Waiting for screen-capture permission..."
            show()
            val asked = reading
            capture.request { ok ->
                if (destroyed || asked != reading) return@request   // closed or cancelled while asking
                if (ok) watchHet() else { hetNote = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        capture.matchScreenSize()
        hetWatching = true
        watchRun++
        hetMemory.reset()
        hetTracking = false
        clearHetLayout()   // (not the last Watch's answer: this may be another layout)
        hetWatchStarted = SystemClock.uptimeMillis()
        hetNote = "Watching: looking for the room..."
        onScreen = true
        // bottom left: the top left corner is where the game shows Het's seal and its health while you mine it
        setWindowCompact(BubbleService.COMPACT_BOTTOM_LEFT)
        showMapOverlay()
        show()
        handler.postDelayed(hetTick, 300L)
    }

    private fun stopHetWatch(note: String?) {
        if (!hetWatching) return
        hetWatching = false
        handler.removeCallbacks(hetTick)
        endWatchWorker()
        freeWatchMemory()
        if (note != null) hetNote = note
        if (!destroyed && ::holder.isInitialized) { show(); refreshMapOverlay() }
    }

    private val hetTick: Runnable = object : Runnable {
        override fun run() {
            if (!hetWatching || destroyed) return
            if (capture.gameHidden) { stopHetWatch("You left the game, so watching stopped."); return }
            if (SystemClock.uptimeMillis() - hetWatchStarted > HET_WATCH_MAX_MS) { stopHetWatch("Stopped watching after 5 minutes."); return }
            // once the whole way has been seen and the room is in view, it only needs to notice the walls going down: a
            // look every 2 seconds. Lost, or with the walls down, it looks every second to catch the next layout quickly.
            val settled = hetTracking && hetMemory.looks >= 3 && hetAnswer >= 0 && hetHiddenOnWay().isEmpty()
            handler.postDelayed(this, if (settled) HET_SETTLED_MS else HET_INTERVAL_MS)
            if (watchBusy) return   // still working on the last look
            val shot = capture.grab() ?: return
            // what's under this window and the map is the app, not the game: blanked out before reading
            val covered = ArrayList<IntArray>()
            if (holder.isShown) {
                val loc = IntArray(2)
                holder.getLocationOnScreen(loc)
                covered.add(intArrayOf(loc[0], loc[1], loc[0] + holder.width, loc[1] + holder.height))
            }
            mapBounds()?.let { covered.add(it) }
            val room = hetMemory.room
            val camera = hetMemory.camera
            val locked = hetMemory.locked
            val lost = hetMemory.lostLooks
            val zoom = hetMemory.zoom
            val seal = hetMemory.seal
            val run = watchRun
            watchBusy = true
            watchWorker().execute {
                val started = SystemClock.uptimeMillis()
                val look = try {
                    try { watchPixels.load(shot) } finally { shot.recycle() }
                    for (c in covered) watchPixels.blank(c[0], c[1], c[2], c[3])
                    HetReader.watchLook(watchPixels, room, camera, locked, lost, zoom, seal)
                } catch (_: Exception) { null }
                // test builds only: how long each look took (adb logcat -s PathOfHet)
                if (debugBuild) Log.d("PathOfHet", "look ${SystemClock.uptimeMillis() - started} ms: room ${look?.reading?.room}, " +
                    "walls down ${look?.wallsDown}, seal ${look?.seal}")
                handler.post {
                    watchBusy = false
                    if (!hetWatching || destroyed) { freeWatchMemory(); return@post }
                    if (run != watchRun || look == null) return@post
                    val outcome = hetMemory.use(look)
                    // the seal broken: the room is done
                    if (hetMemory.broken) { stopHetWatch("Het's seal is broken: on to Akkha!"); return@post }
                    hetTracking = outcome == HetReader.Memory.Outcome.SAME || outcome == HetReader.Memory.Outcome.CHANGED
                    when (outcome) {
                        HetReader.Memory.Outcome.CLEARED -> {
                            // the round is over: forget this layout, and wait for the next one
                            clearHetLayout()
                            hetNote = "Watching: the room is clear. Waiting for the next layout..."
                            show(); refreshMapOverlay()
                        }
                        HetReader.Memory.Outcome.WALLS_DOWN -> if (hetMemory.room < 0) {
                            hetNote = "Watching: waiting for the walls to rise..."; show()
                        }
                        // (zoomed in close, or the camera turned fast: it keeps the answer and picks the room up again)
                        HetReader.Memory.Outcome.LOST -> if (hetMemory.room < 0) {
                            hetNote = "Watching: can't make out the room yet. Look down at it, zoomed out."; show()
                        }
                        // before Het's seal's bar shows, the walls haven't risen and the bare floor can fit a room by
                        // chance: nothing is shown for the first HET_BAR_WAIT_MS (after that it's shown anyway, in
                        // case this screen's bar can't be read)
                        else -> if (hetMemory.seal < 0f && SystemClock.uptimeMillis() - hetWatchStarted < HET_BAR_WAIT_MS) {
                            hetNote = "Watching: waiting for the walls to rise..."; show()
                        } else {
                            val first = hetMemory.room != hetRoom
                            if (first || hetMemory.looks <= 1 || outcome == HetReader.Memory.Outcome.CHANGED) useHetMemory(first)
                        }
                    }
                }
            }
        }
    }

    // Takes the layout off the map and the page (a new Watch, or the walls down at the end of a round)
    private fun clearHetLayout() {
        hetRoom = -1; hetAnswer = -1; hetCracked = null
        hetSeen = emptyList(); hetUnseen = emptyList(); hetFacing = emptyMap()
    }

    // Shows what Watch has made out: the room, this raid's cracked walls, the fixed mirrors settled so far, and the
    // best way round them
    private fun useHetMemory(first: Boolean) {
        val r = hetMemory.room
        val room = ToaPuzzles.HET_ROOMS[r]
        hetRoom = r
        val read = hetMemory.cracked
        val cracked = BooleanArray(room.blocks.size) { i -> val c = read?.getOrNull(i) ?: -1; if (c < 0) room.usuallyCracked[i] else c == 1 }
        hetCracked = if (cracked.contentEquals(room.usuallyCracked)) null else cracked
        hetSeen = hetMemory.fixed
        hetUnseen = hetMemory.unseen
        hetFacing = hetMemory.facing.mapValues { ToaPuzzles.Facing.entries[it.value] }
        val before = hetAnswer
        hetAnswer = ToaPuzzles.hetBestWay(room.wallsWith(cracked), room.fixed, hetSeen, hetFacing).takeIf { it >= 0 } ?: room.way
        hetNote = "Watching room ${r + 1}" +
            (if (hetSeen.isNotEmpty()) ": ${hetSeen.size} fixed mirror${if (hetSeen.size == 1) "" else "s"} found" else "") +
            (if (!first && before != hetAnswer) ". Way changed to go round one" else "")
        show()
        refreshMapOverlay()
    }

    // The squares on the way shown that no read could see yet (a fixed mirror could be standing there)
    private fun hetHiddenOnWay() = if (hetAnswer < 0) emptyList() else ToaPuzzles.hetBeam(ToaPuzzles.HET_ANSWERS[hetAnswer]).filter { it in hetUnseen }

    // The corners of the way shown where a fixed mirror already stands: the room's own (HetRoom.fixed), or one read
    // on screen this raid facing the way the corner needs (or whose facing couldn't be read: then it has to face the
    // way the map shows). One read facing another way isn't one of them: it's drawn as it stands, in the beam's way.
    private fun hetFixedCorners(answer: Int = hetAnswer): List<Int> {
        val room = ToaPuzzles.HET_ROOMS.getOrNull(hetRoom)
        val own = room?.takeIf { it.way == answer }?.fixed ?: emptyList()
        return ToaPuzzles.HET_ANSWERS.getOrElse(answer) { emptyList() }
            .filter { m -> val s = m.x * ToaPuzzles.HET_SIZE + m.y; s in own || (s in hetSeen && (hetFacing[s] ?: m.facing) == m.facing) }
            .map { it.x * ToaPuzzles.HET_SIZE + it.y }
    }

    // One mirror on the Path of Het map, centred on x, y: a gold square (a mirror to place) or a grey one with a dark
    // border (a fixed mirror already there), its shiny half on the side it faces and the mirror itself the line across
    private val hetShine = Path()
    private val hetBox = RectF()
    private fun drawHetMirror(canvas: Canvas, x: Float, y: Float, h: Float, facing: ToaPuzzles.Facing, fixed: Boolean) {
        val l = x - h; val r = x + h; val t = y - h; val b = y + h
        hetBox.set(l, t, r, b)
        fill.color = if (fixed) HET_FIXED else YELLOW
        canvas.drawRect(hetBox, fill)
        hetShine.reset()
        when (facing) {
            ToaPuzzles.Facing.NORTH_EAST -> { hetShine.moveTo(l, t); hetShine.lineTo(r, t); hetShine.lineTo(r, b) }
            ToaPuzzles.Facing.SOUTH_EAST -> { hetShine.moveTo(l, b); hetShine.lineTo(r, b); hetShine.lineTo(r, t) }
            ToaPuzzles.Facing.SOUTH_WEST -> { hetShine.moveTo(l, t); hetShine.lineTo(l, b); hetShine.lineTo(r, b) }
            ToaPuzzles.Facing.NORTH_WEST -> { hetShine.moveTo(l, b); hetShine.lineTo(l, t); hetShine.lineTo(r, t) }
        }
        hetShine.close()
        fill.color = HET_SHINE
        canvas.drawPath(hetShine, fill)
        stroke.color = INK; stroke.strokeWidth = h * 0.24f
        when (facing) {
            ToaPuzzles.Facing.NORTH_EAST, ToaPuzzles.Facing.SOUTH_WEST -> canvas.drawLine(l, t, r, b, stroke)
            else -> canvas.drawLine(l, b, r, t, stroke)
        }
        if (fixed) { stroke.strokeWidth = h * 0.2f; canvas.drawRect(hetBox, stroke) }
    }

    // The picked room's walls, with this raid's cracks if the room was read (null: no room picked)
    private fun hetWalls(): IntArray? {
        val room = ToaPuzzles.HET_ROOMS.getOrNull(hetRoom) ?: return null
        return hetCracked?.let { room.wallsWith(it) } ?: room.walls
    }

    // The Path of Het room with one way through it: the three statues across the middle, the room's walls (if it's
    // known: smooth ones light grey, cracked ones dark), the beam from the caster to the shielded statue, and a gold
    // mirror at each corner, its shiny half on the side the beam comes in and goes out. Cracked walls the beam crosses
    // get an orange border (mine them). `answer` -1 draws just the room. A small one (the puzzle list's picture)
    // leaves out the floor's squares and draws mirrors as gold dots. Turned so `hetTop` is at the top.
    @SuppressLint("ViewConstructor")
    private inner class HetMap(
        answer: Int,
        room: Int,
        private val small: Boolean,
        private val maxHeightDp: Int,
        onTap: (() -> Unit)? = null
    ) : View(context) {
        private val mirrors = ToaPuzzles.HET_ANSWERS.getOrElse(answer) { emptyList() }
        // the corners where a fixed mirror already stands, in this room on its way (drawn grey)
        private val fixed = if (room == hetRoom) hetFixedCorners(answer) else ToaPuzzles.HET_ROOMS.getOrNull(room)?.takeIf { it.way == answer }?.fixed ?: emptyList()
        // other fixed mirrors read on screen this raid: drawn facing the way they were read, or a plain grey square
        private val others = if (room == hetRoom && !small) hetSeen.filter { it !in fixed } else emptyList()
        private val squares = if (answer < 0) emptyList() else ToaPuzzles.hetBeam(mirrors)
        private val walls = if (room == hetRoom) hetWalls() else ToaPuzzles.HET_ROOMS.getOrNull(room)?.walls
        private val turn = -90f * hetTop
        private val beam = Path()
        private val floorShape = Path()
        private val box = RectF()
        private var cell = 0f; private var left = 0f; private var top = 0f

        init { if (onTap != null) setOnClickListener { onTap() } }

        // Where a point of the room is on screen (before turning): x east, y north, so north is up. The drawing
        // reaches HET_DRAW_MARGIN squares past the 21x21 squares on every side, for the alcoves.
        private val m = ToaPuzzles.HET_DRAW_MARGIN
        private val span = ToaPuzzles.HET_SIZE + 2 * m
        private fun sx(x: Float) = left + (x + m) * cell
        private fun sy(y: Float) = top + (ToaPuzzles.HET_SIZE + m - y) * cell
        // a square's middle on screen
        private fun cx(x: Float) = sx(x + 0.5f)
        private fun cy(y: Float) = sy(y + 0.5f)
        private fun squareBox(s: Int) {
            val x = (s / ToaPuzzles.HET_SIZE).toFloat(); val y = (s % ToaPuzzles.HET_SIZE).toFloat()
            box.set(sx(x), sy(y + 1), sx(x + 1), sy(y))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(w, minOf(w, dp(maxHeightDp)))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val size = minOf(w, h).toFloat()
            cell = size / span
            left = (w - size) / 2f; top = (h - size) / 2f
            // the beam: out of the caster's west side, through each square's middle, into the shielded statue
            beam.reset()
            if (squares.isEmpty()) return
            beam.moveTo(cx(ToaPuzzles.HET_CASTER_X - 0.5f), cy(ToaPuzzles.HET_MIDDLE_Y + 1f))
            for (s in squares) beam.lineTo(cx((s / ToaPuzzles.HET_SIZE).toFloat()), cy((s % ToaPuzzles.HET_SIZE).toFloat()))
            val last = squares.last(); val before = squares.getOrElse(squares.size - 2) { last }
            val dx = (last / ToaPuzzles.HET_SIZE - before / ToaPuzzles.HET_SIZE) * 0.5f
            val dy = (last % ToaPuzzles.HET_SIZE - before % ToaPuzzles.HET_SIZE) * 0.5f
            beam.lineTo(cx(last / ToaPuzzles.HET_SIZE + dx), cy(last % ToaPuzzles.HET_SIZE + dy))
        }

        override fun onDraw(canvas: Canvas) {
            canvas.withRotation(turn, width / 2f, height / 2f) {
                // the floor: the room's own outline (ToaPuzzles.HET_OUTLINE, from the wiki's map of the room)
                val e = span * cell
                floorShape.reset()
                ToaPuzzles.HET_OUTLINE.forEachIndexed { i, (ox, oy) ->
                    if (i == 0) floorShape.moveTo(sx(ox), sy(oy)) else floorShape.lineTo(sx(ox), sy(oy))
                }
                floorShape.close()
                fill.color = HET_FLOOR
                drawPath(floorShape, fill)
                if (!small) withClip(floorShape) {
                    stroke.color = HET_LINES; stroke.strokeWidth = 1f
                    for (i in 1 until span) {
                        drawLine(left + i * cell, top, left + i * cell, top + e, stroke)
                        drawLine(left, top + i * cell, left + e, top + i * cell, stroke)
                    }
                }
                // the outer wall round the edge and across the alcoves' mouths, with gaps for the two doors (west and
                // east), to get your bearings
                stroke.color = HET_RIM; stroke.strokeWidth = cell * (if (small) 0.8f else 0.5f)
                drawPath(floorShape, stroke)
                for (a in ToaPuzzles.HET_ALCOVE_WALLS) drawLine(sx(a[0].toFloat()), sy(a[2].toFloat()), sx(a[1].toFloat()), sy(a[2].toFloat()), stroke)
                fill.color = HET_FLOOR
                for (doorX in listOf(0f, ToaPuzzles.HET_SIZE.toFloat())) {
                    box.set(sx(doorX) - cell * 0.5f, sy(ToaPuzzles.HET_DOOR_TO_Y.toFloat()), sx(doorX) + cell * 0.5f, sy(ToaPuzzles.HET_DOOR_FROM_Y.toFloat()))
                    drawRect(box, fill)
                }
                // the walls, a square at a time
                if (walls != null) for (s in walls.indices) {
                    if (walls[s] == 0) continue
                    squareBox(s)
                    fill.color = if (walls[s] == 1) HET_WALL else HET_CRACKED
                    drawRect(box, fill)
                }
                // the caster, Het's seal and the shielded statue, 3×3 each
                for (x in listOf(ToaPuzzles.HET_CASTER_X, ToaPuzzles.HET_SEAL_X, ToaPuzzles.HET_TARGET_X)) {
                    val seal = x == ToaPuzzles.HET_SEAL_X
                    box.set(sx(x + 0.3f), sy(ToaPuzzles.HET_MIDDLE_Y + 2.7f), sx(x + 2.7f), sy(ToaPuzzles.HET_MIDDLE_Y + 0.3f))
                    fill.color = if (seal) HET_SEAL else HET_STONE
                    drawRoundRect(box, cell * 0.4f, cell * 0.4f, fill)
                    if (!seal) { fill.color = YELLOW; drawCircle(box.centerX(), box.centerY(), cell * 0.6f, fill) }
                }
                // the beam: a soft glow with a white middle
                stroke.color = HET_GLOW; stroke.strokeWidth = cell * 0.5f
                drawPath(beam, stroke)
                stroke.color = Color.WHITE; stroke.strokeWidth = cell * 0.18f
                drawPath(beam, stroke)
                for (mirror in mirrors) {
                    val x = cx(mirror.x.toFloat()); val y = cy(mirror.y.toFloat())
                    val already = (mirror.x * ToaPuzzles.HET_SIZE + mirror.y) in fixed
                    if (small) { fill.color = if (already) HET_FIXED else YELLOW; drawCircle(x, y, cell * 1.1f, fill); continue }
                    // exactly the size of its square, so it shows which square to stand it on
                    drawHetMirror(this, x, y, cell * 0.5f, mirror.facing, already)
                }
                for (s in others) {
                    val facing = hetFacing[s]
                    if (facing != null) {
                        drawHetMirror(this, cx((s / ToaPuzzles.HET_SIZE).toFloat()), cy((s % ToaPuzzles.HET_SIZE).toFloat()), cell * 0.5f, facing, true)
                        continue
                    }
                    squareBox(s)
                    fill.color = HET_FIXED; drawRect(box, fill)
                    stroke.color = INK; stroke.strokeWidth = cell * 0.1f; drawRect(box, stroke)
                }
                // cracked walls the beam crosses get an orange border, on top of the beam: mine these
                if (walls != null && !small) for (s in squares) if (walls[s] == 2) {
                    squareBox(s)
                    box.inset(cell * 0.1f, cell * 0.1f)
                    stroke.color = HET_MINE; stroke.strokeWidth = cell * 0.2f
                    drawRect(box, stroke)
                }
            }
        }
    }

    // ---------------- Matching puzzle ----------------

    private fun LinearLayout.matching() {
        if (matchWatching) { matchingSmall(); return }
        tip("close your chat and inventory first, so both boards can be seen.")
        // (while watching, the page is just matchingSmall's Stop button)
        addView(bar(button("Watch", GO_GREEN) { watchMatching() } to 1.2f,
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
                tile(canvas, r, if (b == matchBrush) GO_GREEN else PAPER)
                when (b) {
                    in 0..8 -> glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[b], r, if (b == matchBrush) YELLOW else GLYPH_LIT)
                    9 -> text(canvas, "✓", r.centerX(), r.centerY(), r.height() * 0.55f, GO_GREEN)
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
        memory.startWatch(SystemClock.uptimeMillis())
        watchStarted = SystemClock.uptimeMillis()
        show()
        handler.postDelayed(matchTick, MATCH_INTERVAL_MS)
    }

    private fun stopMatchWatch(note: String?) {
        if (!matchWatching) return
        matchWatching = false
        handler.removeCallbacks(matchTick)
        endWatchWorker()
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
            watchWorker().execute {
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
            }
        }
    }

    // One background thread for Watch's looks, kept while watching (rather than a new one for each of the
    // ~1,000 looks in a 5-minute Watch), and ended when watching stops
    private var watcher: java.util.concurrent.ExecutorService? = null
    // (Normal priority, not background like the garden's savers: a look must be read before the player steps off the
    // tile, so it mustn't wait behind the game. It never keeps the app alive on its own.)
    private fun watchWorker() = watcher ?: java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ToaWatch").apply { isDaemon = true }
    }.also { watcher = it }
    private fun endWatchWorker() { watcher?.shutdown(); watcher = null }   // (a look still running finishes first)


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
                Page.HET -> if (hetAnswer >= 0) addView(HetMap(hetAnswer, hetRoom, small = false, maxHeightDp = HET_MAP_DP),
                    LinearLayout.LayoutParams(dp(HET_MAP_DP), dp(HET_MAP_DP)))
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
        // paints a part of the picture black (the app's own windows over the game), so it isn't read as the game
        fun blank(left: Int, top: Int, right: Int, bottom: Int) {
            val l = left.coerceIn(0, width); val r = right.coerceIn(l, width)
            for (y in maxOf(0, top) until minOf(height, bottom)) data.fill(0, y * width + l, y * width + r)
        }
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
