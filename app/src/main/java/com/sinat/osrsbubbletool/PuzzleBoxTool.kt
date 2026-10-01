package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

// The Puzzle Box Solver tool: finds the puzzle on screen, reads the tiles, solves it,
// and guides you with outlines on the puzzle while this window stays out of the way.
class PuzzleBoxTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit,
    private val hideWindow: () -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before taking a screenshot
        const val GRID = 5                 // puzzle boxes are 5 x 5
        const val MIN_FIT_CONFIDENCE = 2f  // below this, the line-up step is ignored
        const val TRACK_INTERVAL_MS = 80L  // how often to check the puzzle while guiding you
        const val SETTLE_MS = 160L         // a move counts once seen twice in a row, or after this long
        const val LOOKAHEAD = 6            // how many planned moves ahead a fast tapper can get
        const val LOST_AFTER_MS = 6_000L   // stop guiding if the tiles can't be read for this long
        const val GONE_READS = 2           // frame missing this many looks in a row (~0.16 s) = closed
        const val SOLVED_SHOW_MS = 2_500L  // how long "Solved!" stays up
        const val REPLAN_WAIT_MS = 150L    // let the outlines disappear before re-reading the tiles
    }

    private val handler = Handler(Looper.getMainLooper())
    private val puzzleArea = PuzzleAreaOverlay(context, windowManager)
    private val guide = MoveGuideOverlay(context, windowManager)
    private val prefs = context.getSharedPreferences("puzzle_box", Context.MODE_PRIVATE)
    private var styleButtons: List<TextView> = emptyList()
    private val references = PuzzleReferences(context)
    private var status: TextView? = null
    private var image: ImageView? = null
    private var scanButton: TextView? = null
    private var areaButton: TextView? = null
    private var destroyed = false

    // Kept so you can tap the result to compare with the full screenshot
    private var lastFullView: Bitmap? = null
    private var lastPuzzleView: Bitmap? = null
    private var showingFull = false

    // Guiding state
    private var lastArea: Rect? = null        // where the tiles are, in screenshot pixels
    private var puzzleName = ""
    private var solution: List<Int> = emptyList()
    private var step = 0
    private var currentEmpty = -1
    private var candidateEmpty = -1
    private var candidateSince = 0L
    private var candidateSeen = 0
    private var lastSeen = 0L
    private var tracking = false
    private var solving = false
    private var misses = 0                     // looks in a row where the puzzle's frame wasn't there
    private var finished = false               // solved, showing "Solved!" for a moment
    private var replanAt = 0L                  // when to re-read the tiles, or 0 if not needed
    private var planId = 0                     // changes whenever guiding stops, so old results are ignored

    init {
        capture.addStopListener { onCaptureStopped() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Window contents ----------------

    fun buildView(): View {
        fun button(label: String, size: Float, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#8B6B3E"))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }

        scanButton = button("Scan puzzle", 13f) { if (tracking || solving) stopGuide("Stopped.") else scan() }
        areaButton = button("Set area by hand", 11f) { toggleAreaSetup() }.apply {
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        // Guide style: coloured boxes around the tiles, or dots that shrink with each move
        guide.dots = prefs.getBoolean("dots", false)
        fun styleButton(label: String, dots: Boolean) = button(label, 11f) {
            guide.dots = dots
            prefs.edit().putBoolean("dots", dots).apply()
            refreshStyleButtons()
        }.apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        val boxesButton = styleButton("▢ Boxes", false)
        val dotsButton = styleButton("● Dots", true)
        styleButtons = listOf(boxesButton, dotsButton)
        refreshStyleButtons()
        val styleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Show moves as:"
                textSize = 11f
                setTextColor(Color.parseColor("#3E2C12"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(boxesButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            addView(dotsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        status = TextView(context).apply {
            text = "Open a puzzle box, then tap Scan puzzle. The moves appear on the puzzle."
            textSize = 11f
            setTextColor(Color.parseColor("#3E2C12"))
            setPadding(0, dp(6), 0, dp(6))
        }

        image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { toggleFullView() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2E3C0"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(scanButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(image, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(styleRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            addView(areaButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
    }

    // The chosen style's button is darker
    private fun refreshStyleButtons() {
        styleButtons.forEachIndexed { i, b ->
            val chosen = (i == 1) == guide.dots
            (b.background as? GradientDrawable)?.setColor(Color.parseColor(if (chosen) "#5A4220" else "#B89A63"))
            b.setTypeface(null, if (chosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ---------------- Puzzle area by hand (only needed if it can't be found automatically) ----------------

    private fun toggleAreaSetup() {
        if (puzzleArea.isShowing) {
            puzzleArea.saveAndHide()
            areaButton?.text = "Set area by hand"
            status?.text = "Area saved. Tap Scan puzzle."
        } else {
            stopGuide(null)
            puzzleArea.show()
            areaButton?.text = "Save area"
            status?.text = "Only needed if Scan can't find the puzzle. Drag the gold frame roughly " +
                "over the tiles, then tap Save area."
        }
    }

    // ---------------- Scanning ----------------

    private fun scan() {
        stopGuide(null)
        if (puzzleArea.isShowing) toggleAreaSetup() // save and hide the frame so it's not in the screenshot

        // Capture is off (first scan since the bubble started): ask Android once
        if (!capture.isActive) {
            status?.text = "Waiting for screen-capture permission..."
            capture.request { ok ->
                if (ok) scan()
                else status?.text = "Couldn't turn on screen capture: ${capture.lastError}."
            }
            return
        }

        status?.text = "Looking for the puzzle..."
        val id = planId   // changes if you stop, rotate or set the area meanwhile: then this scan is dropped
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            if (destroyed) return@postDelayed
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (id != planId) return@postDelayed
            if (shot == null) {
                status?.text = "Couldn't capture the screen. Try again."
                return@postDelayed
            }
            // Finding and lining up the puzzle takes a moment, so it's done in the background
            // (on the main thread it could make the game stutter)
            Thread {
                val found = PuzzleFinder.findTiles(LightBoxReader.Pixels(shot))
                val result = prepare(shot, found)
                handler.post { if (!destroyed && id == planId) showResult(result, id) }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    // What a scan found. rough: where the puzzle roughly is (null = not found); area: the tiles, lined up
    // exactly; puzzle: the tiles cut out (null = off the screen); the two views are what the window shows.
    private class Scan(val shot: Bitmap, val rough: Rect?, val area: Rect?, val lined: Boolean, val fitConfidence: Float,
                       val puzzle: Bitmap?, val fullView: Bitmap?, val puzzleView: Bitmap?)

    // The slow part of a scan (background thread): line the grid up and cut out the tiles
    private fun prepare(shot: Bitmap, found: Rect?): Scan {
        val rough = found ?: puzzleArea.savedArea(shot.width, shot.height)
            ?: return Scan(shot, null, null, false, 0f, null, null, null)

        // Line the grid up exactly. When the puzzle was found by its frame, that's already
        // exact, so the line-up is only used if it agrees closely.
        val fit = GridFinder.find(shot, rough)
        val fitWorked = fit != null && fit.confidence >= MIN_FIT_CONFIDENCE
        val area = when {
            found != null && fitWorked && closeTo(fit!!.area, found) -> fit.area
            found != null -> found
            fitWorked -> fit!!.area
            else -> rough
        }
        val lined = found != null || fitWorked
        val confidence = fit?.confidence ?: 0f

        val puzzle = crop(shot, area) ?: return Scan(shot, rough, area, lined, confidence, null, null, null)
        return Scan(shot, rough, area, lined, confidence, puzzle, drawFrames(shot, rough, area), drawGrid(puzzle))
    }

    private fun showResult(scan: Scan, id: Int) {
        if (scan.rough == null) {
            lastFullView = null
            lastPuzzleView = null
            image?.setImageBitmap(scan.shot)
            status?.text = "Couldn't find the puzzle. Make sure it's open and not covered, then scan again. " +
                "If it still isn't found, use Set area by hand."
            return
        }
        val area = scan.area
        val puzzle = scan.puzzle
        if (area == null || puzzle == null) {
            status?.text = "The puzzle area is off the screen. Scan again."
            return
        }
        lastArea = Rect(area)

        lastFullView = scan.fullView
        lastPuzzleView = scan.puzzleView
        showingFull = false
        image?.setImageBitmap(lastPuzzleView)

        val lined = scan.lined
        if (!lined) {
            val score = String.format(Locale.US, "%.1f", scan.fitConfidence)
            status?.text = "Couldn't line up the grid (confidence $score), so your frame was used."
        } else {
            status?.text = if (references.isLoaded) "Reading the tiles..."
                else "Downloading the puzzle pictures from the OSRS Wiki (first time only)..."
        }

        // Identify the tiles by comparing them with the wiki's solved pictures
        references.load { puzzles, error ->
            if (destroyed || id != planId) return@load   // stopped while the pictures were downloading
            if (puzzles == null) {
                status?.text = "Couldn't get the puzzle pictures from the wiki: $error. " +
                    "Check your internet connection and scan again."
                return@load
            }
            identify(puzzle, puzzles) { match ->
                if (id != planId) return@identify   // stopped meanwhile
                if (match == null) {
                    status?.text = "Couldn't read the tiles. Try scanning again."
                    return@identify
                }
                showMatch(match, lined)
            }
        }
    }

    // Two areas are the same puzzle position to within 4% of its size
    private fun closeTo(a: Rect, b: Rect): Boolean {
        val tol = b.width() * 0.04f
        return abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol &&
            abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol
    }

    private fun showMatch(match: TileMatcher.Result, lined: Boolean) {
        // (the picture is gone if the window was closed mid-scan: carry on without it)
        lastPuzzleView?.let { puzzleView ->
            lastPuzzleView = drawLabels(puzzleView, match.board, match.correctedPositions)
            if (!showingFull) image?.setImageBitmap(lastPuzzleView)
        }

        val diff = String.format(Locale.US, "%.1f", match.difference)
        val gridNote = if (lined) "" else " (Grid wasn't lined up, so the reading may be off.)"
        val fixNote = if (match.correctedPositions.isEmpty()) "" else
            " Two look-alike tiles were swapped to make it solvable (outlined in orange)."

        if (match.solvable) {
            status?.text = "${match.puzzleName} (difference $diff).$gridNote$fixNote Working out the moves..."
            solveAndGuide(match.board, match.puzzleName)
        } else {
            status?.text = "Looks like ${match.puzzleName} (difference $diff), but a tile was misread, " +
                "because this layout can't be solved.$gridNote Try scanning again."
        }
    }

    // ---------------- Solving and guiding ----------------

    private fun solveAndGuide(board: IntArray, name: String) {
        val id = planId
        solving = true
        scanButton?.text = "Stop"
        Thread {
            val moves = try { PuzzleSolver.solve(board) } catch (e: OutOfMemoryError) { null }   // a very hard scramble on a low-memory phone
            handler.post {
                if (id != planId || destroyed) return@post  // guiding was stopped meanwhile
                solving = false
                when {
                    moves == null -> stopGuide("Couldn't work out a solution. Try scanning again.")
                    moves.isEmpty() -> {
                        stopGuide("This puzzle is already solved!")
                    }
                    else -> startGuide(moves, board.indexOf(TileMatcher.EMPTY), name)
                }
            }
        }.start()
    }

    private fun startGuide(moves: List<Int>, emptyPosition: Int, name: String) {
        val area = lastArea ?: return
        val firstTime = !guide.isShowing
        puzzleName = name
        solution = moves
        step = 0
        currentEmpty = emptyPosition
        candidateEmpty = -1
        lastSeen = SystemClock.uptimeMillis()
        if (firstTime) guide.show(area)
        updateGuide()
        scanButton?.text = "Stop"
        if (!tracking) {
            tracking = true
            handler.postDelayed(trackRunnable, TRACK_INTERVAL_MS)
        }
        if (firstTime) hideWindow()   // out of the way; tap the bubble to bring it back
    }

    private fun updateGuide() {
        guide.setMoves(solution.drop(step))
        guide.setMessage("Move ${step + 1} of ${solution.size}")
        status?.text = "Move ${step + 1} of ${solution.size}: tap the tile outlined in green. " +
            "Yellow, orange and red are the next moves."
    }

    // Stops guiding. message = what to show in the window, or null to leave it as it is.
    private fun stopGuide(message: String?) {
        finished = false
        misses = 0
        tracking = false
        solving = false
        replanAt = 0L
        handler.removeCallbacks(trackRunnable)
        guide.hide()
        solution = emptyList()
        step = 0
        planId++
        scanButton?.text = "Scan puzzle"
        if (message != null) status?.text = message
    }

    private val trackRunnable = object : Runnable {
        override fun run() {
            if (!tracking) return
            trackOnce()
            if (tracking) handler.postDelayed(this, TRACK_INTERVAL_MS)
        }
    }

    // Checks whether the empty space has moved, reading only a few pixels per tile
    private fun trackOnce() {
        val area = lastArea ?: return
        val now = SystemClock.uptimeMillis()

        // You left the game (when only the game is shared): take the outlines off the screen and stop,
        // rather than leaving them over other apps and watching a screen that can't be seen
        if (capture.gameHidden) {
            stopGuide("You left the game, so the guide stopped. Tap Scan puzzle to carry on.")
            return
        }

        if (solving || replanAt != 0L) {
            // Time to re-read the tiles. This needs the newest picture of the screen, so it goes before the
            // check below, which would use that picture up and leave the re-plan waiting for another one.
            if (!solving && now >= replanAt) {
                replanNow(area)
                return
            }
            // While re-planning, still notice if the puzzle is closed, so the guide doesn't linger
            val gone = capture.sample { !PuzzleFinder.frameVisible(it, area) }
            if (gone == true && ++misses >= GONE_READS) {
                stopGuide("The puzzle was closed. Tap Scan puzzle to start again.")
                return
            }
            if (gone == false) misses = 0
            return
        }

        // -2 = the puzzle's frame is gone (closed); -1 = can't tell (a tile is sliding);
        // null = screen unchanged
        val empty = capture.sample {
            if (!PuzzleFinder.frameVisible(it, area)) -2
            else if (finished) -3 else PuzzleFinder.emptySpace(it, area, guide.coveredTiles)
        }
        if (empty == -2) {
            // closed: clear everything off the screen straight away
            if (++misses >= GONE_READS) {
                stopGuide(if (finished) "Puzzle solved! Scan again for another one."
                          else "The puzzle was closed. Tap Scan puzzle to start again.")
            }
            return
        }
        if (empty != null) misses = 0
        if (finished || empty == -3) return   // just watching for it to close
        if (empty == -1) {
            if (now - lastSeen > LOST_AFTER_MS) {
                stopGuide("Lost sight of the puzzle. If it's still open, tap Scan puzzle to carry on.")
            }
            return
        }
        lastSeen = now
        if (empty != null) {
            if (empty != candidateEmpty) {
                candidateEmpty = empty
                candidateSince = now
                candidateSeen = 1
            } else {
                candidateSeen++
            }
        }
        if (candidateEmpty == -1 || candidateEmpty == currentEmpty) return
        if (candidateSeen < 2 && now - candidateSince < SETTLE_MS) return
        onEmptyMoved(candidateEmpty)
    }

    // After a move, the empty space sits where the tapped tile was
    private fun onEmptyMoved(newEmpty: Int) {
        val remaining = solution.size - step
        for (k in 0 until minOf(LOOKAHEAD, remaining)) {
            if (solution[step + k] == newEmpty) {
                step += k + 1
                currentEmpty = newEmpty
                if (step >= solution.size) finish() else updateGuide()
                return
            }
        }
        // A different move was made: read the tiles again and work out a new plan from here
        currentEmpty = newEmpty
        guide.setMoves(emptyList())
        guide.setMessage("Re-planning...", MoveGuideOverlay.MOVE_COLORS[1])
        status?.text = "That wasn't the planned move, so the app is working out a new plan..."
        replanAt = SystemClock.uptimeMillis() + REPLAN_WAIT_MS
    }

    private fun replanNow(area: Rect) {
        val shot = capture.grab() ?: return   // wait for a fresh picture
        val puzzle = crop(shot, area) ?: return
        replanAt = 0L
        solving = true
        val id = planId   // changes if you stop or scan again meanwhile: then this re-plan is dropped
        references.load { puzzles, _ ->
            if (!tracking || id != planId) return@load
            if (puzzles == null) {
                stopGuide("Lost track of the puzzle. Tap Scan puzzle to start again.")
                return@load
            }
            identify(puzzle, puzzles) { match ->
                if (!tracking || id != planId) return@identify
                if (match == null || !match.solvable) {
                    stopGuide("Lost track of the puzzle. Tap Scan puzzle to start again.")
                    return@identify
                }
                solveAndGuide(match.board, match.puzzleName)
            }
        }
    }

    // Reads the tiles by comparing them with the solved pictures. It takes a moment, so it's done in the
    // background (on the main thread it could make the game stutter). onDone runs on the main thread.
    private fun identify(puzzle: Bitmap, puzzles: List<PuzzleReferences.Puzzle>, onDone: (TileMatcher.Result?) -> Unit) {
        Thread {
            val match = try { TileMatcher.identify(puzzle, puzzles) } catch (e: Exception) { null }
            handler.post { if (!destroyed) onDone(match) }
        }.start()
    }

    private fun finish() {
        finished = true   // keep watching only so it disappears the moment the puzzle closes
        guide.setMoves(emptyList())
        guide.setMessage("Solved!", MoveGuideOverlay.GREEN)
        status?.text = "Puzzle solved!"
        val id = planId
        handler.postDelayed({ if (id == planId) stopGuide("Puzzle solved! Scan again for another one.") }, SOLVED_SHOW_MS)
    }

    // ---------------- Pictures in the window ----------------

    // Writes each tile's solved position on top of it (1-24, a dot for the empty space).
    // Tiles in `highlight` get an orange outline.
    private fun drawLabels(source: Bitmap, board: IntArray, highlight: List<Int>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val tileW = out.width / GRID.toFloat()
        val tileH = out.height / GRID.toFloat()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = tileH * 0.4f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val outline = Paint(text).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = text.textSize / 6
        }
        val box = Paint().apply {
            color = Color.parseColor("#FF8C1A")
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, tileW / 12)
        }
        for (pos in highlight) {
            val left = (pos % GRID) * tileW
            val top = (pos / GRID) * tileH
            val inset = box.strokeWidth / 2
            canvas.drawRect(left + inset, top + inset, left + tileW - inset, top + tileH - inset, box)
        }
        for (pos in 0 until GRID * GRID) {
            val label = if (board[pos] == TileMatcher.EMPTY) "•" else "${board[pos] + 1}"
            val x = (pos % GRID) * tileW + tileW / 2
            val y = (pos / GRID) * tileH + tileH / 2 - (text.descent() + text.ascent()) / 2
            canvas.drawText(label, x, y, outline)
            canvas.drawText(label, x, y, text)
        }
        return out
    }

    // Full screenshot with the search area (blue) and the tiles used (gold) drawn on it
    private fun drawFrames(shot: Bitmap, rough: Rect, detected: Rect?): Bitmap {
        val out = shot.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = maxOf(2f, out.width / 300f)
        canvas.drawRect(rough, Paint().apply {
            color = Color.parseColor("#3FA9F5")
            style = Paint.Style.STROKE
            strokeWidth = stroke
        })
        detected?.let {
            canvas.drawRect(it, Paint().apply {
                color = Color.parseColor("#E8C766")
                style = Paint.Style.STROKE
                strokeWidth = stroke
            })
        }
        return out
    }

    private fun toggleFullView() {
        val full = lastFullView ?: return
        val puzzle = lastPuzzleView ?: return
        showingFull = !showingFull
        image?.setImageBitmap(if (showingFull) full else puzzle)
    }

    private fun crop(shot: Bitmap, area: Rect): Bitmap? {
        val r = Rect(area)
        if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < GRID || r.height() < GRID) return null
        return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
    }

    // Draws the 5 x 5 grid on top of the cut-out puzzle, so you can check the alignment
    private fun drawGrid(source: Bitmap): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            color = Color.parseColor("#E8C766")
            strokeWidth = maxOf(1f, out.width / 150f)
        }
        for (i in 1 until GRID) {
            val x = out.width * i / GRID.toFloat()
            val y = out.height * i / GRID.toFloat()
            canvas.drawLine(x, 0f, x, out.height.toFloat(), paint)
            canvas.drawLine(0f, y, out.width.toFloat(), y, paint)
        }
        return out
    }

    // Screen capture was turned off (for example from the status bar)
    private fun onCaptureStopped() {
        if (destroyed) return
        stopGuide("Screen capture is off. Tap Scan puzzle to turn it back on.")
    }

    // ---------------- Called by BubbleService ----------------

    // Closing the window keeps the guide going on the puzzle. Tap the bubble to bring
    // the window back, and Stop to end it.
    fun onWindowClosed() {
        if (puzzleArea.isShowing) {
            puzzleArea.hide()
            areaButton?.text = "Set area by hand"
        }
        // Let go of the scan pictures. The move guide over the game doesn't need them: it
        // keeps working from what it read, and checks the screen afresh as you move tiles.
        lastFullView = null
        lastPuzzleView = null
        showingFull = false
        image?.setImageDrawable(null)
    }

    fun onRotated() {
        stopGuide("Screen rotated. Tap Scan puzzle to start again.")
        if (puzzleArea.isShowing) {
            puzzleArea.hide()
            areaButton?.text = "Set area by hand"
        }
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)   // including a scan that was about to take its picture
        stopGuide(null)
        puzzleArea.hide()
    }
}
