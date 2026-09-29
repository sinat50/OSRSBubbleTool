package com.sinat.osrsbubbletool

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator

// The 2048 board: draws the tiles, slides them smoothly, and turns swipes into moves.
// It stays square and as big as the space allows.
@SuppressLint("ViewConstructor")
class Game2048View(context: Context, private val game: Game2048, private val onMoved: (Game2048.Move) -> Unit) : View(context) {

    companion object {
        private val BOARD = Color.parseColor("#8B6B3E")
        private val EMPTY = Color.parseColor("#A88D60")
        private val DARK_TEXT = Color.parseColor("#3E2C12")

        // Tile colours: parchment, then oranges and reds, then gold, then purple for the huge ones
        fun colorOf(v: Int): Int = Color.parseColor(when (v) {
            2 -> "#F3E6C4"
            4 -> "#EAD39C"
            8 -> "#E8AE62"
            16 -> "#E28F48"
            32 -> "#DA6E3A"
            64 -> "#C94B2C"
            128 -> "#E9C75E"
            256 -> "#E3B943"
            512 -> "#DBAA2C"
            1024 -> "#D19D1C"
            2048 -> "#C8900E"
            else -> "#6A3FA0"
        })
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()

    // The move being animated (null = just draw the board)
    private var move: Game2048.Move? = null
    private var progress = 1f
    private var animator: ValueAnimator? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = when {
            MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED -> w
            else -> minOf(w, h)
        }
        setMeasuredDimension(s, s)
    }

    // ---------------- Swipes ----------------

    private var downX = 0f
    private var downY = 0f
    private var swiped = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; swiped = false
                parent?.requestDisallowInterceptTouchEvent(true)   // don't let a scroll view steal the swipe
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (!swiped) {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    // react as soon as the finger has clearly moved, so it feels snappy
                    if (Math.max(Math.abs(dx), Math.abs(dy)) > dp(22f)) {
                        swiped = true
                        val dir = if (Math.abs(dx) > Math.abs(dy)) {
                            if (dx > 0) Game2048.Dir.RIGHT else Game2048.Dir.LEFT
                        } else {
                            if (dy > 0) Game2048.Dir.DOWN else Game2048.Dir.UP
                        }
                        swipe(dir)
                    }
                }
            }
        }
        return true
    }

    private fun swipe(dir: Game2048.Dir) {
        animator?.end()   // finish the last move straight away
        val m = game.move(dir) ?: return
        move = m
        onMoved(m)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // A new game or a restored one: no animation
    fun refresh() {
        animator?.cancel()
        move = null
        progress = 1f
        invalidate()
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = game.size
        val s = width.toFloat()
        val gap = s * 0.025f
        val cell = (s - gap * (n + 1)) / n
        val radius = cell * 0.1f

        paint.color = BOARD
        rect.set(0f, 0f, s, s)
        canvas.drawRoundRect(rect, radius * 1.5f, radius * 1.5f, paint)
        paint.color = EMPTY
        for (i in 0 until n * n) {
            val x = gap + (i % n) * (cell + gap)
            val y = gap + (i / n) * (cell + gap)
            rect.set(x, y, x + cell, y + cell)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        val m = move
        if (m != null && progress < 0.5f) {
            // first half: every tile slides from where it was to where it's going
            val t = easeOut(progress / 0.5f)
            for (sl in m.slides) {
                val fx = sl.from % n; val fy = sl.from / n
                val tx = sl.to % n; val ty = sl.to / n
                val x = gap + (fx + (tx - fx) * t) * (cell + gap)
                val y = gap + (fy + (ty - fy) * t) * (cell + gap)
                tile(canvas, sl.value, x, y, cell, radius, 1f)
            }
            return
        }
        // second half (or no move): the board as it is now, with new and merged tiles popping in
        val q = if (m == null) 1f else (progress - 0.5f) / 0.5f
        for (i in 0 until n * n) {
            val v = game.cells[i]
            if (v == 0) continue
            val scale = when {
                m == null -> 1f
                i == m.spawned -> easeOut(q)                                     // grows from nothing
                i in m.merged -> 1f + 0.15f * Math.sin(Math.PI * q).toFloat()     // a little bounce
                else -> 1f
            }
            if (scale <= 0.01f) continue
            tile(canvas, v, gap + (i % n) * (cell + gap), gap + (i / n) * (cell + gap), cell, radius, scale)
        }
    }

    private fun tile(canvas: Canvas, v: Int, x: Float, y: Float, cell: Float, radius: Float, scale: Float) {
        val c = cell * scale
        val ox = x + (cell - c) / 2
        val oy = y + (cell - c) / 2
        paint.color = colorOf(v)
        rect.set(ox, oy, ox + c, oy + c)
        canvas.drawRoundRect(rect, radius * scale, radius * scale, paint)
        val label = v.toString()
        text.color = if (v <= 4) DARK_TEXT else Color.WHITE
        text.textSize = c * when (label.length) { 1, 2 -> 0.46f; 3 -> 0.38f; 4 -> 0.3f; else -> 0.24f }
        canvas.drawText(label, ox + c / 2, oy + c / 2 - (text.descent() + text.ascent()) / 2, text)
    }

    private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t)

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
