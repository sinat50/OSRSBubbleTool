package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

// Outlines the next few tiles to tap, drawn on top of the puzzle in the game.
// Taps pass straight through it to the game.
class MoveGuideOverlay(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        private const val GRID = 5

        // Outline colour for move 1, 2, 3, 4
        val MOVE_COLORS = intArrayOf(
            Color.parseColor("#3DDC5A"),  // move 1: green
            Color.parseColor("#FFD60A"),  // move 2: yellow
            Color.parseColor("#FF9500"),  // move 3: orange
            Color.parseColor("#FF3B30")   // move 4: red
        )

        // Outline thickness for move 1, 2, 3, 4, as a fraction of a tile's width.
        // They stay within the outer edge of each tile, which the tracking ignores.
        val MOVE_WIDTHS = floatArrayOf(0.10f, 0.07f, 0.045f, 0.025f)
    }

    private var view: GuideView? = null
    private var moves: List<Int> = emptyList()

    val isShowing: Boolean get() = view != null

    // area: the puzzle's position in screenshot pixels.
    // offset: difference between overlay-window and screenshot coordinates.
    fun show(area: Rect, offsetX: Int, offsetY: Int) {
        hide()
        val params = WindowManager.LayoutParams(
            area.width(), area.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.left + offsetX
            y = area.top + offsetY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val v = GuideView()
        windowManager.addView(v, params)
        view = v
    }

    // The positions (0-24) of the next moves, in order. Up to 4 are shown.
    fun setMoves(nextMoves: List<Int>) {
        moves = nextMoves.take(MOVE_COLORS.size)
        view?.invalidate()
    }

    fun hide() {
        view?.let { windowManager.removeView(it) }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private inner class GuideView : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val tileW = width / GRID.toFloat()
            val tileH = height / GRID.toFloat()
            // draw the later moves first, so move 1 ends up on top
            for (i in moves.indices.reversed()) {
                val pos = moves[i]
                val stroke = tileW * MOVE_WIDTHS[i]
                paint.color = MOVE_COLORS[i]
                paint.strokeWidth = stroke
                val inset = stroke / 2 + tileW * 0.01f
                val left = (pos % GRID) * tileW
                val top = (pos / GRID) * tileH
                canvas.drawRect(left + inset, top + inset, left + tileW - inset, top + tileH - inset, paint)
            }
        }
    }
}
