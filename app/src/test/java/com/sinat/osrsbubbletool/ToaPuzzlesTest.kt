package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// The ToA Puzzle Helper's answers on the PC (Path of Scabaras puzzles).
class ToaPuzzlesTest {

    // ---- Light puzzle ----
    // Plates as the app numbers them:   0 1 2
    //                                   3 . 4
    //                                   5 6 7
    // Going round the ring: 0, 1, 2, 4, 7, 6, 5, 3. Stepping on a plate switches it and the plate on each side.
    private val ring = intArrayOf(0, 1, 2, 4, 7, 6, 5, 3)

    private fun stepOn(lit: Int, steps: Int): Int {
        var plates = lit
        for (k in 0 until 8) if (steps shr ring[k] and 1 == 1) {
            for (d in -1..1) plates = plates xor (1 shl ring[(k + d + 8) % 8])
        }
        return plates
    }

    @Test
    fun lightsStepOnThePlateBetweenTwoDarkNeighbours() {
        // plates 0, 1 and 3 are dark (plate 0 and its two neighbours): stepping on plate 0 alone lights them
        val lit = 0xFF and (0b1011).inv()
        assertEquals(0b1, ToaPuzzles.solveLights(lit))
    }

    @Test
    fun lightsEveryStartingPatternEndsAllLit() {
        for (lit in 0 until 256) {
            assertEquals("starting from lit plates $lit", 0xFF, stepOn(lit, ToaPuzzles.solveLights(lit)))
        }
    }

    // ---- Addition puzzle ----
    // The floor (the same every raid), tiles numbered row by row from the north-west corner:
    private val floor = intArrayOf(
        9, 3, 7, 2, 9,
        7, 6, 4, 1, 1,
        8, 6, 3, 8, 5,
        6, 2, 7, 2, 4,
        1, 9, 5, 4, 3
    )
    private fun total(tiles: Collection<Int>) = tiles.sumOf { floor[it] }

    // Every tile in the set touches another one (side or corner), so they can be walked as one group
    private fun joinedUp(tiles: Set<Int>): Boolean {
        if (tiles.isEmpty()) return true
        val reached = mutableSetOf(tiles.first())
        var grew = true
        while (grew) {
            grew = false
            for (t in tiles) if (t !in reached && reached.any { r -> maxOf(kotlin.math.abs(r / 5 - t / 5), kotlin.math.abs(r % 5 - t % 5)) == 1 }) {
                reached.add(t); grew = true
            }
        }
        return reached.size == tiles.size
    }

    @Test
    fun additionTwentyIsThePluginsWalk() {
        // tiles 5, 11 and 17: 7 + 6 + 7
        assertEquals(setOf(5, 11, 17), ToaPuzzles.solveAddition(20, emptySet()))
    }

    @Test
    fun additionEveryNumberHasAJoinedUpWalkThatAddsUp() {
        for (target in ToaPuzzles.ADDITION_MIN..ToaPuzzles.ADDITION_MAX) {
            val walk = ToaPuzzles.solveAddition(target, emptySet())!!
            assertEquals("the walk for $target adds up", target, total(walk))
            assertEquals("the walk for $target is joined up", true, joinedUp(walk))
        }
    }

    @Test
    fun additionOnThePlannedWalkShowsWhatsLeft() {
        assertEquals(setOf(11, 17), ToaPuzzles.solveAddition(20, setOf(5)))
    }

    @Test
    fun additionOffTheWalkFindsJoinedUpTilesThatMakeUpTheRest() {
        // stood on tile 0 (worth 9) instead: 11 more needed, from tiles joined up with it
        val stepped = setOf(0)
        val rest = ToaPuzzles.solveAddition(20, stepped)!!
        assertEquals(20, total(stepped + rest))
        assertEquals(true, joinedUp(stepped + rest))
    }

    @Test
    fun additionNumberReachedNeedsNothingMore() {
        // off the planned walk, but tiles 0, 1, 2 and 8 already make 9 + 3 + 7 + 1 = 20
        assertEquals(emptySet<Int>(), ToaPuzzles.solveAddition(20, setOf(0, 1, 2, 8)))
    }

    @Test
    fun additionGoneOverHasNoAnswer() {
        // 9 + 9 + 9 = 27, already over 20
        assertEquals(null, ToaPuzzles.solveAddition(20, setOf(0, 4, 21)))
    }

    // ---- Obelisk puzzle ----

    @Test
    fun obelisksAlreadyFoundOrWrongAreNotOfferedAgain() {
        // six obelisks: 4 then 1 found in order so far, 0 was tried next and was wrong
        assertEquals(listOf(2, 3, 5), ToaPuzzles.obeliskChoices(listOf(4, 1), setOf(0)))
    }
}
