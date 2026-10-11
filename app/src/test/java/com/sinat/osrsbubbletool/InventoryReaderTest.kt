package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Gear Loadouts' inventory reading, on screenshots from the owner's phone (10 October 2026, phone sideways, the
// side panel see-through). The screenshots aren't part of the project: they're in Documents\Androiddev\loadout_shots
// (or set LOADOUT_SHOTS to another folder); without them these tests are skipped. The expected items are the ones
// the owner listed.
class InventoryReaderTest {

    private val shots = File(System.getenv("LOADOUT_SHOTS") ?: "C:/Users/Tanis/Documents/Androiddev/loadout_shots")
    private val assets = File("src/main/assets")
    private val shapes by lazy { InventoryReader.Shapes(File(assets, "items/shapes.bin").readBytes()) }
    private val items by lazy { ItemData(File(assets, "items/items.tsv").readText(), File(assets, "items/noted.tsv").readText()) }
    // the pictures of items that can be noted (as the app works them out)
    private val notable by lazy { items.all.filter { items.notedOf(it.id) != null }.flatMap { it.pictures }.toSet() }
    // RuneLite's icons (items/canvas/, from tools/items/build_icons.py), and items that share another's icon
    private val canvases by lazy { InventoryReader.Canvases(File(assets, "items/canvas.bin").readBytes()) }
    private val sharedIcon by lazy {
        File(assets, "items/canvas.tsv").readLines().filter { !it.startsWith("#") && it.isNotBlank() }
            .associate { it.split('\t').let { p -> p[0].toInt() to p[1].toInt() } }
    }
    private val lookAlikes by lazy { items.lookAlikes(sharedIcon) }
    private val icons = InventoryReader.Icons { id ->
        val f = File(assets, "items/canvas/${sharedIcon[id] ?: id}.png")
        if (!f.exists()) null else ImageIO.read(f).let { im ->
            LoadoutReader.Picture(im.width, im.height, IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) })
        }
    }

    private fun read(p: LightBoxReader.PixelSource, panel: PanelFinder.Box) = InventoryReader.read(p, panel, shapes, pictures, notable, canvases, icons)

    // A reading's item name and whether it's noted, read either way (an icon's item, or a wiki picture's name)
    private fun idOf(f: InventoryReader.Found) =
        items.fromIcons(listOf(f.itemId!!) + f.otherIds, listOf(f.cost) + f.otherCosts, lookAlikes).first()
    private fun nameOf(f: InventoryReader.Found?): String? = when {
        f == null -> null
        f.itemId != null -> items.shown(idOf(f))?.first?.name
        else -> f.picture.removeSuffix(".png")
    }
    private fun isNoted(f: InventoryReader.Found?) = f?.noted == true || (f?.itemId != null && items.plainOf(idOf(f)) != null)

    // A screenshot's pixels, read once into an array (as the app does)
    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        private val data = IntArray(width * height).also { image.getRGB(0, 0, width, height, it, 0, width) }
        override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
    }

    private val pictures = InventoryReader.Pictures { name ->
        val f = File(assets, "items/icons/$name")
        if (!f.exists()) null else ImageIO.read(f).let { im ->
            LoadoutReader.Picture(im.width, im.height, IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) })
        }
    }

    init { InventoryReader.debugLog = { println(it) } }

    @Test
    fun readsTheOwnersInventory() = readsInventory("1_inventory.png")

    // the same inventory later, on the Lunar spellbook (a darker scene behind the see-through panel)
    @Test
    fun readsTheOwnersInventoryAgain() = readsInventory("4_lunar.png")

    // and today, with the game drawn a little smaller (panel 427 pixels wide, not 435) and space 10 emptied
    @Test
    fun readsTheOwnersInventoryDrawnSmaller() = readsInventory("6_inventory_smaller.png", empty = 9)

    private fun readsInventory(name: String, empty: Int? = null) {
        val f = File(shots, name)
        assumeTrue("screenshots not found in $shots", f.exists())
        val p = Shot(f)
        val panel = PanelFinder.findPanel(p)
        assertNotNull(panel)
        val started = System.nanoTime()
        val read = read(p, panel!!)
        println("read in ${(System.nanoTime() - started) / 1_000_000} ms")
        val expected = listOf("Eye of Ayak", "Elidinis' ward", "Confliction gauntlets", "Virtus robe top",
            "Virtus robe bottom", "Virtus mask", "Prayer potion(4)", "Prayer potion(4)", "Purple sweets") +
            List(13) { "Shark" } + listOf("Ranging potion(4)", "Extended anti-venom+(4)", "Shark", "Shark",
            "Saturated heart", "Divine rune pouch")
        val wrong = expected.indices.filter { if (it == empty) read[it] != null else nameOf(read[it])?.startsWith(expected[it]) != true }
            .map { "space $it: expected ${if (it == empty) "nothing" else expected[it]}, read ${nameOf(read[it])}" }
        assertEquals("Eye of Ayak", nameOf(read[0]))   // the charged one before the uncharged look-alike
        assertEquals("", wrong.joinToString("\n"))
        assertEquals(103, read[8]?.amount)   // the purple sweets
    }

    // Read on another tab (or the inventory dimmed behind the rune pouch), the fits are poor: the app then leaves the
    // inventory as it was (InventoryReader.trusted: the middle fit must be 17 or better, read with RuneLite's icons)
    @Test
    fun otherTabsDontLookLikeAnInventory() {
        for (name in listOf("2_equipment.png", "3_rune_pouch.png", "4_lunar.png")) {
            val f = File(shots, name)
            assumeTrue(f.exists())
            val p = Shot(f)
            val read = read(p, PanelFinder.findPanel(p)!!)
            println("$name: ${read.count { it != null }} spaces with something")
            assertEquals(name, name == "4_lunar.png", InventoryReader.trusted(read))   // that one has the inventory tab open
        }
    }

    // Stacks of every kind and noted items (the owner's list): coins at 24M (green), chaos runes at 134K (white),
    // teleport scrolls, and three noted items (the black d'hide body is dark all over on its note: told from a black
    // chainbody by the colours inside it).
    @Test
    fun readsStacksAndNotes() = readsStacks("7_inventory_stacks.png")

    // the same a little later, with a dark barrel behind the seed dibber (it read as a dragon warhammer on the phone)
    @Test
    fun readsStacksAndNotesAgain() = readsStacks("8_inventory_dibber.png")

    // and over a noisy background (a rug with black triangles that join the items' outlines: the Revenant cave
    // teleport and the dark chinchompa were lost in it before items were compared at their exact place)
    @Test
    fun readsStacksAndNotesOverANoisyBackground() = readsStacks("9_inventory_noisy.png")

    // and somewhere brighter, where the green 24M is blurred into the light background (it was missed: one coin)
    @Test
    fun readsStacksAndNotesOverALightBackground() = readsStacks("10_inventory_coin.png")

    // and over a red rug with dark stars, where the empty spaces' patterns once read as items
    @Test
    fun readsStacksAndNotesOverARedRug() = readsStacks("11_inventory_red_rug.png")

    private fun readsStacks(name: String, lost: Set<Int> = emptySet()) {
        val f = File(shots, name)
        assumeTrue(f.exists())
        val p = Shot(f)
        val read = read(p, PanelFinder.findPanel(p)!!)
        val expected = listOf(
            Triple("Coins", 24_000_000, false), Triple("Chaos rune", 134_000, false), Triple("Seed dibber", null, false),
            Triple("Magic secateurs", null, false), Triple("Blighted ancient ice sack", 82, false),
            Triple("Mos le'harmless teleport", 59, false), Triple("Tai bwo wannai teleport", 13, false),
            Triple("Piscatoris teleport", 16, false), Triple("Revenant cave teleport", 6, false),
            Triple("Black chinchompa", 62, false), Triple("Rake", null, false), Triple("Spade", null, false),
            Triple("Filled plant pot", 819, true), Triple("Rune platebody", 23, true), Triple("Black d'hide body", 96, true),
            Triple("Bottomless compost bucket", 1, false))
        val wrong = expected.indices.filter { i ->
            val r = read[i]
            val (name, amount, noted) = expected[i]
            if (i in lost) return@filter r == null || r.cost <= 30f || r.amount != amount
            val n = nameOf(r)
            r == null || (n != name && n?.startsWith("$name ") != true) || r.amount != amount || isNoted(r) != noted
        }.map { "space $it: expected ${expected[it]}, read ${nameOf(read[it])} x ${read[it]?.amount}, noted ${isNoted(read[it])} (fit ${read[it]?.cost})" }
        assertEquals("", wrong.joinToString("\n"))
        assertEquals("", (16 until 28).filter { read[it] != null }.joinToString { "space $it: ${nameOf(read[it])}" })   // the rest is empty
    }

    // A phone with a bigger or smaller screen draws the game bigger or smaller: a screenshot resized (smoothly, a
    // little blurrier than a real phone would draw it)
    private fun resized(name: String, scale: Double): LightBoxReader.PixelSource {
        val original = ImageIO.read(File(shots, name))
        val im = java.awt.image.BufferedImage((original.width * scale).toInt(), (original.height * scale).toInt(), java.awt.image.BufferedImage.TYPE_INT_RGB)
        im.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(original, 0, 0, im.width, im.height, null)
            dispose()
        }
        val data = IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) }
        return object : LightBoxReader.PixelSource {
            override val width = im.width; override val height = im.height
            override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
        }
    }

    // Every item and amount at other screen sizes. Each size once went wrong somewhere: the green 24M read as
    // 241,000,000 (an M's stroke taken for a 1), noted items lost in the narrowing down (its colour points were on the
    // note's paper), amounts missed on small screens (the digit cut a little off), 134K read as 1,000. (At 70% the
    // stacks screenshot's side panel isn't found: its thin border blurs away when shrunk, which a phone wouldn't do.)
    @Test
    fun readsAtOtherScreenSizes() {
        val first = listOf("Eye of Ayak", "Elidinis' ward", "Confliction gauntlets", "Virtus robe top", "Virtus robe bottom", "Virtus mask",
            "Prayer potion(4)", "Prayer potion(4)", "Purple sweets") + List(13) { "Shark" } +
            listOf("Ranging potion(4)", "Extended anti-venom+(4)", "Shark", "Shark", "Saturated heart", "Divine rune pouch")
        val stacks = listOf("Coins", "Chaos rune", "Seed dibber", "Magic secateurs", "Blighted ancient ice sack", "Mos le'harmless teleport",
            "Tai bwo wannai teleport", "Piscatoris teleport", "Revenant cave teleport", "Black chinchompa", "Rake", "Spade",
            "Filled plant pot", "Rune platebody", "Black d'hide body", "Bottomless compost bucket") + List(12) { null }
        val amounts = listOf(24_000_000, 134_000, null, null, 82, 59, 13, 16, 6, 62, null, null, 819, 23, 96, 1)
        val cases = listOf("1_inventory.png" to listOf(0.7, 0.85, 1.15, 1.3), "7_inventory_stacks.png" to listOf(0.85, 1.15, 1.3),
            "11_inventory_red_rug.png" to listOf(0.7, 0.85, 1.15, 1.3))
        val problems = ArrayList<String>()
        for ((name, scales) in cases) {
            assumeTrue(File(shots, name).exists())
            val expected = if (name.startsWith("1_")) first else stacks
            for (scale in scales) {
                val p = resized(name, scale)
                val read = read(p, PanelFinder.findPanel(p) ?: run { problems.add("$name at ${scale}x: no side panel"); continue })
                for (i in expected.indices) {
                    val want = expected[i]; val got = nameOf(read[i])
                    val ok = if (want == null) got == null else got?.startsWith(want) == true
                    // an amount must be right, or (134K at 70% on the rug) unread and ringed for checking: never wrong
                    val amountOk = expected !== stacks || read[i]?.amount == amounts.getOrNull(i) || (read[i]?.amount == null && read[i]?.amountUnread == true)
                    if (!ok || !amountOk) problems.add("$name at ${scale}x, space $i: expected $want x ${amounts.getOrNull(i)}, read $got x ${read[i]?.amount}")
                }
            }
        }
        assertEquals("", problems.joinToString("\n"))
    }

    // The green 24M at 130% and 70% (an M's left stroke can come apart at these sizes; it was once taken for a 1)
    @Test
    fun readsTheAmountAtOtherScreenSizes() {
        assumeTrue(File(shots, "11_inventory_red_rug.png").exists())
        for (scale in listOf(1.3, 0.7)) {
            val p = resized("11_inventory_red_rug.png", scale)
            val panel = PanelFinder.findPanel(p)!!
            val s = panel.width / 204f
            val x0 = panel.left + 18 * s; val y0 = panel.top + 12 * s
            assertEquals("at ${scale}x", 24_000_000, LoadoutReader.amountIn(p, PanelFinder.Box(x0.toInt(), y0.toInt(), (x0 + 38 * s).toInt(), (y0 + 12 * s).toInt())))
        }
    }

    // The equipment tab at each slot's exact place (the owner's melee set, 11 October 2026; the older gear reader took
    // 9-14 seconds on the phone for this, and once rejected it over and over)
    @Test
    fun readsTheEquipmentTab() {
        val f = File(shots, "12_equipment_melee.png")
        assumeTrue(f.exists())
        assertEquals("", wrongMeleeGear(Shot(f)))
    }

    // The same at other screen sizes. The Avernic defender's broken look-alike (a crack, little else) once won at 70%
    // and 85%, and was only 1.3 behind at the phone's own size: the whole one goes first when they fit about as well.
    @Test
    fun readsTheEquipmentTabAtOtherScreenSizes() {
        assumeTrue(File(shots, "12_equipment_melee.png").exists())
        val problems = listOf(0.7, 0.85, 1.15, 1.3).mapNotNull { scale ->
            wrongMeleeGear(resized("12_equipment_melee.png", scale)).takeIf { it.isNotEmpty() }?.let { "at ${scale}x: $it" }
        }
        assertEquals("", problems.joinToString("\n"))
    }

    // The slots read wrong in a screenshot of the owner's melee set (empty if none)
    private fun wrongMeleeGear(p: LightBoxReader.PixelSource): String {
        val started = System.nanoTime()
        val worn = InventoryReader.readEquipment(p, PanelFinder.findPanel(p)!!, canvases, icons) { id -> items.shown(id)?.first?.slot }
        println("equipment read in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNotNull(worn)
        val expected = mapOf("head" to "Neitiznot faceguard", "cape" to "Fire cape", "neck" to "Bonecrusher necklace",
            "ammo" to "Rada's blessing 4", "weapon" to "Osmumten's fang", "body" to "Sunfire fanatic cuirass",
            "shield" to "Avernic defender", "legs" to "Sunfire fanatic chausses", "hands" to "Ferocious gloves",
            "feet" to "Devout boots", "ring" to "Celestial ring")
        // the name the app would show (Celestial ring may be charged or not; a broken one is wrong)
        fun shownName(slot: String): String? {
            val fits = worn!!.slots[slot].orEmpty()
            val id = fits.firstOrNull()?.let { items.fromIcons(fits.map { f -> f.first }, fits.map { f -> f.second }, lookAlikes).first() }
            return id?.let { items.shown(it)?.first?.name }
        }
        return expected.filter { (slot, name) ->
            val n = shownName(slot)
            n == null || n.contains("(broken)") || (n != name && !n.startsWith("$name "))
        }.map { (slot, name) -> "$slot: expected $name, read ${shownName(slot)}" }.joinToString("; ")
    }

    // An empty slot reads as empty (the owner's first equipment screenshot: no shield), and a worn item in every other
    @Test
    fun anEmptySlotReadsEmpty() {
        val f = File(shots, "2_equipment.png")
        assumeTrue(f.exists())
        val p = Shot(f)
        val worn = InventoryReader.readEquipment(p, PanelFinder.findPanel(p)!!, canvases, icons) { id -> items.shown(id)?.first?.slot }!!
        println("first equipment screenshot: " + worn.slots.entries.joinToString { (k, v) -> "$k ${v.firstOrNull()?.let { items.shown(it.first)?.first?.name + " %.1f".format(it.second) } ?: "-"}" })
        assertEquals(emptyList<Pair<Int, Float>>(), worn.slots["shield"])
        assertEquals(10, worn.slots.count { it.value.isNotEmpty() })
    }

    // An empty inventory (a trip with only gear) is an inventory too: the red rug screenshot with its empty bottom rows
    // copied over the items. Every space reads empty, and that's trusted: the scan only reads the inventory with its
    // tab open, so "nothing anywhere" can't be another tab. (It was thrown away as one: the inventory never ticked
    // off, nor the rune pouch, and the scan waited its full 90 seconds.)
    @Test
    fun anEmptyInventoryIsTrusted() {
        val f = File(shots, "11_inventory_red_rug.png")
        assumeTrue(f.exists())
        val im = ImageIO.read(f)
        val panel = PanelFinder.findPanel(Shot(f))!!
        val s = panel.width / 202.7f
        val rowH = (36 * s).toInt()
        fun rowTop(row: Int) = (panel.top + (14.4f + row * 36) * s - 4 * s).toInt()
        for (row in 0 until 4) {
            val from = rowTop(4 + row % 3)
            for (y in 0 until rowH) for (x in panel.left until panel.right) im.setRGB(x, rowTop(row) + y, im.getRGB(x, from + y))
        }
        val data = IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) }
        val p = object : LightBoxReader.PixelSource {
            override val width = im.width; override val height = im.height
            override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
        }
        val read = read(p, PanelFinder.findPanel(p)!!)
        assertEquals(List(28) { null }, read.map { nameOf(it) })
        assertEquals(true, InventoryReader.trusted(read))
    }

    // An inventory of every kind of rune, each with its amount (the owner's, 11 October 2026). Two bugs showed here:
    // every item having an amount left nothing to work out the game's brightness from (spaces with digits were left
    // out), so the darkest setting won and most runes read as rune essence; and the second 6 of 2667 was cut half a
    // game pixel off (its blurred left edge fell short of "yellow") and read as a 3.
    @Test
    fun readsAnInventoryOfRunes() {
        val f = File(shots, "16_inventory_all_runes.png")
        assumeTrue(f.exists())
        val expected = listOf("Mind rune" to 47006, "Body rune" to 3833, "Astral rune" to 27146, "Mud rune" to 3509,
            "Blood rune" to 36919, "Soul rune" to 40514, "Death rune" to 99426, "Air rune" to 28404, "Wrath rune" to 6012,
            "Sunfire rune" to 1748, "Nature rune" to 25872, "Smoke rune" to 10324, "Aether rune" to 849, "Cosmic rune" to 13511,
            "Dust rune" to 4000, "Fire rune" to 11513, "Earth rune" to 34977, "Steam rune" to 1475, "Chaos rune" to 134_000,
            "Water rune" to 4749, "Lava rune" to 2667, "Mist rune" to 2284, "Law rune" to 18404)
        val problems = ArrayList<String>()
        for (scale in listOf(1.0, 0.85, 1.15)) {
            val p = if (scale == 1.0) Shot(f) else resized(f.name, scale)
            val read = read(p, PanelFinder.findPanel(p)!!)
            if (!InventoryReader.trusted(read)) problems.add("at ${scale}x: not trusted")
            for (i in 0 until 28) {
                val want = expected.getOrNull(i)
                val got = nameOf(read[i])?.let { it to read[i]?.amount }
                if (got != want) problems.add("at ${scale}x, space $i: expected $want, read $got")
            }
        }
        assertEquals("", problems.joinToString("; "))
    }

    // The game's brightness setting on its lowest step (the owner's rune inventory): the dark floor beside the side
    // panel made longer black lines than the panel's own top and bottom edges, and only the longest line in each row
    // was kept, so the panel was never found (a box in the floor was taken instead, and nothing could be read)
    @Test
    fun findsTheSidePanelAtTheLowestBrightness() {
        val f = File(shots, "18_inventory_brightness_low.png")
        assumeTrue(f.exists())
        // where it is at the usual brightness (a pixel's give: the border is a pixel thicker this dark)
        val found = PanelFinder.findPanel(Shot(f))
        assertNotNull(found)
        val expected = PanelFinder.Box(1698, 491, 2125, 1068)
        assertEquals("$found", true, listOf(found!!.left - expected.left, found.top - expected.top, found.right - expected.right,
            found.bottom - expected.bottom).all { kotlin.math.abs(it) <= 2 })
    }

    // ...and the equipment tab there, with nothing worn: the panel is found, its tab is open, and every slot is empty
    @Test
    fun readsEmptyEquipmentAtTheLowestBrightness() {
        val f = File(shots, "19_equipment_brightness_low.png")
        assumeTrue(f.exists())
        val p = Shot(f)
        val panel = PanelFinder.findPanel(p)
        assertNotNull(panel)
        assertEquals(true, PanelFinder.tabOpen(p, panel!!, 0, PanelFinder.EQUIPMENT_TAB))
        val worn = InventoryReader.readEquipment(p, panel, canvases, icons) { id -> items.shown(id)?.first?.slot }!!
        assertEquals("", worn.slots.filterValues { it.isNotEmpty() }.entries.joinToString { (k, v) -> "$k ${items.shown(v[0].first)?.first?.name}" })
    }

    // ...and the inventory reads there: every rune and amount, and the rune pouch (the one item without an amount: the
    // game's brightness was once worked out from it alone, from a guess that was itself wrong, a Crystal helm)
    @Test
    fun readsTheInventoryAtTheLowestBrightness() = readsTheInventoryAtBrightness("18_inventory_brightness_low.png")

    // and the same inventory at the highest brightness. There the chaos rune's light stone is light enough to count as
    // white text and joins the 4 of "134K" to the K (it was left unread): the K is known now, and the 4 cut from it.
    @Test
    fun readsTheInventoryAtTheHighestBrightness() = readsTheInventoryAtBrightness("20_inventory_brightness_high.png")

    private fun readsTheInventoryAtBrightness(name: String) {
        val f = File(shots, name)
        assumeTrue(f.exists())
        val expected = listOf("Mind rune" to 31006, "Body rune" to 3833, "Astral rune" to 27146, "Mud rune" to 3509,
            "Blood rune" to 36919, "Soul rune" to 40514, "Death rune" to 83426, "Air rune" to 28404, "Wrath rune" to 6012,
            "Sunfire rune" to 1748, "Nature rune" to 25872, "Smoke rune" to 10324, "Aether rune" to 849, null,
            "Dust rune" to 4000, "Fire rune" to 11513, "Earth rune" to 34977, "Steam rune" to 1475, "Chaos rune" to 134_000,
            "Water rune" to 4749, "Lava rune" to 2667, "Mist rune" to 2284, "Law rune" to 18404, "Rune pouch" to null)
        val p = Shot(f)
        val read = read(p, PanelFinder.findPanel(p)!!)
        assertEquals(true, InventoryReader.trusted(read))
        val wrong = (0 until 28).filter { (nameOf(read[it])?.let { n -> n to read[it]?.amount }) != expected.getOrNull(it) }
            .map { "space $it: expected ${expected.getOrNull(it)}, read ${nameOf(read[it])} x ${read[it]?.amount}" }
        assertEquals("", wrong.joinToString("; "))
    }

    // A K standing clear of the item behind it. The digit reader once relied on the K touching the item to end the
    // number: with the chaos rune's stone painted out (in the background's colour) around "134K", the K came apart and
    // its upright stroke read as a 1: 1,341,000. The K is known now: 134,000, or left unread, never wrong.
    @Test
    fun aKStandingAloneIsNotReadAsAOne() {
        val problems = ArrayList<String>()
        for ((name, space) in listOf("7_inventory_stacks.png" to 1, "9_inventory_noisy.png" to 1, "10_inventory_coin.png" to 1,
                "11_inventory_red_rug.png" to 1, "16_inventory_all_runes.png" to 18, "18_inventory_brightness_low.png" to 18,
                "20_inventory_brightness_high.png" to 18)) {
            val f = File(shots, name)
            assumeTrue(f.exists())
            val im = ImageIO.read(f)
            val data = IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) }
            val p = object : LightBoxReader.PixelSource {
                override val width = im.width; override val height = im.height
                override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
            }
            val panel = PanelFinder.findPanel(p)!!
            // the stone (light and a little pink) around the amount, painted in the colour just above it
            val s = panel.width / 204f
            val x0 = (panel.left + (18 + (space % 4) * 42) * s).toInt(); val y0 = (panel.top + (12 + (space / 4) * 36) * s).toInt()
            val behind = data[(y0 - 3) * im.width + x0 - 3]
            for (y in y0 until (y0 + 14 * s).toInt()) for (x in x0 until (x0 + 40 * s).toInt()) {
                val c = data[y * im.width + x]; val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
                if (r - g >= 5 && kotlin.math.abs(g - b) <= 3 && minOf(r, g, b) > 120) data[y * im.width + x] = behind
            }
            val amount = read(p, panel)[space]?.amount
            if (amount != null && amount != 134_000) problems.add("$name: read $amount")
        }
        assertEquals("", problems.joinToString("; "))
    }
}
