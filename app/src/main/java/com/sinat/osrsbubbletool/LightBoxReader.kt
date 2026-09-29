package com.sinat.osrsbubbletool

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

// Finds the light box on the screen and reads which bulbs are on.
//
// Everything is measured in "spacing" units: the distance between two bulb columns.
// The light box is drawn with interface pictures, which the game's brightness setting
// doesn't change, so the colours below stay the same on every phone.
object LightBoxReader {

    const val SIZE = 5                    // 5 x 5 bulbs
    const val ALL_ON = (1 shl 25) - 1     // every bulb lit = solved

    private const val ROW_RATIO = 0.6225f        // row spacing / column spacing
    private const val SPACING_PER_WIDTH = 3.5f   // column spacing / width of a lit bulb's yellow top
    private const val MAX_CELL_DIFF = 16f        // above this, a spot doesn't look like a bulb at all

    // Where the 8 buttons are, from the top-left bulb, in spacing units
    private const val BUTTON_X0 = -0.006f
    private const val BUTTON_STEP = 1.43f
    private val BUTTON_ROWS = floatArrayOf(3.378f, 4.075f)
    private const val BUTTON_HALF_W = 0.625f
    private const val BUTTON_HALF_H = 0.277f

    // Each bulb is compared with these 30 sample points (5 across, 6 down around its top)
    private val SAMPLE_DX = FloatArray(30) { -0.22f + 0.11f * (it / 6) }
    private val SAMPLE_DY = FloatArray(30) { -0.12f + 0.084f * (it % 6) }

    // What a lit and an unlit bulb look like at those points (R, G, B for each point)
    private val LIT = intArrayOf(
        82, 74, 62, 83, 75, 62, 83, 75, 63, 83, 75, 63, 83, 75, 63, 82, 74, 63, 82, 75, 62, 78, 71, 58,
        186, 184, 39, 55, 49, 38, 82, 74, 62, 82, 75, 63, 81, 73, 62, 188, 186, 16, 159, 157, 12, 114, 113, 7,
        61, 45, 18, 47, 40, 30, 57, 55, 8, 127, 126, 8, 101, 101, 5, 70, 69, 6, 78, 75, 26, 12, 8, 0,
        83, 74, 63, 103, 101, 8, 51, 51, 1, 19, 18, 0, 82, 75, 63, 83, 75, 63
    )
    private val UNLIT = intArrayOf(
        83, 75, 63, 83, 75, 63, 83, 75, 63, 83, 75, 63, 83, 75, 63, 83, 75, 63, 83, 75, 63, 79, 71, 61,
        81, 75, 75, 46, 41, 34, 83, 75, 63, 83, 75, 63, 82, 74, 63, 80, 74, 73, 68, 62, 62, 48, 44, 43,
        60, 43, 21, 49, 41, 32, 19, 16, 14, 55, 51, 50, 43, 39, 38, 42, 38, 36, 54, 49, 43, 12, 7, 0,
        83, 75, 63, 42, 40, 38, 20, 18, 19, 7, 7, 7, 83, 74, 62, 82, 75, 63
    )

    // Where the light box is: the top-left bulb, and the column spacing (screenshot pixels)
    data class Layout(val x0: Float, val y0: Float, val spacing: Float) {
        val rowSpacing get() = spacing * ROW_RATIO

        fun buttonRect(button: Int): RectF {
            val cx = x0 + (BUTTON_X0 + (button % 4) * BUTTON_STEP) * spacing
            val cy = y0 + BUTTON_ROWS[button / 4] * spacing
            return RectF(cx - BUTTON_HALF_W * spacing, cy - BUTTON_HALF_H * spacing,
                cx + BUTTON_HALF_W * spacing, cy + BUTTON_HALF_H * spacing)
        }

        // The whole light box window
        fun windowRect() = Rect(
            (x0 - 0.936f * spacing).roundToInt(), (y0 - 1.251f * spacing).roundToInt(),
            (x0 + 5.177f * spacing).roundToInt(), (y0 + 4.558f * spacing).roundToInt()
        )

        // The title bar ("Light box"), where the instructions are written
        fun titleRect() = RectF(x0 - 0.8f * spacing, y0 - 1.19f * spacing,
            x0 + 4.5f * spacing, y0 - 0.62f * spacing)
    }

    // Anything pixels can be read from: a screenshot, or the live capture
    interface PixelSource {
        val width: Int
        val height: Int
        fun rgb(x: Int, y: Int): Int   // 0xRRGGBB
    }

    // A screenshot's pixels, read once so they can be looked at quickly
    class Pixels(bitmap: Bitmap) : PixelSource {
        override val width = bitmap.width
        override val height = bitmap.height
        val data = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        fun at(x: Int, y: Int) = data[y * width + x]
        override fun rgb(x: Int, y: Int) = data[y * width + x]
    }

    // ---------------- Reading bulbs ----------------

    // How different the spot at (x, y) is from a lit and from an unlit bulb.
    // Returns false if the spot is off the screen.
    private fun compare(p: PixelSource, x: Float, y: Float, spacing: Float, out: FloatArray): Boolean {
        var lit = 0
        var unlit = 0
        for (k in 0 until 30) {
            val px = (x + SAMPLE_DX[k] * spacing).roundToInt()
            val py = (y + SAMPLE_DY[k] * spacing).roundToInt()
            if (px < 0 || py < 0 || px >= p.width || py >= p.height) return false
            val c = p.rgb(px, py)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            lit += abs(r - LIT[k * 3]) + abs(g - LIT[k * 3 + 1]) + abs(b - LIT[k * 3 + 2])
            unlit += abs(r - UNLIT[k * 3]) + abs(g - UNLIT[k * 3 + 1]) + abs(b - UNLIT[k * 3 + 2])
        }
        out[0] = lit / 90f
        out[1] = unlit / 90f
        return true
    }

    // Which bulbs are on (bit = row * 5 + column), or null if the light box isn't there
    fun read(p: PixelSource, layout: Layout): Int? {
        val d = FloatArray(2)
        var state = 0
        for (row in 0 until SIZE) for (col in 0 until SIZE) {
            if (!compare(p, layout.x0 + col * layout.spacing, layout.y0 + row * layout.rowSpacing, layout.spacing, d)) return null
            if (minOf(d[0], d[1]) > MAX_CELL_DIFF) return null
            if (d[0] < d[1]) state = state or (1 shl (row * SIZE + col))
        }
        return state
    }

    // ---------------- Finding the light box ----------------

    // Looks for lit bulbs' yellow tops, then tries every way a 5 x 5 grid could fit around
    // one of them, keeping the one where all 25 spots look like bulbs.
    // At least one bulb has to be lit.
    fun locate(p: Pixels): Layout? {
        val candidates = yellowTops(p)
        if (candidates.isEmpty()) return null
        // Real bulbs are all the same size, so try the ones with the most same-sized partners first
        val ordered = candidates.sortedByDescending { b -> candidates.count { abs(it.width - b.width) <= 0.15f * b.width } }

        val d = FloatArray(2)
        var best: Layout? = null
        var bestScore = Float.MAX_VALUE
        for (blob in ordered.take(8)) {
            for (step in -12..12) {
                val spacing = SPACING_PER_WIDTH * blob.width * (1f + step / 100f)
                val rowSpacing = spacing * ROW_RATIO
                for (col0 in 0 until SIZE) for (row0 in 0 until SIZE) {
                    val x0 = blob.x - col0 * spacing
                    val y0 = blob.y - row0 * rowSpacing
                    var total = 0f
                    var ok = true
                    loop@ for (row in 0 until SIZE) for (col in 0 until SIZE) {
                        if (!compare(p, x0 + col * spacing, y0 + row * rowSpacing, spacing, d)) { ok = false; break@loop }
                        val m = minOf(d[0], d[1])
                        if (m > MAX_CELL_DIFF) { ok = false; break@loop }
                        total += m
                        if (total >= bestScore) { ok = false; break@loop }
                    }
                    if (ok && total < bestScore) {
                        bestScore = total
                        best = Layout(x0, y0, spacing)
                    }
                }
            }
        }
        return best
    }

    private class Blob(val x: Float, val y: Float, val width: Float)

    // Patches of the lit bulbs' exact yellow, found on a half-size grid to keep it quick
    private fun yellowTops(p: Pixels): List<Blob> {
        val w = p.width / 2
        val h = p.height / 2
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = p.at(x * 2, y * 2)
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            mask[y * w + x] = r > 135 && g > 135 && abs(r - g) < 20 && b < 40
        }
        val blobs = mutableListOf<Blob>()
        val stack = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start]) continue
            var top = 0
            stack[top++] = start
            mask[start] = false
            var count = 0; var sumX = 0L; var sumY = 0L
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                count++; sumX += x; sumY += y
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1]) { mask[i - 1] = false; stack[top++] = i - 1 }
                if (x < w - 1 && mask[i + 1]) { mask[i + 1] = false; stack[top++] = i + 1 }
                if (y > 0 && mask[i - w]) { mask[i - w] = false; stack[top++] = i - w }
                if (y < h - 1 && mask[i + w]) { mask[i + w] = false; stack[top++] = i + w }
            }
            val bw = (maxX - minX + 1) * 2f
            val bh = (maxY - minY + 1) * 2f
            if (bw < 10f || bh / bw < 0.5f || bh / bw > 1.1f) continue
            if (count * 4f < 0.25f * bw * bh) continue
            blobs.add(Blob(sumX * 2f / count, sumY * 2f / count, bw))
            if (blobs.size >= 200) break
        }
        return blobs
    }

    // ---------------- Solving ----------------

    // The fewest buttons (from the ones whose effect is known) that turn every bulb on.
    // effects[i] = the bulbs button i flips, or null if not known yet.
    // Returns the buttons to press, or null if the known buttons can't do it.
    fun solve(state: Int, effects: Array<Int?>): List<Int>? {
        val need = state xor ALL_ON
        var best: List<Int>? = null
        for (mask in 0 until (1 shl 8)) {
            var flips = 0
            var ok = true
            for (b in 0 until 8) {
                if (mask and (1 shl b) == 0) continue
                val e = effects[b]
                if (e == null) { ok = false; break }
                flips = flips xor e
            }
            if (!ok || flips != need) continue
            val buttons = (0 until 8).filter { mask and (1 shl it) != 0 }
            if (best == null || buttons.size < best.size) best = buttons
        }
        return best
    }
}
