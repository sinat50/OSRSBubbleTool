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
    // better), and the amount written on it (null if none)
    class Found(val picture: String, val others: List<String>, val cost: Float, val amount: Int?)

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

    // The 28 spaces, left to right and top to bottom: what's in each, or null if it's empty
    fun read(p: PixelSource, panel: Box, shapes: Shapes, pictures: Pictures): List<Found?> {
        val s = panel.width / PANEL_GAME_WIDTH
        val spaces = (0 until 28).map { i -> look(p, panel, s, i) }
        val cache = HashMap<String, LoadoutReader.Picture?>()
        val picture = { name: String -> cache.getOrPut(name) { pictures.get(name) } }

        // The game's brightness setting changes how item pictures are drawn: find it from a few clear spaces. Their
        // likely pictures are picked first (the best few at a dark, middle and bright setting), then the setting
        // where those fit best overall wins.
        val clear = spaces.filterNotNull().filter { !it.hasText }.take(10)
        var gamma = 1.2f
        if (clear.isNotEmpty()) {
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
            val fits = shortlist(sp, shapes, lut, if (sp.hasText) SHORTLIST_WITH_TEXT else SHORTLIST)
                .mapNotNull { fit(sp, shapes, it, picture, lut) }
                .sortedBy { it.cost }
                .toMutableList()
            preferCharged(fits, shapes)
            debugLog?.invoke("space $n (${sp.width}x${sp.height}${if (sp.hasText) ", amount ${sp.amount}" else ""}): " +
                fits.take(3).joinToString { "${shapes.names[it.index]} %.1f".format(it.cost) })
            val best = fits.firstOrNull() ?: return@mapIndexed Found("?", emptyList(), Float.MAX_VALUE, sp.amount)
            Found(shapes.names[best.index], fits.drop(1).take(5).map { shapes.names[it.index] }, best.cost, sp.amount)
        }
    }

    // An uncharged or empty item and its charged version look almost the same. When they fit about as well, the
    // charged one goes first: it's the one people take on a trip ("Eye of Ayak" before "Eye of Ayak (uncharged)").
    private fun preferCharged(fits: MutableList<Fit>, shapes: Shapes) {
        val best = fits.firstOrNull() ?: return
        val name = shapes.names[best.index]
        val charged = UNCHARGED.firstOrNull { name.contains(it) }?.let { name.replace(it, "") } ?: return
        val k = fits.indexOfFirst { shapes.names[it.index] == charged }
        if (k > 0 && fits[k].cost - best.cost < CHARGED_MARGIN) fits.add(0, fits.removeAt(k))
    }

    private val UNCHARGED = listOf(" (uncharged)", " (empty)", " (inactive)")
    private const val CHARGED_MARGIN = 2f

    // ---------------- One space ----------------

    // A space shrunk to game pixels: each pixel's colour (the middle of it, as the edges are blurred), its darkest
    // screen pixel (so a thin outline still shows), whether an amount's digits or their shadow are on it, and the
    // item's shape
    private class Space(
        val rgb: IntArray, val text: BooleanArray, val item: BooleanArray,
        val left: Int, val top: Int, val width: Int, val height: Int,   // the item's shape's box, digits left out
        val hasText: Boolean, val amount: Int?
    ) {
        val itemCount = (item.indices).count { item[it] && !text[it] }
        private val grids = HashMap<Int, IntArray>()

        // The item's colours in brief over a picture-sized box (w x h): a 4 x 4 grid like the pictures' (-1 where
        // none of the item is). The box starts at the item's top left (fromEnd false), or ends at its bottom right
        // (the outline found can be a pixel bigger on one side, or digits can hide the top left). Pictures of the
        // same size share them.
        // How much of each square of an 8 x 8 grid over a picture-sized box is the item (0-255; digits count as
        // half), placed as grid() places it
        private val covers = HashMap<Int, IntArray>()

        fun cover(w: Int, h: Int, fromEnd: Boolean): IntArray = covers.getOrPut(w * 1000 + h + if (fromEnd) 500 else 0) {
            val x0 = if (fromEnd) left + width - w else left
            val y0 = if (fromEnd) top + height - h else top
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

        fun grid(w: Int, h: Int, fromEnd: Boolean): IntArray = grids.getOrPut(w * 1000 + h + if (fromEnd) 500 else 0) {
            val x0 = if (fromEnd) left + width - w else left
            val y0 = if (fromEnd) top + height - h else top
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
                if (minOf(cr, cg) - cb > 150) anyYellow = true
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
        return Space(rgb, text, item, left, top, right - left + 1, bottom - top + 1, hasText, amount)
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

    // The pictures most like the space by shape and colours in brief, best first
    private fun shortlist(sp: Space, shapes: Shapes, lut: IntArray, keep: Int): List<Int> {
        val scored = ArrayList<Pair<Int, Float>>()
        for (i in 0 until shapes.count) {
            val w = shapes.widths[i]; val h = shapes.heights[i]
            if (sp.hasText) {
                // part of the item can be under the digits: it can be a little bigger, or look a little smaller
                if (w < sp.width - 3 || h < sp.height - 3 || w > sp.width + 6 || h > sp.height + 6) continue
            } else if (abs(w - sp.width) > 2 || abs(h - sp.height) > 2) continue
            val has = shapes.gridHas[i]
            var score = Float.MAX_VALUE
            for (fromEnd in if (sp.hasText) booleanArrayOf(true) else booleanArrayOf(false, true)) {
                val grid = sp.grid(w, h, fromEnd)
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
                val cover = sp.cover(w, h, fromEnd)
                var apart = 0
                for (k in 0 until 64) apart += abs(cover[k] - (shapes.cover[i * 64 + k].toInt() and 0xFF))
                score = minOf(score, sum / (3f * n) + SHAPE_WEIGHT * apart / (64 * 255f))
            }
            if (score == Float.MAX_VALUE) continue
            if (!sp.hasText) score += 3f * (abs(w - sp.width) + abs(h - sp.height))
            scored.add(i to score)
        }
        scored.sortBy { it.second }
        debugPicture?.let { want ->
            val rank = scored.indexOfFirst { shapes.names[it.first] == want }
            if (rank >= 0) debugLog?.invoke("  $want ranks $rank of ${scored.size} (%.1f; first %.1f)".format(scored[rank].second, scored[0].second))
        }
        return scored.take(keep).map { it.first }
    }

    // ---------------- Comparing closely ----------------

    private class Fit(val index: Int, val cost: Float)

    // How well picture i fits the space at its best position: the average colour difference over the picture,
    // plus a penalty for where the shapes don't overlap. Null if they don't overlap enough anywhere.
    private fun fit(sp: Space, shapes: Shapes, i: Int, picture: (String) -> LoadoutReader.Picture?, lut: IntArray): Fit? {
        val pic = picture(shapes.names[i]) ?: return null
        val w = pic.width; val h = pic.height
        val solid = BooleanArray(w * h) { ((pic.argb[it] ushr 24) and 0xFF) > 128 }
        val ys: IntRange; val xs: IntRange
        if (sp.hasText) {
            // its bottom right is in view; its top left may be under the digits
            ys = (sp.top + sp.height - h - 2)..(sp.top + 2)
            xs = (sp.left + sp.width - w - 2)..(sp.left + 2)
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
            val overlap = both.toFloat() / (picCount + sp.itemCount - both)
            if (overlap > bestOverlap) bestOverlap = overlap
            if (overlap < MIN_OVERLAP) continue
            val cost = diff / (3f * compared) + 60f * (1 - overlap)
            if (cost < best) best = cost
        }
        if (shapes.names[i] == debugPicture) debugLog?.invoke("  ${shapes.names[i]} at item box ${sp.left},${sp.top}: best overlap %.2f, cost %.1f".format(bestOverlap, best))
        return if (best < Float.MAX_VALUE) Fit(i, best) else null
    }

    var debugPicture: String? = null

    // ---------------- Amounts ----------------

    // The amount over a space's top left (yellow digits), or null
    private fun readAmount(p: PixelSource, x0: Float, y0: Float, s: Float): Int? {
        val area = Box(x0.toInt(), y0.toInt(), (x0 + (AREA_W - 2) * s).toInt(), (y0 + TEXT_ROWS * s).toInt())
        return LoadoutReader.amountIn(p, area)
    }
}
