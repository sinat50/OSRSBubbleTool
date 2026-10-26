package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.sinat.osrsbubbletool.ToaColours.GLOW
import com.sinat.osrsbubbletool.ToaColours.GLOW_CORE
import com.sinat.osrsbubbletool.ToaColours.HET_CRACKED
import com.sinat.osrsbubbletool.ToaColours.HET_FIXED
import com.sinat.osrsbubbletool.ToaColours.HET_FLOOR
import com.sinat.osrsbubbletool.ToaColours.HET_GLOW
import com.sinat.osrsbubbletool.ToaColours.HET_LINES
import com.sinat.osrsbubbletool.ToaColours.HET_MINE
import com.sinat.osrsbubbletool.ToaColours.HET_RIM
import com.sinat.osrsbubbletool.ToaColours.HET_SEAL
import com.sinat.osrsbubbletool.ToaColours.HET_SHINE
import com.sinat.osrsbubbletool.ToaColours.HET_STONE
import com.sinat.osrsbubbletool.ToaColours.HET_WALL
import com.sinat.osrsbubbletool.ToaColours.INK
import com.sinat.osrsbubbletool.ToaColours.STEP_GREEN
import com.sinat.osrsbubbletool.ToaColours.TILE
import com.sinat.osrsbubbletool.ToaColours.YELLOW
import com.sinat.osrsbubbletool.ToaPuzzles.Symbol

// How the ToA Puzzle Helper's maps look: the game's colours, the tiles, plates and carved symbols the puzzle maps
// (ToaPuzzleTool's Board) are drawn with, and the Path of Het map. Drawn like the game, so the colours stay the same in
// dark mode.

// The maps, in the game's colours: plates and symbol tiles are purple with yellow on them, and anything lit (a plate,
// a flashing tile, a matched pair) glows pale
object ToaColours {
    val TILE = "#6A5468".toColorInt()          // a purple plate or symbol tile
    val TILE_HIDDEN = "#8C8780".toColorInt()   // a matching tile not stepped on yet (plain grey)
    val TILE_LIT = "#FFF1C2".toColorInt()      // lit: pale and glowing
    val YELLOW = "#E8B828".toColorInt()        // the yellow squares and symbols
    val GLYPH_LIT = "#B08A20".toColorInt()     // a symbol on a lit tile
    val OBELISK = "#5A4350".toColorInt()       // an unlit obelisk (dark)
    val OBELISK_LIT = "#F2C4D4".toColorInt()   // a lit obelisk (light pink)
    val STEP_GREEN = "#2EBD4E".toColorInt()    // "step here"
    val GLOW = "#FFE7A0".toColorInt()          // the glow around a lit plate
    val GLOW_CORE = "#FFFCEE".toColorInt()     // a lit plate's bright middle
    val MISS_GREY = "#B0A8B0".toColorInt()
    val INK = "#3E2C12".toColorInt()           // writing on a lit tile (the same in dark mode)
    // The Path of Het map: the sandy floor, the grey statues, Het's pale seal, the white beam and gold mirrors
    val HET_FLOOR = "#C2B28C".toColorInt()
    val HET_LINES = "#AD9D78".toColorInt()     // the squares on the floor
    val HET_STONE = "#5E6372".toColorInt()
    val HET_SEAL = "#DCD6E8".toColorInt()
    val HET_GLOW = "#B0FFF1B8".toColorInt()    // the beam's glow (see-through)
    val HET_SHINE = "#FFF8E0".toColorInt()     // a mirror's shiny side
    val HET_WALL = "#8A8F9C".toColorInt()      // a smooth stone wall (light)
    val HET_CRACKED = "#3C414E".toColorInt()   // a cracked wall, which can be mined (dark)
    val HET_FIXED = "#7A7F8E".toColorInt()     // a fixed mirror (grey, with a dark border)
    val HET_RIM = "#9C96AC".toColorInt()       // the room's outer wall (pale lavender stone)
    val HET_MINE = "#E8702A".toColorInt()      // the border on a cracked wall to mine
}

// Draws the pieces of the maps. One for the whole tool: its paints and shapes are used again for every drawing, so
// drawing makes nothing new.
class ToaPainter {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    // How far the map being drawn is turned (set by the map while it draws), so writing on it can be turned back to
    // read upright
    var drawingTurn = 0f

    fun tile(canvas: Canvas, r: RectF, colour: Int) {
        fill.color = colour
        val corner = r.width() * 0.12f
        canvas.drawRoundRect(r, corner, corner, fill)
    }

    // An unlit plate, as in the game: a solid yellow square on a purple tile
    private val plateSquare = RectF()
    fun plate(canvas: Canvas, r: RectF) {
        tile(canvas, r, TILE)
        val inset = r.width() * 0.27f
        plateSquare.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
        fill.color = YELLOW
        canvas.drawRect(plateSquare, fill)
    }

    // A lit plate: the same plate, with its square glowing bright and a soft glow around it
    fun litPlate(canvas: Canvas, r: RectF) {
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
    fun stepHere(canvas: Canvas, r: RectF) {
        stroke.color = STEP_GREEN
        stroke.strokeWidth = r.width() * 0.1f
        val corner = r.width() * 0.12f
        canvas.drawRoundRect(r, corner, corner, stroke)
    }

    fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int) {
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

    fun glyph(canvas: Canvas, s: Symbol, r: RectF, colour: Int) {
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

    // One mirror on the Path of Het map, centred on x, y: a gold square (a mirror to place) or a grey one with a dark
    // border (a fixed mirror already there), its shiny half on the side it faces and the mirror itself the line across
    private val hetShine = Path()
    private val hetBox = RectF()
    fun hetMirror(canvas: Canvas, x: Float, y: Float, h: Float, facing: ToaPuzzles.Facing, fixed: Boolean) {
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
}

// What a Path of Het map shows: one way through the room (`answer`, -1 for just the room), the room's walls
// (HetRoom.walls, with this raid's cracks; null: no room picked), the way's corners where a fixed mirror already
// stands (drawn grey), the other fixed mirrors seen this raid (drawn facing the way they were read, in `facing`, or as
// plain grey squares), and which side is at the top (`top`: 0 north, 1 east, 2 south, 3 west)
class HetMapData(val answer: Int, val walls: IntArray?, val fixedCorners: List<Int>, val others: List<Int>,
                 val facing: Map<Int, ToaPuzzles.Facing>, val top: Int)

// The Path of Het room with one way through it: the three statues across the middle, the room's walls (if it's
// known: smooth ones light grey, cracked ones dark), the beam from the caster to the shielded statue, and a gold
// mirror at each corner, its shiny half on the side the beam comes in and goes out. Cracked walls the beam crosses
// get an orange border (mine them). A small one (the puzzle list's picture) leaves out the floor's squares and draws
// mirrors as gold dots. `maxHeight` in pixels.
@SuppressLint("ViewConstructor")
class HetMapView(
    context: Context,
    private val painter: ToaPainter,
    private val map: HetMapData,
    private val small: Boolean,
    private val maxHeight: Int,
    onTap: (() -> Unit)? = null
) : View(context) {
    private val mirrors = ToaPuzzles.HET_ANSWERS.getOrElse(map.answer) { emptyList() }
    private val squares = if (map.answer < 0) emptyList() else ToaPuzzles.hetBeam(mirrors)
    private val turn = -90f * map.top
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
    private fun squareX(s: Int) = (s / ToaPuzzles.HET_SIZE).toFloat()
    private fun squareY(s: Int) = (s % ToaPuzzles.HET_SIZE).toFloat()
    private fun squareBox(s: Int) {
        val x = squareX(s); val y = squareY(s)
        box.set(sx(x), sy(y + 1), sx(x + 1), sy(y))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, minOf(w, maxHeight))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat()
        cell = size / span
        left = (w - size) / 2f; top = (h - size) / 2f
        // the beam: out of the caster's west side, through each square's middle, into the shielded statue
        beam.reset()
        if (squares.isEmpty()) return
        beam.moveTo(cx(ToaPuzzles.HET_CASTER_X - 0.5f), cy(ToaPuzzles.HET_MIDDLE_Y + 1f))
        for (s in squares) beam.lineTo(cx(squareX(s)), cy(squareY(s)))
        val last = squares.last(); val before = squares.getOrElse(squares.size - 2) { last }
        val dx = (last / ToaPuzzles.HET_SIZE - before / ToaPuzzles.HET_SIZE) * 0.5f
        val dy = (last % ToaPuzzles.HET_SIZE - before % ToaPuzzles.HET_SIZE) * 0.5f
        beam.lineTo(cx(last / ToaPuzzles.HET_SIZE + dx), cy(last % ToaPuzzles.HET_SIZE + dy))
    }

    override fun onDraw(canvas: Canvas) {
        val fill = painter.fill; val stroke = painter.stroke
        val walls = map.walls
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
                if (walls[s] == ToaPuzzles.HET_NO_WALL) continue
                squareBox(s)
                fill.color = if (walls[s] == ToaPuzzles.HET_SMOOTH_WALL) HET_WALL else HET_CRACKED
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
                val already = mirror.square in map.fixedCorners
                if (small) { fill.color = if (already) HET_FIXED else YELLOW; drawCircle(x, y, cell * 1.1f, fill); continue }
                // exactly the size of its square, so it shows which square to stand it on
                painter.hetMirror(this, x, y, cell * 0.5f, mirror.facing, already)
            }
            for (s in map.others) {
                val facing = map.facing[s]
                if (facing != null) {
                    painter.hetMirror(this, cx(squareX(s)), cy(squareY(s)), cell * 0.5f, facing, true)
                    continue
                }
                squareBox(s)
                fill.color = HET_FIXED; drawRect(box, fill)
                stroke.color = INK; stroke.strokeWidth = cell * 0.1f; drawRect(box, stroke)
            }
            // cracked walls the beam crosses get an orange border, on top of the beam: mine these
            if (walls != null && !small) for (s in squares) if (walls[s] == ToaPuzzles.HET_CRACKED_WALL) {
                squareBox(s)
                box.inset(cell * 0.1f, cell * 0.1f)
                stroke.color = HET_MINE; stroke.strokeWidth = cell * 0.2f
                drawRect(box, stroke)
            }
        }
    }
}
