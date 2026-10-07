package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.SandGardenView.Companion.RAKE
import com.sinat.osrsbubbletool.SandGardenView.Companion.RES
import com.sinat.osrsbubbletool.SandGardenView.Companion.STICK
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// The Zen Garden's auto rake patterns: which strokes a pattern rakes on a tray of a given size, and the rings around
// placed stones and lanterns. Sizes are in sand squares (SandGardenView.RES per dp). The garden walks the rake along
// them (SandGardenView.autoStep). No Android parts, so it's tested on the PC (ZenPatternsTest).
object ZenPatterns {

    // The auto rake's patterns (the Game Room's list shows them in this order)
    val NAMES = listOf("Rows", "Seed of Life", "Spiral", "Lotus", "Waves", "Golden Spiral", "Ripples",
        "Rose Window", "Spirograph", "Lissajous", "Basket Weave", "Square Spiral", "Flower of Life", "Mandala",
        "Seven Star", "Rain", "Chevrons")   // (raked in a random order, each once before any comes round again)

    // The strokes for pattern `p` (one of NAMES) on a tray gw by gh squares. Circles start at a random point
    // (`random`: fixed in tests).
    fun strokes(p: Int, gw: Int, gh: Int, random: Random = Random): List<Stroke> = Tray(gw, gh, random).patternStrokes(p)

    // Three rings close around each spot, from the inside out, always with the stick. `spots`: x, y and how far out
    // the thing covers the sand, for each stone and lantern in the order they were placed.
    fun rings(spots: FloatArray, random: Random = Random): List<Stroke> {
        val tray = Tray(0, 0, random)
        val half = teeth(STICK) * toothGap(STICK) / 2
        val list = ArrayList<Stroke>()
        for (s in 0 until spots.size / 3) {
            for (k in 0 until 3) list.add(Stroke(tray.circle(spots[3 * s], spots[3 * s + 1], spots[3 * s + 2] + 2f * RES + half + k * 2 * half), STICK))
        }
        return list
    }

    // The tray the patterns are laid out on, and where circles start
    private class Tray(val gw: Int, val gh: Int, val shapeRandom: Random) {
        fun patternStrokes(p: Int): List<Stroke> {
            val cx = gw / 2f
            val cy = gh / 2f
            val size = min(gw, gh).toFloat()
            val far = hypot(gw.toFloat(), gh.toFloat()) / 2
            val rakeWidth = teeth(RAKE) * toothGap(RAKE)
            return when (NAMES[p]) {
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
                // a squared-off spiral, raked from the edge inwards, its corners gently rounded. Its turns are spaced to
                // divide the tray's short side evenly (a little under a rake's width apart), so it closes neatly with one
                // straight line along the middle (across a wide tray, down a tall one), instead of stopping wherever
                // the space ran out and leaving the middle unraked.
                "Square Spiral" -> {
                    val corners = ArrayList<Float>()
                    var l = rakeWidth * 0.6f; var t = rakeWidth * 0.6f
                    var r = gw - rakeWidth * 0.6f; var b = gh - rakeWidth * 0.6f
                    val short = min(r - l, b - t)
                    val turns = max(1, ceil(short / (2 * rakeWidth * 0.95f)).toInt())
                    val gap = short / (2 * turns)
                    corners.add(l); corners.add(t)
                    repeat(turns) {
                        corners.add(r); corners.add(t)
                        corners.add(r); corners.add(b)
                        corners.add(l); corners.add(b)
                        t += gap
                        corners.add(l); corners.add(t)
                        l += gap; r -= gap; b -= gap
                    }
                    // the line along the middle, reaching half a gap further so it meets the last turn
                    if (r - l >= b - t) { corners.add(r + gap / 2); corners.add(t) }
                    else { corners.add(l); corners.add(t); corners.add(l); corners.add(b + gap / 2) }
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
        @Suppress("SameParameterValue")   // (kept general on purpose)
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
        @Suppress("SameParameterValue")   // (kept general on purpose)
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
        fun circle(cx: Float, cy: Float, r: Float): FloatArray {
            val start = shapeRandom.nextFloat() * 6.283f
            val sweep = 2 * PI.toFloat() + TAPER / r
            val steps = ceil(sweep * r).toInt()
            return FloatArray(2 * (steps + 1)) { j ->
                val a = start + sweep * (j / 2) / steps
                if (j % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
            }
        }
    }
}

// ---------------- Shared with the garden's own raking ----------------

// A stroke: points one square apart (x, y, x, y, ...), raked with the rake or the stick
class Stroke(val points: FloatArray, val kind: Int)

// How many teeth a tool has, and how far apart they are, in squares
internal fun teeth(kind: Int) = if (kind == RAKE) 5 else 1
internal fun toothGap(kind: Int) = if (kind == RAKE) 6f * RES else 7f * RES   // (6 and 7 dp)

internal const val TAPER = 8f * RES        // how far the rake takes to sink in at a stroke's start, and lift out at its end

// The points again, one square apart
internal fun resample(p: FloatArray, count: Int): FloatArray {
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

// Wobbles evened out: each point moved to the average of its neighbours (the ends stay put)
internal fun smoothed(p: FloatArray, n: Int): FloatArray = FloatArray(2 * n) { j ->
    val k = j / 2
    val w = min((6 * RES).toInt(), min(k, n - 1 - k))   // (6 dp each way)
    var sum = 0f
    for (o in -w..w) sum += p[2 * (k + o) + (j % 2)]
    sum / (2 * w + 1)
}
