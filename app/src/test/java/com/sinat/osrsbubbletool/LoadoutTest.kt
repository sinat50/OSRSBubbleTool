package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.Loadout.Stack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Gear Loadouts' saved loadouts on the PC: a loadout must come back exactly as it was saved, and the setups
// saved by the older Inventory Setups (only an id and a name) must still open.
class LoadoutTest {

    private fun roundTrip(l: Loadout) = Loadout.fromJson(JSONObject(l.toJson().toString()))

    @Test
    fun aLoadoutComesBackAsItWasSaved() {
        val l = Loadout("123", "Vorkath")
        l.notes = "Bring antifire\nand a stamina"
        l.spellbook = 3
        l.inventory[0] = Stack(12791)              // rune pouch
        l.inventory[5] = Stack(2434)               // prayer potion(4)
        l.inventory[27] = Stack(995, 1_250_000)    // coins
        l.equipment["weapon"] = Stack(4151)
        l.equipment["ammo"] = Stack(21944, 87)
        l.runePouch = mutableListOf(Stack(560, 1000), null, Stack(565, 250))
        l.quiver = Stack(892, 500)
        val back = roundTrip(l)
        assertEquals("123", back.id)
        assertEquals("Vorkath", back.name)
        assertEquals("Bring antifire\nand a stamina", back.notes)
        assertEquals(3, back.spellbook)
        assertEquals(l.inventory.toList(), back.inventory.toList())
        assertEquals(l.equipment, back.equipment)
        assertEquals(l.runePouch, back.runePouch)
        assertNull(back.boltPouch)
        assertEquals(Stack(892, 500), back.quiver)
        assertTrue(back.hasItems)
    }

    @Test
    fun anOldInventorySetupOpensWithNoItems() {
        val old = Loadout.fromJson(JSONObject("""{"id":"1712345678901","name":"Zulrah"}"""))
        assertEquals("Zulrah", old.name)
        assertFalse(old.hasItems)
        assertNull(old.spellbook)
        assertEquals("", old.notes)
        assertTrue(old.inventory.all { it == null })
    }

    @Test
    fun anEmptyLoadoutSavesOnlyItsName() {
        val json = Loadout("9", "New").toJson()
        assertEquals(setOf("id", "name"), json.keys().asSequence().toSet())
    }

    @Test
    fun amountsAreWrittenAsTheGameWritesThem() {
        // from the game: 99,999 coins show in full in yellow, 100,000 as "100K" in white, 10 million as "10M" in green
        assertEquals("1", Loadout.shortAmount(1))
        assertEquals("99999", Loadout.shortAmount(99_999))
        assertEquals("100K", Loadout.shortAmount(100_000))
        assertEquals("1250K", Loadout.shortAmount(1_250_999))
        assertEquals("9999K", Loadout.shortAmount(9_999_999))
        assertEquals("10M", Loadout.shortAmount(10_000_000))
        assertEquals("2147M", Loadout.shortAmount(Int.MAX_VALUE))
        assertEquals(0xFFFFFF00.toInt(), Loadout.amountColour(99_999))
        assertEquals(0xFFFFFFFF.toInt(), Loadout.amountColour(100_000))
        assertEquals(0xFF00FF80.toInt(), Loadout.amountColour(10_000_000))
    }

    @Test
    fun typedAmountsAreUnderstood() {
        assertEquals(250, Loadout.parseAmount("250"))
        assertEquals(1000, Loadout.parseAmount(" 1,000 "))
        assertEquals(15_000, Loadout.parseAmount("15k"))
        assertEquals(2_500_000, Loadout.parseAmount("2.5M"))
        assertNull(Loadout.parseAmount("0"))
        assertNull(Loadout.parseAmount("lots"))
        assertNull(Loadout.parseAmount(""))
        assertNull(Loadout.parseAmount("5b"))   // more than a stack can hold
    }

    @Test
    fun damagedValuesAreLeftOutRatherThanCrashing() {
        val l = Loadout.fromJson(JSONObject("""{"id":"2","name":"x","sb":9,"inv":[["oops"],null,[385,0]],"eq":{"head":"bad","weapon":[4151]}}"""))
        assertNull(l.spellbook)                         // there are only 4 spellbooks
        assertNull(l.inventory[0])
        assertEquals(Stack(385, 1), l.inventory[2])     // an amount below 1 counts as 1
        assertNull(l.equipment["head"])
        assertEquals(Stack(4151, 1), l.equipment["weapon"])
    }
}
