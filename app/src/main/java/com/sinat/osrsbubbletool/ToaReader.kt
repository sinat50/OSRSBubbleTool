package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// Reads the Path of Scabaras puzzles from a picture of the screen, so the ToA Puzzle Helper can fill in
// its maps by itself. Nothing here is Android, so it can be checked on a computer with saved screenshots.
// Built from screenshots of the owner's phone (October 2026).
object ToaReader {

    // ---------------- Finding coloured shapes ----------------

    // One patch of yellow pixels (in the shrunk picture). `plate` = shaped and coloured like a puzzle plate.
    private class Blob(val x: Float, val y: Float, val size: Float, val lit: Boolean, val plate: Boolean)

    // Pictures are shrunk to about this many rows first: plenty to see the plates, and quick
    private const val WORK_ROWS = 540

    // The yellow of the plates. Unlit plates are a flat yellow square (about 156,121,11 on the owner's
    // phone); lit ones are a paler yellow glow (about 195,165,80). Both pass this test; little else does.
    private fun yellowish(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 110 && g > 0.62f * r && g < 0.92f * r && b < 0.62f * r && r - b > 70
    }

    // Finds every patch of yellow about the size of a plate or symbol, marking the solid, roughly square
    // ones that look like a puzzle plate, and whether each is lit (pale) or unlit (deep yellow)
    private fun findPatches(p: LightBoxReader.PixelSource): List<Blob> {
        val step = maxOf(1, p.height / WORK_ROWS)
        val w = p.width / step
        val h = p.height / step
        val mask = BooleanArray(w * h) { i -> yellowish(p.rgb((i % w) * step, (i / w) * step)) }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val minArea = h * h * 0.0002f
        val maxArea = h * h * 0.004f
        val found = ArrayList<Blob>()

        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            // Flood-fill this patch, adding up what we need as we go
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0; var sumX = 0L; var sumY = 0L; var sumR = 0L; var sumB = 0L
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w; val y = i / w
                count++; sumX += x; sumY += y
                val c = p.rgb(x * step, y * step)
                sumR += (c shr 16) and 0xFF; sumB += c and 0xFF
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (x < w - 1) { val j = i + 1; if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (y > 0) { val j = i - w; if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (y < h - 1) { val j = i + w; if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j } }
            }
            if (count < minArea * 0.4f || count > maxArea) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            // How blue it is for its redness tells deep yellow (unlit) from pale yellow (lit)
            val blueness = sumB.toFloat() / sumR
            val lit = blueness in 0.3f..0.6f
            val plate = count >= minArea &&
                bw.toFloat() / bh in 0.55f..1.8f &&                 // about square
                count.toFloat() / (bw * bh) >= 0.42f &&             // solid (a square seen corner-on is a diamond, which fills half its box)
                (blueness <= 0.12f || lit)
            found.add(Blob(sumX.toFloat() / count, sumY.toFloat() / count, sqrt(count.toFloat()), lit, plate))
        }
        return found
    }

    // ---------------- A square of plates (light and sequence puzzles) ----------------
    // Both puzzles are plates on a 3×3 square of floor, two tiles apart: the light puzzle has eight round
    // the edge, the sequence puzzle has a ninth in the middle. On screen the square can be turned any way.

    // Where the plates are, by square position (0-8, left to right and top to bottom once the map is
    // turned to match; null = no plate there), and how far to turn an upright map, in degrees clockwise,
    // so it looks like the screen. Positions are in the shrunk picture: multiply by `scale` for the screen.
    private class Square(val plates: Array<Blob?>, val turn: Float, val scale: Int, val size: Float)

    // The square positions going clockwise round the edge from the top-left corner
    private val EDGE = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)
    private const val MAX_CANDIDATES = 14

    private fun findSquare(p: LightBoxReader.PixelSource, withMiddle: Boolean): Square? {
        val step = maxOf(1, p.height / WORK_ROWS)
        val w = p.width / step
        val h = p.height / step
        // The camera follows you, so the puzzle you're in is near the middle: keep the nearest patches
        val patches = findPatches(p)
        val plates = patches.filter { it.plate }.sortedBy { hypot(it.x - w / 2f, it.y - h / 2f) }.take(MAX_CANDIDATES)
        if (plates.size < 8) return null

        var best: Array<Blob>? = null
        var bestMiddle: Blob? = null
        var bestScore = Float.MAX_VALUE
        var bestMidsOdd = false
        val pick = IntArray(8)
        // Try every group of eight and keep the one that looks most like the edge of the square
        fun tryGroup() {
            val group = Array(8) { plates[pick[it]] }
            val sizes = group.map { it.size }
            if (sizes.max() > 1.8f * sizes.min()) return
            val size = sizes.average().toFloat()
            val cx = group.map { it.x }.average().toFloat()
            val cy = group.map { it.y }.average().toFloat()
            // In order round the middle (clockwise on screen)
            val ring = group.sortedBy { atan2(it.y - cy, it.x - cx) }.toTypedArray()
            // Every other plate is a corner; each plate between two corners sits halfway between them
            var bestErr = Float.MAX_VALUE; var midsOdd = false
            for (s in 0..1) {
                var err = 0f
                for (k in s until 8 step 2) {
                    val a = ring[(k + 7) % 8]; val c = ring[(k + 1) % 8]
                    err += hypot(ring[k].x - (a.x + c.x) / 2f, ring[k].y - (a.y + c.y) / 2f)
                }
                if (err < bestErr) { bestErr = err; midsOdd = s == 1 }
            }
            val err = bestErr / 4f / size
            if (err > 0.6f) return
            // Neighbours a sensible distance apart
            for (k in 0 until 8) {
                val d = hypot(ring[k].x - ring[(k + 7) % 8].x, ring[k].y - ring[(k + 7) % 8].y) / size
                if (d < 2f || d > 7f) return
            }
            // A plate in the middle for the sequence puzzle; none for the light puzzle (or the sequence
            // puzzle's nine tiles could pass for its ring)
            val middle = plates.firstOrNull { it !in group && hypot(it.x - cx, it.y - cy) < size * 1.5f }
            if ((middle != null) != withMiddle) return
            // The floor between the plates is plain. The addition puzzle's floor is covered in yellow symbols,
            // some of which could otherwise pass for a ring of plates.
            val reach = ring.maxOf { hypot(it.x - cx, it.y - cy) } * 1.1f
            if (patches.count { hypot(it.x - cx, it.y - cy) <= reach } > (if (withMiddle) 13 else 11)) return
            val score = err + hypot(cx - w / 2f, cy - h / 2f) / h
            if (score < bestScore) { bestScore = score; best = ring; bestMiddle = middle; bestMidsOdd = midsOdd }
        }
        fun choose(from: Int, depth: Int) {
            if (depth == 8) { tryGroup(); return }
            for (i in from..plates.size - (8 - depth)) { pick[depth] = i; choose(i + 1, depth + 1) }
        }
        choose(0, 0)
        val ring = best ?: return null

        // Which corner is the map's top-left: the one that needs the least turning to get there
        val cx = ring.map { it.x }.average().toFloat()
        val cy = ring.map { it.y }.average().toFloat()
        val firstCorner = if (bestMidsOdd) 0 else 1
        var bestStart = 0
        var bestTurn = Float.MAX_VALUE
        for (start in 0 until 4) {
            // average how far each corner is from where it would sit on an upright map
            var sx = 0f; var sy = 0f
            for (k in 0 until 4) {
                val plate = ring[(firstCorner + 2 * (start + k)) % 8]
                val seen = atan2(plate.y - cy, plate.x - cx)
                val upright = Math.toRadians(-135.0 + 90.0 * k).toFloat()
                sx += cos(seen - upright); sy += sin(seen - upright)
            }
            val turn = Math.toDegrees(atan2(sy, sx).toDouble()).toFloat()
            if (abs(turn) < abs(bestTurn)) { bestTurn = turn; bestStart = start }
        }
        val square = arrayOfNulls<Blob>(9)
        val origin = firstCorner + 2 * bestStart
        for (k in 0 until 8) square[EDGE[k]] = ring[(origin + k) % 8]
        square[4] = bestMiddle
        return Square(square, bestTurn, step, ring.map { it.size }.average().toFloat())
    }

    // Maps are kept straight: a camera a little off one of the four directions turns the map a whole
    // quarter turn (or not at all), never part of one
    private fun straight(turn: Float) = Math.round(turn / 90f) * 90f

    // ---------------- Light puzzle ----------------

    // What was read: the lit plates (as ToaPuzzles numbers them) and how far to turn the map, in degrees
    // clockwise, so it looks like the screen (always 0: the plates are numbered to match instead)
    class LightReading(val lit: Int, val rotation: Float)

    // Finds the ring of eight plates on screen and reads which are lit. Null if it can't be seen.
    fun readLights(p: LightBoxReader.PixelSource): LightReading? {
        val square = findSquare(p, withMiddle = false) ?: return null
        var lit = 0
        for (cell in 0 until 9) {
            if (cell == 4 || square.plates[cell]?.lit != true) continue
            lit = lit or (1 shl (if (cell > 4) cell - 1 else cell))   // ToaPuzzles skips the middle
        }
        return LightReading(lit, straight(square.turn))
    }

    // ---------------- Sequence puzzle ----------------

    // Where the nine tiles are on screen, in ToaPuzzles' order (SEQUENCE_CELLS), so they can be watched;
    // and how far to turn the app's diamond-shaped map so it looks like the screen
    class SequenceTiles(val x: FloatArray, val y: FloatArray, val radius: Float, val rotation: Float)

    // Finds the nine tiles. Null if they can't all be seen.
    fun findSequenceTiles(p: LightBoxReader.PixelSource): SequenceTiles? {
        val square = findSquare(p, withMiddle = true) ?: return null
        val x = FloatArray(9)
        val y = FloatArray(9)
        for (cell in 0 until 9) {
            val plate = square.plates[cell] ?: return null
            val r = cell / 3; val c = cell % 3
            // The app draws the square turned an eighth of a turn, as a diamond: top-left corner at the top
            val i = ToaPuzzles.SEQUENCE_CELLS.indexOf((r + c) to (2 - r + c))
            x[i] = plate.x * square.scale
            y[i] = plate.y * square.scale
        }
        return SequenceTiles(x, y, square.size * square.scale * 0.45f, straight(square.turn - 45f))
    }

    // Which tile is lit up right now (its plate glows pale), or -1 if none clearly is
    fun litSequenceTile(p: LightBoxReader.PixelSource, tiles: SequenceTiles): Int {
        var best = -1; var bestPale = 0f; var second = 0f
        for (i in 0 until 9) {
            val pale = paleness(p, tiles.x[i], tiles.y[i], tiles.radius)
            if (pale > bestPale) { second = bestPale; bestPale = pale; best = i } else if (pale > second) second = pale
        }
        // a flashing tile is almost all pale; the others (even under its beam of light) hardly at all
        return if (bestPale >= 0.5f && second < 0.25f) best else -1
    }

    // How much of a small round patch is the pale glow of a lit plate (0 to 1), from about 30 pixels
    private fun paleness(p: LightBoxReader.PixelSource, cx: Float, cy: Float, radius: Float): Float {
        var pale = 0; var all = 0
        for (dy in -3..3) for (dx in -3..3) {
            if (dx * dx + dy * dy > 9) continue
            val x = (cx + dx * radius / 3f).toInt()
            val y = (cy + dy * radius / 3f).toInt()
            if (x < 0 || y < 0 || x >= p.width || y >= p.height) continue
            val c = p.rgb(x, y)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            all++
            if (r > 150 && g > 0.7f * r && b >= 0.25f * r) pale++
        }
        return if (all == 0) 0f else pale.toFloat() / all
    }

    // ---------------- Addition puzzle: the tablet's number ----------------
    // Reading the tablet puts "The number 30 has been hastily chipped into the stone." in the chat box, with
    // the number in red. This finds two red digits side by side in the chat and reads them.

    // What each digit 0-9 looks like in the game's chat font, as an 8×12 grid of how much of each square
    // is filled (0-9). Cut from the owner's phone, typed into the chat box.
    private const val GRID_W = 8
    private const val GRID_H = 12
    private val DIGITS = arrayOf(
        "002992000287072047302374970092769700927497009274972900749729007496290074434203610387321000294100",
        "000690000079900037799000741490000002900000069000000990000009900000099000000990007779977769644444",
        "079994009870047073000377000000690000038600000970000292000048200005720000771000009877777778444444",
        "049999409875004973000169000002940057729204999940000000490000029700000292730002929877779204984420",
        "470000006700000097000000970000009700000097009400970092009700920088779776332296210000920000009200",
        "699999999920000099200000994370004999450000000940000000490000004900000049272000492977778402884420",
        "000994000397247048400330970000009302700094099400997004709600002992000029234003620397742000296200",
        "999999997720028900002243000079400002871000099000000960000497000004950000784000009610000094000000",
        "004994000787247077300387761004840787293000499400074004709200007992000078233007710247762000296200",
        "079999709770002992000069920000769200007407744274004999940000006400000064000000740000007400000044"
    )
    // Each digit's width for its height
    private val DIGIT_SHAPE = floatArrayOf(0.64f, 0.5f, 0.59f, 0.5f, 0.64f, 0.5f, 0.59f, 0.5f, 0.59f, 0.59f)
    private const val MAX_DIGIT_ERROR = 20f   // out of 96: how unlike the closest digit a shape may be

    // The chat's red: about 186,35,51, darker at the edges
    private fun chatRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r > 120 && r > g * 1.8f && r > b * 1.8f
    }

    private class Glyph(val left: Int, val top: Int, val right: Int, val bottom: Int, val label: Int) {
        val w get() = right - left + 1
        val h get() = bottom - top + 1
    }

    // The tablet's number (20 to 45) from the chat box, or null if it isn't there
    fun readAdditionNumber(p: LightBoxReader.PixelSource): Int? {
        // The chat box is on the left; the health orb (which can be red too) is on the right
        val w = p.width * 6 / 10
        val h = p.height
        val labels = IntArray(w * h)
        val stack = IntArray(w * h)
        val glyphs = ArrayList<Glyph>()
        val minH = h * 0.012f
        val maxH = h * 0.03f
        var next = 0
        for (start in 0 until w * h) {
            if (labels[start] != 0 || !chatRed(p.rgb(start % w, start / w))) continue
            // Flood-fill this red shape (corners count as touching), noting where it reaches
            val id = ++next
            var top = 0
            stack[top++] = start
            labels[start] = id
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w; val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (labels[j] == 0 && chatRed(p.rgb(nx, ny))) { labels[j] = id; stack[top++] = j }
                }
            }
            val g = Glyph(minX, minY, maxX, maxY, id)
            if (g.h >= minH && g.h <= maxH && g.w >= g.h * 0.25f && g.w <= g.h) glyphs.add(g)
        }

        var best: Int? = null
        var bestY = -1
        for (a in glyphs) {
            // The digit to its right, on the same line and right next to it
            val b = glyphs.firstOrNull { o ->
                o !== a && abs(o.top - a.top) <= a.h * 0.15f && abs(o.h - a.h) <= a.h * 0.2f &&
                    o.left > a.right - 1 && o.left - a.right <= a.h * 0.5f
            } ?: continue
            // Exactly two digits: nothing else red right before or after
            if (glyphs.any { o -> o !== a && o !== b && abs(o.top - a.top) <= a.h * 0.3f &&
                    (a.left - o.right in -1..(a.h / 2) || o.left - b.right in -1..(a.h / 2)) }) continue
            val tens = readDigit(labels, w, a) ?: continue
            val ones = readDigit(labels, w, b) ?: continue
            val n = tens * 10 + ones
            // the newest chat line is the lowest one
            if (n in ToaPuzzles.ADDITION_MIN..ToaPuzzles.ADDITION_MAX && a.top > bestY) { best = n; bestY = a.top }
        }
        return best
    }

    // Which digit a red shape is, or null if it isn't much like any of them
    private fun readDigit(labels: IntArray, w: Int, g: Glyph): Int? {
        // How much of each square of an 8×12 grid over the shape is filled (sampled 4×4 times per square)
        val filled = FloatArray(GRID_W * GRID_H)
        for (i in 0 until GRID_H) for (j in 0 until GRID_W) {
            var n = 0
            for (a in 0 until 4) for (b in 0 until 4) {
                val x = g.left + ((j + (b + 0.5f) / 4) / GRID_W * g.w).toInt()
                val y = g.top + ((i + (a + 0.5f) / 4) / GRID_H * g.h).toInt()
                if (labels[y * w + x] == g.label) n++
            }
            filled[i * GRID_W + j] = n / 16f
        }
        val shape = g.w.toFloat() / g.h
        var best = -1
        var bestErr = Float.MAX_VALUE
        for (d in 0..9) {
            var err = 20f * abs(shape - DIGIT_SHAPE[d])
            for (k in filled.indices) err += abs(filled[k] - (DIGITS[d][k] - '0') / 9f)
            if (err < bestErr) { bestErr = err; best = d }
        }
        return if (bestErr <= MAX_DIGIT_ERROR) best else null
    }

    // ---------------- Matching puzzle ----------------
    // Two 3×3 boards of tiles, two floor tiles apart, either side of a statue. A tile is plain grey until
    // you step on it, then shows a dull yellow symbol on purple until you step on the next tile; matched
    // tiles glow pale. The camera follows you, so the boards are found again in every picture.

    // Where the boards are on screen: each board's middle tile, and the step from one tile to the next
    // going right (col) and down (row) on screen. Board 0 is the left one.
    class MatchBoards(
        val cx: FloatArray, val cy: FloatArray,
        val colX: FloatArray, val colY: FloatArray,
        val rowX: FloatArray, val rowY: FloatArray,
        // whether each board was actually found in this picture (rather than worked out from where it slid):
        // only boards actually found are read, so a guess can't put a tile's reading on the wrong tile
        val seen: BooleanArray = booleanArrayOf(true, true),
        // whether each board was found clearly (8 or 9 of its tiles lined up): only those are read, as a board
        // found from just 7 can be a tile out of place
        val clear: BooleanArray = booleanArrayOf(true, true)
    ) {
        // Tile t (0-8 left board, 9-17 right, left to right and top to bottom) on screen
        fun tileX(t: Int): Float { val b = t / 9; val c = t % 9; return cx[b] + (c % 3 - 1) * colX[b] + (c / 3 - 1) * rowX[b] }
        fun tileY(t: Int): Float { val b = t / 9; val c = t % 9; return cy[b] + (c % 3 - 1) * colY[b] + (c / 3 - 1) * rowY[b] }
    }

    // What one tile shows: still grey, glowing (matched), a symbol (with how sure, 0 to 1), or can't tell
    class MatchTile(val state: Int, val symbol: ToaPuzzles.Symbol? = null, val sure: Float = 0f)
    const val TILE_HIDDEN = 0
    const val TILE_MATCHED = 1
    const val TILE_SYMBOL = 2
    const val TILE_UNKNOWN = 3

    // A patch of one kind of pixel, in the shrunk picture
    private class Patch(val x: Float, val y: Float, val count: Int, val w: Int, val h: Int, var grey: Boolean = false)

    private fun hiddenGrey(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return abs(r - 93) <= 7 && abs(g - 86) <= 7 && abs(b - 74) <= 7 && r - b in 12..26
    }

    // Finds patches of pixels that pass `test` (and aren't in `skip`, e.g. the bubble's own window)
    // Where the app's own windows are, so their pixels are left out: "is (x, y) covered?". A plain interface
    // rather than a Kotlin function type, so asking it for every pixel makes no objects on the phone.
    fun interface Skip { fun at(x: Int, y: Int): Boolean }

    // Working memory kept from one look to the next (Watch looks several times a second)
    private var workKind = ByteArray(0)
    private var workSeen = BooleanArray(0)
    private var workStack = IntArray(0)

    // Gives the working memory back once Watch stops (it's made again on the next look)
    fun releaseWork() {
        workKind = ByteArray(0); workSeen = BooleanArray(0); workStack = IntArray(0)
    }
    private const val GREY: Byte = 1
    private const val YELLOW: Byte = 2

    // Finds the patches of hidden-tile grey and of yellow, in one pass over the shrunk picture
    private fun markPatches(p: LightBoxReader.PixelSource, step: Int, skip: Skip): Pair<List<Patch>, List<Patch>> {
        val w = p.width / step
        val h = p.height / step
        if (workKind.size < w * h) { workKind = ByteArray(w * h); workSeen = BooleanArray(w * h); workStack = IntArray(w * h) }
        val kind = workKind; val seen = workSeen; val stack = workStack
        val x0 = 0; val y0 = 0; val x1 = w; val y1 = h   // the whole picture (searching only near the boards was tried: worse)
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val i = row + x
                seen[i] = false
                val px = x * step; val py = y * step
                kind[i] = if (skip.at(px, py)) 0 else {
                    val c = p.rgb(px, py)
                    if (hiddenGrey(c)) GREY else if (yellowish(c)) YELLOW else 0
                }
            }
        }
        val grey = ArrayList<Patch>()
        val yellow = ArrayList<Patch>()
        for (sy in y0 until y1) for (sx in x0 until x1) {
            val start = sy * w + sx
            val k = kind[start]
            if (k == 0.toByte() || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0; var sumX = 0L; var sumY = 0L
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w; val y = i / w
                count++; sumX += x; sumY += y
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > x0) { val j = i - 1; if (kind[j] == k && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (x < x1 - 1) { val j = i + 1; if (kind[j] == k && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (y > y0) { val j = i - w; if (kind[j] == k && !seen[j]) { seen[j] = true; stack[top++] = j } }
                if (y < y1 - 1) { val j = i + w; if (kind[j] == k && !seen[j]) { seen[j] = true; stack[top++] = j } }
            }
            val patch = Patch(sumX.toFloat() / count, sumY.toFloat() / count, count, maxX - minX + 1, maxY - minY + 1)
            if (k == GREY) grey.add(patch) else yellow.add(patch)
        }
        return grey to yellow
    }

    // One board found: its middle, its two steps, and which marks it used
    private class Board(val cx: Float, val cy: Float, val ux: Float, val uy: Float, val vx: Float, val vy: Float,
                        val hits: Int, val err: Float, val used: Set<Int>)

    // For the PC test only: when set, findMatchBoards reports the boards it considers
    var debugLog: ((String) -> Unit)? = null

    // Finds the boards. With `previous` (the last picture's boards), one board on screen is enough: the
    // other is assumed to have moved the same way. Without it, both must be seen. Null if they can't be.
    fun findMatchBoards(p: LightBoxReader.PixelSource, skip: Skip, previous: MatchBoards? = null): MatchBoards? =
        searchBoards(p, skip, previous)

    private fun searchBoards(p: LightBoxReader.PixelSource, skip: Skip, previous: MatchBoards?): MatchBoards? {
        val step = maxOf(1, p.height / WORK_ROWS)
        val h = p.height / step
        val hh = h.toFloat() * h
        // Marks: the grey squares of hidden tiles, and the yellow of symbols (dull or glowing)
        val marks = ArrayList<Patch>()
        val (greyPatches, yellowPatches) = markPatches(p, step, skip)
        greyPatches.filter {
            // (as small as they are at 25% zoom)
            it.count > hh * 0.0005f && it.count < hh * 0.012f && it.w.toFloat() / it.h in 0.6f..1.7f &&
                it.count.toFloat() / (it.w * it.h) >= 0.6f
        }.forEach { it.grey = true; marks.add(it) }
        // (small enough for the thin glowing line symbol)
        yellowPatches.filterTo(marks) {
            it.count > hh * 0.00012f && it.count < hh * 0.004f && it.w.toFloat() / it.h in 0.15f..6f
        }
        // Keep the marks nearest the middle of the screen (the boards are round you)
        val w = p.width / step
        val pts = marks.sortedBy { hypot(it.x - w / 2f, it.y - h / 2f) }.take(48)
        val n = pts.size
        val follow = { previous?.let { followBoards(pts.map { it.x * step to it.y * step }, it) } }
        if (n < 7) return follow()

        // Try each mark as any tile of a board, with pairs of its nearest marks as the steps to the next tiles
        val xs = FloatArray(n) { pts[it].x }
        val ys = FloatArray(n) { pts[it].y }
        // the mark nearest (x, y) within `tol`, or -1
        fun nearest(x: Float, y: Float, tol: Float): Int {
            var bestK = -1; var bestD = tol * tol
            for (k in 0 until n) {
                val dx = xs[k] - x; val dy = ys[k] - y
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; bestK = k }
            }
            return bestK
        }
        val boards = ArrayList<Board>()
        val hit = IntArray(9)
        for (m in 0 until n) {
            val near = (0 until n).filter { it != m && hypot(xs[it] - xs[m], ys[it] - ys[m]) in (h * 0.06f)..(h * 0.3f) }
                .sortedBy { hypot(xs[it] - xs[m], ys[it] - ys[m]) }.take(6)
            for (a in near) for (b in near) {
                val ux = xs[a] - xs[m]; val uy = ys[a] - ys[m]
                val vx = xs[b] - xs[m]; val vy = ys[b] - ys[m]
                val cross = ux * vy - uy * vx
                val lu = hypot(ux, uy); val lv = hypot(vx, vy)
                if (cross <= 0f || cross / (lu * lv) < 0.5f || lu / lv !in 0.5f..2f) continue
                val tol = 0.3f * minOf(lu, lv)
                // the mark could be any of the board's nine tiles
                for (i0 in -1..1) for (j0 in -1..1) {
                    val cx = xs[m] - i0 * ux - j0 * vx; val cy = ys[m] - i0 * uy - j0 * vy
                    var hits = 0; var err = 0f
                    for (i in -1..1) for (j in -1..1) {
                        val qx = cx + i * ux + j * vx; val qy = cy + i * uy + j * vy
                        val k = nearest(qx, qy, tol)
                        hit[(i + 1) * 3 + j + 1] = k
                        if (k >= 0) { hits++; err += hypot(xs[k] - qx, ys[k] - qy) }
                    }
                    // Seven of the nine is enough (a thin symbol, or you, can hide one or two). A board seen for the
                    // first time must have some grey (not yet stepped on) tiles: the light and addition rooms have none.
                    if (hits < 7) continue
                    if (previous == null && (0 until 9).count { hit[it] >= 0 && pts[hit[it]].grey } < 3) continue
                    // The floor between a board's tiles is plain. The addition puzzle's grid has a symbol on every
                    // tile, so marks halfway between "tiles" mean it isn't a matching board.
                    var between = 0
                    for (i in -1..1) for (j in -1..1) {
                        if (i < 1 && nearest(cx + (i + 0.5f) * ux + j * vx, cy + (i + 0.5f) * uy + j * vy, tol) >= 0) between++
                        if (j < 1 && nearest(cx + i * ux + (j + 0.5f) * vx, cy + i * uy + (j + 0.5f) * vy, tol) >= 0) between++
                    }
                    if (between >= 3) continue
                    boards.add(fitBoard(pts, hit, hits, err))
                }
            }
        }
        boards.sortWith(compareBy<Board>({ -it.hits }, { it.err }))
        debugLog?.let { log ->
            boards.take(12).forEach { b ->
                log("board at (%.0f, %.0f) step (%.0f, %.0f) hits %d err %.1f grey %d yellow %d".format(b.cx * step, b.cy * step,
                    b.ux * step, b.uy * step, b.hits, b.err, b.used.count { pts[it].grey }, b.used.count { !pts[it].grey }))
            }
        }
        val first = boards.firstOrNull() ?: return follow()
        // Both boards afresh: the second sits beside the first, along a line of its tiles, about 4 to 9 tiles
        // away, the same size and lined up with it: never far above or below (the grey floor of the next room can
        // otherwise pass for a board). Null if there isn't such a pair.
        fun freshPair(): Pair<Board, Board>? {
            fun beside(a: Board, b: Board): Boolean {
                val la = hypot(a.ux, a.uy); val lb = hypot(b.ux, b.uy)
                if (lb / la !in 0.75f..1.33f) return false
                val dx = b.cx - a.cx; val dy = b.cy - a.cy
                for ((sx, sy) in listOf(a.ux to a.uy, a.vx to a.vy)) {
                    val len = hypot(sx, sy)
                    val along = abs(dx * sx + dy * sy) / (len * len)
                    val across = abs(dx * sy - dy * sx) / (len * len)
                    if (along in 4f..9f && across < 1.5f) return true
                }
                return false
            }
            // A real board has yellow on it (glowing pairs, revealed symbols). The floor of the next room, seen
            // through the see-through inventory, makes a near-perfect "board" of dark squares with at most a speck
            // of gold. So of all the side-by-side pairs, take the one where both boards have the most yellow.
            fun yellow(b: Board) = b.used.count { !pts[it].grey }
            val top = boards.take(60)
            var best: Pair<Board, Board>? = null
            var bestScore = intArrayOf(-1, -1, -1)
            for (i in top.indices) for (j in i + 1 until top.size) {
                val x = top[i]; val y = top[j]
                if (y.used.any { it in x.used } || !beside(x, y)) continue
                val score = intArrayOf(minOf(yellow(x), yellow(y)), yellow(x) + yellow(y), x.hits + y.hits)
                val better = (0..2).firstOrNull { score[it] != bestScore[it] }?.let { score[it] > bestScore[it] } ?: false
                if (better) { bestScore = score; best = x to y }
            }
            val (x, y) = best ?: return null
            return scaled(x, step) to scaled(y, step)
        }

        val prev = previous
        if (prev == null) {
            // The first look: both boards needed. Left board first, along the boards' own left-to-right direction;
            // each board's directions taken from the screen (right and down).
            val (a, b) = freshPair() ?: return null
            val found = listOf(a, b)
            val (colX, colY) = columnStep(found[0])
            val sorted = found.sortedBy { it.cx * colX + it.cy * colY }
            return boardsOf(sorted[0], sorted[1])
        }

        // After that, each board keeps who it is and which way it faces from one picture to the next, so turning
        // the camera doesn't mix up the boards or which tile is which
        val second = boards.firstOrNull { b -> b.used.none { it in first.used } &&
            hypot(b.cx - first.cx, b.cy - first.cy) > 2.5f * hypot(first.ux, first.uy) }
        val found = listOfNotNull(first, second).map { scaled(it, step) }
        val assigned = arrayOfNulls<Board>(2)
        fun gap(f: Board, k: Int) = hypot(f.cx - prev.cx[k], f.cy - prev.cy[k])
        if (found.size == 2) {
            if (gap(found[0], 0) + gap(found[1], 1) <= gap(found[0], 1) + gap(found[1], 0)) {
                assigned[0] = found[0]; assigned[1] = found[1]
            } else {
                assigned[0] = found[1]; assigned[1] = found[0]
            }
        } else {
            assigned[if (gap(found[0], 0) <= gap(found[0], 1)) 0 else 1] = found[0]
        }
        val cx = prev.cx.copyOf(); val cy = prev.cy.copyOf()
        val colX = prev.colX.copyOf(); val colY = prev.colY.copyOf()
        val rowX = prev.rowX.copyOf(); val rowY = prev.rowY.copyOf()
        val seen = BooleanArray(2)
        val clear = BooleanArray(2)
        for (k in 0..1) {
            val f = assigned[k] ?: continue
            // a board can't really move more than a couple of its tiles between two pictures: a jump is a bad fit
            if (gap(f, k) > 2.5f * hypot(prev.colX[k], prev.colY[k])) continue
            val steps = orientLike(f, prev.colX[k], prev.colY[k], prev.rowX[k], prev.rowY[k]) ?: continue
            cx[k] = f.cx; cy[k] = f.cy
            colX[k] = steps[0]; colY[k] = steps[1]; rowX[k] = steps[2]; rowY[k] = steps[3]
            seen[k] = true
            clear[k] = f.hits >= 8
        }
        if (!seen[0] && !seen[1]) {
            // Lost both (they moved too far while out of sight, say): find them afresh, each matched to the board
            // it was before and facing as near as it can to how it faced
            freshPair()?.let { (a, b) ->
                val pair = if (gap(a, 0) + gap(b, 1) <= gap(a, 1) + gap(b, 0)) listOf(a, b) else listOf(b, a)
                for (k in 0..1) {
                    val f = pair[k]
                    val st = orientLike(f, prev.colX[k], prev.colY[k], prev.rowX[k], prev.rowY[k], strict = false)!!
                    cx[k] = f.cx; cy[k] = f.cy
                    colX[k] = st[0]; colY[k] = st[1]; rowX[k] = st[2]; rowY[k] = st[3]
                }
                return MatchBoards(cx, cy, colX, colY, rowX, rowY, booleanArrayOf(true, true), booleanArrayOf(pair[0].hits >= 8, pair[1].hits >= 8))
            }
            return follow()
        }
        if (seen[0] != seen[1]) {
            // One board found: the other (hidden, or not trusted this time) has moved with it, turning and
            // growing or shrinking the same way, as the camera turns and zooms
            val k = if (seen[0]) 0 else 1
            val o = 1 - k
            val turn = atan2(colY[k], colX[k]) - atan2(prev.colY[k], prev.colX[k])
            val scale = hypot(colX[k], colY[k]) / hypot(prev.colX[k], prev.colY[k])
            val c = cos(turn) * scale; val sn = sin(turn) * scale
            fun turnX(x: Float, y: Float) = x * c - y * sn
            fun turnY(x: Float, y: Float) = x * sn + y * c
            val ox = prev.cx[o] - prev.cx[k]; val oy = prev.cy[o] - prev.cy[k]
            cx[o] = cx[k] + turnX(ox, oy); cy[o] = cy[k] + turnY(ox, oy)
            colX[o] = turnX(prev.colX[o], prev.colY[o]); colY[o] = turnY(prev.colX[o], prev.colY[o])
            rowX[o] = turnX(prev.rowX[o], prev.rowY[o]); rowY[o] = turnY(prev.rowX[o], prev.rowY[o])
        }
        return MatchBoards(cx, cy, colX, colY, rowX, rowY, seen, clear)
    }

    // Of a board's four steps (±u, ±v), the ones closest to the steps it had last time, as colX, colY, rowX, rowY.
    // Null if even the closest has turned or stretched too much since then: a bad fit, not to be trusted.
    private fun orientLike(b: Board, pcx: Float, pcy: Float, prx: Float, pry: Float, strict: Boolean = true): FloatArray? {
        val steps = listOf(b.ux to b.uy, -b.ux to -b.uy, b.vx to b.vy, -b.vx to -b.vy)
        fun likeness(x: Float, y: Float, px: Float, py: Float) = (x * px + y * py) / (hypot(x, y) * hypot(px, py))
        val col = steps.maxBy { likeness(it.first, it.second, pcx, pcy) }
        val colIsU = steps.indexOf(col) < 2
        val row = steps.filterIndexed { i, _ -> (i < 2) != colIsU }.maxBy { likeness(it.first, it.second, prx, pry) }
        fun ok(x: Float, y: Float, px: Float, py: Float) =
            likeness(x, y, px, py) >= 0.82f && hypot(x, y) / hypot(px, py) in 0.7f..1.43f   // within about 35°, and ±40% size
        if (strict && (!ok(col.first, col.second, pcx, pcy) || !ok(row.first, row.second, prx, pry))) return null
        return floatArrayOf(col.first, col.second, row.first, row.second)
    }

    // The last boards moved by however far the marks on screen say they've slid. Each mark near a tile's last
    // position suggests a slide; the slide most marks agree on wins. Null if too few agree.
    private fun followBoards(marks: List<Pair<Float, Float>>, prev: MatchBoards): MatchBoards? {
        val stepLen = hypot(prev.colX[0], prev.colY[0])
        val reach = stepLen * 1.2f   // as far as you can walk between two pictures, with room to spare
        val tol = stepLen * 0.3f
        val tiles = (0 until 18).map { prev.tileX(it) to prev.tileY(it) }
        var bestDx = 0f; var bestDy = 0f; var bestCount = 0
        for ((mx, my) in marks) for ((tx, ty) in tiles) {
            val dx = mx - tx; val dy = my - ty
            if (hypot(dx, dy) > reach) continue
            // how many marks sit on a tile if everything slid by (dx, dy)
            var count = 0
            for ((ox, oy) in marks) if (tiles.any { (ax, ay) -> hypot(ox - ax - dx, oy - ay - dy) < tol }) count++
            if (count > bestCount) { bestCount = count; bestDx = dx; bestDy = dy }
        }
        if (bestCount < 5) return null
        // average the agreeing marks for a steadier slide
        var sx = 0f; var sy = 0f; var n = 0
        for ((ox, oy) in marks) {
            val near = tiles.minByOrNull { (ax, ay) -> hypot(ox - ax - bestDx, oy - ay - bestDy) } ?: continue
            if (hypot(ox - near.first - bestDx, oy - near.second - bestDy) < tol) { sx += ox - near.first; sy += oy - near.second; n++ }
        }
        val dx = sx / n; val dy = sy / n
        return MatchBoards(
            FloatArray(2) { prev.cx[it] + dx }, FloatArray(2) { prev.cy[it] + dy },
            prev.colX, prev.colY, prev.rowX, prev.rowY, booleanArrayOf(false, false))
    }

    // The board's middle and steps, best fitted to all the marks it found (least squares)
    private fun fitBoard(pts: List<Patch>, hit: IntArray, hits: Int, err: Float): Board {
        // x = cx + i·ux + j·vx (and the same for y), with i, j = -1, 0, 1
        val m = Array(3) { FloatArray(3) }
        val bx = FloatArray(3); val by = FloatArray(3)
        for (cell in 0 until 9) {
            val k = hit[cell]
            if (k < 0) continue
            val row = floatArrayOf(1f, (cell / 3 - 1).toFloat(), (cell % 3 - 1).toFloat())
            for (a in 0..2) {
                for (b in 0..2) m[a][b] += row[a] * row[b]
                bx[a] += row[a] * pts[k].x; by[a] += row[a] * pts[k].y
            }
        }
        val sx = solve3(m, bx); val sy = solve3(m, by)
        return Board(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2], hits, err, hit.filter { it >= 0 }.toSet())
    }

    // Solves a 3×3 set of equations (Cramer's rule)
    private fun solve3(m: Array<FloatArray>, b: FloatArray): FloatArray {
        fun det(a: Array<FloatArray>) = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        val d = det(m)
        return FloatArray(3) { col -> det(Array(3) { r -> FloatArray(3) { c -> if (c == col) b[r] else m[r][c] } }) / d }
    }

    private fun scaled(b: Board, s: Int) = Board(b.cx * s, b.cy * s, b.ux * s, b.uy * s, b.vx * s, b.vy * s, b.hits, b.err, b.used)

    // Of the board's four steps (±u, ±v), the one going most to the right, and the one going most down
    private fun columnStep(b: Board): Pair<Float, Float> =
        listOf(b.ux to b.uy, -b.ux to -b.uy, b.vx to b.vy, -b.vx to -b.vy).maxBy { it.first }
    private fun rowStep(b: Board): Pair<Float, Float> {
        val col = columnStep(b)
        val usesU = abs(col.first - b.ux) < 1e-3f && abs(col.second - b.uy) < 1e-3f ||
            abs(col.first + b.ux) < 1e-3f && abs(col.second + b.uy) < 1e-3f
        return if (usesU) (if (b.vy >= 0) b.vx to b.vy else -b.vx to -b.vy) else (if (b.uy >= 0) b.ux to b.uy else -b.ux to -b.uy)
    }

    private fun boardsOf(left: Board, right: Board): MatchBoards {
        val l = columnStep(left); val r = columnStep(right)
        val lr = rowStep(left); val rr = rowStep(right)
        return MatchBoards(floatArrayOf(left.cx, right.cx), floatArrayOf(left.cy, right.cy),
            floatArrayOf(l.first, r.first), floatArrayOf(l.second, r.second),
            floatArrayOf(lr.first, rr.first), floatArrayOf(lr.second, rr.second),
            booleanArrayOf(true, true), booleanArrayOf(left.hits >= 8, right.hits >= 8))
    }

    // ---- Reading one tile ----

    private const val TILE_GRID = 12   // a tile is looked at as a 12×12 grid of points, about one floor tile wide

    // The purple behind a revealed symbol
    private fun tilePurple(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r in 40..100 && b >= 0.6f * r && b <= r && r - g >= 8
    }

    // What one tile shows. `allowed` = the symbols it could be.
    fun readMatchTile(p: LightBoxReader.PixelSource, boards: MatchBoards, t: Int, skip: Skip,
                      allowed: Collection<ToaPuzzles.Symbol>): MatchTile {
        val b = t / 9
        val tx = boards.tileX(t); val ty = boards.tileY(t)
        // Each point is symbol (dull yellow), tile (purple), or neither: something in front, like your character.
        // Only symbol and tile points are compared, so a symbol partly hidden can still be recognised.
        val yellow = BooleanArray(TILE_GRID * TILE_GRID)
        val seen = BooleanArray(TILE_GRID * TILE_GRID)
        var hidden = 0; var dull = 0; var pale = 0; var purple = 0; var known = 0
        for (i in 0 until TILE_GRID) for (j in 0 until TILE_GRID) {
            // a symbol fills about one floor tile: half the step between puzzle tiles
            val fj = ((j + 0.5f) / TILE_GRID - 0.5f) * 0.45f
            val fi = ((i + 0.5f) / TILE_GRID - 0.5f) * 0.45f
            val x = (tx + fj * boards.colX[b] + fi * boards.rowX[b]).toInt()
            val y = (ty + fj * boards.colY[b] + fi * boards.rowY[b]).toInt()
            if (x < 0 || y < 0 || x >= p.width || y >= p.height || skip.at(x, y)) continue
            known++
            val c = p.rgb(x, y)
            val k = i * TILE_GRID + j
            when {
                hiddenGrey(c) -> hidden++
                yellowish(c) -> {
                    val r = (c shr 16) and 0xFF
                    if ((c and 0xFF) >= 0.25f * r) pale++ else { dull++; yellow[k] = true; seen[k] = true }
                }
                tilePurple(c) -> { seen[k] = true; purple++ }
            }
        }
        val all = TILE_GRID * TILE_GRID
        if (known < all / 2) return MatchTile(TILE_UNKNOWN)
        // A real tile, glowing or showing a symbol, sits on its purple background. The minimap, orbs or
        // inventory can cover a tile with pale or yellow colours, but not with that purple.
        val onTile = purple >= all / 5
        if (onTile && pale >= all * 6 / 100) return MatchTile(TILE_MATCHED)
        if (onTile && dull >= all * 5 / 100) {
            val (symbol, sure) = recogniseSymbol(yellow, seen, allowed) ?: return MatchTile(TILE_UNKNOWN)
            return MatchTile(TILE_SYMBOL, symbol, sure)
        }
        if (hidden >= all * 3 / 10) return MatchTile(TILE_HIDDEN)
        return MatchTile(TILE_UNKNOWN)
    }

    // All 18 tiles, as Watch reads them from one picture. Tiles of a board not actually found in this picture are
    // left unread ("can't tell"), and a symbol is only taken from a board found clearly (8 or 9 tiles lined up): one
    // found from 7 can be a tile out of place. Glows are checked in pairs anyway (MatchMemory).
    fun readMatchTiles(p: LightBoxReader.PixelSource, boards: MatchBoards, skip: Skip,
                       allowed: Collection<ToaPuzzles.Symbol>): List<MatchTile> = (0 until 18).map { t ->
        if (!boards.seen[t / 9]) MatchTile(TILE_UNKNOWN) else {
            val r = readMatchTile(p, boards, t, skip, allowed)
            if (r.state == TILE_SYMBOL && !boards.clear[t / 9]) MatchTile(TILE_UNKNOWN) else r
        }
    }

    // ---- What Watch remembers from one picture to the next ----
    // Kept here (no Android parts) so the PC replay test follows exactly the same rules as the app.

    private const val MATCH_GLOW_MS = 800L     // a tile must glow this long to count as matched
    private const val LONE_GLOW_MS = 4_000L    // …or this long if its partner can't be seen
    // The symbols that can turn up in a solo raid (the other four pairs start matched)
    private val SOLO_SYMBOLS = listOf(ToaPuzzles.Symbol.DIAMOND, ToaPuzzles.Symbol.KNIVES, ToaPuzzles.Symbol.STAR,
        ToaPuzzles.Symbol.WIGGLE, ToaPuzzles.Symbol.FOOT)

    // The matching boards as far as they're known. `now` is in milliseconds: the phone's clock in the app, the
    // picture's time in a replay.
    class MatchMemory {
        val symbols = HashMap<Int, ToaPuzzles.Symbol>()   // what's been seen on each tile
        val done = HashSet<Int>()                          // matched tiles
        var active: Int? = null                            // the tile flipped last
        private val votes = HashMap<Int, IntArray>()       // per tile: how many pictures showed each symbol
        private val glowSince = HashMap<Int, Long>()       // per tile: when it started glowing (it must keep glowing to count)
        private val glowNearUi = HashSet<Int>()            // glowing tiles seen near the game's buttons (orbs, minimap, inventory)
        private var soloRaid: Boolean? = null              // four pairs already matched on each board when watching began
        // the last picture showed a symbol not settled yet: Watch looks again straight away, before you step off it
        var unsure = false
            private set

        // The symbols a tile could show: in a solo raid, only the five that start unmatched
        val allowed: List<ToaPuzzles.Symbol> get() = if (soloRaid == true) SOLO_SYMBOLS else ToaPuzzles.MATCHING_SYMBOLS

        // Watch is starting: glows have to be seen afresh, and solo or not is decided again
        fun startWatch() { glowSince.clear(); glowNearUi.clear(); soloRaid = null }

        // Forget everything
        fun clear() { symbols.clear(); done.clear(); votes.clear(); active = null; startWatch() }

        // Adds what one picture showed. `screenW` is the screen's width, to tell where the game's buttons are.
        // True if anything changed.
        fun merge(tiles: List<MatchTile>, boards: MatchBoards, screenW: Int, now: Long): Boolean {
            // decided from the first clear look: solo raids start with four pairs matched
            if (soloRaid == null) soloRaid = (0 until 18).count { tiles[it].state == TILE_MATCHED } >= 7
            // The game's buttons down the right of the screen (orbs, minimap, inventory) and down the left can look
            // like a glowing tile; a glow seen there needs a partner to count
            fun nearUi(t: Int) = boards.tileX(t) !in (screenW * 0.1f)..(screenW * 0.72f)
            var changed = false
            unsure = false
            for (t in 0 until 18) {
                val tile = tiles[t]
                when (tile.state) {
                    // A glowing tile is only counted as matched once it has glowed steadily for a while and has a
                    // partner on the other board (see settlePairs). A matched tile then stays matched: one seen through
                    // the see-through inventory can look grey.
                    TILE_MATCHED -> if (t !in done) {
                        glowSince.getOrPut(t) { now }
                        if (nearUi(t)) glowNearUi.add(t)
                    }
                    TILE_HIDDEN -> { glowSince.remove(t); glowNearUi.remove(t) }
                    TILE_SYMBOL -> {
                        glowSince.remove(t)
                        glowNearUi.remove(t)
                        // the tile you've just flipped (it shows its symbol while you stand on it)
                        if (active != t) { active = t; changed = true }
                        val sym = tile.symbol ?: run { unsure = true; continue }
                        val k = ToaPuzzles.MATCHING_SYMBOLS.indexOf(sym)
                        val v = votes.getOrPut(t) { IntArray(9) }
                        v[k]++
                        // the symbol most pictures agreed on, once at least two did (or one was very clear)
                        val best = v.indices.maxBy { v[it] }
                        if (v[best] < 2 && tile.sure < 0.8f) { unsure = true; continue }
                        val chosen = ToaPuzzles.MATCHING_SYMBOLS[best]
                        if (symbols[t] == chosen) continue
                        // each board has each symbol once: keep whichever tile it was seen on more
                        val board = if (t < 9) 0 until 9 else 9 until 18
                        val rival = board.firstOrNull { it != t && symbols[it] == chosen }
                        if (rival != null) {
                            if ((votes[rival]?.get(best) ?: 0) > v[best]) continue
                            symbols.remove(rival)
                        }
                        symbols[t] = chosen
                        changed = true
                    }
                }
            }
            if (settlePairs(now)) changed = true
            return changed
        }

        // Tiles become matched in pairs, one on each board, at the same moment. So a tile that has glowed steadily
        // is only marked matched together with a partner: the tile with the same symbol on the other board, if that's
        // known; otherwise another steadily glowing tile on the other board. A lone glow is something in front of the
        // tile (the orbs by the minimap can look like one), not a match. True if anything changed.
        private fun settlePairs(now: Long): Boolean {
            val steady = glowSince.filter { (t, since) -> t !in done && now - since >= MATCH_GLOW_MS }.keys.toMutableSet()
            fun otherBoard(t: Int) = if (t < 9) 9 until 18 else 0 until 9
            var changed = false
            fun pair(a: Int, b: Int) {
                done.add(a); done.add(b)
                steady.remove(a); steady.remove(b)
                glowSince.remove(a); glowSince.remove(b)
                changed = true
            }
            // a tile whose symbol is known: with that symbol's tile on the other board, or else with a glowing tile
            // there whose symbol was never seen (matched as soon as you stepped on it)
            for (t in steady.sorted()) {
                if (t !in steady) continue
                val sym = symbols[t] ?: continue
                val partner = otherBoard(t).firstOrNull { symbols[it] == sym }
                if (partner != null) {
                    if (partner in steady || partner in done) pair(t, partner)
                } else {
                    otherBoard(t).firstOrNull { it in steady && symbols[it] == null }?.let {
                        symbols[it] = sym
                        pair(t, it)
                    }
                }
            }
            // tiles whose symbols were never seen (like the pairs a solo raid starts with): pair them up across boards
            val left = steady.filter { it < 9 && symbols[it] == null }.sorted()
            val right = steady.filter { it >= 9 && symbols[it] == null }.sorted()
            for (i in 0 until minOf(left.size, right.size)) pair(left[i], right[i])
            // Its partner may be out of sight (behind the inventory, or off the screen): a tile that has glowed
            // steadily for longer, never near the game's buttons, counts by itself. (The pairs a raid starts with.)
            for (t in steady.toList()) {
                if (t in glowNearUi || symbols[t] != null) continue
                if (now - (glowSince[t] ?: now) < LONE_GLOW_MS) continue
                done.add(t); glowSince.remove(t); changed = true
            }
            return changed
        }

        // The tile you've just flipped and its match on the other board, if that's known and not matched yet
        fun activePair(): Pair<Int, Int>? {
            val a = active ?: return null
            val sym = symbols[a] ?: return null
            val other = if (a < 9) 9 until 18 else 0 until 9
            val b = other.firstOrNull { symbols[it] == sym } ?: return null
            if (a in done && b in done) return null
            return a to b
        }
    }

    // How alike a seen symbol is to a sample (0 to 1), counting only the points that could be seen. The sample
    // is tried at each quarter turn (the camera may face any way) and nudged up to two points each way (the
    // board's tile middles are only roughly right).
    private fun likeness(yellow: BooleanArray, seen: BooleanArray, sample: BooleanArray): Float {
        val n = TILE_GRID
        var seenYellow = 0
        for (k in yellow.indices) if (yellow[k]) seenYellow++
        var best = 0f
        for (turn in 0..3) for (dy in -2..2) for (dx in -2..2) {
            var both = 0; var sampleSeen = 0
            for (i in 0 until n) for (j in 0 until n) {
                if (!seen[i * n + j]) continue
                // the sample's point that lands here
                val si = i - dy; val sj = j - dx
                if (si !in 0 until n || sj !in 0 until n) continue
                // (two plain numbers, not a pair: a pair here would make an object for every point, every look)
                val ti: Int; val tj: Int
                when (turn) {
                    0 -> { ti = si; tj = sj }
                    1 -> { ti = sj; tj = n - 1 - si }
                    2 -> { ti = n - 1 - si; tj = n - 1 - sj }
                    else -> { ti = n - 1 - sj; tj = si }
                }
                if (sample[ti * n + tj]) { sampleSeen++; if (yellow[i * n + j]) both++ }
            }
            val score = 2f * both / (seenYellow + sampleSeen).coerceAtLeast(1)
            if (score > best) best = score
        }
        return best
    }

    // Which symbol it is, and how sure (0 to 1); null if it isn't clearly one of `allowed`
    private fun recogniseSymbol(yellow: BooleanArray, seen: BooleanArray, allowed: Collection<ToaPuzzles.Symbol>): Pair<ToaPuzzles.Symbol, Float>? {
        if (yellow.count { it } < 8) return null   // too little of it showing
        val best = HashMap<ToaPuzzles.Symbol, Float>()
        for ((symbol, sample) in symbolSamples) {
            if (symbol !in allowed) continue
            val l = likeness(yellow, seen, sample)
            if (l > (best[symbol] ?: -1f)) best[symbol] = l
        }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0f
        if (top.value < 0.6f || top.value - runnerUp < 0.05f) return null
        return top.key to top.value
    }

    // Each symbol as seen on the owner's phone (matching and addition rooms, several camera angles): which of
    // a tile's 12×12 points are symbol (1)
    private val symbolSamples: List<Pair<ToaPuzzles.Symbol, BooleanArray>> by lazy {
        SYMBOL_SAMPLES.map { line ->
            val (name, bits) = line.split(":")
            ToaPuzzles.Symbol.valueOf(name) to BooleanArray(TILE_GRID * TILE_GRID) { bits[it] == '1' }
        }
    }

    private val SYMBOL_SAMPLES = arrayOf(
        "DIAMOND:000000000000000000000000000000000000000001100000000011011000000100001100000100001100000011011000000001110000000000000000000000000000000000000000",   // match2_sym1
        "FOOT:000000000000000000000000000111100000000111100000000111100000000111100000000111100000001111100000011111100000000000000000000000000000000000000000",   // match2_sym3
        "KNIVES:000000000000000000000000000000000000000000000000000000100010000000100110000001100110000001100110000000100010000000100010000000000010000000000000",   // match2_sym5
        "WIGGLE:000000000000000000000000000000000000000000000000000000000000001001100100001111111100000010010000000000000000000000000000000000000000000000000000",   // match2_sym6
        "KNIVES:000000000000000000000000000000000000000000100010000001100110000001100110000011100110000011101110000001100010000000100010000000100010000000000000",   // match_04
        "FOOT:000000000000000001110000000011110000000011110000000011110000000011110000000011110000001111110000000000000000000000000000000000000000000000000000",   // match_05
        "DIAMOND:000000000000000000000000000001100000000110110000001100001000001100011000000011110000000001000000000000000000000000000000000000000000000000000000",   // match_06
        "STAR:000000000000000000000000000011000000000011000000001111100000011111111000000111100000000111000000000010000000000010000000000000000000000000000000",   // match_09
        "FOOT:000000000000000000000000000000000000000000111000000000111000000000111000000000111000000001111000000000111000000000000000000000000000000000000000",   // add_6 00
        "LINE:000000000000000000000000000000000000000000110000000000110000000000110000000000110000000001100000000000110000000000110000000000000000000000000000",   // add_6 01
        "HAND:000000000000000000000000000000000000000000000000000011110000000011110000001011110000001011110000000111110000000111110000000001100000000001100000",   // add_6 02
        "DIAMOND:000000000000000000000000000000000000000000000000000000000000000011100000000110011000001100001100000110011000000001100000000000000000000000000000",   // add_6 03
        "KNIVES:000000000000000000000000000001000100000011001100000011011000000011011100000011001100000001001000000001001000000000000000000000000000000000000000",   // add_6 10
        "LINE:000000000000000000000000000001100000000001110000000001100000000001100000000001100000000001100000000001100000000000100000000000000000000000000000",   // add_6 11
        "WIGGLE:000000000000000000000000000000000000000000000000000000000000001101100110000110011000000000000000000000000000000000000000000000000000000000000000",   // add_6 12
        "KNIVES:000000000000000000000000000000000000000010001000000110001000000110011000000110011000000110011000000010001000000010001000000010001000000000000000",   // add_6 13
        "DIAMOND:000000000000000000000000000000000000000000000000000000100000000001110000000011011000001100000100000010001100000001110000000000100000000000000000",   // add_6 14
        "CROOK:000000000000000001111000000001001000000001001000000001001000000001000000000001000000000001000000000000000000000000000000000000000000000000000000",   // add_6 20
        "DIAMOND:000000000000000000000000000000100000000011110000000110001000000100001100000010011000000001100000000000000000000000000000000000000000000000000000",   // add_6 21
        "CROOK:000000000000000000000000000000000000000011111000000011001000000011001000000011001000000011000000000011000000000011000000000011000000000000000000",   // add_6 23
        "HAND:000000000000000000000000000000000000000000000000000001111000000001111000000101111000000101111000000111111000000011111000000001111000000000111000",   // add_6 24
        "BIRD:000000000000000010000000000111000000000111000000000111100000000111110000000011111000000001111100000000010000000000010000000000000000000000000000",   // add_6 31
        "BIRD:000000000000000000000000000010000000000111000000000011000000000111100000000011110000000011111000000000111100000000010000000000011000000000000000",   // add_6 32
        "KNIVES:000000000000000000000000000000000000000010000000000010001000000110001100000110011100000110011100000010001100000011000100000010000100000000000100",   // add_6 33
        "FOOT:000000000000000000000000000000000000000000000000000000111000000000111000000000111000000000111100000000111100000001111100000011111100000000000000",   // add_6 34
        "FOOT:000000000000000011110000000011110000000011110000000011110000000011110000000111110000000000010000000000000000000000000000000000000000000000000000",   // add_6 40
        "CROOK:000000000000000000000000000011110000000010010000000010010000000010010000000010000000000010000000000010000000000010000000000000000000000000000000",   // add_6 41
        "WIGGLE:000000000000000000000000000000000000000000000000000000000000011000100010000111110110000010011000000000000000000000000000000000000000000000000000",   // add_6 42
        "BIRD:000000000000000000000000000000000000000000000000000011000000000111100000000011100000000011110000000011111000000001111100000000111110000000001000",   // add_6 43
        "LINE:000000000000000000000000000000000000000000000000000000000000000000011000000000011000000000011000000000011000000000011000000000011000000000011000",   // add_6 44
        "STAR:000000000000000000000000000000100000000001110000000111110000000111111100000001111000000001100000000000000000000000000000000000000000000000000000",   // star2
    )
}
