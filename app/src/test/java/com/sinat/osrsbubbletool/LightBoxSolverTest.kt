package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// The Light Box solver on the PC: 25 bulbs (one bit each), 8 buttons A-H, each flipping a set of bulbs.
class LightBoxSolverTest {

    private val allOn = (1 shl 25) - 1

    @Test
    fun oneButtonThatFlipsExactlyTheDarkBulbs() {
        // bulbs 0-2 are dark; button C (2) flips exactly those three
        val state = allOn and 0b111.inv()
        val effects = arrayOfNulls<Int>(8).also { it[0] = 0b1000; it[2] = 0b111 }
        assertEquals(listOf(2), LightBoxReader.solve(state, effects))
    }

    @Test
    fun picksTheFewestButtons() {
        // bulbs 0-3 dark. A+B+C together flip them too (0b0011, 0b0100, 0b1000), but D alone does it
        val state = allOn and 0b1111.inv()
        val effects = arrayOfNulls<Int>(8).also { it[0] = 0b0011; it[1] = 0b0100; it[2] = 0b1000; it[3] = 0b1111 }
        assertEquals(listOf(3), LightBoxReader.solve(state, effects))
    }

    @Test
    fun overlappingButtonsCancelOut() {
        // bulbs 0 and 2 dark. A flips 0-1, B flips 1-2: pressing both flips bulb 1 twice, so only 0 and 2 change
        val state = allOn and 0b101.inv()
        val effects = arrayOfNulls<Int>(8).also { it[0] = 0b011; it[1] = 0b110 }
        assertEquals(listOf(0, 1), LightBoxReader.solve(state, effects))
    }

    @Test
    fun noAnswerWhileTheNeededButtonIsUnknown() {
        // bulb 4 is dark and no known button reaches it: the app must keep learning buttons
        val state = allOn and (1 shl 4).inv()
        val effects = arrayOfNulls<Int>(8).also { it[0] = 0b1; it[1] = 0b10 }
        assertEquals(null, LightBoxReader.solve(state, effects))
    }

    @Test
    fun alreadySolvedNeedsNoPresses() {
        assertEquals(emptyList<Int>(), LightBoxReader.solve(allOn, arrayOfNulls(8)))
    }
}
