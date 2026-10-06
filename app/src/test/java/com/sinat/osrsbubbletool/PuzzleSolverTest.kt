package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

// Checks the Puzzle Box solver on the PC: scrambles boards, solves them, and plays the moves to make
// sure they really end on the finished picture.
class PuzzleSolverTest {

    // Makes a scramble the way the game does: many random slides from the finished board
    private fun scramble(random: Random, slides: Int): IntArray {
        val b = IntArray(25) { it }
        var blank = 24
        repeat(slides) {
            val r = blank / 5
            val c = blank % 5
            val options = buildList {
                if (r > 0) add(blank - 5); if (r < 4) add(blank + 5)
                if (c > 0) add(blank - 1); if (c < 4) add(blank + 1)
            }
            val next = options[random.nextInt(options.size)]
            b[blank] = b[next]; b[next] = 24; blank = next
        }
        return b
    }

    // Plays the moves (each the position of the tile to tap) and returns the board at the end
    private fun play(start: IntArray, moves: List<Int>): IntArray {
        val b = start.copyOf()
        for (m in moves) {
            val blank = b.indexOf(24)
            assertEquals("each move must be next to the empty space", 1,
                kotlin.math.abs(m / 5 - blank / 5) + kotlin.math.abs(m % 5 - blank % 5))
            b[blank] = b[m]; b[m] = 24
        }
        return b
    }

    @Test
    fun solvesScrambles() {
        val random = Random(7)
        val started = System.currentTimeMillis()
        var total = 0
        for (i in 0 until 20) {
            val board = scramble(random, if (i < 5) 30 else 2000)   // a few nearly solved (re-plans), the rest fresh
            val moves = PuzzleSolver.solve(board)
            assertNotNull("no solution for scramble $i", moves)
            assertTrue("scramble $i not solved", play(board, moves!!).contentEquals(IntArray(25) { it }))
            total += moves.size
        }
        println("20 puzzles solved, ${total / 20} moves on average, ${System.currentTimeMillis() - started} ms")
    }
}
