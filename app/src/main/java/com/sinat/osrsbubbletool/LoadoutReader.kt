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
        // No runes: an empty pouch shows an X in every slot (an empty list), anything else isn't read
        if (stones.isEmpty()) return if (emptySlots(p, box, guess) >= 3) emptyList() else null
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
        val read = stones.map { spot ->
            // the amount is written across the top of the slot: its digits (and around them) only looked for there
            val digits = nearDigits(p, spot.x - 2, spot.y - 2, stone.w + 4, stone.h + 4, spot.y + (AMOUNT_ROWS * bestScale).toInt())
            // Compare where the runes differ from each other: each rune's symbol, and whatever on the screen
            // isn't plain stone (so a rune with a small symbol can't win just by matching the stone)
            val scores = scaled.map { (id, pic) ->
                val symbol = Use { i, x, y ->
                    val c = p.rgb(x, y)
                    !digits.near(x, y) && (diff(pic.rgb[i], stone.rgb[i]) > SYMBOL_DIFF || diff(c, stone.rgb[i]) > SYMBOL_DIFF)
                }
                var best = Float.MAX_VALUE
                for (dy in -2..2) for (dx in -2..2) {
                    fit(p, pic, spot.x + dx, spot.y + dy, symbol, minShare = 0f)?.let { if (it < best) best = it }
                }
                id to best
            }.sortedBy { it.second }
            debugLog?.invoke("  rune at ${spot.x}: ${scores.take(3).map { "${it.first} %.1f".format(it.second) }}")
            readAmount(p, spot, stone, bestScale)?.let { Loadout.Stack(scores[0].first, it) }
        }
        // A real pouch writes an amount on every rune and never holds the same rune twice (the game stacks them). A
        // window caught while it's still being drawn can fail both (once read as a smoke rune in every slot): it's
        // not trusted, and the next look tries again.
        val stacks = read.filterNotNull()
        if (stacks.size < read.size || stacks.map { it.id }.toSet().size < stacks.size) {
            debugLog?.invoke("pouch reading not trusted: $read")
            return null
        }
        return stacks
    }

    // How many empty slots (the game's grey X) are in the pouch's row: X-grey pixels gathered into columns side by side,
    // each group as wide as an X (about 24 game pixels) and well filled. s: screen pixels per game pixel.
    private fun emptySlots(p: PixelSource, box: Box, s: Float): Int {
        val left = box.left.coerceAtLeast(0); val right = box.right.coerceAtMost(p.width)
        val top = box.top.coerceAtLeast(0); val bottom = box.bottom.coerceAtMost(p.height)
        if (right <= left || bottom <= top) return 0
        val perColumn = IntArray(right - left)
        for (x in left until right) for (y in top until bottom) if (isEmptyMark(p.rgb(x, y))) perColumn[x - left]++
        var count = 0
        var x = 0
        val gap = (3 * s).toInt().coerceAtLeast(2)
        while (x < perColumn.size) {
            if (perColumn[x] == 0) { x++; continue }
            // one group: columns with X-grey, allowing small gaps
            var end = x; var total = 0; var blank = 0
            while (end < perColumn.size && blank <= gap) {
                if (perColumn[end] == 0) blank++ else { blank = 0; total += perColumn[end] }
                end++
            }
            val width = (end - blank - x) / s
            debugLog?.invoke("empty slot? width %.1f game px, %.0f game px of grey".format(width, total / (s * s)))
            if (width in EMPTY_MARK_WIDTH && total / (s * s) >= EMPTY_MARK_FILL) count++
            x = end
        }
        return count
    }

    // The grey of an empty slot's X (#655B55 on the owner's phone, a little redder than green, than blue)
    private fun isEmptyMark(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return abs(r - 0x65) <= 14 && abs(g - 0x5B) <= 14 && abs(b - 0x55) <= 14 && r >= g && g >= b
    }

    private val EMPTY_MARK_WIDTH = 13f..25f   // the owner's Xs: 18.5-19 wide, 163-167 of grey
    private const val EMPTY_MARK_FILL = 90f
    // The amount's digits and the pixels around them (their blurred edges and shadow aren't plain yellow or black,
    // but differ from the stone as much): left out when comparing runes. Worked out once per rune, over an area; only
    // digit-coloured pixels above digitsEnd count (lower down, yellow or black is the rune's own: a cosmic rune's
    // yellow symbol, a death rune's dark one, the stone's outline).
    private class DigitArea(val left: Int, val top: Int, val w: Int, val h: Int, val mask: BooleanArray) {
        fun near(x: Int, y: Int): Boolean {
            val i = x - left; val j = y - top
            return i in 0 until w && j in 0 until h && mask[j * w + i]
        }
    }

    private fun nearDigits(p: PixelSource, left: Int, top: Int, w: Int, h: Int, digitsEnd: Int): DigitArea {
        val areaLeft = left.coerceAtLeast(0); val areaTop = top.coerceAtLeast(0)
        val width = ((left + w).coerceAtMost(p.width) - areaLeft).coerceAtLeast(0)
        val height = ((top + h).coerceAtMost(p.height) - areaTop).coerceAtLeast(0)
        val mask = BooleanArray(width * height)
        for (y in 0 until minOf(height, digitsEnd - areaTop)) for (x in 0 until width) {
            if (!isAmount(p.rgb(areaLeft + x, areaTop + y))) continue
            for (dy in -DIGIT_MARGIN..DIGIT_MARGIN) for (dx in -DIGIT_MARGIN..DIGIT_MARGIN) {
                val near = x + dx; val nearY = y + dy
                if (near in 0 until width && nearY in 0 until height) mask[nearY * width + near] = true
            }
        }
        return DigitArea(areaLeft, areaTop, width, height, mask)
    }

    private const val DIGIT_MARGIN = 2
    // How far down from the top of a rune's stone the amount's digits reach, in game pixels (the strip readAmount reads)
    private const val AMOUNT_ROWS = 10f
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

    // The amount written on a stack in an area (the top left of an inventory space or an equipment slot), or null.
    // The game writes it in yellow up to 99,999, in white with a K from 100K ("134K") and in green with an M from
    // 10M ("24M"): the colour says which, so only the digits need reading (the K or M, and anything touching it,
    // ends the number). A K or M amount is as the game shows it, rounded down: "134K" is 134,000.
    fun amountIn(p: PixelSource, area: Box): Int? {
        val left = area.left.coerceAtLeast(0)
        val top = area.top.coerceAtLeast(0)
        val w = area.right.coerceAtMost(p.width) - left
        val h = area.bottom.coerceAtMost(p.height) - top
        if (w <= 0 || h <= 0) return null
        // which colour the text is: the one with the most clear pixels
        var yellow = 0; var white = 0; var green = 0
        for (i in 0 until w * h) {
            val c = p.rgb(left + i % w, top + i / w)
            if (yellowness(c) > 0.9f) yellow++
            if (whiteness(c) > 0.9f) white++
            if (greenness(c) > 0.9f) green++
        }
        val most = maxOf(yellow, white, green)
        if (most == 0) return null
        val (textness, times) = when (most) {
            yellow -> ::yellowness to 1L
            green -> ::greenness to 1_000_000L
            else -> ::whiteness to 1_000L
        }
        val mask = FloatArray(w * h) { textness(p.rgb(left + it % w, top + it / w)) }
        val text = Digits.read(mask, w, h, stopAtOther = times > 1) ?: return null
        // the game writes a K amount with 3 or 4 digits (100K to 9999K), an M amount with 2 or more (10M and up): any
        // other count means a digit wasn't read, and then the amount isn't known rather than wrong ("134K" once came
        // out as 1,000)
        if ((times == 1_000L && text.length !in 3..4) || (times == 1_000_000L && text.length < 2)) return null
        val digits = text.toLongOrNull() ?: return null
        return (digits * times).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    // The amount written over a rune: yellow digits in the game's small font, 8 game pixels tall, starting at
    // the rune's top-left. Null if there's none or it can't be read.
    private fun readAmount(p: PixelSource, spot: Spot, stone: Scaled, s: Float): Int? {
        val left = (spot.x - 4 * s).toInt().coerceAtLeast(0)
        val right = (spot.x + stone.w + 14 * s).toInt().coerceAtMost(p.width)
        val top = (spot.y - 6 * s).toInt().coerceAtLeast(0)
        val bottom = (spot.y + AMOUNT_ROWS * s).toInt().coerceAtMost(p.height)
        if (right <= left || bottom <= top) return null
        return amountIn(p, Box(left, top, right, bottom))
    }

    // How yellow, white or green a pixel is, 0 to 1 (the digits' edges are blurred where the phone enlarged them)
    private fun yellowness(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return ((minOf(r, g) - b - 60) / 120f).coerceIn(0f, 1f)
    }

    private fun whiteness(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return ((minOf(r, g, b) - 130) / 90f).coerceIn(0f, 1f) * (if (maxOf(r, g, b) - minOf(r, g, b) < 50) 1f else 0f)
    }

    // The game's green text is (0, 255, 128): about half as much blue as green, and no red. Blurred into a light
    // background it stays that way (38, 182, 114), while grass behind the panel has hardly any blue.
    private fun greenness(c: Int): Float {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        if (r * 100 > g * 45 || b * 100 < g * 30 || b * 100 > g * 75) return 0f
        return ((g - 130) / 60f).coerceIn(0f, 1f)
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
    // Where each digit's weight is centred across, in game pixels from its left edge
    private val FONT_MIDDLE = FONT.mapValues { (_, shape) ->
        var n = 0; var sum = 0f
        for (row in shape) for ((k, ch) in row.withIndex()) if (ch == '#') { n++; sum += k + 0.5f }
        sum / n
    }

    // The number in a patch of the screen, given how much each pixel is the text's colour (0 to 1), or null if
    // there's none. stopAtOther: a shape that isn't a digit ends the number (the K or M after it) rather than
    // meaning it can't be read.
    fun read(yellow: FloatArray, w: Int, h: Int, stopAtOther: Boolean = false): String? {
        // The rows the digits are on: the rows the first digit (the leftmost run of text columns) covers. Measured
        // on it alone, as an item picture touching the end of the number can have the text's colour too.
        fun inColumn(x: Int) = (0 until h).any { yellow[it * w + x] > 0.5f }
        val first = (0 until w).firstOrNull { inColumn(it) } ?: return null
        var last = first
        while (last + 1 < w && inColumn(last + 1)) last++
        // The tallest run of rows with text in the first digit's columns. A thin stroke can fade for a row where it's
        // blurred into the background: if the digit then looks too short for its width (digits are much taller than
        // wide), runs one empty row apart are joined.
        fun tallest(bridge: Boolean): Pair<Int, Int> {
            var top = -1; var rows = 0; var runTop = -1; var lastRow = -1
            for (y in 0..h) {
                val any = y < h && (first..last).any { yellow[y * w + it] > 0.5f }
                if (any) {
                    if (runTop < 0) runTop = y
                    lastRow = y
                } else if (runTop >= 0 && (!bridge || y - lastRow > 1)) {
                    if (lastRow + 1 - runTop > rows) { rows = lastRow + 1 - runTop; top = runTop }
                    runTop = -1
                }
            }
            return top to rows
        }
        var (bestTop, bestRows) = tallest(false)
        if (bestRows < (last - first + 1) * 1.4f) tallest(true).let { bestTop = it.first; bestRows = it.second }
        if (bestRows < ROWS) return null   // too small to be digits
        val pixel = bestRows / ROWS.toFloat()   // one game pixel, in screen pixels

        // Each digit: a run of columns with yellow in them
        val runs = ArrayList<IntArray>()
        var start = -1
        for (x in 0..w) {
            val any = x < w && (bestTop until bestTop + bestRows).any { yellow[it * w + x] > 0.5f }
            if (any && start < 0) start = x
            if (!any && start >= 0) { runs.add(intArrayOf(start, x)); start = -1 }
        }
        val text = StringBuilder()
        for ((i, run) in runs.withIndex()) {
            val d = digits(yellow, w, run[0], run[1], bestTop, bestRows, pixel)
            if (d != null) { text.append(d); continue }
            // the K or M at the end: it can come apart in up to three pieces, or touch the item behind it (the caller
            // checks the number of digits makes sense for a K or M amount)
            if (stopAtOther && text.isNotEmpty() && runs.size - i - 1 <= 2) break
            return null
        }
        return text.toString().ifEmpty { null }
    }

    // The digits in columns from..to: one digit, or, if it's too wide for one, two that touch (where the phone's blur
    // filled the gap between them): split at the column with the least text, each side read again
    private fun digits(yellow: FloatArray, w: Int, from: Int, to: Int, top: Int, rows: Int, pixel: Float): String? {
        digit(yellow, w, from, to, top, rows, pixel)?.let { return it.toString() }
        if ((to - from) / pixel < MIN_SPLIT_WIDTH) return null
        var cut = -1; var least = Float.MAX_VALUE
        for (x in from + (2 * pixel).toInt() until to - (2 * pixel).toInt()) {
            var sum = 0f
            for (y in top until top + rows) sum += yellow[y * w + x]
            if (sum < least) { least = sum; cut = x }
        }
        if (cut < 0) return null
        val left = digits(yellow, w, from, cut, top, rows, pixel) ?: return null
        val right = digits(yellow, w, cut + 1, to, top, rows, pixel) ?: return null
        return left + right
    }

    private const val MIN_SPLIT_WIDTH = 6.5f   // game pixels: no digit is this wide

    // The digit in columns from..to (to just after it), or null if it isn't one
    private fun digit(yellow: FloatArray, w: Int, from: Int, to: Int, top: Int, rows: Int, pixel: Float): Char? {
        // first the cut centred on what was found; only if no digit fits that, nudged cuts, more strictly
        best(yellow, w, from, to, top, rows, pixel, nudges = false)?.let { (c, wrong, cells) ->
            if (wrong <= (cells * MAX_WRONG_SHARE).toInt()) return c
        }
        best(yellow, w, from, to, top, rows, pixel, nudges = true)?.let { (c, wrong, cells) ->
            if (wrong <= (cells * NUDGED_WRONG_SHARE).toInt()) return c
        }
        return null
    }

    // The digit this patch fits best: the digit, how many game pixels are off, and how many it has
    private fun best(yellow: FloatArray, w: Int, from: Int, to: Int, top: Int, rows: Int, pixel: Float, nudges: Boolean): Triple<Char, Int, Int>? {
        val h = yellow.size / w
        val wide = (to - from) / pixel
        var best: Char? = null
        var bestWrong = Int.MAX_VALUE
        var bestCells = 0
        // where the yellow's weight is centred, across (a blurred edge can fall either side of "yellow", so the run's
        // ends can be half a game pixel off; its weight isn't: a 6 cut half a pixel off once read as a 3)
        var weight = 0f; var moment = 0f
        for (y in top until minOf(top + rows, h)) for (x in (from - 1).coerceAtLeast(0) until minOf(to + 1, w)) {
            val v = yellow[y * w + x]
            weight += v; moment += v * (x + 0.5f)
        }
        val middle = if (weight > 0f) moment / weight else (from + to) / 2f
        for ((c, shape) in FONT) {
            val cols = shape[0].length
            if (abs(wide - cols) > 1.6f) continue
            // this patch cut into game pixels the size of the font's digit, placed so the digit's own weight is centred
            // where the screen's is: each game pixel yellow if most of it is. On a small screen a game pixel is under
            // 2 screen pixels and the cut can fall a little off: nudged, it's tried a third of a game pixel either way.
            val centred = (middle - FONT_MIDDLE.getValue(c) * pixel).coerceIn(from - pixel, to.toFloat())
            val steps = if (nudges) -1..1 else 0..0
            for (nx in steps) for (ny in steps) {
                val start = centred + nx * pixel / 3
                val down = ny * pixel / 3
                var wrong = 0
                for (r in 0 until ROWS) for (k in 0 until cols) {
                    val x0 = (start + k * pixel).roundToInt().coerceIn(from, to - 1)
                    val x1 = (start + (k + 1) * pixel).roundToInt().coerceIn(x0 + 1, to)
                    val y0 = (top + down + r * rows / ROWS.toFloat()).roundToInt().coerceIn(0, h - 1)
                    val y1 = (top + down + (r + 1) * rows / ROWS.toFloat()).roundToInt().coerceIn(y0 + 1, h)
                    var sum = 0f
                    for (y in y0 until y1) for (x in x0 until x1) sum += yellow[y * w + x]
                    val on = sum / ((y1 - y0) * (x1 - x0)) > 0.45f
                    if (on != (shape[r][k] == '#')) wrong++
                }
                if (wrong < bestWrong) { bestWrong = wrong; best = c; bestCells = cols * ROWS }
            }
        }
        LoadoutReader.debugLog?.invoke("  digit ${best ?: '?'}${if (nudges) " (nudged)" else ""}, $bestWrong game pixels off, ${"%.1f".format(wide)} wide")
        return best?.let { Triple(it, bestWrong, bestCells) }
    }

    // A few game pixels can be off where the edges blur, in proportion to the digit's size (a 1 has 24 game pixels, a
    // wide digit 40: one stroke of an M come apart once passed for a 1 with 8 off); more isn't a digit. Nudged cuts
    // fit wrong digits more easily (an 8 cut short looks like a 3), so they're held to less.
    private const val NUDGED_WRONG_SHARE = 0.15f
    private const val MAX_WRONG_SHARE = 0.22f
}
