package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.toColorInt

// Outlines the next few tiles to tap, drawn on top of the puzzle in the game, with a short
// message ("Move 3 of 42") just above the puzzle's frame. Taps pass straight through to the game.
class MoveGuideOverlay(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        private const val GRID = 5

        // Outline colour for move 1, 2, 3, 4
        val MOVE_COLORS = intArrayOf(
            "#3DDC5A".toColorInt(),  // move 1: green
            "#FFD60A".toColorInt(),  // move 2: yellow
            "#FF9500".toColorInt(),  // move 3: orange
            "#FF3B30".toColorInt()   // move 4: red
        )

        // Outline thickness for move 1, 2, 3, 4, as a fraction of a tile's width.
        // They stay within the outer edge of each tile, which the tracking ignores.
        val MOVE_WIDTHS = floatArrayOf(0.10f, 0.07f, 0.045f, 0.025f)

        // Dot style: one dot per move, each clearly smaller than the last (as a fraction of a tile's width).
        // The biggest leaves the ring the tracking reads (see PuzzleFinder.emptySpace) uncovered.
        val DOT_RADII = floatArrayOf(0.28f, 0.2f, 0.13f, 0.07f)
        private val DOT_COLOR = "#FFD60A".toColorInt()
        private val DOT_EDGE = "#1A1208".toColorInt()

        private val BANNER_BG = "#E6201A10".toColorInt()
        val GREEN = MOVE_COLORS[0]
    }

    private var view: GuideView? = null
    private var params: WindowManager.LayoutParams? = null
    private var moves: List<Int> = emptyList()
    private var message = ""
    private var messageColor = Color.WHITE

    // Where things go, in screenshot pixels
    private var tiles = Rect()      // the 5 x 5 tiles
    private var window = Rect()     // the whole overlay (tiles plus the message strip)
    private var banner = RectF()    // the message strip
    private var bannerInWindow = RectF()   // the same, measured from the overlay's corner

    val isShowing: Boolean get() = view != null

    // true = dots that shrink with each move; false = coloured boxes around the tiles
    var dots = false
        set(value) { field = value; updateCovered(); view?.invalidate() }

    // Tiles with a dot in the middle (or that had one a moment ago, as the screen can lag a frame).
    // The tracking reads those tiles differently so the dot doesn't hide the empty space.
    @Volatile var coveredTiles: Set<Int> = emptySet()
        private set
    private var previouslyCovered: Set<Int> = emptySet()
    private var coveredChangedAt = 0L

    private fun updateCovered() {
        val now = android.os.SystemClock.uptimeMillis()
        val current = if (dots) moves.toSet() else emptySet()
        if (now - coveredChangedAt > 600) previouslyCovered = emptySet()
        previouslyCovered = previouslyCovered + coveredTiles
        coveredChangedAt = now
        coveredTiles = current + previouslyCovered
        // forget the old ones once the screen has surely caught up
        view?.postDelayed({ if (android.os.SystemClock.uptimeMillis() - coveredChangedAt >= 600) {
            previouslyCovered = emptySet(); coveredTiles = if (dots) moves.toSet() else emptySet()
        } }, 650)
    }

    // tiles: where the puzzle's tiles are, in screenshot pixels
    fun show(tiles: Rect) {
        hide()
        this.tiles = Rect(tiles)
        // The message sits just outside the wooden frame, above it (or below it if there's no room)
        val frame = (tiles.width() * 0.08f).toInt()
        val bannerH = tiles.height() * 0.09f
        val above = tiles.top - frame - bannerH - 4 >= 0
        banner = if (above) RectF(tiles.left.toFloat(), tiles.top - frame - bannerH - 4, tiles.right.toFloat(), tiles.top - frame - 4f)
                 else RectF(tiles.left.toFloat(), tiles.bottom + frame + 4f, tiles.right.toFloat(), tiles.bottom + frame + 4 + bannerH)
        window = Rect(tiles.left, minOf(tiles.top, banner.top.toInt()), tiles.right, maxOf(tiles.bottom, banner.bottom.toInt() + 1))
        // the message strip inside the overlay, worked out once rather than on every redraw
        bannerInWindow = RectF(banner).apply { offset(-window.left.toFloat(), -window.top.toFloat()) }

        val p = WindowManager.LayoutParams(
            window.width(), window.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = window.left
            y = window.top
            alpha = 0.8f   // Android only lets taps pass through overlays that are a little see-through
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = GuideView()
        windowManager.addView(v, p)
        view = v
        params = p
    }

    // The positions (0-24) of the next moves, in order. Up to 4 are shown.
    fun setMoves(nextMoves: List<Int>) {
        moves = nextMoves.take(MOVE_COLORS.size)
        updateCovered()
        view?.invalidate()
    }

    fun setMessage(text: String, color: Int = Color.WHITE) {
        message = text
        messageColor = color
        view?.invalidate()
    }

    fun hide() {
        view?.let { windowManager.removeView(it) }
        view = null
        params = null
        moves = emptyList()
        message = ""
        coveredTiles = emptySet()
        previouslyCovered = emptySet()
    }

    @SuppressLint("ViewConstructor")
    private inner class GuideView : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private var aligned = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // The overlay may land a little away from where it was asked to be (status bar,
            // camera cutout). Measure where it really is once, and move it to line up.
            if (!aligned) {
                aligned = true
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val p = params
                if (p != null && (loc[0] != window.left || loc[1] != window.top)) {
                    p.x += window.left - loc[0]
                    p.y += window.top - loc[1]
                    windowManager.updateViewLayout(this, p)
                }
            }
            val ox = window.left.toFloat()
            val oy = window.top.toFloat()
            val tileW = tiles.width() / GRID.toFloat()
            val tileH = tiles.height() / GRID.toFloat()

            // draw the later moves first, so move 1 ends up on top
            if (dots) {
                for (i in moves.indices.reversed()) {
                    val pos = moves[i]
                    val cx = tiles.left - ox + (pos % GRID + 0.5f) * tileW
                    val cy = tiles.top - oy + (pos / GRID + 0.5f) * tileH
                    val r = tileW * DOT_RADII[i]
                    fill.color = DOT_EDGE
                    canvas.drawCircle(cx, cy, r, fill)
                    fill.color = DOT_COLOR
                    canvas.drawCircle(cx, cy, r - maxOf(1.5f, tileW * 0.02f), fill)
                }
            } else for (i in moves.indices.reversed()) {
                val pos = moves[i]
                val stroke = tileW * MOVE_WIDTHS[i]
                paint.color = MOVE_COLORS[i]
                paint.strokeWidth = stroke
                val inset = stroke / 2 + tileW * 0.01f
                val left = tiles.left - ox + (pos % GRID) * tileW
                val top = tiles.top - oy + (pos / GRID) * tileH
                canvas.drawRect(left + inset, top + inset, left + tileW - inset, top + tileH - inset, paint)
            }

            if (message.isNotEmpty()) {
                val b = bannerInWindow
                fill.color = BANNER_BG
                canvas.drawRoundRect(b, b.height() * 0.2f, b.height() * 0.2f, fill)
                text.color = messageColor
                text.textSize = b.height() * 0.6f
                val y = b.centerY() - (text.descent() + text.ascent()) / 2
                canvas.drawText(message, b.centerX(), y, text)
            }
        }
    }
}
