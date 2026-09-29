package com.sinat.osrsbubbletool

import android.graphics.Rect
import kotlin.math.abs

// Finds an open puzzle box on the screen by its wooden frame, and spots the empty space quickly.
// The frame and the empty space are interface pictures, so the game's brightness setting
// doesn't change their colours.
object PuzzleFinder {

    private const val GRID = 5

    // The frame's dark wood (the empty space and the lines between tiles are the same colour)
    private fun isWood(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return b < 14 && r in 28..100 && g in 15..65 && r * 10 >= g * 14
    }

    // Where the 5 x 5 tiles are, found from the frame around them, or null if there's no puzzle box
    fun findTiles(p: LightBoxReader.PixelSource): Rect? {
        // 1. Patches of frame-coloured wood, on a half-size grid to keep it quick
        val w = p.width / 2
        val h = p.height / 2
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) mask[y * w + x] = isWood(p.rgb(x * 2, y * 2))

        // 2. The biggest roughly-square patch is the frame (with the lines between tiles)
        var best: Rect? = null
        val stack = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start]) continue
            var top = 0
            stack[top++] = start
            mask[start] = false
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1]) { mask[i - 1] = false; stack[top++] = i - 1 }
                if (x < w - 1 && mask[i + 1]) { mask[i + 1] = false; stack[top++] = i + 1 }
                if (y > 0 && mask[i - w]) { mask[i - w] = false; stack[top++] = i - w }
                if (y < h - 1 && mask[i + w]) { mask[i + w] = false; stack[top++] = i + w }
            }
            val bw = (maxX - minX + 1) * 2
            val bh = (maxY - minY + 1) * 2
            if (bw < 100 || bh < 0.85f * bw || bh > 1.15f * bw) continue
            if (best == null || bw * bh > best.width() * best.height()) {
                best = Rect(minX * 2, minY * 2, (maxX + 1) * 2, (maxY + 1) * 2)
            }
        }
        val outer = best ?: return null

        // 3. How thick the frame is on each side: walk inwards until the wood ends.
        //    Five lines per side, and the middle answer is used (one may cross the empty space).
        fun thickness(side: Int): Int {
            val runs = IntArray(5)
            for (k in 0 until 5) {
                val t = 0.1f + 0.2f * k
                var x: Int; var y: Int; val dx: Int; val dy: Int
                when (side) {
                    0 -> { x = outer.left + 1; y = (outer.top + outer.height() * t).toInt(); dx = 1; dy = 0 }
                    1 -> { x = outer.right - 2; y = (outer.top + outer.height() * t).toInt(); dx = -1; dy = 0 }
                    2 -> { x = (outer.left + outer.width() * t).toInt(); y = outer.top + 1; dx = 0; dy = 1 }
                    else -> { x = (outer.left + outer.width() * t).toInt(); y = outer.bottom - 2; dx = 0; dy = -1 }
                }
                var n = 0
                while (n < outer.width() / 3 && x in 0 until p.width && y in 0 until p.height &&
                    (n < 3 || isWood(p.rgb(x, y)))) {
                    x += dx; y += dy; n++
                }
                runs[k] = n
            }
            runs.sort()
            return runs[2]
        }
        val left = thickness(0); val right = thickness(1); val topT = thickness(2); val bottom = thickness(3)
        val size = outer.width()
        for (t in intArrayOf(left, right, topT, bottom)) {
            if (t < size * 0.03f || t > size * 0.12f) return null   // doesn't look like the puzzle frame
        }
        return Rect(outer.left + left, outer.top + topT, outer.right - right, outer.bottom - bottom)
    }

    // Which space (0-24) is empty, checked from 9 pixels in each tile: the empty space is plain
    // dark wood. Returns -1 if there isn't exactly one (a tile is mid-slide, or something covers it).
    // Tiles in `covered` have a guide dot in the middle, so they're read from a ring of 8 pixels
    // around the dot instead.
    fun emptySpace(p: LightBoxReader.PixelSource, tiles: Rect, covered: Set<Int> = emptySet()): Int {
        val tileW = tiles.width() / GRID.toFloat()
        val tileH = tiles.height() / GRID.toFloat()
        var found = -1
        for (pos in 0 until GRID * GRID) {
            val col = pos % GRID
            val row = pos / GRID
            var empty = true
            var minR = 255; var maxR = 0
            val spots = if (pos in covered) RING else GRID_SPOTS
            loop@ for ((fx, fy) in spots) {
                val x = (tiles.left + (col + fx) * tileW).toInt()
                val y = (tiles.top + (row + fy) * tileH).toInt()
                if (x < 0 || y < 0 || x >= p.width || y >= p.height) return -1
                val c = p.rgb(x, y)
                if (!isWood(c)) { empty = false; break@loop }
                val r = (c shr 16) and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
            }
            if (empty && abs(maxR - minR) <= 30) {
                if (found != -1) return -1
                found = pos
            }
        }
        return found
    }

    // Is the wooden frame still there around these tiles? (It disappears when the puzzle closes.)
    fun frameVisible(p: LightBoxReader.PixelSource, tiles: Rect): Boolean {
        val out = tiles.width() * 0.035f   // the middle of the frame, just outside the tiles
        var wood = 0
        for (t in FRACTIONS) {
            val x = tiles.left + tiles.width() * t
            val y = tiles.top + tiles.height() * t
            val spots = arrayOf(
                floatArrayOf(tiles.left - out, y), floatArrayOf(tiles.right + out, y),
                floatArrayOf(x, tiles.top - out), floatArrayOf(x, tiles.bottom + out)
            )
            for (s in spots) {
                val px = s[0].toInt()
                val py = s[1].toInt()
                if (px in 0 until p.width && py in 0 until p.height && isWood(p.rgb(px, py))) wood++
            }
        }
        return wood >= 8   // of 12 spots
    }

    private val FRACTIONS = floatArrayOf(0.25f, 0.5f, 0.75f)
    private val GRID_SPOTS = FRACTIONS.flatMap { fy -> FRACTIONS.map { fx -> fx to fy } }
    // Just inside the tile's edge, clear of the biggest guide dot (radius 0.28 of a tile)
    private val RING = listOf(0.15f to 0.15f, 0.5f to 0.13f, 0.85f to 0.15f, 0.13f to 0.5f,
        0.87f to 0.5f, 0.15f to 0.85f, 0.5f to 0.87f, 0.85f to 0.85f)
}
