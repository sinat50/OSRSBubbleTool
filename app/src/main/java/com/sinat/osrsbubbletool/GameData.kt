package com.sinat.osrsbubbletool

import kotlin.random.Random

// The rules behind the Game Room games. No Android parts here, so it can be tested on a computer.

// ---------------- 2048 ----------------

// The 2048 board: swipe to slide every tile one way; two tiles with the same number join into one.
class Game2048(val size: Int = 4, private val random: Random = Random.Default) {

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    // What a move did, so it can be animated: every tile's slide, which squares merged, and the new tile
    class Slide(val from: Int, val to: Int, val value: Int)
    class Move(val slides: List<Slide>, val merged: Set<Int>, val spawned: Int, val gained: Int)

    var cells = IntArray(size * size)   // 0 = empty, otherwise 2, 4, 8…
        private set
    var score = 0
        private set

    fun newGame() {
        cells = IntArray(size * size)
        score = 0
        spawn(); spawn()
    }

    fun restore(saved: IntArray, savedScore: Int) {
        if (saved.size != size * size) { newGame(); return }
        cells = saved.copyOf()
        score = savedScore
        if (cells.all { it == 0 }) newGame()
    }

    val best: Int get() = cells.maxOrNull() ?: 0

    // Slides the tiles; null if nothing could move that way
    fun move(dir: Dir): Move? {
        val next = IntArray(size * size)
        val slides = ArrayList<Slide>()
        val merged = HashSet<Int>()
        var gained = 0
        for (line in 0 until size) {
            // the squares of this row/column, starting from the side the tiles slide towards
            val idx = IntArray(size) { i ->
                when (dir) {
                    Dir.LEFT -> line * size + i
                    Dir.RIGHT -> line * size + (size - 1 - i)
                    Dir.UP -> i * size + line
                    Dir.DOWN -> (size - 1 - i) * size + line
                }
            }
            var target = 0              // where the next tile lands
            var canMerge = false        // the tile at target-1 can still take a merge
            for (i in 0 until size) {
                val v = cells[idx[i]]
                if (v == 0) continue
                if (canMerge && next[idx[target - 1]] == v) {
                    val to = idx[target - 1]
                    next[to] = v * 2
                    gained += v * 2
                    merged.add(to)
                    slides.add(Slide(idx[i], to, v))
                    canMerge = false
                } else {
                    val to = idx[target]
                    next[to] = v
                    slides.add(Slide(idx[i], to, v))
                    target++
                    canMerge = true
                }
            }
        }
        if (next.contentEquals(cells)) return null
        cells = next
        score += gained
        val spawned = spawn()
        return Move(slides, merged, spawned, gained)
    }

    fun canMove(): Boolean {
        for (i in cells.indices) {
            if (cells[i] == 0) return true
            val x = i % size
            val y = i / size
            if (x + 1 < size && cells[i + 1] == cells[i]) return true
            if (y + 1 < size && cells[i + size] == cells[i]) return true
        }
        return false
    }

    // Puts a 2 (or sometimes a 4) in a random empty square; returns where, or -1 if full
    private fun spawn(): Int {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return -1
        val at = empty[random.nextInt(empty.size)]
        cells[at] = if (random.nextInt(10) == 0) 4 else 2
        return at
    }
}
