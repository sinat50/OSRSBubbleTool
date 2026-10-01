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

// Drawn on top of the light box in the game: instructions in its title bar,
// and outlines around the buttons to press. Taps pass straight through to the game.
class LightBoxGuideOverlay(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        val GREEN = "#3DDC5A".toColorInt()
        val YELLOW = "#FFD60A".toColorInt()
        private val BANNER_BG = "#E6201A10".toColorInt()
    }

    private var view: GuideView? = null
    private var params: WindowManager.LayoutParams? = null
    private var layout: LightBoxReader.Layout? = null
    private var area = Rect()
    private var buttonBoxes: List<RectF> = emptyList()   // the 8 buttons, in overlay pixels
    private var titleBox = RectF()                        // the title bar, in overlay pixels

    private var message = ""
    private var messageColor = Color.WHITE
    private var buttons: List<Int> = emptyList()
    private var buttonColor = GREEN

    val isShowing: Boolean get() = view != null

    // Covers the light box window. Positions are in screenshot pixels.
    fun show(layout: LightBoxReader.Layout) {
        hide()
        this.layout = layout
        area = layout.windowRect()
        // where the buttons and the title bar are inside the overlay, worked out once rather than on every redraw
        buttonBoxes = List(8) { RectF(layout.buttonRect(it)).apply { offset(-area.left.toFloat(), -area.top.toFloat()) } }
        titleBox = RectF(layout.titleRect()).apply { offset(-area.left.toFloat(), -area.top.toFloat()) }
        val p = WindowManager.LayoutParams(
            area.width(), area.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.left
            y = area.top
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

    // What to show: a message in the title bar, and which buttons (0-7 = A-H) to outline
    fun set(message: String, messageColor: Int, buttons: List<Int>, buttonColor: Int) {
        this.message = message
        this.messageColor = messageColor
        this.buttons = buttons
        this.buttonColor = buttonColor
        view?.invalidate()
    }

    fun hide() {
        view?.let { windowManager.removeView(it) }
        view = null
        params = null
    }

    @SuppressLint("ViewConstructor")
    private inner class GuideView : View(context) {
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
                if (p != null && (loc[0] != area.left || loc[1] != area.top)) {
                    p.x += area.left - loc[0]
                    p.y += area.top - loc[1]
                    windowManager.updateViewLayout(this, p)
                }
            }
            val l = layout ?: return

            // button outlines
            box.color = buttonColor
            box.strokeWidth = l.spacing * 0.07f
            val half = box.strokeWidth / 2
            for (b in buttons) {
                val r = buttonBoxes.getOrNull(b) ?: continue
                canvas.drawRoundRect(r.left + half, r.top + half, r.right - half, r.bottom - half,
                    l.spacing * 0.05f, l.spacing * 0.05f, box)
            }

            // message in the title bar
            if (message.isNotEmpty()) {
                val t = titleBox
                fill.color = BANNER_BG
                canvas.drawRoundRect(t, l.spacing * 0.08f, l.spacing * 0.08f, fill)
                text.color = messageColor
                text.textSize = t.height() * 0.55f
                val y = t.centerY() - (text.descent() + text.ascent()) / 2
                canvas.drawText(message, t.centerX(), y, text)
            }
        }
    }
}
