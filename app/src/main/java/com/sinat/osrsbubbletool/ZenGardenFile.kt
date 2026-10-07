package com.sinat.osrsbubbletool

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// The Zen Garden's save file: a garden written out, and read back in (including gardens saved by older versions of
// the app). Sizes, positions and heights are in today's sand squares (SandGardenView.RES per dp). Where the file is
// kept, and saving it safely in the background, is up to the garden (SandGardenView.save). No Android parts, so it's
// tested on the PC (ZenGardenFileTest).
object ZenGardenFile {

    private const val VERSION = 4

    // One placed thing, as saved
    class Thing(val kind: Int, val x: Float, val y: Float, val r: Float, val aspect: Float, val angle: Float,
                val color: Int, val seed: Int, val rings: Int)

    // A whole garden: its size in squares, each square's height (row by row), what's placed, and how many fireflies
    class Garden(val width: Int, val height: Int, val heights: FloatArray, val things: List<Thing>, val fireflies: Int)

    // Writes the garden, packed small. Heights are kept in dp, one byte each (-2 to 2 dp: plenty for sand).
    fun write(garden: Garden, out: OutputStream) {
        val res = SandGardenView.RES
        val bytes = ByteArray(garden.width * garden.height) {
            ((garden.heights[it] / res).coerceIn(-2f, 2f) + 2f).times(63.5f).toInt().toByte()
        }
        DataOutputStream(GZIPOutputStream(out).buffered()).use { o ->
            o.writeInt(VERSION)
            o.writeFloat(res)   // how fine the sand is (squares per dp)
            o.writeInt(garden.width); o.writeInt(garden.height)
            o.write(bytes)
            o.writeInt(garden.things.size)
            for (s in garden.things) {
                o.writeInt(s.kind)
                o.writeFloat(s.x); o.writeFloat(s.y); o.writeFloat(s.r)
                o.writeFloat(s.aspect); o.writeFloat(s.angle); o.writeInt(s.color)
                o.writeInt(s.seed); o.writeInt(s.rings)
            }
            o.writeInt(garden.fireflies)
        }
    }

    // Reads a saved garden, or null if it isn't one this app can read. Version 1 files, from the first Zen Garden,
    // only had stones; version 3 added the fireflies; version 4 says how fine the sand is (older files are 1 square
    // per dp). A garden saved at another fineness is stretched to today's, so it looks just the same.
    fun read(input: InputStream): Garden? = try {
        DataInputStream(GZIPInputStream(input).buffered()).use { inp ->
            val version = inp.readInt()
            if (version !in 1..VERSION) return null
            val fileRes = if (version >= 4) inp.readFloat() else 1f
            val k = SandGardenView.RES / fileRes   // how much bigger everything is in today's squares
            val w = inp.readInt(); val h = inp.readInt()
            val bytes = ByteArray(w * h)
            inp.readFully(bytes)
            val things = List(inp.readInt()) {
                val kind = if (version == 1) SandGardenView.STONE else inp.readInt()
                val x = inp.readFloat() * k; val y = inp.readFloat() * k; val r = inp.readFloat() * k
                val aspect = inp.readFloat(); val angle = inp.readFloat(); val color = inp.readInt()
                val seed = if (version == 1) Random.nextInt() else inp.readInt()
                val rings = if (version == 1) 0 else inp.readInt()
                Thing(kind, x, y, r, aspect, angle, color, seed, rings)
            }
            val fireflies = if (version >= 3) inp.readInt() else 0
            // heights were saved in dp (version 4) or in the old squares, which were dp too: today's are in squares
            val saved = FloatArray(w * h) { ((bytes[it].toInt() and 0xFF) / 63.5f - 2f) * SandGardenView.RES }
            if (k == 1f) Garden(w, h, saved, things, fireflies)
            else {
                val nw = max(1, (w * k).roundToInt())
                val nh = max(1, (h * k).roundToInt())
                Garden(nw, nh, stretchGrid(saved, w, h, nw, nh), things, fireflies)
            }
        }
    } catch (e: Exception) { null }

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
