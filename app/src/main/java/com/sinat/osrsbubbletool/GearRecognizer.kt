package com.sinat.osrsbubbletool

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Process
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Recognizes the gear in a picture of the equipment window, using the item icons
// bundled with the app (assets/dps). Slow work: call it from a background thread.
class GearRecognizer(private val context: Context) {

    companion object {
        const val GRID = 32                          // a slot's inside is 32 x 32 game pixels
        private const val UNEXPLAINED_WEIGHT = 40f   // penalty for parts of the item an icon doesn't cover
        private const val OVERCLAIM_WEIGHT = 30f     // penalty for icon pixels sitting on plain slot stone
        private const val EMPTY_ITEMNESS = 12f       // less "item" than this in a slot means it's empty
        // A slot this close to its empty-slot outline (from assets/dps/empty_slots.png) is empty.
        // Measured: empty slots score 0 (up to 5.5 if blurred), filled slots 12.8 or more.
        private const val EMPTY_MATCH = 8f
        private const val MAX_CHOICES = 5            // how many guesses to keep per slot
        private const val MIN_LAYOUT_SCORE = 0.15f   // how clearly the slot outlines must be found

        val SLOT_ORDER = listOf("head", "cape", "neck", "ammo", "weapon", "body",
            "shield", "legs", "hands", "feet", "ring")

        val SLOT_LABELS = mapOf(
            "head" to "Head", "cape" to "Cape", "neck" to "Neck", "ammo" to "Ammo",
            "weapon" to "Weapon", "body" to "Body", "shield" to "Shield", "legs" to "Legs",
            "hands" to "Hands", "feet" to "Feet", "ring" to "Ring"
        )

        // Each slot's position relative to the head slot, in pixels when a slot is 72 pixels wide
        // (a "slot" here is the square inside its dark outline)
        private val OFFSETS: Map<String, Pair<Int, Int>> = mapOf(
            "head" to (0 to 0),
            "cape" to (-86 to 83), "neck" to (0 to 83), "ammo" to (86 to 83),
            "weapon" to (-118 to 165), "body" to (0 to 165), "shield" to (119 to 165),
            "legs" to (0 to 249),
            "hands" to (-118 to 333), "feet" to (0 to 333), "ring" to (119 to 333)
        )
        private const val REF_SIZE = 72f

        // Where the head slot normally sits inside the equipment window, as fractions of the
        // window's width and height, and a slot's size as a fraction of the window's width.
        // The search looks around these, so the area you set only needs to be roughly right.
        private const val HEAD_X = 0.411f
        private const val HEAD_Y = 0.044f
        private const val SLOT_SIZE = 0.170f
        private const val SEARCH = 0.12f     // search up to 12% of the window width around it
        private const val SIZE_RANGE = 0.15f // and slot sizes 15% smaller or bigger

        private const val DARK = 56          // slot outlines are darker than this

        // Look-alike items (same shape, different colour) within this much of the best guess
        // get a closer comparison, and are flagged for checking if they end up this close
        private const val LOOKALIKE_MARGIN = 12f
        private const val LOOKALIKE_CLOSE = 6f

        // Game brightness changes how item icons are drawn (not the slot stone): the game's
        // colour = DIMMING x 255 x (wiki colour / 255) ^ gamma. Measured from screenshots at
        // minimum, middle and maximum brightness: gamma 0.7 (brightest) to about 1.6 (darkest).
        private val GAMMAS = (5..20).map { it / 10f }   // 0.5 to 2.0, the values tried
        private const val DIMMING = 0.94f
        private const val SHORTLIST = 30               // guesses per slot used for the brightness test
        private const val RECHECK = 60                 // guesses per slot rematched after it

        // Speed: icons are only tried within this many game pixels of where the item's
        // middle is in the slot, and the work is shared between this many processor cores
        private const val WINDOW = 3

        // Some items are drawn bigger in the game than their wiki icon (for example the
        // recoloured crystal armour, about 25% bigger). When the item on screen is clearly
        // bigger than an icon, an enlarged copy of the icon is tried too.
        private const val ENLARGE_FROM = 1.08f
        private const val ENLARGE_MAX = 1.35f
        private const val MAX_THREADS = 4   // more rarely helps: phones mix fast and slow cores
    }

    class Item(val id: Int, val name: String, val version: String, val slot: String, val iconFile: String) {
        val label: String get() = if (version.isEmpty()) name else "$name ($version)"
    }

    // One guess for a slot. item == null means "nothing equipped".
    class Choice(val item: Item?, val cost: Float)

    // slotRects: where each slot was found (shown to you so you can check it)
    // notes: slots where two look-alike items were close, with the scores, so you can check them
    class Result(
        val slots: Map<String, List<Choice>>,
        val layoutScore: Float,
        val slotRects: Map<String, Rect>,
        val notes: Map<String, String> = emptyMap(),
        val gamma: Float = 1f              // the game brightness the app measured
    )

    private class Icon(val w: Int, val h: Int, val rgb: ByteArray, val mask: BooleanArray) {
        val cy: Float   // the middle of the icon's visible pixels
        val cx: Float
        init {
            var sy = 0f; var sx = 0f; var n = 0
            for (i in mask.indices) if (mask[i]) { sy += i / w; sx += i % w; n++ }
            cy = if (n > 0) sy / n else h / 2f
            cx = if (n > 0) sx / n else w / 2f
        }
        fun c(i: Int): Int = rgb[i].toInt() and 0xFF   // colour value 0-255
    }

    // Where an icon fits best in a slot
    private class Fit(val cost: Float, val oy: Int, val ox: Int, val enlarged: Boolean = false)

    // A slot shrunk to game pixels
    private class Cells(val r: FloatArray, val g: FloatArray, val b: FloatArray, val ok: BooleanArray)

    // Everything needed to match items against one slot
    private class SlotData(
        val cells: Cells,
        val itemness: FloatArray,
        val total: Float,
        val stone: BooleanArray,
        val emptyCost: Float?          // difference from the empty-slot outline, if known
    ) {
        val empty: Boolean get() = emptyCost?.let { it < EMPTY_MATCH } ?: (total < EMPTY_ITEMNESS)
        val cy: Float   // the middle of what looks like item in the slot
        val cx: Float
        init {
            var sy = 0f; var sx = 0f; var n = 0f
            for (i in itemness.indices) {
                val v = itemness[i]
                if (v > 0f) { sy += (i / GRID) * v; sx += (i % GRID) * v; n += v }
            }
            cy = if (n > 0f) sy / n else GRID / 2f
            cx = if (n > 0f) sx / n else GRID / 2f
        }

        // how big the item is on screen, in game pixels
        val boxH: Int
        val boxW: Int
        init {
            var y0 = GRID; var y1 = -1; var x0 = GRID; var x1 = -1
            for (i in itemness.indices) if (itemness[i] > 0.5f) {
                val y = i / GRID; val x = i % GRID
                y0 = min(y0, y); y1 = max(y1, y); x0 = min(x0, x); x1 = max(x1, x)
            }
            boxH = if (y1 >= 0) y1 - y0 + 1 else 0
            boxW = if (x1 >= 0) x1 - x0 + 1 else 0
        }
    }

    private var itemsBySlot: Map<String, List<Item>>? = null
    private val iconCache = HashMap<String, Icon?>()

    // ---------------- Bundled data ----------------

    private fun items(): Map<String, List<Item>> {
        itemsBySlot?.let { return it }
        val text = context.assets.open("dps/equipment.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val list = ArrayList<Item>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(Item(o.getInt("id"), o.getString("name"), o.optString("version", ""),
                o.getString("slot"), o.getString("icon")))
        }
        // Items whose versions are just numbers are charges or degradation (Amulet of glory 1-6,
        // Barrows 0-100), which don't change damage. Keep only the highest number of each.
        val chargeFamilies = list.filter { it.version.toIntOrNull() != null }
            .groupBy { it.slot to it.name }
        val keep = chargeFamilies.values.map { family -> family.maxByOrNull { it.version.toInt() }!! }.toSet()
        val trimmed = list.filter { it.version.toIntOrNull() == null || it in keep }

        val grouped = trimmed.groupBy { it.slot }
        itemsBySlot = grouped
        return grouped
    }

    fun iconBitmap(item: Item): Bitmap? = try {
        context.assets.open("dps/icons/${item.iconFile}").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    }

    // Loads the item list, every icon and the empty-slot outlines. Called when the DPS tool
    // opens, so the first import doesn't wait for it. Safe to call more than once.
    @Synchronized
    fun warmUp() {
        for (list in items().values) for (item in list) loadIcon(item.iconFile)
        emptyOutlines()
        warmedUp = true
    }

    // What each slot looks like with nothing in it, at game pixel size (slot to colours)
    private var emptyOutlineCache: Map<String, IntArray>? = null

    @Synchronized
    private fun emptyOutlines(): Map<String, IntArray> {
        emptyOutlineCache?.let { return it }
        val outlines = HashMap<String, IntArray>()
        try {
            val strip = context.assets.open("dps/empty_slots.png").use { BitmapFactory.decodeStream(it) }
            if (strip != null && strip.width >= GRID * SLOT_ORDER.size && strip.height >= GRID) {
                for ((n, slot) in SLOT_ORDER.withIndex()) {
                    val px = IntArray(GRID * GRID)
                    strip.getPixels(px, 0, GRID, n * GRID, 0, GRID, GRID)
                    outlines[slot] = px
                }
            }
        } catch (_: Exception) {
            // no outlines bundled: fall back to judging emptiness by how much item there is
        }
        emptyOutlineCache = outlines
        return outlines
    }

    // How different a slot is from its empty outline (lower = more likely empty), allowing a
    // one game pixel shift. Null if the outline isn't available.
    private fun emptyCost(cells: Cells, slot: String): Float? {
        val outline = emptyOutlines()[slot] ?: return null
        var best = Float.MAX_VALUE
        for (dy in -1..1) for (dx in -1..1) {
            var diff = 0f
            var n = 0
            for (y in 0 until GRID) {
                val oy = y - dy
                if (oy !in 0 until GRID) continue
                for (x in 0 until GRID) {
                    val ox = x - dx
                    if (ox !in 0 until GRID) continue
                    val i = y * GRID + x
                    if (!cells.ok[i]) continue
                    val c = outline[oy * GRID + ox]
                    diff += abs(cells.r[i] - ((c shr 16) and 0xFF)) +
                        abs(cells.g[i] - ((c shr 8) and 0xFF)) +
                        abs(cells.b[i] - (c and 0xFF))
                    n++
                }
            }
            if (n > 0) best = min(best, diff / (3 * n))
        }
        return if (best < Float.MAX_VALUE) best else null
    }

    @Volatile private var warmedUp = false

    // An icon trimmed to its visible pixels (read-only once warmUp has run)
    private fun icon(file: String): Icon? = iconCache[file]

    private fun loadIcon(file: String): Icon? = iconCache.getOrPut(file) {
        val bmp: Bitmap? = try {
            context.assets.open("dps/icons/$file").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
        if (bmp == null) return@getOrPut null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) > 128) {
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
        }
        if (x1 < 0) return@getOrPut null
        val iw = x1 - x0 + 1
        val ih = y1 - y0 + 1
        val rgb = ByteArray(iw * ih * 3)
        val mask = BooleanArray(iw * ih)
        for (y in 0 until ih) for (x in 0 until iw) {
            val c = px[(y + y0) * w + (x + x0)]
            val i = y * iw + x
            mask[i] = (c ushr 24) > 128
            rgb[i * 3] = ((c shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (c and 0xFF).toByte()
        }
        Icon(iw, ih, rgb, mask)
    }

    // ---------------- Whole equipment window ----------------

    // window: a picture of the equipment window (the area you set)
    // preferred: the item you picked for a slot last time (slot to item id). When that item is
    // one of the close look-alikes again, it's put first.
    fun recognize(window: Bitmap, preferred: Map<String, Int> = emptyMap()): Result? {
        val w = window.width
        val h = window.height
        if (w < 40 || h < 60) return null
        val px = IntArray(w * h)
        window.getPixels(px, 0, w, 0, 0, w, h)
        val (slots, score) = findSlots(px, w, h)
        if (score < MIN_LAYOUT_SCORE) return Result(emptyMap(), score, slots)

        // Read every slot once
        val data = LinkedHashMap<String, SlotData?>()
        for (slot in SLOT_ORDER) {
            val rect = slots[slot] ?: continue
            data[slot] = prepareSlot(px, w, h, rect, slot)
        }
        val occupied = data.filterValues { it != null && !it.empty }.mapValues { it.value!! }

        if (!warmedUp) warmUp()
        val pool = Executors.newFixedThreadPool(threadCount()) { task ->
            // background priority: the game always gets the processor first
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }
        }
        try {
            // Pass 1: match every item with the wiki's colours as they are; keep the best per slot
            val shortlist = occupied.mapValues { (slot, d) ->
                pool.submit(Callable {
                    // items that share a picture only need scoring once
                    val costByIcon = HashMap<String, Float>()
                    items()[slot].orEmpty()
                        .mapNotNull { item ->
                            val cost = costByIcon.getOrPut(item.iconFile) {
                                icon(item.iconFile)?.let { fitAny(it, d).cost } ?: Float.MAX_VALUE
                            }
                            if (cost < Float.MAX_VALUE) item to cost else null
                        }
                        .sortedBy { it.second }
                        .take(RECHECK)
                        .map { it.first }
                })
            }.mapValues { it.value.get() }

            // Pass 2: find the game brightness at which the best guesses fit best overall
            val totals = GAMMAS.map { g ->
                pool.submit(Callable {
                    var total = 0f
                    for ((slot, d) in occupied) {
                        var best = Float.MAX_VALUE
                        for (item in shortlist[slot].orEmpty().take(SHORTLIST)) {
                            val base = icon(item.iconFile) ?: continue
                            best = min(best, fitAny(shade(base, g), d).cost)
                        }
                        if (best < Float.MAX_VALUE) total += best
                    }
                    total
                })
            }.map { it.get() }
            val gamma = GAMMAS[totals.indices.minByOrNull { totals[it] } ?: GAMMAS.indexOf(1f)]

            // Pass 3: rematch the best guesses, drawn at that brightness
            val tasks = LinkedHashMap<String, Future<Pair<List<Choice>, String?>>>()
            for (slot in SLOT_ORDER) {
                val d = data[slot] ?: continue
                if (d.empty) continue
                tasks[slot] = pool.submit(Callable {
                    val slotNotes = ArrayList<String>()
                    val choices = rankSlot(slot, d, gamma, shortlist[slot].orEmpty(), preferred[slot]) {
                        slotNotes.add(it)
                    }
                    choices to (if (slotNotes.isEmpty()) null else slotNotes.joinToString("\n"))
                })
            }
            val results = LinkedHashMap<String, List<Choice>>()
            val notes = HashMap<String, String>()
            for (slot in SLOT_ORDER) {
                val d = data[slot] ?: continue
                if (d.empty) { results[slot] = listOf(Choice(null, 0f)); continue }
                val (choices, note) = tasks[slot]!!.get()
                results[slot] = choices
                if (note != null) notes[slot] = note
            }
            return Result(results, score, slots, notes, gamma)
        } finally {
            pool.shutdown()
            synchronized(shadedCache) { shadedCache.clear() }
        }
    }

    // Half the phone's cores (at most 4), or just 1 on phones Android marks as low on memory
    private fun threadCount(): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        if (activityManager?.isLowRamDevice == true) return 1
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(1, MAX_THREADS)
    }

    // An icon recoloured to how the game draws it at a given brightness
    private val shadedCache = HashMap<String, Icon?>()

    private fun shadedIcon(file: String, gamma: Float): Icon? {
        val base = icon(file) ?: return null
        synchronized(shadedCache) {
            return shadedCache.getOrPut("$file@$gamma") { shade(base, gamma) }
        }
    }

    private val lutCache = HashMap<Float, IntArray>()

    private fun shade(base: Icon, gamma: Float): Icon {
        val lut = synchronized(lutCache) {
            lutCache.getOrPut(gamma) {
                IntArray(256) { v ->
                    (DIMMING * 255f * Math.pow(v / 255.0, gamma.toDouble()).toFloat()).roundToInt().coerceIn(0, 255)
                }
            }
        }
        return Icon(base.w, base.h, ByteArray(base.rgb.size) { lut[base.c(it)].toByte() }, base.mask)
    }

    // ---------------- Finding the 11 slots by their dark outlines ----------------

    private class Integral(mask: BooleanArray, val w: Int, val h: Int) {
        val sums = IntArray((w + 1) * (h + 1))
        init {
            for (y in 0 until h) {
                var row = 0
                for (x in 0 until w) {
                    if (mask[y * w + x]) row++
                    sums[(y + 1) * (w + 1) + (x + 1)] = sums[y * (w + 1) + (x + 1)] + row
                }
            }
        }
        // dark pixels and total pixels in a rectangle (clipped to the picture)
        fun count(ax0: Int, ay0: Int, ax1: Int, ay1: Int): Pair<Int, Int> {
            val x0 = ax0.coerceIn(0, w); val x1 = ax1.coerceIn(0, w)
            val y0 = ay0.coerceIn(0, h); val y1 = ay1.coerceIn(0, h)
            if (x1 <= x0 || y1 <= y0) return 0 to 0
            val s = sums[y1 * (w + 1) + x1] - sums[y0 * (w + 1) + x1] -
                sums[y1 * (w + 1) + x0] + sums[y0 * (w + 1) + x0]
            return s to (x1 - x0) * (y1 - y0)
        }
    }

    // How well the slot squares line up with dark outlines at slot size s,
    // with the head slot's top-left corner at (hx, hy)
    private fun layoutScore(g: Integral, s: Int, hx: Int, hy: Int): Float {
        val t = max(2, (s * 3 / REF_SIZE).roundToInt())   // outline thickness
        val b = max(2, (s * 5 / REF_SIZE).roundToInt())   // light bevel just inside
        var total = 0f
        for ((ox, oy) in OFFSETS.values) {
            val x = hx + (ox * s / REF_SIZE).roundToInt()
            val y = hy + (oy * s / REF_SIZE).roundToInt()
            val (c, ca) = g.count(x - t, y - t, x + s + t, y + s + t)
            val (d, da) = g.count(x, y, x + s, y + s)
            val (e, ea) = g.count(x + b, y + b, x + s - b, y + s - b)
            val outline = (c - d).toFloat() / max(ca - da, 1)   // should be dark
            val bevel = (d - e).toFloat() / max(da - ea, 1)     // should not be dark
            total += outline - 0.5f * bevel
        }
        return total / OFFSETS.size
    }

    private fun findSlots(px: IntArray, w: Int, h: Int): Pair<Map<String, Rect>, Float> {
        val dark = BooleanArray(w * h) {
            val c = px[it]
            val lum = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
            lum <= DARK
        }
        val g = Integral(dark, w, h)

        val s0 = SLOT_SIZE * w
        val x0 = HEAD_X * w
        val y0 = HEAD_Y * h
        val range = (SEARCH * w).toInt()

        var bestScore = -9f
        var bestS = s0.roundToInt()
        var bestX = x0.roundToInt()
        var bestY = y0.roundToInt()
        for (s in (s0 * (1 - SIZE_RANGE)).toInt()..(s0 * (1 + SIZE_RANGE)).toInt()) {
            for (y in (y0.toInt() - range)..(y0.toInt() + range)) {
                for (x in (x0.toInt() - range)..(x0.toInt() + range)) {
                    val score = layoutScore(g, s, x, y)
                    if (score > bestScore) {
                        bestScore = score; bestS = s; bestX = x; bestY = y
                    }
                }
            }
        }

        val rects = OFFSETS.mapValues { (_, off) ->
            val x = bestX + (off.first * bestS / REF_SIZE).roundToInt()
            val y = bestY + (off.second * bestS / REF_SIZE).roundToInt()
            Rect(x, y, x + bestS, y + bestS)
        }
        return rects to bestScore
    }

    // ---------------- Recognizing one slot ----------------

    // Reads one slot: shrinks it to game pixels and marks what looks like item or stone
    private fun prepareSlot(px: IntArray, stride: Int, height: Int, rect: Rect, slot: String): SlotData? {
        // the inside of the slot, without its light bevel
        val inset = max(1, (rect.width() * 4 / REF_SIZE).roundToInt())
        val x0 = (rect.left + inset).coerceIn(0, stride - 2)
        val y0 = (rect.top + inset).coerceIn(0, height - 2)
        val inside = min(rect.width() - 2 * inset, min(stride - x0, height - y0))
        if (inside < GRID) return null

        // Each game pixel is drawn as a block of screen pixels (2 x 2 on most phones). When the
        // block size is a whole number, use exact blocks and try every alignment; otherwise
        // blend with blocks as close as possible.
        val ratio = inside / GRID.toFloat()
        val k = ratio.roundToInt()
        val exact = k >= 1 && abs(ratio - k) < 0.15f
        val size = if (exact) k * GRID else inside - 1       // screen pixels shrunk into the grid
        val shifts = if (exact) k else 2                    // alignments to try in each direction
        val span = size + shifts                             // screen pixels read in each direction

        // Read the slot once (clipped to the picture)
        val region = IntArray(span * span)
        val inPicture = BooleanArray(span * span)
        for (y in 0 until span) for (x in 0 until span) {
            val sx = x0 + x
            val sy = y0 + y
            if (sx < stride && sy < height) {
                region[y * span + x] = px[sy * stride + sx]
                inPicture[y * span + x] = true
            }
        }

        // Ignore the yellow quantity number and its dark shadow on stacks of ammo or throwing
        // weapons. Only the digits' own pixels are ignored, so the item shows between them.
        val valid = inPicture.copyOf()
        if (slot == "ammo" || slot == "weapon") {
            val yellow = BooleanArray(span * span)
            var count = 0
            for (y in 0 until (size * 0.35f).toInt()) for (x in 0 until (size * 0.7f).toInt()) {
                val c = region[y * span + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (r > 150 && g > 150 && b < 110 && r - b > 70) { yellow[y * span + x] = true; count++ }
            }
            if (count > 8) {
                val shadow = max(1, if (exact) k else 2)   // the shadow sits one game pixel down-right
                for (y in 0 until span) for (x in 0 until span) {
                    if (!yellow[y * span + x]) continue
                    // the digit pixel grown by one screen pixel, plus the same shifted to its shadow
                    for (dy in -1..1) for (dx in -1..1) {
                        for (s in intArrayOf(0, shadow)) {
                            val yy = y + dy + s
                            val xx = x + dx + s
                            if (yy in 0 until span && xx in 0 until span) valid[yy * span + xx] = false
                        }
                    }
                }
            }
        }

        // Shrink to the game's pixel size, keeping the sharpest alignment
        var cells: Cells? = null
        var bestSharpness = -1f
        for (dy in 0 until shifts) for (dx in 0 until shifts) {
            val c = shrink(region, span, size, valid, dx, dy)
            val s = sharpness(c)
            if (s > bestSharpness) { cells = c; bestSharpness = s }
        }
        val chosen = cells ?: return null
        val cr = chosen.r; val cgc = chosen.g; val cb = chosen.b; val ok = chosen.ok

        // How much each cell looks like part of an item rather than the grey stone
        val itemness = FloatArray(GRID * GRID)
        var total = 0f
        for (i in 0 until GRID * GRID) {
            if (!ok[i]) continue
            val lum = (cr[i] * 3 + cgc[i] * 6 + cb[i]) / 10
            val sat = max(cr[i], max(cgc[i], cb[i])) - min(cr[i], min(cgc[i], cb[i]))
            val v = ((sat - 15) / 30).coerceIn(0f, 1f) +
                ((45 - lum) / 20).coerceIn(0f, 1f) +
                ((lum - 130) / 30).coerceIn(0f, 1f)
            itemness[i] = v
            total += v
        }

        // Cells that look like the plain grey stone of the slot (no item there)
        val stone = BooleanArray(GRID * GRID) { i ->
            if (!ok[i]) return@BooleanArray false
            val lum = (cr[i] * 3 + cgc[i] * 6 + cb[i]) / 10
            val sat = max(cr[i], max(cgc[i], cb[i])) - min(cr[i], min(cgc[i], cb[i]))
            sat <= 14 && lum in 55f..105f
        }

        return SlotData(chosen, itemness, total, stone, emptyCost(chosen, slot))
    }

    // Scores every item that fits this slot, drawn at the measured brightness
    private fun rankSlot(
        slot: String, d: SlotData, gamma: Float, candidates: List<Item>, preferredId: Int?,
        addNote: (String) -> Unit
    ): List<Choice> {
        val fits = ArrayList<Pair<Item, Fit>>()
        for (item in candidates) {
            val base = icon(item.iconFile) ?: continue
            fits.add(item to fitAny(shade(base, gamma), d))
        }
        fits.sortBy { it.second.cost }

        // Keep the best guesses, skipping repeats of the same item name and icon
        val ranked = ArrayList<Pair<Item, Fit>>()
        val seen = HashSet<String>()
        for (f in fits) {
            if (!seen.add("${f.first.name}|${f.first.iconFile}")) continue
            ranked.add(f)
            if (ranked.size >= MAX_CHOICES + 3) break
        }

        tieBreakLookAlikes(ranked, d.cells) { shadedIcon(it, gamma) }.let { note -> if (note != null) addNote(note) }

        // If you picked a close look-alike here before, start with that one
        val preferred = preferredId?.let { id -> items()[slot]?.find { it.id == id } }
        if (preferred != null && ranked.isNotEmpty()) {
            val index = ranked.indexOfFirst {
                it.first.name == preferred.name && it.first.iconFile == preferred.iconFile
            }
            if (index > 0 && ranked[index].second.cost <= ranked[0].second.cost + LOOKALIKE_MARGIN) {
                val moved = ranked.removeAt(index)
                ranked.add(0, preferred to moved.second)
                addNote("Starting with ${preferred.label} because you picked it last time")
            } else if (index == 0) {
                ranked[0] = preferred to ranked[0].second   // the exact version you picked
            }
        }

        // An enchanted item's plain version (or a plain item's enchanted version) always comes
        // second, so switching between them is a single tap. For ammo, the enchanted version
        // comes first, since almost everyone uses enchanted bolts (unless you picked the plain
        // ones yourself last time).
        if (ranked.isNotEmpty()) {
            val top = ranked[0].first
            val partnerName = if (top.name.endsWith(" (e)")) top.name.removeSuffix(" (e)") else "${top.name} (e)"
            val partner = items()[slot]?.firstOrNull { it.name == partnerName }
            if (partner != null) {
                val index = ranked.indexOfFirst { it.first.name == partnerName }
                val entry = if (index >= 0) ranked.removeAt(index)
                    else partner to (icon(partner.iconFile)?.let { fitAny(shade(it, gamma), d) } ?: Fit(Float.MAX_VALUE, 0, 0))
                val topIsYourPick = preferred != null && top.id == preferred.id
                val enchantedFirst = slot == "ammo" && partnerName.endsWith(" (e)") && !topIsYourPick
                ranked.add(if (enchantedFirst) 0 else min(1, ranked.size), entry)
            }
        }

        val choices = ranked.take(MAX_CHOICES).map { Choice(it.first, it.second.cost) }.toMutableList()
        choices.add(Choice(null, d.emptyCost ?: 0f))
        return choices
    }


    // Averages the slot into GRID x GRID cells, starting (dx, dy) screen pixels in
    private fun shrink(region: IntArray, span: Int, size: Int, valid: BooleanArray, dx: Int, dy: Int): Cells {
        val r = FloatArray(GRID * GRID)
        val g = FloatArray(GRID * GRID)
        val b = FloatArray(GRID * GRID)
        val ok = BooleanArray(GRID * GRID)
        for (gy in 0 until GRID) {
            val ya = gy * size / GRID
            val yb = max(ya + 1, (gy + 1) * size / GRID)
            for (gx in 0 until GRID) {
                val xa = gx * size / GRID
                val xb = max(xa + 1, (gx + 1) * size / GRID)
                var sr = 0f; var sg = 0f; var sb = 0f; var n = 0; var all = 0
                for (y in ya until yb) for (x in xa until xb) {
                    all++
                    val i = (y + dy) * span + x + dx
                    if (!valid[i]) continue
                    val c = region[i]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
                }
                val i = gy * GRID + gx
                if (n * 2 >= all && n > 0) {
                    r[i] = sr / n; g[i] = sg / n; b[i] = sb / n; ok[i] = true
                }
            }
        }
        return Cells(r, g, b, ok)
    }

    // How crisp a shrunk slot is: big differences between neighbouring cells mean the
    // blocks lined up with the game's pixels instead of blending two of them
    private fun sharpness(c: Cells): Float {
        var s = 0f
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val i = y * GRID + x
            if (!c.ok[i]) continue
            if (x + 1 < GRID && c.ok[i + 1]) {
                s += abs(c.r[i] - c.r[i + 1]) + abs(c.g[i] - c.g[i + 1]) + abs(c.b[i] - c.b[i + 1])
            }
            if (y + 1 < GRID && c.ok[i + GRID]) {
                s += abs(c.r[i] - c.r[i + GRID]) + abs(c.g[i] - c.g[i + GRID]) + abs(c.b[i] - c.b[i + GRID])
            }
        }
        return s
    }

    // Items that share one shape but differ in colour (like enchanted and plain bolts) score
    // almost the same overall. Re-rank them using only the pixels where their icons differ,
    // after correcting for the game's overall tint using the pixels they share.
    // Returns a note for you to check the slot, or null
    private fun tieBreakLookAlikes(
        ranked: MutableList<Pair<Item, Fit>>, cells: Cells, iconFor: (String) -> Icon?
    ): String? {
        if (ranked.size < 2) return null
        val lead = ranked[0]
        if (lead.second.enlarged) return null   // positions are for the enlarged icon
        val leadIcon = iconFor(lead.first.iconFile) ?: return null
        val group = ranked.filter { (item, fit) ->
            val ic = iconFor(item.iconFile) ?: return@filter false
            if (ic.w != leadIcon.w || ic.h != leadIcon.h || fit.cost > lead.second.cost + LOOKALIKE_MARGIN) {
                return@filter false
            }
            var same = 0
            for (i in ic.mask.indices) if (ic.mask[i] == leadIcon.mask[i]) same++
            same >= ic.mask.size * 0.95f
        }
        if (group.size < 2) return null
        val icons = group.map { iconFor(it.first.iconFile)!! }
        val w = leadIcon.w
        val n = leadIcon.mask.size

        // pixels where the look-alikes differ, and pixels they all share
        val differs = BooleanArray(n)
        val shared = BooleanArray(n)
        for (i in 0 until n) {
            if (!leadIcon.mask[i]) continue
            var spread = 0
            for (ch in 0 until 3) {
                var lo = 255; var hi = 0
                for (ic in icons) { val v = ic.c(i * 3 + ch); lo = min(lo, v); hi = max(hi, v) }
                spread += hi - lo
            }
            if (spread > 30) {
                differs[i] = true
            } else if (spread < 6) {
                // only bright shared pixels are used to measure the tint; the dark outline
                // pixels get blurred with their surroundings and would skew it
                val lum = (leadIcon.c(i * 3) * 3 + leadIcon.c(i * 3 + 1) * 6 + leadIcon.c(i * 3 + 2)) / 10
                if (lum > 50) shared[i] = true
            }
        }

        // Same position for all of them: where the leading guess fitted
        val oy = lead.second.oy
        val ox = lead.second.ox
        fun cellAt(i: Int): Int {
            val y = i / w + oy
            val x = i % w + ox
            if (y !in 0 until GRID || x !in 0 until GRID) return -1
            val c = y * GRID + x
            return if (cells.ok[c]) c else -1
        }

        // The game's tint: screen colour divided by icon colour on the shared pixels
        val screenSum = FloatArray(3)
        val iconSum = FloatArray(3)
        for (i in 0 until n) {
            if (!shared[i]) continue
            val c = cellAt(i)
            if (c < 0) continue
            screenSum[0] += cells.r[c]; screenSum[1] += cells.g[c]; screenSum[2] += cells.b[c]
            for (ch in 0 until 3) iconSum[ch] += leadIcon.c(i * 3 + ch).toFloat()
        }
        val gain = FloatArray(3) { ch ->
            if (iconSum[ch] > 0f) (screenSum[ch] / iconSum[ch]).coerceIn(0.6f, 1.4f) else 1f
        }

        // Score each look-alike on the differing pixels only
        val scores = HashMap<Item, Float>()
        for ((k, member) in group.withIndex()) {
            val ic = icons[k]
            var diff = 0f
            var count = 0
            for (i in 0 until n) {
                if (!differs[i]) continue
                val c = cellAt(i)
                if (c < 0) continue
                diff += abs(cells.r[c] - gain[0] * ic.c(i * 3)) +
                    abs(cells.g[c] - gain[1] * ic.c(i * 3 + 1)) +
                    abs(cells.b[c] - gain[2] * ic.c(i * 3 + 2))
                count++
            }
            scores[member.first] = if (count == 0) 0f else diff / (3 * count)
        }

        // Put the look-alikes in order of their tie-break score, in the places they held
        val positions = group.map { ranked.indexOf(it) }.sorted()
        val reordered = group.sortedBy { scores[it.first] }
        for ((p, member) in positions.zip(reordered)) ranked[p] = member

        val first = reordered[0].first
        val runnerUp = reordered[1].first

        // Some items have identical pictures (for example Emerald bolts and Emerald bolts (e)
        // in the wiki's images), so the picture can't decide between them: ask you to pick
        val firstIcon = iconFor(first.iconFile) ?: return null
        val runnerIcon = iconFor(runnerUp.iconFile) ?: return null
        var differing = 0
        for (i in 0 until n) {
            if (!firstIcon.mask[i]) continue
            var d = 0
            for (ch in 0 until 3) d += abs(firstIcon.c(i * 3 + ch) - runnerIcon.c(i * 3 + ch))
            if (d > 30) differing++
        }
        if (differing < 5) {
            return "Check this one: ${first.label} and ${runnerUp.label} look identical, so pick the right one"
        }

        // If the top two are close, flag the slot so you check it
        val best = scores[first] ?: return null
        val second = scores[runnerUp] ?: return null
        if (second - best < LOOKALIKE_CLOSE) {
            return String.format(Locale.US, "Check this one: %s %.1f vs %s %.1f (lower is closer)",
                first.label, best, runnerUp.label, second)
        }
        return null
    }

    // Best fit of an icon as it is, or enlarged if the item on screen is clearly bigger than it
    private fun fitAny(ic: Icon, d: SlotData): Fit {
        val plain = bestFit(ic, d)
        if (d.boxH == 0 || d.boxW == 0) return plain
        val scale = min(d.boxH / ic.h.toFloat(), d.boxW / ic.w.toFloat())
        if (scale < ENLARGE_FROM) return plain
        val big = bestFit(enlarge(ic, min(scale, ENLARGE_MAX)), d)
        return if (big.cost < plain.cost) Fit(big.cost, big.oy, big.ox, enlarged = true) else plain
    }

    // A copy of the icon made bigger (each pixel repeated as needed)
    private fun enlarge(ic: Icon, scale: Float): Icon {
        val h = max(1, (ic.h * scale).roundToInt())
        val w = max(1, (ic.w * scale).roundToInt())
        val rgb = ByteArray(w * h * 3)
        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val sy = y * ic.h / h
            for (x in 0 until w) {
                val si = sy * ic.w + x * ic.w / w
                val i = y * w + x
                mask[i] = ic.mask[si]
                rgb[i * 3] = ic.rgb[si * 3]; rgb[i * 3 + 1] = ic.rgb[si * 3 + 1]; rgb[i * 3 + 2] = ic.rgb[si * 3 + 2]
            }
        }
        return Icon(w, h, rgb, mask)
    }

    // Slides the icon around the slot and returns the best (lowest) mismatch and where it fitted
    private fun bestFit(ic: Icon, d: SlotData): Fit {
        val cr = d.cells.r; val cg = d.cells.g; val cb = d.cells.b; val ok = d.cells.ok
        val itemness = d.itemness; val total = d.total; val stone = d.stone
        var best = Float.MAX_VALUE
        var bestY = 0
        var bestX = 0
        // Try positions that put the icon's middle near the middle of the item in the slot
        val yCentre = (d.cy - ic.cy).roundToInt()
        val xCentre = (d.cx - ic.cx).roundToInt()
        var yFrom = max(-2, yCentre - WINDOW); var yTo = min(GRID - ic.h + 2, yCentre + WINDOW)
        var xFrom = max(-2, xCentre - WINDOW); var xTo = min(GRID - ic.w + 2, xCentre + WINDOW)
        if (yFrom > yTo) { yFrom = -2; yTo = GRID - ic.h + 2 }   // fall back to every position
        if (xFrom > xTo) { xFrom = -2; xTo = GRID - ic.w + 2 }
        for (oy in yFrom..yTo) {
            for (ox in xFrom..xTo) {
                val ya = max(0, oy); val yb = min(GRID, oy + ic.h)
                val xa = max(0, ox); val xb = min(GRID, ox + ic.w)
                if (yb - ya < ic.h * 0.8f || xb - xa < ic.w * 0.8f) continue
                var diff = 0f
                var n = 0
                var covered = 0f
                var onStone = 0
                for (y in ya until yb) {
                    val iconRow = (y - oy) * ic.w
                    for (x in xa until xb) {
                        val ii = iconRow + (x - ox)
                        if (!ic.mask[ii]) continue
                        val ci = y * GRID + x
                        covered += itemness[ci]
                        if (!ok[ci]) continue
                        if (stone[ci]) onStone++
                        diff += abs(cr[ci] - ic.c(ii * 3)) +
                            abs(cg[ci] - ic.c(ii * 3 + 1)) +
                            abs(cb[ci] - ic.c(ii * 3 + 2))
                        n++
                    }
                }
                if (n == 0) continue
                val cost = diff / (3 * n) +
                    UNEXPLAINED_WEIGHT * (total - covered) / max(total, 1e-6f) +
                    OVERCLAIM_WEIGHT * onStone / n
                if (cost < best) { best = cost; bestY = oy; bestX = ox }
            }
        }
        return Fit(best, bestY, bestX)
    }
}
