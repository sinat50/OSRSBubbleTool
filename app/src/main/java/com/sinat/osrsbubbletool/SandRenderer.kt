package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Half
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import java.nio.ShortBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// The Zen Garden's smooth drawing, for Android 13 and newer (older phones keep SandGardenView's own drawing).
//
// The garden still works out its sand square by square (SandGardenView.shade), but instead of turning each square
// into a colour, it hands this the values the colour comes from: how bright the sand is, which way and how high the
// surface lies, and what's placed there and how much of the square it covers. These are smooth from square to
// square, so the graphics chip can blend them smoothly between the squares ("bicubic") and work out the colour of
// every screen pixel from them: grooves, ridges and outlines come out smooth at any size, with no squares to see.
//
// The lanterns' light is worked out entirely by the graphics chip too, per screen pixel, straight from the sand's
// shape: fading with distance, the fire box's six beams and the dark spokes between them, slopes facing the flame
// catching more, and ridges and placed things casting shadows in it. So it's always exactly in step with the sand.
//
// To keep it light on battery (and off the game's toes, which shares the graphics chip): the finished sand is kept,
// cut into tiles, and so is each lantern's light; only the tiles where the sand changed are drawn again, and a
// lantern's flicker only changes how brightly its light is shown.
//
// The fireflies' light is worked out the same way, all of them in one go, drawn afresh every frame while one glows
// (they're always moving).
//
// `glow`, `steps` and `res` are the lantern light's sizes from SandGardenView (LAMP_GLOW, LAMP_STEPS, RES);
// `flyReach` how far a firefly's light reaches (in squares), `maxFlies` how many fireflies there can be, and
// `shadowDark` how much a placed thing's shadow darkens the sand at full strength (THING_SHADOW_DARK).
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SandRenderer(glow: Float, steps: FloatArray, res: Float, flyReach: Float, private val maxFlies: Int,
                   private val shadowDark: Float) {

    companion object {
        const val TILE = 64                       // a tile of kept sand, in squares

        // Catmull-Rom ("bicubic") blending of a picture of values, from its 16 nearest squares. `s` is a place in
        // squares; square (i, j) is the picture's pixel (i, j). (A function of its own for each picture, as the
        // graphics chip's language can't hand a picture to a function.)
        @Suppress("SameParameterValue")   // (kept general on purpose)
        private fun cubic(name: String, input: String) = """
            float4 $name(float2 s) {
                float2 t = s - 0.5;
                float2 i = floor(t);
                float2 f = t - i;
                float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                float2 w3 = f * f * (-0.5 + 0.5 * f);
                float4 sum = float4(0.0);
                for (int dy = 0; dy < 4; dy++) {
                    float wy = dy == 0 ? w0.y : (dy == 1 ? w1.y : (dy == 2 ? w2.y : w3.y));
                    float y = i.y + float(dy) - 0.5;
                    sum += (float4($input.eval(float2(i.x - 0.5, y))) * w0.x + float4($input.eval(float2(i.x + 0.5, y))) * w1.x +
                        float4($input.eval(float2(i.x + 1.5, y))) * w2.x + float4($input.eval(float2(i.x + 2.5, y))) * w3.x) * wy;
                }
                return sum;
            }
        """

        // Plain ("bilinear") blending from the 4 nearest squares: enough for soft things, and a quarter of the work
        private fun linear(name: String, input: String) = """
            float4 $name(float2 s) {
                float2 t = s - 0.5;
                float2 i = floor(t);
                float2 f = t - i;
                float4 a = mix(float4($input.eval(float2(i.x + 0.5, i.y + 0.5))), float4($input.eval(float2(i.x + 1.5, i.y + 0.5))), f.x);
                float4 b = mix(float4($input.eval(float2(i.x + 0.5, i.y + 1.5))), float4($input.eval(float2(i.x + 1.5, i.y + 1.5))), f.x);
                return mix(a, b, f.y);
            }
        """

        // A placed thing's outline made crisp: how much of a square it covers fades out over its last square and a
        // half, and blended between squares that fade is a few screen pixels wide, which made outlines look soft.
        // Instead the outline is where the blended cover crosses 0.4, smoothed over about one screen pixel.
        // (`cell` is one square, in pixels.)
        // Soft things (moss) keep their soft edge instead, fading into the sand: `soft` is a picture of how soft
        // each square's edge is (0 = crisp, 1 = soft), see SandGardenView.objSoft.
        private const val SHARP = """
            float sharp(float a, float2 s) {
                float g = 0.5 / cell;
                return mix(smoothstep(0.4 - g, 0.4 + g, a), clamp(a, 0.0, 1.0), linearSoft(s).a);
            }
        """

        // The colour of a placed thing at a pixel: the things picture holds colour times cover, so it's divided back out
        private const val THING_COLOUR = """
            float3 thingColour(float4 t) {
                return t.a > 0.001 ? max(t.rgb, 0.0) / t.a : float3(0.0);
            }
        """

        // The sand (and what's placed on it), one screen pixel at a time
        private val SAND = """
            uniform shader data;     // r: the sand's brightness (0-1), g: a placed thing's light, b, a: the surface's slope
            uniform shader things;   // rgb: a placed thing's colour times how much of the square it covers, a: how much
            uniform shader soft;     // r: how much a placed thing's shadow falls here, g: the same on moss, a: how soft a placed thing's edge is
            uniform float2 origin;   // where the sand starts, in pixels
            uniform float cell;      // one square, in pixels
            uniform float shadowDark; // how much a placed thing's shadow darkens the sand (less as it gets dark)
            uniform float mossDark;   // and the moss lying in it
            uniform float3 shadowSand;
            uniform float3 plainSand;
            uniform float3 sunnySand;
            ${cubic("cubicData", "data")}
            ${linear("linearThings", "things")}
            ${linear("linearSoft", "soft")}
            $SHARP
            $THING_COLOUR
            half4 main(float2 p) {
                float2 s = (p - origin) / cell;
                float4 d = cubicData(s);
                float4 t = linearThings(s);
                float4 sh = linearSoft(s);
                float v = d.r * 255.0 - sh.r * shadowDark;
                // now and then a grain glints on the sunny side of a slope (one screen pixel, always the same ones)
                float2 q = floor(p - origin);
                float n = fract(sin(dot(q, float2(12.9898, 78.233))) * 43758.5453);
                if (n > 0.996 && 2.0 * (d.b + d.a) > 0.3) v += 45.0;
                v = clamp(v, 0.0, 255.0);
                float3 c = v < 128.0 ? mix(shadowSand, plainSand, v / 128.0) : mix(plainSand, sunnySand, (v - 128.0) / 127.0);
                float cover = sharp(t.a, s);
                c = c * (1.0 - cover) + thingColour(t) * cover * d.g * (1.0 - mossDark * sh.g);
                return half4(clamp(c, 0.0, 1.0), 1.0);
            }
        """

        private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

        // A picture of values the graphics chip reads exactly as written (four half-size numbers per square)
        private fun valuePicture(w: Int, h: Int) = createBitmap(w, h, Bitmap.Config.RGBA_F16)

        private fun input(bmp: Bitmap) = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = BitmapShader.FILTER_MODE_NEAREST   // (the blending is done by the program, exactly)
        }
    }

    // One lantern's light, one screen pixel at a time (added on top of the garden): the same light as the garden's
    // own drawing works out square by square (SandGardenView.LightMath.at), all in squares.
    private val LAMP = """
        uniform shader data;     // the surface's slope (b, a)
        uniform shader things;   // placed things' colour times cover (rgb), and cover (a)
        uniform shader soft;     // how soft a placed thing's edge is (a)
        uniform shader shape;    // r: how high the surface is, g: 1 where something is placed, b: how high it stands
                                 // for blocking the light (placed things smoothed, see SandGardenView.castSolid)
        uniform float2 origin;
        uniform float cell;
        uniform float2 flame;    // the flame, in squares
        uniform float roof;      // the roof's size, from its middle to a corner
        uniform float turn;      // which way the lantern is turned, in radians
        uniform float3 plainSand;
        ${cubic("cubicData", "data")}
        ${linear("linearThings", "things")}
        ${linear("linearShape", "shape")}
        ${linear("linearSoft", "soft")}
        $SHARP
        $THING_COLOUR
        half4 main(float2 p) {
            float2 s = (p - origin) / cell;
            float2 to = flame - s;
            float rho2 = dot(to, to);
            float reach = ${glow * 7f};
            if (rho2 >= reach * reach) return half4(0.0, 0.0, 0.0, 1.0);
            float rho = sqrt(rho2);
            if (rho < roof * 0.9) return half4(0.0, 0.0, 0.0, 1.0);                 // under the roof
            float4 here = linearShape(s);
            if (rho < roof * 1.15 && here.g > 0.5) return half4(0.0, 0.0, 0.0, 1.0); // the lantern's own roof
            float h0 = here.b;   // (placed things smoothed, so their bumps don't shade each other in a speckle)
            float up = ${glow * 1.3f} - h0;                                          // from here up to the flame
            if (up <= 0.0) return half4(0.0, 0.0, 0.0, 1.0);
            float d = sqrt(rho2 + up * up);
            // slopes facing the flame catch more of its light
            float4 sl = cubicData(s);
            float facing = max(0.0, (-sl.b * to.x - sl.a * to.y + up) / (d * sqrt(sl.b * sl.b + sl.a * sl.a + 1.0)));
            // the fire box's six windows throw beams, its corner posts thin dark spokes
            float w = (atan(-to.y, -to.x) - turn) / 1.0471976;
            w = w - 6.0 * floor(w / 6.0);
            float side = fract(w);
            float window = 0.08 + 0.92 * smoothstep(0.0, 0.18, side) * smoothstep(0.0, 0.18, 1.0 - side);
            // walk towards the flame: anything standing above the line to it blocks the light
            float2 u = to / rho;
            float near = rho - roof * 1.15;      // (the lantern doesn't shade its own light)
            float block = 0.0;
            ${steps.joinToString("\n            ") { step ->
                "if ($step < near) block = max(block, (linearShape(s + u * $step).b - (h0 + up * $step / rho)) * ${1f / (res * (2.2f + 0.3f * step / res))});"
            }}
            block = smoothstep(0.0, 1.0, block);   // (as SandGardenView.lampBlock: soft edges, wider further out)
            float fade = 1.0 - rho2 / (reach * reach);
            fade *= fade;
            fade *= smoothstep(0.0, roof * 0.25, rho - roof * 0.9);   // fades in right at the roof's edge
            float light = fade * (${glow} / (d + ${glow * 0.25f})) * window * ((1.0 - block) * 2.0 * facing + 0.25);
            float k = min(light, 2.2);
            float4 t = linearThings(s);
            float cover = sharp(t.a, s);
            float3 c = plainSand * (1.0 - cover) + thingColour(t) * cover;
            return half4(min(c * float3(k, 0.68 * k, 0.36 * k), 1.0), 1.0);
        }
    """

    // The glowing fireflies' light, one screen pixel at a time (added on top of the garden): like the lanterns',
    // slopes facing a firefly catch its light and slopes facing away fall into shade, it fades with distance and
    // with height (as a firefly climbs while it glows, its pool spreads wider and softer), and it takes the colour of
    // what it falls on, in its yellow-green glow. Fireflies are small and fly high, so nothing casts a shadow in it.
    private val FLIES = """
        uniform shader data;     // the surface's slope (b, a)
        uniform shader things;   // placed things' colour times cover (rgb), and cover (a)
        uniform shader soft;     // how soft a placed thing's edge is (a)
        uniform shader shape;    // r: how high the surface is
        uniform float2 origin;
        uniform float cell;
        uniform float3 plainSand;
        uniform float4 flies[$maxFlies];   // each glowing firefly: where it is (squares), how high, how bright
        uniform float count;               // how many of them are glowing
        ${cubic("cubicData", "data")}
        ${linear("linearThings", "things")}
        ${linear("linearShape", "shape")}
        ${linear("linearSoft", "soft")}
        $SHARP
        $THING_COLOUR
        half4 main(float2 p) {
            float2 s = (p - origin) / cell;
            float reach2 = ${flyReach * flyReach};
            // (the box drawn holds every glowing firefly's light, so much of it is out of reach of them all: those
            // pixels are left dark straight away, before the slower looks at the sand below)
            bool near = false;
            for (int k = 0; k < $maxFlies; k++) {
                float2 to = flies[k].xy - s;
                if (float(k) < count && dot(to, to) < reach2) near = true;
            }
            if (!near) return half4(0.0, 0.0, 0.0, 1.0);
            float4 d = cubicData(s);
            float h0 = linearShape(s).b;
            float tilt = sqrt(d.b * d.b + d.a * d.a + 1.0);
            float light = 0.0;
            for (int k = 0; k < $maxFlies; k++) {
                float4 f = flies[k];
                float2 to = f.xy - s;
                float rho2 = dot(to, to);
                float up = f.z - h0;
                if (float(k) < count && rho2 < reach2 && up > 0.0) {
                    float d2 = rho2 + up * up;
                    float facing = max(0.0, (-d.b * to.x - d.a * to.y + up) / (sqrt(d2) * tilt));
                    float fade = 1.0 - rho2 / reach2;
                    light += (facing * 0.85 + 0.15) * (f.z * f.z / d2) * fade * fade * f.w;
                }
            }
            if (light <= 0.0) return half4(0.0, 0.0, 0.0, 1.0);
            float4 t = linearThings(s);
            float cover = sharp(t.a, s);
            float3 c = plainSand * (1.0 - cover) + thingColour(t) * cover;
            return half4(min(c * float3(0.62, 1.0, 0.78) * light, 1.0), 1.0);
        }
    """
    private var fliesShader: RuntimeShader? = null
    private val fliesPaint = Paint().apply { blendMode = BlendMode.PLUS }   // (their light is added on top)
    private var fliesHaveInputs = false          // the fireflies' program has been given the latest pictures
    private var fliesSandColour = -1

    // Draws the glowing fireflies' light over the box from (left, top) to (right, bottom) (in pixels: where it
    // reaches). `flies` holds `count` of them, four numbers each: where (in squares), how high above the sand, and
    // how bright. Returns false if the garden's own drawing should be used instead.
    fun drawFlies(canvas: Canvas, flies: FloatArray, count: Int, inset: Float, cell: Float, plainSand: Int,
                  left: Float, top: Float, right: Float, bottom: Float): Boolean {
        if (broken || !canvas.isHardwareAccelerated || gw == 0) return false
        val shader = fliesShader ?: try {
            RuntimeShader(FLIES).also { fliesShader = it; fliesPaint.shader = it; fliesHaveInputs = false }
        } catch (e: Exception) { gaveUp(e); return false }
        send(withShape = true)
        if (!fliesHaveInputs) {
            fliesHaveInputs = true
            dataInput?.let { shader.setInputBuffer("data", it) }
            thingsInput?.let { shader.setInputBuffer("things", it) }
            softInput?.let { shader.setInputBuffer("soft", it) }
            shapeInput?.let { shader.setInputBuffer("shape", it) }
        }
        if (plainSand != fliesSandColour) { fliesSandColour = plainSand; shader.setFloatUniform("plainSand", rgb(plainSand)) }
        shader.setFloatUniform("origin", inset, inset)
        shader.setFloatUniform("cell", cell)
        shader.setFloatUniform("flies", flies)
        shader.setFloatUniform("count", count.toFloat())
        canvas.drawRect(left, top, right, bottom, fliesPaint)
        return true
    }

    // how far a lantern's light can reach, in squares, and how far a ridge's shadow in it can, roughly
    private val lightReach = glow * 7f
    private val shadowReach = (16 * res).toInt()

    // The graphics chip couldn't run the programs: the garden goes back to its own drawing
    var broken = false
        private set

    private var gw = 0
    private var gh = 0
    private var data = ShortArray(0)             // per square: brightness, a placed thing's light, slope x, slope y
    private var things = ShortArray(0)           // per square: placed thing's colour × cover (r, g, b), cover
    private var shape = ShortArray(0)            // per square: the surface's height, 1 if something is placed there
    // per square: how much a placed thing's shadow falls there, the same on moss only, (unused), how soft a placed
    // thing's edge is. (The shadows are kept apart from the sand's brightness so the brightness slider can fade them
    // without the garden working out every square again: see shadowDark.)
    private var soft = ShortArray(0)
    private var dataPicture: Bitmap? = null
    private var thingsPicture: Bitmap? = null
    private var shapePicture: Bitmap? = null
    private var softPicture: Bitmap? = null
    // Which pictures changed since they were last handed to the graphics chip. Each is handed over only when it
    // changed: raking changes the sand's values but never the placed things', and the heights are only needed for
    // lantern and firefly light, so most raking frames hand over one picture instead of three.
    private var dataChanged = false
    private var thingsChanged = false
    private var shapeChanged = false
    private var softChanged = false

    private var sandShader: RuntimeShader? = null
    private var lampShader: RuntimeShader? = null
    private val sandPaint = Paint()
    private val lampPaint = Paint()
    private val addPaint = Paint().apply { blendMode = BlendMode.PLUS }   // the lanterns' light is added on top
    private var dataInput: BitmapShader? = null
    private var thingsInput: BitmapShader? = null
    private var shapeInput: BitmapShader? = null
    private var softInput: BitmapShader? = null
    private var sandHasInputs = false            // the sand program has been given the latest pictures

    // the kept sand, in tiles
    private var tilesX = 0
    private var tilesY = 0
    private var tiles = arrayOf<RenderNode>()
    private var tileStale = BooleanArray(0)
    private var drawnCell = -1f                  // the sizes the tiles were drawn at
    private var drawnInset = -1f
    private var sandColour = -1
    private var drawnShadow = -1f                // how strong placed things' shadows were drawn

    // A new grid of nw by nh squares (everything is worked out again)
    fun resize(nw: Int, nh: Int) {
        gw = nw; gh = nh
        data = ShortArray(nw * nh * 4)
        things = ShortArray(nw * nh * 4)
        shape = ShortArray(nw * nh * 4)
        soft = ShortArray(nw * nh * 4)
        softPicture = valuePicture(nw, nh)
        dataPicture = valuePicture(nw, nh)
        thingsPicture = valuePicture(nw, nh)
        shapePicture = valuePicture(nw, nh)
        dataChanged = true; thingsChanged = true; shapeChanged = true; softChanged = true
        tiles.forEach { it.discardDisplayList() }
        tilesX = (nw + TILE - 1) / TILE
        tilesY = (nh + TILE - 1) / TILE
        tiles = Array(tilesX * tilesY) { RenderNode("sand").apply { setUseCompositingLayer(true, null) } }
        tileStale = BooleanArray(tiles.size) { true }
        lamps.forEach { it.stale = true }
    }

    // One square's values (see SandGardenView.shade). `alb` is a placed thing's colour (0 = none), `cover` how
    // much of the square it covers, and `height` how high the surface is there (the thing's, or the sand's).
    // `shadow` is how much a placed thing's shadow falls on it, and `mossShadow` the same where it's moss.
    // (the values are stored as half-size numbers in a plain list of shorts, which is how the picture wants them)
    @SuppressLint("HalfFloat")
    fun putSquare(i: Int, brightness: Float, thingLight: Float, slopeX: Float, slopeY: Float, alb: Int, cover: Float,
                  height: Float, castHeight: Float, softEdge: Float, shadow: Float, mossShadow: Float) {
        val k = 4 * i
        // (the sand's own values nearly always change where it's redrawn, so they're not checked, just handed over)
        data[k] = Half.toHalf(brightness)
        data[k + 1] = Half.toHalf(thingLight)
        data[k + 2] = Half.toHalf(slopeX)
        data[k + 3] = Half.toHalf(slopeY)
        dataChanged = true
        // (bare sand, which is nearly every square raked: nothing placed, all zeros, with no working out needed)
        val thingsNew = if (alb == 0) set(things, k, 0, 0, 0, 0)
            else set(things, k, Half.toHalf(((alb shr 16) and 255) / 255f * cover), Half.toHalf(((alb shr 8) and 255) / 255f * cover),
                Half.toHalf((alb and 255) / 255f * cover), Half.toHalf(cover))
        if (thingsNew) thingsChanged = true
        if (set(soft, k, Half.toHalf(shadow), Half.toHalf(mossShadow), 0, Half.toHalf(softEdge))) softChanged = true
        if (set(shape, k, Half.toHalf(height), if (alb != 0) HALF_ONE else 0, Half.toHalf(castHeight), 0)) shapeChanged = true
    }

    // Puts one square's four values in, and says whether any of them changed
    private fun set(to: ShortArray, k: Int, a: Short, b: Short, c: Short, d: Short): Boolean {
        if (to[k] == a && to[k + 1] == b && to[k + 2] == c && to[k + 3] == d) return false
        to[k] = a; to[k + 1] = b; to[k + 2] = c; to[k + 3] = d
        return true
    }
    private val HALF_ONE = Half.toHalf(1f)

    // The squares in a box changed: the tiles they're in (and their neighbours, which blend into them) are drawn
    // again, and so is the lanterns' light there, a little further out too (a new ridge casts a shadow in it)
    fun changed(x0: Int, y0: Int, x1: Int, y1: Int) {
        stale(tileStale, x0 - 2, y0 - 2, x1 + 2, y1 + 2)
        for (lamp in lamps) {
            if (lamp.tileStale.size != tiles.size) continue
            stale(lamp.tileStale, x0 - shadowReach, y0 - shadowReach, x1 + shadowReach, y1 + shadowReach)
        }
    }

    private fun stale(marks: BooleanArray, x0: Int, y0: Int, x1: Int, y1: Int) {
        val tx0 = max(0, x0 / TILE); val tx1 = min(tilesX - 1, max(0, x1) / TILE)
        val ty0 = max(0, y0 / TILE); val ty1 = min(tilesY - 1, max(0, y1) / TILE)
        for (ty in ty0..ty1) for (tx in tx0..tx1) marks[ty * tilesX + tx] = true
    }

    // Everything drawn again (the window was closed, or the sand colour changed)
    fun redrawAll() {
        tileStale.fill(true)
        lamps.forEach { it.stale = true }
    }

    // ---- lanterns ----

    // One lantern: where its flame is, its roof's size and which way it's turned. Its light is kept in the same tiles
    // as the sand, so raking only draws again the bit of light where the sand changed.
    inner class Lamp {
        var flameX = 0f
        var flameY = 0f
        var roof = 0f
        var turn = 0f
        var tiles = arrayOfNulls<RenderNode>(0)   // its light in each tile (only those it reaches)
        var tileStale = BooleanArray(0)
        var stale = true                          // all its tiles are to be drawn again

        // The lantern was placed or moved
        fun set(x: Float, y: Float, r: Float, t: Float) {
            if (x == flameX && y == flameY && r == roof && t == turn) return
            flameX = x; flameY = y; roof = r; turn = t
            stale = true
        }
    }

    val lamps = ArrayList<Lamp>()

    // ---- drawing ----

    // Draws the sand (in a canvas already moved to the tray's corner), with placed things' shadows `shadow` strong
    // (1 = full, 0 = none, see SandGardenView.brightness). Returns false if the garden's own drawing should be used
    // instead.
    fun drawSand(canvas: Canvas, inset: Float, cell: Float, sand: IntArray, shadow: Float): Boolean {
        if (broken || !canvas.isHardwareAccelerated || gw == 0) return false
        val shader = sandShader ?: try {
            RuntimeShader(SAND).also { sandShader = it; sandPaint.shader = it }
        } catch (e: Exception) { gaveUp(e); return false }
        send(withShape = false)
        if (cell != drawnCell || inset != drawnInset || sand[1] != sandColour) {
            drawnCell = cell; drawnInset = inset; sandColour = sand[1]
            shader.setFloatUniform("origin", inset, inset)
            shader.setFloatUniform("cell", cell)
            shader.setFloatUniform("shadowSand", rgb(sand[0]))
            shader.setFloatUniform("plainSand", rgb(sand[1]))
            shader.setFloatUniform("sunnySand", rgb(sand[2]))
            redrawAll()
        }
        if (shadow != drawnShadow) {
            // (the brightness slider moved: the tiles are drawn again, but nothing is worked out again)
            drawnShadow = shadow
            shader.setFloatUniform("shadowDark", shadowDark * shadow)
            shader.setFloatUniform("mossDark", 0.4f * shadow)
            tileStale.fill(true)
        }
        if (!sandHasInputs) {
            sandHasInputs = true
            dataInput?.let { shader.setInputBuffer("data", it) }
            thingsInput?.let { shader.setInputBuffer("things", it) }
            softInput?.let { shader.setInputBuffer("soft", it) }
        }
        for (ty in 0 until tilesY) for (tx in 0 until tilesX) {
            val k = ty * tilesX + tx
            val node = tiles[k]
            val left = tileLeft(tx, inset, cell)
            val top = tileTop(ty, inset, cell)
            val right = tileLeft(tx + 1, inset, cell)
            val bottom = tileTop(ty + 1, inset, cell)
            if (tileStale[k] || !node.hasDisplayList()) {
                tileStale[k] = false
                node.setPosition(left, top, right, bottom)
                val c = node.beginRecording()
                c.translate(-left.toFloat(), -top.toFloat())
                c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), sandPaint)
                node.endRecording()
            }
            canvas.drawRenderNode(node)
        }
        return true
    }

    // a tile's edges, in pixels (shared exactly with the next tile, so there's never a seam)
    private fun tileLeft(tx: Int, inset: Float, cell: Float) = floor(inset + min(gw, tx * TILE) * cell).toInt()
    private fun tileTop(ty: Int, inset: Float, cell: Float) = floor(inset + min(gh, ty * TILE) * cell).toInt()

    // Draws lantern `lamp`'s light, `alpha` bright (the flicker): tile by tile, drawing again only the tiles that
    // changed. Returns false if the garden's own drawing should be used instead.
    fun drawLamp(canvas: Canvas, lamp: Lamp, inset: Float, cell: Float, plainSand: Int, alpha: Float): Boolean {
        if (broken || !canvas.isHardwareAccelerated) return false
        val shader = lampShader ?: try {
            RuntimeShader(LAMP).also { lampShader = it; lampPaint.shader = it }
        } catch (e: Exception) { gaveUp(e); return false }
        send(withShape = true)
        if (lamp.tiles.size != tiles.size) {
            lamp.tiles.forEach { it?.discardDisplayList() }
            lamp.tiles = arrayOfNulls(tiles.size)
            lamp.tileStale = BooleanArray(tiles.size) { true }
        }
        if (lamp.stale) { lamp.stale = false; lamp.tileStale.fill(true) }
        // the tiles its light reaches
        val tx0 = max(0, floor((lamp.flameX - lightReach) / TILE).toInt())
        val tx1 = min(tilesX - 1, floor((lamp.flameX + lightReach) / TILE).toInt())
        val ty0 = max(0, floor((lamp.flameY - lightReach) / TILE).toInt())
        val ty1 = min(tilesY - 1, floor((lamp.flameY + lightReach) / TILE).toInt())
        var ready = false                         // the program has been set up for this lantern
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val k = ty * tilesX + tx
            val node = lamp.tiles[k] ?: RenderNode("lamp").also {
                it.setUseCompositingLayer(true, addPaint)   // (its light is added)
                lamp.tiles[k] = it
            }
            if (lamp.tileStale[k] || !node.hasDisplayList()) {
                lamp.tileStale[k] = false
                if (!ready) {
                    ready = true
                    shader.setFloatUniform("origin", inset, inset)
                    shader.setFloatUniform("cell", cell)
                    shader.setFloatUniform("flame", lamp.flameX, lamp.flameY)
                    shader.setFloatUniform("roof", lamp.roof)
                    shader.setFloatUniform("turn", lamp.turn)
                    shader.setFloatUniform("plainSand", rgb(plainSand))
                    dataInput?.let { shader.setInputBuffer("data", it) }
                    thingsInput?.let { shader.setInputBuffer("things", it) }
                    softInput?.let { shader.setInputBuffer("soft", it) }
                    shapeInput?.let { shader.setInputBuffer("shape", it) }
                }
                val left = tileLeft(tx, inset, cell)
                val top = tileTop(ty, inset, cell)
                val right = tileLeft(tx + 1, inset, cell)
                val bottom = tileTop(ty + 1, inset, cell)
                node.setPosition(left, top, right, bottom)
                val c = node.beginRecording()
                c.translate(-left.toFloat(), -top.toFloat())
                c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), lampPaint)
                node.endRecording()
            }
            node.setAlpha(alpha)
            canvas.drawRenderNode(node)
        }
        return true
    }

    // Hands the changed values to the graphics chip (each picture is handed over again whole: Android only notices a
    // changed picture that way). Only the pictures that changed are handed over, and the heights only `withShape`
    // (for the lantern and firefly light: the sand itself doesn't use them).
    private fun send(withShape: Boolean) {
        if (dataChanged) dataPicture?.let { d ->
            dataChanged = false
            d.copyPixelsFromBuffer(ShortBuffer.wrap(data))
            dataInput = input(d)
            sandHasInputs = false
            fliesHaveInputs = false
        }
        if (thingsChanged) thingsPicture?.let { t ->
            thingsChanged = false
            t.copyPixelsFromBuffer(ShortBuffer.wrap(things))
            thingsInput = input(t)
            sandHasInputs = false
            fliesHaveInputs = false
        }
        if (softChanged) softPicture?.let { p ->
            softChanged = false
            p.copyPixelsFromBuffer(ShortBuffer.wrap(soft))
            softInput = input(p)
            sandHasInputs = false
            fliesHaveInputs = false
        }
        if (withShape && shapeChanged) shapePicture?.let { h ->
            shapeChanged = false
            h.copyPixelsFromBuffer(ShortBuffer.wrap(shape))
            shapeInput = input(h)
            fliesHaveInputs = false
        }
    }

    // The graphics chip couldn't run a program: noted in the phone's log (adb logcat -s ZenGarden), and the garden
    // goes back to its own drawing
    private fun gaveUp(e: Exception) {
        broken = true
        android.util.Log.w("ZenGarden", "Smooth drawing not available: ${e.message}")
    }

    // The window closed: let the graphics chip forget the kept pictures (they're drawn again when it opens)
    fun release() {
        tiles.forEach { it.discardDisplayList() }
        lamps.forEach { lamp -> lamp.tiles.forEach { it?.discardDisplayList() } }
        redrawAll()
    }
}
