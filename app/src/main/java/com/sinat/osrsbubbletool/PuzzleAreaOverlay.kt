package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import kotlin.math.max
import kotlin.math.min

// A gold 5x5 frame you drag over the puzzle once, so the app knows where the puzzle is on screen.
class PuzzleAreaOverlay(private val context: Context, private val windowManager: WindowManager) {

    private val prefs = context.getSharedPreferences("puzzle_area", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private var frame: FrameView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = frame != null

    // The saved puzzle area in screen pixels, or null if it hasn't been set for this screen size
    fun savedArea(screenWidth: Int, screenHeight: Int): Rect? {
        if (!matchesSavedScreen(screenWidth, screenHeight)) return null
        val left = prefs.getInt("left", 0)
        val top = prefs.getInt("top", 0)
        val size = prefs.getInt("size", 0)
        return Rect(left, top, left + size, top + size)
    }

    // How far an overlay window's position is from the matching screenshot position.
    // Other overlays add this so they line up exactly with what's in the screenshot.
    fun windowOffset(): Point =
        if (prefs.contains("size")) {
            Point(prefs.getInt("winX", 0) - prefs.getInt("left", 0),
                  prefs.getInt("winY", 0) - prefs.getInt("top", 0))
        } else {
            Point(0, 0)
        }

    fun show() {
        if (frame != null) return
        val screen = realScreenSize()
        val saved = matchesSavedScreen(screen.x, screen.y)
        val size = if (saved) prefs.getInt("size", 0) else (min(screen.x, screen.y) * 0.5f).toInt()

        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (saved) prefs.getInt("winX", 0) else (screen.x - size) / 2
            y = if (saved) prefs.getInt("winY", 0) else (screen.y - size) / 2
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val v = FrameView()
        windowManager.addView(v, p)
        frame = v
        params = p
    }

    fun saveAndHide() {
        val v = frame ?: return
        val p = params ?: return
        // where the frame actually sits on screen (this matches screenshot pixels)
        val location = IntArray(2)
        v.getLocationOnScreen(location)
        val screen = realScreenSize()
        prefs.edit {
            putInt("left", location[0])
            putInt("top", location[1])
            putInt("size", v.width)
            putInt("winX", p.x)
            putInt("winY", p.y)
            putInt("screenW", screen.x)
            putInt("screenH", screen.y)
        }
        hide()
    }

    fun hide() {
        frame?.let { windowManager.removeView(it) }
        frame = null
        params = null
    }

    private fun matchesSavedScreen(width: Int, height: Int) =
        prefs.contains("size") &&
            prefs.getInt("screenW", 0) == width &&
            prefs.getInt("screenH", 0) == height

    private fun realScreenSize(): Point {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    // The frame itself: drag the middle to move it, drag the corner triangle to resize it
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class FrameView : View(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#E8C766".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#CCE8C766".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = "#E8C766".toColorInt()
        }
        private val handleSize = 32 * density
        private val corner = Path()   // the resize triangle, reused on every redraw
        private val minSize = (80 * density).toInt()

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startSize = 0
        private var resizing = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            for (i in 1 until 5) {
                canvas.drawLine(w * i / 5, 0f, w * i / 5, h, gridPaint)
                canvas.drawLine(0f, h * i / 5, w, h * i / 5, gridPaint)
            }
            val half = border.strokeWidth / 2
            canvas.drawRect(half, half, w - half, h - half, border)
            corner.apply {
                reset()
                moveTo(w, h - handleSize)
                lineTo(w, h)
                lineTo(w - handleSize, h)
                close()
            }
            canvas.drawPath(corner, handlePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    startSize = p.width
                    resizing = event.x > width - handleSize && event.y > height - handleSize
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (resizing) {
                        val size = max(minSize, startSize + max(dx, dy))
                        p.width = size
                        p.height = size
                    } else {
                        p.x = startX + dx
                        p.y = startY + dy
                    }
                    windowManager.updateViewLayout(this, p)
                }
            }
            return true
        }
    }
}
