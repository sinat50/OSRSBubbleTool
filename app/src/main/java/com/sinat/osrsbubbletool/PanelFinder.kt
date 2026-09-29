package com.sinat.osrsbubbletool

import android.graphics.Rect
import kotlin.math.abs

// Finds parts of the game screen by the thin black line the game draws around its windows:
// the side panel (inventory, equipment...), the spellbook tab, and an open rune pouch.
// They're always the same shape, so the tools can find them instead of you marking them by hand.
object PanelFinder {

    private class Line(val at: Int, val start: Int, val length: Int)

    // The side panel (inventory, equipment, spellbook...): a bit taller than wide
    fun find(p: LightBoxReader.PixelSource): Rect? = biggestOutline(p, 1.15f, 1.6f)

    // The spellbook tab button. Its picture shows which spellbook you're on.
    // It's in the column of tab buttons right of the panel: left column, 4th row down.
    fun spellbookTab(panel: Rect): Rect {
        val left = panel.right + panel.width() * 0.012f
        val colW = panel.width() * 0.198f
        val rowH = panel.height() / 7f
        return Rect(left.toInt(), (panel.top + 3 * rowH).toInt(), (left + colW).toInt(), (panel.top + 4 * rowH).toInt())
    }

    // The runes inside an open rune pouch: the "Pouch" box near the top of its window
    fun runePouch(p: LightBoxReader.PixelSource): Rect? {
        val window = biggestOutline(p, 0.85f, 1.0f) ?: return null
        val w = window.width()
        val h = window.height()
        return Rect(window.left + (w * 0.196f).toInt(), window.top + (h * 0.124f).toInt(),
            window.left + (w * 0.802f).toInt(), window.top + (h * 0.306f).toInt())
    }

    // The biggest window outlined in black whose height / width is between minAspect and maxAspect
    private fun biggestOutline(p: LightBoxReader.PixelSource, minAspect: Float, maxAspect: Float): Rect? {
        val w = p.width
        val h = p.height
        fun black(x: Int, y: Int): Boolean {
            val c = p.rgb(x, y)
            return ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < 80
        }

        // Longest black run in every column and every row. Gaps of up to 2 pixels are allowed,
        // so a speck of noise in the line doesn't break it.
        val colBest = IntArray(w); val colStart = IntArray(w); val colRun = IntArray(w); val colGap = IntArray(w)
        val rows = mutableListOf<Line>()
        for (y in 0 until h) {
            var run = 0; var gap = 0; var bestRun = 0; var bestStart = 0
            for (x in 0 until w) {
                if (black(x, y)) {
                    run += 1 + gap; gap = 0
                    colRun[x] += 1 + colGap[x]; colGap[x] = 0
                    if (colRun[x] > colBest[x]) { colBest[x] = colRun[x]; colStart[x] = y - colRun[x] + 1 }
                } else {
                    if (run > 0 && ++gap > 2) { run = 0; gap = 0 }
                    if (colRun[x] > 0 && ++colGap[x] > 2) { colRun[x] = 0; colGap[x] = 0 }
                }
                if (run > bestRun) { bestRun = run; bestStart = x - gap - run + 1 }
            }
            if (bestRun >= w * 0.12f) rows.add(Line(y, bestStart, bestRun))
        }
        val cols = (0 until w).filter { colBest[it] >= h * 0.3f }.map { Line(it, colStart[it], colBest[it]) }
        if (rows.size < 2 || cols.size < 2) return null

        // A top and bottom edge the same width, with a left and right edge joining them
        var best: Rect? = null
        for (i in rows.indices) for (j in i + 1 until rows.size) {
            val top = rows[i]; val bottom = rows[j]
            val height = bottom.at - top.at
            if (height < 20) continue
            // Where both edges overlap (something dark beside a line can make it look longer)
            val from = maxOf(top.start, bottom.start)
            val to = minOf(top.start + top.length, bottom.start + bottom.length)
            // Side edges: long black lines that run from the top edge down to the bottom edge
            val sides = cols.filter {
                it.at in (from - 5)..(to + 5) &&
                    abs(it.start - top.at) <= height * 0.03f + 6 && it.length >= height * 0.9f
            }
            // the lines nearest the ends of the top and bottom edges
            val mid = (from + to) / 2
            val leftLine = sides.filter { it.at < mid }.minByOrNull { abs(it.at - (from - 1)) } ?: continue
            val rightLine = sides.filter { it.at > mid }.minByOrNull { abs(it.at - to) } ?: continue
            val width = rightLine.at - leftLine.at
            if (width < 40 || width < 0.8f * minOf(top.length, bottom.length)) continue
            val aspect = height / width.toFloat()
            if (aspect < minAspect || aspect > maxAspect) continue
            // the inside of the black outline
            val r = Rect(leftLine.at + 1, top.at + 1, rightLine.at, bottom.at)
            if (best == null || r.width() * r.height() > best.width() * best.height()) best = r
        }
        return best
    }
}
