package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.Loadout.Stack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Gear Loadouts' screen reading, on screenshots from the owner's phone (10 October 2026, phone sideways).
// The screenshots aren't part of the project: they're in Documents\Androiddev\loadout_shots (or set
// LOADOUT_SHOTS to another folder). Without them these tests are skipped. The expected answers are what the
// owner had in the game: the spellbook they'd switched to, and their divine rune pouch's runes.
class LoadoutReaderTest {

    private val shots = File(System.getenv("LOADOUT_SHOTS") ?: "C:/Users/Tanis/Documents/Androiddev/loadout_shots")
    private val assets = File("src/main/assets")
    private val items by lazy { ItemData(File(assets, "items/items.tsv").readText()) }

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    private fun picture(file: File): LoadoutReader.Picture {
        val image = ImageIO.read(file)
        val argb = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) }
        return LoadoutReader.Picture(image.width, image.height, argb)
    }

    private val books by lazy { (0..3).map { picture(File(assets, "items/spellbooks/$it.png")) } }
    // the runes' icons (RuneLite's, as the app uses them), cut down to the rune itself
    private val runes by lazy {
        LoadoutReader.POUCH_RUNES.map { id -> id to trimmed(picture(File(assets, "items/canvas/$id.png"))) }
    }

    private fun trimmed(p: LoadoutReader.Picture): LoadoutReader.Picture {
        var l = p.width; var t = p.height; var r = -1; var b = -1
        for (y in 0 until p.height) for (x in 0 until p.width) if (((p.argb[y * p.width + x] ushr 24) and 0xFF) > 128) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        val w = r - l + 1; val h = b - t + 1
        return LoadoutReader.Picture(w, h, IntArray(w * h) { p.argb[(t + it / w) * p.width + l + it % w] })
    }

    init { LoadoutReader.debugLog = { println(it) } }

    private fun shot(name: String): Shot {
        val f = File(shots, name)
        assumeTrue("screenshots not found in $shots", f.exists())
        return Shot(f)
    }

    private fun spellbookIn(name: String): Int? {
        val p = shot(name)
        val panel = PanelFinder.findPanel(p)
        assertNotNull("side panel not found in $name", panel)
        return LoadoutReader.spellbook(p, panel!!, books)
    }

    @Test
    fun readsEachSpellbook() {
        assertEquals(0, spellbookIn("4_standard.png"))
        assertEquals(1, spellbookIn("4_ancient.png"))
        assertEquals(2, spellbookIn("4_lunar.png"))
        assertEquals(3, spellbookIn("4_arceuus.png"))
        // the first screenshots were on the standard spellbook too, with other tabs open
        assertEquals(0, spellbookIn("1_inventory.png"))
        assertEquals(0, spellbookIn("2_equipment.png"))
        assertEquals(0, spellbookIn("3_rune_pouch.png"))
    }

    @Test
    fun readsTheRunePouch() {
        val p = shot("3_rune_pouch.png")
        val window = PanelFinder.runePouchWindow(p)
        assertNotNull("rune pouch window not found", window)
        val started = System.nanoTime()
        val read = LoadoutReader.runePouch(p, window!!, runes)
        println("rune pouch read in ${(System.nanoTime() - started) / 1_000_000} ms: " +
            read?.joinToString { "${items[it.id]?.name} x ${it.amount}" })
        // nature, lava, law and mist runes
        assertEquals(listOf(Stack(561, 16000), Stack(4699, 2668), Stack(563, 16000), Stack(4695, 2285)), read)
    }

    @Test
    fun readsTheRunePouchAgainAfterAHouseTeleport() {
        // the same pouch later, one law, lava and mist rune used (amounts with every digit the first one lacked)
        val p = shot("5_rune_pouch_479.png")
        val read = LoadoutReader.runePouch(p, PanelFinder.runePouchWindow(p)!!, runes)
        assertEquals(listOf(Stack(561, 16000), Stack(4699, 2667), Stack(563, 15999), Stack(4695, 2284)), read)
        assertEquals(3, spellbookIn("5_rune_pouch_479.png"))   // on Arceuus by then
    }

    @Test
    fun readsAPouchWithEmptySlots() {
        // two runes, the other two slots crossed out (an X): the empty slots mustn't read as runes, or stop the reading
        val p = shot("13_rune_pouch_two.png")
        val read = LoadoutReader.runePouch(p, PanelFinder.runePouchWindow(p)!!, runes)
        assertEquals(listOf(Stack(561, 16000), Stack(4699, 2667)), read)
    }

    @Test
    fun aPouchWithoutItsAmountsIsNotTrusted() {
        // Once a look caught the pouch window while it was still being drawn and read a smoke rune in every slot.
        // A real pouch always shows each rune's amount: the same screenshot with its yellow digits wiped off (painted
        // stone grey) must not be read.
        val real = shot("3_rune_pouch.png")
        val window = PanelFinder.runePouchWindow(real)!!
        val wiped = object : LightBoxReader.PixelSource {
            override val width = real.width
            override val height = real.height
            override fun rgb(x: Int, y: Int): Int {
                val c = real.rgb(x, y)
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                return if (minOf(r, g) - b > 60 && x in window.left..window.right && y in window.top..window.bottom) 0x5A5046 else c
            }
        }
        assertNull(LoadoutReader.runePouch(wiped, window, runes))
    }

    @Test
    fun noRunePouchWhenNoneIsOpen() {
        for (name in listOf("1_inventory.png", "2_equipment.png", "4_lunar.png")) {
            val p = shot(name)
            val window = PanelFinder.runePouchWindow(p)
            assertNull("found a rune pouch in $name", window?.let { LoadoutReader.runePouch(p, it, runes) })
        }
    }

    // Which tab is open: its button is red (the guided scan reads the inventory or the equipment only on its tab)
    @Test
    fun tellsWhichTabIsOpen() {
        for ((name, open) in listOf("1_inventory.png" to PanelFinder.INVENTORY_TAB, "2_equipment.png" to PanelFinder.EQUIPMENT_TAB,
                "4_arceuus.png" to PanelFinder.INVENTORY_TAB, "3_rune_pouch.png" to PanelFinder.INVENTORY_TAB)) {
            val p = shot(name)
            val panel = PanelFinder.findPanel(p)!!
            assertEquals(name, open == PanelFinder.INVENTORY_TAB, PanelFinder.tabOpen(p, panel, 0, PanelFinder.INVENTORY_TAB))
            assertEquals(name, open == PanelFinder.EQUIPMENT_TAB, PanelFinder.tabOpen(p, panel, 0, PanelFinder.EQUIPMENT_TAB))
        }
    }
}
