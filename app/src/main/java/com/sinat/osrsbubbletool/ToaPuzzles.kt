/*
 * Puzzle rules, the addition puzzle's answers and the Path of Het's answers adapted from the "Tombs of Amascut" RuneLite plugin
 * (github.com/LlemonDuck/tombs-of-amascut).
 *
 * Copyright (c) 2022, LlemonDuck
 * Copyright (c) 2022, TheStonedTurtle
 * Copyright (c) 2019, Ron Young <https://github.com/raiyni>
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

// The Path of Scabaras puzzles and the Path of Het's mirror puzzle in the Tombs of Amascut: the rules and the answers.
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

    // The tiles of the board tile t is on, and of the other board
    fun matchBoardOf(t: Int) = if (t < 9) 0 until 9 else 9 until 18
    fun otherMatchBoard(t: Int) = if (t < 9) 9 until 18 else 0 until 9

    // ---------------- Path of Het (the mirror puzzle before Akkha) ----------------
    // The room is a 21×21 square of floor, counted from its south-west corner (x east, y north). Across the
    // middle stand the caster statue (west), Het's seal and the shielded statue (east), each 3×3. Every 9 ticks
    // the caster fires a beam west; mirrors turn it, and it must reach the shielded statue. Smooth stone walls
    // stop the beam; cracked ones can be mined away. The plugin has the nine ways through the room that every
    // layout uses: each is just its mirrors (the beam goes straight between them).

    const val HET_SIZE = 21

    // A square of the room as one number (x * HET_SIZE + y), as every list of squares here and in HetReader keeps them
    fun hetSquare(x: Int, y: Int) = x * HET_SIZE + y

    // The room's floor, from the game's own walking map (the Shortest Path plugin's collision map, region 57,82;
    // square 0,0 is game tile 3670,5270): for each row y, south to north, the first and last floor square x. It's
    // a stepped circle, 21 squares across the middle and 4-5 at the north and south ends.
    private val HET_FLOOR_ROWS = arrayOf(
        8 to 12, 5 to 15, 3 to 17, 2 to 18, 2 to 18, 1 to 19, 1 to 19, 1 to 19, 0 to 20, 0 to 20, 0 to 20,
        0 to 20, 0 to 20, 1 to 19, 1 to 19, 1 to 19, 2 to 18, 2 to 18, 3 to 17, 5 to 15, 8 to 11
    )

    // The room's outline, traced from the OSRS Wiki's map of the room (maps.runescape.wiki, map -1, region 57,82):
    // straight runs and one-square diagonal wall pieces, as the room's outer wall is in the game, with the alcoves
    // to the north and south where the pickaxe statues stand. Corners (x, y) in squares, going round.
    val HET_OUTLINE: List<Pair<Float, Float>> = listOf(
        9 to 24, 12 to 24, 12 to 22, 13 to 21, 14 to 20, 16 to 20, 17 to 19, 18 to 19, 19 to 18, 19 to 17, 20 to 16,
        20 to 14, 21 to 13, 21 to 8, 20 to 7, 20 to 5, 19 to 4, 19 to 3, 18 to 2, 17 to 2, 16 to 1, 14 to 1, 13 to 0,
        12 to -1, 12 to -3, 9 to -3, 9 to -1, 8 to 0, 7 to 1, 5 to 1, 4 to 2, 3 to 2, 2 to 3, 2 to 4, 1 to 5, 1 to 7,
        0 to 8, 0 to 13, 1 to 14, 1 to 16, 2 to 17, 2 to 18, 3 to 19, 4 to 19, 5 to 20, 7 to 20, 8 to 21, 9 to 22
    ).map { (x, y) -> x.toFloat() to y.toFloat() }
    // The short walls across the alcoves' mouths, each side of the gap you look through (x from, x to, y)
    val HET_ALCOVE_WALLS = listOf(intArrayOf(8, 10, 21), intArrayOf(11, 13, 21), intArrayOf(8, 10, 0), intArrayOf(11, 13, 0))
    // How far the drawing reaches past the 21x21 squares on each side (the alcoves go 3 squares north and south)
    const val HET_DRAW_MARGIN = 3
    // The doors: on the west and east edges, rows 9-11 (the exit west, the way in east)
    const val HET_DOOR_FROM_Y = 9
    const val HET_DOOR_TO_Y = 12
    fun isHetFloor(x: Int, y: Int) = y in 0 until HET_SIZE && x in HET_FLOOR_ROWS[y].first..HET_FLOOR_ROWS[y].second
    const val HET_MIDDLE_Y = 9                    // the bottom row of the three statues (they cover rows 9-11)
    const val HET_CASTER_X = 6                    // the west column of each statue: caster 6-8, seal 9-11, target 12-14
    const val HET_SEAL_X = 9
    const val HET_TARGET_X = 12

    // Which way a mirror's shiny side faces. A beam coming in on that side leaves on the other one of its two
    // sides: a north-east mirror sends a beam going west up north, and one going south off east.
    enum class Facing { NORTH_EAST, SOUTH_EAST, SOUTH_WEST, NORTH_WEST }

    class HetMirror(val x: Int, val y: Int, val facing: Facing) {
        val square get() = hetSquare(x, y)
    }

    private fun m(x: Int, y: Int, f: Facing) = HetMirror(x, y, f)

    val HET_ANSWERS: List<List<HetMirror>> = listOf(
        listOf(m(5, 10, Facing.NORTH_EAST), m(5, 18, Facing.SOUTH_EAST), m(14, 18, Facing.SOUTH_WEST)),
        listOf(m(5, 10, Facing.SOUTH_EAST), m(5, 6, Facing.NORTH_EAST), m(18, 6, Facing.NORTH_WEST), m(18, 10, Facing.SOUTH_WEST)),
        listOf(m(4, 10, Facing.SOUTH_EAST), m(4, 2, Facing.NORTH_EAST), m(13, 2, Facing.NORTH_WEST)),
        listOf(m(3, 10, Facing.SOUTH_EAST), m(3, 2, Facing.NORTH_EAST), m(17, 2, Facing.NORTH_WEST), m(17, 11, Facing.SOUTH_WEST)),
        listOf(m(2, 10, Facing.NORTH_EAST), m(2, 17, Facing.SOUTH_EAST), m(13, 17, Facing.SOUTH_WEST)),
        listOf(m(3, 10, Facing.NORTH_EAST), m(3, 17, Facing.SOUTH_EAST), m(14, 17, Facing.SOUTH_WEST)),
        listOf(m(5, 10, Facing.NORTH_EAST), m(5, 13, Facing.SOUTH_EAST), m(12, 13, Facing.SOUTH_WEST)),
        listOf(m(4, 10, Facing.SOUTH_EAST), m(4, 3, Facing.NORTH_EAST), m(13, 3, Facing.NORTH_WEST)),
        listOf(m(5, 10, Facing.SOUTH_EAST), m(5, 3, Facing.NORTH_EAST), m(13, 3, Facing.NORTH_WEST))
    )

    // What's on a square of a room's walls (HetRoom.walls)
    const val HET_NO_WALL = 0
    const val HET_SMOOTH_WALL = 1
    const val HET_CRACKED_WALL = 2

    // A room layout seen in a raid: its stone walls, each a block of squares (x from, x to, y from, y to), smooth
    // ones and cracked ones (which can be mined), and the way through that fits it. (The fixed mirrors standing about
    // differ from raid to raid, so Watch reads them each time: HetReader.)
    // Read from the owner's screenshots (October 2026): every wall is one square thick, fitted by drawing the walls
    // as solid blocks over each picture of the room. A wall's end may be a square off (the ends of walls running away
    // from the camera are hard to see; every room was also seen from the north, which pins them); the ways' mirrors are exact.
    class HetRoom(val way: Int, smooth: List<IntArray>, cracked: List<IntArray>) {
        // Each wall (x from, x to, y from, y to), and whether it's usually cracked. Which walls are cracked changes from
        // raid to raid (the game's "break sets"), so the room reader looks at each one (HetReader).
        val blocks: List<IntArray> = smooth + cracked
        val usuallyCracked = BooleanArray(blocks.size) { it >= smooth.size }
        // what's on each square (hetSquare): HET_NO_WALL, HET_SMOOTH_WALL or HET_CRACKED_WALL
        val walls = wallsWith(usuallyCracked)

        // The squares with these walls cracked (one true/false for each of `blocks`)
        fun wallsWith(cracked: BooleanArray) = IntArray(HET_SIZE * HET_SIZE).also { w ->
            blocks.forEachIndexed { i, r ->
                for (x in r[0]..r[1]) for (y in r[2]..r[3]) if (isHetFloor(x, y)) w[hetSquare(x, y)] = if (cracked[i]) HET_CRACKED_WALL else HET_SMOOTH_WALL
            }
        }
    }

    private fun b(x1: Int, x2: Int, y1: Int, y2: Int) = intArrayOf(x1, x2, y1, y2)

    val HET_ROOMS: List<HetRoom> = listOf(
        // walls hugging the statues' south side and the near end; a cracked wall along their north side
        // (way 1 and way 7 both take 3 mirrors and 1 square mined; the plugin takes the first in its list)
        HetRoom(0,
            // (counted square by square on the owner's straight-down PC screenshot: the north-west corner is a west
            // wall 4 squares from the outer wall and a north wall on the same row as its north end, with a 1-square
            // gap between them; in that raid the walls at 17,9..13 and 12,2..5 were cracked: another crack pattern)
            smooth = listOf(b(8, 17, 8, 8), b(17, 17, 9, 13), b(4, 4, 11, 15), b(6, 10, 15, 15), b(11, 11, 14, 17),
                b(13, 13, 14, 17), b(12, 12, 2, 5)),
            // (the north one reaches the square just north of the seal's middle: the owner couldn't stand there)
            cracked = listOf(b(10, 15, 12, 12), b(15, 18, 16, 16))),
        // a smooth wall from the far wall to beside the caster; lots of cracked walls
        HetRoom(2,
            // (checked square by square on the owner's straight-down PC screenshot: every wall but two was right. The
            // one from the far wall stops a square short of it: 0,11 is the doorway's edge, open floor you can run
            // through (the owner, October 2026))
            smooth = listOf(b(1, 5, 11, 11), b(5, 5, 13, 17), b(12, 16, 13, 13), b(12, 12, 14, 18), b(12, 12, 5, 8)),
            cracked = listOf(b(9, 9, 12, 16), b(16, 16, 8, 12), b(5, 9, 6, 6), b(13, 17, 4, 4))),
        // smooth walls, a cracked one near the entrance and one from the far wall
        // (counted square by square on the owner's straight-down PC screenshot, which also agrees with the owner's
        // counts in the game. With these walls the best way is 5: 3 mirrors and nothing to mine, as the plugin
        // would pick; way 4, used before, needs a fixed mirror and a wall mined. The wall north-east of the seal,
        // 12,12..16, was cracked in that raid)
        HetRoom(4,
            smooth = listOf(b(8, 8, 4, 8), b(10, 14, 5, 5), b(12, 16, 8, 8), b(4, 8, 12, 12), b(6, 10, 15, 15),
                b(12, 12, 12, 16)),
            // (the one from the outer wall is 5 squares long: the owner counted it in the game)
            cracked = listOf(b(15, 19, 14, 14), b(1, 5, 7, 7)))
    )

    // How many cracked walls the beam crosses in this room (squares to mine), or -1 if it hits a smooth one
    fun hetMining(room: HetRoom, way: Int) = hetMining(room.walls, way)
    fun hetMining(walls: IntArray, way: Int): Int {
        var mine = 0
        for (s in hetBeam(HET_ANSWERS[way])) when (walls[s]) { HET_SMOOTH_WALL -> return -1; HET_CRACKED_WALL -> mine++ }
        return mine
    }

    // The best way through these walls, by the plugin's rules: no smooth wall in the way and at most 3 mirrors to
    // carry (the corners without a fixed mirror seen), then the fewest mirrors to carry, then the fewest walls to mine,
    // then the first in the list. -1 if none works.
    // `seen`: fixed mirrors spotted on screen this raid (HetReader), and `facing` which way the ones it could make out
    // face. A fixed mirror on the beam's path stops it (the plugin's rule: it can't be moved or turned), so ways past
    // one are left out. One on a way's corner works only if it faces the right way: then it's a mirror already in place
    // (one fewer to carry); facing wrong, that way is left out. If which way it faces isn't known, that way is left out
    // too, unless nothing else works.
    fun hetBestWay(walls: IntArray, seen: List<Int> = emptyList(), facing: Map<Int, Facing> = emptyMap()): Int {
        val clear = bestWayAvoiding(walls, seen, facing, guess = false)
        return if (clear >= 0 || seen.isEmpty()) clear else bestWayAvoiding(walls, seen, facing, guess = true)
    }

    // the same, leaving out ways that cross a seen fixed mirror on a straight part or one facing wrong on a corner
    // (and one whose facing isn't known on a corner, unless `guess`, when it's taken to face right)
    private fun bestWayAvoiding(walls: IntArray, seen: List<Int>, facing: Map<Int, Facing>, guess: Boolean): Int {
        var best = -1; var bestCarry = 0; var bestMine = 0
        for (way in HET_ANSWERS.indices) {
            val usable = hetBeam(HET_ANSWERS[way]).all { s ->
                if (s !in seen) return@all true
                val corner = HET_ANSWERS[way].firstOrNull { it.square == s } ?: return@all false
                facing[s]?.let { it == corner.facing } ?: guess
            }
            if (!usable) continue
            val mine = hetMining(walls, way)
            if (mine < 0) continue
            val carry = HET_ANSWERS[way].count { it.square !in seen }
            if (carry > 3) continue
            if (best < 0 || carry < bestCarry || (carry == bestCarry && mine < bestMine)) { best = way; bestCarry = carry; bestMine = mine }
        }
        return best
    }

    fun isHetTarget(x: Int, y: Int) = x in HET_TARGET_X until HET_TARGET_X + 3 && y in HET_MIDDLE_Y until HET_MIDDLE_Y + 3

    // The squares the beam crosses with these mirrors, in order from the caster (which fires from square 6,10
    // going west), up to the shielded statue. Each is a hetSquare. Empty if the beam never gets there.
    fun hetBeam(mirrors: List<HetMirror>): List<Int> {
        var x = HET_CASTER_X - 1; var y = HET_MIDDLE_Y + 1
        var dx = -1; var dy = 0
        val squares = mutableListOf<Int>()
        while (x in 0 until HET_SIZE && y in 0 until HET_SIZE && squares.size < HET_SIZE * HET_SIZE) {
            if (isHetTarget(x, y)) return squares
            squares.add(hetSquare(x, y))
            when (mirrors.firstOrNull { it.x == x && it.y == y }?.facing) {
                Facing.NORTH_EAST, Facing.SOUTH_WEST -> { val t = dx; dx = -dy; dy = -t }
                Facing.SOUTH_EAST, Facing.NORTH_WEST -> { val t = dx; dx = dy; dy = t }
                null -> {}
            }
            x += dx; y += dy
        }
        return emptyList()
    }
}
