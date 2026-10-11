package com.sinat.osrsbubbletool

import android.graphics.Rect
import kotlin.math.abs

// Finds parts of the game screen by the thin black line the game draws around its windows:
// the side panel (inventory, equipment...), the spellbook tab button, and an open rune pouch.
// They're always the same shape, so the tools can find them instead of you marking them by hand.
// The finding itself has no Android parts (Box, not Rect), so it can be tested on a computer.
object PanelFinder {

    // An area of the screen; right and bottom are just outside it, as with Android's Rect
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun toRect() = Rect(left, top, right, bottom)
    }

    private class Line(val at: Int, val start: Int, val length: Int)

    // The side panel (inventory, equipment, spellbook...): a bit taller than wide
    fun find(p: LightBoxReader.PixelSource): Rect? = findPanel(p)?.toRect()

    // (only a box with the column of tab buttons beside it: in a dark scene, the floor can outline a bigger box of the
    // same shape)
    fun findPanel(p: LightBoxReader.PixelSource): Box? = biggestOutline(p, 1.15f, 1.6f) { hasTabButtons(p, it) }

    // A tab button's box: they're in two columns right of the panel, 7 rows (the panel and the 7 rows are as tall)
    private fun tabButton(panel: Box, column: Int, row: Int): Box {
        val left = panel.right + panel.width * 0.012f + column * panel.width * 0.198f
        val colW = panel.width * 0.198f
        val rowH = panel.height / 7f
        return Box(left.toInt(), (panel.top + row * rowH).toInt(), (left + colW).toInt(), (panel.top + (row + 1) * rowH).toInt())
    }

    // A tab button's colour (red, green, blue), from a band just inside its edge (its picture is in the middle), or
    // null if it's off the screen
    private fun buttonColour(p: LightBoxReader.PixelSource, panel: Box, column: Int, row: Int): IntArray? {
        val button = tabButton(panel, column, row)
        var r = 0; var g = 0; var b = 0; var n = 0
        for (fx in floatArrayOf(0.12f, 0.16f, 0.84f, 0.88f)) for (fy in floatArrayOf(0.12f, 0.16f, 0.84f, 0.88f)) {
            val x = (button.left + button.width * fx).toInt(); val y = (button.top + button.height * fy).toInt()
            if (x < 0 || y < 0 || x >= p.width || y >= p.height) continue
            val c = p.rgb(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        return if (n == 0) null else intArrayOf(r / n, g / n, b / n)
    }

    // The spellbook tab button, whose picture shows which spellbook you're on: left column, 4th row down
    fun spellbookTab(panel: Box): Box = tabButton(panel, 0, 3)

    // Whether a tab is the open one: its button is red (119, 51, 40), the others brownish grey (88, 78, 64). The
    // inventory is column 0 row 0, the equipment column 0 row 1.
    fun tabOpen(p: LightBoxReader.PixelSource, panel: Box, column: Int, row: Int): Boolean {
        val c = buttonColour(p, panel, column, row) ?: return false
        return c[0] - c[1] > 40
    }

    // Whether the 14 tab buttons are beside a box: most of them the buttons' brownish grey (about 87, 77, 63), or red
    // for the open one
    private fun hasTabButtons(p: LightBoxReader.PixelSource, panel: Box): Boolean {
        var buttons = 0
        for (column in 0 until 2) for (row in 0 until 7) {
            val (r, g, b) = buttonColour(p, panel, column, row) ?: continue
            val grey = r in 40..140 && r - g in 4..20 && g - b in 6..22
            val red = r - g > 40
            if (grey || red) buttons++
        }
        return buttons >= MIN_TAB_BUTTONS
    }

    private const val MIN_TAB_BUTTONS = 11   // of 14 (a few can be covered: a tooltip, a highlighted tab)

    const val INVENTORY_TAB = 0   // its row (in column 0)
    const val EQUIPMENT_TAB = 1

    // An open rune pouch's window (it's almost square), or null if none is open
    fun runePouchWindow(p: LightBoxReader.PixelSource): Box? = biggestOutline(p, 0.85f, 1.0f)

    // The runes inside that window: the "Pouch" box near its top
    fun runePouchBox(window: Box): Box {
        val w = window.width
        val h = window.height
        return Box(window.left + (w * 0.196f).toInt(), window.top + (h * 0.124f).toInt(),
            window.left + (w * 0.802f).toInt(), window.top + (h * 0.306f).toInt())
    }

    // The biggest window outlined in black whose height / width is between minAspect and maxAspect, and that `accept`
    // agrees is the window looked for
    private fun biggestOutline(p: LightBoxReader.PixelSource, minAspect: Float, maxAspect: Float, accept: (Box) -> Boolean = { true }): Box? {
        val w = p.width
        val h = p.height
        fun black(x: Int, y: Int): Boolean {
            val c = p.rgb(x, y)
            return ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < 80
        }

        // Every long black run in each row, and the longest in each column. Gaps of up to 2 pixels are allowed,
        // so a speck of noise in the line doesn't break it. (Every run in a row, not just the longest: in a dark
        // scene, the floor beside the panel can make a longer black run in the same row as its top edge.)
        val colBest = IntArray(w); val colStart = IntArray(w); val colRun = IntArray(w); val colGap = IntArray(w)
        val rows = mutableListOf<Line>()
        val longRow = w * 0.12f
        for (y in 0 until h) {
            var run = 0; var gap = 0
            for (x in 0 until w) {
                if (black(x, y)) {
                    run += 1 + gap; gap = 0
                    colRun[x] += 1 + colGap[x]; colGap[x] = 0
                    if (colRun[x] > colBest[x]) { colBest[x] = colRun[x]; colStart[x] = y - colRun[x] + 1 }
                } else {
                    if (run > 0 && ++gap > 2) {
                        // the run ended 3 pixels back
                        if (run >= longRow) rows.add(Line(y, x - gap - run + 1, run))
                        run = 0; gap = 0
                    }
                    if (colRun[x] > 0 && ++colGap[x] > 2) { colRun[x] = 0; colGap[x] = 0 }
                }
            }
            if (run >= longRow) rows.add(Line(y, w - gap - run, run))
        }
        val cols = (0 until w).filter { colBest[it] >= h * 0.3f }.map { Line(it, colStart[it], colBest[it]) }
        if (rows.size < 2 || cols.size < 2) return null

        // A top and bottom edge the same width, with a left and right edge joining them
        var best: Box? = null
        for (i in rows.indices) for (j in i + 1 until rows.size) {
            val top = rows[i]; val bottom = rows[j]
            val height = bottom.at - top.at
            if (height < 20) continue
            // Where both edges overlap (something dark beside a line can make it look longer)
            val from = maxOf(top.start, bottom.start)
            val to = minOf(top.start + top.length, bottom.start + bottom.length)
            // Quick checks before the slower search for side edges (dark scenes give hundreds of rows):
            // the width can't be more than the overlap (plus a little), nor under 0.8 × the shorter edge
            val span = to - from
            if (span < 30) continue
            if (height > maxAspect * (span + 10)) continue
            if (height < minAspect * 0.8f * minOf(top.length, bottom.length)) continue
            // Side edges: long black lines that run from the top edge down to (nearly) the bottom edge. A line may
            // start higher up: something dark above the window can carry it on past the top edge.
            val near = height * 0.03f + 6
            val sides = cols.filter {
                it.at in (from - 5)..(to + 5) &&
                    it.start <= top.at + near && it.start + it.length >= top.at + height * 0.9f
            }
            // the lines nearest the ends of the top and bottom edges
            val mid = (from + to) / 2
            val leftLine = sides.filter { it.at < mid }.minByOrNull { abs(it.at - (from - 1)) } ?: continue
            val rightLine = sides.filter { it.at > mid }.minByOrNull { abs(it.at - to) } ?: continue
            val width = rightLine.at - leftLine.at
            if (width < 40 || width < 0.8f * minOf(top.length, bottom.length)) continue
            val aspect = height / width.toFloat()
            if (aspect !in minAspect..maxAspect) continue
            // the inside of the black outline
            val r = Box(leftLine.at + 1, top.at + 1, rightLine.at, bottom.at)
            if ((best == null || r.width * r.height > best.width * best.height) && accept(r)) best = r
        }
        return best
    }
}
