package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import kotlin.math.min

// Showing items: the item list (loaded once, in the background), item pictures, and the square slots
// Gear Loadouts draws its inventory, equipment and pouches with.

// The item list (assets/items/items.tsv), read once for the whole app the first time it's needed. Reading it
// takes a moment, so it's done in the background; get() calls back on the main thread when it's ready.
object ItemCatalog {
    private var data: ItemData? = null
    private var loading = false
    private val waiting = ArrayList<(ItemData) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    // Call from the main thread
    fun get(context: Context, ready: (ItemData) -> Unit) {
        data?.let { ready(it); return }
        waiting.add(ready)
        if (loading) return
        loading = true
        val assets = context.applicationContext.assets
        Thread {
            val read = try {
                ItemData(assets.open("items/items.tsv").bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                ItemData("")   // missing or damaged: no items rather than a crash
            }
            main.post {
                data = read
                loading = false
                waiting.toList().forEach { it(read) }
                waiting.clear()
            }
        }.start()
    }
}

// Item pictures from assets/items/icons/, and the spellbook pictures. Pictures seen recently are kept, so
// scrolling and redrawing don't read them again. They're tiny (about 36 x 32), so reading one is quick.
class ItemIcons(context: Context) {
    private val assets = context.applicationContext.assets
    private val cache = LruCache<String, Bitmap>(400)
    private val missing = HashSet<String>()

    private fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (path in missing) return null
        val b = try { assets.open(path).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        if (b == null) missing.add(path) else cache.put(path, b)
        return b
    }

    fun picture(item: ItemData.Item, amount: Int = 1): Bitmap? = item.picture(amount)?.let { load("items/icons/$it") }

    // 0-3: standard, Ancient, Lunar, Arceuus (Loadout.SPELLBOOKS)
    fun spellbook(book: Int): Bitmap? = load("items/spellbooks/$book.png")
}

// One square slot: an item's picture with its amount in the top left, as the game draws it, on a plain
// square. Empty, it's just the square (with a faint label if it has one, like "Head").
@SuppressLint("ViewConstructor")
class ItemSlotView(context: Context) : View(context) {
    var picture: Bitmap? = null
        set(value) { field = value; invalidate() }
    var amount = 1               // written on the slot when more than 1
        set(value) { field = value; invalidate() }
    var label: String? = null    // shown faintly while empty
        set(value) { field = value; invalidate() }
    var square = Palette.CELL

    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixels = Paint().apply { isFilterBitmap = false }   // sharp, blocky pixels like the game
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        box.color = square
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, w * 0.1f, w * 0.1f, box)
        val p = picture
        if (p != null) {
            // as big as fits, keeping the shape, in whole steps so every game pixel is the same size
            val fit = min(w * 0.9f / p.width, h * 0.9f / p.height)
            val scale = if (fit >= 1f) fit.toInt().toFloat() else fit
            val pw = p.width * scale
            val ph = p.height * scale
            rect.set((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
            canvas.drawBitmap(p, null, rect, pixels)
            if (amount > 1) {
                val words = Loadout.shortAmount(amount)
                text.textSize = h * 0.23f
                text.color = 0xFF000000.toInt()
                canvas.drawText(words, w * 0.06f + 1.5f, h * 0.25f + 1.5f, text)   // the game's dark shadow
                text.color = Loadout.amountColour(amount)
                canvas.drawText(words, w * 0.06f, h * 0.25f, text)
            }
        } else label?.let {
            text.textSize = h * 0.2f
            text.color = Palette.FADED
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(it, w / 2, (h + text.textSize * 0.7f) / 2, text)
            text.textAlign = Paint.Align.LEFT
        }
    }
}

// Lays its children out in a grid of equal squares, as big as fits the width up to maxCell, centred.
// A child that is null in the list given to add() leaves its square blank.
@SuppressLint("ViewConstructor")
class SlotGrid(context: Context, private val columns: Int, private val maxCell: Int, private val gap: Int) : ViewGroup(context) {
    private var cell = 0

    // Adds a row of squares; null leaves a blank square
    fun addRow(views: List<View?>) {
        for (v in views) addView(v ?: View(context))
    }

    private val rows get() = (childCount + columns - 1) / columns

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cell = min(maxCell, (width - gap * (columns - 1)) / columns).coerceAtLeast(1)
        val exact = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(exact, exact)
        setMeasuredDimension(width, rows * cell + (rows - 1).coerceAtLeast(0) * gap)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val used = columns * cell + (columns - 1) * gap
        val left = (r - l - used) / 2
        for (i in 0 until childCount) {
            val x = left + (i % columns) * (cell + gap)
            val y = (i / columns) * (cell + gap)
            getChildAt(i).layout(x, y, x + cell, y + cell)
        }
    }
}

// Two columns side by side when the window is wide enough for both (minColumn each), otherwise one under
// the other. Gear Loadouts puts the inventory on the left and the equipment on the right, as there's
// little height to spare with the phone sideways.
@SuppressLint("ViewConstructor")
class TwoColumns(context: Context, private val left: View, private val right: View,
                 private val minColumn: Int, private val gap: Int) : ViewGroup(context) {
    init {
        addView(left)
        addView(right)
    }

    private var sideBySide = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        sideBySide = width >= minColumn * 2 + gap
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (sideBySide) {
            val half = MeasureSpec.makeMeasureSpec((width - gap) / 2, MeasureSpec.EXACTLY)
            left.measure(half, any)
            right.measure(half, any)
            setMeasuredDimension(width, maxOf(left.measuredHeight, right.measuredHeight))
        } else {
            val full = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            left.measure(full, any)
            right.measure(full, any)
            setMeasuredDimension(width, left.measuredHeight + gap + right.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (sideBySide) {
            left.layout(0, 0, left.measuredWidth, left.measuredHeight)
            val x = r - l - right.measuredWidth
            right.layout(x, 0, r - l, right.measuredHeight)
        } else {
            left.layout(0, 0, left.measuredWidth, left.measuredHeight)
            val y = left.measuredHeight + gap
            right.layout(0, y, right.measuredWidth, y + right.measuredHeight)
        }
    }
}
