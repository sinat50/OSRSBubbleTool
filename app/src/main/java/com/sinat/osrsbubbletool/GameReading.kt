package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log

// Gear Loadouts' reading of the game from a screenshot: the spellbook, an open rune pouch (LoadoutReader), the
// inventory and the equipment (InventoryReader: RuneLite's icons at each item's exact place; the equipment falls back
// to the DPS tool's GearRecognizer if that finds nothing). It loads the pictures the readers compare with and keeps
// them, and decides which readings to trust. Called from the guided scan's background thread (GuidedScan).
class GameReading(private val context: Context, private val icons: ItemIcons, private val debugBuild: Boolean) {

    companion object {
        // Read the wiki's way (stacks drawn differently by size), an inventory is trusted when 60% of its items fit
        // better than this; an item fitting worse than UNSURE_FIT is ringed after a read, to be checked (good fits 8-25)
        private const val GOOD_FIT = 40f
        private const val UNSURE_FIT = 30f
        // Read with RuneLite's icons (their fits are smaller: the owner's items fit 8-20): an inventory's middle fit
        // must be this or better (other tabs' and the inventory dimmed behind a window are 20 or more), and an item
        // or worn slot fitting worse than ICONS_UNSURE_FIT is ringed
        private const val ICONS_MIDDLE_FIT = 17f
        private const val ICONS_UNSURE_FIT = 22f
        // An equipment slot read by the older gear reader is ringed above this (dark melee gear fits 11-37 there)
        private const val GEAR_UNSURE_FIT = 32f
        private const val ICONS_KEPT = 1500
    }

    // What the inventory tab shows: each space's guesses (item IDs, best first; null for an empty space), amount, and
    // whether its best guess fits only poorly (or its amount couldn't be read)
    class InventoryReading(val spaces: List<List<Int>?>, val amounts: List<Int?>, val unsure: List<Boolean>)

    // What the equipment tab shows: each slot's guesses (item IDs, best first; an empty list for an empty slot), the
    // ammunition's amount, and the slots read without a clear fit
    class GearReading(val slots: Map<String, List<Int>>, val ammoAmount: Int?, val unsure: Set<String>)

    // The pictures the readers compare the screen with, loaded the first time they're needed (in the background,
    // under lock: a stopped scan's look can still be finishing when the next scan starts)
    private val lock = Any()
    private var books: List<LoadoutReader.Picture>? = null
    private var runes: List<Pair<Int, LoadoutReader.Picture>>? = null
    private var shapes: InventoryReader.Shapes? = null
    private var pictureItems: Map<String, Int>? = null
    private var notedPictureItems: Map<String, Int>? = null   // the same for items that can be noted: picture to noted ID
    private var canvases: InventoryReader.Canvases? = null     // RuneLite's icons in brief
    private var sameLook: Map<Int, Int>? = null                // an icon's ID to the item best shown for it
    // Icons read are kept (about 4.6 KB each), so reading again doesn't decode the same ones again
    private val iconPixels = android.util.LruCache<Int, LoadoutReader.Picture>(ICONS_KEPT)

    // ---------------- Spellbook and rune pouch ----------------

    // Loads the spellbook and rune pictures (the first look's "load pictures")
    fun preload(data: ItemData) { readerPictures(data) }

    fun spellbook(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData): Int? =
        LoadoutReader.spellbook(pixels, panel, readerPictures(data).first)

    fun runePouch(pixels: LightBoxReader.Pixels, window: PanelFinder.Box, data: ItemData): List<Loadout.Stack>? =
        LoadoutReader.runePouch(pixels, window, readerPictures(data).second)

    // The spellbook pictures and the pouch runes' pictures, read from the app's files once
    private fun readerPictures(data: ItemData): Pair<List<LoadoutReader.Picture>, List<Pair<Int, LoadoutReader.Picture>>> = synchronized(lock) {
        val b = books ?: (0..3).mapNotNull { assetPicture("items/spellbooks/$it.png") }.also { books = it }
        val r = runes ?: LoadoutReader.POUCH_RUNES.mapNotNull { id ->
            (iconPicture(id)?.let { trimmed(it) } ?: data[id]?.picture()?.let { assetPicture("items/icons/$it") })?.let { id to it }
        }.also { runes = it }
        b to r
    }

    // ---------------- Inventory ----------------

    fun inventory(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData): InventoryReading? {
        val shapeList: InventoryReader.Shapes
        val items: Map<String, Int>
        val notedItems: Map<String, Int>
        val canvasList: InventoryReader.Canvases?
        val alike: Map<Int, Int>
        synchronized(lock) {
            shapeList = shapes ?: try {
                InventoryReader.Shapes(context.assets.open("items/shapes.bin").use { it.readBytes() })
            } catch (_: Exception) { return null }.also { shapes = it }
            // which item a picture shows; on a bank note, among the items that can be noted (as its noted ID)
            items = pictureItems ?: data.byPicture().mapValues { it.value.id }.also { pictureItems = it }
            notedItems = notedPictureItems ?: data.byPicture { data.notedOf(it.id) != null }
                .mapValues { data.notedOf(it.value.id)!! }.also { notedPictureItems = it }
            canvasList = canvases()
            alike = lookAlikes(data)
        }
        val found = InventoryReader.read(pixels, panel, shapeList, { name -> assetPicture("items/icons/$name") }, notedItems.keys,
            canvasList, { id -> iconPicture(id) })
        val filled = found.filterNotNull()
        if (debugBuild) Log.d("GearLoadouts", "inventory: ${found.mapIndexed { i, f -> "$i ${f?.let { it.itemId?.let { id -> data.shown(id)?.first?.name + " #$id" } ?: it.picture } ?: "-"} ${f?.cost?.let { "%.0f".format(it) } ?: ""}" }}")
        // Another tab (equipment, prayers...) or the inventory dimmed behind a window gives poor fits, or none: then the
        // inventory isn't changed
        if (filled.isEmpty()) return null
        if (filled.any { it.itemId != null }) {
            if (filled.map { it.cost }.sorted()[filled.size / 2] > ICONS_MIDDLE_FIT) return null
        } else if (filled.count { it.cost < GOOD_FIT } < filled.size * 0.6f) return null
        return InventoryReading(
            found.map { f -> f?.let {
                if (it.itemId != null) data.fromIcons(listOf(it.itemId) + it.otherIds, listOf(it.cost) + it.otherCosts, alike)
                else (listOf(it.picture) + it.others).mapNotNull { pic -> (if (it.noted) notedItems else items)[pic] }.distinct()
            } },
            found.map { it?.amount },
            found.map { it != null && (it.amountUnread || it.cost > (if (it.itemId != null) ICONS_UNSURE_FIT else UNSURE_FIT)) })
    }

    // ---------------- Equipment ----------------

    fun equipment(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box, data: ItemData): GearReading? {
        // RuneLite's icons at each slot's exact place: a fraction of a second. The older gear reader (the DPS tool's,
        // 9-14 seconds on the owner's phone, and the phone slows down after it) only if that finds nothing at all.
        val canvasList = synchronized(lock) { canvases() }
        if (canvasList != null) {
            val worn = InventoryReader.readEquipment(pixels, panel, canvasList, { id -> iconPicture(id) }) { id -> data.shown(id)?.first?.slot }
            if (worn != null && worn.slots.values.any { it.isNotEmpty() }) {
                if (debugBuild) Log.d("GearLoadouts", "equipment: " + worn.slots.entries.joinToString { (k, v) ->
                    "$k ${v.firstOrNull()?.let { (id, fit) -> data.shown(id)?.first?.name + " %.0f".format(fit) } ?: "-"}" })
                val alike = synchronized(lock) { lookAlikes(data) }
                val ids = worn.slots.mapValues { (_, fits) -> data.fromIcons(fits.map { it.first }, fits.map { it.second }, alike) }
                val unsure = worn.slots.filterValues { (it.firstOrNull()?.second ?: 0f) > ICONS_UNSURE_FIT }.keys
                return GearReading(ids, worn.ammoAmount, unsure)
            }
        }
        return equipmentSlowly(pixels, panel)
    }

    private fun equipmentSlowly(pixels: LightBoxReader.Pixels, panel: PanelFinder.Box): GearReading? {
        val w = panel.width
        val h = panel.height
        if (w <= 0 || h <= 0 || panel.right > pixels.width || panel.bottom > pixels.height) return null
        val px = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels.data, (panel.top + y) * pixels.width + panel.left, px, y * w, w)
        val result = GearRecognizer.shared(context).recognize(px, w, h) ?: return null
        if (debugBuild) Log.d("GearLoadouts", "panel $panel, slot layout fit ${result.layoutScore}, brightness ${result.gamma}")
        if (debugBuild) for ((slot, choices) in result.slots) Log.d("GearLoadouts", "  $slot at ${result.slotRects[slot]}: " +
            choices.take(3).joinToString { "${it.item?.label ?: "empty"} (${it.item?.id}) %.1f".format(it.cost) })
        if (result.slots.isEmpty()) return null
        // (The scan only reads here when the equipment tab's button shows it's open, so another tab can't be taken for
        // it. Gear that fits only poorly is ringed for checking rather than thrown away: dark melee gear once got
        // rejected over and over, 14 seconds a time, though every guess was right.)
        val unsure = result.slots.filterValues { (it.firstOrNull()?.takeIf { c -> c.item != null }?.cost ?: 0f) > GEAR_UNSURE_FIT }.keys
        val slots = result.slots.mapValues { (_, choices) -> choices.mapNotNull { it.item?.id } }
        // the ammunition's amount is written in yellow over the top of its slot
        val ammo = result.slotRects["ammo"]?.takeIf { slots["ammo"].orEmpty().isNotEmpty() }?.let { r ->
            LoadoutReader.amountIn(pixels, PanelFinder.Box(panel.left + r.left - r.width() / 8, panel.top + r.top - r.height() / 6,
                panel.left + r.right, panel.top + r.top + r.height() / 2))
        }
        return GearReading(slots, ammo, unsure)
    }

    // ---------------- Pictures ----------------

    // RuneLite's icons in brief (call under lock)
    private fun canvases(): InventoryReader.Canvases? = canvases ?: try {
        InventoryReader.Canvases(context.assets.open("items/canvas.bin").use { it.readBytes() })
    } catch (_: Exception) { null }?.also { canvases = it }

    // Items that look exactly alike share one icon (the lowest ID's): for an icon read, the one to pick (call under lock)
    private fun lookAlikes(data: ItemData): Map<Int, Int> = sameLook ?: data.lookAlikes(icons.sharedIcon).also { sameLook = it }

    // An item's icon (RuneLite's, 36 x 32) by its ID
    private fun iconPicture(id: Int): LoadoutReader.Picture? {
        val key = icons.iconId(id)
        synchronized(iconPixels) { iconPixels.get(key) }?.let { return it }
        return assetPicture("items/canvas/$key.png")?.also { synchronized(iconPixels) { iconPixels.put(key, it) } }
    }

    // A picture cut down to its visible part
    private fun trimmed(p: LoadoutReader.Picture): LoadoutReader.Picture {
        var l = p.width; var t = p.height; var r = -1; var b = -1
        for (y in 0 until p.height) for (x in 0 until p.width) if (((p.argb[y * p.width + x] ushr 24) and 0xFF) > 128) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        if (r < 0) return p
        val w = r - l + 1; val h = b - t + 1
        return LoadoutReader.Picture(w, h, IntArray(w * h) { p.argb[(t + it / w) * p.width + l + it % w] })
    }

    private fun assetPicture(path: String): LoadoutReader.Picture? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.let { bmp ->
            val argb = IntArray(bmp.width * bmp.height)
            bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            LoadoutReader.Picture(bmp.width, bmp.height, argb).also { bmp.recycle() }
        }
    } catch (_: Exception) { null }
}
