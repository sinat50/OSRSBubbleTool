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
        val read = InventoryReader.read(p, panel!!, shapes, pictures)
        println("read in ${(System.nanoTime() - started) / 1_000_000} ms")
        val expected = listOf("Eye of Ayak", "Elidinis' ward", "Confliction gauntlets", "Virtus robe top",
            "Virtus robe bottom", "Virtus mask", "Prayer potion(4)", "Prayer potion(4)", "Purple sweets") +
            List(13) { "Shark" } + listOf("Ranging potion(4)", "Extended anti-venom+(4)", "Shark", "Shark",
            "Saturated heart", "Divine rune pouch")
        val wrong = expected.indices.filter { if (it == empty) read[it] != null else read[it]?.picture?.startsWith(expected[it]) != true }
            .map { "space $it: expected ${if (it == empty) "nothing" else expected[it]}, read ${read[it]?.picture}" }
        assertEquals("Eye of Ayak.png", read[0]?.picture)   // the charged one before the uncharged look-alike
        assertEquals("", wrong.joinToString("\n"))
        assertEquals(103, read[8]?.amount)   // the purple sweets
    }

    // Read on another tab (or the inventory dimmed behind the rune pouch), few spaces fit well: the app then leaves
    // the inventory as it was (GearLoadoutsTool: at least 60% must fit better than 40)
    @Test
    fun otherTabsDontLookLikeAnInventory() {
        for (name in listOf("2_equipment.png", "3_rune_pouch.png", "4_lunar.png")) {
            val f = File(shots, name)
            assumeTrue(f.exists())
            val p = Shot(f)
            val read = InventoryReader.read(p, PanelFinder.findPanel(p)!!, shapes, pictures).filterNotNull()
            val good = read.count { it.cost < 40f }
            println("$name: ${read.size} spaces with something, $good fitting well")
            val looksLikeInventory = read.isNotEmpty() && good >= read.size * 0.6f
            assertEquals(name, name == "4_lunar.png", looksLikeInventory)   // that one has the inventory tab open
        }
    }
}
