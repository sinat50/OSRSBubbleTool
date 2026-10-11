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
    private val lookAlikes by lazy {
        HashMap<Int, MutableList<Int>>().also { g -> for ((a, b) in sharedIcon) g.getOrPut(b) { mutableListOf(b) }.add(a) }
            .mapValues { items.preferred(it.value) }
    }
    private val icons = InventoryReader.Icons { id ->
        val f = File(assets, "items/canvas/${sharedIcon[id] ?: id}.png")
        if (!f.exists()) null else ImageIO.read(f).let { im ->
            LoadoutReader.Picture(im.width, im.height, IntArray(im.width * im.height).also { im.getRGB(0, 0, im.width, im.height, it, 0, im.width) })
        }
    }

    private fun read(p: Shot, panel: PanelFinder.Box) = InventoryReader.read(p, panel, shapes, pictures, notable, canvases, icons)

    // A reading's item name and whether it's noted, read either way (an icon's item, or a wiki picture's name)
    private fun idOf(f: InventoryReader.Found) =
        items.chargedFirst((listOf(f.itemId!!) + f.otherIds).map { lookAlikes[it] ?: it }, listOf(f.cost) + f.otherCosts).first()
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
    // inventory as it was (GearLoadoutsTool: the middle fit must be 17 or better, read with RuneLite's icons)
    @Test
    fun otherTabsDontLookLikeAnInventory() {
        for (name in listOf("2_equipment.png", "3_rune_pouch.png", "4_lunar.png")) {
            val f = File(shots, name)
            assumeTrue(f.exists())
            val p = Shot(f)
            val read = read(p, PanelFinder.findPanel(p)!!).filterNotNull()
            val middle = read.map { it.cost }.sorted().getOrNull(read.size / 2) ?: Float.MAX_VALUE
            println("$name: ${read.size} spaces with something, middle fit $middle")
            val looksLikeInventory = read.isNotEmpty() && middle <= 17f
            assertEquals(name, name == "4_lunar.png", looksLikeInventory)   // that one has the inventory tab open
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
}
