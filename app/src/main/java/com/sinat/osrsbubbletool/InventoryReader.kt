package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.LightBoxReader.PixelSource
import com.sinat.osrsbubbletool.PanelFinder.Box
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// Gear Loadouts' inventory reading: which item is in each of the 28 inventory spaces, and how many. No Android
// parts, so it's tested on a computer with screenshots from the owner's phone (InventoryReaderTest).
//
// The side panel can be see-through (the game world shows behind the items), so an item is told apart from what's
// behind it by the thin black outline the game draws round every item picture. For each space:
//  1. the screen is shrunk back to game pixels, and the item's shape found: whatever its outline closes in;
//  2. the 16,000 item pictures are narrowed down to a few dozen by their shape and their colours in brief
//     (assets/items/shapes.bin, made by tools/items/build_items.py);
//  3. those pictures are laid over the item, and the best fit wins.
// A stack's amount (yellow digits at the top left) is left out of the comparing, and read with Digits.
// A noted item is drawn on a bank note: the note is told by its paper colour, and the item drawn on it (two thirds
// as big) is compared with the pictures of the items that can be noted.
object InventoryReader {

    // ---------------- The pictures in brief (shapes.bin) ----------------

    class Shapes(bytes: ByteArray) {
        val count: Int
        val names: Array<String>
        val widths: IntArray
        val heights: IntArray
        val masks: Array<ByteArray>        // 1 bit a pixel, row by row, first pixel in the top bit
        val gridHas: IntArray              // which of the 4 x 4 squares have any of the picture (first in bit 15)
        val grids: ByteArray               // 48 bytes a picture: each square's average red, green, blue

        init {
            val b = ByteBuffer.wrap(bytes)
            require(b.remaining() >= 8 && b.get() == 'S'.code.toByte() && b.get() == 'H'.code.toByte() &&
                b.get() == 'P'.code.toByte() && b.get() == '1'.code.toByte()) { "not a shapes file" }
            count = b.int
            names = Array(count) { "" }
            widths = IntArray(count); heights = IntArray(count)
            masks = Array(count) { ByteArray(0) }
            gridHas = IntArray(count)
            grids = ByteArray(count * 48)
            for (i in 0 until count) {
                val name = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
                names[i] = String(name, Charsets.UTF_8)
                widths[i] = b.get().toInt() and 0xFF
                heights[i] = b.get().toInt() and 0xFF
                masks[i] = ByteArray((widths[i] * heights[i] + 7) / 8).also { b.get(it) }
                gridHas[i] = b.short.toInt() and 0xFFFF
                b.get(grids, i * 48, 48)
            }
        }

        // Each picture's shape in brief: how much of each square of an 8 x 8 grid over it is the picture (0-255)
        val cover: ByteArray by lazy {
            val out = ByteArray(count * 64)
            for (i in 0 until count) {
                val w = widths[i]; val h = heights[i]
                for (k in 0 until 64) {
                    val j = k / 8; val c = k % 8
                    var n = 0; var on = 0
                    for (y in j * h / 8 until (j + 1) * h / 8) for (x in c * w / 8 until (c + 1) * w / 8) { n++; if (solid(i, x, y)) on++ }
                    out[i * 64 + k] = (if (n == 0) 0 else on * 255 / n).toByte()
                }
            }
            out
        }

        fun solid(i: Int, x: Int, y: Int): Boolean {
            val k = y * widths[i] + x
            return (masks[i][k shr 3].toInt() shr (7 - (k and 7))) and 1 == 1
        }
    }

    // Gives a picture by its file name (from assets/items/icons/), or null if it can't be read
    fun interface Pictures { fun get(name: String): LoadoutReader.Picture? }

    // What's in one space: the picture that fits best, the next best (other guesses), how well it fits (lower is
    // better), the amount written on it (null if none), and whether it's on a bank note. Read with RuneLite's icons,
    // itemId is the item itself (a noted item's own noted ID) and otherIds the other guesses; picture is then "".
    class Found(val picture: String, val others: List<String>, val cost: Float, val amount: Int?, val noted: Boolean = false,
                val itemId: Int? = null, val otherIds: List<Int> = emptyList(), val otherCosts: List<Float> = emptyList(),
                val amountUnread: Boolean = false)   // an amount is written on it but couldn't be read

    // ---------------- Reading ----------------

    // Where the inventory sits in the side panel, in game pixels (the panel is about 204 wide): the first space's
    // top-left, and the step from one space to the next
    private const val PANEL_GAME_WIDTH = 204f
    private const val FIRST_X = 20f
    private const val FIRST_Y = 14f
    private const val STEP_X = 42
    private const val STEP_Y = 36
    // Each space is looked at with 2 game pixels round it: 40 x 36 (an item picture is at most 36 x 32)
    private const val AREA_W = 40
    private const val AREA_H = 36
    private const val MARGIN = 2

    private val GAMMAS = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)
    private const val SHORTLIST = 40           // pictures compared closely in each space
    private const val SHORTLIST_WITH_TEXT = 150 // more when an amount hides part of the item's outline
    private const val MIN_OVERLAP = 0.7f        // a picture must cover this much of the item's shape (and the item of it)
    private const val SHAPE_WEIGHT = 60f        // in the narrowing down: how much the shape counts against the colours

    var debugLog: ((String) -> Unit)? = null

    // Gives an item's icon (36 x 32, from assets/items/canvas/) by its ID, or null
    fun interface Icons { fun get(id: Int): LoadoutReader.Picture? }

    // The 28 spaces, left to right and top to bottom: what's in each, or null if it's empty. notable: the pictures of
    // items that can be noted (the only ones a note can show). canvases and icons: RuneLite's icons, tried first;
    // shapes and pictures (the wiki's pictures, for stacks drawn differently by size) when those don't fit clearly.
    fun read(p: PixelSource, panel: Box, shapes: Shapes, pictures: Pictures, notable: Set<String> = emptySet(),
             canvases: Canvases? = null, icons: Icons? = null): List<Found?> {
        val s = panel.width / PANEL_GAME_WIDTH
        val spaces = (0 until 28).map { i -> look(p, panel, s, i) }
        val cache = HashMap<String, LoadoutReader.Picture?>()
        val picture = { name: String -> cache.getOrPut(name) { pictures.get(name) } }
        val iconCache = HashMap<Int, LoadoutReader.Picture?>()
        val icon = { id: Int -> iconCache.getOrPut(id) { icons?.get(id) } }
        val grids = if (canvases != null && icons != null) (0 until 28).map { n -> if (spaces[n] == null) null else canvasGrids(p, panel, n) } else null

        // The game's brightness setting changes how item pictures are drawn: find it from a few clear spaces. Their
        // likely pictures are picked first (the best few at a dark, middle and bright setting), then the setting
        // where those fit best overall wins.
        val clear = spaces.filterNotNull().filter { !it.hasText && it.note == null }.take(10)
        var gamma = 1.2f
        if (grids != null && canvases != null) {
            // with RuneLite's icons: the best few icons in a few clear spaces, then the setting they fit best at
            val clearGrids = spaces.indices.filter { spaces[it]?.let { sp -> !sp.hasText } == true }.mapNotNull { grids[it] }.take(8)
            val likely = clearGrids.map { g -> floatArrayOf(0.9f, 1.2f, 1.5f).flatMap { fixedShortlist(g[0], canvases, shading(it), 3) }.distinct() }
            var bestTotal = Float.MAX_VALUE
            for (gm in GAMMAS) {
                val lut = shading(gm)
                var total = 0f
                for ((k, g) in clearGrids.withIndex()) total += likely[k].mapNotNull { i -> icon(canvases.ids[i])?.let { fixedFit(g, it, lut, 0) } }.minOrNull() ?: continue
                debugLog?.invoke("brightness $gm: total %.1f (icons)".format(total))
                if (total < bestTotal) { bestTotal = total; gamma = gm }
            }
        } else if (clear.isNotEmpty()) {
            val likely = clear.map { sp -> floatArrayOf(0.9f, 1.2f, 1.5f).flatMap { shortlist(sp, shapes, shading(it), 4) }.distinct() }
            var bestTotal = Float.MAX_VALUE
            for (g in GAMMAS) {
                val lut = shading(g)
                var total = 0f
                for ((k, sp) in clear.withIndex()) {
                    total += likely[k].mapNotNull { fit(sp, shapes, it, picture, lut)?.cost }.minOrNull() ?: continue
                }
                debugLog?.invoke("brightness $g: total %.1f".format(total))
                if (total < bestTotal) { bestTotal = total; gamma = g }
            }
        }
        debugLog?.invoke("scale %.3f, brightness %.1f".format(s, gamma))

        val lut = shading(gamma)
        return spaces.mapIndexed { n, sp ->
            if (sp == null) return@mapIndexed null
            // RuneLite's icons at their exact place first
            val g = grids?.get(n)
            val fixed = if (g != null && canvases != null) fixedRead(g, canvases, icon, lut, FIXED_SHORTLIST, sp.itemCount) else emptyList()
            debugLog?.invoke("space $n at its place: " + fixed.take(3).joinToString { "${it.first} %.1f".format(it.second) })
            val fixedBest = fixed.firstOrNull()
            // read with RuneLite's icons and none has its outline on black: nothing's there (patterns behind a
            // see-through panel can look like an item to the first look)
            if (g != null && fixedBest == null) return@mapIndexed null
            if (fixedBest != null && fixedBest.second <= FIXED_GOOD) {
                return@mapIndexed Found("", emptyList(), fixedBest.second, sp.amount, amountUnread = sp.hasText && sp.amount == null, itemId = fixedBest.first,
                    otherIds = fixed.drop(1).take(5).map { it.first }, otherCosts = fixed.drop(1).take(5).map { it.second })
            }
            val note = sp.note
            var found = (if (note != null) noteShortlist(sp, note, shapes, lut, notable)
                else shortlist(sp, shapes, lut, if (sp.hasText) SHORTLIST_WITH_TEXT else SHORTLIST))
                .mapNotNull { if (note != null) noteFit(sp, note, shapes, it, picture, lut) else fit(sp, shapes, it, picture, lut) }
            // Nothing fits well: dark shapes behind a see-through panel may have joined the item's outline and made it
            // look bigger. Look again, more forgivingly: smaller pictures anywhere in the shape found. (Only without
            // RuneLite's icons: those are compared at the item's exact place, whatever is behind it, and this is slow.)
            if (grids == null && note == null && (found.minOfOrNull { it.cost } ?: Float.MAX_VALUE) > RESCUE_COST) {
                val again = shortlist(sp, shapes, lut, RESCUE_SHORTLIST, lenient = true).mapNotNull { fit(sp, shapes, it, picture, lut, lenient = true) }
                found = (found + again).groupBy { it.index }.map { (_, f) -> f.minBy { it.cost } }
            }
            val fits = found
                // pictures that fit equally well are the same drawing: the plain item first (not a minigame or
                // quest copy in brackets, like "Shark (Last Man Standing)"), then the shortest name
                .sortedWith(compareBy<Fit>({ (it.cost * 10).roundToInt() }, { " (" in shapes.names[it.index] }, { shapes.names[it.index].length }))
                .toMutableList()
            preferCharged(fits, shapes)
            debugLog?.invoke("space $n (${sp.width}x${sp.height}${if (sp.hasText) ", amount ${sp.amount}" else ""}): " +
                fits.take(3).joinToString { "${shapes.names[it.index]} %.1f".format(it.cost) })
            val best = fits.firstOrNull()
            // the exact-place icon wins if today's way does no better (a stack drawn differently by size fits better today's way)
            if (fixedBest != null && (best == null || fixedBest.second <= best.cost)) {
                return@mapIndexed Found("", emptyList(), fixedBest.second, sp.amount, amountUnread = sp.hasText && sp.amount == null, itemId = fixedBest.first,
                    otherIds = fixed.drop(1).take(5).map { it.first }, otherCosts = fixed.drop(1).take(5).map { it.second })
            }
            val unread = sp.hasText && sp.amount == null
            if (best == null) return@mapIndexed Found("?", emptyList(), Float.MAX_VALUE, sp.amount, note != null, amountUnread = unread)
            Found(shapes.names[best.index], fits.drop(1).take(5).map { shapes.names[it.index] }, best.cost, sp.amount, note != null, amountUnread = unread)
        }
    }

    // An uncharged, empty or broken item and its charged (whole) version look almost the same. When they fit about as
    // well, the charged one goes first: it's the one people take on a trip ("Eye of Ayak" before "Eye of Ayak (uncharged)").
    private fun preferCharged(fits: MutableList<Fit>, shapes: Shapes) {
        val best = fits.firstOrNull() ?: return
        val name = shapes.names[best.index]
        val charged = UNCHARGED.firstOrNull { name.contains(it) }?.let { name.replace(it, "") } ?: return
        val k = fits.indexOfFirst { shapes.names[it.index] == charged }
        if (k > 0 && fits[k].cost - best.cost < CHARGED_MARGIN) fits.add(0, fits.removeAt(k))
    }

    private val UNCHARGED = listOf(" (uncharged)", " (empty)", " (inactive)", " (broken)")
    private const val CHARGED_MARGIN = 2f

    // ---------------- Items at their exact place (RuneLite's icons) ----------------
    //
    // The game draws every item on a 36 x 32 canvas, always at the same place in its inventory space. RuneLite's
    // icons (assets/items/canvas/, from tools/items/build_icons.py) are those canvases exactly as the game draws them,
    // so an icon can be laid on a space at its exact place and only its own pixels compared: whatever is behind a
    // see-through panel never comes into it. Narrowed down first with each icon in brief (assets/items/canvas.bin):
    // a few of its colours at their places, and a few points of its black outline.

    class Canvases(bytes: ByteArray) {
        val count: Int
        val ids: IntArray
        val sampleCount: IntArray
        val samples: Array<ByteArray>   // x, y, red, green, blue for each point
        val outlineCount: IntArray
        val outline: Array<ByteArray>   // x, y for each point

        init {
            val b = ByteBuffer.wrap(bytes)
            require(b.remaining() >= 8 && b.get() == 'C'.code.toByte() && b.get() == 'N'.code.toByte() &&
                b.get() == 'V'.code.toByte() && b.get() == '1'.code.toByte()) { "not a canvas file" }
            count = b.int
            ids = IntArray(count); sampleCount = IntArray(count); outlineCount = IntArray(count)
            samples = Array(count) { ByteArray(0) }; outline = Array(count) { ByteArray(0) }
            for (i in 0 until count) {
                ids[i] = b.int
                repeat(4) { b.get() }   // its box on the canvas (not needed here)
                sampleCount[i] = b.get().toInt() and 0xFF
                outlineCount[i] = b.get().toInt() and 0xFF
                samples[i] = ByteArray(sampleCount[i] * 5).also { b.get(it) }
                outline[i] = ByteArray(outlineCount[i] * 2).also { b.get(it) }
            }
        }
    }

    // Where the canvas sits in each inventory space, in game pixels from the side panel's top-left (the panel is
    // about 202.7 game pixels wide): measured on the owner's screenshots, where every shark then fits to 7.5
    private const val CANVAS_PANEL_WIDTH = 202.7f
    private const val CANVAS_X = 22.4f
    private const val CANVAS_Y = 14.4f
    private const val CANVAS_W = 36
    private const val CANVAS_H = 32
    private const val NUDGE = 1                 // the canvas is looked for up to this far from where it should be
    private const val GRID_W = CANVAS_W + 2 * NUDGE
    private const val GRID_H = CANVAS_H + 2 * NUDGE
    private const val FIXED_SHORTLIST = 60
    private const val OUTLINE_WEIGHT = 60f      // in the narrowing down: an outline point that isn't dark counts this much
    // A fit this good at the exact place is clear (the owner's items fit 7-16); worse, today's way is tried too
    private const val FIXED_GOOD = 22f

    // A space at the canvas's place, shrunk to game pixels (the middle half of each, as the edges are blurred), with a
    // pixel round it to look a little either side; and where an amount's text is (left out when comparing).
    // Two of them for each space, half a game pixel apart each way, as the canvas may not fall on whole game pixels.
    // dark: each game pixel's darkest screen pixel (r + g + b), so a thin outline split between two still shows
    private class CanvasGrid(val rgb: IntArray, val text: BooleanArray, val dark: IntArray)

    private fun canvasGrids(p: PixelSource, panel: Box, n: Int): List<CanvasGrid>? =
        canvasGridsAt(p, panel, CANVAS_X + (n % 4) * STEP_X, CANVAS_Y + (n / 4) * STEP_Y)

    // The same for a canvas whose top-left is (gx, gy) game pixels from the side panel's top-left
    private fun canvasGridsAt(p: PixelSource, panel: Box, gx: Float, gy: Float): List<CanvasGrid>? {
        val s = panel.width / CANVAS_PANEL_WIDTH
        val grids = ArrayList<CanvasGrid>()
        for (phase in floatArrayOf(0f, 0.5f)) {
            val x0 = panel.left + (gx - NUDGE + phase) * s
            val y0 = panel.top + (gy - NUDGE + phase) * s
            val rgb = IntArray(GRID_W * GRID_H)
            val yellow = BooleanArray(GRID_W * GRID_H)
            val darkest = IntArray(GRID_W * GRID_H)
            for (j in 0 until GRID_H) for (i in 0 until GRID_W) {
                val xa = (x0 + i * s).roundToInt(); val xb = maxOf(xa + 1, (x0 + (i + 1) * s).roundToInt())
                val ya = (y0 + j * s).roundToInt(); val yb = maxOf(ya + 1, (y0 + (j + 1) * s).roundToInt())
                if (xa < 0 || ya < 0 || xb > p.width || yb > p.height) return null
                val qx = (xb - xa) / 4; val qy = (yb - ya) / 4
                var r = 0; var g = 0; var b = 0; var count = 0; var text = false; var least = Int.MAX_VALUE
                for (y in ya until yb) for (x in xa until xb) {
                    val c = p.rgb(x, y)
                    val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                    if (cr + cg + cb < least) least = cr + cg + cb
                    if (minOf(cr, cg) - cb > 150 || (minOf(cr, cg, cb) > 200 && maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 40) ||
                        (cg > 160 && cr * 100 <= cg * 45 && cb * 100 >= cg * 30 && cb * 100 <= cg * 75)) text = true
                    if (x >= xa + qx && x < xb - qx && y >= ya + qy && y < yb - qy) { r += cr; g += cg; b += cb; count++ }
                }
                if (count == 0) { val c = p.rgb(xa, ya); r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF; count = 1 }
                val a = j * GRID_W + i
                rgb[a] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                darkest[a] = least
                yellow[a] = text && j < TEXT_ROWS + NUDGE
            }
            // the text, and its black shadow one game pixel below and to the right
            val text = BooleanArray(GRID_W * GRID_H) { a ->
                val i = a % GRID_W; val j = a / GRID_W
                yellow[a] || (j > 0 && yellow[a - GRID_W]) || (i > 0 && yellow[a - 1]) || (j > 0 && i > 0 && yellow[a - GRID_W - 1])
            }
            grids.add(CanvasGrid(rgb, text, darkest))
        }
        return grids
    }

    private fun colourDiff(s: Int, r: Int, g: Int, b: Int) =
        abs(((s shr 16) and 0xFF) - r) + abs(((s shr 8) and 0xFF) - g) + abs((s and 0xFF) - b)

    // The icons most like the space at their place, by their points in brief: the colours at a few places, and
    // whether the outline points are dark (best first, as indices into canvases)
    private fun fixedShortlist(grid: CanvasGrid, c: Canvases, lut: IntArray, keep: Int, only: BooleanArray? = null): List<Int> {
        // the best few so far, in plain arrays (thousands of icons are scored for each space: no objects for each)
        val bestIndex = IntArray(keep); val bestScore = FloatArray(keep)
        var kept = 0; var worst = 0
        val all = if (debugIds.isEmpty()) null else FloatArray(c.count) { Float.MAX_VALUE }
        for (i in 0 until c.count) {
            if (only != null && !only[i]) continue
            var best = Float.MAX_VALUE
            val sm = c.samples[i]; val ol = c.outline[i]
            for (dy in 0..2 * NUDGE) for (dx in 0..2 * NUDGE) {
                var sum = 0; var n = 0
                for (k in 0 until c.sampleCount[i]) {
                    val a = (sm[k * 5 + 1].toInt() + dy) * GRID_W + sm[k * 5].toInt() + dx
                    if (grid.text[a]) continue
                    sum += colourDiff(grid.rgb[a], lut[sm[k * 5 + 2].toInt() and 0xFF], lut[sm[k * 5 + 3].toInt() and 0xFF], lut[sm[k * 5 + 4].toInt() and 0xFF])
                    n++
                }
                var light = 0; var m = 0
                for (k in 0 until c.outlineCount[i]) {
                    val a = (ol[k * 2 + 1].toInt() + dy) * GRID_W + ol[k * 2].toInt() + dx
                    if (grid.text[a]) continue
                    val v = grid.rgb[a]
                    if (((v shr 16) and 0xFF) + ((v shr 8) and 0xFF) + (v and 0xFF) >= DARK) light++
                    m++
                }
                if (n == 0) continue
                val score = sum / (3f * n) + (if (m > 0) OUTLINE_WEIGHT * light / m else 0f)
                if (score < best) best = score
            }
            if (best == Float.MAX_VALUE) continue
            all?.set(i, best)
            if (kept < keep) {
                bestIndex[kept] = i; bestScore[kept] = best; kept++
                if (kept == keep) worst = (0 until keep).maxBy { bestScore[it] }
            } else if (best < bestScore[worst]) {
                bestIndex[worst] = i; bestScore[worst] = best
                worst = (0 until keep).maxBy { bestScore[it] }
            }
        }
        if (all != null) for (want in debugIds) {
            val k = (0 until c.count).firstOrNull { c.ids[it] == want } ?: continue
            debugLog?.invoke("  icon $want ranks ${all.count { it < all[k] }} (%.1f)".format(all[k]))
        }
        return (0 until kept).sortedBy { bestScore[it] }.map { bestIndex[it] }
    }

    var debugIds: Set<Int> = emptySet()

    // How well an icon fits the space at its place (the best of the half-pixel grids and the nudges): the average
    // colour difference over the icon's own pixels, the amount's text left out
    // Most of the icon must be compared (not under the amount's text), and an icon much smaller than the item found in
    // the space is marked down: a small icon can match a part of almost anything.
    private fun fixedFit(grids: List<CanvasGrid>, icon: LoadoutReader.Picture, lut: IntArray, itemPixels: Int): Float? {
        if (icon.width != CANVAS_W || icon.height != CANVAS_H) return null
        var opaque = 0
        for (c in icon.argb) if (((c ushr 24) and 0xFF) > 128) opaque++
        if (opaque == 0) return null
        var best = Float.MAX_VALUE
        for (grid in grids) for (dy in 0..2 * NUDGE) for (dx in 0..2 * NUDGE) {
            var sum = 0f; var weight = 0f; var n = 0; var outline = 0; var darkOutline = 0
            for (y in 0 until CANVAS_H) for (x in 0 until CANVAS_W) {
                val c = icon.argb[y * CANVAS_W + x]
                if (((c ushr 24) and 0xFF) <= 128) continue
                val a = (y + dy) * GRID_W + x + dx
                if (grid.text[a]) continue
                // the item's black outline must be black on screen too: a real item's is (dark stars on a rug
                // behind the panel are dark red-brown, never black)
                if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < ICON_OUTLINE) {
                    outline++
                    if (grid.dark[a] < SCREEN_BLACK) darkOutline++
                }
                val r = lut[(c shr 16) and 0xFF]; val g = lut[(c shr 8) and 0xFF]; val b = lut[c and 0xFF]
                // a bank note's paper is the same on every noted item: the item drawn on it tells them apart
                val w = if (isPaper((r shl 16) or (g shl 8) or b)) PAPER_WEIGHT else 1f
                sum += w * colourDiff(grid.rgb[a], r, g, b)
                weight += w
                n++
            }
            if (n < opaque * MIN_COMPARED || weight == 0f) continue
            if (outline > 0 && darkOutline < outline * MIN_DARK_OUTLINE) continue
            if (sum / (3f * weight) < best) best = sum / (3f * weight)
        }
        if (best == Float.MAX_VALUE) return null
        return best + SMALL_WEIGHT * maxOf(0f, 1f - opaque.toFloat() / maxOf(1, itemPixels))
    }

    private const val MIN_COMPARED = 0.6f   // of an icon's pixels, at least this many must be compared
    private const val PAPER_WEIGHT = 0.25f
    private const val ICON_OUTLINE = 30        // r + g + b below this in an icon: its black outline
    private const val SCREEN_BLACK = 90        // ...and on screen, black (the owner's items' outlines: 19-32)
    private const val MIN_DARK_OUTLINE = 0.6f  // real items: 93-100% of the outline black; a rug's patterns: none
    private const val SMALL_WEIGHT = 15f

    // The items at their place in one space, best first (item ID to fit)
    private fun fixedRead(grids: List<CanvasGrid>, c: Canvases, icons: (Int) -> LoadoutReader.Picture?, lut: IntArray, keep: Int,
                          itemPixels: Int, only: BooleanArray? = null): List<Pair<Int, Float>> =
        fixedShortlist(grids[0], c, lut, keep, only).mapNotNull { i ->
            val id = c.ids[i]
            icons(id)?.let { fixedFit(grids, it, lut, itemPixels) }?.let { id to it }
        }.sortedBy { it.second }

    // ---------------- The equipment tab ----------------
    //
    // The equipment tab draws each worn item's icon (the same 36 x 32 canvas) at a fixed place in its slot, so it's
    // read the same way: each slot's items laid at its place. Places measured on the owner's screenshot (every slot's
    // item then fit 11-16, the next best 22 or worse), in game pixels from the side panel's top-left.
    private val EQUIPMENT_PLACES = mapOf(
        "head" to (85.5f to 12.5f), "cape" to (44.5f to 51.5f), "neck" to (85.5f to 51.5f), "ammo" to (126.5f to 51.5f),
        "weapon" to (29f to 91f), "body" to (85.5f to 90.5f), "shield" to (141.5f to 90.5f), "legs" to (85.5f to 130.5f),
        "hands" to (29f to 171f), "feet" to (85.5f to 170.5f), "ring" to (141.5f to 170.5f))
    private const val EQUIPMENT_SHORTLIST = 30

    // What's worn: each slot's guesses (item ID to fit, best first; none for an empty slot), and the ammunition's amount.
    // slotOf: an item's equipment slot. Null if the panel can't be looked at.
    class Equipment(val slots: Map<String, List<Pair<Int, Float>>>, val ammoAmount: Int?)

    fun readEquipment(p: PixelSource, panel: Box, c: Canvases, icons: Icons, slotOf: (Int) -> String?): Equipment? {
        val iconCache = HashMap<Int, LoadoutReader.Picture?>()
        val icon = { id: Int -> iconCache.getOrPut(id) { icons.get(id) } }
        val grids = EQUIPMENT_PLACES.mapValues { (_, at) -> canvasGridsAt(p, panel, at.first, at.second) ?: return null }
        val slotOfIcon = Array(c.count) { slotOf(c.ids[it]) }
        val only = EQUIPMENT_PLACES.keys.associateWith { slot -> BooleanArray(c.count) { slotOfIcon[it] == slot } }
        // the game's brightness setting, from the best few in each slot (as for the inventory)
        var gamma = 1.2f
        var bestTotal = Float.MAX_VALUE
        val likely = grids.mapValues { (slot, g) -> floatArrayOf(0.9f, 1.2f, 1.5f).flatMap { fixedShortlist(g[0], c, shading(it), 3, only[slot]) }.distinct() }
        for (gm in GAMMAS) {
            val lut = shading(gm)
            var total = 0f
            for ((slot, g) in grids) total += likely[slot].orEmpty().mapNotNull { i -> icon(c.ids[i])?.let { fixedFit(g, it, lut, 0) } }.minOrNull() ?: continue
            if (total < bestTotal) { bestTotal = total; gamma = gm }
        }
        val lut = shading(gamma)
        val slots = grids.mapValues { (slot, g) -> fixedRead(g, c, icon, lut, EQUIPMENT_SHORTLIST, 0, only[slot]) }
        debugLog?.invoke("equipment, brightness $gamma: " + slots.entries.joinToString { (k, v) -> "$k ${v.take(2).joinToString { "${it.first} %.1f".format(it.second) }}" })
        // the ammunition's amount is written over the top of its slot
        val s = panel.width / CANVAS_PANEL_WIDTH
        val (ax, ay) = EQUIPMENT_PLACES.getValue("ammo")
        val ammo = if (slots["ammo"].isNullOrEmpty()) null else LoadoutReader.amountIn(p, Box(
            (panel.left + (ax - 1) * s).toInt(), (panel.top + (ay - 1) * s).toInt(),
            (panel.left + (ax + CANVAS_W) * s).toInt(), (panel.top + (ay + TEXT_ROWS) * s).toInt()))
        return Equipment(slots, ammo)
    }

    // ---------------- Trusting a reading ----------------

    // Whether a reading of the inventory tab (read()) can be trusted as the inventory: its items fit well. The inventory
    // dimmed behind a window, or another tab, fits poorly. Every space empty is an empty inventory (a trip with only
    // gear): the scan reads the inventory only while its tab's button shows it's open, so it can't be another tab.
    fun trusted(found: List<Found?>): Boolean {
        val filled = found.filterNotNull()
        if (filled.isEmpty()) return true
        // read with RuneLite's icons: the middle fit; the wiki's way: most items fitting well
        return if (filled.any { it.itemId != null }) filled.map { it.cost }.sorted()[filled.size / 2] <= ICONS_MIDDLE_FIT
            else filled.count { it.cost < GOOD_FIT } >= filled.size * 0.6f
    }

    // Read with RuneLite's icons, the owner's items fit 8-20; other tabs and the inventory dimmed behind a window, 20 or
    // more. Read the wiki's way (stacks drawn differently by size), good fits are 8-25.
    private const val ICONS_MIDDLE_FIT = 17f
    private const val GOOD_FIT = 40f

    // ---------------- One space ----------------

    // A space shrunk to game pixels: each pixel's colour (the middle of it, as the edges are blurred), its darkest
    // screen pixel (so a thin outline still shows), whether an amount's digits or their shadow are on it, and the
    // item's shape
    private class Space(
        val rgb: IntArray, val text: BooleanArray, val item: BooleanArray,
        val left: Int, val top: Int, val width: Int, val height: Int,   // the item's shape's box, digits left out
        val hasText: Boolean, val amount: Int?,
        val note: Note? = null                                          // the item drawn on it, if it's a bank note
    ) {
        val itemCount = (item.indices).count { item[it] && !text[it] }
        private val grids = HashMap<Int, IntArray>()

        // How much of each square of an 8 x 8 grid over a picture-sized box is the item (0-255; digits count as
        // half), placed as grid() places it
        private val covers = HashMap<Int, IntArray>()

        fun cover(w: Int, h: Int, at: Int): IntArray = covers.getOrPut((w * 1000 + h) * 4 + at) {
            val x0 = place(left, width, w, at)
            val y0 = place(top, height, h, at)
            IntArray(64) { k ->
                val j = k / 8; val c = k % 8
                var n = 0; var on = 0
                for (y in y0 + j * h / 8 until y0 + (j + 1) * h / 8) for (x in x0 + c * w / 8 until x0 + (c + 1) * w / 8) {
                    n += 2
                    if (x < 0 || y < 0 || x >= AREA_W || y >= AREA_H) continue
                    val a = y * AREA_W + x
                    on += if (text[a]) 1 else if (item[a]) 2 else 0
                }
                if (n == 0) 0 else on * 255 / n
            }
        }

        // The item's colours in brief over a picture-sized box (w x h): a 4 x 4 grid like the pictures' (-1 where
        // none of the item is). The box is at the item's top left, middle or bottom right (at: 0, 1, 2; see place()):
        // the outline found can be a pixel or two bigger on a side (something dark behind it), or digits can hide
        // the top left. Pictures of the same size share them.
        fun grid(w: Int, h: Int, at: Int): IntArray = grids.getOrPut((w * 1000 + h) * 4 + at) {
            val x0 = place(left, width, w, at)
            val y0 = place(top, height, h, at)
            IntArray(16) { k ->
                val j = k / 4; val i = k % 4
                var r = 0; var g = 0; var b = 0; var n = 0
                for (y in y0 + j * h / 4 until y0 + (j + 1) * h / 4) for (x in x0 + i * w / 4 until x0 + (i + 1) * w / 4) {
                    if (x < 0 || y < 0 || x >= AREA_W || y >= AREA_H) continue
                    val a = y * AREA_W + x
                    if (!item[a] || text[a]) continue
                    val c = rgb[a]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                if (n > 0) ((r / n) shl 16) or ((g / n) shl 8) or (b / n) else -1
            }
        }
    }

    private fun look(p: PixelSource, panel: Box, s: Float, n: Int): Space? {
        val x0 = panel.left + (FIRST_X + (n % 4) * STEP_X - MARGIN) * s
        val y0 = panel.top + (FIRST_Y + (n / 4) * STEP_Y - MARGIN) * s
        val size = AREA_W * AREA_H
        val rgb = IntArray(size)
        val dark = BooleanArray(size)
        val yellow = BooleanArray(size)
        for (j in 0 until AREA_H) for (i in 0 until AREA_W) {
            val xa = (x0 + i * s).roundToInt(); val xb = maxOf(xa + 1, (x0 + (i + 1) * s).roundToInt())
            val ya = (y0 + j * s).roundToInt(); val yb = maxOf(ya + 1, (y0 + (j + 1) * s).roundToInt())
            if (xa < 0 || ya < 0 || xb > p.width || yb > p.height) return null
            var darkest = Int.MAX_VALUE
            var anyYellow = false
            var r = 0; var g = 0; var b = 0; var count = 0
            val qx = (xb - xa) / 4; val qy = (yb - ya) / 4
            for (y in ya until yb) for (x in xa until xb) {
                val c = p.rgb(x, y)
                val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                if (cr + cg + cb < darkest) darkest = cr + cg + cb
                // an amount's text: yellow, white (100K and up) or green (10M and up)
                if (minOf(cr, cg) - cb > 150 || (minOf(cr, cg, cb) > 200 && maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 40) ||
                    (cg > 160 && cr * 100 <= cg * 45 && cb * 100 >= cg * 30 && cb * 100 <= cg * 75)) anyYellow = true
                if (x >= xa + qx && x < xb - qx && y >= ya + qy && y < yb - qy) { r += cr; g += cg; b += cb; count++ }
            }
            if (count == 0) { val c = p.rgb(xa, ya); r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF; count = 1 }
            val a = j * AREA_W + i
            rgb[a] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            dark[a] = darkest < DARK
            yellow[a] = anyYellow && j < TEXT_ROWS
        }
        // the digits, and their black shadow one game pixel below and to the right
        val text = BooleanArray(size)
        for (j in 0 until AREA_H) for (i in 0 until AREA_W) {
            val a = j * AREA_W + i
            text[a] = yellow[a] || (j > 0 && yellow[a - AREA_W]) || (i > 0 && yellow[a - 1]) ||
                (j > 0 && i > 0 && yellow[a - AREA_W - 1])
        }
        val item = closedIn(BooleanArray(size) { dark[it] || text[it] })

        var left = AREA_W; var right = -1; var top = AREA_H; var bottom = -1; var count = 0
        for (a in 0 until size) if (item[a] && !text[a]) {
            val x = a % AREA_W; val y = a / AREA_W
            if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; if (y > bottom) bottom = y
            count++
        }
        if (count < MIN_ITEM_PIXELS) return null   // nothing here
        val hasText = text.any { it }
        val amount = if (hasText) readAmount(p, x0, y0, s) else null
        val note = noteIn(rgb, dark, text, item, left, top, right, bottom, count)
        return Space(rgb, text, item, left, top, right - left + 1, bottom - top + 1, hasText, amount, note)
    }

    // ---------------- Bank notes ----------------

    // The item drawn on a bank note: its shape (in the space's game pixels) and that shape's box
    private class Note(val item: BooleanArray, val left: Int, val top: Int, val width: Int, val height: Int) {
        val count = item.count { it }
        // its colours and shape in brief, over its own box (the same at any size, like the pictures')
        fun grid(sp: Space): IntArray = IntArray(16) { k ->
            val j = k / 4; val i = k % 4
            var r = 0; var g = 0; var b = 0; var n = 0
            for (y in top + j * height / 4 until top + (j + 1) * height / 4) for (x in left + i * width / 4 until left + (i + 1) * width / 4) {
                val a = y * AREA_W + x
                if (!item[a]) continue
                val c = sp.rgb[a]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
            }
            if (n > 0) ((r / n) shl 16) or ((g / n) shl 8) or (b / n) else -1
        }

        fun cover(): IntArray = IntArray(64) { k ->
            val j = k / 8; val c = k % 8
            var n = 0; var on = 0
            for (y in top + j * height / 8 until top + (j + 1) * height / 8) for (x in left + c * width / 8 until left + (c + 1) * width / 8) {
                n++; if (item[y * AREA_W + x]) on++
            }
            if (n == 0) 0 else on * 255 / n
        }
    }

    // A bank note's paper colour on screen (light parchment; teleport scrolls and the like are much darker)
    private fun isPaper(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return r in 136..209 && g in 121..189 && b in 81..139 && r - g in 6..29 && g - b in 26..59
    }

    // If the space holds a bank note (enough of it is paper), the item drawn on it: what's left inside the note's
    // edge once everything reachable from the paper without crossing an outline is taken away (the biggest piece)
    private fun noteIn(rgb: IntArray, dark: BooleanArray, text: BooleanArray, item: BooleanArray,
                       left: Int, top: Int, right: Int, bottom: Int, itemCount: Int): Note? {
        var paper = 0
        for (a in item.indices) if (item[a] && !text[a] && isPaper(rgb[a])) paper++
        if (paper < itemCount * NOTE_PAPER_SHARE) return null
        val x0 = left + NOTE_EDGE; val y0 = top + NOTE_EDGE; val x1 = right - NOTE_EDGE; val y1 = bottom - NOTE_EDGE
        if (x1 <= x0 || y1 <= y0) return null
        val reached = BooleanArray(AREA_W * AREA_H)
        val queue = IntArray(AREA_W * AREA_H)
        var head = 0; var tail = 0
        for (y in y0..y1) for (x in x0..x1) {
            val a = y * AREA_W + x
            if (isPaper(rgb[a]) && !dark[a]) { reached[a] = true; queue[tail++] = a }
        }
        while (head < tail) {
            val a = queue[head++]
            val x = a % AREA_W; val y = a / AREA_W
            for (b in intArrayOf(if (x > x0) a - 1 else -1, if (x < x1) a + 1 else -1, if (y > y0) a - AREA_W else -1, if (y < y1) a + AREA_W else -1)) {
                if (b >= 0 && !reached[b] && !dark[b]) { reached[b] = true; queue[tail++] = b }
            }
        }
        val inside = BooleanArray(AREA_W * AREA_H)
        for (y in y0..y1) for (x in x0..x1) { val a = y * AREA_W + x; inside[a] = !reached[a] && !text[a] }
        // keep the biggest piece (bits of the note's own edge can be left over)
        val piece = IntArray(AREA_W * AREA_H)
        var biggest = 0; var biggestSize = 0; var label = 0
        for (start in inside.indices) {
            if (!inside[start] || piece[start] != 0) continue
            label++
            var h = 0; var t = 0; var size = 0
            piece[start] = label; queue[t++] = start
            while (h < t) {
                val a = queue[h++]; size++
                val x = a % AREA_W; val y = a / AREA_W
                for (b in intArrayOf(if (x > 0) a - 1 else -1, if (x < AREA_W - 1) a + 1 else -1, if (y > 0) a - AREA_W else -1, if (y < AREA_H - 1) a + AREA_W else -1)) {
                    if (b >= 0 && inside[b] && piece[b] == 0) { piece[b] = label; queue[t++] = b }
                }
            }
            if (size > biggestSize) { biggestSize = size; biggest = label }
        }
        if (biggestSize < MIN_NOTE_ITEM) return null
        val shape = BooleanArray(AREA_W * AREA_H) { piece[it] == biggest }
        var l = AREA_W; var r = -1; var t = AREA_H; var b = -1
        for (a in shape.indices) if (shape[a]) {
            val x = a % AREA_W; val y = a / AREA_W
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return Note(shape, l, t, r - l + 1, b - t + 1)
    }

    private const val NOTE_PAPER_SHARE = 0.3f   // notes measured 0.36-0.42 paper, other items 0
    private const val NOTE_EDGE = 3             // the note's own edge, left out when finding the item on it
    private const val MIN_NOTE_ITEM = 8
    private val NOTE_SCALES = floatArrayOf(0.6f, 2f / 3)   // an item on a note is drawn about this much smaller
    private const val NOTE_SHAPE_WEIGHT = 30f
    private const val MIN_NOTE_OVERLAP = 0.6f
    private const val OUTLINE_DARK = 30          // r + g + b below this in a picture: its black outline

    // The notable items' pictures most like the item on a note
    private fun noteShortlist(sp: Space, note: Note, shapes: Shapes, lut: IntArray, notable: Set<String>): List<Int> {
        val grid = note.grid(sp)
        val cover = note.cover()
        val scored = ArrayList<Pair<Int, Float>>()
        for (i in 0 until shapes.count) {
            if (shapes.names[i] !in notable) continue
            if (NOTE_SCALES.none { f ->
                    abs((shapes.widths[i] * f).roundToInt() - note.width) <= 2 && abs((shapes.heights[i] * f).roundToInt() - note.height) <= 2
                }) continue
            var sum = 0; var n = 0
            val has = shapes.gridHas[i]
            for (k in 0 until 16) {
                val c = grid[k]
                if (c < 0 || (has shr (15 - k)) and 1 == 0) continue
                val o = i * 48 + k * 3
                sum += abs(((c shr 16) and 0xFF) - lut[shapes.grids[o].toInt() and 0xFF]) +
                    abs(((c shr 8) and 0xFF) - lut[shapes.grids[o + 1].toInt() and 0xFF]) +
                    abs((c and 0xFF) - lut[shapes.grids[o + 2].toInt() and 0xFF])
                n++
            }
            if (n < 6) continue
            var apart = 0
            for (k in 0 until 64) apart += abs(cover[k] - (shapes.cover[i * 64 + k].toInt() and 0xFF))
            scored.add(i to sum / (3f * n) + SHAPE_WEIGHT * apart / (64 * 255f))
        }
        scored.sortBy { it.second }
        for (want in debugPictures) {
            val rank = scored.indexOfFirst { shapes.names[it.first] == want }
            debugLog?.invoke("  note: $want ranks $rank of ${scored.size}" + (if (rank >= 0) " (%.1f; first %.1f)".format(scored[rank].second, scored[0].second) else "") +
                " (item on note ${note.width}x${note.height})")
        }
        return scored.take(SHORTLIST).map { it.first }
    }

    // How well picture i, two thirds as big, fits the item on a note (as fit() does for whole items)
    private fun noteFit(sp: Space, note: Note, shapes: Shapes, i: Int, picture: (String) -> LoadoutReader.Picture?, lut: IntArray): Fit? {
        val pic = picture(shapes.names[i]) ?: return null
        // The game draws the item afresh, smaller (about 0.6 to two thirds as big), with its own thin black outline.
        // So: the picture's inside (its outline left out) shrunk, each pixel the average of the pixels it covers, then
        // a new outline one pixel wide round it. The colours are compared inside the outline only: a note's item can be
        // dark all over, and then its outline doesn't show where its shape is.
        val inside = BooleanArray(pic.width * pic.height) { k ->
            val c = pic.argb[k]
            ((c ushr 24) and 0xFF) > 128 && ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > OUTLINE_DARK
        }
        var best = Float.MAX_VALUE
        for (scale in NOTE_SCALES) {
            val w = (pic.width * scale).roundToInt().coerceAtLeast(1)
            val h = (pic.height * scale).roundToInt().coerceAtLeast(1)
            val rgb = IntArray(w * h)
            val body = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val ya = (y / scale).toInt(); val yb = maxOf(ya + 1, ((y + 1) / scale).toInt()).coerceAtMost(pic.height)
                val xa = (x / scale).toInt(); val xb = maxOf(xa + 1, ((x + 1) / scale).toInt()).coerceAtMost(pic.width)
                var r = 0; var g = 0; var b = 0; var on = 0; var all = 0
                for (py in ya until yb) for (px in xa until xb) {
                    all++
                    if (!inside[py * pic.width + px]) continue
                    val c = pic.argb[py * pic.width + px]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; on++
                }
                if (on > 0) rgb[y * w + x] = ((r / on) shl 16) or ((g / on) shl 8) or (b / on)
                body[y * w + x] = on * 2 >= all
            }
            val solid = BooleanArray(w * h) { k ->
                body[k] || run {
                    val x = k % w; val y = k / w
                    (x > 0 && body[k - 1]) || (x < w - 1 && body[k + 1]) || (y > 0 && body[k - w]) || (y < h - 1 && body[k + w])
                }
            }
            for (oy in note.top - 2..note.top + 2) for (ox in note.left - 2..note.left + 2) {
                if (oy < 0 || ox < 0 || oy + h > AREA_H || ox + w > AREA_W) continue
                var both = 0; var picCount = 0; var diff = 0L; var compared = 0
                for (y in 0 until h) for (x in 0 until w) {
                    val k = y * w + x
                    if (!solid[k]) continue
                    val a = (oy + y) * AREA_W + ox + x
                    if (sp.text[a]) continue
                    picCount++
                    if (note.item[a]) both++
                    if (!body[k]) continue
                    val c = rgb[k]; val sc = sp.rgb[a]
                    diff += abs(((sc shr 16) and 0xFF) - lut[(c shr 16) and 0xFF]) + abs(((sc shr 8) and 0xFF) - lut[(c shr 8) and 0xFF]) +
                        abs((sc and 0xFF) - lut[c and 0xFF])
                    compared++
                }
                if (compared == 0) continue
                val overlap = both.toFloat() / (picCount + note.count - both)
                if (overlap < MIN_NOTE_OVERLAP) continue
                val cost = diff / (3f * compared) + NOTE_SHAPE_WEIGHT * (1 - overlap)
                if (shapes.names[i] in debugPictures) debugLog?.invoke("  note fit ${shapes.names[i]} x$scale at $ox,$oy: overlap %.2f, colours %.1f".format(overlap, diff / (3f * compared)))
                if (cost < best) best = cost
            }
        }
        return if (best < Float.MAX_VALUE) Fit(i, best) else null
    }

    private const val DARK = 110            // r + g + b below this: an outline
    private const val TEXT_ROWS = 12        // an amount is written in the top rows of a space
    private const val MIN_ITEM_PIXELS = 20

    // What the outline closes in: everything a fill from outside the area can't reach without crossing it. The
    // outline is thickened by one pixel first (to close gaps where the screen blurred it) and the shape thinned
    // back after.
    private fun closedIn(outline: BooleanArray): BooleanArray {
        val w = AREA_W + 2; val h = AREA_H + 2   // with a border of "outside" all round
        val wall = BooleanArray(w * h)
        for (j in 0 until AREA_H) for (i in 0 until AREA_W) {
            if (!outline[j * AREA_W + i]) continue
            val a = (j + 1) * w + (i + 1)
            wall[a] = true; wall[a - 1] = true; wall[a + 1] = true; wall[a - w] = true; wall[a + w] = true
        }
        for (i in 0 until w) { wall[i] = false; wall[(h - 1) * w + i] = false }
        for (j in 0 until h) { wall[j * w] = false; wall[j * w + w - 1] = false }
        val outside = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        outside[0] = true; queue[tail++] = 0
        while (head < tail) {
            val a = queue[head++]
            val x = a % w; val y = a / w
            for (b in intArrayOf(if (x > 0) a - 1 else -1, if (x < w - 1) a + 1 else -1, if (y > 0) a - w else -1, if (y < h - 1) a + w else -1)) {
                if (b >= 0 && !outside[b] && !wall[b]) { outside[b] = true; queue[tail++] = b }
            }
        }
        val shape = BooleanArray(AREA_W * AREA_H)
        for (j in 0 until AREA_H) for (i in 0 until AREA_W) {
            val a = (j + 1) * w + (i + 1)
            shape[j * AREA_W + i] = !outside[a] && !outside[a - 1] && !outside[a + 1] && !outside[a - w] && !outside[a + w]
        }
        return shape
    }

    // ---------------- Narrowing down ----------------

    // How the game draws a colour value at a brightness setting (as GearRecognizer works it out)
    private fun shading(gamma: Float) = IntArray(256) { v -> (0.94f * 255f * (v / 255f).pow(gamma)).roundToInt().coerceIn(0, 255) }

    // The pictures most like the space by shape and colours in brief, best first, and then the ones most like it by
    // shape alone (what's behind a see-through panel can show through an item's gaps and change its colours in
    // brief; its shape stays)
    private fun shortlist(sp: Space, shapes: Shapes, lut: IntArray, keep: Int, lenient: Boolean = false): List<Int> {
        val scored = ArrayList<Pair<Int, Float>>()
        val byShape = ArrayList<Pair<Int, Float>>()
        for (i in 0 until shapes.count) {
            val w = shapes.widths[i]; val h = shapes.heights[i]
            if (lenient) {
                // the shape found may have grown with dark shapes behind it: the item can be quite a bit smaller
                if (w < sp.width - 10 || h < sp.height - 10 || w > sp.width + (if (sp.hasText) 6 else 2) || h > sp.height + (if (sp.hasText) 6 else 2)) continue
            } else if (sp.hasText) {
                // part of the item can be under the digits: it can be a little bigger, or look a little smaller
                // (the gap between the digits and the item counts as item: it can look up to about 6 taller or wider)
                if (w < sp.width - 8 || h < sp.height - 8 || w > sp.width + 6 || h > sp.height + 6) continue
            } else if (abs(w - sp.width) > 2 || abs(h - sp.height) > 2) continue
            val has = shapes.gridHas[i]
            var score = Float.MAX_VALUE
            var shapeOnly = Float.MAX_VALUE
            for (at in if (sp.hasText && !lenient) AT_END_ONLY else AT_ALL) {
                val grid = sp.grid(w, h, at)
                var sum = 0; var n = 0
                for (k in 0 until 16) {
                    val c = grid[k]
                    if (c < 0 || (has shr (15 - k)) and 1 == 0) continue
                    val o = i * 48 + k * 3
                    sum += abs(((c shr 16) and 0xFF) - lut[shapes.grids[o].toInt() and 0xFF]) +
                        abs(((c shr 8) and 0xFF) - lut[shapes.grids[o + 1].toInt() and 0xFF]) +
                        abs((c and 0xFF) - lut[shapes.grids[o + 2].toInt() and 0xFF])
                    n++
                }
                if (n < 6) continue
                // and how alike the shapes are (0-255 apart in each square, on average)
                val cover = sp.cover(w, h, at)
                var apart = 0
                for (k in 0 until 64) apart += abs(cover[k] - (shapes.cover[i * 64 + k].toInt() and 0xFF))
                score = minOf(score, sum / (3f * n) + SHAPE_WEIGHT * apart / (64 * 255f))
                shapeOnly = minOf(shapeOnly, apart / (64 * 255f))
            }
            if (score == Float.MAX_VALUE) continue
            val sizeOff = if (sp.hasText || lenient) 0 else abs(w - sp.width) + abs(h - sp.height)
            scored.add(i to score + 3f * sizeOff)
            byShape.add(i to shapeOnly + 0.02f * sizeOff)
        }
        scored.sortBy { it.second }
        byShape.sortBy { it.second }
        for (want in debugPictures) {
            val rank = scored.indexOfFirst { shapes.names[it.first] == want }
            if (rank >= 0) debugLog?.invoke("  $want ranks $rank of ${scored.size} (%.1f; first %.1f)".format(scored[rank].second, scored[0].second))
            val shapeRank = byShape.indexOfFirst { shapes.names[it.first] == want }
            if (shapeRank >= 0) debugLog?.invoke("  $want shape ranks $shapeRank (%.3f; first %.3f ${shapes.names[byShape[0].first]})".format(byShape[shapeRank].second, byShape[0].second))
        }
        return (scored.take(keep).map { it.first } + byShape.take(BY_SHAPE).map { it.first }).distinct()
    }

    private const val BY_SHAPE = 20   // pictures kept for their shape alone

    private const val RESCUE_COST = 30f       // a best fit worse than this gets the second, more forgiving look
    private const val RESCUE_SHORTLIST = 80
    private const val MIN_LENIENT_COVER = 0.85f

    // Where a picture-sized box goes in the item's box (from start, length, the picture's length): 0 at its start,
    // 1 in the middle, 2 at its end
    private fun place(start: Int, length: Int, size: Int, at: Int) = when (at) {
        0 -> start
        1 -> start + (length - size) / 2
        else -> start + length - size
    }
    private val AT_ALL = intArrayOf(0, 1, 2)
    private val AT_END_ONLY = intArrayOf(2)   // digits may hide the item's top left

    // ---------------- Comparing closely ----------------

    private class Fit(val index: Int, val cost: Float)

    // How well picture i fits the space at its best position: the average colour difference over the picture,
    // plus a penalty for where the shapes don't overlap. Null if they don't overlap enough anywhere.
    // lenient: anywhere in the shape found, and only how much of the picture it covers counts (not the shape found
    // beyond the picture)
    private fun fit(sp: Space, shapes: Shapes, i: Int, picture: (String) -> LoadoutReader.Picture?, lut: IntArray, lenient: Boolean = false): Fit? {
        val pic = picture(shapes.names[i]) ?: return null
        val w = pic.width; val h = pic.height
        val solid = BooleanArray(w * h) { ((pic.argb[it] ushr 24) and 0xFF) > 128 }
        val ys: IntRange; val xs: IntRange
        if (sp.hasText || lenient) {
            // its bottom right is in view, its top left may be under the digits, and the gap between the digits and
            // the item makes the box look bigger: anywhere from lined up with the box's top left to its bottom right
            ys = (minOf(sp.top, sp.top + sp.height - h) - 2)..(maxOf(sp.top, sp.top + sp.height - h) + 2)
            xs = (minOf(sp.left, sp.left + sp.width - w) - 2)..(maxOf(sp.left, sp.left + sp.width - w) + 2)
        } else {
            ys = (sp.top - 2)..(sp.top + 2)
            xs = (sp.left - 2)..(sp.left + 2)
        }
        var best = Float.MAX_VALUE
        var bestOverlap = 0f
        for (oy in ys) for (ox in xs) {
            if (oy < 0 || ox < 0 || oy + h > AREA_H || ox + w > AREA_W) continue
            var both = 0; var picCount = 0; var diff = 0L; var compared = 0
            for (y in 0 until h) for (x in 0 until w) {
                val a = (oy + y) * AREA_W + ox + x
                if (sp.text[a] || !solid[y * w + x]) continue
                picCount++
                if (sp.item[a]) both++
                val c = pic.argb[y * w + x]
                val s = sp.rgb[a]
                diff += abs(((s shr 16) and 0xFF) - lut[(c shr 16) and 0xFF]) + abs(((s shr 8) and 0xFF) - lut[(c shr 8) and 0xFF]) +
                    abs((s and 0xFF) - lut[c and 0xFF])
                compared++
            }
            if (compared == 0) continue
            // how much of the picture the item covers, and how alike the two shapes are overall. The first counts most:
            // what's behind a see-through panel can show through an item's holes and make the shape found bigger
            val overlap = if (lenient) both.toFloat() / picCount else 0.7f * both / picCount + 0.3f * both / (picCount + sp.itemCount - both)
            if (overlap > bestOverlap) bestOverlap = overlap
            if (overlap < (if (lenient) MIN_LENIENT_COVER else MIN_OVERLAP)) continue
            val cost = diff / (3f * compared) + 60f * (1 - overlap)
            if (cost < best) best = cost
        }
        if (shapes.names[i] in debugPictures) debugLog?.invoke("  ${shapes.names[i]} at item box ${sp.left},${sp.top}: best overlap %.2f, cost %.1f".format(bestOverlap, best))
        return if (best < Float.MAX_VALUE) Fit(i, best) else null
    }

    var debugPictures: Set<String> = emptySet()

    // ---------------- Amounts ----------------

    // The amount over a space's top left (yellow digits), or null
    private fun readAmount(p: PixelSource, x0: Float, y0: Float, s: Float): Int? {
        val area = Box(x0.toInt(), y0.toInt(), (x0 + (AREA_W - 2) * s).toInt(), (y0 + TEXT_ROWS * s).toInt())
        return LoadoutReader.amountIn(p, area)
    }
}
