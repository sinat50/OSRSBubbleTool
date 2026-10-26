package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- Path of Het ----

    private fun square(x: Int, y: Int) = x * ToaPuzzles.HET_SIZE + y

    @Test
    fun hetShortestWayIsThePluginsList() {
        // the plugin's seventh way, square by square: west from the caster, up at 5,10, east along row 13,
        // down at 12,13 into the shielded statue (which starts at 12,11)
        val want = listOf(square(5, 10), square(5, 11), square(5, 12), square(5, 13)) +
            (6..12).map { square(it, 13) } + square(12, 12)
        assertEquals(want, ToaPuzzles.hetBeam(ToaPuzzles.HET_ANSWERS[6]))
    }

    @Test
    fun hetEveryWayReachesTheStatueInThePluginsNumberOfSquares() {
        // the lengths of the plugin's nine lists
        assertEquals(listOf(24, 25, 25, 36, 27, 26, 12, 23, 21), ToaPuzzles.HET_ANSWERS.map { ToaPuzzles.hetBeam(it).size })
    }

    @Test
    fun hetEachRoomsWayMissesItsSmoothWalls() {
        // the cracked walls to mine, counted on the screenshots: the first room's way crosses one, the second's
        // crosses the one on the shielded statue's south side, the third's (way 5) crosses none
        assertEquals(listOf(1, 1, 0), ToaPuzzles.HET_ROOMS.map { ToaPuzzles.hetMining(it, it.way) })
    }

    @Test
    fun hetNoRoomNeedsMoreThanThreeMirrorsMoved() {
        // only three mirrors can be picked up (the plugin's rule), so every room's way has three; a way with four
        // corners would need a fixed mirror on one
        assertEquals(listOf(3, 3, 3), ToaPuzzles.HET_ROOMS.map { ToaPuzzles.hetMirrorsToMove(it, it.way) })
        assertEquals(4, ToaPuzzles.hetMirrorsToMove(null, 3))   // way 4 has four corners
    }

    @Test
    fun hetEveryWayStaysOnTheFloor() {
        // the room's floor is from the game's walking map; every square of every way (the plugin's) must be on it
        for (way in ToaPuzzles.HET_ANSWERS) for (sq in ToaPuzzles.hetBeam(way))
            assertTrue("$sq", ToaPuzzles.isHetFloor(sq / ToaPuzzles.HET_SIZE, sq % ToaPuzzles.HET_SIZE))
        // the floor is 21 squares across the middle and 5 at the south end
        assertEquals(0 to 20, ToaPuzzles.hetFloorRow(10))
        assertEquals(8 to 12, ToaPuzzles.hetFloorRow(0))
    }

    @Test
    fun hetEachRoomsWayIsWhatThePluginsRulesPick() {
        // fewest mirrors to carry (at most 3), then fewest walls to mine, then the first in the plugin's list
        for (room in ToaPuzzles.HET_ROOMS) assertEquals(room.way, ToaPuzzles.hetBestWay(room.walls, room.fixed))
    }

    @Test
    fun hetCracksDecideWhichWaysAreOpen() {
        // the first room's ways both mine the cracked wall north of the statues: with every wall smooth, none is open;
        // with every wall cracked, way 1 still wins (the fewest to mine, then first in the list)
        val room = ToaPuzzles.HET_ROOMS[0]
        assertEquals(-1, ToaPuzzles.hetBestWay(room.wallsWith(BooleanArray(room.blocks.size) { false })))
        assertEquals(0, ToaPuzzles.hetBestWay(room.wallsWith(BooleanArray(room.blocks.size) { true })))
    }

    @Test
    fun hetWaysGoRoundSeenFixedMirrors() {
        // an empty room: every way is open and none needs mining, so way 1 wins (3 to carry, first in the list)
        val open = IntArray(ToaPuzzles.HET_SIZE * ToaPuzzles.HET_SIZE)
        assertEquals(0, ToaPuzzles.hetBestWay(open))
        // a fixed mirror at 5,13 stands on way 1's run north; way 2 needs 4 mirrors, so way 3 (4,10 -> 4,2 -> 13,2)
        assertEquals(2, ToaPuzzles.hetBestWay(open, seen = listOf(5 * 21 + 13)))
        // one at 5,10, the beam's first square, is on every way: the ways turning there could use it if it faces
        // right, and way 1 then needs only 2 mirrors carried (way 7 too, but way 1 comes first)
        assertEquals(0, ToaPuzzles.hetBestWay(open, seen = listOf(5 * 21 + 10)))
    }

    @Test
    fun hetWaysUseFixedMirrorsFacingRight() {
        val open = IntArray(ToaPuzzles.HET_SIZE * ToaPuzzles.HET_SIZE)
        // a fixed mirror at 5,13 facing south-east is way 7's middle corner, already in place: only 2 to carry, the
        // fewest (way 1 runs past it)
        assertEquals(6, ToaPuzzles.hetBestWay(open, seen = listOf(5 * 21 + 13), facing = mapOf(5 * 21 + 13 to ToaPuzzles.Facing.SOUTH_EAST)))
        // facing north-west it can't be used: way 3 goes round it, as when its facing isn't known
        assertEquals(2, ToaPuzzles.hetBestWay(open, seen = listOf(5 * 21 + 13), facing = mapOf(5 * 21 + 13 to ToaPuzzles.Facing.NORTH_WEST)))
        // one at 5,10 facing south-east: only ways 2 and 9 turn the beam that way there; way 9 then needs 2 carried,
        // way 2 three
        assertEquals(8, ToaPuzzles.hetBestWay(open, seen = listOf(5 * 21 + 10), facing = mapOf(5 * 21 + 10 to ToaPuzzles.Facing.SOUTH_EAST)))
    }

    @Test
    fun hetNoMirrorsMeansTheBeamNeverArrives() {
        // straight west from the caster and off the floor
        assertEquals(emptyList<Int>(), ToaPuzzles.hetBeam(emptyList()))
    }
}
