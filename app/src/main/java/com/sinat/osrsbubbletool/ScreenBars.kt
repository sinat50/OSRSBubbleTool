package com.sinat.osrsbubbletool

// Android draws the status bar (along the top) and the navigation bar (the home and back buttons, or the gesture
// strip: along the bottom, or down one side in landscape) on top of everything, so anything under them can't be
// touched. While the game is open the phone hides them; elsewhere they show. This works out where the bubble and the
// tool windows go so they stay clear of them, and go back where you left them when they hide. Plain arithmetic in
// screen pixels (no Android parts), so it's tested on the PC (ScreenBarsTest); BubbleService does the moving.

// How far each bar reaches in from its edge, in pixels (0 = not there)
data class Bars(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0) {
    fun clearRight(screenW: Int) = screenW - right      // the right edge of the part of the screen you can touch
    fun clearBottom(screenH: Int) = screenH - bottom    // and its bottom edge
}

// A place on the screen, and a window's place and size (a size of 0 or less: as big as what's in it)
data class Spot(val x: Int, val y: Int)
data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

object ScreenBars {

    // How far the bars reach in, from the two invisible strips Android keeps clear of them (see
    // BubbleService.addBarProbes): one a pixel wide from top to bottom (where its top and bottom are), one a pixel tall
    // across the top (where its ends are). Null if the reading can't be right: a strip longer than the screen, or a
    // "bar" a third of the screen deep, is one read while the phone turns, against the old screen size.
    fun fromStrips(downTop: Int, downHeight: Int, acrossLeft: Int, acrossWidth: Int, screenW: Int, screenH: Int): Bars? {
        val top = downTop
        val bottom = screenH - downTop - downHeight
        val left = acrossLeft
        val right = screenW - acrossLeft - acrossWidth
        if (bottom < 0 || right < 0) return null
        val l = left.coerceAtLeast(0)
        if (bottom > screenH / 3 || l > screenW / 3 || right > screenW / 3) return null
        return Bars(top, bottom, l, right)
    }

    // Where a window you put at `want` goes while `bars` show: moved out from under them, and if it's then too big to
    // fit, made smaller for now. A window as big as what's in it (want.w or want.h 0 or less) is kept clear once its
    // size (contentW, contentH) is known. With no bars it's exactly where you put it.
    fun place(want: Box, contentW: Int, contentH: Int, screenW: Int, screenH: Int, bars: Bars): Box {
        val right = bars.clearRight(screenW)
        val bottom = bars.clearBottom(screenH)
        // across
        var x = maxOf(want.x, bars.left)
        var w = want.w
        if (want.w > 0) {
            if (x + w > right) {
                x = maxOf(bars.left, right - w)
                w = minOf(w, right - x)
            }
        } else if (contentW > 0 && x + contentW > right) x = maxOf(bars.left, right - contentW)
        // down
        var y = maxOf(want.y, bars.top)
        var h = want.h
        if (want.h > 0) {
            if (y + h > bottom) {
                y = maxOf(bars.top, bottom - h)
                h = minOf(h, bottom - y)
            }
        } else if (contentH > 0 && y + contentH > bottom) y = maxOf(bars.top, bottom - contentH)
        return Box(x, y, w, h)
    }

    // The bubble (`size` across) at (x, y), kept where it can be touched: never under a bar while it shows
    fun keepBubble(x: Int, y: Int, size: Int, screenW: Int, screenH: Int, bars: Bars): Spot {
        val right = bars.clearRight(screenW)
        val bottom = bars.clearBottom(screenH)
        return Spot(x.coerceIn(minOf(bars.left, right - size).coerceAtLeast(0), maxOf(0, right - size)),
            y.coerceIn(minOf(bars.top, bottom - size).coerceAtLeast(0), maxOf(0, bottom - size)))
    }

    // A window (w × h) dragged to (x, y), stopped at the bars; up and down too only if `moveY` (otherwise y is left)
    fun drag(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int, bars: Bars, moveY: Boolean): Spot {
        val maxX = maxOf(0, bars.clearRight(screenW) - w)
        val maxY = maxOf(0, bars.clearBottom(screenH) - h)
        return Spot(x.coerceIn(minOf(bars.left, maxX), maxX), if (moveY) y.coerceIn(minOf(bars.top, maxY), maxY) else y)
    }

    // A window at (x, y) resized to w × h: no smaller than minW × minH, and not past the bars
    fun resize(w: Int, h: Int, x: Int, y: Int, minW: Int, minH: Int, screenW: Int, screenH: Int, bars: Bars): Box =
        Box(x, y, w.coerceIn(minW, maxOf(minW, bars.clearRight(screenW) - x)), h.coerceIn(minH, maxOf(minH, bars.clearBottom(screenH) - y)))
}
