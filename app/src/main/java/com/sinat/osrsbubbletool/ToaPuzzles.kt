/*
 * Puzzle rules and the addition puzzle's answers adapted from the "Tombs of Amascut" RuneLite plugin
 * (github.com/LlemonDuck/tombs-of-amascut).
 *
 * Copyright (c) 2022, LlemonDuck
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sinat.osrsbubbletool

// The Path of Scabaras puzzles in the Tombs of Amascut: the rules and the answers.
// Nothing here is Android: the tool's maps (ToaPuzzleTool) are filled in by tapping or by screen reading (ToaReader).
object ToaPuzzles {

    // ---------------- Symbols ----------------

    // The symbols carved on the puzzle floors. The addition puzzle uses the first nine, worth 1 to 9;
    // the matching puzzle uses the same ones but with a star instead of the triangle.
    enum class Symbol(val label: String, val value: Int) {
        LINE("Line", 1), KNIVES("Knives", 2), TRIANGLE("Triangle", 3), DIAMOND("Diamond", 4),
        HAND("Hand", 5), BIRD("Bird", 6), CROOK("Crook", 7), WIGGLE("Wiggle", 8), FOOT("Foot", 9),
        STAR("Star", 0)
    }

    val MATCHING_SYMBOLS = listOf(
        Symbol.LINE, Symbol.KNIVES, Symbol.CROOK, Symbol.DIAMOND, Symbol.HAND,
        Symbol.STAR, Symbol.BIRD, Symbol.WIGGLE, Symbol.FOOT
    )

    // ---------------- Light puzzle ----------------
    // Eight pressure plates in a square ring (the middle has none). Stepping on a plate switches it and
    // the two plates next to it in the ring. Plates are numbered left to right, top to bottom:
    //   0 1 2
    //   3 . 4
    //   5 6 7

    // Where each plate sits in the 3×3 square (row, column)
    fun lightCell(i: Int): Pair<Int, Int> { val cell = if (i > 3) i + 1 else i; return cell / 3 to cell % 3 }

    private val LIGHT_NEIGHBOURS = arrayOf(
        intArrayOf(1, 3), intArrayOf(0, 2), intArrayOf(1, 4), intArrayOf(0, 5),
        intArrayOf(2, 7), intArrayOf(3, 6), intArrayOf(5, 7), intArrayOf(4, 6)
    )

    // Which plates change when you step on the plates in `steps` (both are 8-bit masks)
    private fun lightEffect(steps: Int): Int {
        var changed = 0
        for (i in 0 until 8) if (steps shr i and 1 == 1) {
            changed = changed xor (1 shl i)
            for (n in LIGHT_NEIGHBOURS[i]) changed = changed xor (1 shl n)
        }
        return changed
    }

    // The plates to step on (in any order) so all eight end up lit. There's always exactly one answer.
    fun solveLights(lit: Int): Int = (0 until 256).first { lightEffect(it) xor lit == 0xFF }

    // ---------------- Addition puzzle ----------------
    // A 5×5 floor of symbols, the same in every raid. Every tile you walk on adds its symbol's value;
    // reach the tablet's number (20 to 45) exactly. Tiles are numbered from the north-west corner,
    // row by row, with north at the top.

    val ADDITION_GRID: List<Symbol> = listOf(
        9, 3, 7, 2, 9,
        7, 6, 4, 1, 1,
        8, 6, 3, 8, 5,
        6, 2, 7, 2, 4,
        1, 9, 5, 4, 3
    ).map { v -> Symbol.entries.first { it.value == v } }

    const val ADDITION_MIN = 20
    const val ADDITION_MAX = 45

    // The plugin's shortest walk for each number
    private val ADDITION_ANSWERS: Map<Int, Set<Int>> = mapOf(
        20 to setOf(5, 11, 17),
        21 to setOf(10, 11, 17),
        22 to setOf(10, 11, 12, 18, 24),
        23 to setOf(5, 6, 7, 8, 14),
        24 to setOf(5, 11, 17, 23),
        25 to setOf(10, 11, 12, 13),
        26 to setOf(9, 10, 11, 12, 13),
        27 to setOf(5, 6, 7, 8, 4),
        28 to setOf(0, 1, 7, 13, 19),
        29 to setOf(10, 11, 12, 13, 19),
        30 to setOf(10, 11, 12, 13, 14),
        31 to setOf(0, 6, 12, 13, 14),
        32 to setOf(2, 3, 4, 6, 10),
        33 to setOf(4, 5, 6, 7, 8, 9, 14),
        34 to setOf(10, 11, 12, 13, 14, 19),
        35 to setOf(9, 10, 11, 12, 13, 14, 19),
        36 to setOf(0, 1, 2, 3, 4, 9, 14),
        37 to setOf(10, 11, 12, 13, 14, 19, 24),
        38 to setOf(0, 5, 6, 10, 12, 18, 24),
        39 to setOf(2, 3, 4, 7, 10, 11, 12),
        40 to setOf(4, 9, 10, 11, 12, 13, 14),
        41 to setOf(0, 4, 6, 9, 12, 13, 14),
        42 to setOf(0, 5, 9, 10, 11, 12, 13),
        43 to setOf(0, 1, 5, 7, 10, 13, 19),
        44 to setOf(0, 5, 10, 11, 14, 17, 18),
        45 to setOf(0, 1, 2, 3, 4, 5, 10)
    )

    fun additionTotal(tiles: Collection<Int>) = tiles.sumOf { ADDITION_GRID[it].value }

    // The tiles still to walk on, given the ones already lit. If you've stepped off the plugin's walk, this
    // finds the fewest extra tiles that touch the ones you've lit (so you can walk to them) and make up the
    // rest. Null if nothing fits (you've gone over, or no walk of up to 6 more tiles adds up).
    fun solveAddition(target: Int, stepped: Set<Int>): Set<Int>? {
        val best = ADDITION_ANSWERS[target] ?: return null
        if (best.containsAll(stepped)) return best - stepped
        val left = target - additionTotal(stepped)
        if (left < 0) return null
        if (left == 0) return emptySet()

        val start = stepped.fold(0L) { m, i -> m or (1L shl i) }
        // Grow the lit area one touching tile at a time, keeping only sums that don't go over
        var level = hashMapOf(start to 0)
        repeat(6) {
            val next = HashMap<Long, Int>()
            for ((mask, sum) in level) {
                for (i in 0 until 25) {
                    if (mask shr i and 1L == 1L || !touches(mask, i)) continue
                    val s = sum + ADDITION_GRID[i].value
                    if (s > left) continue
                    val m = mask or (1L shl i)
                    if (s == left) return (0 until 25).filter { m shr it and 1L == 1L && it !in stepped }.toSet()
                    next[m] = s
                }
            }
            level = next
        }
        return null
    }

    // Is tile i next to (or diagonal from) any tile in the mask?
    private fun touches(mask: Long, i: Int): Boolean {
        val r = i / 5; val c = i % 5
        for (dr in -1..1) for (dc in -1..1) {
            val rr = r + dr; val cc = c + dc
            if ((dr != 0 || dc != 0) && rr in 0..4 && cc in 0..4 && mask shr (rr * 5 + cc) and 1L == 1L) return true
        }
        return false
    }

    // ---------------- Sequence puzzle ----------------
    // Nine tiles in a diamond. Pressing the button lights five of them one after another; step on them in
    // the same order. Where each tile sits on a 5×5 grid (row, column), top to bottom:
    val SEQUENCE_CELLS = listOf(0 to 2, 1 to 1, 1 to 3, 2 to 0, 2 to 2, 2 to 4, 3 to 1, 3 to 3, 4 to 2)
    const val SEQUENCE_LENGTH = 5

    // ---------------- Obelisk puzzle ----------------
    // Hit the obelisks in the right order. A right one lights up; a wrong one drops rocks and puts every
    // obelisk out, so you start again. The order stays the same, so remember what you've found.
    // There are three on each long wall. Obelisks are numbered along the top wall as you see it facing east
    // (0-2), then along the bottom wall (3-5). A lit obelisk turns light pink.
    const val OBELISKS = 6

    // The obelisks still worth trying next
    fun obeliskChoices(order: List<Int>, misses: Set<Int>) = (0 until OBELISKS).filter { it !in order && it !in misses }

    // ---------------- Matching puzzle ----------------
    // Two 3×3 boards (tiles 0-8 on the left, 9-17 on the right). Each board has each symbol once; step on
    // the two tiles with the same symbol to match them. (Watch's memory of the boards is ToaReader.MatchMemory.)
}
