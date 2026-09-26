package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.max
import kotlin.math.min

// A gold rectangle you drag over part of the game screen once (like the inventory),
// so the app knows which part of a screenshot to keep. Each area is saved separately.
class RegionOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    prefsName: String,
    private val label: String
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private var frame: FrameView? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = frame != null

    // True if this area has been set at least once
    val hasSavedArea: Boolean get() = prefs.contains("width")

    // The saved area in screen pixels, or null if it hasn't been set for this screen size
    fun savedArea(screenWidth: Int, screenHeight: Int): Rect? {
        if (!matchesSavedScreen(screenWidth, screenHeight)) return null
        val left = prefs.getInt("left", 0)
        val top = prefs.getInt("top", 0)
        return Rect(left, top, left + prefs.getInt("width", 0), top + prefs.getInt("height", 0))
    }

    fun show() {
        if (frame != null) return
        val screen = realScreenSize()
        val saved = matchesSavedScreen(screen.x, screen.y)
        val w = if (saved) prefs.getInt("width", 0) else (min(screen.x, screen.y) * 0.4f).toInt()
        val h = if (saved) prefs.getInt("height", 0) else (min(screen.x, screen.y) * 0.6f).toInt()

        val p = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (saved) prefs.getInt("winX", 0) else (screen.x - w) / 2
            y = if (saved) prefs.getInt("winY", 0) else (screen.y - h) / 2
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
        prefs.edit()
            .putInt("left", location[0])
            .putInt("top", location[1])
            .putInt("width", v.width)
            .putInt("height", v.height)
            .putInt("winX", p.x)
            .putInt("winY", p.y)
            .putInt("screenW", screen.x)
            .putInt("screenH", screen.y)
            .apply()
        hide()
    }

    fun hide() {
        frame?.let { windowManager.removeView(it) }
        frame = null
        params = null
    }

    private fun matchesSavedScreen(width: Int, height: Int) =
        prefs.contains("width") &&
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

    // The frame: drag the middle to move it, drag the corner triangle to resize it
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class FrameView : View(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8C766")
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
        }
        private val fill = Paint().apply { color = Color.parseColor("#22E8C766") }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8C766")
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E8C766")
            textSize = 14 * density
            isFakeBoldText = true
            setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
        }
        private val handleSize = 32 * density
        private val minSize = (60 * density).toInt()

        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0
        private var resizing = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, fill)
            val half = border.strokeWidth / 2
            canvas.drawRect(half, half, w - half, h - half, border)
            canvas.drawText(label, 8 * density, 20 * density, textPaint)
            val corner = Path().apply {
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
                    startW = p.width
                    startH = p.height
                    resizing = event.x > width - handleSize && event.y > height - handleSize
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (resizing) {
                        p.width = max(minSize, startW + dx)
                        p.height = max(minSize, startH + dy)
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
