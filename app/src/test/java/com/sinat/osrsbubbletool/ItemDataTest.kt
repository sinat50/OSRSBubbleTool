package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The item list Gear Loadouts uses (assets/items/items.tsv), on the PC. Expected answers are from the game
// and the OSRS Wiki, not from the code: item IDs as RuneLite uses them, and the pictures coins show in the game
// (1, 2, 3, 4, 5, 25, 100, 250, 1,000 and 10,000 coins each look fuller).
class ItemDataTest {

    // The real file the app comes with (tests run from the app folder)
    private val items by lazy {
        ItemData(File("src/main/assets/items/items.tsv").readText(), File("src/main/assets/items/noted.tsv").readText())
    }

    @Test
    fun wellKnownItemsHaveTheirGameIds() {
        assertEquals("Shark", items[385]?.name)
        assertEquals("Prayer potion(4)", items[2434]?.name)
        assertEquals("Prayer potion(1)", items[143]?.name)
        assertEquals("Rune pouch", items[12791]?.name)
        assertEquals("Abyssal whip", items[4151]?.name)
        assertEquals("Coins", items[995]?.name)
        assertNull(items[-5])
    }

    @Test
    fun wornItemsKnowTheirSlot() {
        assertEquals("weapon", items[4151]?.slot)     // Abyssal whip
        assertEquals("neck", items[6585]?.slot)       // Amulet of fury
        assertEquals("ammo", items[892]?.slot)        // Rune arrow
        assertEquals("head", items[10828]?.slot)      // Helm of Neitiznot
        assertEquals("weapon", items[11802]?.slot)    // Armadyl godsword (two-handed counts as weapon)
        assertNull(items[385]?.slot)                  // a shark isn't worn
    }

    @Test
    fun coinsLookFullerAsTheStackGrows() {
        val coins = items[995]!!
        assertEquals("Coins 1.png", coins.picture(1))
        assertEquals("Coins 4.png", coins.picture(4))
        assertEquals("Coins 5.png", coins.picture(24))
        assertEquals("Coins 25.png", coins.picture(25))
        assertEquals("Coins 250.png", coins.picture(999))
        assertEquals("Coins 10000.png", coins.picture(2_000_000))
        assertEquals("Coins 1.png", coins.picture(0))   // less than any stack: the smallest picture
    }

    @Test
    fun oneDrawingItemsAlwaysUseIt() {
        assertEquals("Shark.png", items[385]?.picture(1))
        assertEquals("Shark.png", items[385]?.picture(27))
    }

    @Test
    fun aMadeUpListIsReadAsWritten() {
        val list = ItemData("# a note\n1\tThing\t\tThing.png\n2\tArrows\tammo\tArrows 1.png|Arrows 5.png\nbroken line\n")
        assertEquals(2, list.all.size)
        assertNull(list[1]?.slot)
        assertEquals("Arrows 1.png", list[2]?.picture(3))
        assertEquals("Arrows 5.png", list[2]?.picture(80))
    }

    @Test
    fun searchFindsByWordsAndPutsNameStartsFirst() {
        val found = items.search("prayer pot")
        assertTrue(found.isNotEmpty())
        assertTrue(found.first().name.startsWith("Prayer potion"))
        // all typed words count, in any order and case
        assertTrue(items.search("WHIP abyssal").any { it.id == 4151 })
        // with a slot, only items worn there
        assertTrue(items.search("whip", slot = "weapon").all { it.slot == "weapon" })
        assertTrue(items.search("shark", slot = "head").none { it.name == "Shark" })
        assertTrue(items.search("   ").isEmpty())
    }

    @Test
    fun notedItemsHaveTheirOwnIds() {
        // the noted IDs as the game has them (a shark is 385, a noted shark 386)
        assertEquals(386, items.notedOf(385))
        assertEquals(385, items.plainOf(386))
        assertEquals(5355, items.notedOf(5354))   // Filled plant pot
        assertNull(items.notedOf(4151 + 1000000))
        assertNull(items.plainOf(385))            // a shark isn't noted
        assertEquals("Shark", items.shown(386)?.first?.name)
        assertEquals(true, items.shown(386)?.second)
        assertEquals(false, items.shown(385)?.second)
        assertNull(items.notedOf(12791))          // a rune pouch can't be noted
    }

    @Test
    fun minigameCopiesComeAfterTheRealItemAndSayWhereFrom() {
        // the Last Man Standing rune pouch (23650) has the same name in the game as the real one (12791)
        assertNull(items[12791]?.variant)
        assertEquals("Last Man Standing", items[23650]?.variant)
        assertNull(items[2434]?.variant)   // a potion dose isn't a copy
        val pouches = items.search("rune pouch").filter { it.name == "Rune pouch" }
        assertEquals(12791, pouches.first().id)
        assertEquals(pouches.size, pouches.map { it.variant }.toSet().size)   // each copy listed once
    }

    @Test
    fun runePouchesAndHowManyRunesTheyHold() {
        assertEquals(3, items.runePouchSize(12791))    // Rune pouch
        assertEquals(4, items.runePouchSize(27281))    // Divine rune pouch
        assertNull(items.runePouchSize(385))           // a shark
        assertNull(items.runePouchSize(12792))         // a noted ID isn't a pouch that holds runes
    }

    // Reading an icon: the item to offer first. The Avernic defender (22322) and its Last Man Standing copy (24186)
    // share one icon (canvas.tsv: the reader reports 22322 for both): the defender itself is offered, not the copy. Its
    // broken version (22441, a crack apart) read only a little better than the whole one (1 apart) offers the whole
    // one first; read clearly better (5 apart), it stays first.
    @Test
    fun anIconReadingOffersTheItemPeopleUse() {
        val alike = items.lookAlikes(mapOf(24186 to 22322))
        assertEquals(22322, alike[22322])
        assertEquals(listOf(22322, 22441), items.fromIcons(listOf(22322, 22441), listOf(15f, 16f), alike))
        assertEquals(listOf(22322, 22441), items.fromIcons(listOf(22441, 22322), listOf(15f, 16f), alike))
        assertEquals(listOf(22441, 22322), items.fromIcons(listOf(22441, 22322), listOf(15f, 20f), alike))
    }
}
