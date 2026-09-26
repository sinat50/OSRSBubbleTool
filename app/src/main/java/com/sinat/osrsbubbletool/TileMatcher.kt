package com.sinat.osrsbubbletool

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

// Works out which puzzle is on screen and where each of its tiles belongs.
object TileMatcher {

    const val GRID = 5
    const val TILES = GRID * GRID
    const val EMPTY = TILES - 1        // the empty space belongs in the bottom-right corner

    private const val SAMPLE = 8       // each tile is shrunk to 8 x 8 colours for comparing
    private const val INNER = 0.7f     // only the middle 70% of each tile is compared (ignores edges)

    // board[position] = the tile sitting at that position (positions and tiles are numbered
    // left to right, top to bottom, 0-24). Tile 24 is the empty space.
    class Result(
        val puzzleName: String,
        val board: IntArray,
        val difference: Float,         // average colour difference of the best puzzle (lower = better)
        val runnerUpName: String,
        val runnerUpDifference: Float,
        val solvable: Boolean,
        val correctedPositions: List<Int>  // tiles that were swapped to make the layout solvable
    )

    // Reference features are worked out once per puzzle and kept
    private val referenceCache = mutableMapOf<String, Array<FloatArray>>()

    // How close a square must look to the empty space to count as it
    private const val EMPTY_MAX_DIFFERENCE = 25f

    // Finds which position (0-24) the empty space is in, or null if none looks empty
    // (for example, while a tile is halfway through sliding). Used to follow your moves.
    fun findEmpty(scan: Bitmap, puzzleName: String): Int? {
        val reference = referenceCache[puzzleName] ?: return null
        val scanned = features(scan)
        var best = -1
        var bestDiff = Float.MAX_VALUE
        for (pos in 0 until TILES) {
            val d = difference(scanned[pos], reference[EMPTY])
            if (d < bestDiff) {
                bestDiff = d
                best = pos
            }
        }
        return if (bestDiff < EMPTY_MAX_DIFFERENCE) best else null
    }

    fun identify(scan: Bitmap, puzzles: List<PuzzleReferences.Puzzle>): Result? {
        if (puzzles.isEmpty()) return null
        val scanned = features(scan)

        var bestName = ""
        var bestBoard = IntArray(TILES)
        var bestCost: Array<DoubleArray>? = null
        var bestDiff = Float.MAX_VALUE
        var secondName = ""
        var secondDiff = Float.MAX_VALUE

        for (puzzle in puzzles) {
            val reference = referenceCache.getOrPut(puzzle.name) { features(tileArea(puzzle.image)) }
            val cost = Array(TILES) { pos ->
                DoubleArray(TILES) { tile -> difference(scanned[pos], reference[tile]).toDouble() }
            }
            val board = assign(cost)
            var total = 0.0
            for (pos in 0 until TILES) total += cost[pos][board[pos]]
            val average = (total / TILES).toFloat()

            if (average < bestDiff) {
                secondName = bestName
                secondDiff = bestDiff
                bestName = puzzle.name
                bestBoard = board
                bestCost = cost
                bestDiff = average
            } else if (average < secondDiff) {
                secondName = puzzle.name
                secondDiff = average
            }
        }

        // An unsolvable reading means two tiles were mixed up. Swap the pair whose
        // swap costs the least; that is almost always two similar-looking tiles.
        var corrected = emptyList<Int>()
        if (!isSolvable(bestBoard) && bestCost != null) {
            val fix = cheapestSwap(bestBoard, bestCost)
            if (fix != null) {
                val (a, b) = fix
                val t = bestBoard[a]
                bestBoard[a] = bestBoard[b]
                bestBoard[b] = t
                corrected = listOf(a, b)
            }
        }

        return Result(bestName, bestBoard, bestDiff, secondName, secondDiff,
            isSolvable(bestBoard), corrected)
    }

    // Finds the two positions (neither holding the empty space) whose tiles can be swapped
    // with the smallest increase in difference. Swapping two tiles always flips solvability.
    private fun cheapestSwap(board: IntArray, cost: Array<DoubleArray>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestIncrease = Double.MAX_VALUE
        for (a in 0 until TILES) {
            if (board[a] == EMPTY) continue
            for (b in a + 1 until TILES) {
                if (board[b] == EMPTY) continue
                val increase = cost[a][board[b]] + cost[b][board[a]] - cost[a][board[a]] - cost[b][board[b]]
                if (increase < bestIncrease) {
                    bestIncrease = increase
                    best = a to b
                }
            }
        }
        return best
    }

    // The wiki pictures include the puzzle window's brown frame around the tiles.
    // This finds the inner edge of the frame on each side (the strongest straight edge
    // near that side) and returns just the tile area.
    private fun tileArea(image: Bitmap): Bitmap {
        val w = image.width
        val h = image.height
        if (w < 20 || h < 20) return image
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h) { i ->
            val c = pixels[i]
            (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
        }
        fun columnEdge(x: Int): Int {
            var sum = 0
            for (y in 0 until h) sum += abs(lum[y * w + x] - lum[y * w + x - 1])
            return sum
        }
        fun rowEdge(y: Int): Int {
            var sum = 0
            for (x in 0 until w) sum += abs(lum[y * w + x] - lum[(y - 1) * w + x])
            return sum
        }
        fun strongest(from: Int, to: Int, edge: (Int) -> Int): Int {
            var bestAt = from
            var bestValue = -1
            for (i in from..to) {
                val v = edge(i)
                if (v > bestValue) { bestValue = v; bestAt = i }
            }
            return bestAt
        }

        val left = strongest(max(1, (w * 0.02f).toInt()), (w * 0.15f).toInt(), ::columnEdge)
        val right = strongest((w * 0.85f).toInt(), (w * 0.98f).toInt(), ::columnEdge)
        val top = strongest(max(1, (h * 0.02f).toInt()), (h * 0.15f).toInt(), ::rowEdge)
        val bottom = strongest((h * 0.85f).toInt(), (h * 0.98f).toInt(), ::rowEdge)

        if (right - left < w / 2 || bottom - top < h / 2) return image
        return Bitmap.createBitmap(image, left, top, right - left, bottom - top)
    }

    // Shrinks each of the 25 tiles to an 8 x 8 grid of average colours
    private fun features(image: Bitmap): Array<FloatArray> {
        val w = image.width
        val h = image.height
        val pixels = IntArray(w * h)
        image.getPixels(pixels, 0, w, 0, 0, w, h)
        val tileW = w / GRID.toFloat()
        val tileH = h / GRID.toFloat()

        return Array(TILES) { tile ->
            val x0 = (tile % GRID) * tileW + tileW * (1 - INNER) / 2
            val y0 = (tile / GRID) * tileH + tileH * (1 - INNER) / 2
            val innerW = tileW * INNER
            val innerH = tileH * INNER
            val result = FloatArray(SAMPLE * SAMPLE * 3)

            for (cy in 0 until SAMPLE) {
                val sy0 = (y0 + innerH * cy / SAMPLE).toInt().coerceIn(0, h - 1)
                val sy1 = max(sy0 + 1, (y0 + innerH * (cy + 1) / SAMPLE).toInt()).coerceAtMost(h)
                for (cx in 0 until SAMPLE) {
                    val sx0 = (x0 + innerW * cx / SAMPLE).toInt().coerceIn(0, w - 1)
                    val sx1 = max(sx0 + 1, (x0 + innerW * (cx + 1) / SAMPLE).toInt()).coerceAtMost(w)
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    var count = 0
                    for (y in sy0 until sy1) {
                        for (x in sx0 until sx1) {
                            val c = pixels[y * w + x]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                    val i = (cy * SAMPLE + cx) * 3
                    val n = max(1, count).toFloat()
                    result[i] = r / n
                    result[i + 1] = g / n
                    result[i + 2] = b / n
                }
            }
            result
        }
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    // Pairs every screen position with a different tile so the total difference is as small
    // as possible (the "Hungarian algorithm"). Returns the tile for each position.
    private fun assign(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minV = DoubleArray(n + 1) { Double.MAX_VALUE }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..n) {
                    if (!used[j]) {
                        val current = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (current < minV[j]) { minV[j] = current; way[j] = j0 }
                        if (minV[j] < delta) { delta = minV[j]; j1 = j }
                    }
                }
                for (j in 0..n) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minV[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(n)
        for (j in 1..n) result[p[j] - 1] = j - 1
        return result
    }

    // On a 5 x 5 puzzle, a layout can be solved only if the tiles (ignoring the empty space)
    // are out of order an even number of times.
    fun isSolvable(board: IntArray): Boolean {
        var inversions = 0
        for (i in board.indices) {
            if (board[i] == EMPTY) continue
            for (j in i + 1 until board.size) {
                if (board[j] != EMPTY && board[j] < board[i]) inversions++
            }
        }
        return inversions % 2 == 0
    }
}
