package com.sinat.osrsbubbletool

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The Zen Garden: a little tray of sand to rake lines in, with stones, moss, lanterns and bonsai to place.
// The sand isn't separate grains: it's a grid of heights, one square per dp. The rake sets the heights it passes
// over to grooves (under each tooth) and ridges (between them), and a light shades the slopes and casts short
// shadows from the ridges, which is what makes it look like sand. A slider makes it darker or brighter. Lanterns have a flame inside: its light is worked
// out square by square (slopes facing it catch it, ridges and stones cast shadows away from it, the fire box's
// windows and corner posts shape it) and kept as its own layer, so the faint flicker only changes how bright that
// layer is drawn, without redrawing the sand. The light can follow the real time of day.
// Only the squares the rake touched are redrawn, and nothing runs while your finger is still (the sound stops
// too), so it costs no battery when you're not using it.
// The garden is saved to a small file when the window closes (it isn't backed up: it's only sand).
@SuppressLint("ViewConstructor")
class SandGardenView(context: Context, private val saveFile: File, var tool: Int) : View(context) {

    companion object {
        // The tools: three for drawing in the sand, then the things you can place
        const val RAKE = 0
        const val STICK = 1
        const val BRUSH = 2
        const val STONE = 3
        const val FLOWERS = 4                     // (was moss: saved moss comes back as flowers)
        const val LANTERN = 5
        const val BONSAI = 6
        const val FIREFLY = 7
        val TOOL_NAMES = listOf("Rake", "Stick", "Brush", "Stone", "Flowers", "Lantern", "Bonsai", "Fireflies")

        // The sands: shadow, plain sand, and the sunny side of a ridge
        val SAND_NAMES = listOf("Beige", "White", "Grey", "Black")
        val SANDS = arrayOf(
            intArrayOf("#8A7148".toColorInt(), "#DCC594".toColorInt(), "#FFF6DE".toColorInt()),
            intArrayOf("#B9B5AC".toColorInt(), "#F5F3EE".toColorInt(), "#FFFFFF".toColorInt()),
            intArrayOf("#5E5E5B".toColorInt(), "#ABAAA5".toColorInt(), "#E4E3DE".toColorInt()),
            intArrayOf("#121212".toColorInt(), "#3B3936".toColorInt(), "#7A766F".toColorInt()),
        )

        private const val MAX_ITEMS = 16
        private const val MAX_UNDO = 5
        private const val MAX_RINGS = 6
        private const val MAX_FLIES = 12
        private const val LANTERN_SIZE = 10f      // a lantern's roof, in squares from its middle to a corner
        private const val LAMP_GLOW = 13f         // the size its light is worked out for (kept from the bigger lanterns)
        private const val LAMP_SIZE = 2 * 91 + 1  // each lantern's light picture, in squares (its reach, 7 x 13, each way)
        private const val GRID = 32f              // the triangle grid's spacing, in squares (dp): still easy to hit with a finger
        // the grid in dark brown on the light sands, and in white on the black sand, so it always shows
        private val GRID_LINE = Color.argb(75, 70, 50, 25)
        private val GRID_POINT = Color.argb(150, 70, 50, 25)
        private val GRID_LINE_ON_DARK = Color.argb(75, 255, 255, 255)
        private val GRID_POINT_ON_DARK = Color.argb(150, 255, 255, 255)
        private const val FLY_LOW = 24f           // how high fireflies fly above the sand, in squares, and how
        private const val FLY_HIGH = 32f          // high they climb while glowing (the 'J' of a real firefly's flash)
        private const val FLY_REACH = 64          // how far a firefly's light reaches, in squares
        // The auto rake's patterns, in the order it rakes them
        val AUTO_PATTERNS = listOf("Rows", "Seed of Life", "Spiral", "Lotus", "Waves", "Golden Spiral", "Ripples")   // (raked in random order)
        private const val RAKE_SPEED = 30f        // squares (dp) a second: a slow, steady walking pace
        private const val STICK_SPEED = 40f
        private const val SMOOTH_SECONDS = 4f
        private const val LIFT_SECONDS = 1.2f
        private const val REST_SECONDS = 6f
        private val HANDLE = "#A07E4E".toColorInt()
        private val SHADOW_SOFT = Color.argb(70, 0, 0, 0)
        private const val DEPTH = 1f              // how deep a groove is (and how high a ridge), in squares
        private val WOOD = "#6B4B24".toColorInt()
        private val WOOD_EDGE = "#4A3518".toColorInt()
        private val FRAME_MARK = "#A88A5C".toColorInt()   // the little arrow carved into the frame
        private val STONE_COLORS = intArrayOf(
            "#8E8A84".toColorInt(), "#7A746C".toColorInt(), "#A39C92".toColorInt(), "#6E6A66".toColorInt(),
        )
        private val LANTERN_GREY = "#8F8B83".toColorInt()
        private val JEWEL_GREY = "#77736D".toColorInt()
        private val POT_COLORS = intArrayOf("#3E5A78".toColorInt(), "#6B4B33".toColorInt(), "#7A3B2E".toColorInt())
        private val SOIL = "#3B2E22".toColorInt()
        private val LEAF_DARK = "#2F4A22".toColorInt()
        private val LEAF_LIGHT = "#6F8F4A".toColorInt()
        // the flowers' colours: blush pink, cream, lavender and pale yellow, with golden centres
        private val PETALS = intArrayOf("#ECAABD".toColorInt(), "#F1ECE2".toColorInt(), "#B9A6D8".toColorInt(), "#EED67A".toColorInt())
        private val FLOWER_EYE = "#E8BE46".toColorInt()
        private val FLOWER_EYE_RING = "#B08020".toColorInt()
        private val PINE_DEEP = "#17260F".toColorInt()
        private val PINE_TIP = "#64843F".toColorInt()
        private val PETAL_SCALE = floatArrayOf(1f, 0.72f, 0.42f)
        private val PETAL_SHIFT = floatArrayOf(0f, 0.18f, 0.3f)
        private val PETAL_TONE = floatArrayOf(0.84f, 1f, 1.12f)
        private const val OBJ_STEPS = 12          // the most steps a ridge's shadow is looked for, in squares
        // how far out the lantern light looks for things blocking it, in squares
        private val LAMP_STEPS = floatArrayOf(1f, 2f, 3f, 4f, 6f, 8f, 11f, 15f, 20f, 26f, 33f, 41f, 50f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---------------- Settings (set by the Game Room) ----------------

    var soundOn = true
    var volume = 1f                          // 1 = normal
        set(v) { field = v; sound.volume = v }
    // Scale to fit (on by default): the garden keeps its squares and is drawn bigger or smaller to fit the
    // window, centred, with clear space at the sides if the window is a different shape. Off: one square per dp,
    // so resizing adds sand or cuts it off.
    var scaleToFit = true
        set(v) {
            if (field == v) return
            field = v
            if (width > 0) onSizeChanged(width, height, width, height)
            requestLayout()   // so the icons move to the tray's new corner
        }
    var onDragging: (() -> Unit)? = null     // a stroke or a drag has started (the screen hides its controls)
    var tapUsed = false                      // the last tap placed, took away or let out something
        private set
    var steadyHand = false
    // The triangle grid: shown over the sand, and anything placed or dragged snaps to its points
    var gridOn = false
        set(v) { field = v; invalidate() }
    var sand = 0
        set(v) { field = v.coerceIn(0, SANDS.size - 1); updateLight() }
    // How bright the garden is, from 0 (dark as night, lanterns glowing) to 1 (full daylight, lanterns faint).
    // Only the wash over the garden and the strength of the lantern light change, so sliding it is cheap.
    var brightness = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            tint = Color.argb(((1 - field) * 150).toInt(), 20, 30, 60)
            lampPower = 0.3f + 0.7f * (1 - field)
            invalidate()
        }
    var onUndoChanged: (() -> Unit)? = null
    val canUndo get() = undoStack.isNotEmpty()

    // ---------------- The sand ----------------

    private var gw = 0                       // grid size, in squares
    private var gh = 0
    private var heights = FloatArray(0)
    private var colors = IntArray(0)         // the shaded sand, one colour per square
    private var bitmap: Bitmap? = null
    private var covered = BooleanArray(0)    // squares something is placed on: the rake leaves them alone
    private var cell = 1f                    // size of one square on screen, in pixels
    private var inset = 0f                   // the wooden frame's width
    private var changed = false              // anything to save?

    private val shades = IntArray(256)       // sand colours from deep shadow (0) through plain (128) to sunny (255)

    // A fine speckle drawn over the sand at full screen sharpness, so it looks grainy rather than smooth
    private val grainPaint = Paint().apply {
        val n = 96
        val rnd = Random(7)
        val px = IntArray(n * n) {
            val a = rnd.nextInt(30)
            if (rnd.nextBoolean()) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255)
        }
        shader = BitmapShader(Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
    private val sandPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sandRect = RectF()
    private val gridRect = RectF()

    // Waves for freshly smoothed sand (a smoothed tray is never perfectly flat)
    private var wave = FloatArray(4) { Random.nextFloat() * 6.28f }

    private fun freshSand(x: Int, y: Int): Float =
        0.14f * sin(x * 0.045f + wave[0]) * sin(y * 0.05f + wave[1]) + 0.07f * sin((x + y) * 0.09f + wave[2]) + 0.05f * sin((x - y) * 0.13f + wave[3])

    private var offX = 0f                    // where the tray sits in this view (when scaled to fit)
    private var offY = 0f
    private var trayW = 0f                   // the tray's size on screen, frame included
    private var trayH = 0f

    // Where the tray is drawn in this view (it may not fill it when scaled to fit)
    fun trayBounds(out: RectF) = out.set(offX, offY, offX + trayW, offY + trayH)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        inset = dp(7f)
        val first = bitmap == null
        val saved = if (first) load() else null
        var regrown = first
        if (scaleToFit) {
            // the garden keeps its own size (the saved one, or the window's at one square per dp to start with)
            if (first) {
                if (saved != null) makeGrid(saved.first, saved.second, saved.first, saved.second, saved.third)
                else makeGrid(max(1, ((w - 2 * inset) / dp(1f)).toInt()), max(1, ((h - 2 * inset) / dp(1f)).toInt()), 0, 0, FloatArray(0))
            }
            cell = min((w - 2 * inset) / gw, (h - 2 * inset) / gh).coerceAtLeast(0.01f)
            trayW = gw * cell + 2 * inset
            trayH = gh * cell + 2 * inset
            offX = (w - trayW) / 2
            offY = (h - trayH) / 2
        } else {
            cell = dp(1f)
            offX = 0f; offY = 0f
            trayW = w.toFloat(); trayH = h.toFloat()
            val nw = ceil((w - 2 * inset) / cell).toInt()
            val nh = ceil((h - 2 * inset) / cell).toInt()
            if (nw <= 0 || nh <= 0) return
            if (first) {
                if (saved != null) makeGrid(nw, nh, saved.first, saved.second, saved.third) else makeGrid(nw, nh, 0, 0, FloatArray(0))
            } else if (nw != gw || nh != gh) {
                // the window is being resized during Auto: pause, and start the round again fitted to the new size
                // once the resizing has stopped for half a second
                if (autoOn) {
                    autoResizing = true
                    removeCallbacks(autoFrame)
                    removeCallbacks(autoAfterResize)
                    postDelayed(autoAfterResize, 500)
                    sound.level(0f)
                }
                anim?.end()
                makeGrid(nw, nh, gw, gh, heights)
                regrown = true
            }
        }
        sandRect.set(inset, inset, inset + gw * cell, inset + gh * cell)
        gridRect.set(sandRect)
        if (regrown) {
            undoStack.clear()   // the old grid size doesn't fit any more
            onUndoChanged?.invoke()
            markCovered()
            updateLight()   // works out the light, then shades the whole tray
            // the saved garden's fireflies, let out somewhere over the sand
            repeat(savedFlies) { flies.add(Firefly(Random.nextFloat() * gw, Random.nextFloat() * gh).apply { glowState = 0; glowLength = Random.nextFloat() * 3f }) }
            savedFlies = 0
            startFlies()
        } else {
            items.forEach { makeLooks(it) }   // only the drawing size changed
            invalidate()
        }
    }

    // A new grid of sand, nw by nh squares, keeping what's in `old` (oldW by oldH) where it fits
    private fun makeGrid(nw: Int, nh: Int, oldW: Int, oldH: Int, old: FloatArray) {
        val fresh = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            fresh[y * nw + x] = if (x < oldW && y < oldH) old[y * oldW + x] else freshSand(x, y)
        }
        gw = nw; gh = nh; heights = fresh
        colors = IntArray(nw * nh)
        bitmap?.recycle()
        bitmap = createBitmap(nw, nh)
    }

    // ---------------- The light ----------------

    private val lightX = -0.7071f            // which way the light is, on screen (from the top-left)
    private val lightY = -0.7071f
    private val sunTan = 0.39f               // how high the sun is (lower = longer shadows)
    private var shadowSteps = 6              // how far a ridge's shadow can reach, in squares
    private val stepDX = IntArray(OBJ_STEPS + 1)
    private val stepDY = IntArray(OBJ_STEPS + 1)
    private val stepDrop = FloatArray(OBJ_STEPS + 1)
    private var tint = 0                     // a dark blue washed over the garden (none at full brightness)
    private var lampPower = 0.3f             // how strongly the lanterns' light shows: faint in daylight, full in the dark

    // A fixed afternoon light from the top-left: works out the shadows' reach and the sand's colours, then shades
    // the whole tray (when the garden opens, its size changes, or the sand colour changes)
    private fun updateLight() {
        shadowSteps = ceil(2.2f / sunTan).toInt().coerceIn(3, 11)
        for (k in 1..OBJ_STEPS) {
            stepDX[k] = (k * lightX).roundToInt()
            stepDY[k] = (k * lightY).roundToInt()
            stepDrop[k] = k * sunTan
        }
        val sandColors = SANDS[sand]
        for (i in 0 until 256) {
            shades[i] = if (i < 128) ColorUtils.blendARGB(sandColors[0], sandColors[1], i / 128f)
            else ColorUtils.blendARGB(sandColors[1], sandColors[2], (i - 128) / 127f)
        }
        items.forEach { makeLooks(it) }
        if (bitmap != null) {
            shade(0, 0, gw - 1, gh - 1)
            relight(0, 0, gw - 1, gh - 1)
            invalidate()
        }
    }

    // Colours the squares in a box from their heights: slopes facing the light are lit, the others shaded.
    // Placed things are part of the same surface (their own height and colour), so they're lit and shaded just
    // like it (they don't cast shadows from this light, though: only the sand's ridges do).
    private fun shade(x0: Int, y0: Int, x1: Int, y1: Int) {
        val bmp = bitmap ?: return
        val ax = max(0, x0); val ay = max(0, y0); val bx = min(gw - 1, x1); val by = min(gh - 1, y1)
        if (ax > bx || ay > by) return
        val h = heights
        val lx = lightX * 1.414f
        val ly = lightY * 1.414f
        val steps = shadowSteps
        for (y in ay..by) {
            val up = if (y > 0) -gw else 0
            val down = if (y < gh - 1) gw else 0
            for (x in ax..bx) {
                val i = y * gw + x
                val n = hash(x, y)
                val own = surface(i)
                // a shadow from any ridge of sand towards the light that stands higher than the light reaches
                // (placed things don't cast these: they'd only clutter the garden)
                var shadow = 0f
                for (k in 1..steps) {
                    val sx = x + stepDX[k]
                    val sy = y + stepDY[k]
                    if (sx < 0 || sy < 0 || sx >= gw || sy >= gh) break
                    val j = sy * gw + sx
                    if (objAlbedo[j] != 0) continue
                    shadow = max(shadow, heights[j] - own - stepDrop[k])
                }
                val alb = objAlbedo[i]
                if (alb != 0 && objCover[i] >= 1f) {
                    // a placed thing: its own colour, lit by its own slopes
                    val left = if (x > 0) surface(i - 1) else own
                    val right = if (x < gw - 1) surface(i + 1) else own
                    val above = surface(i + up)
                    val below = surface(i + down)
                    val slope = -((right - left) * lx + (below - above) * ly)
                    val hollow = left + right + above + below - 4 * own
                    val f = (0.72f + slope.coerceIn(-3f, 3f) * 0.2f - hollow.coerceIn(-3f, 3f) * 0.08f -
                        min(shadow * 0.1f, 0.35f)).coerceIn(0.2f, 1.45f)
                    colors[i] = tone(alb, f)
                    continue
                }
                val left = if (x > 0) h[i - 1] else h[i]
                val right = if (x < gw - 1) h[i + 1] else h[i]
                val slope = -((right - left) * lx + (h[i + down] - h[i + up]) * ly)
                // how hollow the square is: groove bottoms are a little darker and ridge tops lighter whichever
                // way the line runs (without this, lines raked towards the light come out almost invisible)
                val hollow = left + right + h[i + up] + h[i + down] - 4 * h[i]
                var v = 128 + (slope * 30f).toInt() + (h[i] * 16f).toInt() - (hollow * 34f).toInt() + ((n ushr 24) and 15) - 7
                v -= (shadow * 40f).toInt().coerceAtMost(45)
                // now and then a grain glints on the sunny side of a slope
                if ((n ushr 16) and 255 < 4 && slope > 0.3f) v += 45
                colors[i] = shades[v.coerceIn(0, 255)]
                if (alb != 0) {
                    // the edge of a placed thing: its colour (lit by its own slopes) blended over the sand
                    val tl = if (x > 0) surface(i - 1) else own
                    val tr = if (x < gw - 1) surface(i + 1) else own
                    val ta = surface(i + up)
                    val tb = surface(i + down)
                    val f = (0.72f + (-((tr - tl) * lx + (tb - ta) * ly)).coerceIn(-3f, 3f) * 0.2f -
                        min(shadow * 0.1f, 0.35f)).coerceIn(0.2f, 1.45f)
                    colors[i] = ColorUtils.blendARGB(colors[i], tone(alb, f), objCover[i])
                }
            }
        }
        bmp.setPixels(colors, ay * gw + ax, gw, ax, ay, bx - ax + 1, by - ay + 1)
    }

    // The height of the surface at a square: a placed thing's own height there, or the sand's
    private fun surface(i: Int) = if (objAlbedo[i] != 0) solid[i] else heights[i]

    // A colour made lighter or darker (f = 1 leaves it as it is)
    private fun tone(c: Int, f: Float) = Color.rgb(
        min(255f, Color.red(c) * f).toInt(), min(255f, Color.green(c) * f).toInt(), min(255f, Color.blue(c) * f).toInt())

    // A fixed jumble of bits for each square (the same every time, so the sand doesn't flicker): the colour
    // wobble, the glints and the roughness of raked sand all come from it
    private fun hash(x: Int, y: Int): Int {
        val n = x * 374761393 + y * 668265263
        return (n xor (n ushr 13)) * 1274126177
    }

    // ---------------- Lantern light ----------------

    // Where each lantern's flame is, its size and which way it's turned (filled in by markCovered)
    private var lampX = FloatArray(0)
    private var lampY = FloatArray(0)
    private var lampR = FloatArray(0)
    private var lampTurn = FloatArray(0)
    private var solid = FloatArray(0)        // how tall whatever is placed on each square stands (0 = bare sand)
    private var objAlbedo = IntArray(0)      // the colour of whatever is placed on each square (0 = bare sand)
    private var objCover = FloatArray(0)     // how much of the square it covers (its outline blends into the sand)
    // the light is added on top of the garden, so black adds nothing
    private val lampPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val lampRect = RectF()

    // Each lantern's light is its own small picture around it, so each can flicker in its own time
    private class LampLight(val seed: Int) {
        val pixels = IntArray(LAMP_SIZE * LAMP_SIZE)
        val picture: Bitmap = createBitmap(LAMP_SIZE, LAMP_SIZE)
        var ox = 0                              // where the picture's corner is, in squares
        var oy = 0
        val phase = (seed and 0xFFFF) / 65535f * 1000f          // its own place in the flicker
        val pace = 0.85f + ((seed ushr 16) and 0xFF) / 255f * 0.3f   // and its own speed (0.85-1.15)
    }
    private val lampLights = ArrayList<LampLight>()

    // Works out the lanterns' light on the squares in a box. The flame sits just under the roof: the light
    // spills out, fading with distance; slopes facing the flame catch more of it; anything standing above the line
    // from a square to the flame (a ridge, a stone, a bonsai) shades it; and the fire box's six windows and
    // corner posts shape it into beams. A little of it bounces around, so the pool is never patchy.
    private fun relight(x0: Int, y0: Int, x1: Int, y1: Int, only: Int = -1) {
        if (lampX.isEmpty()) return
        // warm flame light, coloured by what it falls on (the sand, or a placed thing)
        val sandColor = SANDS[sand][1]
        for (l in lampX.indices) {
            if (only >= 0 && l != only) continue
            val layer = lampLights[l]
            val ax = maxOf(0, x0, layer.ox); val ay = maxOf(0, y0, layer.oy)
            val bx = minOf(gw - 1, x1, layer.ox + LAMP_SIZE - 1); val by = minOf(gh - 1, y1, layer.oy + LAMP_SIZE - 1)
            if (ax > bx || ay > by) continue
            for (y in ay..by) for (x in ax..bx) {
                val light = lampLightAt(l, x, y)
                layer.pixels[(y - layer.oy) * LAMP_SIZE + (x - layer.ox)] = if (light <= 0f) Color.BLACK else {
                    val k = min(light, 2.2f)
                    val c = objAlbedo[y * gw + x].let { if (it != 0) it else sandColor }
                    Color.rgb(min(255f, Color.red(c) * k).toInt(), min(255f, Color.green(c) * 0.68f * k).toInt(),
                        min(255f, Color.blue(c) * 0.36f * k).toInt())
                }
            }
            layer.picture.setPixels(layer.pixels, (ay - layer.oy) * LAMP_SIZE + (ax - layer.ox), LAMP_SIZE,
                ax - layer.ox, ay - layer.oy, bx - ax + 1, by - ay + 1)
        }
    }

    // How much of lantern l's light falls on square (x, y)
    private fun lampLightAt(l: Int, x: Int, y: Int): Float {
        val h = heights
        val i = y * gw + x
        val r = lampR[l]
        val reach = LAMP_GLOW * 7f
        val dx = lampX[l] - x - 0.5f
        val dy = lampY[l] - y - 0.5f
        val rho2 = dx * dx + dy * dy
        if (rho2 >= reach * reach) return 0f
        val rho = sqrt(rho2)
        if (rho < r * 0.9f) return 0f                 // under the roof
        if (rho < r * 1.15f && objAlbedo[i] != 0) return 0f   // the lantern's own roof (its flame is under it)
        val h0 = surface(i)
        val rise = LAMP_GLOW * 1.3f - h0              // from this square up to the flame
        if (rise <= 0f) return 0f
        val d = sqrt(rho2 + rise * rise)
        // which way the surface faces (the sand's ripples, or a placed thing's own slopes)
        val gx: Float
        val gy: Float
        if (objAlbedo[i] != 0) {
            gx = ((if (x < gw - 1) surface(i + 1) else h0) - (if (x > 0) surface(i - 1) else h0)) / 2
            gy = ((if (y < gh - 1) surface(i + gw) else h0) - (if (y > 0) surface(i - gw) else h0)) / 2
        } else {
            gx = ((if (x < gw - 1) h[i + 1] else h[i]) - (if (x > 0) h[i - 1] else h[i])) / 2
            gy = ((if (y < gh - 1) h[i + gw] else h[i]) - (if (y > 0) h[i - gw] else h[i])) / 2
        }
        val facing = max(0f, (-gx * dx - gy * dy + rise) / (d * sqrt(gx * gx + gy * gy + 1)))
        val window = windowLight(atan2(-dy, -dx) - lampTurn[l])
        // walk towards the flame: anything standing above the line to it blocks the light
        val ux = dx / rho
        val uy = dy / rho
        var block = 0f
        for (step in LAMP_STEPS) {
            if (step >= rho - r * 1.15f) break       // reached the lantern itself: it doesn't shade its own light
            val fx = x + ux * step
            val fy = y + uy * step
            if (fx < 0f || fy < 0f) break
            val sx = fx.toInt()
            val sy = fy.toInt()
            if (sx >= gw - 1 || sy >= gh - 1) break
            val tx = fx - sx
            val ty = fy - sy
            val j = sy * gw + sx
            val there = (surface(j) * (1 - tx) + surface(j + 1) * tx) * (1 - ty) +
                (surface(j + gw) * (1 - tx) + surface(j + gw + 1) * tx) * ty
            block = max(block, (there - (h0 + rise * step / rho)) * 0.45f)
            if (block >= 1f) break
        }
        block = min(block, 1f)
        var fade = 1 - rho2 / (reach * reach)
        fade *= fade
        fade *= smoothStep((rho - r * 0.9f) / (r * 0.25f))   // fades in right at the roof's edge, no dark ring
        // (the extra bit added to the distance softens the brightest part close to the lantern a little)
        return fade * (LAMP_GLOW / (d + LAMP_GLOW * 0.25f)) * (window * facing * (1 - block) * 2f + 0.25f * window)
    }

    // How much light leaves the fire box in a direction (in radians, from the lantern's turn): each of the six
    // sides is an open window throwing a beam, with the corner posts between them casting thin dark spokes
    private fun windowLight(a: Float): Float {
        var t = (a / (PI / 3).toFloat()) % 6f
        if (t < 0f) t += 6f
        val s = t - t.toInt()
        return 0.08f + 0.92f * smoothStep(s / 0.18f) * smoothStep((1 - s) / 0.18f)
    }

    private fun smoothStep(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return c * c * (3 - 2 * c)
    }

    // The light gets a little brighter and dimmer like a real flame (and now and then dips, as if in a breath of
    // wind). Only how bright the light layer is drawn changes, so nothing is worked out again.
    private fun flicker(layer: LampLight): Float {
        val t = (SystemClock.uptimeMillis() % 1_000_000L) / 1000f * layer.pace + layer.phase
        var f = 0.92f + 0.05f * sin(t * 7.3f) + 0.03f * sin(t * 13.1f + 1.7f) + 0.02f * sin(t * 23.9f + 0.6f)
        val gust = sin(t * 0.71f)
        if (gust > 0.92f) f -= (gust - 0.92f) * 1.5f
        return f
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            invalidate()   // the lantern flicker carries on
            startFlies()    // the fireflies carry on
            if (autoOn) {   // and so does the auto rake
                autoLast = SystemClock.uptimeMillis()
                removeCallbacks(autoFrame)
                post(autoFrame)
            }
        }
    }

    // ---------------- Raking ----------------

    // One rake being pulled through the sand: where it is, which way it's being pulled, how far this stroke has
    // gone (for the hand's changing pressure) and whether it's the rake, the stick or the brush. Your finger has
    // one and the auto rake has its own, so both can work at once.
    private class Raker {
        var x = 0f
        var y = 0f
        var dirX = 0f
        var dirY = 0f
        var hasDir = false
        var len = 0f
        var phase = 0f
        var kind = RAKE
        fun start(px: Float, py: Float, k: Int) {
            x = px; y = py; hasDir = false; len = 0f; phase = Random.nextFloat() * 6.28f; kind = k
        }
    }
    private val hand = Raker()
    // the box of squares changed since the last redraw
    private var dirtyX0 = Int.MAX_VALUE
    private var dirtyY0 = Int.MAX_VALUE
    private var dirtyX1 = -1
    private var dirtyY1 = -1

    private fun teeth(kind: Int) = if (kind == RAKE) 5 else 1
    private fun toothGap(kind: Int) = if (kind == RAKE) 6f else 7f
    private val toothDepth = floatArrayOf(1f, 0.88f, 1.1f, 0.95f, 1.06f)   // a real rake's teeth are never quite even

    // Drags a rake (or the brush) to (x, y), in squares, in steps of one square.
    // `follow` is how quickly the rake turns to face the way it's going (1 = at once, for exact paths).
    private fun rakeTo(r: Raker, x: Float, y: Float, follow: Float = 0.25f) {
        val dx = x - r.x
        val dy = y - r.y
        val dist = hypot(dx, dy)
        if (dist < 1f) return
        val ux = dx / dist
        val uy = dy / dist
        if (!r.hasDir) { r.dirX = ux; r.dirY = uy; r.hasDir = true }
        val half = teeth(r.kind) * toothGap(r.kind) / 2
        val steps = ceil(dist).toInt()
        for (s in 1..steps) {
            // turn the rake gently towards where it's going, so small wobbles don't twist it
            var nx = r.dirX * (1 - follow) + ux * follow
            var ny = r.dirY * (1 - follow) + uy * follow
            val len = hypot(nx, ny)
            if (len < 0.001f) { nx = ux; ny = uy } else { nx /= len; ny /= len }
            val turn = abs(r.dirX * ny - r.dirY * nx)   // how far it turned (so the outside of a bend isn't left with gaps)
            r.dirX = nx; r.dirY = ny
            val t = s.toFloat() / steps
            val px = r.x + dx * t
            val py = r.y + dy * t
            r.len += dist / steps
            if (r.kind == BRUSH) { brushAt(px, py); continue }
            stamp(r, px, py, 0.5f * dist / steps + 0.35f + half * turn)
        }
        r.x = x; r.y = y
    }

    // Presses the rake into the sand at one spot: grooves under the teeth, ridges between them and at the sides
    private fun stamp(r: Raker, px: Float, py: Float, halfLen: Float) {
        val gap = toothGap(r.kind)
        val teeth = teeth(r.kind)
        val dirX = r.dirX
        val dirY = r.dirY
        val half = teeth * gap / 2
        val outer = half + gap * 0.25f
        val reach = outer + halfLen + 1
        val x0 = max(0, floor(px - reach).toInt()); val x1 = min(gw - 1, ceil(px + reach).toInt())
        val y0 = max(0, floor(py - reach).toInt()); val y1 = min(gh - 1, ceil(py + reach).toInt())
        if (x0 > x1 || y0 > y1) return
        val h = heights
        // the hand presses a little harder or softer as the stroke goes on
        val press = DEPTH * (1 + 0.12f * sin(r.len * 0.05f + r.phase) + 0.06f * sin(r.len * 0.13f + r.phase * 2))
        for (y in y0..y1) {
            val cy = y + 0.5f - py
            for (x in x0..x1) {
                val i = y * gw + x
                if (covered[i]) continue
                val cx = x + 0.5f - px
                val along = cx * dirX + cy * dirY           // forwards/backwards from the rake
                if (abs(along) > halfLen) continue
                val u = cy * dirX - cx * dirY               // sideways along the rake
                val au = abs(u)
                if (au > outer) continue
                val tooth = if (teeth == 1) 1f else toothDepth[((u + half) / gap).toInt().coerceIn(0, teeth - 1)]
                val rough = (((hash(x, y) ushr 8) and 255) / 127.5f - 1f) * 0.07f   // loose grains in the groove walls
                val target = -press * tooth * cos(2 * PI * (u + half - gap / 2) / gap).toFloat() + rough
                val strength = 0.65f * ((outer - au) / (gap * 0.5f)).coerceAtMost(1f)   // the sides blend in softly
                h[i] += (target - h[i]) * strength
            }
        }
        markDirty(x0, y0, x1, y1)
    }

    // The brush: gently wipes the sand back to smooth under a round, soft-edged patch
    private fun brushAt(px: Float, py: Float) {
        val r = 13f
        val x0 = max(0, floor(px - r).toInt()); val x1 = min(gw - 1, ceil(px + r).toInt())
        val y0 = max(0, floor(py - r).toInt()); val y1 = min(gh - 1, ceil(py + r).toInt())
        if (x0 > x1 || y0 > y1) return
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * gw + x
            if (covered[i]) continue
            val d = hypot(x + 0.5f - px, y + 0.5f - py)
            if (d > r) continue
            heights[i] += (freshSand(x, y) - heights[i]) * 0.3f * (1 - d / r)
        }
        markDirty(x0, y0, x1, y1)
    }

    // (the shadows reach a few squares away from the light, so those squares are redrawn too)
    private fun markDirty(x0: Int, y0: Int, x1: Int, y1: Int) {
        val pad = shadowSteps + 1
        dirtyX0 = min(dirtyX0, x0 - pad); dirtyY0 = min(dirtyY0, y0 - pad)
        dirtyX1 = max(dirtyX1, x1 + pad); dirtyY1 = max(dirtyY1, y1 + pad)
    }

    private fun redrawDirty() {
        if (dirtyX1 < 0) return
        shade(dirtyX0, dirtyY0, dirtyX1, dirtyY1)
        relight(dirtyX0 - 14, dirtyY0 - 14, dirtyX1 + 14, dirtyY1 + 14)
        dirtyX0 = Int.MAX_VALUE; dirtyY0 = Int.MAX_VALUE; dirtyX1 = -1; dirtyY1 = -1
        changed = true
        invalidate()
    }

    // ---------------- Steady hand ----------------

    private var points = FloatArray(512)     // the stroke as the finger drew it (x, y, x, y, ...)
    private var pointCount = 0

    private fun addPoint(x: Float, y: Float) {
        if (pointCount * 2 + 2 > points.size) points = points.copyOf(points.size * 2)
        points[pointCount * 2] = x
        points[pointCount * 2 + 1] = y
        pointCount++
    }

    // When the finger lifts: a nearly straight stroke becomes perfectly straight, a nearly round one becomes a
    // perfect curve (or a whole circle), and anything else is smoothed out. The stroke is taken back out of the
    // sand and raked again along the tidied path.
    private fun steady() {
        val before = undoStack.lastOrNull() ?: return
        val path = resample(points, pointCount)
        val n = path.size / 2
        if (n < 12) return
        val ax = path[0]; val ay = path[1]; val bx = path[2 * n - 2]; val by = path[2 * n - 1]
        val chord = hypot(bx - ax, by - ay)
        var off = 0f
        if (chord > 1f) for (k in 0 until n) off = max(off, abs((path[2 * k] - ax) * (by - ay) - (path[2 * k + 1] - ay) * (bx - ax)) / chord)
        val exact: Boolean
        val tidy: FloatArray
        if (chord >= 12f && off <= max(2.5f, chord * 0.05f)) {
            tidy = resample(floatArrayOf(ax, ay, bx, by), 2); exact = true
        } else {
            val arc = arcThrough(path, n)
            if (arc != null) { tidy = arc; exact = true } else { tidy = smoothed(path, n); exact = false }
        }
        heights = before.heights.copyOf()
        hand.x = tidy[0]; hand.y = tidy[1]; hand.hasDir = false; hand.len = 0f
        for (k in 1 until tidy.size / 2) rakeTo(hand, tidy[2 * k], tidy[2 * k + 1], if (exact) 1f else 0.5f)
        dirtyX0 = Int.MAX_VALUE; dirtyY0 = Int.MAX_VALUE; dirtyX1 = -1; dirtyY1 = -1
        shade(0, 0, gw - 1, gh - 1)
        relight(0, 0, gw - 1, gh - 1)
        changed = true
        invalidate()
    }

    // The points again, one square apart
    private fun resample(p: FloatArray, count: Int): FloatArray {
        val out = ArrayList<Float>()
        out.add(p[0]); out.add(p[1])
        var cx = p[0]; var cy = p[1]
        for (k in 1 until count) {
            val tx = p[2 * k]; val ty = p[2 * k + 1]
            var d = hypot(tx - cx, ty - cy)
            while (d >= 1f) {
                cx += (tx - cx) / d; cy += (ty - cy) / d
                out.add(cx); out.add(cy)
                d = hypot(tx - cx, ty - cy)
            }
        }
        return out.toFloatArray()
    }

    // If the stroke is close to part of a circle, the points of that circle's arc (or a full circle if it nearly
    // closes); otherwise null
    private fun arcThrough(p: FloatArray, n: Int): FloatArray? {
        var mx = 0.0; var my = 0.0
        for (k in 0 until n) { mx += p[2 * k]; my += p[2 * k + 1] }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (k in 0 until n) {
            val u = p[2 * k] - mx; val v = p[2 * k + 1] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-6) return null
        val ru = ((suuu + suvv) / 2 * svv - (svvv + svuu) / 2 * suv) / det
        val rv = ((svvv + svuu) / 2 * suu - (suuu + suvv) / 2 * suv) / det
        val cx = (mx + ru).toFloat(); val cy = (my + rv).toFloat()
        val r = sqrt(ru * ru + rv * rv + (suu + svv) / n).toFloat()
        if (r.isNaN() || r < 8f || r > 2f * max(gw, gh)) return null
        var miss = 0f
        var swept = 0f
        var prev = atan2(p[1] - cy, p[0] - cx)
        for (k in 0 until n) {
            val dd = hypot(p[2 * k] - cx, p[2 * k + 1] - cy) - r
            miss += dd * dd
            val a = atan2(p[2 * k + 1] - cy, p[2 * k] - cx)
            var da = a - prev
            if (da > PI) da -= (2 * PI).toFloat() else if (da < -PI) da += (2 * PI).toFloat()
            swept += da
            prev = a
        }
        if (sqrt(miss / n) > max(1.5f, r * 0.05f) || abs(swept) < 0.8f) return null
        // nearly closed: go all the way round, and a little past the start so the join doesn't show
        val sweep = if (abs(swept) > 2 * PI * 0.85) (if (swept > 0) 1 else -1) * (2 * PI + 0.35).toFloat() else swept
        val a0 = atan2(p[1] - cy, p[0] - cx)
        val steps = ceil(abs(sweep) * r).toInt()
        return FloatArray(2 * (steps + 1)) { j ->
            val a = a0 + sweep * (j / 2) / steps
            if (j % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }
    }

    // Wobbles evened out: each point moved to the average of its neighbours (the ends stay put)
    private fun smoothed(p: FloatArray, n: Int): FloatArray = FloatArray(2 * n) { j ->
        val k = j / 2
        val w = min(6, min(k, n - 1 - k))
        var sum = 0f
        for (o in -w..w) sum += p[2 * (k + o) + (j % 2)]
        sum / (2 * w + 1)
    }

    // ---------------- Things placed in the garden ----------------

    private class Item(val kind: Int, var x: Float, var y: Float, val r: Float, val aspect: Float, val angle: Float,
                       val color: Int, val seed: Int, var rings: Int = 0) {
        var blobs = FloatArray(0)          // a bonsai's clumps (x, y, size) or a flower clump's blossoms (x, y, size, turn), in squares
        fun copy() = Item(kind, x, y, r, aspect, angle, color, seed, rings)
    }

    private val items = ArrayList<Item>()
    private var held: Item? = null           // the item being dragged
    private var heldFromX = 0f
    private var heldFromY = 0f
    private var heldRemembered = false
    private var pressItem: Item? = null      // pressed with a drawing tool: held long enough, the rake circles it
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    private fun newItem(kind: Int, x: Float, y: Float): Item {
        val rnd = Random
        return when (kind) {
            FLOWERS -> Item(kind, x, y, 11f + rnd.nextFloat() * 3f, 1f, 0f, PETALS[rnd.nextInt(PETALS.size)], rnd.nextInt())
            LANTERN -> Item(kind, x, y, LANTERN_SIZE, 1f, rnd.nextFloat() * 60f, LANTERN_GREY, rnd.nextInt())
            BONSAI -> Item(kind, x, y, 15f + rnd.nextFloat() * 4f, 1f, rnd.nextFloat() * 90f,
                POT_COLORS[rnd.nextInt(POT_COLORS.size)], rnd.nextInt())
            else -> Item(STONE, x, y, 9f + rnd.nextFloat() * 7f, 0.72f + rnd.nextFloat() * 0.25f, rnd.nextFloat() * 180f,
                STONE_COLORS[rnd.nextInt(STONE_COLORS.size)], rnd.nextInt())
        }.also { makeLooks(it) }
    }

    // How far out from its middle an item covers the sand
    private fun footprint(kind: Int) = when (kind) { LANTERN -> 1.1f; BONSAI -> 0.95f; else -> 1.05f }

    // A flower clump's blossom colour (old saved moss had none: pick one from its seed)
    private fun petalColour(it: Item) = if (it.color != 0) it.color else PETALS[(it.seed and 0x7FFFFFFF) % PETALS.size]

    // A bonsai's clumps of foliage, or a flower clump's blossoms, worked out once from its seed
    private fun makeLooks(it: Item) {
        if (it.blobs.isNotEmpty()) return
        if (it.kind == FLOWERS) {
            // 5-7 blossoms (x, y, size, turn) scattered over the leaves
            val rnd = Random(it.seed xor 0x5F3759DF)
            val n = 5 + rnd.nextInt(3)
            it.blobs = FloatArray(n * 4)
            for (k in 0 until n) {
                val ang = rnd.nextFloat() * 6.283f
                val d = it.r * 0.6f * sqrt(rnd.nextFloat())
                it.blobs[4 * k] = d * cos(ang); it.blobs[4 * k + 1] = d * sin(ang)
                it.blobs[4 * k + 2] = it.r * (0.26f + 0.08f * rnd.nextFloat())
                it.blobs[4 * k + 3] = rnd.nextFloat() * 360f
            }
            return
        }
        if (it.kind != BONSAI) return
        val rnd = Random(it.seed)
        val n = 7
        it.blobs = FloatArray(n * 3)
        for (k in 0 until n) {
            val ang = k * 1.047f + rnd.nextFloat() * 0.6f
            val d = if (k == 0) it.r * 0.1f else it.r * (0.42f + 0.15f * rnd.nextFloat())
            it.blobs[3 * k] = d * cos(ang); it.blobs[3 * k + 1] = d * sin(ang)
            it.blobs[3 * k + 2] = it.r * (0.32f + 0.13f * rnd.nextFloat())
        }
    }

    // Puts a placed thing into the surface: its shape (how tall it stands on each square) and its colour there.
    // Everything comes from its seed, so it looks the same every time.
    // - stone: a rounded, slightly lumpy outline with gentle bumps and speckled granite
    // - flowers: a low clump of pointed leaves (the blossoms on top are drawn sharply over it, see drawFlowers)
    // - bonsai: a glazed pot with soil, under round clumps of foliage, lighter where they're higher
    // - lantern: a six-sided stone roof rising to a round jewel, its corners turned up a little
    private fun bake(s: Item) {
        makeLooks(s)
        val rnd = Random(s.seed)
        val a = rnd.nextFloat() * 6.283f
        val b = rnd.nextFloat() * 6.283f
        val c = rnd.nextFloat() * 6.283f
        val reach = s.r * 1.3f
        val turn = Math.toRadians(s.angle.toDouble())
        val ca = cos(-turn).toFloat()
        val sa = sin(-turn).toFloat()
        val r = s.r
        for (y in max(0, (s.y - reach).toInt())..min(gh - 1, (s.y + reach).toInt())) {
            for (x in max(0, (s.x - reach).toInt())..min(gw - 1, (s.x + reach).toInt())) {
                val i = y * gw + x
                val dx = x + 0.5f - s.x
                val dy = y + 0.5f - s.y
                val n = hash(x, y)
                val speck = ((n ushr 3) and 255) / 255f
                when (s.kind) {
                    STONE -> {
                        val u = dx * ca - dy * sa
                        val v = (dx * sa + dy * ca) / s.aspect
                        val th = atan2(v, u)
                        val q = hypot(u, v) / (r * (1 + 0.10f * sin(2 * th + a) + 0.07f * sin(3 * th + b) + 0.04f * sin(5 * th + c)))
                        if (q >= 1f) continue
                        val height = r * 0.6f * (1 - q * q).pow(0.55f) + 0.35f * sin(u * 0.7f + a) * sin(v * 0.8f + b) * (1 - q)
                        var f = (0.9f + 0.2f * speck) * (0.95f + 0.08f * sin(u * 0.25f + c))
                        if ((n ushr 11) and 31 == 0) f -= 0.25f   // dark flecks
                        put(i, height, tone(s.color, f), (1 - q) * r)
                    }
                    FLOWERS -> {
                        val th = atan2(dy, dx)
                        val d = hypot(dx, dy)
                        val q = d / (r * (1 + 0.18f * sin(7 * th + a) + 0.1f * sin(3 * th + b)))
                        if (q >= 1f) continue
                        // leaves fanning out from the middle
                        val leaf = abs(sin(th * 7 + d * 0.25f + a))
                        val height = r * 0.22f * sqrt(1 - q * q) + leaf * 0.8f * (1 - q)
                        val t = (leaf * 0.6f + (1 - q) * 0.3f + speck * 0.15f).coerceIn(0f, 1f)
                        put(i, height, ColorUtils.blendARGB(LEAF_DARK, LEAF_LIGHT, t), (1 - q) * r)
                        // the blossoms' own colour under them, so lantern and firefly light on them is the right colour
                        for (k in 0 until s.blobs.size / 4) {
                            if (hypot(dx - s.blobs[4 * k], dy - s.blobs[4 * k + 1]) < s.blobs[4 * k + 2]) {
                                put(i, r * 0.4f, petalColour(s))
                                break
                            }
                        }
                    }
                    BONSAI -> {
                        val u = dx * ca - dy * sa
                        val v = dx * sa + dy * ca
                        val box = max(abs(u), abs(v))
                        if (box < 0.8f * r) {
                            val rim = box > 0.68f * r
                            put(i, if (rim) 0.38f * r else 0.3f * r,
                                if (rim) tone(s.color, 0.92f + 0.12f * speck) else tone(SOIL, 0.85f + 0.3f * speck), 0.8f * r - box)
                        }
                        for (k in 0 until s.blobs.size / 3) {
                            val br = s.blobs[3 * k + 2]
                            val e = hypot(dx - s.blobs[3 * k], dy - s.blobs[3 * k + 1]) / br
                            if (e >= 1f) continue
                            val needles = ((n ushr 2) and 255) / 255f
                            val height = 0.8f * r + br * 1.1f * sqrt(1 - e * e) + needles
                            val t = ((height - 0.8f * r) / (br * 1.1f + 1f)).coerceIn(0f, 1f).pow(1.3f)
                            put(i, height, ColorUtils.blendARGB(PINE_DEEP, PINE_TIP, t), (1 - e) * br)
                        }
                    }
                    LANTERN -> {
                        var hd = 0f
                        for (k in 0 until 3) {
                            val ak = turn + Math.toRadians(30.0 + k * 60)
                            hd = max(hd, abs(dx * cos(ak).toFloat() + dy * sin(ak).toFloat()))
                        }
                        hd /= r * 0.866f
                        if (hd > 1f) continue
                        val d = hypot(dx, dy)
                        val corner = ((d / r - 0.85f) / 0.15f).coerceIn(0f, 1f)
                        var height = 2f * r - 0.45f * r * hd + 0.12f * r * corner * corner
                        if (d < 0.32f * r) height += 0.06f * r   // the ring the jewel sits on
                        val j = d / (0.2f * r)
                        if (j < 1f) put(i, 2.06f * r + 0.3f * r * sqrt(1 - j * j), tone(JEWEL_GREY, 0.95f + 0.1f * speck))
                        else put(i, height, tone(LANTERN_GREY, 0.9f + 0.18f * speck), (1 - hd) * r * 0.866f)
                    }
                }
            }
        }
    }

    // One square of a placed thing: the tallest part wins. `edge` is how far inside the outline the square is, in
    // squares: the last square and a half blends into the sand, so outlines are smooth rather than stepped.
    private fun put(i: Int, height: Float, colour: Int, edge: Float = 9f) {
        covered[i] = true
        if (objAlbedo[i] == 0 || height > solid[i]) {
            solid[i] = height
            objAlbedo[i] = colour or Color.BLACK   // (never 0, which means bare sand)
        }
        objCover[i] = max(objCover[i], (edge / 1.5f).coerceIn(0.15f, 1f))
    }

    // The placed thing under (x, y): the nearest one, if several are close together
    private fun itemAt(x: Float, y: Float): Item? {
        var best: Item? = null
        var bestD = Float.MAX_VALUE
        for (it in items) {
            val d = hypot(x - it.x, y - it.y)
            if (d < it.r * 1.15f && d < bestD) { best = it; bestD = d }
        }
        return best
    }

    // Which squares have something on them (and how tall it stands there), and where the lanterns' flames are
    private fun markCovered() {
        if (covered.size != gw * gh) {
            covered = BooleanArray(gw * gh); solid = FloatArray(gw * gh); objAlbedo = IntArray(gw * gh); objCover = FloatArray(gw * gh)
        } else { covered.fill(false); solid.fill(0f); objAlbedo.fill(0); objCover.fill(0f) }
        for (s in items) bake(s)
        val lamps = items.filter { it.kind == LANTERN }
        lampX = FloatArray(lamps.size) { lamps[it].x }
        lampY = FloatArray(lamps.size) { lamps[it].y }
        lampR = FloatArray(lamps.size) { lamps[it].r }
        lampTurn = FloatArray(lamps.size) { Math.toRadians(lamps[it].angle.toDouble()).toFloat() }
        // each lantern's light picture (kept and reused, as this runs on every move while something is dragged)
        while (lampLights.size > lamps.size) lampLights.removeAt(lampLights.size - 1)
        for (k in lamps.indices) {
            if (k >= lampLights.size || lampLights[k].seed != lamps[k].seed) {
                if (k < lampLights.size) lampLights[k] = LampLight(lamps[k].seed) else lampLights.add(LampLight(lamps[k].seed))
            }
            val layer = lampLights[k]
            val nx = lamps[k].x.toInt() - LAMP_SIZE / 2
            val ny = lamps[k].y.toInt() - LAMP_SIZE / 2
            if (nx != layer.ox || ny != layer.oy) {
                layer.ox = nx; layer.oy = ny
                layer.pixels.fill(Color.BLACK)   // (squares off the tray stay dark)
            }
        }
    }

    // Draws again the squares around a placed thing (where it was or is)
    private fun reshadeAround(x: Float, y: Float, r: Float) {
        val reach = (r * 1.4f).toInt() + 2
        val pad = shadowSteps + 2
        shade(x.toInt() - reach - pad, y.toInt() - reach - pad, x.toInt() + reach + pad, y.toInt() + reach + pad)
    }

    // An item set down pushes the sand around it flat
    private fun settle(s: Item) {
        eachNear(s, 1.35f) { i, d -> heights[i] *= (d / 1.35f).coerceIn(0.25f, 1f) }
        val r = (s.r * 1.4f).toInt() + 2
        val pad = shadowSteps + 1
        shade(s.x.toInt() - r - pad, s.y.toInt() - r - pad, s.x.toInt() + r + pad, s.y.toInt() + r + pad)
    }

    // Runs `act` for each square within `scale` × the item's size, with its distance in item sizes
    private inline fun eachNear(s: Item, scale: Float, act: (Int, Float) -> Unit) {
        val reach = s.r * scale
        for (y in max(0, (s.y - reach).toInt())..min(gh - 1, (s.y + reach).toInt())) {
            for (x in max(0, (s.x - reach).toInt())..min(gw - 1, (s.x + reach).toInt())) {
                val d = hypot(x + 0.5f - s.x, y + 0.5f - s.y) / s.r
                if (d <= scale) act(y * gw + x, d)
            }
        }
    }

    // ---------------- Undo ----------------

    private class Snapshot(val heights: FloatArray, val items: List<Item>, val wave: FloatArray)
    private val undoStack = ArrayDeque<Snapshot>()

    // Keeps the garden as it is now, so the next change can be undone
    private fun remember() {
        if (gw == 0) return
        undoStack.addLast(Snapshot(heights.copyOf(), items.map { it.copy() }, wave.copyOf()))
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        onUndoChanged?.invoke()
    }

    fun undo() {
        saveSoon()
        if (anim != null) return
        val s = undoStack.removeLastOrNull() ?: return
        heights = s.heights
        wave = s.wave
        items.clear()
        items.addAll(s.items)
        items.forEach { makeLooks(it) }
        markCovered()
        shade(0, 0, gw - 1, gh - 1)
        relight(0, 0, gw - 1, gh - 1)
        changed = true
        invalidate()
        onUndoChanged?.invoke()
    }

    // ---------------- Sound ----------------

    private val sound = SandSound()
    private var heard = 0f                   // the finger's speed, smoothed (a stroke's first reading is always a jump)
    private val hush = Runnable { heard = 0f; sound.level(0f) }

    // The finger is moving at `speed` (0 to 1): the sound follows, and fades if the finger stops
    private fun makeSound(speed: Float) {
        if (!soundOn) return
        heard += (speed - heard) * 0.15f
        sound.level(heard * when (tool) { RAKE -> 1f; STICK -> 0.6f; else -> 0.45f })
        removeCallbacks(hush)
        postDelayed(hush, 120)
    }

    // ---------------- Touch ----------------

    private var touching = false
    private var strokeRemembered = false
    private var prevTime = 0L
    private var prevX = 0f
    private var prevY = 0f

    private val longPress = Runnable {
        val it = pressItem ?: return@Runnable
        pressItem = null
        if (!moved && touching) {
            touching = false
            ringAround(it)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return true
        val x = (e.x - offX - inset) / cell
        val y = (e.y - offY - inset) / cell
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapUsed = false
                touching = anim == null
                if (!touching) return true
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x; downY = y; moved = false
                hand.start(x, y, tool)
                heard = 0f
                strokeRemembered = false
                pointCount = 0
                addPoint(x, y)
                prevTime = e.eventTime; prevX = x; prevY = y
                held = null; heldRemembered = false; pressItem = null
                if (tool == FIREFLY) return true
                if (tool >= STONE) {
                    held = itemAt(x, y)?.also { heldFromX = it.x; heldFromY = it.y }
                } else if (tool != BRUSH) {
                    pressItem = itemAt(x, y)
                    if (pressItem != null) postDelayed(longPress, 450)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                if (!moved && hypot(x - downX, y - downY) * cell > dp(8f)) {
                    moved = true
                    removeCallbacks(longPress)
                    onDragging?.invoke()
                }
                if (tool == FIREFLY) return true
                if (tool >= STONE) {
                    val s = held ?: return true
                    if (!moved) return true
                    if (!heldRemembered) { remember(); heldRemembered = true }
                    val oldX = s.x
                    val oldY = s.y
                    snapTo((heldFromX + x - downX).coerceIn(0f, gw.toFloat()), (heldFromY + y - downY).coerceIn(0f, gh.toFloat()))
                    s.x = snapX
                    s.y = snapY
                    if (s.x == oldX && s.y == oldY) return true   // (on the grid it only moves point to point)
                    markCovered()
                    reshadeAround(oldX, oldY, s.r)
                    reshadeAround(s.x, s.y, s.r)
                    // with a lantern out, its light and the shadows in it follow what you're dragging: just the light
                    // near where it was and is now (everything is worked out again when you let go)
                    if (lampX.isNotEmpty()) {
                        val pad = (s.r * 1.4f).toInt() + 40
                        relight(oldX.toInt() - pad, oldY.toInt() - pad, oldX.toInt() + pad, oldY.toInt() + pad)
                        relight(s.x.toInt() - pad, s.y.toInt() - pad, s.x.toInt() + pad, s.y.toInt() + pad)
                        // a lantern being moved: all of its own light goes with it
                        if (s.kind == LANTERN) relight(0, 0, gw - 1, gh - 1, items.filter { it.kind == LANTERN }.indexOf(s))
                    }
                    invalidate()
                } else {
                    if (pressItem != null && !moved) return true   // might be the start of a long press
                    if (!strokeRemembered) { remember(); strokeRemembered = true }
                    // use every point the finger passed through, not just the latest, so fast strokes stay smooth
                    for (k in 0 until e.historySize) {
                        val hx = (e.getHistoricalX(k) - offX - inset) / cell
                        val hy = (e.getHistoricalY(k) - offY - inset) / cell
                        addPoint(hx, hy)
                        rakeTo(hand, hx, hy)
                    }
                    addPoint(x, y)
                    rakeTo(hand, x, y)
                    redrawDirty()
                    val dt = max(8L, e.eventTime - prevTime)
                    makeSound((hypot(x - prevX, y - prevY) / dt * 1000f / 700f).coerceAtMost(1f))
                    prevTime = e.eventTime; prevX = x; prevY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                saveSoon()
                removeCallbacks(longPress)
                if (!touching) return true
                touching = false
                if (tool == FIREFLY) {
                    if (!moved) { tapFirefly(x, y); tapUsed = true }
                } else if (tool >= STONE) {
                    val s = held
                    if (!moved) {
                        tapUsed = true
                        // a tap: take away the item you tapped, or put a new one down
                        if (s != null) { remember(); items.remove(s) }
                        else if (items.size < MAX_ITEMS) { remember(); snapTo(x, y); items.add(newItem(tool, snapX, snapY).also { settle(it) }) }
                    } else if (s != null) settle(s)
                    held = null
                    markCovered()
                    shade(0, 0, gw - 1, gh - 1)
                    relight(0, 0, gw - 1, gh - 1)   // the lantern light falls differently now
                    changed = true
                    invalidate()
                } else {
                    sound.level(0f)
                    if (strokeRemembered && steadyHand && tool != BRUSH) steady()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touching = false
                sound.level(0f)
                held?.let { it.x = heldFromX; it.y = heldFromY }
                held = null
                invalidate()
            }
        }
        return true
    }

    // ---------------- Rings around an item, and smoothing the tray ----------------

    private var anim: ValueAnimator? = null

    private var lastRinged: Item? = null
    private var lastRingTime = 0L

    // Rakes a perfect circle right around an item. Pressing the same item again within a few seconds adds the next
    // ring further out; otherwise it starts again close to the item.
    private fun ringAround(it: Item) {
        remember()
        val half = teeth(tool) * toothGap(tool) / 2
        val now = SystemClock.uptimeMillis()
        val ring = if (it === lastRinged && now - lastRingTime < 8000) it.rings % MAX_RINGS else 0
        lastRinged = it
        lastRingTime = now
        val r = it.r * footprint(it.kind) + 2f + half + ring * 2 * half
        it.rings = ring + 1
        val cx = it.x; val cy = it.y
        val start = atan2(lightY, lightX)
        val sweep = (2 * PI + 0.35).toFloat()
        hand.start(cx + r * cos(start), cy + r * sin(start), tool)
        var done = 0f
        if (soundOn) sound.level(0.55f * if (tool == RAKE) 1f else 0.6f)
        anim = ValueAnimator.ofFloat(0f, sweep).apply {
            duration = (500 + r * 8).toLong().coerceAtMost(1800)
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val to = a.animatedValue as Float
                while (done < to) {
                    done = min(to, done + 1f / r)
                    rakeTo(hand, cx + r * cos(start + done), cy + r * sin(start + done), 1f)
                }
                redrawDirty()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveSoon()
                    anim = null
                    sound.level(0f)
                }
            })
            start()
        }
    }

    private var smoothedTo = 0

    // Sweeps across the tray from left to right, leaving fresh smooth sand (the placed things stay put)
    fun smooth() {
        if (bitmap == null || anim != null) return
        remember()
        wave = FloatArray(4) { Random.nextFloat() * 6.28f }
        items.forEach { it.rings = 0 }
        smoothedTo = 0
        if (soundOn) sound.level(0.5f)
        anim = ValueAnimator.ofInt(0, gw).apply {
            duration = 450
            interpolator = LinearInterpolator()
            addUpdateListener { smoothColumns(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    saveSoon()
                    smoothColumns(gw)
                    anim = null
                    sound.level(0f)
                }
            })
            start()
        }
    }

    private fun smoothColumns(upTo: Int) {
        val to = min(upTo, gw)
        if (to <= smoothedTo) return
        smoothRange(smoothedTo, to)
        smoothedTo = to
    }

    // Fresh smooth sand in the columns from `from` up to (not including) `to`
    private fun smoothRange(from: Int, to: Int) {
        val a = max(0, from)
        val b = min(gw, to)
        if (b <= a) return
        for (y in 0 until gh) for (x in a until b) {
            val i = y * gw + x
            heights[i] = if (covered[i]) heights[i] * 0.25f else freshSand(x, y)
        }
        shade(a - shadowSteps - 1, 0, b + shadowSteps + 1, gh - 1)
        relight(a - 14, 0, b + 14, gh - 1)
        changed = true
        invalidate()
    }

    // Takes away everything placed in the garden (can be undone)
    fun clearItems() {
        saveSoon()
        if (anim != null) return
        flies.forEach { if (it.leaving < 0f) it.leaving = LEAVE_SECONDS }   // the fireflies fly off
        if (items.isEmpty()) return
        remember()
        items.clear()
        markCovered()
        shade(0, 0, gw - 1, gh - 1)
        changed = true
        invalidate()
    }

    // ---------------- Triangle grid ----------------

    // Points of equilateral triangles, GRID squares apart, with one point in the middle of the tray. A point is
    // (i, j): row j is j * rowGap down from the middle, and every other row is shifted half a step sideways.
    private val rowGap = GRID * 0.8660254f    // the height of one triangle
    private var snapX = 0f
    private var snapY = 0f

    // Sets snapX and snapY to (x, y), moved to the nearest grid point when the grid is on
    private fun snapTo(x: Float, y: Float) {
        snapX = x
        snapY = y
        if (!gridOn) return
        val cx = gw / 2f
        val cy = gh / 2f
        val row = ((y - cy) / rowGap).roundToInt()
        var best = Float.MAX_VALUE
        for (j in row - 1..row + 1) {
            val i = ((x - cx) / GRID - 0.5f * j).roundToInt()
            val px = cx + GRID * (i + 0.5f * j)
            val py = cy + rowGap * j
            val d = hypot(px - x, py - y)
            if (d < best && px in 0f..gw.toFloat() && py in 0f..gh.toFloat()) { best = d; snapX = px; snapY = py }
        }
    }

    // Faint lines in the three directions of the triangles, and a soft dot where they meet
    private fun drawGrid(canvas: Canvas) {
        if (!gridOn) return
        val cx = gw / 2f
        val cy = gh / 2f
        val sx = inset          // squares to pixels: px = inset + x * cell
        val c = cell
        val top = -cy / rowGap - 1
        val bottom = (gh - cy) / rowGap + 1
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        val onDark = sand == SANDS.size - 1
        paint.color = if (onDark) GRID_LINE_ON_DARK else GRID_LINE
        // across
        for (j in top.toInt()..bottom.toInt()) {
            val y = cy + rowGap * j
            canvas.drawLine(sx, sx + y * c, sx + gw * c, sx + y * c, paint)
        }
        // the two slanted directions: each line goes through the points with the same i (or the same i + j)
        val span = ((gw + gh) / GRID).toInt() + 2
        for (k in -span..span) {
            val x0 = cx + GRID * (k + 0.5f * top)
            val x1 = cx + GRID * (k + 0.5f * bottom)
            canvas.drawLine(sx + x0 * c, sx + (cy + rowGap * top) * c, sx + x1 * c, sx + (cy + rowGap * bottom) * c, paint)
            val x2 = cx + GRID * (k - 0.5f * top)
            val x3 = cx + GRID * (k - 0.5f * bottom)
            canvas.drawLine(sx + x2 * c, sx + (cy + rowGap * top) * c, sx + x3 * c, sx + (cy + rowGap * bottom) * c, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (onDark) GRID_POINT_ON_DARK else GRID_POINT
        for (j in top.toInt()..bottom.toInt()) {
            val y = cy + rowGap * j
            if (y < 0 || y > gh) continue
            val first = ((0 - cx) / GRID - 0.5f * j).toInt() - 1
            val last = ((gw - cx) / GRID - 0.5f * j).toInt() + 1
            for (i in first..last) {
                val x = cx + GRID * (i + 0.5f * j)
                if (x < 0 || x > gw) continue
                canvas.drawCircle(sx + x * c, sx + y * c, dp(2.5f), paint)
            }
        }
    }

    // ---------------- Fireflies ----------------

    // A firefly: where it is, which way it's flying, its slowly wandering turn, and its glow, which swells,
    // holds, fades and then rests in the dark for a while. Each has a small picture of the light it casts on the
    // sand under it, worked out again every frame while it glows.
    private class Firefly(var x: Float, var y: Float) {
        var heading = Random.nextFloat() * 6.283f
        var turn = 0f
        var phase = Random.nextFloat() * 6.283f     // for its gentle changes of speed
        var glowState = 1                            // 0 dark, 1 brightening, 2 bright, 3 dimming (new ones light up)
        var glowTime = 0f
        var glowLength = 1.2f
        var level = 0f                               // how brightly it's glowing, 0 to 1
        var height = FLY_LOW                         // how high it is above the sand
        var leaving = -1f                            // seconds left fading away (after a tap), -1 = staying
        val size = FLY_REACH + 1                     // its light picture is worked out every other square
        val pixels = IntArray(size * size)
        val light: Bitmap = createBitmap(size, size)
        var ox = 0                                   // where its light picture sits, in squares
        var oy = 0
        var lit = false                              // its light picture is up to date and showing
    }

    private val flies = ArrayList<Firefly>()
    private var savedFlies = 0                       // fireflies in the saved garden, let out once its size is known
    private var flyRunning = false
    private var flyLast = 0L
    private val flyFrame = Runnable { flyStep() }
    private val flyRect = RectF()
    private var flyHalo: Shader? = null              // the faint haze in the air around a glowing firefly
    private var flyCore: Shader? = null              // the small soft glow around its light
    private var flySpark: Shader? = null             // the tiny soft point the light comes from
    private val LEAVE_SECONDS = 1.5f

    // A tap with the Fireflies tool: a firefly near the tap flies away, otherwise a new one is let out there
    private fun tapFirefly(x: Float, y: Float) {
        saveSoon()
        val near = flies.firstOrNull { it.leaving < 0f && hypot(it.x - x, it.y - y) < 12f }
        if (near != null) near.leaving = LEAVE_SECONDS
        else if (flies.count { it.leaving < 0f } < MAX_FLIES) flies.add(Firefly(x, y))
        changed = true
        startFlies()
    }

    private fun startFlies() {
        if (flyRunning || flies.isEmpty() || bitmap == null) return
        flyRunning = true
        flyLast = SystemClock.uptimeMillis()
        post(flyFrame)
    }

    // One frame of the fireflies (about 30 a second, only while there are some and the garden is on screen)
    private fun flyStep() {
        if (flies.isEmpty() || bitmap == null || windowVisibility != VISIBLE) { flyRunning = false; invalidate(); return }
        val now = SystemClock.uptimeMillis()
        val dt = ((now - flyLast) / 1000f).coerceIn(0f, 0.05f)
        flyLast = now
        val gone = flies.removeAll { it.leaving == 0f }
        if (gone) changed = true
        for (f in flies) {
            flyAbout(f, dt)
            lightFirefly(f)
        }
        invalidate()
        postDelayed(flyFrame, 33)
    }

    private fun flyAbout(f: Firefly, dt: Float) {
        // wander: the turn drifts slowly, so the path is a lazy curve
        f.turn += (Random.nextFloat() - 0.5f) * 2.4f * dt
        f.turn = (f.turn * (1 - 0.6f * dt)).coerceIn(-0.9f, 0.9f)
        f.heading += f.turn * dt
        // near the edge of the tray, turn gently back towards the middle
        val margin = 24f
        if (f.leaving < 0f && (f.x < margin || f.y < margin || f.x > gw - margin || f.y > gh - margin)) {
            var diff = atan2(gh / 2f - f.y, gw / 2f - f.x) - f.heading
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            f.heading += diff * 1.2f * dt
        }
        // slow and unhurried, slower still while glowing (they hover as they flash), quicker when flying off
        f.phase += dt * 0.7f
        val speed = (11f + 4f * sin(f.phase)) * (1 - 0.6f * f.level) * (if (f.leaving >= 0f) 2.5f else 1f)
        f.x += cos(f.heading) * speed * dt
        f.y += sin(f.heading) * speed * dt
        // the glow: a slow swell, a short hold, a slow fade, then dark for a while
        f.glowTime += dt
        if (f.glowTime >= f.glowLength) {
            f.glowTime = 0f
            f.glowState = (f.glowState + 1) % 4
            f.glowLength = when (f.glowState) {
                0 -> 3f + Random.nextFloat() * 3f   // dark 3-6 s, close to a real firefly's rhythm
                1 -> 1f + Random.nextFloat() * 0.8f
                2 -> 0.3f + Random.nextFloat() * 0.6f
                else -> 1.4f + Random.nextFloat() * 1f
            }
        }
        val t = f.glowTime / f.glowLength
        f.level = when (f.glowState) { 1 -> smoothStep(t); 2 -> 1f; 3 -> 1 - smoothStep(t); else -> 0f }
        // like a real firefly it climbs gently while it glows, so its light spreads wider and softer, then settles
        f.height += ((if (f.glowState == 0) FLY_LOW else FLY_HIGH) - f.height) * min(1f, dt * 0.8f)
        if (f.leaving >= 0f) {
            f.leaving = max(0f, f.leaving - dt)
            f.level *= f.leaving / LEAVE_SECONDS
        }
    }

    // The light a glowing firefly casts on the sand below it. A firefly's light organ is on the underside of its
    // body, so seen from above the light is mostly this soft pool on the ground: brightest straight under it,
    // fading out, and caught by slopes that face it, in a gentle yellow-green coloured by the sand (no shadows:
    // it's small and flies high).
    private fun lightFirefly(f: Firefly) {
        val power = f.level * lampPower * 0.32f
        if (power < 0.01f) { f.lit = false; return }
        val reach = FLY_REACH
        val size = f.size
        // every other square, so a soft wide glow stays cheap
        f.ox = (f.x.toInt() - reach) and 1.inv()
        f.oy = (f.y.toInt() - reach) and 1.inv()
        val sandColor = SANDS[sand][1]
        val h = heights
        val reach2 = (reach * reach).toFloat()
        for (j in 0 until size) {
            val gy = f.oy + 2 * j
            for (i in 0 until size) {
                val gx = f.ox + 2 * i
                val p = j * size + i
                if (gx < 0 || gy < 0 || gx >= gw || gy >= gh) { f.pixels[p] = Color.BLACK; continue }
                val dx = f.x - gx - 0.5f
                val dy = f.y - gy - 0.5f
                val rho2 = dx * dx + dy * dy
                if (rho2 >= reach2) { f.pixels[p] = Color.BLACK; continue }
                val k = gy * gw + gx
                val own = surface(k)
                val rise = f.height - own
                if (rise <= 0f) { f.pixels[p] = Color.BLACK; continue }
                val d2 = rho2 + rise * rise
                val thing = objAlbedo[k] != 0
                val sx = ((if (gx < gw - 1) (if (thing) surface(k + 1) else h[k + 1]) else own) - (if (gx > 0) (if (thing) surface(k - 1) else h[k - 1]) else own)) / 2
                val sy = ((if (gy < gh - 1) (if (thing) surface(k + gw) else h[k + gw]) else own) - (if (gy > 0) (if (thing) surface(k - gw) else h[k - gw]) else own)) / 2
                val facing = max(0f, (-sx * dx - sy * dy + rise) / (sqrt(d2) * sqrt(sx * sx + sy * sy + 1)))
                var fade = 1 - rho2 / reach2
                fade *= fade
                // part of the light is even (it's soft and comes from above), part follows the slopes
                val e = (facing * 0.6f + 0.4f) * (f.height * f.height / d2) * fade * power
                val c = if (thing) objAlbedo[k] else sandColor
                f.pixels[p] = Color.rgb(min(255f, Color.red(c) * 0.62f * e).toInt(), min(255f, Color.green(c) * e).toInt(),
                    min(255f, Color.blue(c) * 0.78f * e).toInt())
            }
        }
        f.light.setPixels(f.pixels, 0, size, 0, 0, size, size)
        f.lit = true
    }

    // The fireflies' light on the sand (added on top, like the lanterns'), drawn under everything that stands up
    private fun drawFireflyLight(canvas: Canvas) {
        lampPaint.alpha = 255
        for (f in flies) if (f.lit) {
            flyRect.set(inset + f.ox * cell, inset + f.oy * cell, inset + (f.ox + 2 * f.size) * cell, inset + (f.oy + 2 * f.size) * cell)
            canvas.drawBitmap(f.light, null, flyRect, lampPaint)
        }
    }

    // The fireflies themselves, above everything: only their light, as you see them at night. A tiny soft spark
    // where the light comes from, a small glow around it and a wide, faint haze in the air (no bodies: a dark
    // insect moving about looked like a real bug crawling on the screen). Between flashes they can't be seen.
    private fun drawFireflies(canvas: Canvas) {
        if (flies.isEmpty()) return
        val halo = flyHalo ?: RadialGradient(0f, 0f, dp(14f),
            intArrayOf(Color.argb(30, 170, 238, 200), Color.argb(10, 165, 232, 195), Color.argb(0, 165, 232, 195)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP).also { flyHalo = it }
        val core = flyCore ?: RadialGradient(0f, 0f, dp(3.5f),
            intArrayOf(Color.argb(55, 190, 245, 212), Color.argb(20, 180, 240, 205), Color.argb(0, 180, 240, 205)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP).also { flyCore = it }
        val spark = flySpark ?: RadialGradient(0f, 0f, dp(1.6f),
            intArrayOf(Color.argb(210, 228, 255, 236), Color.argb(150, 190, 250, 214), Color.argb(0, 180, 245, 205)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP).also { flySpark = it }
        val haloPower = 0.35f + 0.65f * (lampPower - 0.3f) / 0.7f
        for (f in flies) {
            canvas.withSave {
                translate(inset + f.x * cell, inset + f.y * cell)
                if (f.level > 0.01f) {
                    paint.shader = halo
                    paint.alpha = (255 * f.level * haloPower).toInt().coerceIn(0, 255)
                    drawCircle(0f, 0f, dp(14f), paint)
                    paint.shader = core
                    paint.alpha = (255 * f.level).toInt().coerceIn(0, 255)
                    drawCircle(0f, 0f, dp(3.5f), paint)
                    paint.shader = spark
                    drawCircle(0f, 0f, dp(1.6f), paint)
                    paint.shader = null
                }
                paint.alpha = 255
            }
        }
    }

    // ---------------- Auto rake ----------------

    // A stroke for the auto rake: points one square apart (x, y, x, y, ...), raked with the rake or the stick
    private class Stroke(val points: FloatArray, val kind: Int)

    var onAutoPattern: ((String?) -> Unit)? = null    // tells the Game Room which pattern is being raked (null = off)
    var autoOn = false
        private set
    private val autoRake = Raker()
    private var autoPattern = 0               // which pattern is raked next (chosen at random)
    private var autoStage = 0                 // 0 smoothing the tray, 1 raking the pattern, 2 rings around things, 3 resting
    private val autoStrokes = ArrayList<Stroke>()
    private var autoStroke = 0                // which stroke it's on
    private var autoIndex = 0                 // which point of that stroke
    private var autoBudget = 0f               // squares it may still move this frame
    private var autoLift = 0f                 // seconds left lifting the rake between strokes (it fades out, then in)
    private var autoLiftMoved = false         // halfway through a lift: the rake has moved to the next stroke
    private var autoClock = 0f                // seconds into smoothing or resting
    private var autoSmoothedTo = 0
    private var autoLast = 0L
    private var autoKind = RAKE               // the pattern's tool, also used for the rings
    private val autoFrame = Runnable { autoStep() }
    private var autoCurrent = 0               // the pattern being raked (or just finished)
    private var autoResizing = false          // paused while the window is being resized
    private val autoAfterResize = Runnable {
        autoResizing = false
        if (autoOn && bitmap != null) {
            // still smoothing for the next pattern: do that one; otherwise redo the one that was cut short
            if (autoStage != 0) autoPattern = autoCurrent
            autoStage = -1
            nextAutoStage()
            autoLast = SystemClock.uptimeMillis()
            removeCallbacks(autoFrame)
            post(autoFrame)
        }
    }

    // Starts or stops the auto rake. It smooths the tray, rakes a pattern slowly, rakes rings around whatever is
    // placed, rests a little to let you look at it, then moves on to the next pattern.
    fun setAuto(on: Boolean) {
        if (on == autoOn) return
        autoOn = on
        removeCallbacks(autoFrame)
        if (on) {
            autoPattern = Random.nextInt(AUTO_PATTERNS.size)   // a different starting pattern each time
            autoStage = -1
            nextAutoStage()
            autoLast = SystemClock.uptimeMillis()
            post(autoFrame)
        } else {
            sound.level(0f)
            onAutoPattern?.invoke(null)
            invalidate()
        }
    }

    private fun nextAutoStage() {
        saveSoon()
        autoStage = (autoStage + 1) % 4
        autoClock = 0f
        when (autoStage) {
            0 -> {
                wave = FloatArray(4) { Random.nextFloat() * 6.28f }
                items.forEach { it.rings = 0 }
                autoSmoothedTo = 0
                onAutoPattern?.invoke(AUTO_PATTERNS[autoPattern])
            }
            1 -> {
                autoStrokes.clear()
                autoStrokes.addAll(patternStrokes(autoPattern))
                autoCurrent = autoPattern
                autoKind = autoStrokes.firstOrNull()?.kind ?: RAKE
                // the next round's pattern: any other one, at random
                autoPattern = (autoPattern + 1 + Random.nextInt(AUTO_PATTERNS.size - 1)) % AUTO_PATTERNS.size
                beginStrokes()
            }
            2 -> {
                autoStrokes.clear()
                autoStrokes.addAll(ringStrokes())
                beginStrokes()
            }
        }
    }

    private fun beginStrokes() {
        if (autoStrokes.isEmpty()) { nextAutoStage(); return }
        autoStroke = 0
        startAutoStroke()
        autoLift = LIFT_SECONDS / 2       // fade in at the start
        autoLiftMoved = true
    }

    private fun startAutoStroke() {
        val p = autoStrokes[autoStroke].points
        autoRake.start(p[0], p[1], autoStrokes[autoStroke].kind)
        if (p.size >= 4) {
            val d = hypot(p[2] - p[0], p[3] - p[1]).coerceAtLeast(0.001f)
            autoRake.dirX = (p[2] - p[0]) / d; autoRake.dirY = (p[3] - p[1]) / d; autoRake.hasDir = true
        }
        autoIndex = 0
        autoBudget = 0f
    }

    // One frame of the auto rake (about 30 a second, only while the garden is on screen)
    private fun autoStep() {
        if (!autoOn || bitmap == null || autoResizing) return
        if (windowVisibility != VISIBLE) return   // carries on when the window shows again
        val now = SystemClock.uptimeMillis()
        val dt = ((now - autoLast) / 1000f).coerceIn(0f, 0.05f)
        autoLast = now
        when (autoStage) {
            0 -> {
                // a slow sweep of smooth sand across the tray
                autoClock += dt
                val to = if (autoClock >= SMOOTH_SECONDS) gw else (gw * autoClock / SMOOTH_SECONDS).toInt()
                smoothRange(autoSmoothedTo, to)
                autoSmoothedTo = max(autoSmoothedTo, to)
                if (soundOn) sound.level(0.2f)
                if (autoClock >= SMOOTH_SECONDS) nextAutoStage()
            }
            1, 2 -> walkAutoStroke(dt)
            3 -> {
                sound.level(0f)
                autoClock += dt
                if (autoClock >= REST_SECONDS) nextAutoStage()
            }
        }
        redrawDirty()
        invalidate()
        postDelayed(autoFrame, 33)
    }

    private fun walkAutoStroke(dt: Float) {
        if (autoLift > 0f) {
            // the rake is lifted: it fades out where it was, moves, and fades in at the next stroke
            sound.level(0f)
            autoLift -= dt
            if (!autoLiftMoved && autoLift <= LIFT_SECONDS / 2) { autoLiftMoved = true; startAutoStroke() }
            return
        }
        val stroke = autoStrokes[autoStroke]
        val p = stroke.points
        val n = p.size / 2
        var budget = autoBudget + dt * if (stroke.kind == RAKE) RAKE_SPEED else STICK_SPEED
        var onSand = false
        while (budget >= 1f && autoIndex < n - 1) {
            autoIndex++
            val px = p[2 * autoIndex]
            val py = p[2 * autoIndex + 1]
            rakeTo(autoRake, px, py, 1f)
            // over the frame of the tray the rake is in the air: no time spent there
            if (px >= 0f && py >= 0f && px < gw && py < gh) { budget -= 1f; onSand = true }
        }
        autoBudget = min(budget, 2f)
        if (soundOn) sound.level(if (onSand) (if (stroke.kind == RAKE) 0.3f else 0.2f) else 0f)
        if (autoIndex >= n - 1) {
            autoStroke++
            if (autoStroke >= autoStrokes.size) nextAutoStage()
            else { autoLift = LIFT_SECONDS; autoLiftMoved = false }
        }
    }

    // ---- the patterns (sacred geometry and classic raking), in squares ----

    private fun patternStrokes(p: Int): List<Stroke> {
        val cx = gw / 2f
        val cy = gh / 2f
        val size = min(gw, gh).toFloat()
        val far = hypot(gw.toFloat(), gh.toFloat()) / 2
        val rakeWidth = teeth(RAKE) * toothGap(RAKE)
        return when (AUTO_PATTERNS[p]) {
            // straight rows, back and forth along the long side of the tray
            "Rows" -> rows(rakeWidth, 0f)
            // the same, as gentle waves
            "Waves" -> rows(rakeWidth, 9f)
            // one unbroken spiral from the middle outwards, each turn a rake's width from the last
            "Spiral" -> {
                val pts = ArrayList<Float>()
                var a = 0f
                var r = rakeWidth * 0.5f
                while (r < far + rakeWidth) {
                    pts.add(cx + r * cos(a)); pts.add(cy + r * sin(a))
                    a += 1f / r
                    r = rakeWidth * 0.5f + rakeWidth * a / (2 * PI).toFloat()
                }
                listOf(Stroke(pts.toFloatArray(), RAKE))
            }
            // circles spreading out from the middle, like a stone dropped in still water
            "Ripples" -> {
                val list = ArrayList<Stroke>()
                var r = rakeWidth
                while (r < far + rakeWidth / 2) { list.add(Stroke(circle(cx, cy, r), RAKE)); r += rakeWidth }
                list
            }
            // seven circles of the same size, each passing through the middle of its neighbours, inside a larger one
            "Seed of Life" -> {
                val r = size * 0.17f
                val list = arrayListOf(Stroke(circle(cx, cy, r), STICK))
                for (k in 0 until 6) {
                    val a = k * PI.toFloat() / 3
                    list.add(Stroke(circle(cx + r * cos(a), cy + r * sin(a), r), STICK))
                }
                list.add(Stroke(circle(cx, cy, r * 2), STICK))
                list.add(Stroke(circle(cx, cy, r * 2 + 7), STICK))
                list
            }
            // an eight-petalled lotus (a rose curve) inside two circles
            "Lotus" -> {
                val r = size * 0.36f
                val raw = ArrayList<Float>()
                var t = 0f
                while (t <= 2 * PI.toFloat() + 0.001f) {
                    val rr = r * cos(4 * t)
                    raw.add(cx + rr * cos(t)); raw.add(cy + rr * sin(t))
                    t += 0.002f
                }
                listOf(Stroke(resample(raw.toFloatArray(), raw.size / 2), STICK),
                    Stroke(circle(cx, cy, r * 1.1f), STICK), Stroke(circle(cx, cy, r * 1.2f), STICK))
            }
            // six golden spirals turning out from the middle (each grows by the golden ratio every quarter turn)
            else -> {
                val grow = (ln(1.618034) / (PI / 2)).toFloat()
                val start = 9f   // a little way out, so the six arms don't tangle in the middle
                val end = ln(size * 0.48f / start) / grow
                List(6) { k ->
                    val turn = k * PI.toFloat() / 3
                    val raw = ArrayList<Float>()
                    var t = 0f
                    while (t <= end) {
                        val r = start * exp(grow * t)
                        raw.add(cx + r * cos(t + turn)); raw.add(cy + r * sin(t + turn))
                        t += 0.5f / r
                    }
                    Stroke(resample(raw.toFloatArray(), raw.size / 2), STICK)
                }
            }
        }
    }

    // Rows (or waves) a rake's width apart along the long side, starting just off the sand so the ends are clean
    private fun rows(spacing: Float, wave: Float): List<Stroke> {
        val across = gw >= gh                       // rows run left-right when the tray is wider than tall
        val length = (if (across) gw else gh).toFloat()
        val depth = (if (across) gh else gw).toFloat()
        val list = ArrayList<Stroke>()
        var c = spacing / 2
        var k = 0
        while (c - spacing / 2 < depth) {
            val n = (length + 24).toInt()
            val forward = k % 2 == 0                // back and forth, as you'd walk it
            val pts = FloatArray(2 * n) { j ->
                val i = j / 2
                val along = if (forward) i - 12f else length + 12f - i
                val side = c + wave * sin(along * 2 * PI.toFloat() / 120f)
                if (j % 2 == 0) (if (across) along else side) else (if (across) side else along)
            }
            list.add(Stroke(pts, RAKE))
            c += spacing
            k++
        }
        return list
    }

    // A full circle, a little past the start so the join doesn't show
    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val start = Random.nextFloat() * 6.283f
        val steps = ceil((2 * PI + 0.3) * r).toInt()
        return FloatArray(2 * (steps + 1)) { j ->
            val a = start + (2 * PI.toFloat() + 0.3f) * (j / 2) / steps
            if (j % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }
    }

    // Rings close around each placed thing, with the pattern's tool
    private fun ringStrokes(): List<Stroke> {
        val half = teeth(autoKind) * toothGap(autoKind) / 2
        val count = if (autoKind == RAKE) 2 else 3
        val list = ArrayList<Stroke>()
        for (it in items) for (k in 0 until count) {
            list.add(Stroke(circle(it.x, it.y, it.r * footprint(it.kind) + 2f + half + k * 2 * half), autoKind))
        }
        return list
    }

    // The auto rake itself, seen from above: the wooden head with its teeth and the handle pointing the way it's
    // pulled (or the smoothing board sweeping across). It fades out and in when lifted between strokes.
    private fun drawAutoRake(canvas: Canvas) {
        if (!autoOn || autoResizing) return
        if (autoStage == 0) {
            val x = inset + autoSmoothedTo * cell
            paint.color = SHADOW_SOFT
            canvas.drawRect(x + cell * 1.5f, inset, x + cell * 5.5f, inset + gh * cell, paint)
            paint.color = WOOD
            canvas.drawRect(x - cell * 2f, inset, x + cell * 2f, inset + gh * cell, paint)
            return
        }
        if (autoStage == 3) return
        val px = autoRake.x
        val py = autoRake.y
        if (px < -4 || py < -4 || px > gw + 4 || py > gh + 4) return
        val fade = if (autoLift > 0f) (if (autoLiftMoved) 1 - autoLift / (LIFT_SECONDS / 2) else (autoLift - LIFT_SECONDS / 2) / (LIFT_SECONDS / 2)) else 1f
        val alpha = (255 * fade.coerceIn(0f, 1f)).toInt()
        if (alpha <= 0) return
        val half = teeth(autoRake.kind) * toothGap(autoRake.kind) / 2
        canvas.withSave {
            translate(inset + px * cell, inset + py * cell)
            rotate(Math.toDegrees(atan2(autoRake.dirY, autoRake.dirX).toDouble()).toFloat())
            val c = cell
            if (autoRake.kind == RAKE) {
                // shadow, handle, head, teeth
                paint.color = SHADOW_SOFT; paint.alpha = alpha * 60 / 255
                drawRect(-1.6f * c + 2 * c, -(half + 2) * c + 2 * c, 1.6f * c + 2 * c, (half + 2) * c + 2 * c, paint)
                paint.color = HANDLE; paint.alpha = alpha
                drawRoundRect(0f, -1.2f * c, 80f * c, 1.2f * c, c, c, paint)
                paint.color = WOOD; paint.alpha = alpha
                drawRoundRect(-1.8f * c, -(half + 2) * c, 1.8f * c, (half + 2) * c, c, c, paint)
                paint.color = WOOD_EDGE; paint.alpha = alpha
                for (k in 0 until teeth(RAKE)) drawCircle(-1.8f * c, (-half + toothGap(RAKE) * (k + 0.5f)) * c, 0.9f * c, paint)
            } else {
                // a bamboo stick, its tip in the sand
                paint.color = HANDLE; paint.alpha = alpha
                drawRoundRect(0f, -1.1f * c, 70f * c, 1.1f * c, c, c, paint)
                paint.color = WOOD_EDGE; paint.alpha = alpha
                drawCircle(0f, 0f, 1.4f * c, paint)
            }
            paint.alpha = 255
        }
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.withTranslation(offX, offY) { drawTray(this, bmp) }
        // keep the flame flickering (about 25 times a second, only while a lantern is out and this is on screen)
        if (lampX.isNotEmpty()) postInvalidateDelayed(40)
    }

    // The tray, where it sits in this view
    private fun drawTray(canvas: Canvas, bmp: Bitmap) {
        // the wooden tray
        paint.shader = null
        paint.color = WOOD
        canvas.drawRoundRect(0f, 0f, trayW, trayH, dp(8f), dp(8f), paint)
        // a small arrow carved into the frame's bottom-left corner: tap the corner to bring the icons back
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.6f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = FRAME_MARK
        val my = trayH - inset / 2
        canvas.drawLine(dp(14f), my + dp(1.5f), dp(19f), my - dp(1.5f), paint)
        canvas.drawLine(dp(19f), my - dp(1.5f), dp(24f), my + dp(1.5f), paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        canvas.withClip(sandRect) {
            drawBitmap(bmp, null, gridRect, sandPaint)
            drawRect(sandRect, grainPaint)
            drawGrid(this)
            for (it in items) if (it.kind == FLOWERS) drawFlowers(this, it)
            drawAutoRake(this)
            paint.shader = null
            // darker: a deep blue wash, like dusk
            if (Color.alpha(tint) > 0) {
                paint.color = tint
                drawRect(sandRect, paint)
            }
            // the lanterns' flame light, added on top (faint in daylight, bright in the dark), then the lanterns themselves
            for (layer in lampLights) {
                lampPaint.alpha = (255 * flicker(layer) * lampPower).toInt().coerceIn(0, 255)
                lampRect.set(inset + layer.ox * cell, inset + layer.oy * cell,
                    inset + (layer.ox + LAMP_SIZE) * cell, inset + (layer.oy + LAMP_SIZE) * cell)
                drawBitmap(layer.picture, null, lampRect, lampPaint)
            }
            drawFireflyLight(this)
            drawFireflies(this)
        }
        paint.shader = null
        // a dark line where the sand meets the frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.5f)
        paint.color = WOOD_EDGE
        canvas.drawRect(sandRect, paint)
        paint.style = Paint.Style.FILL
    }

    // A flower clump's blossoms, drawn sharply: five oval petals each, darker at the base and lit on the side
    // towards the light, with a golden centre
    private fun drawFlowers(canvas: Canvas, it: Item) {
        val petal = petalColour(it)
        val b = it.blobs
        paint.shader = null
        for (k in 0 until b.size / 4) {
            val bx = inset + (it.x + b[4 * k]) * cell
            val by = inset + (it.y + b[4 * k + 1]) * cell
            val size = b[4 * k + 2] * cell
            val spin = b[4 * k + 3]
            for (p in 0 until 5) {
                val a = Math.toRadians((spin + p * 72f).toDouble())
                val ax = cos(a).toFloat()
                val ay = sin(a).toFloat()
                // three layers: the petal, then smaller and lighter towards the light
                for (layer in 0 until 3) {
                    val scale = PETAL_SCALE[layer]
                    val shift = PETAL_SHIFT[layer] * size * 0.64f
                    paint.color = tone(petal, PETAL_TONE[layer])
                    canvas.withSave {
                        translate(bx + ax * size * 0.5f + lightX * shift, by + ay * size * 0.5f + lightY * shift)
                        rotate((spin + p * 72f))
                        drawOval(-size * 0.52f * scale, -size * 0.3f * scale, size * 0.52f * scale, size * 0.3f * scale, paint)
                    }
                }
            }
            paint.color = FLOWER_EYE_RING
            canvas.drawCircle(bx, by, size * 0.26f, paint)
            paint.color = FLOWER_EYE
            canvas.drawCircle(bx + lightX * size * 0.05f, by + lightY * size * 0.05f, size * 0.19f, paint)
        }
    }

    // The garden as a picture, the size it is on screen (for "Save picture")
    fun picture(): Bitmap? {
        if (width == 0 || height == 0) return null
        return createBitmap(width, height).also { draw(Canvas(it)) }
    }

    // ---------------- Saving ----------------

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        removeCallbacks(hush)
        removeCallbacks(autoFrame)
        removeCallbacks(flyFrame)
        removeCallbacks(saveLater)
        removeCallbacks(autoAfterResize)
        flyRunning = false
        autoResizing = false
        anim?.end()
        sound.stop()
        save()
        super.onDetachedFromWindow()
    }

    // Saves a few seconds after a change has finished (and not more often), so the garden isn't lost if the app
    // is closed suddenly (by Android, or by installing a new version) while its window is open
    private val saveLater = Runnable { save() }

    private fun saveSoon() {
        removeCallbacks(saveLater)
        postDelayed(saveLater, 3000)
    }

    fun save() {
        if (!changed || gw == 0) return
        try {
            DataOutputStream(GZIPOutputStream(saveFile.outputStream()).buffered()).use { out ->
                out.writeInt(3)   // file version
                out.writeInt(gw); out.writeInt(gh)
                // heights squeezed into one byte each (plenty for sand)
                val bytes = ByteArray(gw * gh) { ((heights[it].coerceIn(-2f, 2f) + 2f) * 63.5f).toInt().toByte() }
                out.write(bytes)
                out.writeInt(items.size)
                for (s in items) {
                    out.writeInt(s.kind)
                    out.writeFloat(s.x); out.writeFloat(s.y); out.writeFloat(s.r)
                    out.writeFloat(s.aspect); out.writeFloat(s.angle); out.writeInt(s.color)
                    out.writeInt(s.seed); out.writeInt(s.rings)
                }
                out.writeInt(flies.count { it.leaving < 0f })
            }
            changed = false
        } catch (e: Exception) { }
    }

    // Returns the saved grid size and heights (and puts the saved items back), or null if there's nothing saved.
    // (Version 1 files, from the first Zen Garden, only had stones; version 3 added the fireflies.)
    private fun load(): Triple<Int, Int, FloatArray>? = try {
        if (!saveFile.exists()) null
        else DataInputStream(GZIPInputStream(saveFile.inputStream()).buffered()).use { inp ->
            val version = inp.readInt()
            if (version !in 1..3) return null
            val w = inp.readInt(); val h = inp.readInt()
            val bytes = ByteArray(w * h)
            inp.readFully(bytes)
            val n = inp.readInt()
            items.clear()
            repeat(n) {
                val kind = if (version == 1) STONE else inp.readInt()
                val x = inp.readFloat(); val y = inp.readFloat(); val r = inp.readFloat()
                val aspect = inp.readFloat(); val angle = inp.readFloat(); val color = inp.readInt()
                val seed = if (version == 1) Random.nextInt() else inp.readInt()
                val rings = if (version == 1) 0 else inp.readInt()
                // (lanterns were bigger before: they all come back at today's size)
                items.add(Item(kind, x, y, if (kind == LANTERN) LANTERN_SIZE else r, aspect, angle, color, seed, rings))
            }
            savedFlies = if (version >= 3) inp.readInt().coerceIn(0, MAX_FLIES) else 0
            Triple(w, h, FloatArray(w * h) { (bytes[it].toInt() and 0xFF) / 63.5f - 2f })
        }
    } catch (e: Exception) { items.clear(); null }
}
