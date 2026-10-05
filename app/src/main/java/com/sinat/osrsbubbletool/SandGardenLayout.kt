package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isGone
import kotlin.math.max
import kotlin.math.min

// Zen mode: the Zen Garden screen. No window around it, just the tray in its wooden frame, with the window's buttons
// carved into the frame's corners (see SandGardenView.drawFrame): Exit in the top left, and the arrow in the bottom
// left that opens the tools and settings (a row of round icons along the bottom) and closes them again. They stay
// open or closed until the arrow is tapped again. (The move and resize corners, top and bottom right, are the
// window's own: see BubbleService.applyFrameless.)
@SuppressLint("ViewConstructor")
class ZenScreen(
    context: Context,
    private val garden: SandGardenView,   // the tray of sand, filling the screen
    private val dock: View,               // the icons, with any open slider and the pattern's name above them
) : FrameLayout(context) {

    var onExit: (() -> Unit)? = null                  // the Exit corner was tapped
    var onHide: (() -> Unit)? = null                  // the tools closed (close any open slider)
    var open = false                                  // the tools are showing
        private set
    private var pressed = 0                           // the carved corner this touch began on (Exit or the arrow)
    private val tray = android.graphics.RectF()
    var onTrayPlaced: ((Int, Int, Int, Int) -> Unit)? = null   // the tray's edges here (left, top, right, bottom)
    private val placed = IntArray(4)

    init {
        addView(garden, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dock, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
        dock.visibility = GONE
    }

    // Opens or closes the tools (the arrow in the corner turns to match)
    fun setOpen(on: Boolean) {
        if (open == on) return
        open = on
        garden.dockOpen = on
        dock.animate().cancel()
        if (on) {
            dock.alpha = 0f
            dock.visibility = VISIBLE
            dock.animate().alpha(1f).setDuration(150).start()
        } else {
            onHide?.invoke()
            dock.animate().alpha(0f).setDuration(150).withEndAction { if (!open) dock.visibility = GONE }.start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // the carved Exit and arrow corners: a tap there (and letting go there) is a press of the button
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val c = garden.cornerAt(ev.x, ev.y)
            pressed = if (c == SandGardenView.CORNER_EXIT || c == SandGardenView.CORNER_TOOLS) c else 0
        }
        if (pressed != 0) {
            if (ev.actionMasked == MotionEvent.ACTION_UP && garden.cornerAt(ev.x, ev.y) == pressed) {
                if (pressed == SandGardenView.CORNER_EXIT) onExit?.invoke() else setOpen(!open)
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) pressed = 0
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // The tools sit along the bottom of the sand, between the two bottom corner blocks (the tray doesn't fill the
    // window when the garden is scaled to fit a window of a different shape)
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        garden.trayBounds(tray)
        if (tray.width() <= 0f) return
        val corner = garden.cornerSize.toInt()
        val edge = (12 * resources.displayMetrics.density).toInt()   // (clear of the frame)
        val l = tray.left.toInt() + corner
        val w = max(0, tray.width().toInt() - 2 * corner)
        val b = tray.bottom.toInt() - edge
        dock.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(0, tray.height().toInt() - 2 * edge), MeasureSpec.AT_MOST))
        dock.layout(l, b - dock.measuredHeight, l + dock.measuredWidth, b)
        // tell the window, so its move and resize corners sit on the tray's corners (after this layout is done)
        val t = tray.top.toInt()
        val r = tray.right.toInt()
        val tb = tray.bottom.toInt()
        val tl = tray.left.toInt()
        if (placed[0] != tl || placed[1] != t || placed[2] != r || placed[3] != tb) {
            placed[0] = tl; placed[1] = t; placed[2] = r; placed[3] = tb
            post { onTrayPlaced?.invoke(tl, t, r, tb) }
        }
    }
}

// A round icon for the Zen Garden's controls, drawn in code (sharp at any size): the tools, Undo, Smooth,
// brightness, volume, settings and help.
@SuppressLint("ViewConstructor")
class ZenIcon(context: Context, kind: Int, private val fill: Int) : View(context) {

    companion object {
        // the tools use their own numbers (SandGardenView.RAKE and so on); these are the rest
        const val UNDO = 21
        const val SMOOTH = 22
        const val LIGHT = 23
        const val VOLUME = 24
        const val GEAR = 25
        const val HELP = 26
    }

    var kind = kind
        set(v) { field = v; rebuild(); invalidate() }
    var muted = false                      // the volume icon shows a cross
        set(v) { field = v; rebuild(); invalidate() }

    private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val ink = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val solid = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val lines = android.graphics.Path()     // drawn as lines
    private val shapes = android.graphics.Path()    // filled in

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    // The drawing, in a box from -1 to 1 each way around the middle
    private fun rebuild() {
        lines.reset(); shapes.reset()
        shapes.fillType = android.graphics.Path.FillType.WINDING
        val c = width / 2f
        val g = width * 0.27f
        if (g <= 0f) return
        ink.strokeWidth = width * 0.075f
        text.textSize = width * 0.5f
        fun x(v: Float) = c + v * g
        fun y(v: Float) = c + v * g
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) { lines.moveTo(x(x0), y(y0)); lines.lineTo(x(x1), y(y1)) }
        fun dot(cx: Float, cy: Float, r: Float) = shapes.addCircle(x(cx), y(cy), r * g, android.graphics.Path.Direction.CW)
        fun poly(vararg p: Float) {
            shapes.moveTo(x(p[0]), y(p[1]))
            for (i in 2 until p.size step 2) shapes.lineTo(x(p[i]), y(p[i + 1]))
            shapes.close()
        }
        // an arc around (cx, cy) with a filled arrowhead at its end, pointing the way it turns
        fun arrowArc(cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            lines.addArc(x(cx - r), y(cy - r), x(cx + r), y(cy + r), start, sweep)
            val end = Math.toRadians((start + sweep).toDouble())
            val ex = cx + r * kotlin.math.cos(end).toFloat()
            val ey = cy + r * kotlin.math.sin(end).toFloat()
            val turn = if (sweep > 0) 1f else -1f
            val dx = -kotlin.math.sin(end).toFloat() * turn
            val dy = kotlin.math.cos(end).toFloat() * turn
            poly(ex + dx * 0.4f, ey + dy * 0.4f, ex - dy * 0.3f, ey + dx * 0.3f, ex + dy * 0.3f, ey - dx * 0.3f)
        }
        when (kind) {
            SandGardenView.RAKE -> {
                line(-1f, 0.1f, 1f, 0.1f)
                for (t in floatArrayOf(-0.8f, -0.4f, 0f, 0.4f, 0.8f)) line(t, 0.1f, t, 0.6f)
                line(0f, 0.1f, 0f, -1f)
            }
            SandGardenView.STICK -> { line(-0.8f, 0.8f, 0.8f, -0.8f); dot(-0.8f, 0.8f, 0.17f) }
            SandGardenView.BRUSH -> {
                line(0f, -1f, 0f, 0f)
                shapes.addRoundRect(x(-0.65f), y(-0.05f), x(0.65f), y(0.35f), g * 0.1f, g * 0.1f, android.graphics.Path.Direction.CW)
                for (t in floatArrayOf(-0.45f, -0.15f, 0.15f, 0.45f)) line(t, 0.35f, t, 0.9f)
            }
            SandGardenView.STONE -> shapes.addOval(x(-0.9f), y(-0.6f), x(0.9f), y(0.6f), android.graphics.Path.Direction.CW)
            SandGardenView.FLOWERS -> {
                // five round petals around a hollow centre
                for (k in 0 until 5) {
                    val a = Math.toRadians(k * 72.0 - 90)
                    dot((0.52 * kotlin.math.cos(a)).toFloat(), (0.52 * kotlin.math.sin(a)).toFloat(), 0.36f)
                }
                lines.addCircle(x(0f), y(0f), 0.2f * g, android.graphics.Path.Direction.CW)
            }
            SandGardenView.LANTERN -> {
                for (k in 0 until 6) {
                    val a0 = Math.toRadians(k * 60.0 + 30); val a1 = Math.toRadians(k * 60.0 + 90)
                    line((0.9 * kotlin.math.cos(a0)).toFloat(), (0.9 * kotlin.math.sin(a0)).toFloat(),
                        (0.9 * kotlin.math.cos(a1)).toFloat(), (0.9 * kotlin.math.sin(a1)).toFloat())
                }
                dot(0f, 0f, 0.28f)
            }
            SandGardenView.BONSAI -> { line(0f, 0.9f, 0f, 0.1f); dot(-0.45f, -0.05f, 0.4f); dot(0.45f, -0.05f, 0.4f); dot(0f, -0.45f, 0.45f) }
            SandGardenView.FIREFLY -> {
                // a soft four-pointed sparkle, with two little ones beside it
                poly(0f, -0.95f, 0.2f, -0.2f, 0.95f, 0f, 0.2f, 0.2f, 0f, 0.95f, -0.2f, 0.2f, -0.95f, 0f, -0.2f, -0.2f)
                dot(0.68f, -0.62f, 0.13f)
                dot(-0.62f, 0.7f, 0.1f)
            }
            UNDO -> arrowArc(0.05f, 0.2f, 0.65f, 0f, -200f)
            SMOOTH -> {
                // a chunky eraser, tilted: its rubber end filled in, its sleeve outlined, and a few crumbs
                val ang = Math.toRadians(-40.0)
                val ca = kotlin.math.cos(ang).toFloat()
                val sa = kotlin.math.sin(ang).toFloat()
                fun ex(u: Float, v: Float) = 0.05f + u * ca - v * sa
                fun ey(u: Float, v: Float) = -0.08f + u * sa + v * ca
                val len = 0.85f
                val wide = 0.48f
                val split = -0.1f
                poly(ex(-len, -wide), ey(-len, -wide), ex(split, -wide), ey(split, -wide),
                    ex(split, wide), ey(split, wide), ex(-len, wide), ey(-len, wide))
                lines.moveTo(x(ex(split, -wide)), y(ey(split, -wide)))
                lines.lineTo(x(ex(len, -wide)), y(ey(len, -wide)))
                lines.lineTo(x(ex(len, wide)), y(ey(len, wide)))
                lines.lineTo(x(ex(split, wide)), y(ey(split, wide)))
                dot(-0.85f, 0.78f, 0.08f); dot(-0.5f, 0.9f, 0.07f); dot(-0.18f, 0.86f, 0.06f)
            }
            LIGHT -> {
                dot(0f, 0f, 0.38f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    line((0.62 * kotlin.math.cos(a)).toFloat(), (0.62 * kotlin.math.sin(a)).toFloat(),
                        (0.95 * kotlin.math.cos(a)).toFloat(), (0.95 * kotlin.math.sin(a)).toFloat())
                }
            }
            VOLUME -> {
                poly(-0.9f, -0.3f, -0.5f, -0.3f, 0f, -0.75f, 0f, 0.75f, -0.5f, 0.3f, -0.9f, 0.3f)
                if (muted) { line(0.3f, -0.35f, 0.9f, 0.35f); line(0.3f, 0.35f, 0.9f, -0.35f) }
                else {
                    lines.addArc(x(-0.35f), y(-0.4f), x(0.45f), y(0.4f), -50f, 100f)
                    lines.addArc(x(-0.75f), y(-0.8f), x(0.85f), y(0.8f), -50f, 100f)
                }
            }
            GEAR -> {
                // eight teeth around a ring, with a hole in the middle
                for (k in 0 until 32) {
                    val a = Math.toRadians(k * 360.0 / 32 - 360.0 / 64)
                    val r = if (k % 4 < 2) 0.95 else 0.7
                    val px = (r * kotlin.math.cos(a)).toFloat(); val py = (r * kotlin.math.sin(a)).toFloat()
                    if (k == 0) shapes.moveTo(x(px), y(py)) else shapes.lineTo(x(px), y(py))
                }
                shapes.close()
                shapes.addCircle(x(0f), y(0f), 0.32f * g, android.graphics.Path.Direction.CW)
                shapes.fillType = android.graphics.Path.FillType.EVEN_ODD   // the middle is a hole
            }
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val c = width / 2f
        back.color = fill
        canvas.drawCircle(c, c, c, back)
        canvas.drawPath(shapes, solid)
        canvas.drawPath(lines, ink)
        if (kind == HELP) canvas.drawText("?", c, c - (text.descent() + text.ascent()) / 2, text)
    }
}

// A row of controls that wraps onto more lines when it doesn't fit, then stretches each line to fill the width.
// How much each control stretches is its `tag` (a Float; none = 1, 0 = keeps its natural width).
class FlowRow(context: Context) : ViewGroup(context) {

    private val gap = (4 * context.resources.displayMetrics.density).toInt()
    private var placeX = IntArray(0)          // where each control goes (worked out while measuring)
    private var placeY = IntArray(0)
    private var natural = IntArray(0)         // each control's own width

    private fun stretch(v: View) = (v.tag as? Float) ?: 1f

    // A control given a fixed size keeps it; otherwise it's as big as it needs to be
    private fun sizeSpec(size: Int) =
        if (size > 0) MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    // (the arrays are only made again when the row gets more controls than before)
    @SuppressLint("DrawAllocation")
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val n = childCount
        if (placeX.size < n) { placeX = IntArray(n); placeY = IntArray(n); natural = IntArray(n) }
        for (i in 0 until n) {
            val c = getChildAt(i)
            if (c.isGone) continue
            c.measure(sizeSpec(c.layoutParams?.width ?: 0), sizeSpec(c.layoutParams?.height ?: 0))
            natural[i] = min(max(c.measuredWidth, c.minimumWidth), width)
        }
        var y = paddingTop
        var i = 0
        while (i < n) {
            // gather as many controls as fit on this line (always at least one)
            val start = i
            var used = 0
            var count = 0
            var weights = 0f
            while (i < n) {
                val c = getChildAt(i)
                if (c.isGone) { i++; continue }
                val need = natural[i] + if (count > 0) gap else 0
                if (count > 0 && used + need > width) break
                used += need
                weights += stretch(c)
                count++
                i++
            }
            // share out the spare width, then measure each at its final size
            val spare = max(0, width - used)
            var x = paddingLeft
            var lineH = 0
            for (k in start until i) {
                val c = getChildAt(k)
                if (c.isGone) continue
                val extra = if (weights > 0f) (spare * stretch(c) / weights).toInt() else 0
                c.measure(MeasureSpec.makeMeasureSpec(natural[k] + extra, MeasureSpec.EXACTLY), sizeSpec(c.layoutParams?.height ?: 0))
                placeX[k] = x
                x += c.measuredWidth + gap
                lineH = max(lineH, c.measuredHeight)
            }
            // centre each control on its line
            for (k in start until i) {
                val c = getChildAt(k)
                if (!c.isGone) placeY[k] = y + (lineH - c.measuredHeight) / 2
            }
            y += lineH + gap
        }
        val height = if (y > paddingTop) y - gap + paddingBottom else paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isGone) continue
            c.layout(placeX[i], placeY[i], placeX[i] + c.measuredWidth, placeY[i] + c.measuredHeight)
        }
    }
}
