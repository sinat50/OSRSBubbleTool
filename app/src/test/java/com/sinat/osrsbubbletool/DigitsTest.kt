package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// The digit reader on amounts drawn here from the game's font, at the sizes phones draw the game and with the blur
// their enlarging adds. The shapes are the game's (worked out from the owner's screenshots); what each amount says is
// known because it's drawn here.
class DigitsTest {

    // The game's digits and letters, one string per row of game pixels ('#' is text)
    private val shapes = mapOf(
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
        'K' to listOf("#....", "#..#.", "#.#..", "###..", "#.#..", "#..#.", "#..#.", "#...#"),
        'M' to listOf("#....#", "##..##", "#.##.#", "#....#", "#....#", "#....#", "#....#", "#....#"),
    )

    // An amount drawn s screen pixels to a game pixel, one game pixel between characters, then blurred: each screen
    // pixel how much it's the text's colour (0 to 1), as the reader gets it. shift moves it a fraction of a pixel.
    private fun drawn(text: String, s: Float, shift: Float, blur: Boolean): Triple<FloatArray, Int, Int> {
        val gameW = text.sumOf { shapes.getValue(it)[0].length + 1 } + 2
        val w = (gameW * s).toInt() + 4
        val h = (12 * s).toInt()
        val mask = FloatArray(w * h)
        var gx = 1
        for (c in text) {
            val shape = shapes.getValue(c)
            for (r in shape.indices) for (k in shape[r].indices) if (shape[r][k] == '#') {
                // the game pixel's square on the screen, partly covering the pixels at its edges
                val x0 = (gx + k) * s + shift; val y0 = (2 + r) * s + shift
                for (y in y0.toInt() until minOf(h, (y0 + s).toInt() + 1)) for (x in x0.toInt() until minOf(w, (x0 + s).toInt() + 1)) {
                    val cover = (minOf(x + 1f, x0 + s) - maxOf(x.toFloat(), x0)).coerceIn(0f, 1f) *
                        (minOf(y + 1f, y0 + s) - maxOf(y.toFloat(), y0)).coerceIn(0f, 1f)
                    mask[y * w + x] = minOf(1f, mask[y * w + x] + cover)
                }
            }
            gx += shape[0].length + 1
        }
        if (!blur) return Triple(mask, w, h)
        // the phone's smoothing: each pixel shares a little with its neighbours
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var n = 0f
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                val weight = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 1f else 0.5f
                sum += mask[yy * w + xx] * weight; n += weight
            }
            out[y * w + x] = sum / n
        }
        return Triple(out, w, h)
    }

    // A 4 just before the K or M was once at risk of passing for the letter (it ends the number early: "1234K" would
    // be 123K). Each amount must read in full, or not at all: never shorter.
    @Test
    fun aFourBeforeTheLetterIsNeverTakenForIt() {
        val problems = ArrayList<String>()
        for (amount in listOf("1234K", "4444K", "9994K", "124M", "44M", "2444M", "134K", "24M")) {
            val digits = amount.dropLast(1)
            for (s in listOf(1.4f, 1.6f, 1.8f, 2.1f, 2.4f, 2.8f)) for (shift in listOf(0f, 0.33f, 0.5f, 0.75f)) for (blur in listOf(false, true)) {
                val (mask, w, h) = drawn(amount, s, shift, blur)
                val read = Digits.read(mask, w, h, stopAtOther = true)
                if (read != null && read != digits) problems.add("$amount at ${s}x+$shift${if (blur) " blurred" else ""}: read $read")
            }
        }
        assertEquals("", problems.joinToString("; "))
    }

    // And the letter ends the number: its upright stroke isn't read as a 1 (134K once came out as 1,341,000)
    @Test
    fun theLetterEndsTheNumber() {
        val (mask, w, h) = drawn("134K", 2.1f, 0f, true)
        assertEquals("134", Digits.read(mask, w, h, stopAtOther = true))
        val (mask2, w2, h2) = drawn("24M", 2.1f, 0f, true)
        assertEquals("24", Digits.read(mask2, w2, h2, stopAtOther = true))
    }
}
