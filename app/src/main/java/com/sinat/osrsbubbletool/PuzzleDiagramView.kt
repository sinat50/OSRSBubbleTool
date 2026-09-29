package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Draws a puzzle's answer as a simple map: north is always up.
class PuzzleDiagramView(context: Context, private val diagram: QuestPuzzles.Diagram, private val step: Int) : View(context) {

    companion object {
        private val BG = Color.parseColor("#E9DAB5")
        private val GRID = Color.parseColor("#CDB888")
        private val INK = Color.parseColor("#3E2C12")
        private val FADED = Color.parseColor("#9C8A68")
        private val HIGHLIGHT = Color.parseColor("#FFB000")
        private val BEAM = Color.parseColor("#F5C400")
        private val PLAYER = Color.parseColor("#1F6FD1")
        private val RUBBLE = Color.parseColor("#8A7456")
        private val TREE = Color.parseColor("#4E7A34")
        private val STUMP = Color.parseColor("#8B5A2B")
        private val MARK = Color.parseColor("#3E7A2E")
    }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textAlign = Paint.Align.CENTER }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // a row of book symbols is wide and short; everything else is roughly square
        setMeasuredDimension(w, (w * if (diagram is QuestPuzzles.Diagram.Glyphs) 0.42f else 0.95f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        when (diagram) {
            is QuestPuzzles.Diagram.Light -> drawLight(canvas, diagram.puzzle)
            is QuestPuzzles.Diagram.Tiles -> drawTiles(canvas, diagram.steps, diagram.kind)
            is QuestPuzzles.Diagram.Marked -> drawMarked(canvas, diagram)
            is QuestPuzzles.Diagram.Power -> drawPower(canvas, diagram)
            QuestPuzzles.Diagram.PipeMachine -> drawPipeMachine(canvas)
            is QuestPuzzles.Diagram.Pattern -> drawPattern(canvas, diagram)
            is QuestPuzzles.Diagram.Glyphs -> drawGlyphs(canvas, diagram)
        }
        if (diagram is QuestPuzzles.Diagram.Light || diagram is QuestPuzzles.Diagram.Tiles) drawNorth(canvas)
    }

    // A little "N ↑" in the top-right corner
    private fun drawNorth(canvas: Canvas) {
        val x = width - 14 * density
        val y = 8 * density
        line.color = INK; line.strokeWidth = 1.5f * density
        canvas.drawLine(x, y + 16 * density, x, y + 4 * density, line)
        canvas.drawLine(x, y + 4 * density, x - 3 * density, y + 8 * density, line)
        canvas.drawLine(x, y + 4 * density, x + 3 * density, y + 8 * density, line)
        text.textSize = 9 * density; text.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("N", x, y + 26 * density, text)
    }

    // ---------------- Song of the Elves light puzzles ----------------

    private fun drawLight(canvas: Canvas, puzzle: QuestPuzzles.LightPuzzle) {
        val steps = puzzle.steps
        val at = step.coerceIn(0, steps.size - 1)
        val floor = steps[at].floor
        // the library, in game tiles
        val x0 = 2556f; val x1 = 2690f; val y0 = 6082f; val y1 = 6210f
        val top = 20 * density
        val scale = min((width - 8 * density) / (x1 - x0), (height - top - 4 * density) / (y1 - y0))
        val left = (width - (x1 - x0) * scale) / 2f
        fun px(x: Int) = left + (x - x0 + 0.5f) * scale
        fun py(y: Int) = top + (y1 - y - 0.5f) * scale
        val cell = 14 * scale   // the space between pillars

        text.textSize = 12 * density; text.typeface = Typeface.DEFAULT_BOLD; text.color = INK
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(when (floor) { 0 -> "Bottom floor"; 1 -> "Middle floor"; else -> "Top floor" },
            8 * density, 15 * density, text)
        text.textAlign = Paint.Align.CENTER

        // where the seal you're aiming for is
        val target = "Seal of ${puzzle.clan}"
        QuestPuzzles.SOTE_LANDMARKS.firstOrNull { it.label == target }?.let { seal ->
            val label = if (seal.floor == floor) target
                else "Seal: " + when (seal.floor) { 0 -> "bottom floor"; 1 -> "middle floor"; else -> "top floor" }
            text.textSize = 10 * density; text.textAlign = Paint.Align.RIGHT; text.color = Color.parseColor("#B03A2E")
            val tx = width - 28 * density
            canvas.drawText(label, tx, 15 * density, text)
            text.textAlign = Paint.Align.CENTER; text.color = INK
            if (seal.floor == floor) {
                line.color = Color.parseColor("#B03A2E"); line.strokeWidth = 2f * density
                canvas.drawCircle(tx - text.measureText(label) - 9 * density, 11 * density, 5 * density, line)
            }
        }

        // the edge of the library
        line.color = GRID; line.strokeWidth = 1.5f * density
        canvas.drawRoundRect(RectF(px(2562), py(6205), px(2686), py(6086)), 6 * density, 6 * density, line)

        // landmarks: stairs, the dispenser, the seals
        for (m in QuestPuzzles.SOTE_LANDMARKS) {
            if (m.floor != floor) continue
            drawLandmark(canvas, m, px(m.x), py(m.y), cell, m.label == target)
        }

        // every pillar on this floor
        for (p in QuestPuzzles.SOTE_PILLARS) {
            if (p.floor != floor) continue
            fill.color = GRID
            canvas.drawCircle(px(QuestPuzzles.pillarX(p.col)), py(QuestPuzzles.pillarY(p.row)), cell * 0.14f, fill)
        }

        val onFloor = steps.withIndex().filter { it.value.floor == floor }
        fun sx(s: QuestPuzzles.LightStep) = px(QuestPuzzles.pillarX(s.col))
        fun sy(s: QuestPuzzles.LightStep) = py(QuestPuzzles.pillarY(s.row))

        // pieces still to come: faint outlines with their step number
        for ((i, s) in onFloor) {
            if (i <= at) continue
            line.color = FADED; line.strokeWidth = 1.2f * density
            canvas.drawCircle(sx(s), sy(s), cell * 0.3f, line)
            drawNumber(canvas, i + 1, sx(s), sy(s), cell, faded = true)
        }

        // the light so far on this floor
        val done = onFloor.filter { it.index <= at }
        line.color = BEAM; line.strokeWidth = cell * 0.09f
        for (i in 0 until done.size - 1) {
            val a = done[i].value; val b = done[i + 1].value
            if (done[i + 1].index != done[i].index + 1) continue   // not placed one after the other
            if (a.col != b.col && a.row != b.row) continue            // a new beam, not a straight line
            canvas.drawLine(sx(a), sy(a), sx(b), sy(b), line)
        }
        for ((i, s) in done) {
            drawPiece(canvas, s, sx(s), sy(s), cell, current = i == at)
            drawNumber(canvas, i + 1, sx(s), sy(s), cell, faded = false)
        }
    }

    private fun drawNumber(canvas: Canvas, n: Int, x: Float, y: Float, cell: Float, faded: Boolean) {
        text.textSize = max(8 * density, cell * 0.3f); text.typeface = Typeface.DEFAULT_BOLD
        text.color = if (faded) FADED else INK
        canvas.drawText("$n", x + cell * 0.42f, y - cell * 0.3f, text)
        text.color = INK
    }

    private fun drawLandmark(canvas: Canvas, m: QuestPuzzles.Landmark, x: Float, y: Float, cell: Float, target: Boolean) {
        val r = cell * 0.32f
        when (m.kind) {
            "stairs" -> {
                // three little steps
                line.color = INK; line.strokeWidth = 1.3f * density
                for (k in 0..2) canvas.drawLine(x - r + k * r * 0.66f, y + r - k * r * 0.66f, x - r + (k + 1) * r * 0.66f, y + r - k * r * 0.66f, line)
                for (k in 0..2) canvas.drawLine(x - r + (k + 1) * r * 0.66f, y + r - k * r * 0.66f, x - r + (k + 1) * r * 0.66f, y + r - (k + 1) * r * 0.66f, line)
            }
            "dispenser", "exit", "handhold" -> {
                fill.color = Color.parseColor("#B89A63")
                canvas.drawRect(x - r, y - r * 0.7f, x + r, y + r * 0.7f, fill)
            }
            "seal" -> {
                line.color = if (target) Color.parseColor("#B03A2E") else FADED
                line.strokeWidth = (if (target) 3f else 1.5f) * density
                canvas.drawCircle(x, y, r * if (target) 1.3f else 1f, line)
            }
        }
        // small faint labels (the seal you're aiming for is named at the top instead)
        if (m.kind == "seal" && m.label != "Seal of the Forgotten") return
        text.textSize = 8.5f * density
        text.typeface = Typeface.DEFAULT
        text.color = FADED
        val ly = if (y > height * 0.85f) y - r * 1.6f else y + r + 10 * density
        canvas.drawText(m.label, x, ly, text)
        text.color = INK
    }

    private fun drawPiece(canvas: Canvas, s: QuestPuzzles.LightStep, x: Float, y: Float, cell: Float, current: Boolean) {
        val r = cell * if (current) 0.42f else 0.32f
        if (current) {
            fill.color = HIGHLIGHT
            canvas.drawCircle(x, y, r * 1.25f, fill)
        }
        if (s.item == "mirror") {
            fill.color = if (current) Color.WHITE else Color.parseColor("#F4EEDD")
            canvas.drawRect(x - r * 0.75f, y - r * 0.75f, x + r * 0.75f, y + r * 0.75f, fill)
            line.color = INK; line.strokeWidth = 1.2f * density
            canvas.drawRect(x - r * 0.75f, y - r * 0.75f, x + r * 0.75f, y + r * 0.75f, line)
            s.dir?.let { drawDirection(canvas, it, x, y, r * 0.62f) }
        } else {
            // a crystal: a coloured diamond
            fill.color = crystalColour(s.item)
            val p = Path().apply {
                moveTo(x, y - r); lineTo(x + r * 0.8f, y); lineTo(x, y + r); lineTo(x - r * 0.8f, y); close()
            }
            canvas.drawPath(p, fill)
            line.color = INK; line.strokeWidth = 1.2f * density
            canvas.drawPath(p, line)
        }
    }

    private fun drawDirection(canvas: Canvas, dir: String, x: Float, y: Float, r: Float) {
        line.color = INK; line.strokeWidth = 2f * density
        when (dir) {
            "up", "down" -> {
                text.textSize = r * 1.5f; text.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(if (dir == "up") "↑" else "↓", x, y + r * 0.55f, text)
                return
            }
        }
        val (dx, dy) = when (dir) { "north" -> 0f to -1f; "south" -> 0f to 1f; "east" -> 1f to 0f; else -> -1f to 0f }
        val ex = x + dx * r; val ey = y + dy * r
        canvas.drawLine(x - dx * r, y - dy * r, ex, ey, line)
        // arrow head
        val hx = -dy; val hy = dx
        canvas.drawLine(ex, ey, ex - dx * r * 0.5f + hx * r * 0.45f, ey - dy * r * 0.5f + hy * r * 0.45f, line)
        canvas.drawLine(ex, ey, ex - dx * r * 0.5f - hx * r * 0.45f, ey - dy * r * 0.5f - hy * r * 0.45f, line)
    }

    private fun crystalColour(item: String): Int = when {
        item.startsWith("red") -> Color.parseColor("#E03A2F")
        item.startsWith("green") -> Color.parseColor("#3DBB4A")
        item.startsWith("blue") -> Color.parseColor("#3667E0")
        item.startsWith("yellow") -> Color.parseColor("#F2D23A")
        item.startsWith("cyan") -> Color.parseColor("#3CD6E0")
        item.startsWith("magenta") -> Color.parseColor("#D845C8")
        else -> Color.parseColor("#C9C9D6")   // fractured / clear
    }

    // ---------------- Rubble and trees: which tile, and which side to stand on ----------------

    private fun drawTiles(canvas: Canvas, steps: List<QuestPuzzles.TileStep>, kind: String) {
        val cur = steps[step.coerceIn(0, steps.size - 1)]
        // the group of tiles near this one (the steps can be far apart)
        val group = HashSet<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(cur.x to cur.y) }
        val all = steps.map { it.x to it.y }.toSet()
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (!group.add(t)) continue
            all.filter { abs(it.first - t.first) <= 3 && abs(it.second - t.second) <= 3 && it !in group }.forEach { queue.add(it) }
        }
        val minX = group.minOf { it.first } - 1; val maxX = group.maxOf { it.first } + 1
        val minY = group.minOf { it.second } - 1; val maxY = group.maxOf { it.second } + 1
        val cols = max(maxX - minX + 1, 5); val rows = max(maxY - minY + 1, 5)
        val top = 8 * density
        val cell = min((width - 16 * density) / cols, (height - top - 8 * density) / rows)
        val left = (width - cell * cols) / 2f
        val gridTop = top + (height - top - 8 * density - cell * rows) / 2f
        fun rx(x: Int) = left + (x - minX) * cell
        fun ry(y: Int) = gridTop + (maxY - y) * cell   // north up

        line.color = GRID; line.strokeWidth = 1f * density
        for (c in 0..cols) canvas.drawLine(left + c * cell, gridTop, left + c * cell, gridTop + rows * cell, line)
        for (r in 0..rows) canvas.drawLine(left, gridTop + r * cell, left + cols * cell, gridTop + r * cell, line)

        // what's still standing: a tile stays until its last step is done
        for (t in group) {
            val last = steps.indexOfLast { it.x == t.first && it.y == t.second }
            val gone = last < step
            fill.color = when {
                gone -> Color.TRANSPARENT
                kind == "rubble" -> RUBBLE
                steps.drop(step).firstOrNull { it.x == t.first && it.y == t.second }?.action == "climb" -> STUMP
                else -> TREE
            }
            val pad = cell * 0.12f
            canvas.drawRoundRect(RectF(rx(t.first) + pad, ry(t.second) + pad, rx(t.first) + cell - pad, ry(t.second) + cell - pad),
                cell * 0.15f, cell * 0.15f, fill)
        }

        // the tile to use now, and where to stand
        val (dx, dy) = when (cur.side) { "north" -> 0 to 1; "south" -> 0 to -1; "east" -> 1 to 0; else -> -1 to 0 }
        val tx = rx(cur.x) + cell / 2; val ty = ry(cur.y) + cell / 2
        val px = rx(cur.x + dx) + cell / 2; val py = ry(cur.y + dy) + cell / 2
        line.color = HIGHLIGHT; line.strokeWidth = 3f * density
        canvas.drawRoundRect(RectF(rx(cur.x) + 2, ry(cur.y) + 2, rx(cur.x) + cell - 2, ry(cur.y) + cell - 2), cell * 0.15f, cell * 0.15f, line)
        fill.color = PLAYER
        canvas.drawCircle(px, py, cell * 0.28f, fill)
        line.color = PLAYER; line.strokeWidth = 2.5f * density
        val ex = tx + (px - tx) * 0.45f; val ey = ty + (py - ty) * 0.45f
        canvas.drawLine(px + (tx - px) * 0.3f, py + (ty - py) * 0.3f, ex, ey, line)
        fill.color = Color.WHITE
        canvas.drawCircle(px, py, cell * 0.1f, fill)
    }

    // ---------------- The Forsaken Tower power grid ----------------

    private fun drawPower(canvas: Canvas, d: QuestPuzzles.Diagram.Power) {
        val n = d.rows.size
        val size = min(width, height) - 16 * density
        val cell = size / n
        val left = (width - cell * n) / 2f
        val top = (height - cell * n) / 2f
        fill.color = Color.parseColor("#2B2B30")
        canvas.drawRect(left - 4 * density, top - 4 * density, left + cell * n + 4 * density, top + cell * n + 4 * density, fill)
        val gold = Color.parseColor("#C9A04A")
        for (r in 0 until n) for (c in 0 until n) {
            val x = left + c * cell; val y = top + r * cell
            fill.color = Color.parseColor("#3C3D44")
            canvas.drawRect(x + 1.5f * density, y + 1.5f * density, x + cell - 1.5f * density, y + cell - 1.5f * density, fill)
            val t = d.rows[r][c]
            val cx = x + cell / 2; val cy = y + cell / 2
            line.color = gold; line.strokeWidth = cell * 0.11f; line.strokeCap = Paint.Cap.SQUARE
            if ('N' in t) canvas.drawLine(cx, cy, cx, y, line)
            if ('S' in t) canvas.drawLine(cx, cy, cx, y + cell, line)
            if ('E' in t) canvas.drawLine(cx, cy, x + cell, cy, line)
            if ('W' in t) canvas.drawLine(cx, cy, x, cy, line)
            if ('t' in t) {
                fill.color = gold
                canvas.drawRect(cx - cell * 0.2f, cy - cell * 0.2f, cx + cell * 0.2f, cy + cell * 0.2f, fill)
            }
        }
        line.strokeCap = Paint.Cap.ROUND
    }

    // ---------------- Tower of Life pipe machine (the finished machine) ----------------

    private fun drawPipeMachine(canvas: Canvas) {
        // drawn on a 350 x 383 plan of the machine, scaled to fit
        val s = min((width - 16 * density) / 350f, (height - 16 * density) / 383f)
        val ox = (width - 350 * s) / 2f; val oy = (height - 383 * s) / 2f
        fun X(v: Float) = ox + (v - 225f) * s
        fun Y(v: Float) = oy + (v - 33f) * s
        fill.color = Color.parseColor("#6E6E76")
        canvas.drawRoundRect(RectF(X(245f), Y(40f), X(555f), Y(330f)), 10 * s, 10 * s, fill)
        fill.color = Color.parseColor("#55555C")
        canvas.drawRect(X(262f), Y(58f), X(540f), Y(320f), fill)

        val pipe = Color.parseColor("#E4E4EA")
        val edge = Color.parseColor("#9A9AA3")
        fun run(width: Float, colour: Int, vararg pts: Float) {
            val p = Path()
            p.moveTo(X(pts[0]), Y(pts[1]))
            var i = 2
            while (i + 3 < pts.size) {   // pairs of (control, end) points for smooth bends
                p.quadTo(X(pts[i]), Y(pts[i + 1]), X(pts[i + 2]), Y(pts[i + 3])); i += 4
            }
            if (i + 1 < pts.size) p.lineTo(X(pts[i]), Y(pts[i + 1]))
            line.color = colour; line.strokeWidth = width * s
            canvas.drawPath(p, line)
        }
        fun pipes(width: Float, colour: Int) {
            // outlet 1 down, then across to the middle
            run(width, colour, 297f, 70f, 297f, 150f, 297f, 175f, 297f, 200f, 330f, 205f, 395f, 222f)
            // outlet 2 bends left into pipe 1
            run(width, colour, 365f, 70f, 365f, 105f, 365f, 125f, 365f, 150f, 330f, 150f, 300f, 150f)
            // outlet 3 down, then into the middle
            run(width, colour, 432f, 70f, 432f, 150f, 432f, 180f, 432f, 210f, 405f, 222f, 400f, 222f)
            // the link between outlets 3 and 4
            run(width, colour, 432f, 150f, 432f, 150f, 470f, 150f, 502f, 150f)
            // outlet 4 down, then a big bend into the bottom pipe
            run(width, colour, 502f, 70f, 502f, 200f, 502f, 225f, 502f, 262f, 470f, 262f, 402f, 262f)
            // the pipe down to the bottom
            run(width, colour, 400f, 215f, 400f, 250f, 400f, 270f, 400f, 300f, 400f, 305f)
        }
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        pipes(30f, edge)
        pipes(24f, pipe)
        // the four outlets and the inlet
        fill.color = Color.parseColor("#C8C8D0")
        for (x in listOf(297f, 365f, 432f, 502f)) canvas.drawRoundRect(RectF(X(x - 26f), Y(58f), X(x + 26f), Y(76f)), 4 * s, 4 * s, fill)
        canvas.drawRoundRect(RectF(X(373f), Y(298f), X(427f), Y(322f)), 4 * s, 4 * s, fill)
    }

    // ---------------- Icthlarin's Little Helper door tiles ----------------

    private fun drawPattern(canvas: Canvas, d: QuestPuzzles.Diagram.Pattern) {
        val size = min(width, height) - 16 * density
        val cell = size / max(d.cols, d.rows)
        val left = (width - cell * d.cols) / 2f
        val top = (height - cell * d.rows) / 2f
        fill.color = Color.parseColor("#C9A233")
        canvas.drawRect(left - 5 * density, top - 5 * density, left + cell * d.cols + 5 * density, top + cell * d.rows + 5 * density, fill)
        for (r in 0 until d.rows) for (c in 0 until d.cols) {
            val i = r * d.cols + c
            val x = left + c * cell; val y = top + r * cell
            fill.color = if (i in d.lit) Color.parseColor("#E8D24A") else Color.parseColor("#5A2E0C")
            canvas.drawRect(x + 2 * density, y + 2 * density, x + cell - 2 * density, y + cell - 2 * density, fill)
            d.clicks[i]?.let { n ->
                fill.color = Color.WHITE
                canvas.drawCircle(x + cell / 2, y + cell / 2, cell * 0.3f, fill)
                text.textSize = cell * 0.4f; text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.parseColor("#D0201A")
                canvas.drawText("$n", x + cell / 2, y + cell / 2 + cell * 0.14f, text)
                text.color = INK
            }
        }
    }

    // ---------------- The Blood Moon Rises bookcase symbols ----------------

    private fun drawGlyphs(canvas: Canvas, d: QuestPuzzles.Diagram.Glyphs) {
        val glyphs = d.data.split("|").map { part ->
            val (w, rows) = part.split(":")
            w.toInt() to rows.split(",").map { it.toLong(36) }
        }
        val n = glyphs.size
        val gap = 4 * density
        val bookW = (width - 16 * density - gap * (n - 1)) / n
        val bookH = height - 16 * density
        val maxRows = glyphs.maxOf { it.second.size }
        val px = min(bookW * 0.85f / glyphs.maxOf { it.first }, bookH * 0.8f / maxRows)
        for ((k, g) in glyphs.withIndex()) {
            val (w, rows) = g
            val bx = 8 * density + k * (bookW + gap); val by = 8 * density
            fill.color = Color.parseColor("#3B2622")
            canvas.drawRoundRect(RectF(bx, by, bx + bookW, by + bookH), 3 * density, 3 * density, fill)
            fill.color = Color.parseColor("#B8862E")
            canvas.drawRect(bx, by + bookH * 0.06f, bx + bookW, by + bookH * 0.1f, fill)
            canvas.drawRect(bx, by + bookH * 0.9f, bx + bookW, by + bookH * 0.94f, fill)
            val gx = bx + (bookW - w * px) / 2f; val gy = by + (bookH - rows.size * px) / 2f
            fill.color = Color.parseColor("#E0A43A")
            for ((y, bits) in rows.withIndex()) for (x in 0 until w) {
                if ((bits shr x) and 1L == 1L) canvas.drawRect(gx + x * px, gy + y * px, gx + (x + 1) * px + 0.5f, gy + (y + 1) * px + 0.5f, fill)
            }
            text.textSize = 10 * density; text.typeface = Typeface.DEFAULT_BOLD; text.color = Color.parseColor("#E9DAB5")
            canvas.drawText("${k + 1}", bx + bookW / 2, by + bookH - 2 * density, text)
            text.color = INK
        }
    }

    // ---------------- A grid with some squares marked ----------------

    private fun drawMarked(canvas: Canvas, d: QuestPuzzles.Diagram.Marked) {
        val size = min(width, height) - 16 * density
        val cell = size / max(d.cols, d.rows)
        val left = (width - cell * d.cols) / 2f
        val top = (height - cell * d.rows) / 2f
        for (r in 0 until d.rows) for (c in 0 until d.cols) {
            fill.color = if ((r + c) % 2 == 0) Color.parseColor("#DCC99C") else Color.parseColor("#E9DAB5")
            canvas.drawRect(left + c * cell, top + r * cell, left + (c + 1) * cell, top + (r + 1) * cell, fill)
            if (r * d.cols + c in d.marked) {
                fill.color = MARK
                canvas.drawCircle(left + c * cell + cell / 2, top + r * cell + cell / 2, cell * 0.32f, fill)
            }
        }
        line.color = INK; line.strokeWidth = 1.5f * density
        canvas.drawRect(left, top, left + cell * d.cols, top + cell * d.rows, line)
    }
}
