package com.sinat.osrsbubbletool

import java.util.PriorityQueue
import kotlin.math.abs

// Works out a short list of moves that solves a 5 x 5 sliding puzzle.
// A board lists which tile is at each position (0-24, left to right, top to bottom);
// tile 24 is the empty space. Each move is the position of the tile to tap.
object PuzzleSolver {

    private const val N = 5
    private const val SIZE = N * N
    private const val EMPTY = SIZE - 1

    private const val IDA_NODE_LIMIT = 3_000_000L   // how hard to try for the shortest solution
    private const val ASTAR_LIMIT = 400_000         // how hard to try in each quicker fallback

    // For each position, the positions next to it
    private val neighbours: Array<IntArray> = Array(SIZE) { p ->
        val r = p / N
        val c = p % N
        val list = ArrayList<Int>()
        if (r > 0) list.add(p - N)
        if (r < N - 1) list.add(p + N)
        if (c > 0) list.add(p - 1)
        if (c < N - 1) list.add(p + 1)
        list.toIntArray()
    }

    // Returns the positions to tap in order, or null if no solution was found
    fun solve(board: IntArray): List<Int>? {
        if (board.size != SIZE || !TileMatcher.isSolvable(board)) return null
        if (heuristic(board) == 0) return emptyList()
        return idaStar(board)
            ?: weightedAStar(board, 2.0)
            ?: weightedAStar(board, 5.0)
    }

    // Estimated moves left: how far each tile is from home, plus extra for tiles
    // in their home row or column but in the wrong order (they must step around each other)
    private fun heuristic(b: IntArray): Int {
        var h = 0
        for (p in 0 until SIZE) {
            val t = b[p]
            if (t == EMPTY) continue
            h += abs(p / N - t / N) + abs(p % N - t % N)
        }
        for (r in 0 until N) {
            for (c1 in 0 until N) {
                val t1 = b[r * N + c1]
                if (t1 == EMPTY || t1 / N != r) continue
                for (c2 in c1 + 1 until N) {
                    val t2 = b[r * N + c2]
                    if (t2 != EMPTY && t2 / N == r && t2 < t1) h += 2
                }
            }
        }
        for (c in 0 until N) {
            for (r1 in 0 until N) {
                val t1 = b[r1 * N + c]
                if (t1 == EMPTY || t1 % N != c) continue
                for (r2 in r1 + 1 until N) {
                    val t2 = b[r2 * N + c]
                    if (t2 != EMPTY && t2 % N == c && t2 < t1) h += 2
                }
            }
        }
        return h
    }

    // ---------------- Search 1: shortest route, with a limit on effort ----------------

    private fun idaStar(start: IntArray): List<Int>? {
        val b = start.copyOf()
        var blank = b.indexOf(EMPTY)
        val path = ArrayList<Int>()
        var nodes = 0L
        var aborted = false
        var bound = heuristic(b)

        fun search(g: Int, previous: Int): Int {
            nodes++
            if (nodes > IDA_NODE_LIMIT) { aborted = true; return Int.MAX_VALUE }
            val h = heuristic(b)
            if (h == 0) return -1                    // solved
            val f = g + h
            if (f > bound) return f
            var smallest = Int.MAX_VALUE
            for (next in neighbours[blank]) {
                if (next == previous) continue       // don't undo the last move
                val old = blank
                b[old] = b[next]
                b[next] = EMPTY
                blank = next
                path.add(next)

                val result = search(g + 1, old)
                if (result == -1) return -1

                path.removeAt(path.size - 1)
                blank = old
                b[next] = b[old]
                b[old] = EMPTY
                if (aborted) return Int.MAX_VALUE
                if (result < smallest) smallest = result
            }
            return smallest
        }

        while (true) {
            val result = search(0, -1)
            if (result == -1) return ArrayList(path)
            if (aborted || result == Int.MAX_VALUE) return null
            bound = result
        }
    }

    // ---------------- Search 2: quicker, near-shortest route ----------------

    private class Node(
        val board: IntArray,
        val blank: Int,
        val g: Int,
        val f: Double,
        val parent: Node?,
        val move: Int
    )

    private data class Key(val a: Long, val b: Long)

    // Packs a board into two numbers so visited boards can be remembered cheaply
    private fun key(b: IntArray): Key {
        var a = 0L
        var c = 0L
        for (i in 0 until 12) a = (a shl 5) or b[i].toLong()
        for (i in 12 until 24) c = (c shl 5) or b[i].toLong()
        return Key(a, c)
    }

    private fun weightedAStar(start: IntArray, weight: Double): List<Int>? {
        val open = PriorityQueue<Node>(compareBy { it.f })
        val visited = HashSet<Key>()
        open.add(Node(start, start.indexOf(EMPTY), 0, weight * heuristic(start), null, -1))
        var expanded = 0

        while (open.isNotEmpty()) {
            val node = open.poll() ?: break
            if (heuristic(node.board) == 0) {
                val moves = ArrayList<Int>()
                var n: Node = node
                while (true) {
                    val parent = n.parent ?: break
                    moves.add(n.move)
                    n = parent
                }
                moves.reverse()
                return moves
            }
            if (!visited.add(key(node.board))) continue
            if (++expanded > ASTAR_LIMIT) return null

            for (next in neighbours[node.blank]) {
                if (node.parent != null && next == node.parent.blank) continue
                val nb = node.board.copyOf()
                nb[node.blank] = nb[next]
                nb[next] = EMPTY
                if (key(nb) in visited) continue
                val g = node.g + 1
                open.add(Node(nb, next, g, g + weight * heuristic(nb), node, next))
            }
        }
        return null
    }
}
