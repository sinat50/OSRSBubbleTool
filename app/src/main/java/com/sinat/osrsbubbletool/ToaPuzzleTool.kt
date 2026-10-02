package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import androidx.core.graphics.withTranslation
import com.sinat.osrsbubbletool.ToaPuzzles.Symbol

// ToA Puzzle Helper: the five Path of Scabaras puzzles in the Tombs of Amascut. You tap in what you see
// on a small map of the room, and the answer is marked on the same map. What you've entered is kept while
// the bubble is running, so you can close the window between puzzles.
class ToaPuzzleTool(private val context: Context) {

    companion object {
        private val PARCHMENT = "#F2E3C0".toColorInt()
        private val DARK_BROWN = "#3E2C12".toColorInt()
        private val BUTTON_BROWN = "#8B6B3E".toColorInt()
        private val SELECTED = "#3E7A2E".toColorInt()
        private val PAPER = "#FFF8E6".toColorInt()
        private val FADED = "#8C7B5E".toColorInt()
        private val WARN = "#9C4A10".toColorInt()

        // The maps
        private val TILE = "#D9C49A".toColorInt()          // a plain floor tile
        private val TILE_DARK = "#6E5E48".toColorInt()     // an unlit plate or obelisk
        private val TILE_LIT = "#F2C230".toColorInt()      // a lit plate, tile or obelisk
        private val TILE_DONE = "#B7D7A8".toColorInt()     // a matched tile
        private val GLYPH = "#7A5A12".toColorInt()         // the carved symbols
        private val ANSWER_RED = "#D0312D".toColorInt()    // "step here"
        private val MISS_GREY = "#9A9A9A".toColorInt()

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

    // ---------------- What you've entered (kept while the bubble runs) ----------------

    private var lightLit = 0                          // lit plates, one bit each
    private var additionTarget = 0                    // the tablet's number, 0 = not picked yet
    private val additionLit = mutableSetOf<Int>()     // tiles already lit (walked on)
    private val sequence = mutableListOf<Int>()       // the tiles that lit up, in order
    private val obeliskOrder = mutableListOf<Int>()   // the right order, as far as it's known
    private val obeliskMisses = mutableSetOf<Int>()   // wrong guesses for the next obelisk
    private val obeliskUndo = ArrayDeque<Pair<List<Int>, Set<Int>>>()
    private val matchSymbols = HashMap<Int, Symbol>() // what you've seen on each matching tile
    private val matchDone = mutableSetOf<Int>()       // matched tiles
    private var matchBrush = 0                        // what a tap does: 0-8 a symbol, 9 matched, 10 erase

    // Which way the addition map faces: how many quarter turns from north at the top. Starts with east
    // at the top, as you see the room walking in towards the exit.
    private var additionTurns: Int
        get() = prefs.getInt("addition_turns", 1)
        set(v) { prefs.edit { putInt("addition_turns", v) } }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The window's ◀ button: from a puzzle back to the list
    fun goBack() {
        if (page != Page.MENU) { page = Page.MENU; show(keepScroll = false) }
    }

    private fun open(p: Page) { page = p; show(keepScroll = false) }

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
        addView(label("Tombs of Amascut: Path of Scabaras", 15f, bold = true), full())
        addView(label("Pick the puzzle you're in. Tap what you see on the map, and the answer is marked on it.", 12f), full(4))
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
        heading(Page.LIGHT)
        addView(label("Tap every plate that's lit (yellow) right now. Then step on the plates circled in red, in any order.", 12f), full(4))
        addView(Board(3, 3, (0 until 8).map { ToaPuzzles.lightCell(it) }, onTap = { i ->
            lightLit = lightLit xor (1 shl i); show()
        }) { canvas, i, r ->
            tile(canvas, r, if (lightLit shr i and 1 == 1) TILE_LIT else TILE_DARK)
            if (answer shr i and 1 == 1) ring(canvas, r, ANSWER_RED)
        }, full(8))
        val steps = Integer.bitCount(answer)
        addView(answerCard(if (steps == 0) "All eight plates are lit: done! ✓" else "Step on the $steps circled plate${if (steps == 1) "" else "s"}."), full(8))
        addView(label("Stepping on a plate switches it and the two plates beside it.", 11f).apply { setTextColor(FADED) }, full(6))
        addView(button("Clear: all plates unlit") { lightLit = 0; show() }, full(8))
    }

    // ---------------- Addition puzzle ----------------

    private fun LinearLayout.addition() {
        heading(Page.ADDITION)
        addView(label("Read the tablet (it opens once the other puzzles are done), then pick its number:", 12f), full(4))
        choiceGrid(this, (ToaPuzzles.ADDITION_MIN..ToaPuzzles.ADDITION_MAX).map { "$it" }, additionTarget - ToaPuzzles.ADDITION_MIN, perRow = 7) {
            additionTarget = ToaPuzzles.ADDITION_MIN + it; show()
        }
        if (additionTarget == 0) return

        val turns = additionTurns
        val top = listOf("North", "East", "South", "West")[turns]
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Top of the map: $top. Turn it until the symbols match what you see.", 11f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Turn ↻") { additionTurns = (turns + 3) % 4; show() }.apply { textSize = 12f })
        }, full(10))

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
            tile(canvas, r, if (i in additionLit) TILE_LIT else TILE)
            glyph(canvas, sym, r, GLYPH)
            text(canvas, values[sym.ordinal], r.left + r.width() * 0.17f, r.top + r.height() * 0.2f, r.height() * 0.22f, DARK_BROWN)
            if (answer != null && i in answer) ring(canvas, r, ANSWER_RED)
        }, full(6))

        val total = ToaPuzzles.additionTotal(additionLit)
        addView(answerCard(when {
            total == additionTarget -> "That's $additionTarget: done! ✓"
            answer == null && total > additionTarget -> "That's $total, over $additionTarget. When the tiles go dark, tap Clear and start again."
            answer == null -> "No walk adds up from here. When the tiles go dark, tap Clear and start again."
            else -> "Walk onto the circled tiles one at a time, without crossing any others."
        }), full(8))
        addView(label("Lit so far: $total of $additionTarget. If you walk a different way, tap each tile as it lights up and the walk is worked out again.", 11f)
            .apply { setTextColor(FADED) }, full(6))
        addView(button("Clear: no tiles lit") { additionLit.clear(); show() }, full(8))
    }

    // ---------------- Sequence puzzle ----------------

    private fun LinearLayout.sequence() {
        heading(Page.SEQUENCE)
        addView(label("Press the button, then tap each tile as it lights up. Then step on them in the same order.", 12f), full(4))
        // Each tile's number(s) in the order, worked out here so drawing makes nothing new
        val numbers = (0 until 9).map { t -> sequence.indices.filter { sequence[it] == t }.joinToString(",") { "${it + 1}" } }
        addView(Board(5, 5, ToaPuzzles.SEQUENCE_CELLS, onTap = { i ->
            if (sequence.size < ToaPuzzles.SEQUENCE_LENGTH) { sequence.add(i); show() }
        }) { canvas, i, r ->
            val n = numbers[i]
            tile(canvas, r, if (n.isEmpty()) TILE_DARK else TILE_LIT)
            if (n.isNotEmpty()) text(canvas, n, r.centerX(), r.centerY(), r.height() * if (n.length > 2) 0.3f else 0.5f, DARK_BROWN)
        }, full(8))
        addView(answerCard(when (sequence.size) {
            0 -> "Waiting for the first tile."
            ToaPuzzles.SEQUENCE_LENGTH -> "Step on the tiles from 1 to ${ToaPuzzles.SEQUENCE_LENGTH}."
            else -> "${sequence.size} of ${ToaPuzzles.SEQUENCE_LENGTH} tiles."
        }), full(8))
        addView(row(button("Undo") { sequence.removeLastOrNull(); show() },
            button("Clear") { sequence.clear(); show() }), full(8))
        addView(label("Forgot it? Press the button again for a new order, and tap Clear first.", 11f).apply { setTextColor(FADED) }, full(6))
    }

    // ---------------- Obelisk puzzle ----------------

    private fun LinearLayout.obelisk() {
        heading(Page.OBELISK)
        addView(label("After each hit, tap the obelisk if it lit up, or hold your finger on it if it didn't. " +
            "Left and right are as you face in from the entrance.", 12f), full(4))
        val choices = ToaPuzzles.obeliskChoices(obeliskOrder, obeliskMisses)
        val numbers = (0 until ToaPuzzles.OBELISKS).map { o -> obeliskOrder.indexOf(o).let { if (it < 0) "" else "${it + 1}" } }
        fun remember() { obeliskUndo.addLast(obeliskOrder.toList() to obeliskMisses.toSet()) }
        val cells = (0 until ToaPuzzles.OBELISKS).map { o -> (o % 3) to if (o < 3) 0 else 2 }
        addView(Board(3, 3, cells, maxHeightDp = 200,
            onLong = { o -> if (o !in obeliskOrder) { remember(); if (!obeliskMisses.add(o)) obeliskMisses.remove(o); show() } },
            onTap = { o -> if (o !in obeliskOrder) { remember(); obeliskOrder.add(o); obeliskMisses.clear(); show() } }
        ) { canvas, o, r ->
            val tall = obeliskRect(r)
            val n = numbers[o]
            tile(canvas, tall, if (n.isNotEmpty()) TILE_LIT else TILE_DARK)
            when {
                n.isNotEmpty() -> text(canvas, n, tall.centerX(), tall.centerY(), r.height() * 0.45f, DARK_BROWN)
                o in obeliskMisses -> text(canvas, "✗", tall.centerX(), tall.centerY(), r.height() * 0.45f, MISS_GREY)
                else -> text(canvas, "?", tall.centerX(), tall.centerY(), r.height() * 0.4f, Color.WHITE)
            }
        }, full(8))
        val order = obeliskOrder.joinToString(" → ") { "${it + 1}" }
        addView(answerCard(when {
            obeliskOrder.isEmpty() && obeliskMisses.isEmpty() -> "Hit any obelisk to start."
            choices.isEmpty() || obeliskOrder.size == ToaPuzzles.OBELISKS -> "All found: hit them in order 1 to ${obeliskOrder.size}."
            obeliskOrder.isEmpty() -> "Try one of the obelisks marked ?."
            else -> "Hit the numbered ones in order, then try one marked ?."
        }), full(8))
        if (order.isNotEmpty()) addView(label("Found so far: ${obeliskOrder.size} obelisk${if (obeliskOrder.size == 1) "" else "s"}.", 11f).apply { setTextColor(FADED) }, full(6))
        addView(row(button("Undo") { obeliskUndo.removeLastOrNull()?.let { (o, m) ->
                obeliskOrder.clear(); obeliskOrder.addAll(o); obeliskMisses.clear(); obeliskMisses.addAll(m); show() } },
            button("Clear") { remember(); obeliskOrder.clear(); obeliskMisses.clear(); show() }), full(8))
    }

    // An obelisk is drawn tall and thin inside its square (one shared rectangle, so drawing makes nothing new)
    private val obeliskBox = RectF()
    private fun obeliskRect(r: RectF) = obeliskBox.apply { set(r.centerX() - r.width() * 0.22f, r.top, r.centerX() + r.width() * 0.22f, r.bottom) }

    // ---------------- Matching puzzle ----------------

    private fun LinearLayout.matching() {
        heading(Page.MATCHING)
        addView(label("Each board has each symbol once. Pick a symbol below, then tap the tile you saw it on. " +
            "When a symbol is found on both boards, the pair is circled in the same colour: step on both.", 12f), full(4))

        val pairs = ToaPuzzles.matchingPairs(matchSymbols, matchDone)
        val pairColour = HashMap<Int, Int>()
        pairs.forEach { (a, b) -> PAIR_COLOURS[ToaPuzzles.MATCHING_SYMBOLS.indexOf(matchSymbols[a])].let { pairColour[a] = it; pairColour[b] = it } }
        // Left board in columns 0-2, right board in columns 4-6 (the statue sits between them)
        val cells = (0 until 18).map { t -> (t % 9) / 3 to (t % 3) + if (t < 9) 0 else 4 }
        addView(Board(3, 7, cells, maxHeightDp = 150, onTap = { t -> tapMatching(t); show() }) { canvas, t, r ->
            tile(canvas, r, if (t in matchDone) TILE_DONE else TILE)
            matchSymbols[t]?.let { glyph(canvas, it, r, if (t in matchDone) FADED else GLYPH) }
            pairColour[t]?.let { ring(canvas, r, it) }
        }, full(8))

        // The brushes: nine symbols, then "matched" and "erase"
        val brushes = (0 until 11).map { 0 to it }
        addView(label("Tap a tile to:", 11f, bold = true).apply { setTextColor(FADED) }, full(8))
        addView(Board(1, 11, brushes, maxHeightDp = 44, onTap = { b -> matchBrush = b; show() }) { canvas, b, r ->
            tile(canvas, r, if (b == matchBrush) TILE_LIT else PAPER)
            when (b) {
                in 0..8 -> glyph(canvas, ToaPuzzles.MATCHING_SYMBOLS[b], r, GLYPH)
                9 -> text(canvas, "✓", r.centerX(), r.centerY(), r.height() * 0.55f, SELECTED)
                else -> text(canvas, "✗", r.centerX(), r.centerY(), r.height() * 0.55f, WARN)
            }
        }, full(2))
        addView(label(when (matchBrush) {
            in 0..8 -> "Mark it as ${ToaPuzzles.MATCHING_SYMBOLS[matchBrush].label.lowercase()}"
            9 -> "Mark it (and its pair) as matched"
            else -> "Rub it out"
        }, 11f), full(2))

        val names = pairs.joinToString(", ") { matchSymbols[it.first]!!.label.lowercase() }
        addView(answerCard(when {
            matchDone.size == 18 -> "All nine pairs matched: done! ✓"
            pairs.isEmpty() -> "No pairs found yet. Step on tiles to see their symbols."
            else -> "Pairs to step on: $names."
        }), full(8))
        addView(label("Solo: the line, crook, hand and bird pairs start matched.", 11f).apply { setTextColor(FADED) }, full(6))
        addView(button("Clear both boards") { matchSymbols.clear(); matchDone.clear(); show() }, full(8))
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

    private fun ring(canvas: Canvas, r: RectF, colour: Int) {
        stroke.color = colour
        stroke.strokeWidth = r.width() * 0.1f
        val corner = r.width() * 0.12f
        canvas.drawRoundRect(r, corner, corner, stroke)
    }

    private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int) {
        textPaint.color = colour
        textPaint.textSize = size
        canvas.drawText(s, x, y - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
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
            Symbol.KNIVES to Glyph(poly(0.16f, 0.5f, 0.42f, 0.18f, 0.42f, 0.82f).apply {
                addPath(poly(0.52f, 0.5f, 0.8f, 0.18f, 0.8f, 0.82f)) }, false),
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

        private fun cellAt(e: MotionEvent) = rects.indexOfFirst { it.contains(e.x, e.y) }.takeIf { it >= 0 }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val cell = minOf(w.toFloat() / cols, dp(maxHeightDp).toFloat() / rows)
            setMeasuredDimension(w, (cell * rows).toInt())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val cell = minOf(w.toFloat() / cols, h.toFloat() / rows)
            val left = (w - cell * cols) / 2f
            val pad = cell * 0.06f
            cells.forEachIndexed { i, (r, c) ->
                rects[i].set(left + c * cell + pad, r * cell + pad, left + (c + 1) * cell - pad, (r + 1) * cell - pad)
            }
        }

        override fun onDraw(canvas: Canvas) {
            for (i in rects.indices) drawCell(canvas, i, rects[i])
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean { detector.onTouchEvent(event); return true }
    }

    // ---------------- Small building blocks ----------------

    private fun scrolling(build: LinearLayout.() -> Unit) = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
            build()
        })
    }

    private fun LinearLayout.heading(p: Page) = addView(label(p.title, 15f, bold = true), full())

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

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
    }

    private fun answerCard(text: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
        addView(label(text, 13f, bold = true))
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
