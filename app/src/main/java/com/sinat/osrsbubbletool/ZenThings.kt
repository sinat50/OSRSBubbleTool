package com.sinat.osrsbubbletool

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// The Zen Garden's placed things, worked out from their seed: a stone (or a little group), a bonsai, a lantern's roof
// or a flower clump, as a look: for each square in a box around its middle, how tall it stands, its colour and how
// far inside its outline the square is. The garden presses looks into the sand (SandGardenView.bakeLook). No Android
// parts, so it's tested on the PC (ZenThingsTest). See docs/zen-garden.md.
object ZenThings {

    private const val RES = SandGardenView.RES

    // stones (middle, darker mottling, lighter mottling): granite grey, blue-grey, warm brown-grey, weathered
    // green-grey, dark stone; their pale lichen, a white quartz vein, and the moss some groups sit in
    private val ROCKS = arrayOf(
        intArrayOf(colour("#8C8880"), colour("#6A665F"), colour("#B4AFA5")),
        intArrayOf(colour("#6F747A"), colour("#52565C"), colour("#9AA0A4")),
        intArrayOf(colour("#8A7E70"), colour("#665C52"), colour("#B2A592")),
        intArrayOf(colour("#7C8172"), colour("#5A6052"), colour("#A4A894")),
        intArrayOf(colour("#55544F"), colour("#3C3B38"), colour("#807E78")),
    )
    private val LICHEN = colour("#B8BCA0")
    private val QUARTZ = colour("#E6E2D8")
    private val ROCK_MOSS_DARK = colour("#3E5220")
    private val ROCK_MOSS_LIGHT = colour("#6A7E34")
    // the kinds of stone (see makeRock)
    private const val CRAG = 0
    private const val BOULDER = 1
    private const val SLAB = 2
    private const val PEBBLE = 3
    // a lantern's weathered granite (middle, darker, lighter), and the moss in its roof's hollows
    val LANTERN_GREY = colour("#8F8B83")
    private val LANTERN_DARK = colour("#5E5B55")
    private val LANTERN_LIGHT = colour("#B3AEA4")
    private val LANTERN_MOSS = colour("#5A6E2C")
    // bonsai pots: unglazed brown and grey stoneware, a blue glaze, a red-brown clay
    val POT_COLORS = intArrayOf(colour("#6B5443"), colour("#55504B"), colour("#3E5A78"), colour("#7A3B2E"))
    private val SOIL = colour("#5C4A38")
    private val GRIT = colour("#9A8F7E")
    private val MOSS = colour("#667A36")
    private val BARK = colour("#54443A")
    private val BARK_LIGHT = colour("#9C907F")
    // a bonsai's leaves (shade, middle, sunlit tips): pine, juniper, autumn maple
    private val FOLIAGE = arrayOf(
        intArrayOf(colour("#1B2C13"), colour("#40602A"), colour("#9AB060")),
        intArrayOf(colour("#183022"), colour("#386545"), colour("#93BA7C")),
        intArrayOf(colour("#4A1A10"), colour("#A8402A"), colour("#F09A50")),
    )
    // the kinds of flower (see makeFlowerLooks), each with its blossom colours, and its leaves (dark, light, vein):
    // camellia (deep pink, red, white, coral), chrysanthemum (magenta, yellow, white, rust), cherry blossom
    // (pale pinks, white)
    const val CAMELLIA = 0
    const val CHRYSANTHEMUM = 1
    const val CHERRY = 2
    val PETAL_COLOURS = arrayOf(
        intArrayOf(colour("#D94A78"), colour("#C8303A"), colour("#F2EEE6"), colour("#E8735A")),
        intArrayOf(colour("#C8508C"), colour("#E8C040"), colour("#EEE8DA"), colour("#C46A30")),
        intArrayOf(colour("#F2C4D0"), colour("#F6EEF0"), colour("#EFB0C4")),
    )
    private val FLOWER_LEAVES = arrayOf(
        intArrayOf(colour("#1E3A1C"), colour("#4E7A36"), colour("#8AA060")),
        intArrayOf(colour("#2E4628"), colour("#6A8452"), colour("#9AAE80")),
        intArrayOf(colour("#2A4A20"), colour("#6A9040"), colour("#9CB870")),
    )

    // which kind of flower a clump is, from its seed
    fun flowerKind(seed: Int) = ((seed ushr 4) and 0xFFFF) % 3

    // A placed thing, as far as its look goes
    private class Thing(kind: Int, val r: Float, val angle: Float, val color: Int, val seed: Int, given: FloatArray?) {
        val blobs: FloatArray = given ?: if (kind == SandGardenView.FLOWERS) blossoms(seed, r) else FloatArray(0)
    }

    // A placed thing's look, from its kind, size (in squares), turn (degrees), colour and seed (see makeBonsai,
    // makeStone, makeLantern and makeFlowers). The same every time for the same values.
    // (`blossoms`: a flower clump's blossoms if already worked out, see blossoms, so they aren't worked out twice)
    fun look(kind: Int, r: Float, angle: Float, color: Int, seed: Int, blossoms: FloatArray? = null): Look {
        val s = Thing(kind, r, angle, color, seed, blossoms)
        return when (kind) {
            SandGardenView.BONSAI -> makeBonsai(s)
            SandGardenView.LANTERN -> makeLantern(s)
            SandGardenView.FLOWERS -> makeFlowers(s)
            else -> makeStone(s)
        }.also { look ->
            // how far out from its middle it really covers the sand (a stone's companions and moss, a bonsai's leaves)
            var far = 0f
            for (gy in -look.half..look.half) for (gx in -look.half..look.half) {
                if (look.height[(gy + look.half) * look.size + gx + look.half] < 0) continue
                // (the square's corner furthest from the middle, which is at a square's corner, see onSquare)
                far = max(far, hypot(max(abs(gx), abs(gx + 1)).toFloat(), max(abs(gy), abs(gy + 1)).toFloat()))
            }
            look.reach = far
        }
    }

    // A bonsai or stone, worked out once: for each square in a box around its middle, how tall it stands (-1 =
    // nothing there), its colour, and how far inside its outline the square is (see put)
    class Look(val half: Int) {
        val size = 2 * half + 1
        val height = FloatArray(size * size) { -1f }
        val colour = IntArray(size * size)
        val edge = FloatArray(size * size)
        val cast = FloatArray(size * size)      // the height smoothed, for blocking the lanterns' light (see castSolid)
        val soft = FloatArray(size * size)      // how soft its edge is (see objSoft)
        var reach = 0f                          // how far out from its middle it covers the sand, in squares (see lookOf)
    }

    // A flower clump's blossoms, worked out once from its seed: a few open ones, one or two half open and a few
    // buds, kept apart so none hides another. 5 numbers each: x, y (from the clump's middle), size, turn (degrees),
    // and 2 = open, 1 = half open, 0 = a bud. Kept buds first, so the open ones are drawn over them.
    fun blossoms(seed: Int, r: Float): FloatArray {
        val rnd = Random(seed xor 0x5F3759DF)
        val kind = flowerKind(seed)
        val open = if (kind == CHERRY) 3 + rnd.nextInt(3) else 2 + rnd.nextInt(2)
        val halfOpen = if (kind == CHERRY) 2 else 1 + rnd.nextInt(2)
        val buds = 2 + rnd.nextInt(3)
        val n = open + halfOpen + buds
        val placed = FloatArray(5 * n)
        // the open ones are placed first (they get the best spots), the buds last
        for (k in 0 until n) {
            val stage = if (k < open) 2 else if (k < open + halfOpen) 1 else 0
            val size = r * (0.85f + 0.3f * rnd.nextFloat()) * when (stage) {
                2 -> if (kind == CHERRY) 0.17f else 0.25f
                1 -> 0.16f
                else -> 0.07f
            }
            var x = 0f
            var y = 0f
            for (tries in 0 until 30) {
                val ang = rnd.nextFloat() * 6.283f
                val d = r * (if (stage > 0) 0.5f else 0.6f) * sqrt(rnd.nextFloat())
                x = d * cos(ang); y = d * sin(ang)
                var clear = true
                for (j in 0 until k) {
                    if (hypot(x - placed[5 * j], y - placed[5 * j + 1]) <= (size + placed[5 * j + 2]) * 0.8f) { clear = false; break }
                }
                if (clear) break
            }
            placed[5 * k] = x; placed[5 * k + 1] = y; placed[5 * k + 2] = size
            placed[5 * k + 3] = rnd.nextFloat() * 360f; placed[5 * k + 4] = stage.toFloat()
        }
        // kept buds first, then the half open ones, then the open ones
        val blobs = FloatArray(5 * n)
        var at = 0
        for (stage in 0..2) for (k in 0 until n) {
            if (placed[5 * k + 4].toInt() != stage) continue
            for (j in 0 until 5) blobs[5 * at + j] = placed[5 * k + j]
            at++
        }
        return blobs
    }

    // A flower clump's blossom colour (old saved moss had none: pick one from its seed)
    fun petalColour(color: Int, seed: Int) = if (color != 0) color else
        PETAL_COLOURS[flowerKind(seed)].let { c -> c[(seed and 0x7FFFFFFF) % c.size] }

    // A flower clump's leaves (drawn from photos of camellias, a satsuki azalea, chrysanthemums and a kusamono
    // planting): two dozen or more pointed oval leaves fanning out from the middle, each arching up and drooping at
    // its tip, folded a little along its paler vein, darker at its edges and lighter towards its tip. The outer
    // leaves are long and low, the inner ones shorter and higher. Camellia leaves are broad, chrysanthemum leaves
    // lobed. Under each blossom, the blossom's own colour, so lantern and firefly light on it is the right colour
    // (the blossoms themselves are drawn sharply on top, see drawFlowers).
    private fun makeFlowers(s: Thing): Look {
        val rnd = Random(s.seed)
        val kind = flowerKind(s.seed)
        val colours = FLOWER_LEAVES[kind]
        val petal = petalColour(s.color, s.seed)
        val r = s.r
        val n = 24 + rnd.nextInt(7)
        // 6 numbers each: which way it points, its length, its width, how high its stem end is, and where its stem
        // end is (x, y)
        val leaves = FloatArray(6 * n)
        for (k in 0 until n) {
            val inner = k.toFloat() / n
            val length = r * (1 - 0.5f * inner) * (0.85f + 0.25f * rnd.nextFloat())
            leaves[6 * k] = k * 2.4f + rnd.nextFloat() * 0.5f
            leaves[6 * k + 1] = length
            leaves[6 * k + 2] = length * ((if (kind == CAMELLIA) 0.5f else 0.42f) + 0.08f * rnd.nextFloat())
            leaves[6 * k + 3] = r * (0.05f + 0.3f * inner)
            leaves[6 * k + 4] = (rnd.nextFloat() - 0.5f) * 0.2f * r
            leaves[6 * k + 5] = (rnd.nextFloat() - 0.5f) * 0.2f * r
        }
        // (worked out once per leaf, not again for every square: which way it points, and how far either side of its
        // vein it can reach at its widest)
        val ux = FloatArray(n) { cos(leaves[6 * it]) }
        val uy = FloatArray(n) { sin(leaves[6 * it]) }
        val widest = FloatArray(n) { leaves[6 * it + 2] * 0.5f * if (kind == CHRYSANTHEMUM) 1.22f else 1f }
        val look = Look(ceil(1.2f * r).toInt())
        val half = look.half
        val b = s.blobs
        for (gy in -half..half) for (gx in -half..half) {
            val dx = gx + 0.5f
            val dy = gy + 0.5f
            val speck = ((squareHash(gx, gy) ushr 3) and 255) / 255f
            var bestH = -1f
            var bestC = 0
            var edge = 0f
            for (k in 0 until n) {
                val o = 6 * k
                val length = leaves[o + 1]; val width = leaves[o + 2]
                val px = dx - leaves[o + 4]; val py = dy - leaves[o + 5]
                val along = (px * ux[k] + py * uy[k]) / length      // 0 at its stem, 1 at its tip
                if (along <= 0f || along >= 1f) continue
                val across = -px * uy[k] + py * ux[k]
                if (abs(across) >= widest[k]) continue              // (beside the leaf, even at its widest)
                var hw = width * 0.5f * sin(PI.toFloat() * along.pow(0.8f)).pow(0.9f)
                if (kind == CHRYSANTHEMUM) hw *= 1 + 0.22f * sin(along * PI.toFloat() * 7)
                if (hw <= 0.2f || abs(across) >= hw) continue
                val q = abs(across) / hw
                edge = max(edge, hw - abs(across))
                val h = leaves[o + 3] + 0.168f * r * sin(PI.toFloat() * min(1f, along * 1.1f)) + width * 0.18f * (1 - q)
                if (h <= bestH) continue
                var c = tone(blend(colours[0], colours[1], 0.25f + 0.5f * along + 0.15f * speck), 0.85f + 0.2f * (1 - q))
                if (abs(across) < 0.35f * RES && along < 0.85f) c = blend(c, colours[2], 0.55f)   // the vein
                bestH = h; bestC = c
            }
            if (bestH < 0) continue
            for (k in 0 until b.size / 5) {
                if (hypot(dx - b[5 * k], dy - b[5 * k + 1]) < b[5 * k + 2]) { bestH = 0.5f * r; bestC = petal; break }
            }
            val i = (gy + half) * look.size + gx + half
            look.height[i] = bestH; look.colour[i] = bestC; look.edge[i] = edge
        }
        smoothCast(look)
        return look
    }

    // A bonsai as real ones look from above (drawn from photos of the pines, junipers and maples at the US National
    // Bonsai Museum): a shallow pot with a rim, soil with moss and grit, a thick trunk starting near one end and
    // leaning towards the other with roots spreading over the soil, and branches ending in flat "cloud" pads of
    // leaves, the low ones long and the high ones short, with one pad on top. Each pad is a few overlapping
    // domes covered in small tufts, so its outline is scalloped and a little ragged. Higher parts shade the ones
    // below them (the sun is top left, as for the sand). Everything comes from its seed; the kind of tree too.
    // (Sizes are parts of r, its size; tufts and grain are in dp, × RES.)
    private fun makeBonsai(s: Thing): Look {
        val rnd = Random(s.seed)
        val r = s.r
        val turn = Math.toRadians(s.angle.toDouble()).toFloat()
        val ca = cos(turn); val sa = sin(turn)
        val leaves = FOLIAGE[((s.seed ushr 8) and 0x7FFF) % FOLIAGE.size]
        val potH = 0.22f * r                     // how tall the pot stands, and the tree's top
        val topH = 1.35f * r
        val oval = rnd.nextBoolean()             // an oval pot, or a rounded rectangle
        val potA = 0.76f * r; val potB = 0.52f * r; val rimW = 0.11f * r
        // Sticks (the trunk, roots and branches), 8 numbers each: from x, y, height to x, y, height, and the width
        // at each end. Domes (the leaves), 5 each: x, y, size, height of the bottom, how much it bulges.
        val st = FloatArray(8 * 16); var ns = 0
        val dm = FloatArray(5 * 80); var nd = 0
        // the trunk: from near one end of the pot, leaning over, with a few bends
        val baseX = -0.3f * r * ca; val baseY = -0.3f * r * sa
        val lean = turn + (rnd.nextFloat() - 0.5f) * 0.8f
        val apexX = baseX + 0.38f * r * cos(lean); val apexY = baseY + 0.38f * r * sin(lean)
        var px = baseX; var py = baseY
        for (k in 1..3) {
            val f = k / 3f
            val w = (rnd.nextFloat() - 0.5f) * 0.14f * r
            val nx = baseX + (apexX - baseX) * f - w * sin(lean)
            val ny = baseY + (apexY - baseY) * f + w * cos(lean)
            val f0 = (k - 1) / 3f
            st[8 * ns] = px; st[8 * ns + 1] = py; st[8 * ns + 2] = potH + (topH - potH) * f0 * 0.8f
            st[8 * ns + 3] = nx; st[8 * ns + 4] = ny; st[8 * ns + 5] = potH + (topH - potH) * f * 0.8f
            st[8 * ns + 6] = 0.1f * r * (1 - 0.5f * f0); st[8 * ns + 7] = 0.1f * r * (1 - 0.5f * f); ns++
            px = nx; py = ny
        }
        // roots over the soil
        for (k in 0 until 5) {
            val a = rnd.nextFloat() * 6.283f
            val len = r * (0.15f + 0.12f * rnd.nextFloat())
            st[8 * ns] = baseX; st[8 * ns + 1] = baseY; st[8 * ns + 2] = potH + 0.04f * r
            st[8 * ns + 3] = baseX + len * cos(a); st[8 * ns + 4] = baseY + len * sin(a); st[8 * ns + 5] = potH - 0.02f * r
            st[8 * ns + 6] = 0.06f * r; st[8 * ns + 7] = 0.015f * r; ns++
        }
        // branches going round the trunk (each a golden angle on from the last), longer on the side it leans to,
        // each ending in a pad: an oval across the branch, a big dome in the middle and a ring of smaller ones
        val branches = 5 + rnd.nextInt(3)
        val a0 = rnd.nextFloat() * 6.283f
        for (k in 0..branches) {
            val top = k == branches              // (the last pad sits on top of the trunk)
            val f = k.toFloat() / branches       // 0 = the lowest
            val ang = if (top) rnd.nextFloat() * 6.283f else a0 + k * 2.4f + (rnd.nextFloat() - 0.5f) * 0.5f
            var padX = apexX; var padY = apexY; var low = topH
            var size = r * 0.36f * 0.85f
            if (!top) {
                val length = r * (0.7f - 0.3f * f) * (0.85f + 0.25f * rnd.nextFloat()) * (1 + 0.3f * cos(ang - lean))
                val sx = baseX + (apexX - baseX) * (0.3f + 0.6f * f)
                val sy = baseY + (apexY - baseY) * (0.3f + 0.6f * f)
                val sh = potH + (topH - potH) * (0.25f + 0.6f * f)
                padX = sx + length * cos(ang); padY = sy + length * sin(ang)
                val d = hypot(padX, padY)        // (kept within reach of the middle)
                if (d > 0.88f * r) { padX *= 0.88f * r / d; padY *= 0.88f * r / d }
                st[8 * ns] = sx; st[8 * ns + 1] = sy; st[8 * ns + 2] = sh
                st[8 * ns + 3] = padX; st[8 * ns + 4] = padY; st[8 * ns + 5] = sh + 0.03f * r
                st[8 * ns + 6] = 0.05f * r * (1 - 0.4f * f); st[8 * ns + 7] = 0.02f * r; ns++
                low = sh + 0.04f * r
                size = r * (0.36f - 0.07f * f) * (0.85f + 0.25f * rnd.nextFloat())
            }
            val ux = -sin(ang); val uy = cos(ang)     // across the branch
            val vx = cos(ang); val vy = sin(ang)      // along it
            dm[5 * nd] = padX; dm[5 * nd + 1] = padY; dm[5 * nd + 2] = size * 0.55f; dm[5 * nd + 3] = low
            dm[5 * nd + 4] = size * 0.32f; nd++
            val ring = 6 + rnd.nextInt(3)
            for (j in 0 until ring) {
                val a = j * 6.283f / ring + rnd.nextFloat() * 0.6f
                val out = 0.45f + 0.25f * rnd.nextFloat()
                val ox = cos(a) * size * out
                val oy = sin(a) * size * out * 0.62f
                dm[5 * nd] = padX + ux * ox + vx * oy; dm[5 * nd + 1] = padY + uy * ox + vy * oy
                dm[5 * nd + 2] = size * (0.3f + 0.22f * rnd.nextFloat()); dm[5 * nd + 3] = low - 0.02f * r
                dm[5 * nd + 4] = size * 0.22f; nd++
            }
        }

        val look = Look(ceil(1.3f * r).toInt())
        val half = look.half
        val tuftSize = 3f * RES
        val mossA = (s.seed and 7).toFloat()
        val mossB = ((s.seed ushr 3) and 7).toFloat()
        for (gy in -half..half) for (gx in -half..half) {
            val dx = gx + 0.5f
            val dy = gy + 0.5f
            val n = squareHash(gx, gy)
            val speck = ((n ushr 3) and 255) / 255f
            var bestH = -1f
            var bestC = 0
            var edge = 0f
            // the pot: its rim (a rounded lip, lit on the side facing the sun), then soil with grit and moss
            val u = dx * ca + dy * sa
            val v = -dx * sa + dy * ca
            val inside = if (oval) (1 - hypot(u / potA, v / potB)) * potB else {
                val round = 0.14f * r
                val qx = max(abs(u) - (potA - round), 0f)
                val qy = max(abs(v) - (potB - round), 0f)
                if (qx > 0 && qy > 0) round - hypot(qx, qy) else min(potA - abs(u), potB - abs(v))
            }
            if (inside > 0) {
                if (inside < rimW) {
                    bestH = potH + 0.06f * r * sin(PI.toFloat() * min(1f, inside / rimW * 1.3f))
                    bestC = tone(s.color, 1.15f + 0.12f * speck)
                    edge = inside
                } else {
                    val m = sin(dx / RES * 0.33f + mossA) * sin(dy / RES * 0.37f + 0.7f) + 0.35f * sin((dx - dy) / RES * 0.8f + mossB)
                    if (m > 0.3f) {
                        bestH = potH + 0.3f * RES * min(1f, (m - 0.3f) * 4)
                        bestC = tone(MOSS, 0.8f + 0.4f * speck)
                    } else {
                        bestH = potH - 0.2f * RES
                        bestC = if ((n ushr 11) and 15 == 0) tone(GRIT, 0.85f + 0.25f * speck) else tone(SOIL, 0.75f + 0.45f * speck)
                    }
                    edge = 9f
                }
            }
            // the trunk, roots and branches: rounded, with streaks of lighter bark
            for (k in 0 until ns) {
                val o = 8 * k
                val ax = st[o]; val ay = st[o + 1]
                val bx = st[o + 3] - ax; val by = st[o + 4] - ay
                val t = (((dx - ax) * bx + (dy - ay) * by) / (bx * bx + by * by + 1e-6f)).coerceIn(0f, 1f)
                val d = hypot(dx - ax - bx * t, dy - ay - by * t)
                val w = st[o + 6] + (st[o + 7] - st[o + 6]) * t + 0.5f
                if (d >= w) continue
                val h = st[o + 2] + (st[o + 5] - st[o + 2]) * t + w * 0.8f * sqrt(1 - (d / w) * (d / w))
                if (h > bestH) {
                    bestH = h
                    bestC = blend(BARK, BARK_LIGHT, 0.35f * (0.5f + 0.5f * sin(d / w * 7 + t * 5 + mossA)) + 0.35f * speck)
                }
                edge = max(edge, (w - d) * 2)
            }
            // the leaves: the nearest of a scattered set of tuft middles (they turn and move with the tree) makes
            // each tuft a little mound, lighter on top; the tufts also make the pads' outlines ragged
            val tx = (dx + 300) / tuftSize
            val ty = (dy + 300) / tuftSize
            val tix = tx.toInt(); val tiy = ty.toInt()
            var nearest = 9f
            var tuftId = 0
            for (oy in -1..1) for (ox in -1..1) {
                val hh = squareHash(tix + ox + s.seed, tiy + oy - s.seed)
                val jx = tix + ox + 0.1f + 0.8f * ((hh ushr 4) and 255) / 255f
                val jy = tiy + oy + 0.1f + 0.8f * ((hh ushr 12) and 255) / 255f
                val d = hypot(tx - jx, ty - jy)
                if (d < nearest) { nearest = d; tuftId = hh }
            }
            val tuft = max(0f, 1 - nearest / 0.8f)
            val tuftTone = ((tuftId ushr 20) and 255) / 255f
            for (k in 0 until nd) {
                val o = 5 * k
                val reach = dm[o + 2] * (0.85f + 0.2f * tuft)
                val d = hypot(dx - dm[o], dy - dm[o + 1])
                if (d >= reach) continue
                val e = d / reach
                edge = max(edge, (1 - e) * dm[o + 2])
                val h = dm[o + 3] + dm[o + 4] * sqrt(1 - e * e) + 0.8f * RES * tuft.pow(0.7f) + 0.25f * RES * speck
                if (h <= bestH) continue
                val lit = (0.6f * (1 - e * e) + 0.35f * tuft + 0.15f * tuftTone + 0.1f * speck - 0.05f).coerceIn(0f, 1f)
                bestH = h
                bestC = if (lit < 0.6f) blend(leaves[0], leaves[1], lit / 0.6f)
                    else blend(leaves[1], leaves[2], (lit - 0.6f) / 0.4f * 0.85f)
            }
            if (bestH < 0) continue
            val i = (gy + half) * look.size + gx + half
            look.height[i] = bestH; look.colour[i] = bestC; look.edge[i] = edge
        }
        // the shade of the higher parts: walking towards the sun (up and left), anything standing above the
        // sunlight's slope down to this square shades it (the slope is steeper than the sand's, so the shade
        // stays short and the tree isn't half in darkness)
        val size = look.size
        val drop = SandGardenView.SHADOW_DROP * 1.6f
        val shaded = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            val h = look.height[i]
            if (h < 0) continue
            var shade = 0f
            var k = 1
            while (k <= min(x, y)) {
                val over = look.height[i - k * (size + 1)] - (h + k * drop)
                if (over > 0) shade = max(shade, min(1f, over / (1.5f * RES)))
                k++
            }
            shaded[i] = shade
        }
        for (i in 0 until size * size) if (shaded[i] > 0) look.colour[i] = tone(look.colour[i], 1 - 0.55f * shaded[i])
        smoothCast(look)
        return look
    }

    // A look's smoothed height (see castSolid): each square averaged with the squares up to 2 away, across then
    // down, twice
    private fun smoothCast(look: Look) {
        val size = look.size
        val c = look.cast
        val step = FloatArray(size * size)
        for (i in 0 until size * size) c[i] = max(0f, look.height[i])
        repeat(2) {
            for (y in 0 until size) for (x in 0 until size) {
                var sum = 0f
                for (o in -2..2) sum += c[y * size + (x + o).coerceIn(0, size - 1)]
                step[y * size + x] = sum / 5
            }
            for (y in 0 until size) for (x in 0 until size) {
                var sum = 0f
                for (o in -2..2) sum += step[(y + o).coerceIn(0, size - 1) * size + x]
                c[y * size + x] = sum / 5
            }
        }
    }

    // One stone of a group: where it sits in the group (ox, oy), its size, kind and top height, how rounded its
    // edges are, a crag's peak (px, py), its faces (4 numbers each: which way the face looks (ux, uy), how steep it
    // is, and how far out it is, as a height at the middle), and about the steepest its sides get
    private class Rock(val ox: Float, val oy: Float, val r: Float, val kind: Int, val top: Float, val soft: Float,
                       val px: Float, val py: Float, val faces: FloatArray, val steepest: Float)

    // A stone made the way real ones are shaped: flat faces leaning in from every side, cut where they meet into
    // ridges (so the outline is a rough polygon, not an oval), the top capped. Crags are tall with sharp ridges up
    // to an off-centre peak; boulders low and well rounded; slabs low and flat-topped with steep sides; pebbles
    // small and rounded. (No side is much steeper than 2.6 squares up for one across: on steeper ones, the light
    // flipped from square to square along the outline and left a beaded rim.)
    private fun makeRock(rnd: Random, ox: Float, oy: Float, r: Float, kind: Int): Rock {
        val n = (if (kind == CRAG || kind == BOULDER) 9 else 7) + rnd.nextInt(4)
        val aspect = 0.6f + 0.35f * rnd.nextFloat()      // (narrower one way)
        val a0 = rnd.nextFloat() * 6.283f
        val faces = FloatArray(4 * n)
        for (k in 0 until n) {
            val a = a0 + k * 6.283f / n + (rnd.nextFloat() - 0.5f) * 0.9f
            val ux = cos(a); val uy = sin(a)
            val dist = r * (0.7f + 0.35f * rnd.nextFloat()) / sqrt(ux * ux + (uy / aspect) * (uy / aspect))
            val steep = when (kind) {
                CRAG -> 1.3f + 1f * rnd.nextFloat()
                BOULDER -> 0.7f + 0.5f * rnd.nextFloat()
                SLAB -> 1.6f + 1f * rnd.nextFloat()
                else -> 0.9f + 0.5f * rnd.nextFloat()
            }
            faces[4 * k] = ux; faces[4 * k + 1] = uy; faces[4 * k + 2] = steep; faces[4 * k + 3] = steep * dist
        }
        val top = r * when (kind) { CRAG -> 1f; BOULDER -> 0.42f; SLAB -> 0.22f; else -> 0.4f }
        val soft = r * when (kind) { CRAG -> 0.14f; BOULDER -> 0.5f; SLAB -> 0.12f; else -> 0.5f }
        val px = if (kind == CRAG) (rnd.nextFloat() - 0.5f) * 0.5f * r else 0f
        val py = if (kind == CRAG) (rnd.nextFloat() - 0.5f) * 0.5f * r else 0f
        var steepest = 0f
        for (k in 0 until n) steepest = max(steepest, faces[4 * k + 2])
        if (kind == CRAG) steepest *= 1.6f   // (the faces meet in sharper ridges up to its peak)
        return Rock(ox, oy, r, kind, top, soft, px, py, faces, steepest)
    }

    // A stone's height at (x, y) (from its middle, in squares): under every face, the edges where they meet rounded.
    // (How far inside its outline the point is goes into inside[0].)
    private fun rockAt(rk: Rock, x: Float, y: Float, inside: FloatArray): Float {
        val f = rk.faces
        var h = 0f
        var inn = Float.MAX_VALUE
        for (k in 0 until f.size / 4) {
            val v = f[4 * k + 3] - f[4 * k + 2] * (f[4 * k] * x + f[4 * k + 1] * y)
            h = if (k == 0) v else softMin(h, v, rk.soft)
            inn = min(inn, v / f[4 * k + 2])
        }
        if (rk.kind == CRAG) h = softMin(h, rk.top * 1.6f - 0.9f * hypot(x - rk.px, y - rk.py), rk.soft)
        inside[0] = inn
        return softMin(h, rk.top, rk.soft * 0.6f + 0.5f)
    }

    // The lower of two heights, rounded off where they meet (over a width of k)
    private fun softMin(a: Float, b: Float, k: Float): Float {
        val h = max(k - abs(a - b), 0f) / k
        return min(a, b) - h * h * k * 0.25f
    }

    // Smooth noise between 0 and 1, changing over about one unit (the same every time for the same seed)
    private fun noise(x: Float, y: Float, seed: Int): Float {
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = ((squareHash(ix + seed * 31, iy - seed * 17) ushr 8) and 1023) / 1023f
        val b = ((squareHash(ix + 1 + seed * 31, iy - seed * 17) ushr 8) and 1023) / 1023f
        val c = ((squareHash(ix + seed * 31, iy + 1 - seed * 17) ushr 8) and 1023) / 1023f
        val d = ((squareHash(ix + 1 + seed * 31, iy + 1 - seed * 17) ushr 8) and 1023) / 1023f
        return (a * (1 - sx) + b * sx) * (1 - sy) + (c * (1 - sx) + d * sx) * sy
    }

    // A stone, or a little group, as stones in real gardens look from above (drawn from photos of Ryoan-ji's dry
    // garden and a Kyoto courtyard garden): a main stone (a tall crag, a low boulder or a flat slab) and sometimes
    // one or two small companions, some groups in a patchy bed of moss. Their outlines and faces are bent a
    // little (so no edge is ruler-straight), with weathered bumps and fine cracks; their colour is mottled,
    // darker low down and in the cracks, with faint lichen on top, and some have a white quartz vein.
    // Everything comes from its seed; r is the size of the whole group.
    private fun makeStone(s: Thing): Look {
        val rnd = Random(s.seed)
        val r = s.r
        val turn = Math.toRadians(s.angle.toDouble()).toFloat()
        val ca = cos(turn); val sa = sin(turn)
        val pal = ROCKS[rnd.nextInt(ROCKS.size)]
        val roll = rnd.nextFloat()
        val mainKind = if (roll < 0.4f) CRAG else if (roll < 0.75f) BOULDER else SLAB
        val companions = intArrayOf(0, 0, 1, 1, 2)[rnd.nextInt(5)]
        val rocks = ArrayList<Rock>()
        rocks.add(makeRock(rnd, 0f, 0f, r * (if (mainKind == CRAG) 0.62f else 0.8f), mainKind))
        for (k in 0 until companions) {
            val a = rnd.nextFloat() * 6.283f
            val cr = r * (0.22f + 0.15f * rnd.nextFloat())
            val d = r * 0.75f + cr * 0.5f
            rocks.add(makeRock(rnd, d * cos(a), d * sin(a), cr, if (rnd.nextFloat() < 0.6f) PEBBLE else CRAG))
        }
        val moss = companions > 0 && rnd.nextFloat() < 0.35f
        val vein = rnd.nextFloat() < 0.35f
        val veinA = rnd.nextFloat() * 3.14f
        val veinAt = (rnd.nextFloat() - 0.5f) * r * 0.6f
        val seed = s.seed
        val look = Look(ceil(1.45f * r).toInt())
        val half = look.half
        val insideOut = FloatArray(1)   // (rockAt's second answer)
        for (gy in -half..half) for (gx in -half..half) {
            val dx = gx + 0.5f
            val dy = gy + 0.5f
            val u = dx * ca + dy * sa
            val v = -dx * sa + dy * ca
            val n = squareHash(gx, gy)
            val speck = ((n ushr 3) and 255) / 255f
            // bent a little, so faces and outlines aren't perfectly flat and straight
            val wu = u + 0.22f * r * (noise(u / r * 2.2f, v / r * 2.2f, seed + 11) - 0.5f)
            val wv = v + 0.22f * r * (noise(u / r * 2.2f + 7, v / r * 2.2f, seed + 13) - 0.5f)
            var bestH = -1f
            var bestC = 0
            var edge = 0f
            var near = -Float.MAX_VALUE              // how far inside the nearest stone (for the moss)
            for (k in rocks.indices) {
                val rk = rocks[k]
                val h = rockAt(rk, wu - rk.ox, wv - rk.oy, insideOut)
                val inside = insideOut[0]
                near = max(near, inside / rk.r * r)
                if (inside <= 0 || h <= 0) continue
                // weathering: bumps (in dp, × RES), and fine cracks: thin grooves
                val bump = noise(u / RES * 0.25f, v / RES * 0.25f, seed) - 0.5f + 0.5f * (noise(u / RES * 0.7f, v / RES * 0.7f, seed + 5) - 0.5f)
                val crackLine = abs(noise(u / RES * 0.18f + 3, v / RES * 0.18f, seed + 9) - 0.5f)
                val crack = 0.6f * max(0f, 1 - crackLine / 0.025f) * min(1f, h / (2 * RES))
                val height = h + 0.9f * RES * bump * min(1f, inside / RES) - crack * 0.8f * RES
                // how far in from the outline: the rounded edges drop the stone to the sand a little inside its
                // faces' outline, so this is measured from its height there (or the outline came out stepped)
                edge = max(edge, min(inside, max(0f, height) / rk.steepest))
                if (height <= bestH) continue
                // colour: mottled, darker low down and in the cracks, faint lichen on top, maybe a quartz vein
                val m = noise(u / RES * 0.12f, v / RES * 0.12f, seed + 3)
                var c = blend(pal[1], pal[0], min(1f, m * 1.6f))
                c = blend(c, pal[2], max(0f, (m - 0.62f) * 2.2f))
                c = tone(c, (0.9f + 0.15f * speck) * (1 - 0.35f * crack) * (0.82f + 0.18f * min(1f, h / max(1f, rk.top))))
                if (vein && k == 0) {
                    val dv = abs(u * cos(veinA) + v * sin(veinA) - veinAt - 0.12f * r * sin(u / r * 3))
                    if (dv < 0.5f * RES) c = blend(c, QUARTZ, 0.75f * (1 - dv / (0.5f * RES)))
                }
                val lichen = noise(u / RES * 0.5f, v / RES * 0.5f, seed + 7)
                if (lichen > 0.74f && h > 0.5f * rk.top) c = blend(c, LICHEN, min(0.35f, (lichen - 0.74f) * 3))
                bestH = height
                bestC = c
            }
            var soft = 0f
            if (moss) {
                // a patchy bed of moss around the group, wider in places, thinning out into the sand over its
                // last 3 dp (its edge is soft, see objSoft)
                val bed = 0.45f * r * (0.25f + 1.1f * noise(u / RES * 0.12f, v / RES * 0.12f, seed + 1))
                val mm = -near / bed
                if (mm < 1f) {
                    val fade = smoothStep((1 - mm) * bed / (3f * RES))
                    // (where it has faded to almost nothing it's left as sand: placed, it would look like sand but
                    // the rake couldn't touch it, leaving a smooth ring around the moss)
                    if (bestH < 0 && fade >= 0.1f) {
                        val t = noise(u / RES * 0.6f, v / RES * 0.6f, seed + 2)
                        bestH = 0.6f * RES * (0.5f + t) * fade
                        bestC = blend(ROCK_MOSS_DARK, ROCK_MOSS_LIGHT, t * 0.8f + 0.2f * speck)
                        edge = 1.5f * fade
                        soft = 1f
                    } else edge = max(edge, (1 - mm) * bed)   // (under a stone: so no sand shows between them)
                }
            }
            if (bestH < 0) continue
            val i = (gy + half) * look.size + gx + half
            look.height[i] = bestH; look.colour[i] = bestC; look.edge[i] = edge; look.soft[i] = soft
        }
        smoothCast(look)
        return look
    }

    // A stone lantern's roof, seen from above (drawn from photos of Kasuga-style lanterns at Rikugien Garden and
    // Kasuga Taisha): six-sided, its sides bowing in a little between corners that reach out and sweep up, rising
    // in a hollow curve to the top, with a soft ridge from the top down to each corner and a thin lip along the
    // eaves; on top, a collar of lotus petals and an onion-shaped jewel. The stone is weathered: mottled, with dark
    // rain streaks running down the slopes, pale lichen, and moss low in the hollows between the ridges.
    // Every lantern is the same (only turned differently), so its weathering has a seed of its own.
    private fun makeLantern(s: Thing): Look {
        val r = s.r
        val turn = Math.toRadians(s.angle.toDouble()).toFloat()
        val ca = cos(turn); val sa = sin(turn)
        val seed = 4242
        val sixth = (PI / 3).toFloat()
        val eave = 1.55f * r                     // how high the eaves stand, and how much higher the top is
        val rise = 0.42f * r
        val look = Look(ceil(1.3f * r).toInt())
        val half = look.half
        for (gy in -half..half) for (gx in -half..half) {
            val dx = gx + 0.5f
            val dy = gy + 0.5f
            val n = squareHash(gx, gy)
            val speck = ((n ushr 3) and 255) / 255f
            val d = hypot(dx, dy)
            val u = dx * ca + dy * sa              // (turned with the lantern, so its weathering turns too)
            val v = -dx * sa + dy * ca
            val a = atan2(v, u)
            // the angle to the nearest corner (0 at a corner, a twelfth of a turn mid-side), and how far out
            // towards the eaves (1 = the eave), with the corners reaching further out
            var w = (a / sixth) % 1f
            if (w < 0) w += 1f
            val off = min(w, 1 - w) * sixth
            val toCorner = 1 - off / (sixth / 2)   // 1 towards a corner, 0 mid-side
            val hd = d * cos(off - sixth / 2) / (r * 0.866f) / (1 + 0.14f * toCorner * toCorner * toCorner)
            var bestH = -1f
            var bestC = 0
            var edge = 0f
            if (hd < 1f) {
                // the roof: a hollow sweep up from the eaves, a lip along them, the corners swept up into a curl
                var h = eave + rise * (1 - hd).pow(1.8f)
                if (hd > 0.93f) h += 0.025f * r
                val c4 = toCorner * toCorner * toCorner * toCorner
                h += 0.14f * r * c4 * smoothStep((hd - 0.7f) / 0.3f)
                h += 0.05f * r * c4 * toCorner * toCorner * smoothStep((hd - 0.88f) / 0.09f) * (0.5f + 0.5f * sin(hd * 60))
                // a ridge from the top down to each corner
                val ridgeIn = max(0f, 1 - off / 0.2f)
                val ridge = ridgeIn * ridgeIn * min(1f, hd * 3) * (1 - 0.5f * hd)
                h += 0.035f * r * ridge
                // weathering: mottling, rain streaks down the slopes, lichen, moss low in the hollows
                val m = noise(u / RES * 0.15f, v / RES * 0.15f, seed + 3)
                var c = blend(LANTERN_DARK, LANTERN_GREY, min(1f, 0.4f + m))
                c = blend(c, LANTERN_LIGHT, max(0f, m - 0.6f) * 1.5f)
                val streak = noise(cos(a) * 4 + 10, sin(a) * 4 + hd * 1.2f, seed)
                c = tone(c, (1 - 0.5f * max(0f, streak - 0.45f) * hd) * (0.92f + 0.12f * speck) * (1 + 0.08f * ridge))
                val lichen = noise(u / RES * 0.45f, v / RES * 0.45f, seed + 7)
                if (lichen > 0.72f) c = blend(c, LICHEN, min(0.4f, (lichen - 0.72f) * 3))
                val moss = noise(u / RES * 0.3f, v / RES * 0.3f, seed + 9)
                if (hd > 0.7f && off > 0.3f && moss > 0.6f) c = blend(c, LANTERN_MOSS, min(0.7f, (moss - 0.6f) * 12 * (hd - 0.7f)))
                bestH = h; bestC = c
                edge = (1 - hd) * r * 0.866f
            }
            // the collar of lotus petals, and the jewel on it
            val collar = d / (0.3f * r)
            if (collar < 1f) {
                val petal = 0.5f + 0.5f * cos(8 * a)
                val h = eave + rise + 0.05f * r * (1 - collar) + 0.08f * r * petal * (1 - collar)
                if (h > bestH) { bestH = h; bestC = tone(LANTERN_GREY, 0.88f + 0.12f * speck + 0.08f * petal) }
            }
            val jewel = d / (0.15f * r)
            if (jewel < 1f) {
                val h = eave + rise + 0.08f * r + 0.35f * r * (1 - jewel.pow(1.6f)).pow(0.8f)
                if (h > bestH) { bestH = h; bestC = tone(blend(LANTERN_GREY, LANTERN_LIGHT, 0.2f), 0.92f + 0.1f * speck) }
            }
            if (bestH < 0) continue
            val i = (gy + half) * look.size + gx + half
            look.height[i] = bestH; look.colour[i] = bestC; look.edge[i] = edge
        }
        smoothCast(look)
        return look
    }
}

// ---------------- Small helpers, shared with the garden's own drawing ----------------
// (plain arithmetic, the same as Android's Color and ColorUtils give, so the looks can be worked out on the PC too)

// A fixed jumble of bits for each square (the same every time, so the sand doesn't flicker): the colour
// wobble, the glints and the roughness of raked sand all come from it
internal fun squareHash(x: Int, y: Int): Int {
    val n = x * 374761393 + y * 668265263
    return (n xor (n ushr 13)) * 1274126177
}

// A colour made lighter or darker (f = 1 leaves it as it is)
internal fun tone(c: Int, f: Float) = rgb(
    min(255f, ((c shr 16) and 0xFF) * f).toInt(), min(255f, ((c shr 8) and 0xFF) * f).toInt(), min(255f, (c and 0xFF) * f).toInt())

// 0 below 0, 1 above 1, and an S-shaped rise between
internal fun smoothStep(v: Float): Float {
    val c = v.coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

// A colour from its red, green and blue (0-255 each), fully opaque (as Android's Color.rgb)
internal fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

// Two colours mixed, `t` of the way from a to b (as Android's ColorUtils.blendARGB, step for step)
internal fun blend(a: Int, b: Int, t: Float): Int {
    val inv = 1 - t
    val al = ((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t
    val r = ((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * t
    val g = ((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * t
    val bl = (a and 0xFF) * inv + (b and 0xFF) * t
    return (al.toInt() shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
}

// A colour written "#RRGGBB", fully opaque (as Android's toColorInt)
internal fun colour(hex: String) = (0xFF000000L or hex.substring(1).toLong(16)).toInt()
