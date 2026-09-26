package com.sinat.osrsbubbletool

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.roundToInt

// Finds the exact position of the 5×5 puzzle grid near a rough area the user marked.
// It looks for 6 evenly spaced lines where the picture changes sharply (the tile edges).
object GridFinder {

    private const val GRID = 5
    private const val SEARCH_MARGIN = 0.2f      // look up to 20% beyond the rough area
    private const val SIZE_TOLERANCE = 0.2f     // the real grid can be up to 20% bigger or smaller
    private const val SPACING_STEP = 0.25f      // how finely to test tile sizes, in pixels
    private const val DISTANCE_PENALTY = 0.35f  // how strongly to favor grids close to your frame

    class Result(val area: Rect, val confidence: Float)

    private class Fit(val offset: Int, val spacing: Float, val confidence: Float)

    fun find(shot: Bitmap, rough: Rect): Result? {
        val margin = (rough.width() * SEARCH_MARGIN).toInt()
        val search = Rect(rough.left - margin, rough.top - margin, rough.right + margin, rough.bottom + margin)
        if (!search.intersect(0, 0, shot.width, shot.height)) return null
        val w = search.width()
        val h = search.height()
        if (w < 20 || h < 20) return null

        // Brightness of every pixel in the search area
        val pixels = IntArray(w * h)
        shot.getPixels(pixels, 0, w, search.left, search.top, w, h)
        val lum = IntArray(w * h) { i ->
            val c = pixels[i]
            ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        }

        // The rough area, measured inside the search area
        val rx0 = (rough.left - search.left).coerceIn(0, w - 1)
        val rx1 = (rough.right - search.left).coerceIn(rx0 + 1, w)
        val ry0 = (rough.top - search.top).coerceIn(0, h - 1)
        val ry1 = (rough.bottom - search.top).coerceIn(ry0 + 1, h)

        // How sharply the picture changes at each column and each row
        val colEdges = FloatArray(w)
        for (x in 1 until w) {
            var sum = 0
            for (y in ry0 until ry1) sum += abs(lum[y * w + x] - lum[y * w + x - 1])
            colEdges[x] = sum.toFloat()
        }
        val rowEdges = FloatArray(h)
        for (y in 1 until h) {
            var sum = 0
            val row = y * w
            val above = (y - 1) * w
            for (x in rx0 until rx1) sum += abs(lum[row + x] - lum[above + x])
            rowEdges[y] = sum.toFloat()
        }

        val cols = bestFit(colEdges, rough.width() / GRID.toFloat(), rx0) ?: return null
        val rows = bestFit(rowEdges, rough.height() / GRID.toFloat(), ry0) ?: return null

        val left = search.left + cols.offset
        val top = search.top + rows.offset
        val area = Rect(
            left, top,
            left + (cols.spacing * GRID).roundToInt(),
            top + (rows.spacing * GRID).roundToInt()
        )
        return Result(area, minOf(cols.confidence, rows.confidence))
    }

    // Tries every tile size and starting position, and keeps the set of 6 evenly spaced
    // lines that land on the strongest edges, preferring ones close to your frame.
    private fun bestFit(edges: FloatArray, roughTile: Float, expectedStart: Int): Fit? {
        val average = edges.average().toFloat()
        if (average <= 0f || roughTile <= 0f) return null
        val minSpacing = roughTile * (1 - SIZE_TOLERANCE)
        val maxSpacing = roughTile * (1 + SIZE_TOLERANCE)
        val expectedEnd = expectedStart + roughTile * GRID

        var best: Fit? = null
        var bestWeighted = -1f
        var spacing = minSpacing
        while (spacing <= maxSpacing) {
            val span = spacing * GRID
            var offset = 0
            while (offset + span < edges.size - 1) {
                var score = 0f
                for (k in 0..GRID) score += peak(edges, (offset + k * spacing).roundToInt())

                // how far this grid's edges are from your frame's edges, in tiles
                val distance = (abs(offset - expectedStart) + abs(offset + span - expectedEnd)) / 2f
                val weighted = score * (1f - DISTANCE_PENALTY * minOf(1f, distance / roughTile))

                if (weighted > bestWeighted) {
                    bestWeighted = weighted
                    // confidence: how much stronger these lines are than an average column/row
                    best = Fit(offset, spacing, score / ((GRID + 1) * average))
                }
                offset++
            }
            spacing += SPACING_STEP
        }
        return best
    }

    // Strongest edge at or right next to position i (allows for rounding)
    private fun peak(edges: FloatArray, i: Int): Float {
        var max = 0f
        for (j in i - 1..i + 1) if (j in edges.indices && edges[j] > max) max = edges[j]
        return max
    }
}