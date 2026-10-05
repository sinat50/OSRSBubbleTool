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
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
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
// The sand isn't separate grains: it's a grid of heights, RES (1.5) squares per dp. The rake sets the heights it passes
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

        // How fine the sand is: squares per dp. Every size and speed below that's "in squares" is written as so many
        // dp times RES, so changing it keeps everything the same size on screen, just finer or coarser. (It was 1
        // until October 2026; 1.5 makes curves and outlines smoother for about 2¼ times the work while raking.
        // Gardens saved at another fineness are converted when they're opened.)
        const val RES = 1.5f

        private const val MAX_ITEMS = 16
        private const val MAX_UNDO = 5
        private const val MAX_RINGS = 6
        private const val MAX_FLIES = 12
        private const val LANTERN_SIZE = 10f * RES   // a lantern's roof, in squares from its middle to a corner
        private const val LAMP_GLOW = 13f * RES      // the size its light is worked out for (kept from the bigger lanterns)
        private val LAMP_SIZE = 2 * ceil(91 * RES).toInt() + 1   // each lantern's light picture, in squares (its reach, 7 x 13 dp, each way)
        private const val GRID = 32f * RES           // the triangle grid's spacing (32 dp): still easy to hit with a finger
        // the grid in dark brown on the light sands, and in white on the black sand, so it always shows
        private val GRID_LINE = Color.argb(75, 70, 50, 25)
        private val GRID_POINT = Color.argb(150, 70, 50, 25)
        private val GRID_LINE_ON_DARK = Color.argb(75, 255, 255, 255)
        private val GRID_POINT_ON_DARK = Color.argb(150, 255, 255, 255)
        private const val FLY_LOW = 24f * RES     // how high fireflies fly above the sand, in squares, and how
        private const val FLY_HIGH = 32f * RES    // high they climb while glowing (the 'J' of a real firefly's flash)
        private val FLY_REACH = (64 * RES).toInt()   // how far a firefly's light reaches, in squares
        // a firefly's light is soft, so it's worked out only every few squares (every 2 dp or so), which keeps it cheap
        private val FLY_STEP = max(1, (2 * RES).roundToInt())
        // The auto rake's patterns, in the order it rakes them
        val AUTO_PATTERNS = listOf("Rows", "Seed of Life", "Spiral", "Lotus", "Waves", "Golden Spiral", "Ripples",
            "Rose Window", "Spirograph", "Lissajous", "Basket Weave", "Square Spiral", "Flower of Life", "Mandala",
            "Seven Star", "Rain", "Chevrons")   // (raked in a random order, each once before any comes round again)
        private const val RAKE_SPEED = 30f * RES  // squares a second (30 dp): a slow, steady walking pace
        private const val STICK_SPEED = 40f * RES
        private const val SMOOTH_SECONDS = 4f
        private const val LIFT_SECONDS = 1.2f
        private const val REST_SECONDS = 6f
        private val HANDLE = "#A07E4E".toColorInt()
        private val SHADOW_SOFT = Color.argb(70, 0, 0, 0)
        // Heights are in squares too (so slopes, and so the shading, look the same at any fineness)
        private const val DEPTH = 1f * RES        // how deep a groove is (and how high a ridge), in squares
        private const val STAMP_LEN = 1.6f        // how far a stamp of the rake reaches forwards and back, at least, in squares
                                                  // (it's stamped once a square, so this one doesn't change with RES)
        private const val HAND_STRING = 4f * RES  // how far the rake trails behind your finger, in squares (see followFinger)
        private const val TAPER = 8f * RES        // how far the rake takes to sink in at a stroke's start, and lift out at its end
        private val WOOD = "#6B4B24".toColorInt()
        private val WOOD_EDGE = "#4A3518".toColorInt()
        // The wooden frame (see drawFrame): two of the boards a touch darker (no two boards are quite alike), the
        // joins between boards and blocks, and the carvings' pale cut wood and shaded edge
        private val BOARD_SHADE = Color.argb(22, 0, 0, 0)
        private val WOOD_JOIN = Color.argb(110, 12, 6, 2)
        private val CARVING_LIT = Color.argb(200, 176, 138, 96)
        private val CARVING_DARK = Color.argb(220, 14, 8, 3)
        // The carved corners' size (from the tray's outer corner along each side), and which is which (see cornerAt)
        const val CORNER_DP = 32f
        const val FRAME_DP = 7.5f                 // the frame's width
        const val CORNER_EXIT = 1
        const val CORNER_MOVE = 2
        const val CORNER_RESIZE = 3
        const val CORNER_TOOLS = 4
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
        // The thread saving gardens to storage (one at a time, in order, at background importance so it always
        // gives way to the garden and to the game)
        private val SAVE_THREAD by lazy { backgroundThread("ZenSave") }
        // The thread the garden's own drawing works out the lantern light on while you rake (see relightNow), and its
        // own copy of the light maths
        private val LIGHT_THREAD by lazy { backgroundThread("ZenLight") }
        private val WORKER_LIGHT by lazy { LightMath() }

        // A lantern's warm flame light (`light` of it), coloured by what it falls on: a placed thing (`alb`), or the
        // sand (`sandColor`)
        private fun warmLight(light: Float, alb: Int, sandColor: Int): Int {
            if (light <= 0f) return Color.BLACK
            val k = min(light, 2.2f)
            val c = if (alb != 0) alb else sandColor
            return Color.rgb(min(255f, Color.red(c) * k).toInt(), min(255f, Color.green(c) * 0.68f * k).toInt(),
                min(255f, Color.blue(c) * 0.36f * k).toInt())
        }

        // The bubble is closing: wait (up to 3 seconds) for any garden still being written to storage
        fun waitForSaves() {
            if (!saving) return
            try { SAVE_THREAD.submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
        }
        @Volatile private var saving = false   // (a save has ever been handed to the save thread)

        private fun backgroundThread(name: String) = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, name).apply { isDaemon = true }
        }

        // How much light leaves a lantern's fire box in a direction (in radians, from the lantern's turn): each of
        // the six sides is an open window throwing a beam, with the corner posts between them casting thin dark spokes
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

        // the sunlight's drop for each diagonal step towards the top-left (it comes in low, so shadows are long)
        private const val SHADOW_DROP = 0.39f * 1.2f
        private const val NO_LINE = -1e9f          // (no sunlight line yet: nothing casts a shadow)
        private const val T_RAKE = 0             // (the steps timed in test builds, see timed)
        private const val T_SHADE = 1
        private const val T_LIGHT = 2
        private const val T_DRAW = 3
        private val OBJ_STEPS = ceil(12 * RES).toInt()   // the most steps a ridge's shadow is looked for, in squares
        // how far around changed sand the lantern light is worked out again (a ridge's shade on it reaches 14 dp)
        private val LIGHT_PAD = ceil(14 * RES).toInt()
        // how far out the lantern light looks for things blocking it, in squares (1 to 50 dp)
        private val LAMP_STEPS = floatArrayOf(1f, 2f, 3f, 4f, 6f, 8f, 11f, 15f, 20f, 26f, 33f, 41f, 50f).map { it * RES }.toFloatArray()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    // ---------------- Settings (set by the Game Room) ----------------

    var soundOn = true
    var volume = 1f                          // 1 = normal
        set(v) { field = v; sound.volume = v }
    // Scale to fit (on by default): the garden keeps its squares and is drawn bigger or smaller to fit the
    // window, centred, with clear space at the sides if the window is a different shape. Off: RES squares per dp,
    // so resizing adds sand or cuts it off.
    var scaleToFit = true
        set(v) {
            if (field == v) return
            field = v
            if (width > 0) onSizeChanged(width, height, width, height)
            requestLayout()   // so the icons move to the tray's new corner
        }
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
            frameChanged = true   // (the dusk falls on the frame too)
            invalidate()
        }
    var onUndoChanged: (() -> Unit)? = null
    val canUndo get() = undoStack.isNotEmpty()

    // ---------------- The sand ----------------

    private var gw = 0                       // grid size, in squares
    private var gh = 0
    private var heights = FloatArray(0)
    private var bitmap: Bitmap? = null
    private var covered = BooleanArray(0)    // squares something is placed on: the rake leaves them alone
    private var handMark = BooleanArray(0)   // squares your finger's stroke has raked (for Steady hand)
    private var markX0 = Int.MAX_VALUE       // and the box around them
    private var markY0 = Int.MAX_VALUE
    private var markX1 = -1
    private var markY1 = -1
    // The smooth drawing (Android 13 and newer, see SandRenderer), or null for the garden's own square-by-square
    // drawing (older phones, or if the graphics chip can't run it)
    private var smooth: SandRenderer? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) SandRenderer(LAMP_GLOW, LAMP_STEPS, RES, FLY_REACH.toFloat(), MAX_FLIES) else null
    private var plainColours = false         // shade and relight work out plain colours for now (for Save picture)
    private val smoothKept = smooth          // (kept, for the test switch below)

    // Battery saver (the Garden card's switch, on Android 13 and newer): draw with the garden's own drawing, as phones
    // older than Android 13 do, instead of the smooth drawing. It looks a little softer, but it's much lighter on the
    // graphics chip (measured October 2026: about 1% busy instead of 4% sitting with lanterns and fireflies out, and
    // 3% instead of 14% while raking) and no heavier on the processor.
    var batterySaver = false
        set(v) {
            if (field == v) return
            field = v
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (v) { smooth?.release(); smooth = null }
                else smoothKept?.takeIf { !it.broken }?.let {
                    smooth = it
                    if (gw > 0) it.resize(gw, gh)
                    if (bitmap != null) letGoOwnDrawing()
                }
            }
            lampLights.forEach { it.smooth = null }   // (each lantern's light is set up again for the drawing in use)
            if (bitmap != null) { markCovered(); redrawAll() }
            invalidate()
        }
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

    // (worked out in dp, so the ripples are the same size at any fineness, then given as a height in squares)
    private fun freshSand(x: Int, y: Int): Float {
        val u = x / RES
        val v = y / RES
        return RES * (0.14f * sin(u * 0.045f + wave[0]) * sin(v * 0.05f + wave[1]) + 0.07f * sin((u + v) * 0.09f + wave[2]) +
            0.05f * sin((u - v) * 0.13f + wave[3]))
    }

    private var offX = 0f                    // where the tray sits in this view (when scaled to fit)
    private var offY = 0f
    private var trayW = 0f                   // the tray's size on screen, frame included
    private var trayH = 0f

    // Where the tray is drawn in this view (it may not fill it when scaled to fit)
    fun trayBounds(out: RectF) = out.set(offX, offY, offX + trayW, offY + trayH)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        inset = dp(FRAME_DP)
        val first = bitmap == null
        val saved = if (first) load() else null
        var regrown = first
        if (scaleToFit) {
            // the garden keeps its own size (the saved one, or the window's at RES squares per dp to start with)
            if (first) {
                if (saved != null) makeGrid(saved.first, saved.second, saved.first, saved.second, saved.third)
                else makeGrid(max(1, ((w - 2 * inset) / dp(1f / RES)).toInt()), max(1, ((h - 2 * inset) / dp(1f / RES)).toInt()), 0, 0, FloatArray(0))
            }
            cell = min((w - 2 * inset) / gw, (h - 2 * inset) / gh).coerceAtLeast(0.01f)
            trayW = gw * cell + 2 * inset
            trayH = gh * cell + 2 * inset
            offX = (w - trayW) / 2
            offY = (h - trayH) / 2
        } else {
            cell = dp(1f / RES)
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
            updateLight(now = true)   // works out the light, then shades the whole tray
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
        handMark = BooleanArray(nw * nh)
        bitmap?.recycle()
        // (the garden's own drawing's picture, twice as fine as the squares, see paintFine; the smooth drawing
        // doesn't use it, so it's kept small there)
        bitmap = if (smooth != null) createBitmap(1, 1) else createBitmap(2 * nw, 2 * nh)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) smooth?.resize(nw, nh)
    }

    // ---------------- The light ----------------

    private val lightX = -0.7071f            // which way the light is, on screen (from the top-left)
    private val lightY = -0.7071f
    private val sunTan = 0.39f               // how high the sun is (lower = longer shadows)
    private var shadowSteps = 6              // how far a ridge's shadow can reach, in squares
    private var shadowRows = FloatArray(0)   // the sunlight line along the row above and this one (see shade)
    private var tint = 0                     // a dark blue washed over the garden (none at full brightness)
    private var lampPower = 0.3f             // how strongly the lanterns' light shows: faint in daylight, full in the dark

    // A fixed afternoon light from the top-left: works out the shadows' reach and the sand's colours, then shades
    // the whole tray (when the garden opens, its size changes, or the sand colour changes). `now`: all at once
    // (when it opens, so it never shows half drawn); otherwise a band at a time (see redrawAll).
    private fun updateLight(now: Boolean = false) {
        shadowSteps = ceil(2.2f / sunTan * RES).toInt().coerceIn(3, OBJ_STEPS - 1)
        val sandColors = SANDS[sand]
        for (i in 0 until 256) {
            shades[i] = if (i < 128) ColorUtils.blendARGB(sandColors[0], sandColors[1], i / 128f)
            else ColorUtils.blendARGB(sandColors[1], sandColors[2], (i - 128) / 127f)
        }
        items.forEach { makeLooks(it) }
        if (bitmap == null) return
        if (now) {
            redrawRow = -1   // (anything waiting is done here)
            shade(0, 0, gw - 1, gh - 1)
            relight(0, 0, gw - 1, gh - 1)
            invalidate()
        } else redrawAll()
    }

    // Colours the squares in a box from their heights: slopes facing the light are lit, the others shaded.
    // Placed things are part of the same surface (their own height and colour), so they're lit and shaded just
    // like it (they don't cast shadows from this light, though: only the sand's ridges do).
    private fun shade(x0: Int, y0: Int, x1: Int, y1: Int) {
        if (bitmap == null) return
        val ax = max(0, x0); val ay = max(0, y0); val bx = min(gw - 1, x1); val by = min(gh - 1, y1)
        if (ax > bx || ay > by) return
        // the smooth drawing gets each square's values instead of a colour (see SandRenderer)
        val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !plainColours) smooth else null
        // (the garden's own drawing keeps each square's values, made the first time they're needed)
        if (gpu == null && squareBright.size != gw * gh) {
            squareBright = FloatArray(gw * gh); squareLight = FloatArray(gw * gh); squareSlope = FloatArray(gw * gh)
        }
        val h = heights
        val lx = lightX * 1.414f
        val ly = lightY * 1.414f
        // Shadows: a ridge of sand casts one on the squares after it, away from the light (top left), as far as it
        // stands higher than the sunlight's slope down from its top. Rather than look back along the light from
        // every square (the same squares again and again), the highest "sunlight line" is carried along each
        // diagonal from square to square, dropping a little each step: so the rows start a few squares before the
        // box. (Placed things don't cast these shadows: they'd only clutter the garden.)
        val sx0 = max(0, ax - shadowSteps)
        val sy0 = max(0, ay - shadowSteps)
        val rowW = bx - sx0 + 1
        if (shadowRows.size < 2 * rowW) shadowRows = FloatArray(2 * rowW)
        var prev = 0                             // where the row above starts in shadowRows (it takes turns with this one)
        for (y in sy0..by) {
            val cur = if (prev == 0) rowW else 0
            val up = if (y > 0) -gw else 0
            val down = if (y < gh - 1) gw else 0
            for (x in sx0..bx) {
                val i = y * gw + x
                // the sunlight line arriving here: from the square up and to the left (its sand, or the line it was
                // already under), dropping one diagonal step
                val line = if (x > sx0 && y > sy0) {
                    val j = i - gw - 1
                    max(if (objAlbedo[j] == 0) heights[j] else NO_LINE, shadowRows[prev + x - 1 - sx0]) - SHADOW_DROP
                } else NO_LINE
                shadowRows[cur + x - sx0] = line
                if (y < ay || x < ax) continue
                val own = surface(i)
                val shadow = max(0f, line - own)
                val alb = objAlbedo[i]
                // a placed thing here: lit by its own slopes (f), which way it slopes (for the lantern light)
                var f = 0.8f
                var thingX = 0f
                var thingY = 0f
                if (alb != 0) {
                    val left = if (x > 0) thingAt(i - 1, own) else own
                    val right = if (x < gw - 1) thingAt(i + 1, own) else own
                    val above = thingAt(i + up, own)
                    val below = thingAt(i + down, own)
                    val slope = -((right - left) * lx + (below - above) * ly)
                    val hollow = (left + right + above + below - 4 * own) * RES   // (× RES: the same at any fineness)
                    f = (0.72f + slope.coerceIn(-3f, 3f) * 0.2f - hollow.coerceIn(-3f, 3f) * 0.08f -
                        min(shadow / RES * 0.1f, 0.35f)).coerceIn(0.2f, 1.45f)
                    thingX = (right - left) / 2
                    thingY = (below - above) / 2
                }
                val left = if (x > 0) h[i - 1] else h[i]
                val right = if (x < gw - 1) h[i + 1] else h[i]
                val slope = -((right - left) * lx + (h[i + down] - h[i + up]) * ly)
                // how hollow the square is: groove bottoms are a little darker and ridge tops lighter whichever
                // way the line runs (without this, lines raked towards the light come out almost invisible).
                // Measured against the squares 2 away, not the next ones: one square on its own is only about 3
                // screen pixels, and square-by-square differences made curved lines look stepped, like a game
                // without anti-aliasing. (The fine grain is drawn over the top at full screen sharpness instead.)
                // (heights are in squares, so the hollow, the height and the shadow are evened out by RES to look
                // the same at any fineness; the slope already is)
                val hollow = (h[i - min(x, 2)] + h[i + min(gw - 1 - x, 2)] + h[i - min(y, 2) * gw] +
                    h[i + min(gh - 1 - y, 2) * gw] - 4 * h[i]) / 3f * RES
                // how bright the sand is (128 = plain sand, lower in shade, higher in the sun)
                val bright = 128f + slope * 30f + h[i] / RES * 16f - hollow * 34f - min(shadow / RES * 40f, 45f)
                if (gpu != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // (the slope the lantern light sees: the placed thing's where there is one, else the sand's)
                    val sx = if (alb != 0) thingX else (right - left) / 2
                    val sy = if (alb != 0) thingY else (h[i + down] - h[i + up]) / 2
                    gpu.putSquare(i, bright / 255f, f, sx, sy, alb, if (alb != 0) objCover[i] else 0f, own)
                    continue
                }
                // the garden's own drawing: the square's values, coloured below (see paintFine)
                squareBright[i] = bright
                squareLight[i] = f
                squareSlope[i] = slope
            }
            prev = cur
        }
        if (gpu != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) gpu.changed(ax, ay, bx, by)
        else paintFine(ax, ay, bx, by)
    }

    // The garden's own drawing (older phones, and Save picture): the sand's picture is twice as fine as its squares,
    // each of its pixels coloured from the four nearest squares' values blended together (brightness, a placed
    // thing's colour and light and how much of the square it covers) rather than stretching squares already
    // coloured, so curves and outlines come out smoother; each grain's wobble and glint is one small pixel.
    private var squareBright = FloatArray(0)     // each square's brightness (see shade), its placed thing's light,
    private var squareLight = FloatArray(0)      // and its slope towards the light (for the glints)
    private var squareSlope = FloatArray(0)
    private var fineColors = IntArray(0)         // the picture's pixels

    // Colours again the picture's pixels that the squares in a box (and so their neighbours) affect
    private fun paintFine(ax: Int, ay: Int, bx: Int, by: Int) {
        val fw = 2 * gw
        val fh = 2 * gh
        if (fineColors.size != fw * fh) fineColors = IntArray(fw * fh)
        // (the picture is made full size the first time it's needed: the smooth drawing doesn't use it)
        val bmp = bitmap?.takeIf { it.width == fw && it.height == fh } ?: createBitmap(fw, fh).also { bitmap?.recycle(); bitmap = it }
        // (pixel 2i is blended from squares i-1 and i, pixel 2i+1 from squares i and i+1)
        val px0 = max(0, 2 * ax - 1); val px1 = min(fw - 1, 2 * bx + 2)
        val py0 = max(0, 2 * ay - 1); val py1 = min(fh - 1, 2 * by + 2)
        for (fy in py0..py1) {
            val ty = (fy + 0.5f) / 2 - 0.5f
            val j = floor(ty).toInt()
            val wy = ty - j
            val r0 = j.coerceIn(0, gh - 1) * gw
            val r1 = (j + 1).coerceIn(0, gh - 1) * gw
            for (fx in px0..px1) {
                val tx = (fx + 0.5f) / 2 - 0.5f
                val k = floor(tx).toInt()
                val wx = tx - k
                val c0 = k.coerceIn(0, gw - 1)
                val c1 = (k + 1).coerceIn(0, gw - 1)
                val a = r0 + c0; val b = r0 + c1; val c = r1 + c0; val d = r1 + c1
                val wa = (1 - wx) * (1 - wy); val wb = wx * (1 - wy); val wc = (1 - wx) * wy; val wd = wx * wy
                // the sand: its brightness, a little wobble from grain to grain, and now and then a glint on the
                // sunny side of a slope
                val n = hash(fx, fy)
                var v = (squareBright[a] * wa + squareBright[b] * wb + squareBright[c] * wc + squareBright[d] * wd).toInt() +
                    ((n ushr 24) and 7) - 3
                if ((n ushr 16) and 1023 < 4 &&
                    squareSlope[a] * wa + squareSlope[b] * wb + squareSlope[c] * wc + squareSlope[d] * wd > 0.3f) v += 45
                var colour = shades[v.coerceIn(0, 255)]
                // a placed thing over it: its colour lit by its own slopes, as much of it as covers the pixel
                val cover = coverAt(a) * wa + coverAt(b) * wb + coverAt(c) * wc + coverAt(d) * wd
                if (cover > 0.002f) {
                    var red = 0f; var green = 0f; var blue = 0f
                    for (q in 0..3) {
                        val sq = when (q) { 0 -> a; 1 -> b; 2 -> c; else -> d }
                        val alb = objAlbedo[sq]
                        if (alb == 0) continue
                        val w = (when (q) { 0 -> wa; 1 -> wb; 2 -> wc; else -> wd }) * objCover[sq] * squareLight[sq]
                        red += ((alb shr 16) and 255) * w; green += ((alb shr 8) and 255) * w; blue += (alb and 255) * w
                    }
                    val keep = 1 - min(cover, 1f)
                    colour = Color.rgb(min(255f, ((colour shr 16) and 255) * keep + red).toInt(),
                        min(255f, ((colour shr 8) and 255) * keep + green).toInt(), min(255f, (colour and 255) * keep + blue).toInt())
                }
                fineColors[fy * fw + fx] = colour
            }
        }
        bmp.setPixels(fineColors, py0 * fw + px0, fw, px0, py0, px1 - px0 + 1, py1 - py0 + 1)
    }

    // how much of square i a placed thing covers (0 for bare sand)
    private fun coverAt(i: Int) = if (objAlbedo[i] != 0) objCover[i] else 0f

    // The height of the surface at a square: a placed thing's own height there, or the sand's
    private fun surface(i: Int) = if (objAlbedo[i] != 0) solid[i] else heights[i]

    // For shading a placed thing: the height of the thing at a square next to it, or (if that square is bare sand)
    // its own height `own`. A placed thing is lit by its own slopes only: the sand beside it is far lower, and that
    // drop made its outline squares flip between very dark and very bright, which looked stepped.
    private fun thingAt(i: Int, own: Float) = if (objAlbedo[i] != 0) solid[i] else own

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

    // Each lantern's light is its own small picture around it, so each can flicker in its own time.
    // (The picture is only for the garden's own drawing, and about 600 KB a lantern, so it's made the first time it's
    // needed: the smooth drawing never needs it, apart from Save picture, after which it's let go again.)
    private class LampLight(val seed: Int) {
        private var px: IntArray? = null
        private var pic: Bitmap? = null
        val pixels: IntArray
            get() = px ?: IntArray(LAMP_SIZE * LAMP_SIZE).also { it.fill(Color.BLACK); px = it }
        val picture: Bitmap
            get() = pic ?: createBitmap(LAMP_SIZE, LAMP_SIZE).also { it.eraseColor(Color.BLACK); pic = it }
        // (moved: squares off the tray stay dark)
        fun clear() { px?.fill(Color.BLACK); pic?.eraseColor(Color.BLACK) }
        fun letGo() { px = null; pic = null }
        var ox = 0                              // where the picture's corner is, in squares
        var oy = 0
        val phase = (seed and 0xFFFF) / 65535f * 1000f          // its own place in the flicker
        val pace = 0.85f + ((seed ushr 16) and 0xFF) / 255f * 0.3f   // and its own speed (0.85-1.15)
        var smooth: SandRenderer.Lamp? = null   // its light in the smooth drawing (Android 13 and newer)
    }
    private val lampLights = ArrayList<LampLight>()

    // Works out the lanterns' light on the squares in a box. The flame sits just under the roof: the light
    // spills out, fading with distance; slopes facing the flame catch more of it; anything standing above the line
    // from a square to the flame (a ridge, a stone, a bonsai) shades it; and the fire box's six windows and
    // corner posts shape it into beams. A little of it bounces around, so the pool is never patchy.
    // (Only for the garden's own drawing, a colour for every square: the smooth drawing works out all of the light
    // itself, per screen pixel, on the graphics chip, see SandRenderer.)
    private fun relight(x0: Int, y0: Int, x1: Int, y1: Int, only: Int = -1) {
        if (lampX.isEmpty()) return
        if (smooth != null && !plainColours) return
        val m = mainLight
        m.take(gw, gh, heights, solid, objAlbedo)
        // warm flame light, coloured by what it falls on (the sand, or a placed thing)
        val sandColor = SANDS[sand][1]
        for (l in lampX.indices) {
            if (only >= 0 && l != only) continue
            val layer = lampLights[l]
            val ax = maxOf(0, x0, layer.ox); val ay = maxOf(0, y0, layer.oy)
            val bx = minOf(gw - 1, x1, layer.ox + LAMP_SIZE - 1); val by = minOf(gh - 1, y1, layer.oy + LAMP_SIZE - 1)
            if (ax > bx || ay > by) continue
            val pixels = layer.pixels
            for (y in ay..by) for (x in ax..bx) {
                val light = m.at(lampX[l], lampY[l], lampR[l], lampTurn[l], x, y)
                pixels[(y - layer.oy) * LAMP_SIZE + (x - layer.ox)] = warmLight(light, objAlbedo[y * gw + x], sandColor)
            }
            layer.picture.setPixels(pixels,(ay - layer.oy) * LAMP_SIZE + (ax - layer.ox), LAMP_SIZE,
                ax - layer.ox, ay - layer.oy, bx - ax + 1, by - ay + 1)
        }
    }

    // The lantern light (for the garden's own drawing), worked out for one lantern at one square
    private class LightMath {
        private var gw = 0
        private var gh = 0
        private var heights = FloatArray(0)
        private var solid = FloatArray(0)
        private var objAlbedo = IntArray(0)
        // the parts of the light at the last square looked at (see at): straight from the flame, a soft fill, and
        // how high the flame stands above the square
        var flame = 0f
        var fill = 0f
        var rise = 0f

        fun take(w: Int, h: Int, heightsNow: FloatArray, solidNow: FloatArray, albedoNow: IntArray) {
            gw = w; gh = h; heights = heightsNow; solid = solidNow; objAlbedo = albedoNow
        }

        private fun surface(i: Int) = if (objAlbedo[i] != 0) solid[i] else heights[i]

        // How much of the light of the lantern at (lx, ly) (roof size lr, turned `turn`) falls on square (x, y)
        fun at(lx: Float, ly: Float, lr: Float, turn: Float, x: Int, y: Int): Float {
            flame = 0f; fill = 0f; rise = 0f
            if (x < 0 || y < 0 || x >= gw || y >= gh) return 0f
            val h = heights
            val i = y * gw + x
            val reach = LAMP_GLOW * 7f
            val dx = lx - x - 0.5f
            val dy = ly - y - 0.5f
            val rho2 = dx * dx + dy * dy
            if (rho2 >= reach * reach) return 0f
            val rho = sqrt(rho2)
            if (rho < lr * 0.9f) return 0f                 // under the roof
            if (rho < lr * 1.15f && objAlbedo[i] != 0) return 0f   // the lantern's own roof (its flame is under it)
            val h0 = surface(i)
            val up = LAMP_GLOW * 1.3f - h0                 // from this square up to the flame
            if (up <= 0f) return 0f
            val d = sqrt(rho2 + up * up)
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
            val facing = max(0f, (-gx * dx - gy * dy + up) / (d * sqrt(gx * gx + gy * gy + 1)))
            val window = windowLight(atan2(-dy, -dx) - turn)
            // walk towards the flame: anything standing above the line to it blocks the light
            val ux = dx / rho
            val uy = dy / rho
            var block = 0f
            for (step in LAMP_STEPS) {
                if (step >= rho - lr * 1.15f) break       // reached the lantern itself: it doesn't shade its own light
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
                block = max(block, (there - (h0 + up * step / rho)) / RES * 0.45f)   // (/ RES: heights are in squares)
                if (block >= 1f) break
            }
            block = min(block, 1f)
            var fade = 1 - rho2 / (reach * reach)
            fade *= fade
            fade *= smoothStep((rho - lr * 0.9f) / (lr * 0.25f))   // fades in right at the roof's edge, no dark ring
            // (the extra bit added to the distance softens the brightest part close to the lantern a little)
            val glow = fade * (LAMP_GLOW / (d + LAMP_GLOW * 0.25f))
            flame = glow * window * (1 - block) * 2f
            fill = glow * 0.25f * window
            rise = up
            return flame * facing + fill
        }
    }
    private val mainLight = LightMath()

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
            if (redrawRow >= 0) { removeCallbacks(redrawMore); post(redrawMore) }   // and a whole-tray redraw
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
        var end = Float.MAX_VALUE                // how long the stroke is, when that's known (it lifts out before it)
        var phase = 0f
        var kind = RAKE
        fun start(px: Float, py: Float, k: Int) {
            x = px; y = py; hasDir = false; len = 0f; end = Float.MAX_VALUE; phase = Random.nextFloat() * 6.28f; kind = k
        }
    }
    private val hand = Raker()
    // the box of squares changed since the last redraw
    private var dirtyX0 = Int.MAX_VALUE
    private var dirtyY0 = Int.MAX_VALUE
    private var dirtyX1 = -1
    private var dirtyY1 = -1

    private fun teeth(kind: Int) = if (kind == RAKE) 5 else 1
    private fun toothGap(kind: Int) = if (kind == RAKE) 6f * RES else 7f * RES   // (6 and 7 dp)
    // how much the brush wipes each time it moves a square (30% per dp, a little less per square when squares are finer)
    private val BRUSH_WIPE = 1 - 0.7f.pow(1 / RES)
    private val toothDepth = floatArrayOf(1f, 0.88f, 1.1f, 0.95f, 1.06f)   // a real rake's teeth are never quite even

    // Drags a rake (or the brush) to (x, y), in squares, in steps of one square.
    // `follow` is how quickly the rake turns to face the way it's going, for each dp it moves (1 = at once, for
    // exact paths).
    private fun rakeTo(r: Raker, x: Float, y: Float, followPerDp: Float = 0.25f) {
        // (it moves a square at a time, and a square is 1/RES dp, so it turns a little less each step)
        val follow = if (followPerDp >= 1f) 1f else 1 - (1 - followPerDp).pow(1 / RES)
        val dx = x - r.x
        val dy = y - r.y
        val dist = hypot(dx, dy)
        // (half a square or more: an exact path's points, just under a square apart, are each followed in turn;
        // skipping every other one made the next move a double one, which bent the presses unevenly and beaded the
        // outer grooves of Steady hand's and Auto's circles)
        if (dist < 0.5f) return
        val ux = dx / dist
        val uy = dy / dist
        if (!r.hasDir) { r.dirX = ux; r.dirY = uy; r.hasDir = true }
        val steps = ceil(dist).toInt()
        val stepLen = dist / steps
        // an exact path: the whole turn spread evenly over the presses, so each is bent the same
        val turnEach = if (follow >= 1f) atan2(r.dirX * uy - r.dirY * ux, r.dirX * ux + r.dirY * uy) / steps else 0f
        val turnCos = cos(turnEach)
        val turnSin = sin(turnEach)
        for (s in 1..steps) {
            var nx: Float
            var ny: Float
            val bend: Float                        // how far it turned, and which way (see stamp)
            if (follow >= 1f) {
                nx = r.dirX * turnCos - r.dirY * turnSin
                ny = r.dirX * turnSin + r.dirY * turnCos
                bend = turnEach
            } else {
                // turn the rake gently towards where it's going, so small wobbles don't twist it
                nx = r.dirX * (1 - follow) + ux * follow
                ny = r.dirY * (1 - follow) + uy * follow
                val len = hypot(nx, ny)
                if (len < 0.001f) { nx = ux; ny = uy } else { nx /= len; ny /= len }
                bend = r.dirX * ny - r.dirY * nx
            }
            r.dirX = nx; r.dirY = ny
            val t = s.toFloat() / steps
            val px = r.x + dx * t
            val py = r.y + dy * t
            r.len += stepLen
            if (r.kind == BRUSH) { brushAt(px, py); continue }
            stamp(r, px, py, max(STAMP_LEN, 0.5f * stepLen + 0.35f), bend / stepLen)
        }
        r.x = x; r.y = y
    }

    // The rake follows your finger as if on a short string: while the finger stays within the string's length
    // nothing happens (so a finger resting on the sand, which always trembles a little, doesn't swivel the rake
    // and scrape a stripe at the end of the line), and once the string is pulled tight the rake glides after it,
    // smoothly, so curves stay round
    private fun followFinger(fx: Float, fy: Float) {
        val d = hypot(fx - hand.x, fy - hand.y)
        if (d <= HAND_STRING) return
        val t0 = System.nanoTime()
        rakeTo(hand, fx - (fx - hand.x) / d * HAND_STRING, fy - (fy - hand.y) / d * HAND_STRING)
        timed(T_RAKE, t0)
    }

    // Your finger has lifted: the rake lifts out of the sand over the last bit of the stroke, carrying straight on
    // the way it was going (towards your finger: it trails it on its string), never turning, so the grooves fade
    // out smoothly instead of stopping dead, and it can't scrape a stripe
    private fun liftOut() {
        if (!hand.hasDir) return
        hand.end = hand.len + TAPER
        rakeTo(hand, hand.x + hand.dirX * TAPER, hand.y + hand.dirY * TAPER, 1f)
        redrawDirty()
    }

    // Presses the rake into the sand at one spot: grooves under the teeth, ridges between them and at the sides.
    // `reachLen` is how far forwards and back the press reaches (along the middle of the rake); `curve` is how
    // sharply, and which way, its path is bending (1 / the bend's radius, in squares). On a bend the press is
    // shaped like a piece of the bend itself, not a straight bar: each square's distance from the line is measured
    // around the bend, so every press agrees on where each groove runs, however far out (straight presses smeared
    // the outer grooves, or left them as a chain of beads), and the outer teeth's presses reach as far as they moved.
    // Only the squares the press can touch are looked at: a thin band across the path (looking at a whole square
    // box around the rake made raking lag).
    private fun stamp(r: Raker, px: Float, py: Float, reachLen: Float, curve: Float) {
        val gap = toothGap(r.kind)
        val teeth = teeth(r.kind)
        val dirX = r.dirX
        val dirY = r.dirY
        val half = teeth * gap / 2
        val outer = half + gap * 0.25f
        // (never bending around a middle nearer than about half the rake's width: the finger's small wobbles can make
        // a bend look sharper than it is for a moment; but Steady hand's tight swirls really do go that tight, and a
        // press shaped for a gentler bend than the real one beads their outer grooves)
        val bend = curve.coerceIn(-1f / (0.45f * outer), 1f / (0.45f * outer))
        val bendy = abs(bend) > 0.0005f          // (nearly straight: plain straight presses)
        val rc = if (bendy) 1 / bend else 0f     // the bend's middle, this far to the side (+ or -) of the rake's middle
        // how far the press reaches forwards/back and sideways, measured straight (on a bend the outer teeth's part
        // is longer, as far round as they moved, and curls in a little)
        val reachA = reachLen * (1 + outer * abs(bend)) + 1
        val reachU = outer + reachA * reachA * abs(bend) + 1
        val reach = hypot(reachA, reachU)
        val x0 = max(0, floor(px - reach).toInt()); val x1 = min(gw - 1, ceil(px + reach).toInt())
        val y0 = max(0, floor(py - reach).toInt()); val y1 = min(gh - 1, ceil(py + reach).toInt())
        if (x0 > x1 || y0 > y1) return
        val h = heights
        val mark = steadyHand && r === hand      // remember what your finger raked, so Steady hand can redo just that
        // the hand presses a little harder or softer as the stroke goes on
        val gone = r.len / RES                   // how far this stroke has gone, in dp
        val press = DEPTH * (1 + 0.12f * sin(gone * 0.05f + r.phase) + 0.06f * sin(gone * 0.13f + r.phase * 2))
        // like a real rake, it's lowered into the sand at the start of a stroke and lifted out at the end, so the
        // grooves fade in and out rather than stopping dead (which left a hard edge, and a strip of half-pressed sand)
        val lift = smoothStep(min(r.len, r.end - r.len) / TAPER)
        if (lift <= 0f) return
        var tx0 = Int.MAX_VALUE; var ty0 = Int.MAX_VALUE; var tx1 = -1; var ty1 = -1   // the squares it touched
        for (y in y0..y1) {
            val cy = y + 0.5f - py
            // where this row crosses the band: |along| <= reachA and |sideways| <= reachU, worked out for x
            var lo = x0 + 0.5f - px
            var hi = x1 + 0.5f - px
            // along = cx * dirX + cy * dirY
            if (abs(dirX) > 1e-4f) {
                val a = (-reachA - cy * dirY) / dirX; val b = (reachA - cy * dirY) / dirX
                lo = max(lo, min(a, b)); hi = min(hi, max(a, b))
            } else if (abs(cy * dirY) > reachA) continue
            // sideways = cy * dirX - cx * dirY
            if (abs(dirY) > 1e-4f) {
                val a = (cy * dirX - reachU) / dirY; val b = (cy * dirX + reachU) / dirY
                lo = max(lo, min(a, b)); hi = min(hi, max(a, b))
            } else if (abs(cy * dirX) > reachU) continue
            if (lo > hi) continue
            val xs = max(x0, floor(lo + px - 0.5f).toInt())
            val xe = min(x1, ceil(hi + px - 0.5f).toInt())
            if (xs > xe) continue
            // (only the squares the press can touch need drawing again)
            if (xs < tx0) tx0 = xs
            if (xe > tx1) tx1 = xe
            if (y < ty0) ty0 = y
            if (y > ty1) ty1 = y
            for (x in xs..xe) {
                val i = y * gw + x
                if (covered[i]) continue
                val cx = x + 0.5f - px
                var u = cy * dirX - cx * dirY               // sideways along the rake
                var along = cx * dirX + cy * dirY           // forwards/backwards from the rake
                if (bendy) {
                    // measured around the bend: sideways = how much nearer the bend's middle than the line is,
                    // forwards/backwards = how far round the bend (at the line)
                    val toMid = rc - u
                    u = rc - kotlin.math.sign(rc) * hypot(along, toMid)
                    along = abs(rc) * atan2(along, toMid * kotlin.math.sign(rc))
                }
                val au = abs(u)
                if (au > outer) continue
                val halfLen = reachLen
                if (abs(along) > halfLen) continue
                // each tooth digs a little deeper or shallower than the next, blending smoothly from one tooth's
                // middle to the next (a sudden step, right on the ridge between two teeth, notched the ridges
                // whenever the rake's direction wobbled a little)
                val tooth = if (teeth == 1) 1f else {
                    val t = ((u + half) / gap - 0.5f).coerceIn(0f, teeth - 1f)
                    val k = min(teeth - 2, t.toInt())
                    toothDepth[k] + (toothDepth[k + 1] - toothDepth[k]) * (t - k)
                }
                // loose grains in the groove walls (gentle: a square on its own is only a couple of screen pixels, and
                // more made the grooves look dotted; the fine grain drawn over the sand does the rest)
                val rough = (((hash(x, y) ushr 8) and 255) / 127.5f - 1f) * 0.028f * RES
                val target = -press * tooth * cos(2 * PI * (u + half - gap / 2) / gap).toFloat() + rough
                // the sides blend in softly, and so do the front and back: each square ends up a blend of the few
                // stamps that passed over it, not just a copy of the last one (which showed every wobble)
                val soft = along / halfLen
                val strength = 0.65f * ((outer - au) / (gap * 0.5f)).coerceAtMost(1f) * (1 - soft * soft) * lift
                h[i] += (target - h[i]) * strength
                if (mark) handMark[i] = true
            }
        }
        if (tx1 < 0) return
        markDirty(tx0, ty0, tx1, ty1)
        if (mark) {
            markX0 = min(markX0, tx0); markY0 = min(markY0, ty0)
            markX1 = max(markX1, tx1); markY1 = max(markY1, ty1)
        }
    }

    // The brush: gently wipes the sand back to smooth under a round, soft-edged patch
    private fun brushAt(px: Float, py: Float) {
        val r = 13f * RES
        val x0 = max(0, floor(px - r).toInt()); val x1 = min(gw - 1, ceil(px + r).toInt())
        val y0 = max(0, floor(py - r).toInt()); val y1 = min(gh - 1, ceil(py + r).toInt())
        if (x0 > x1 || y0 > y1) return
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * gw + x
            if (covered[i]) continue
            val d = hypot(x + 0.5f - px, y + 0.5f - py)
            if (d > r) continue
            heights[i] += (freshSand(x, y) - heights[i]) * BRUSH_WIPE * (1 - d / r)
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
        val t0 = System.nanoTime()
        shade(dirtyX0, dirtyY0, dirtyX1, dirtyY1)
        timed(T_SHADE, t0)
        // the lantern light around it is worked out again a little later (see relightSoon; the smooth drawing's
        // light follows the sand by itself)
        if (lampX.isNotEmpty() && smooth == null) {
            lightX0 = min(lightX0, dirtyX0 - LIGHT_PAD); lightY0 = min(lightY0, dirtyY0 - LIGHT_PAD)
            lightX1 = max(lightX1, dirtyX1 + LIGHT_PAD); lightY1 = max(lightY1, dirtyY1 + LIGHT_PAD)
            relightSoon()
        }
        dirtyX0 = Int.MAX_VALUE; dirtyY0 = Int.MAX_VALUE; dirtyX1 = -1; dirtyY1 = -1
        changed = true
        invalidate()
    }

    // While the sand is being raked, the lantern light on it is worked out again at most about 10 times a second
    // (light changes slowly, and doing it after every little move of the finger made raking lag): the changed box
    // is gathered up, and done when a tenth of a second has passed since the last time
    private var lightX0 = Int.MAX_VALUE
    private var lightY0 = Int.MAX_VALUE
    private var lightX1 = -1
    private var lightY1 = -1
    private var lastRelight = 0L
    private val relightLater = Runnable { relightNow() }

    private fun relightSoon() {
        val wait = 100 - (SystemClock.uptimeMillis() - lastRelight)
        if (wait <= 0) { removeCallbacks(relightLater); relightNow() }
        else { removeCallbacks(relightLater); postDelayed(relightLater, wait) }
    }

    // (Worked out on a background thread, so the stroke never waits for it; only putting the finished light into
    // each lantern's picture is done here, which is quick. It reads the sand while you go on raking; anything it
    // misses is caught next time.)
    private fun relightNow() {
        if (lightX1 < 0 || lightBusy) return     // (while busy, the box waits until the light being worked out is done)
        val x0 = lightX0; val y0 = lightY0; val x1 = lightX1; val y1 = lightY1
        lightX0 = Int.MAX_VALUE; lightY0 = Int.MAX_VALUE; lightX1 = -1; lightY1 = -1
        // a copy of what it needs: the lanterns, where they are, and the garden as it is now
        val n = lampX.size
        if (n == 0 || lampLights.size < n) return
        val layers = Array(n) { lampLights[it] }
        val ox = IntArray(n) { layers[it].ox }; val oy = IntArray(n) { layers[it].oy }
        val lx = lampX.copyOf(); val ly = lampY.copyOf(); val lr = lampR.copyOf(); val lt = lampTurn.copyOf()
        val w = gw; val hh = gh; val hs = heights; val so = solid; val al = objAlbedo
        val sandColor = SANDS[sand][1]
        lightBusy = true
        val t0 = System.nanoTime()
        LIGHT_THREAD.execute {
            val m = WORKER_LIGHT
            m.take(w, hh, hs, so, al)
            val done = arrayOfNulls<IntArray>(n)       // each lantern's light in its part of the box
            val boxes = IntArray(4 * n)
            for (k in 0 until n) {
                val ax = maxOf(0, x0, ox[k]); val ay = maxOf(0, y0, oy[k])
                val bx = minOf(w - 1, x1, ox[k] + LAMP_SIZE - 1); val by = minOf(hh - 1, y1, oy[k] + LAMP_SIZE - 1)
                if (ax > bx || ay > by) continue
                val bw = bx - ax + 1
                val out = IntArray(bw * (by - ay + 1))
                for (y in ay..by) for (x in ax..bx) {
                    out[(y - ay) * bw + (x - ax)] = warmLight(m.at(lx[k], ly[k], lr[k], lt[k], x, y), al[y * w + x], sandColor)
                }
                done[k] = out
                boxes[4 * k] = ax; boxes[4 * k + 1] = ay; boxes[4 * k + 2] = bx; boxes[4 * k + 3] = by
            }
            post {
                lightBusy = false
                for (k in 0 until n) {
                    val out = done[k] ?: continue
                    val layer = layers[k]
                    // (skipped if the lantern moved or went meanwhile: it's worked out again anyway)
                    if (k >= lampLights.size || lampLights[k] !== layer || layer.ox != ox[k] || layer.oy != oy[k]) continue
                    val ax = boxes[4 * k]; val ay = boxes[4 * k + 1]; val bw = boxes[4 * k + 2] - ax + 1; val bh = boxes[4 * k + 3] - ay + 1
                    val pixels = layer.pixels
                    for (r in 0 until bh) System.arraycopy(out, r * bw, pixels,(ay - layer.oy + r) * LAMP_SIZE + (ax - layer.ox), bw)
                    layer.picture.setPixels(out, 0, bw, ax - layer.ox, ay - layer.oy, bw, bh)
                }
                timed(T_LIGHT, t0)
                lastRelight = SystemClock.uptimeMillis()
                invalidate()
                if (lightX1 >= 0) relightSoon()   // (more changed meanwhile)
            }
        }
    }
    private var lightBusy = false            // the background thread is working out lantern light

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
    // sand and raked again along the tidied path. (Only the squares the stroke raked go back to how they were, so
    // whatever the auto rake did meanwhile stays.)
    private fun steady() {
        val before = undoStack.lastOrNull() ?: return
        if (before.heights.size != heights.size) return
        val path = resample(points, pointCount)
        // the finger trembles where it stops: leave out the last bit (as far as the rake trails behind it on its
        // string), so the tidied line ends where the rake did, not in a scraped stripe
        var n = path.size / 2
        val endX = path[2 * n - 2]
        val endY = path[2 * n - 1]
        while (n > 1 && hypot(path[2 * n - 2] - endX, path[2 * n - 1] - endY) < HAND_STRING) n--
        if (n < 12 * RES) return   // (shorter than 12 dp: leave it)
        val ax = path[0]; val ay = path[1]; val bx = path[2 * n - 2]; val by = path[2 * n - 1]
        val chord = hypot(bx - ax, by - ay)
        var off = 0f
        if (chord > 1f) for (k in 0 until n) off = max(off, abs((path[2 * k] - ax) * (by - ay) - (path[2 * k + 1] - ay) * (bx - ax)) / chord)
        val exact: Boolean
        val tidy: FloatArray
        // nearly straight: at least 12 dp long, and never more than 2.5 dp (or 5%) off the straight line
        if (chord >= 12f * RES && off <= max(2.5f * RES, chord * 0.05f)) {
            tidy = resample(floatArrayOf(ax, ay, bx, by), 2); exact = true
        } else {
            val arc = arcThrough(path, n)
            if (arc != null) { tidy = arc; exact = true } else { tidy = smoothed(path, n); exact = false }
        }
        if (markX1 < 0) return
        // the stroke taken back out (only inside the box around it)
        val base = before.heights
        for (y in max(0, markY0)..min(gh - 1, markY1)) for (x in max(0, markX0)..min(gw - 1, markX1)) {
            val i = y * gw + x
            if (handMark[i]) heights[i] = base[i]
        }
        markDirty(markX0, markY0, markX1, markY1)
        // and raked again along the tidy path; then only the squares around the old and new lines are redrawn,
        // just like while raking (redrawing the whole tray and all the lantern light at once made it stutter)
        hand.x = tidy[0]; hand.y = tidy[1]; hand.hasDir = false; hand.len = 0f
        // (its length, so it lifts out smoothly at the end)
        var length = 0f
        for (k in 1 until tidy.size / 2) length += hypot(tidy[2 * k] - tidy[2 * k - 2], tidy[2 * k + 1] - tidy[2 * k - 1])
        hand.end = length
        for (k in 1 until tidy.size / 2) rakeTo(hand, tidy[2 * k], tidy[2 * k + 1], if (exact) 1f else 0.5f)
        redrawDirty()
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
        if (r.isNaN() || r < 8f * RES || r > 2f * max(gw, gh)) return null
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
        if (sqrt(miss / n) > max(1.5f * RES, r * 0.05f) || abs(swept) < 0.8f) return null
        // nearly closed: go all the way round, and a little past the start so the join doesn't show
        val sweep = if (abs(swept) > 2 * PI * 0.85) (if (swept > 0) 1 else -1) * (2 * PI.toFloat() + TAPER / r) else swept
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
        val w = min((6 * RES).toInt(), min(k, n - 1 - k))   // (6 dp each way)
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
            // (sizes in dp, times RES for squares)
            FLOWERS -> Item(kind, x, y, (11f + rnd.nextFloat() * 3f) * RES, 1f, 0f, PETALS[rnd.nextInt(PETALS.size)], rnd.nextInt())
            LANTERN -> Item(kind, x, y, LANTERN_SIZE, 1f, rnd.nextFloat() * 60f, LANTERN_GREY, rnd.nextInt())
            BONSAI -> Item(kind, x, y, (15f + rnd.nextFloat() * 4f) * RES, 1f, rnd.nextFloat() * 90f,
                POT_COLORS[rnd.nextInt(POT_COLORS.size)], rnd.nextInt())
            else -> Item(STONE, x, y, (9f + rnd.nextFloat() * 7f) * RES, 0.72f + rnd.nextFloat() * 0.25f, rnd.nextFloat() * 180f,
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
                        // (the bumps and colour bands are sized in dp: u / RES)
                        val height = r * 0.6f * (1 - q * q).pow(0.55f) + 0.35f * RES * sin(u / RES * 0.7f + a) * sin(v / RES * 0.8f + b) * (1 - q)
                        var f = (0.9f + 0.2f * speck) * (0.95f + 0.08f * sin(u / RES * 0.25f + c))
                        if ((n ushr 11) and 31 == 0) f -= 0.25f   // dark flecks
                        put(i, height, tone(s.color, f), (1 - q) * r)
                    }
                    FLOWERS -> {
                        val th = atan2(dy, dx)
                        val d = hypot(dx, dy)
                        val q = d / (r * (1 + 0.18f * sin(7 * th + a) + 0.1f * sin(3 * th + b)))
                        if (q >= 1f) continue
                        // leaves fanning out from the middle
                        val leaf = abs(sin(th * 7 + d / RES * 0.25f + a))
                        val height = r * 0.22f * sqrt(1 - q * q) + leaf * 0.8f * RES * (1 - q)
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
                            val needles = ((n ushr 2) and 255) / 255f * RES
                            val height = 0.8f * r + br * 1.1f * sqrt(1 - e * e) + needles
                            val t = ((height - 0.8f * r) / (br * 1.1f + RES)).coerceIn(0f, 1f).pow(1.3f)
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
                layer.clear()
            }
        }
        // the lanterns' light in the smooth drawing: one for each lantern, with its flame where the lantern is
        val gpu = smooth
        if (gpu != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gpu.lamps.clear()
            for (k in lamps.indices) {
                val layer = lampLights[k]
                val s = layer.smooth ?: gpu.Lamp().also { layer.smooth = it }
                s.set(lamps[k].x, lamps[k].y, lamps[k].r, lampTurn[k])
                gpu.lamps.add(s)
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
        redrawAll()
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
    private var finger = 0                   // which finger is raking or dragging (Android's number for it)
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
        // only the finger that started the touch counts (a second finger would make the rake jump over to it)
        if (e.actionMasked == MotionEvent.ACTION_DOWN) finger = e.getPointerId(0)
        val p = e.findPointerIndex(finger)
        if (p < 0) return true
        val x = (e.getX(p) - offX - inset) / cell
        val y = (e.getY(p) - offY - inset) / cell
        // that finger lifting while another one stays down ends the touch, just as if it were the last
        val action = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            if (e.actionIndex != p) return true
            MotionEvent.ACTION_UP
        } else e.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                touching = anim == null
                if (!touching) return true
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x; downY = y; moved = false
                hand.start(x, y, tool)
                if (steadyHand) {
                    handMark.fill(false)
                    markX0 = Int.MAX_VALUE; markY0 = Int.MAX_VALUE; markX1 = -1; markY1 = -1
                }
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
                }
                if (tool == FIREFLY) return true
                if (tool >= STONE) {
                    val s = held ?: return true
                    if (!moved) return true
                    if (!heldRemembered) { remember(); heldRemembered = true }
                    val oldX = s.x
                    val oldY = s.y
                    snapTo((heldFromX + x - downX).coerceIn(0f, gw.toFloat()), (heldFromY + y - downY).coerceIn(0f, gh.toFloat()))
                    clearOfCorners(snapX, snapY, s.r * footprint(s.kind))   // (never slid under a corner block)
                    s.x = snapX
                    s.y = snapY
                    if (s.x == oldX && s.y == oldY) return true   // (on the grid it only moves point to point)
                    markCovered()
                    reshadeAround(oldX, oldY, s.r)
                    reshadeAround(s.x, s.y, s.r)
                    // with a lantern out, its light and the shadows in it follow what you're dragging: just the light
                    // near where it was and is now (everything is worked out again when you let go)
                    if (lampX.isNotEmpty()) {
                        val pad = (s.r * 1.4f).toInt() + (40 * RES).toInt()
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
                        val hx = (e.getHistoricalX(p, k) - offX - inset) / cell
                        val hy = (e.getHistoricalY(p, k) - offY - inset) / cell
                        addPoint(hx, hy)
                        followFinger(hx, hy)
                    }
                    addPoint(x, y)
                    followFinger(x, y)
                    redrawDirty()
                    val dt = max(8L, e.eventTime - prevTime)
                    makeSound((hypot(x - prevX, y - prevY) / RES / dt * 1000f / 700f).coerceAtMost(1f))   // (700 dp a second = full)
                    prevTime = e.eventTime; prevX = x; prevY = y
                }
            }
            MotionEvent.ACTION_UP -> {
                saveSoon()
                removeCallbacks(longPress)
                if (!touching) return true
                touching = false
                if (tool == FIREFLY) {
                    if (!moved) tapFirefly(x, y)
                } else if (tool >= STONE) {
                    val s = held
                    if (!moved) {
                        // a tap: take away the item you tapped, or put a new one down
                        if (s != null) { remember(); items.remove(s) }
                        else if (items.size < MAX_ITEMS) {
                            remember()
                            snapTo(x, y)
                            val thing = newItem(tool, snapX, snapY)
                            clearOfCorners(thing.x, thing.y, thing.r * footprint(thing.kind))   // (never under a corner block)
                            thing.x = snapX; thing.y = snapY
                            items.add(thing)
                            settle(thing)
                        }
                    } else if (s != null) settle(s)
                    held = null
                    markCovered()
                    redrawAll()   // the whole tray (the lantern light falls differently now), a band at a time
                } else {
                    sound.level(0f)
                    if (strokeRemembered && tool != BRUSH) { if (steadyHand) steady() else liftOut() }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                touching = false
                sound.level(0f)
                val s = held
                held = null
                if (s != null && heldRemembered) {
                    // Android took the touch away mid-drag (an edge swipe, the phone turning): the item goes back
                    // where it was, its picture in the sand too, and the undo step for the move is dropped
                    s.x = heldFromX; s.y = heldFromY
                    undoStack.removeLastOrNull()
                    onUndoChanged?.invoke()
                    markCovered()
                    redrawAll()
                }
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
        val r = it.r * footprint(it.kind) + 2f * RES + half + ring * 2 * half
        it.rings = ring + 1
        val cx = it.x; val cy = it.y
        val start = atan2(lightY, lightX)
        val sweep = 2 * PI.toFloat() + TAPER / r
        hand.start(cx + r * cos(start), cy + r * sin(start), tool)
        hand.end = r * sweep                 // (all the way round: it lifts out smoothly over its own start)
        var done = 0f
        if (soundOn) sound.level(0.55f * if (tool == RAKE) 1f else 0.6f)
        anim = ValueAnimator.ofFloat(0f, sweep).apply {
            duration = (500 + r / RES * 8).toLong().coerceAtMost(1800)
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
        relight(a - LIGHT_PAD, 0, b + LIGHT_PAD, gh - 1)
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
        redrawAll()
    }

    // ---------------- Redrawing the whole tray, a band at a time ----------------

    // After a big change (something placed, moved or taken away, Undo, a new sand colour), the whole tray is shaded
    // again and all the lantern light worked out again. Done all at once, that froze the garden for a moment, so
    // it's done a band of rows at a time instead: as many as fit in about 8 ms each frame, until it's finished
    // (usually 1-3 frames). Small changes, like raking, are still drawn straight away.
    private var redrawRow = -1               // the next row to redraw (-1 = nothing waiting)
    private val redrawMore = Runnable { redrawSomeRows() }

    private fun redrawAll() {
        if (bitmap == null) return
        redrawRow = 0                        // (a redraw already going on starts again from the top)
        removeCallbacks(redrawMore)
        redrawSomeRows()
    }

    private fun redrawSomeRows() {
        if (redrawRow < 0 || bitmap == null) return
        val start = SystemClock.uptimeMillis()
        while (redrawRow < gh) {
            val last = min(gh - 1, redrawRow + 7)   // 8 rows at a time
            shade(0, redrawRow, gw - 1, last)
            relight(0, redrawRow, gw - 1, last)
            redrawRow = last + 1
            if (SystemClock.uptimeMillis() - start >= 8) break
        }
        changed = true
        invalidate()
        if (redrawRow >= gh) redrawRow = -1 else postOnAnimation(redrawMore)
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
        val size = (2 * FLY_REACH + FLY_STEP - 1) / FLY_STEP + 1   // its light picture is worked out every FLY_STEP squares
        val pixels = IntArray(size * size)
        val light: Bitmap = createBitmap(size, size)
        var ox = 0                                   // where its light picture sits, in squares
        var oy = 0
        var lit = false                              // its light picture is up to date and showing
    }

    private val flies = ArrayList<Firefly>()
    private var savedFlies = 0                       // fireflies in the saved garden, let out once its size is known
    private var flyRunning = false
    private var wasGlowing = false                   // a firefly was glowing last frame
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
        val near = flies.firstOrNull { it.leaving < 0f && hypot(it.x - x, it.y - y) < 12f * RES }
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
        var glowing = false
        for (f in flies) {
            flyAbout(f, dt)
            // (the smooth drawing works out their light itself, per screen pixel, see drawFireflyLight)
            if (smooth == null) lightFirefly(f) else f.lit = false
            if (f.level > 0.005f) glowing = true
        }
        // drawn again only when something shows: a firefly glowing (or just gone dark), or a lantern's flicker (which
        // goes along with the fireflies' frames while they're out). Dark fireflies can't be seen, so while they're
        // all dark between flashes, nothing is drawn.
        if (glowing || wasGlowing || lampX.isNotEmpty()) lightMoved()
        wasGlowing = glowing
        postDelayed(flyFrame, 33)
    }

    private fun flyAbout(f: Firefly, dt: Float) {
        // wander: the turn drifts slowly, so the path is a lazy curve
        f.turn += (Random.nextFloat() - 0.5f) * 2.4f * dt
        f.turn = (f.turn * (1 - 0.6f * dt)).coerceIn(-0.9f, 0.9f)
        f.heading += f.turn * dt
        // near the edge of the tray, turn gently back towards the middle
        val margin = 24f * RES
        if (f.leaving < 0f && (f.x < margin || f.y < margin || f.x > gw - margin || f.y > gh - margin)) {
            var diff = atan2(gh / 2f - f.y, gw / 2f - f.x) - f.heading
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            f.heading += diff * 1.2f * dt
        }
        // slow and unhurried, slower still while glowing (they hover as they flash), quicker when flying off
        f.phase += dt * 0.7f
        val speed = (11f + 4f * sin(f.phase)) * RES * (1 - 0.6f * f.level) * (if (f.leaving >= 0f) 2.5f else 1f)
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
        // every few squares, so a soft wide glow stays cheap (lined up on a fixed pattern, so it doesn't shimmer)
        val step = FLY_STEP
        f.ox = Math.floorDiv(f.x.toInt() - reach, step) * step
        f.oy = Math.floorDiv(f.y.toInt() - reach, step) * step
        val sandColor = SANDS[sand][1]
        val h = heights
        val reach2 = (reach * reach).toFloat()
        for (j in 0 until size) {
            val gy = f.oy + step * j
            for (i in 0 until size) {
                val gx = f.ox + step * i
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

    // The fireflies' light on the sand (added on top, like the lanterns'), drawn under everything that stands up.
    // The smooth drawing gets the glowing ones (where, how high, how bright) and works out their light per screen
    // pixel, over just the box their light reaches.
    private val flyValues = FloatArray(4 * MAX_FLIES)

    private fun drawFireflyLight(canvas: Canvas) {
        val gpu = smooth
        if (gpu != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && canvas.isHardwareAccelerated) {
            var n = 0
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (f in flies) {
                val power = f.level * lampPower * 0.32f
                if (power < 0.01f || n >= MAX_FLIES) continue
                flyValues[4 * n] = f.x; flyValues[4 * n + 1] = f.y; flyValues[4 * n + 2] = f.height; flyValues[4 * n + 3] = power
                n++
                l = min(l, f.x - FLY_REACH); t = min(t, f.y - FLY_REACH); r = max(r, f.x + FLY_REACH); b = max(b, f.y + FLY_REACH)
            }
            if (n == 0) return
            for (k in 4 * n until flyValues.size) flyValues[k] = 0f
            val drawn = gpu.drawFlies(canvas, flyValues, n, inset, cell, SANDS[sand][1],
                max(sandRect.left, inset + l * cell), max(sandRect.top, inset + t * cell),
                min(sandRect.right, inset + r * cell), min(sandRect.bottom, inset + b * cell))
            if (drawn) return
            if (gpu.broken) leaveSmooth()
            return
        }
        lampPaint.alpha = 255
        for (f in flies) if (f.lit) {
            flyRect.set(inset + f.ox * cell, inset + f.oy * cell, inset + (f.ox + FLY_STEP * f.size) * cell,
                inset + (f.oy + FLY_STEP * f.size) * cell)
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
            autoPattern = -1                     // (drawn from the deck when its round starts)
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

    // The next pattern: drawn like a card from a shuffled deck of all of them, so each is raked once before any comes
    // round again. The deck is kept in the Game Room's settings, so it carries on where it left off next time; when
    // it's shuffled again, the pattern just raked is never drawn first.
    private fun drawPattern(): Int {
        val prefs = context.getSharedPreferences("game_room", Context.MODE_PRIVATE)
        val deck = (prefs.getString("auto_deck", "") ?: "").split(',').mapNotNull { it.toIntOrNull() }
            .filter { it in AUTO_PATTERNS.indices }.toMutableList()
        val last = prefs.getInt("auto_last", -1)
        if (deck.isEmpty()) {
            deck.addAll(AUTO_PATTERNS.indices.shuffled())
            if (deck.size > 1 && deck[0] == last) deck.add(deck.removeAt(0))
        }
        val next = deck.removeAt(0)
        prefs.edit { putString("auto_deck", deck.joinToString(",")); putInt("auto_last", next) }
        return next
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
                if (autoPattern < 0) autoPattern = drawPattern()
                onAutoPattern?.invoke(AUTO_PATTERNS[autoPattern])
            }
            1 -> {
                autoStrokes.clear()
                autoStrokes.addAll(patternStrokes(autoPattern))
                autoCurrent = autoPattern
                autoKind = autoStrokes.firstOrNull()?.kind ?: RAKE
                autoPattern = -1                     // (the next round draws its own)
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
        autoRake.end = (p.size / 2 - 1).toFloat()   // (its points are a square apart: it lifts out smoothly at the end)
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
        val wasResting = autoStage == 3
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
        // (resting, nothing moves: nothing to draw again)
        if (!(wasResting && autoStage == 3)) invalidate()
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
            "Waves" -> rows(rakeWidth, 9f * RES)
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
                list.add(Stroke(circle(cx, cy, r * 2 + 7 * RES), STICK))
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
            // a five-petalled rose curve that loops over itself three times, inside a ring (stick)
            "Rose Window" -> {
                val r = size * 0.42f
                listOf(traced(STICK, 3 * PI.toFloat(), 6000, true) { t, o ->
                    val rr = r * cos(5f / 3f * t)
                    o[0] = cx + rr * cos(t); o[1] = cy + rr * sin(t)
                }, Stroke(circle(cx, cy, r * 1.08f), STICK))
            }
            // looping, overlapping circles, like the drawing toy (a hypotrochoid: a circle 5 across rolling inside
            // one 8 across, the pen 5 from its middle), inside a ring (stick)
            "Spirograph" -> {
                val k = size * 0.44f / 8f
                listOf(traced(STICK, 10 * PI.toFloat(), 8000, true) { t, o ->
                    o[0] = cx + k * (3f * cos(t) + 5f * cos(0.6f * t))
                    o[1] = cy + k * (3f * sin(t) - 5f * sin(0.6f * t))
                }, Stroke(circle(cx, cy, size * 0.47f), STICK))
            }
            // one long figure, three loops across and two down, crossing itself again and again (rake)
            "Lissajous" -> {
                val a = gw * 0.38f
                val b = gh * 0.36f
                listOf(traced(RAKE, 2 * PI.toFloat(), 4000, true) { t, o ->
                    o[0] = cx + a * sin(3 * t + PI.toFloat() / 2); o[1] = cy + b * sin(2 * t)
                })
            }
            // rows raked along the tray, then stick lines through them the other way
            "Basket Weave" -> rows(rakeWidth, 0f) + crossLines(rakeWidth, STICK)
            // a squared-off spiral, raked from the edge inwards, its corners gently rounded
            "Square Spiral" -> {
                val corners = ArrayList<Float>()
                var l = rakeWidth * 0.6f; var t = rakeWidth * 0.6f
                var r = gw - rakeWidth * 0.6f; var b = gh - rakeWidth * 0.6f
                corners.add(l); corners.add(t)
                while (r - l > rakeWidth * 2 && b - t > rakeWidth * 2) {
                    corners.add(r); corners.add(t)
                    corners.add(r); corners.add(b)
                    corners.add(l); corners.add(b)
                    t += rakeWidth
                    corners.add(l); corners.add(t)
                    l += rakeWidth; r -= rakeWidth; b -= rakeWidth
                }
                val path = resample(corners.toFloatArray(), corners.size / 2)
                listOf(Stroke(smoothed(path, path.size / 2), RAKE))
            }
            // nineteen circles of the same size, each passing through the middle of its neighbours (the bigger
            // sibling of the Seed of Life), inside a larger one (stick)
            "Flower of Life" -> {
                val r = size * 0.12f
                val list = ArrayList<Stroke>()
                for (ring in 0..2) for (q in -2..2) for (s in -2..2) {
                    if (maxOf(abs(q), abs(s), abs(q + s)) != ring) continue   // (from the middle outwards)
                    list.add(Stroke(circle(cx + r * (q + s / 2f), cy + r * s * 0.8660254f, r), STICK))
                }
                list.add(Stroke(circle(cx, cy, r * 3f), STICK))
                list
            }
            // a ring of twelve overlapping circles round a middle one (with a small one in it), inside a raked ring
            "Mandala" -> {
                val ring = size * 0.2f
                val small = ring * sin(PI.toFloat() / 12) * 1.6f
                val list = arrayListOf(Stroke(circle(cx, cy, (ring - small) * 0.5f), STICK),
                    Stroke(circle(cx, cy, ring - small), STICK))
                for (k in 0 until 12) {
                    val a = k * PI.toFloat() / 6
                    list.add(Stroke(circle(cx + ring * cos(a), cy + ring * sin(a), small), STICK))
                }
                list.add(Stroke(circle(cx, cy, ring + small + rakeWidth * 0.7f), RAKE))
                list
            }
            // a seven-pointed star drawn in one line (each point joined to the one three along), inside a circle
            "Seven Star" -> {
                val r = size * 0.42f
                val turn = -PI.toFloat() / 2
                val corners = FloatArray(16)
                for (n in 0..7) {
                    val a = turn + (n * 3 % 7) * 2 * PI.toFloat() / 7
                    corners[2 * n] = cx + r * cos(a); corners[2 * n + 1] = cy + r * sin(a)
                }
                listOf(Stroke(closedPath(resample(corners, 8)), STICK), Stroke(circle(cx, cy, r * 1.07f), STICK))
            }
            // ripples spreading from four raindrops, overlapping where they meet (stick)
            "Rain" -> {
                val list = ArrayList<Stroke>()
                val drops = floatArrayOf(0.27f, 0.33f, 0.72f, 0.27f, 0.47f, 0.72f, 0.82f, 0.74f)
                val gap = toothGap(STICK) * 1.7f
                for (d in 0 until 4) {
                    var r = gap
                    while (r < size * 0.34f) {
                        list.add(Stroke(circle(gw * drops[2 * d], gh * drops[2 * d + 1], r), STICK))
                        r += gap
                    }
                }
                list
            }
            // zigzag rows, raked back and forth
            "Chevrons" -> rows(rakeWidth * 1.4f, rakeWidth * 0.6f, zigzag = true)
            // six golden spirals turning out from the middle (each grows by the golden ratio every quarter turn)
            else -> {
                val grow = (ln(1.618034) / (PI / 2)).toFloat()
                val start = 9f * RES   // a little way out (9 dp), so the six arms don't tangle in the middle
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

    // Rows (or waves, or zigzags) `spacing` apart along the long side, starting just off the sand so the ends are clean
    private fun rows(spacing: Float, wave: Float, zigzag: Boolean = false): List<Stroke> {
        val across = gw >= gh                       // rows run left-right when the tray is wider than tall
        val length = (if (across) gw else gh).toFloat()
        val depth = (if (across) gh else gw).toFloat()
        val list = ArrayList<Stroke>()
        var c = spacing / 2
        var k = 0
        val over = 12f * RES                        // (starting and ending 12 dp off the sand)
        while (c - spacing / 2 < depth) {
            val n = (length + 2 * over).toInt()
            val forward = k % 2 == 0                // back and forth, as you'd walk it
            val pts = FloatArray(2 * n) { j ->
                val i = j / 2
                val along = if (forward) i - over else length + over - i
                val turn = along * 2 * PI.toFloat() / (120f * RES)              // (a wave every 120 dp)
                // (a zigzag: straight up and down, its points just rounded off)
                val side = c + wave * (if (zigzag) asin(sin(turn) * 0.97f) * 2 / PI.toFloat() else sin(turn))
                if (j % 2 == 0) (if (across) along else side) else (if (across) side else along)
            }
            list.add(Stroke(pts, RAKE))
            c += spacing
            k++
        }
        return list
    }

    // Straight lines across the tray the other way from rows(), `spacing` apart, back and forth (with `kind`)
    private fun crossLines(spacing: Float, kind: Int): List<Stroke> {
        val across = gw >= gh                       // (the rows run left-right: these run up and down)
        val length = (if (across) gh else gw).toFloat()
        val depth = (if (across) gw else gh).toFloat()
        val over = 12f * RES
        val list = ArrayList<Stroke>()
        var c = spacing / 2
        var k = 0
        while (c < depth) {
            val n = (length + 2 * over).toInt()
            val forward = k % 2 == 0
            val pts = FloatArray(2 * n) { j ->
                val along = if (forward) j / 2 - over else length + over - j / 2
                if (j % 2 == 0) (if (across) c else along) else (if (across) along else c)
            }
            list.add(Stroke(pts, kind))
            c += spacing
            k++
        }
        return list
    }

    // A smooth curve (with `kind`), traced through at(t) for t from 0 to `until` in `samples` steps, then evened out
    // to points a square apart; a `closed` one goes on a little past its start, so the join doesn't show
    private fun traced(kind: Int, until: Float, samples: Int, closed: Boolean, at: (Float, FloatArray) -> Unit): Stroke {
        val o = FloatArray(2)
        val raw = FloatArray(2 * (samples + 1))
        for (k in 0..samples) {
            at(until * k / samples, o)
            raw[2 * k] = o[0]; raw[2 * k + 1] = o[1]
        }
        val path = resample(raw, samples + 1)
        return Stroke(if (closed) closedPath(path) else path, kind)
    }

    // A closed path (points a square apart, ending where it began) going on past its start for as long as the rake
    // takes to lift out, so the end fades out over the start fading in and the join doesn't show
    private fun closedPath(p: FloatArray): FloatArray {
        val n = p.size / 2
        val extra = min(n - 1, ceil(TAPER).toInt())
        return p.copyOf(2 * (n + extra)).also { for (k in 0 until extra) { it[2 * (n + k)] = p[2 * (k + 1)]; it[2 * (n + k) + 1] = p[2 * (k + 1) + 1] } }
    }

    // A full circle, going on past its start for as long as the rake takes to lift out (see closedPath)
    private fun circle(cx: Float, cy: Float, r: Float): FloatArray {
        val start = Random.nextFloat() * 6.283f
        val sweep = 2 * PI.toFloat() + TAPER / r
        val steps = ceil(sweep * r).toInt()
        return FloatArray(2 * (steps + 1)) { j ->
            val a = start + sweep * (j / 2) / steps
            if (j % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }
    }

    // Three rings close around each placed thing, always with the stick
    private fun ringStrokes(): List<Stroke> {
        val half = teeth(STICK) * toothGap(STICK) / 2
        val list = ArrayList<Stroke>()
        for (it in items) for (k in 0 until 3) {
            list.add(Stroke(circle(it.x, it.y, it.r * footprint(it.kind) + 2f * RES + half + k * 2 * half), STICK))
        }
        return list
    }

    // The auto rake itself, seen from above: the wooden head with its teeth and the handle pointing the way it's
    // pulled (or the smoothing board sweeping across). It fades out and in when lifted between strokes.
    private fun drawAutoRake(canvas: Canvas) {
        if (!autoOn || autoResizing) return
        val c = cell * RES                   // one dp on screen (the rake's drawing is measured in dp)
        if (autoStage == 0) {
            val x = inset + autoSmoothedTo * cell
            paint.color = SHADOW_SOFT
            canvas.drawRect(x + c * 1.5f, inset, x + c * 5.5f, inset + gh * cell, paint)
            paint.color = WOOD
            canvas.drawRect(x - c * 2f, inset, x + c * 2f, inset + gh * cell, paint)
            return
        }
        if (autoStage == 3) return
        val px = autoRake.x
        val py = autoRake.y
        if (px < -4 * RES || py < -4 * RES || px > gw + 4 * RES || py > gh + 4 * RES) return
        val fade = if (autoLift > 0f) (if (autoLiftMoved) 1 - autoLift / (LIFT_SECONDS / 2) else (autoLift - LIFT_SECONDS / 2) / (LIFT_SECONDS / 2)) else 1f
        val alpha = (255 * fade.coerceIn(0f, 1f)).toInt()
        if (alpha <= 0) return
        val half = teeth(autoRake.kind) * toothGap(autoRake.kind) / 2 / RES   // (in dp)
        canvas.withSave {
            translate(inset + px * cell, inset + py * cell)
            rotate(Math.toDegrees(atan2(autoRake.dirY, autoRake.dirX).toDouble()).toFloat())
            if (autoRake.kind == RAKE) {
                // shadow, handle, head, teeth
                paint.color = SHADOW_SOFT; paint.alpha = alpha * 60 / 255
                drawRect(-1.6f * c + 2 * c, -(half + 2) * c + 2 * c, 1.6f * c + 2 * c, (half + 2) * c + 2 * c, paint)
                paint.color = HANDLE; paint.alpha = alpha
                drawRoundRect(0f, -1.2f * c, 80f * c, 1.2f * c, c, c, paint)
                paint.color = WOOD; paint.alpha = alpha
                drawRoundRect(-1.8f * c, -(half + 2) * c, 1.8f * c, (half + 2) * c, c, c, paint)
                paint.color = WOOD_EDGE; paint.alpha = alpha
                for (k in 0 until teeth(RAKE)) drawCircle(-1.8f * c, (-half + toothGap(RAKE) / RES * (k + 0.5f)) * c, 0.9f * c, paint)
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

    // ---------------- The wooden frame ----------------

    // The tray's frame: four wooden boards, with a triangular block of wood across each inner corner. Three of the
    // blocks have a symbol carved into them, the window's own buttons: Exit (top left), move (top right, drag it) and
    // resize (bottom right, drag it); the fourth (bottom left) has an arrow that opens and closes the tools. They
    // never fade, so they can always be seen and used. All drawn in code, the wood grain too (see woodTexture).
    // (The move and resize corners are worked by the window itself, see BubbleService.applyFrameless; Exit and the
    // arrow by ZenScreen.)

    // the carved corners' size, in pixels: from the tray's outer corner along each side
    val cornerSize get() = dp(CORNER_DP)
    // the tools are showing (the bottom-left arrow points down, to close them)
    var dockOpen = false
        set(v) { field = v; frameChanged = true; invalidate() }

    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var woodShader: BitmapShader? = null
    private val woodMatrix = android.graphics.Matrix()
    private val boards = Array(4) { android.graphics.Path() }   // top, bottom, left, right
    private val blocks = Array(4) { android.graphics.Path() }   // the corner blocks: top left, top right, bottom right, bottom left
    private val sandEdge = android.graphics.Path()     // where the wood meets the sand
    private val frameShape = android.graphics.Path()   // the whole frame (the tray, less the sand inside it)
    private val carvings = arrayOf(android.graphics.Path(), android.graphics.Path(), android.graphics.Path(),
        android.graphics.Path(), android.graphics.Path())   // Exit, move, resize, up arrow, down arrow
    private var builtW = -1f                           // the size the paths and shadows were made for
    private var builtH = -1f
    private var builtInset = -1f

    // The wood: a photo of real dark wood, a strip 1,024 by 256 (repeating seamlessly along the grain), loaded once.
    // ("Dark Wood" from Poly Haven, public domain (CC0), toned from mahogany towards walnut brown; credited on the
    // Legal screen. Made from the 1k photo by cutting its top quarter, taking some colour out (55% left), tinting it
    // brown (1, 0.93, 0.82) and darkening it to 85%.)
    private fun woodTexture(): BitmapShader {
        woodShader?.let { return it }
        val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.zen_wood,
            android.graphics.BitmapFactory.Options().apply { inScaled = false })
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { woodShader = it }
    }

    // The paths for this size of tray (made again only when it changes)
    private fun buildFrame() {
        if (builtW == trayW && builtH == trayH && builtInset == inset) return
        builtW = trayW; builtH = trayH; builtInset = inset
        val w = trayW; val h = trayH; val i = inset; val k = cornerSize
        // the frame's outline: the tray, less the sand inside it (an octagon: its corners are the wooden blocks)
        frameShape.reset()
        frameShape.fillType = android.graphics.Path.FillType.EVEN_ODD
        frameShape.addRect(0f, 0f, w, h, android.graphics.Path.Direction.CW)
        frameShape.moveTo(k, i); frameShape.lineTo(w - k, i); frameShape.lineTo(w - i, k); frameShape.lineTo(w - i, h - k)
        frameShape.lineTo(w - k, h - i); frameShape.lineTo(k, h - i); frameShape.lineTo(i, h - k); frameShape.lineTo(i, k)
        frameShape.close()
        sandEdge.reset()
        sandEdge.moveTo(k, i); sandEdge.lineTo(w - k, i); sandEdge.lineTo(w - i, k); sandEdge.lineTo(w - i, h - k)
        sandEdge.lineTo(w - k, h - i); sandEdge.lineTo(k, h - i); sandEdge.lineTo(i, h - k); sandEdge.lineTo(i, k); sandEdge.close()
        // the four boards, mitred where they meet, and the corner blocks
        boards.forEach { it.reset() }
        boards[0].apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w - i, i); lineTo(i, i); close() }
        boards[1].apply { moveTo(0f, h); lineTo(w, h); lineTo(w - i, h - i); lineTo(i, h - i); close() }
        boards[2].apply { moveTo(0f, 0f); lineTo(i, i); lineTo(i, h - i); lineTo(0f, h); close() }
        boards[3].apply { moveTo(w, 0f); lineTo(w - i, i); lineTo(w - i, h - i); lineTo(w, h); close() }
        // (each block's slanted edge runs right across the board to the sand's edge, like a real corner joint, so no
        // part of the frame is left without wood)
        val e = k + i
        blocks.forEach { it.reset() }
        blocks[0].apply { moveTo(0f, 0f); lineTo(e, 0f); lineTo(0f, e); close() }
        blocks[1].apply { moveTo(w, 0f); lineTo(w - e, 0f); lineTo(w, e); close() }
        blocks[2].apply { moveTo(w, h); lineTo(w - e, h); lineTo(w, h - e); close() }
        blocks[3].apply { moveTo(0f, h); lineTo(e, h); lineTo(0f, h - e); close() }
        // the carvings, each in its corner block, a little in from the corner
        val d = (k + i) * 0.36f                        // from the corner to the carving's middle
        val s = k * 0.15f                              // half the carving's size
        fun line(p: android.graphics.Path, cx: Float, cy: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            p.moveTo(cx + x0 * s, cy + y0 * s); p.lineTo(cx + x1 * s, cy + y1 * s)
        }
        carvings.forEach { it.reset() }
        // Exit: a doorway with an arrow walking out of it
        carvings[0].apply {
            moveTo(d + 0.2f * s, d - s); lineTo(d - 0.8f * s, d - s); lineTo(d - 0.8f * s, d + s); lineTo(d + 0.2f * s, d + s)
            line(this, d, d, -0.25f, 0f, 1.05f, 0f)
            moveTo(d + 0.6f * s, d - 0.45f * s); lineTo(d + 1.05f * s, d); lineTo(d + 0.6f * s, d + 0.45f * s)
        }
        // move: a four-way arrow
        carvings[1].apply {
            val cx = w - d; val cy = d
            line(this, cx, cy, -1f, 0f, 1f, 0f); line(this, cx, cy, 0f, -1f, 0f, 1f)
            for ((ax, ay) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
                moveTo(cx + (ax * 0.62f + ay * 0.38f) * s, cy + (ay * 0.62f + ax * 0.38f) * s)
                lineTo(cx + ax * s, cy + ay * s)
                lineTo(cx + (ax * 0.62f - ay * 0.38f) * s, cy + (ay * 0.62f - ax * 0.38f) * s)
            }
        }
        // resize: three grip lines across the corner
        carvings[2].apply {
            val cx = w - d; val cy = h - d
            for (t in floatArrayOf(-0.9f, -0.2f, 0.5f)) line(this, cx, cy, t, 1f, 1f, t)
        }
        // the arrow that opens the tools (up), and closes them (down)
        line(carvings[3], d, h - d, -0.85f, 0.4f, 0f, -0.45f); line(carvings[3], d, h - d, 0f, -0.45f, 0.85f, 0.4f)
        line(carvings[4], d, h - d, -0.85f, -0.4f, 0f, 0.45f); line(carvings[4], d, h - d, 0f, 0.45f, 0.85f, -0.4f)
    }

    // One board or block of wood: `grain` is the grain's angle (degrees) and `shift` moves the grain along, so no two
    // boards look the same; `tone` makes it a little darker or lighter
    private fun drawWood(canvas: Canvas, path: android.graphics.Path, grain: Float, shift: Float, tone: Int) {
        val wood = woodTexture()
        woodMatrix.reset()
        woodMatrix.setScale(dp(0.25f), dp(0.25f))    // one pixel of the photo = a quarter of a dp
        woodMatrix.postTranslate(shift, shift * 0.37f)
        woodMatrix.postRotate(grain)
        wood.setLocalMatrix(woodMatrix)
        woodPaint.shader = wood
        canvas.drawPath(path, woodPaint)
        woodPaint.shader = null
        if (tone != 0) {
            paint.style = Paint.Style.FILL
            paint.color = tone
            canvas.drawPath(path, paint)
        }
    }

    // The frame, on top of the sand (in the tray's own coordinates)
    private fun drawFrame(canvas: Canvas) {
        buildFrame()
        val w = trayW; val h = trayH; val i = inset; val k = cornerSize
        // the four boards (the grain runs along each; the bottom and right ones a touch darker, away from the light)
        drawWood(canvas, boards[0], 0f, 0f, 0)
        drawWood(canvas, boards[1], 0f, dp(41f), BOARD_SHADE)
        drawWood(canvas, boards[2], 90f, dp(23f), 0)
        drawWood(canvas, boards[3], 90f, dp(67f), BOARD_SHADE)
        // the corner blocks (the grain runs across the corner)
        for (n in 0..3) drawWood(canvas, blocks[n], if (n % 2 == 0) 45f else -45f, dp(13f + 19f * n), 0)
        // edges: a dark line round the outside and where the wood meets the sand, and the joins between the boards
        // and blocks (seen straight down, there's nothing more: no walls or bevels)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeWidth = dp(1.2f)
        paint.color = WOOD_EDGE
        canvas.drawRect(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f), paint)
        canvas.drawPath(sandEdge, paint)
        // the joins where each corner block meets the boards (its slanted edge, across them)
        paint.strokeWidth = dp(0.8f)
        paint.color = WOOD_JOIN
        val e = k + i
        canvas.drawLine(e, 0f, k, i, paint); canvas.drawLine(0f, e, i, k, paint)
        canvas.drawLine(w - e, 0f, w - k, i, paint); canvas.drawLine(w, e, w - i, k, paint)
        canvas.drawLine(w - e, h, w - k, h - i, paint); canvas.drawLine(w, h - e, w - i, h - k, paint)
        canvas.drawLine(e, h, k, h - i, paint); canvas.drawLine(0f, h - e, i, h - k, paint)
        // the carvings: cut into the dark wood, they show the paler wood inside, with a dark edge where the cut's
        // upper wall is in shade
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = dp(1.9f)
        val arrow = if (dockOpen) carvings[4] else carvings[3]
        for (pass in 0..1) {
            if (pass == 0) {
                paint.color = CARVING_DARK
                canvas.save(); canvas.translate(-dp(0.6f), -dp(0.6f))
            } else paint.color = CARVING_LIT
            for (n in 0..2) canvas.drawPath(carvings[n], paint)
            canvas.drawPath(arrow, paint)
            if (pass == 0) canvas.restore()
        }
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.style = Paint.Style.FILL
    }

    // Which carved corner (if any) is at (x, y) in this view: CORNER_EXIT, CORNER_MOVE, CORNER_RESIZE, CORNER_TOOLS,
    // or 0. Only the triangle of wood counts (a little generously), so the sand right beside it is still raked.
    fun cornerAt(x: Float, y: Float): Int {
        val tx = x - offX
        val ty = y - offY
        if (tx < -dp(4f) || ty < -dp(4f) || tx > trayW + dp(4f) || ty > trayH + dp(4f)) return 0
        val k = (cornerSize + inset) * 1.08f   // (each block's slanted edge: x + y = its size plus the frame's)
        return when {
            tx + ty <= k -> CORNER_EXIT
            (trayW - tx) + ty <= k -> CORNER_MOVE
            (trayW - tx) + (trayH - ty) <= k -> CORNER_RESIZE
            tx + (trayH - ty) <= k -> CORNER_TOOLS
            else -> 0
        }
    }

    // Moves a placed thing at (x, y) (in squares, `r` across) clear of the corner blocks, into snapX and snapY: the
    // sand under them can't be seen, so nothing may be placed or slid under them
    private fun clearOfCorners(x: Float, y: Float, r: Float) {
        snapX = x; snapY = y
        val line = (cornerSize - inset) / cell              // each block's slanted edge: x + y = line (top left)
        val need = line + r * 1.4142f                        // far enough out that the thing is clear of it
        val gwF = gw.toFloat(); val ghF = gh.toFloat()
        // (each corner in turn, measured from that corner)
        var u = snapX + snapY
        if (u < need) { snapX += (need - u) / 2; snapY += (need - u) / 2 }
        u = (gwF - snapX) + snapY
        if (u < need) { snapX -= (need - u) / 2; snapY += (need - u) / 2 }
        u = (gwF - snapX) + (ghF - snapY)
        if (u < need) { snapX -= (need - u) / 2; snapY -= (need - u) / 2 }
        u = snapX + (ghF - snapY)
        if (u < need) { snapX += (need - u) / 2; snapY -= (need - u) / 2 }
    }

    // ---------------- Drawing ----------------

    // While lanterns and fireflies are out, the garden is drawn again 25-30 times a second, but only their light
    // changes: so the still parts (the sand, with everything on it, and the wooden frame) are kept as finished
    // pictures, drawn again only when something in them changes (see invalidate), and each of those frames only adds
    // the moving light on top. (Android 10 and newer; before that, everything is drawn each time.)
    private var stillChanged = true          // something other than the moving light changed since the last frame
    // The frame changes far less often than the sand (only its size, the brightness and the tools arrow), so it has
    // its own mark: raking doesn't draw it again every frame
    private var frameChanged = true
    private var frameDrawnW = -1f
    private var frameDrawnH = -1f
    private var frameDrawnInset = -1f
    private var sandStill: android.graphics.RenderNode? = null
    private var frameStill: android.graphics.RenderNode? = null
    private val flickerFrame = Runnable { lightMoved() }

    // Anything at all changed (raking, placing, the brightness...): the still parts are drawn again too
    override fun invalidate() {
        stillChanged = true
        super.invalidate()
    }

    // Only the lanterns' flicker or the fireflies moved: the still parts stay as they are
    private fun lightMoved() = super.invalidate()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val t0 = System.nanoTime()
        canvas.withTranslation(offX, offY) { drawTray(this, bmp) }
        // (only once the kept pictures were really drawn again: Save picture draws without them)
        if (canvas.isHardwareAccelerated) stillChanged = false
        timed(T_DRAW, t0)
        // keep the flame flickering (about 25 times a second, only while a lantern is out and this is on screen;
        // while fireflies are out, they keep it going instead, see flyStep)
        if (lampX.isNotEmpty() && !flyRunning) { removeCallbacks(flickerFrame); postDelayed(flickerFrame, 40) }
    }

    // The tray, where it sits in this view
    private fun drawTray(canvas: Canvas, bmp: Bitmap) {
        // the sand, everything placed on it, the Auto rake and the dusk (kept, see above)
        drawStill(canvas, 0) { drawSand(it, bmp) }
        canvas.withClip(sandRect) {
            // the lanterns' flame light, added on top (faint in daylight, bright in the dark)
            // (only how bright it's drawn changes as it flickers)
            for (layer in lampLights) {
                val bright = (255 * flicker(layer) * lampPower).toInt().coerceIn(0, 255)
                if (drawSmoothLamp(this, layer, bright)) continue
                lampRect.set(inset + layer.ox * cell, inset + layer.oy * cell,
                    inset + (layer.ox + LAMP_SIZE) * cell, inset + (layer.oy + LAMP_SIZE) * cell)
                lampPaint.alpha = bright
                drawBitmap(layer.picture, null, lampRect, lampPaint)
            }
            drawFireflyLight(this)
            drawFireflies(this)
        }
        // the wooden frame on top, its corner blocks hiding the sand's corners (kept too, and drawn again only when
        // it changed itself)
        val frameNew = frameChanged || trayW != frameDrawnW || trayH != frameDrawnH || inset != frameDrawnInset
        if (drawStill(canvas, 1, frameNew) { drawWholeFrame(it) }) {
            frameChanged = false; frameDrawnW = trayW; frameDrawnH = trayH; frameDrawnInset = inset
        }
    }

    // Draws one of the still parts (0: the sand, 1: the frame), from its kept picture unless `changed` (the sand's
    // is drawn again after anything at all changed). Returns true if the kept picture was drawn again.
    private inline fun drawStill(canvas: Canvas, which: Int, changed: Boolean = stillChanged, draw: (Canvas) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !canvas.isHardwareAccelerated) { draw(canvas); return false }
        val node = (if (which == 0) sandStill else frameStill) ?: android.graphics.RenderNode("still").also {
            it.setUseCompositingLayer(true, null)
            if (which == 0) sandStill = it else frameStill = it
        }
        val w = ceil(trayW).toInt()
        val h = ceil(trayH).toInt()
        var redrawn = false
        if (changed || !node.hasDisplayList() || node.width != w || node.height != h) {
            node.setPosition(0, 0, w, h)
            draw(node.beginRecording(w, h))
            node.endRecording()
            redrawn = true
        }
        canvas.drawRenderNode(node)
        return redrawn
    }

    private fun drawSand(canvas: Canvas, bmp: Bitmap) {
        paint.shader = null
        canvas.withClip(sandRect) {
            if (!drawSmoothSand(this)) drawBitmap(bmp, null, gridRect, sandPaint)
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
        }
    }

    private fun drawWholeFrame(canvas: Canvas) {
        drawFrame(canvas)
        // darker: the dusk falls on the frame too
        if (Color.alpha(tint) > 0) {
            paint.style = Paint.Style.FILL
            paint.color = tint
            canvas.drawPath(frameShape, paint)
        }
    }

    // ---------------- The smooth drawing (Android 13 and newer) ----------------

    // Draws the sand with the smooth drawing (see SandRenderer). Returns false if the garden's own drawing should be
    // used instead (older phones, saving a picture, or if the graphics chip can't run it).
    private fun drawSmoothSand(canvas: Canvas): Boolean {
        val gpu = smooth ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (gpu.drawSand(canvas, inset, cell, SANDS[sand])) return true
        if (gpu.broken) leaveSmooth()
        return false
    }

    // Draws lantern `layer`'s light with the smooth drawing, `bright` (0-255) as it flickers
    private fun drawSmoothLamp(canvas: Canvas, layer: LampLight, bright: Int): Boolean {
        val gpu = smooth ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val lamp = layer.smooth ?: return false
        if (gpu.drawLamp(canvas, lamp, inset, cell, SANDS[sand][1], bright / 255f)) return true
        if (gpu.broken) leaveSmooth()
        return false
    }

    // The graphics chip couldn't run the smooth drawing: the garden's own drawing from now on (worked out again)
    private fun leaveSmooth() {
        smooth = null
        post { redrawAll() }
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

    // The garden as a picture, the size it is on screen (for "Save picture"): just the tray, without the clear
    // space around it when it's scaled to fit a window of a different shape
    // (a picture is drawn on the phone's processor, which the smooth drawing can't do: so the colours are worked out
    // the garden's own way for it first)
    fun picture(): Bitmap? {
        val w = trayW.roundToInt()
        val h = trayH.roundToInt()
        if (bitmap == null || w <= 0 || h <= 0) return null
        val wasSmooth = smooth != null
        if (wasSmooth) {
            plainColours = true
            shade(0, 0, gw - 1, gh - 1)
            relight(0, 0, gw - 1, gh - 1)
            plainColours = false
            flies.forEach { lightFirefly(it) }   // (the fireflies' glow on the sand too)
        }
        val out = createBitmap(w, h).also { Canvas(it).withTranslation(-offX, -offY) { draw(this) } }
        if (wasSmooth) {
            flies.forEach { it.lit = false }
            letGoOwnDrawing()
        }
        return out
    }

    // The smooth drawing is in use: the garden's own drawing's pictures and values (made for Save picture, or used
    // before Battery saver was switched off) are let go, so they don't take up memory (10-15 MB for a big garden)
    private fun letGoOwnDrawing() {
        bitmap = createBitmap(1, 1)   // (not recycled: the screen may still be showing the old one this frame)
        squareBright = FloatArray(0); squareLight = FloatArray(0); squareSlope = FloatArray(0)
        fineColors = IntArray(0)
        lampLights.forEach { it.letGo() }
    }

    // ---------------- Timings (test builds only) ----------------

    // Test builds write, once a second while the garden is busy, how long it spent on each step (adb logcat -s
    // ZenTiming): pressing the rake into the sand, its shading values, the lantern light, and drawing
    private val timing = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val timeSum = LongArray(4)
    private val timeCount = IntArray(4)
    private var timeFrom = 0L

    private fun timed(step: Int, from: Long) {
        if (!timing) return
        timeSum[step] += System.nanoTime() - from
        timeCount[step]++
        val now = SystemClock.uptimeMillis()
        if (timeFrom == 0L) timeFrom = now
        if (now - timeFrom < 1000) return
        val names = arrayOf("rake", "shade", "light", "draw")
        android.util.Log.d("ZenTiming", names.indices.joinToString("  ") { k ->
            "%s %dx %.1fms".format(names[k], timeCount[k], timeSum[k] / 1e6)
        } + "  (in %d ms)".format(now - timeFrom))
        timeSum.fill(0); timeCount.fill(0); timeFrom = now
    }

    // ---------------- Saving ----------------

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        removeCallbacks(hush)
        removeCallbacks(autoFrame)
        removeCallbacks(flyFrame)
        removeCallbacks(saveLater)
        removeCallbacks(autoAfterResize)
        removeCallbacks(redrawMore)          // (a whole-tray redraw carries on when it shows again)
        removeCallbacks(relightLater)
        relightNow()                         // (any lantern light still waiting is done now)
        // the smooth drawing's kept pictures, and the still parts', are let go (drawn again when the window opens)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) smooth?.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { sandStill?.discardDisplayList(); frameStill?.discardDisplayList() }
        removeCallbacks(flickerFrame)
        flyRunning = false
        autoResizing = false
        anim?.end()
        sound.stop()
        save()
        super.onDetachedFromWindow()
    }

    // Saves a few seconds after a change has finished (and not more often), so the garden isn't lost if the app
    // is closed suddenly (by Android, or by installing a new version) while its window is open
    // (and never in the middle of a stroke: it waits until your finger is off the sand)
    private val saveLater = Runnable { if (touching) saveSoon() else save() }

    private fun saveSoon() {
        removeCallbacks(saveLater)
        postDelayed(saveLater, 3000)
    }

    // Only a quick copy of the garden is taken here; packing it up small and writing it to the phone's storage (the
    // slow part, which made raking stutter when it came along mid-stroke) is done on a background thread, unless
    // `now` (the bubble is closing: the garden must be saved before the app can stop). The garden is written whole
    // into memory first, then Android's AtomicFile swaps the new file in only once it's completely written, so an app
    // closed mid-save keeps the last good garden.
    fun save(now: Boolean = false) {
        if (!changed || gw == 0) return
        changed = false
        val w = gw; val h = gh
        // heights (in dp) squeezed into one byte each (plenty for sand)
        val bytes = ByteArray(w * h) { ((heights[it] / RES).coerceIn(-2f, 2f) + 2f).times(63.5f).toInt().toByte() }
        val things = items.map { it.copy() }
        val fireflies = flies.count { it.leaving < 0f }
        val write = Runnable {
            val file = AtomicFile(saveFile)
            var stream: java.io.FileOutputStream? = null
            try {
                val data = ByteArrayOutputStream()
                DataOutputStream(GZIPOutputStream(data).buffered()).use { out ->
                    out.writeInt(4)   // file version
                    out.writeFloat(RES)   // how fine the sand is (squares per dp)
                    out.writeInt(w); out.writeInt(h)
                    out.write(bytes)
                    out.writeInt(things.size)
                    for (s in things) {
                        out.writeInt(s.kind)
                        out.writeFloat(s.x); out.writeFloat(s.y); out.writeFloat(s.r)
                        out.writeFloat(s.aspect); out.writeFloat(s.angle); out.writeInt(s.color)
                        out.writeInt(s.seed); out.writeInt(s.rings)
                    }
                    out.writeInt(fireflies)
                }
                stream = file.startWrite()
                stream.write(data.toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                stream?.let { file.failWrite(it) }   // the old file stays as it was
                post { changed = true }              // (tried again next time)
            }
        }
        // (right now: any save still going on in the background is finished first, so the two never write at once)
        if (now) { waitForSaves(); write.run() } else { saving = true; SAVE_THREAD.execute(write) }
    }

    // Returns the saved grid size and heights (and puts the saved items back), or null if there's nothing saved.
    // (Version 1 files, from the first Zen Garden, only had stones; version 3 added the fireflies; version 4 says
    // how fine the sand is. Older files are 1 square per dp. A garden saved at another fineness is stretched to
    // today's, so it looks just the same.)
    private fun load(): Triple<Int, Int, FloatArray>? = try {
        // (a save of the garden just left may still be being written in the background: it's finished first, or
        // opening the file would throw away the half-written save, and with it the last strokes)
        waitForSaves()
        // (AtomicFile also finds a garden whose save was cut short and puts the last good one back; no garden
        // saved yet ends up in the catch below, with nothing placed)
        DataInputStream(GZIPInputStream(AtomicFile(saveFile).openRead()).buffered()).use { inp ->
            val version = inp.readInt()
            if (version !in 1..4) return null
            val fileRes = if (version >= 4) inp.readFloat() else 1f
            val k = RES / fileRes                  // how much bigger everything is in today's squares
            val w = inp.readInt(); val h = inp.readInt()
            val bytes = ByteArray(w * h)
            inp.readFully(bytes)
            val n = inp.readInt()
            items.clear()
            repeat(n) {
                val kind = if (version == 1) STONE else inp.readInt()
                val x = inp.readFloat() * k; val y = inp.readFloat() * k; val r = inp.readFloat() * k
                val aspect = inp.readFloat(); val angle = inp.readFloat(); val color = inp.readInt()
                val seed = if (version == 1) Random.nextInt() else inp.readInt()
                val rings = if (version == 1) 0 else inp.readInt()
                // (lanterns were bigger before: they all come back at today's size)
                items.add(Item(kind, x, y, if (kind == LANTERN) LANTERN_SIZE else r, aspect, angle, color, seed, rings))
            }
            savedFlies = if (version >= 3) inp.readInt().coerceIn(0, MAX_FLIES) else 0
            // heights were saved in dp (version 4) or in the old squares, which were dp too: today's are in squares
            val saved = FloatArray(w * h) { ((bytes[it].toInt() and 0xFF) / 63.5f - 2f) * RES }
            if (k == 1f) Triple(w, h, saved)
            else {
                val nw = max(1, (w * k).roundToInt())
                val nh = max(1, (h * k).roundToInt())
                Triple(nw, nh, stretchGrid(saved, w, h, nw, nh))
            }
        }
    } catch (e: Exception) { items.clear(); null }

    // A grid of heights (w by h) stretched smoothly to nw by nh
    private fun stretchGrid(old: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val sx = w.toFloat() / nw
        val sy = h.toFloat() / nh
        return FloatArray(nw * nh) { i ->
            val fx = ((i % nw + 0.5f) * sx - 0.5f).coerceIn(0f, w - 1f)
            val fy = ((i / nw + 0.5f) * sy - 0.5f).coerceIn(0f, h - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
            val tx = fx - x0; val ty = fy - y0
            (old[y0 * w + x0] * (1 - tx) + old[y0 * w + x1] * tx) * (1 - ty) + (old[y1 * w + x0] * (1 - tx) + old[y1 * w + x1] * tx) * ty
        }
    }
}
