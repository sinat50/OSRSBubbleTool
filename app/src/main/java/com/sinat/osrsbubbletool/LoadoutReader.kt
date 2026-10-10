package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.LightBoxReader.PixelSource
import com.sinat.osrsbubbletool.PanelFinder.Box
import kotlin.math.abs
import kotlin.math.roundToInt

// Gear Loadouts' screen reading: which spellbook you're on (from the spellbook tab button's picture) and
// what's in an open rune pouch (each rune and its amount). No Android parts, so it's tested on a computer
// with screenshots from the owner's phone.
//
// Both work by laying the game's own pictures (the item pictures and spellbook pictures the app comes with)
// over the screen, enlarged to the size the game draws them, and seeing which fits best.
object LoadoutReader {

    // Set by tests to see the scores behind each answer
    var debugLog: ((String) -> Unit)? = null

    // A picture to compare against: its pixels as 0xAARRGGBB
    class Picture(val width: Int, val height: Int, val argb: IntArray)

    // The runes a rune pouch can hold (item IDs): the 13 basic runes, the 6 combination runes, astral,
    // wrath, sunfire and aether
    val POUCH_RUNES = (554..566).toList() + (4694..4699).toList() + listOf(9075, 21880, 28929, 30843)

    // ---------------- Pictures enlarged to screen size ----------------

    // A picture enlarged s times, smoothed between its pixels as the phone smooths the game's when it
    // enlarges them. solid: the pixels that are fully the picture's (not see-through, not its blurred edge).
    private class Scaled(val w: Int, val h: Int, val rgb: IntArray, val solid: BooleanArray)

    private fun scale(p: Picture, s: Float): Scaled {
        val w = (p.width * s).roundToInt().coerceAtLeast(1)
        val h = (p.height * s).roundToInt().coerceAtLeast(1)
        val rgb = IntArray(w * h)
        val solid = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // the spot in the small picture this pixel's middle lands on, and its four nearest pixels
            val fx = ((x + 0.5f) / s - 0.5f).coerceIn(0f, p.width - 1f)
            val fy = ((y + 0.5f) / s - 0.5f).coerceIn(0f, p.height - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = minOf(x0 + 1, p.width - 1); val y1 = minOf(y0 + 1, p.height - 1)
            val ax = fx - x0; val ay = fy - y0
            var a = 0f; var r = 0f; var g = 0f; var b = 0f
            for (k in 0 until 4) {
                val c = p.argb[(if (k < 2) y0 else y1) * p.width + (if (k % 2 == 0) x0 else x1)]
                val weight = (if (k % 2 == 0) 1 - ax else ax) * (if (k < 2) 1 - ay else ay)
                val alpha = ((c ushr 24) and 0xFF) / 255f * weight
                a += alpha
                r += ((c shr 16) and 0xFF) * alpha; g += ((c shr 8) and 0xFF) * alpha; b += (c and 0xFF) * alpha
            }
            if (a > 0f) rgb[y * w + x] = ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            solid[y * w + x] = a > 0.8f
        }
        return Scaled(w, h, rgb, solid)
    }

    // Which screen pixels to compare: i is the pixel in the picture, (x, y) on the screen. A fun interface,
    // not a Kotlin function type, which would box every call's numbers (see ToaReader.Skip).
    fun interface Use { fun ok(i: Int, x: Int, y: Int): Boolean }

    private val ALL = Use { _, _, _ -> true }

    private fun diff(c1: Int, c2: Int) =
        abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF)) + abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF)) +
            abs((c1 and 0xFF) - (c2 and 0xFF))

    // How different the screen is from the picture placed with its top-left at (left, top): the average
    // difference per colour (red, green, blue: 0-255) over the pixels `use` allows (null if fewer than
    // minShare of the picture's pixels can be compared)
    private fun fit(p: PixelSource, pic: Scaled, left: Int, top: Int, use: Use, minShare: Float = 0.5f): Float? {
        if (left < 0 || top < 0 || left + pic.w > p.width || top + pic.h > p.height) return null
        var sum = 0L; var count = 0; var solid = 0
        for (y in 0 until pic.h) for (x in 0 until pic.w) {
            val i = y * pic.w + x
            if (!pic.solid[i]) continue
            solid++
            val screen = p.rgb(left + x, top + y)
            if (!use.ok(i, left + x, top + y)) continue
            sum += diff(screen, pic.rgb[i]); count++
        }
        return if (count == 0 || count < solid * minShare) null else sum.toFloat() / (3 * count)
    }

    // ---------------- Spellbook ----------------

    // Which spellbook the tab button shows (0-3, as Loadout.SPELLBOOKS), or null if none fits well.
    // books: the four spellbook pictures, in that order.
    fun spellbook(p: PixelSource, panel: Box, books: List<Picture>): Int? {
        val tab = PanelFinder.spellbookTab(panel)
        // How big the game is drawn: the side panel is about 204 game pixels wide (2.13 x on the owner's phone)
        val guess = panel.width / PANEL_GAME_WIDTH
        val scores = books.map { book ->
            var best = Float.MAX_VALUE
            for (k in -2..2) {
                val pic = scale(book, guess * (1 + k * 0.02f))
                for (y in tab.top..tab.bottom - pic.h step 2) for (x in tab.left..tab.right - pic.w step 2) {
                    fit(p, pic, x, y, ALL)?.let { if (it < best) best = it }
                }
            }
            best
        }
        debugLog?.invoke("panel $panel tab $tab spellbook scores ${scores.map { "%.1f".format(it) }}")
        val order = scores.indices.sortedBy { scores[it] }
        val best = scores[order[0]]
        val second = scores[order[1]]
        // On the owner's phone the right book scores about 17-25 and the next best 39 or more
        return if (best < SPELLBOOK_MAX_SCORE && second > best * 1.25f) order[0] else null
    }

    private const val SPELLBOOK_MAX_SCORE = 32f
    private const val PANEL_GAME_WIDTH = 204f

    // ---------------- Rune pouch ----------------

    // What an open rune pouch holds, left to right, or null if it can't be read. window: the pouch's window
    // (PanelFinder.runePouchWindow). runes: each pouch rune's ID and item picture (POUCH_RUNES).
    fun runePouch(p: PixelSource, window: Box, runes: List<Pair<Int, Picture>>): List<Loadout.Stack>? {
        if (runes.isEmpty()) return null
        val box = PanelFinder.runePouchBox(window)
        // The amounts are written over the top of each rune in yellow with a black shadow: left out when comparing
        val keep = Use { _, x, y -> !isAmount(p.rgb(x, y)) }

        // How big the game is drawn: its pouch window is about 358 game pixels wide (2.1 x on the owner's phone).
        // The stones are found at that size, then the size is fine-tuned around them.
        val guess = window.width / POUCH_WINDOW_GAME_WIDTH
        var stones = findStones(p, box, sharedStone(runes.map { scale(it.second, guess) }), keep)
        if (stones.isEmpty()) return null
        var bestScale = guess
        var bestTotal = Float.MAX_VALUE
        val found = stones
        for (k in -2..2) {
            val s = guess * (1 + k * 0.015f)
            val stone = sharedStone(runes.map { scale(it.second, s) })
            val moved = found.map { nearBest(p, stone, it, keep) }
            val total = moved.sumOf { it.score.toDouble() }.toFloat()
            if (total < bestTotal) { bestTotal = total; bestScale = s; stones = moved }
        }
        debugLog?.invoke("scale %.3f (guessed %.3f): stones ${stones.map { "${it.x},${it.y} %.1f".format(it.score) }}".format(bestScale, guess))

        val scaled = runes.map { (id, pic) -> id to scale(pic, bestScale) }
        val stone = sharedStone(scaled.map { it.second })
        return stones.map { spot ->
            // Compare where the runes differ from each other: each rune's symbol, and whatever on the screen
            // isn't plain stone (so a rune with a small symbol can't win just by matching the stone)
            val scores = scaled.map { (id, pic) ->
                val symbol = Use { i, x, y ->
                    val c = p.rgb(x, y)
                    !isAmount(c) && (diff(pic.rgb[i], stone.rgb[i]) > SYMBOL_DIFF || diff(c, stone.rgb[i]) > SYMBOL_DIFF)
                }
                var best = Float.MAX_VALUE
                for (dy in -2..2) for (dx in -2..2) {
                    fit(p, pic, spot.x + dx, spot.y + dy, symbol, minShare = 0f)?.let { if (it < best) best = it }
                }
                id to best
            }.sortedBy { it.second }
            debugLog?.invoke("  rune at ${spot.x}: ${scores.take(3).map { "${it.first} %.1f".format(it.second) }}")
            Loadout.Stack(scores[0].first, readAmount(p, spot, stone, bestScale) ?: 1)
        }
    }

    private const val POUCH_WINDOW_GAME_WIDTH = 358f
    private const val SYMBOL_DIFF = 90

    private class Spot(val x: Int, val y: Int, val score: Float)

    // Yellow digits and their black shadow
    private fun isAmount(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return (r > 150 && g > 150 && b < 110) || r + g + b < 70
    }

    // What every rune shares: the grey stone. Each pixel is the middle value of all the runes' pixels there.
    private fun sharedStone(pics: List<Scaled>): Scaled {
        val first = pics[0]
        val n = first.w * first.h
        val rgb = IntArray(n)
        val solid = BooleanArray(n)
        val values = IntArray(pics.size)
        for (i in 0 until n) {
            var c = 0
            for (shift in intArrayOf(16, 8, 0)) {
                for (k in pics.indices) values[k] = (pics[k].rgb.getOrElse(i) { 0 } shr shift) and 0xFF
                values.sort()
                c = c or (values[values.size / 2] shl shift)
            }
            rgb[i] = c
            solid[i] = pics.count { it.solid.getOrElse(i) { false } } > pics.size / 2
        }
        return Scaled(first.w, first.h, rgb, solid)
    }

    // The stones in the pouch box: the best fits of the shared stone, left to right, at least a stone apart
    private fun findStones(p: PixelSource, box: Box, stone: Scaled, keep: Use): List<Spot> {
        val all = ArrayList<Spot>()
        for (y in box.top..box.bottom - stone.h step 3) for (x in box.left..box.right - stone.w step 3) {
            fit(p, stone, x, y, keep)?.let { all.add(Spot(x, y, it)) }
        }
        all.sortBy { it.score }
        val picked = ArrayList<Spot>()
        for (spot in all) {
            if (spot.score > STONE_MAX_SCORE) break
            if (picked.all { abs(it.x - spot.x) > stone.w }) picked.add(spot)
            if (picked.size == 4) break   // a divine rune pouch holds 4
        }
        return picked.sortedBy { it.x }
    }

    // The best fit of the stone within a few pixels of a spot
    private fun nearBest(p: PixelSource, stone: Scaled, spot: Spot, keep: Use): Spot {
        var best = Spot(spot.x, spot.y, Float.MAX_VALUE)
        for (dy in -3..3) for (dx in -3..3) {
            fit(p, stone, spot.x + dx, spot.y + dy, keep)?.let { if (it < best.score) best = Spot(spot.x + dx, spot.y + dy, it) }
        }
        return best
    }

    // On the owner's phone the stones score 27-45; the pouch's brown background, far more
    private const val STONE_MAX_SCORE = 60f

    // ---------------- Amounts ----------------

    // The amount written over a rune: yellow digits in the game's small font, 8 game pixels tall, starting at
    // the rune's top-left. Null if there's none or it can't be read.
    private fun readAmount(p: PixelSource, spot: Spot, stone: Scaled, s: Float): Int? {
        val left = (spot.x - 4 * s).toInt().coerceAtLeast(0)
        val right = (spot.x + stone.w + 14 * s).toInt().coerceAtMost(p.width)
        val top = (spot.y - 6 * s).toInt().coerceAtLeast(0)
        val bottom = (spot.y + 10 * s).toInt().coerceAtMost(p.height)
        if (right <= left || bottom <= top) return null
        val w = right - left
        val h = bottom - top
        val yellow = FloatArray(w * h) { yellowness(p.rgb(left + it % w, top + it / w)) }
        val text = Digits.read(yellow, w, h) ?: return null
        return text.toIntOrNull()
    }

    // How yellow a pixel is, 0 to 1 (the digits' edges are blurred where the phone enlarged them)
    private fun yellowness(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return ((minOf(r, g) - b - 60) / 120f).coerceIn(0f, 1f)
    }
}

// Reads the yellow numbers the game writes on stacks of items, in its small font: every digit 8 game pixels
// tall. The picture is turned back into game pixels (however much the phone enlarged it) and each digit is
// compared with the font's digits, drawn below as the game draws them (from screenshots of the game).
object Digits {

    private val FONT = mapOf(
        '0' to listOf(".###.", "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."),
        '1' to listOf(".#.", "##.", ".#.", ".#.", ".#.", ".#.", ".#.", "###"),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#....", "#####"),
        '3' to listOf(".##.", "#..#", "...#", ".##.", "...#", "...#", "#..#", ".##."),
        '4' to listOf("#...", "#...", "#...", "#.#.", "#.#.", "####", "..#.", "..#."),
        '5' to listOf("####", "#...", "#...", "###.", "...#", "...#", "...#", ".##."),
        '6' to listOf("..##.", ".#..#", "#....", "#.##.", "##..#", "#...#", "#...#", ".###."),
        '7' to listOf("####", "...#", "..#.", "..#.", ".#..", ".#..", "#...", "#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".#..#", "..###", "....#", "....#", "....#"),
    )
    private const val ROWS = 8

    // The number in a patch of the screen, given how yellow each pixel is (0 to 1), or null if there's none
    fun read(yellow: FloatArray, w: Int, h: Int): String? {
        // The rows the digits are on: the tallest run of rows with yellow in them
        var bestTop = -1; var bestRows = 0; var runTop = -1
        for (y in 0..h) {
            val any = y < h && (0 until w).any { yellow[y * w + it] > 0.5f }
            if (any && runTop < 0) runTop = y
            if (!any && runTop >= 0) {
                if (y - runTop > bestRows) { bestRows = y - runTop; bestTop = runTop }
                runTop = -1
            }
        }
        if (bestRows < ROWS) return null   // too small to be digits
        val pixel = bestRows / ROWS.toFloat()   // one game pixel, in screen pixels

        // Each digit: a run of columns with yellow in them
        val text = StringBuilder()
        var start = -1
        for (x in 0..w) {
            val any = x < w && (bestTop until bestTop + bestRows).any { yellow[it * w + x] > 0.5f }
            if (any && start < 0) start = x
            if (!any && start >= 0) {
                text.append(digit(yellow, w, start, x, bestTop, bestRows, pixel) ?: return null)
                start = -1
            }
        }
        return text.toString().ifEmpty { null }
    }

    // The digit in columns from..to (to just after it), or null if it isn't one
    private fun digit(yellow: FloatArray, w: Int, from: Int, to: Int, top: Int, rows: Int, pixel: Float): Char? {
        val wide = (to - from) / pixel
        var best: Char? = null
        var bestWrong = Int.MAX_VALUE
        for ((c, shape) in FONT) {
            val cols = shape[0].length
            if (abs(wide - cols) > 1.2f) continue
            // this patch cut into game pixels the size of the font's digit, centred on what was found (its blurred
            // edges make it look a little wider): each game pixel yellow if most of it is
            val start = from + ((to - from) - cols * pixel) / 2
            var wrong = 0
            for (r in 0 until ROWS) for (k in 0 until cols) {
                val x0 = (start + k * pixel).roundToInt().coerceIn(from, to - 1)
                val x1 = (start + (k + 1) * pixel).roundToInt().coerceIn(x0 + 1, to)
                val y0 = top + (r * rows / ROWS.toFloat()).toInt()
                val y1 = maxOf(y0 + 1, top + ((r + 1) * rows / ROWS.toFloat()).toInt())
                var sum = 0f
                for (y in y0 until y1) for (x in x0 until x1) sum += yellow[y * w + x]
                val on = sum / ((y1 - y0) * (x1 - x0)) > 0.45f
                if (on != (shape[r][k] == '#')) wrong++
            }
            if (wrong < bestWrong) { bestWrong = wrong; best = c }
        }
        // a few game pixels can be off where the edges blur; more than that isn't a digit
        LoadoutReader.debugLog?.invoke("  digit ${best ?: '?'}, $bestWrong game pixels off, ${"%.1f".format(wide)} wide")
        return if (bestWrong <= MAX_WRONG) best else null
    }

    private const val MAX_WRONG = 7
}
