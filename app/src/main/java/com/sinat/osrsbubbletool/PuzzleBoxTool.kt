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

// The Puzzle Box Solver tool: its window contents, screen capture, tile reading,
// solving, and the on-screen move guide.
class PuzzleBoxTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before taking a screenshot
        const val GRID = 5                 // puzzle boxes are 5 x 5
        const val MIN_FIT_CONFIDENCE = 2f  // below this, auto-fit is ignored and your frame is used
        const val TRACK_INTERVAL_MS = 250L // how often to check the puzzle while guiding you
        const val SETTLE_MS = 300L         // how long the empty space must stay put to count as a move
        const val LOOKAHEAD = 6            // how many planned moves ahead a fast tapper can get
    }

    private val handler = Handler(Looper.getMainLooper())
    private val puzzleArea = PuzzleAreaOverlay(context, windowManager)
    private val guide = MoveGuideOverlay(context, windowManager)
    private val references = PuzzleReferences(context)
    private var status: TextView? = null
    private var image: ImageView? = null
    private var areaButton: TextView? = null
    private var destroyed = false

    // Kept so you can tap the result to compare with the full screenshot
    private var lastFullView: Bitmap? = null
    private var lastPuzzleView: Bitmap? = null
    private var showingFull = false

    // Guiding state
    private var lastArea: Rect? = null        // where the puzzle is, in screenshot pixels
    private var lastCrop: Bitmap? = null      // most recent picture of the puzzle while guiding
    private var puzzleName = ""
    private var solution: List<Int> = emptyList()
    private var step = 0
    private var currentEmpty = -1
    private var candidateEmpty = -1
    private var candidateSince = 0L
    private var tracking = false
    private var solving = false
    private var planId = 0                     // changes whenever guiding stops, so old results are ignored

    init {
        capture.addStopListener { onCaptureStopped() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Window contents ----------------

    fun buildView(): View {
        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#8B6B3E"))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }

        val scanButton = button("Scan puzzle") { scan() }
        val setAreaButton = button("Set puzzle area") { toggleAreaSetup() }
        areaButton = setAreaButton

        status = TextView(context).apply {
            text = "Open a puzzle box in the game, then tap Scan puzzle."
            textSize = 13f
            setTextColor(Color.parseColor("#3E2C12"))
            setPadding(0, dp(8), 0, dp(8))
        }

        image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { toggleFullView() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2E3C0"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(scanButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(setAreaButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // ---------------- Puzzle area setup ----------------

    private fun toggleAreaSetup() {
        if (puzzleArea.isShowing) {
            puzzleArea.saveAndHide()
            areaButton?.text = "Set puzzle area"
            status?.text = "Puzzle area saved. Tap Scan puzzle."
        } else {
            stopGuide()
            puzzleArea.show()
            areaButton?.text = "Save puzzle area"
            status?.text = "Drag the gold frame so it roughly covers the puzzle tiles. " +
                "It doesn't need to be exact; the app lines it up for you when you scan. " +
                "Then tap Save puzzle area."
        }
    }

    // ---------------- Scanning ----------------

    private fun scan() {
        stopGuide()
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

        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (shot == null) {
                status?.text = "Couldn't capture the screen. Try again."
            } else {
                showResult(shot)
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun showResult(shot: Bitmap) {
        val rough = puzzleArea.savedArea(shot.width, shot.height)
        if (rough == null) {
            lastFullView = null
            lastPuzzleView = null
            image?.setImageBitmap(shot)
            status?.text = "Screen captured. Now tap Set puzzle area and drag the frame roughly over the puzzle."
            return
        }

        // Find the exact grid near the rough frame
        val fit = GridFinder.find(shot, rough)
        val autoFitWorked = fit != null && fit.confidence >= MIN_FIT_CONFIDENCE
        val area = if (autoFitWorked) fit!!.area else rough

        val puzzle = crop(shot, area)
        if (puzzle == null) {
            status?.text = "The puzzle area is off the screen. Tap Set puzzle area and move the frame."
            return
        }
        lastArea = Rect(area)

        lastFullView = drawFrames(shot, rough, fit?.area)
        lastPuzzleView = drawGrid(puzzle)
        showingFull = false
        image?.setImageBitmap(lastPuzzleView)

        val score = String.format(Locale.US, "%.1f", fit?.confidence ?: 0f)
        if (!autoFitWorked) {
            status?.text = "Couldn't line up the grid automatically (confidence $score), so your frame was used. " +
                "Tap the picture to see the full screen: blue = your frame, gold = detected grid."
        } else {
            status?.text = if (references.isLoaded) "Reading the tiles..."
                else "Downloading the puzzle pictures from the OSRS Wiki (first time only)..."
        }

        // Identify the tiles by comparing them with the wiki's solved pictures
        references.load { puzzles, error ->
            if (puzzles == null) {
                status?.text = "Couldn't get the puzzle pictures from the wiki: $error. " +
                    "Check your internet connection and scan again."
                return@load
            }
            val match = TileMatcher.identify(puzzle, puzzles)
            if (match == null) {
                status?.text = "Couldn't read the tiles. Try scanning again."
                return@load
            }
            showMatch(match, autoFitWorked)
        }
    }

    private fun showMatch(match: TileMatcher.Result, autoFitWorked: Boolean) {
        val puzzleView = lastPuzzleView ?: return
        lastPuzzleView = drawLabels(puzzleView, match.board, match.correctedPositions)
        if (!showingFull) image?.setImageBitmap(lastPuzzleView)

        val diff = String.format(Locale.US, "%.1f", match.difference)
        val gridNote = if (autoFitWorked) "" else " (Grid wasn't auto-aligned, so the reading may be off.)"
        val fixNote = if (match.correctedPositions.isEmpty()) "" else
            " Two similar-looking tiles were swapped to make the layout solvable; they're outlined in orange."

        if (match.solvable) {
            status?.text = "Puzzle: ${match.puzzleName} (difference $diff).$gridNote$fixNote Working out the moves..."
            solveAndGuide(match.board, match.puzzleName)
        } else {
            status?.text = "Puzzle looks like ${match.puzzleName} (difference $diff), but at least one tile " +
                "was misread, because this layout couldn't be solved.$gridNote Try scanning again."
        }
    }

    // ---------------- Solving and guiding ----------------

    private fun solveAndGuide(board: IntArray, name: String) {
        val id = planId
        solving = true
        Thread {
            val moves = PuzzleSolver.solve(board)
            handler.post {
                if (id != planId || destroyed) return@post  // guiding was stopped meanwhile
                solving = false
                when {
                    moves == null -> {
                        stopGuide()
                        status?.text = "Couldn't work out a solution. Try scanning again."
                    }
                    moves.isEmpty() -> {
                        stopGuide()
                        status?.text = "This puzzle is already solved!"
                    }
                    else -> startGuide(moves, board.indexOf(TileMatcher.EMPTY), name)
                }
            }
        }.start()
    }

    private fun startGuide(moves: List<Int>, emptyPosition: Int, name: String) {
        val area = lastArea ?: return
        puzzleName = name
        solution = moves
        step = 0
        currentEmpty = emptyPosition
        candidateEmpty = -1
        if (!guide.isShowing) {
            val offset = puzzleArea.windowOffset()
            guide.show(area, offset.x, offset.y)
        }
        updateGuide()
        if (!tracking) {
            tracking = true
            handler.postDelayed(trackRunnable, TRACK_INTERVAL_MS)
        }
    }

    private fun updateGuide() {
        guide.setMoves(solution.drop(step))
        status?.text = "Move ${step + 1} of ${solution.size}: tap the tile outlined in green. " +
            "Yellow, red and white show the next moves. " +
            "Keep this window open; the app follows your moves automatically."
    }

    private fun stopGuide() {
        tracking = false
        solving = false
        handler.removeCallbacks(trackRunnable)
        guide.hide()
        solution = emptyList()
        step = 0
        planId++
    }

    private val trackRunnable = object : Runnable {
        override fun run() {
            if (!tracking) return
            trackOnce()
            if (tracking) handler.postDelayed(this, TRACK_INTERVAL_MS)
        }
    }

    // Looks at the puzzle and checks whether the empty space has moved
    private fun trackOnce() {
        if (solving) return
        val area = lastArea ?: return
        val shot = capture.grab()   // null means nothing on screen changed
        if (shot != null) {
            val puzzle = crop(shot, area) ?: return
            lastCrop = puzzle
            val empty = TileMatcher.findEmpty(puzzle, puzzleName)
            if (empty == null) {
                candidateEmpty = -1   // a tile is probably mid-slide
            } else if (empty != candidateEmpty) {
                candidateEmpty = empty
                candidateSince = SystemClock.uptimeMillis()
            }
        }
        if (candidateEmpty == -1 || candidateEmpty == currentEmpty) return
        if (SystemClock.uptimeMillis() - candidateSince < SETTLE_MS) return
        onEmptyMoved(candidateEmpty)
    }

    // After a move, the empty space sits where the tapped tile was
    private fun onEmptyMoved(newEmpty: Int) {
        val remaining = solution.size - step
        for (k in 0 until minOf(LOOKAHEAD, remaining)) {
            if (solution[step + k] == newEmpty) {
                step += k + 1
                currentEmpty = newEmpty
                if (step >= solution.size) {
                    stopGuide()
                    status?.text = "Puzzle solved! Scan again for another puzzle, or close this window."
                } else {
                    updateGuide()
                }
                return
            }
        }
        replan()
    }

    // A different move was made: read the tiles again and work out a new plan from here
    private fun replan() {
        val puzzle = lastCrop ?: return
        solving = true
        currentEmpty = candidateEmpty
        guide.setMoves(emptyList())
        status?.text = "That wasn't the planned move, so the app is working out a new plan..."
        references.load { puzzles, _ ->
            if (!tracking) return@load
            val match = if (puzzles == null) null else TileMatcher.identify(puzzle, puzzles)
            if (match == null || !match.solvable) {
                stopGuide()
                status?.text = "Lost track of the puzzle. Tap Scan puzzle to start again."
                return@load
            }
            solveAndGuide(match.board, match.puzzleName)
        }
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

    // Full screenshot with your frame (blue) and the detected grid (gold) drawn on it
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
        stopGuide()
        status?.text = "Screen capture is off. Tap Scan puzzle to turn it back on."
    }

    // ---------------- Called by BubbleService ----------------

    // Closing the window stops the move guide, but screen capture stays on for next time
    fun onWindowClosed() {
        stopGuide()
        if (puzzleArea.isShowing) toggleAreaSetup()
    }

    fun onRotated() {
        stopGuide()
        if (puzzleArea.isShowing) {
            puzzleArea.hide()
            areaButton?.text = "Set puzzle area"
            status?.text = "Screen rotated. Tap Set puzzle area to place the frame again."
        }
    }

    fun destroy() {
        destroyed = true
        stopGuide()
        puzzleArea.hide()
    }
}
