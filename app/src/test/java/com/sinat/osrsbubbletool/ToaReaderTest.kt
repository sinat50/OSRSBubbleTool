package com.sinat.osrsbubbletool

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Runs the ToA puzzle reader on saved phone screenshots and prints what it reads.
// The pictures aren't part of the project (they're in Documents\Androiddev\toa_recordings). Point one of these at a folder:
//   TOA_SHOTS=<folder of screenshots>    reads every screenshot fresh (all the puzzles)
//   TOA_REPLAY=<folder of f_<time>.png>  replays a recorded matching solve, as Watch would see it
//   TOA_DEBUG_FRAME=<n>                  (with TOA_REPLAY) also lists the boards considered in picture n
// e.g.  set TOA_REPLAY=C:\...\toa_recordings\matching_1  then
//       gradlew.bat testDebugUnitTest --tests *ToaReaderTest* --rerun-tasks
// (--rerun-tasks, or Gradle skips the test when only the folder changed.) Without them the tests are skipped.
class ToaReaderTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width = image.width
        override val height = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    @Test
    fun readsLightPuzzle() {
        val dir = System.getenv("TOA_SHOTS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!.listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }.forEach { f ->
            val reading = ToaReader.readLights(Shot(f))
            val text = reading?.let { r ->
                val lit = (0 until 8).filter { r.lit shr it and 1 == 1 }
                val steps = ToaPuzzles.solveLights(r.lit).let { s -> (0 until 8).filter { s shr it and 1 == 1 } }
                "lit $lit, turn ${"%.0f".format(r.rotation)}°, step on $steps"
            } ?: "no light puzzle"
            val number = ToaReader.readAdditionNumber(Shot(f))?.let { ", addition number $it" } ?: ""
            val shot = Shot(f)
            val sequence = ToaReader.findSequenceTiles(shot)?.let { t ->
                ", sequence tiles (turn ${"%.0f".format(t.rotation)}°), lit tile ${ToaReader.litSequenceTile(shot, t)}"
            } ?: ""
            println("${f.name}: $text$number$sequence")
            // Matching boards: each tile as . grey, * glowing, ? can't tell, or the symbol's first letters
            val started = System.nanoTime()
            val found = ToaReader.findMatchBoards(shot, ToaReader.Skip { _, _ -> false })
            val ms = (System.nanoTime() - started) / 1_000_000
            found?.let { boards ->
                val tiles = (0 until 18).map { t ->
                    val r = ToaReader.readMatchTile(shot, boards, t, ToaReader.Skip { _, _ -> false }, ToaPuzzles.MATCHING_SYMBOLS)
                    when (r.state) {
                        ToaReader.TILE_HIDDEN -> "."
                        ToaReader.TILE_MATCHED -> "*"
                        ToaReader.TILE_SYMBOL -> r.symbol!!.label.take(2) + "%.2f".format(r.sure)
                        else -> "?"
                    }
                }
                println("    matching (${ms} ms): left ${tiles.take(9)}  right ${tiles.drop(9)}")
            }
        }
    }

    // Replays a recording of a matching-puzzle solve (screenshots in TOA_REPLAY, named by time) through the
    // reader, carrying the boards from one picture to the next as Watch does, and prints each picture's reading.
    @Test
    fun replaysMatching() {
        val dir = System.getenv("TOA_REPLAY")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        var previous: ToaReader.MatchBoards? = null
        var totalNanos = 0L; var frames = 0
        // the app's own memory (the same code as Watch uses), with each picture's time as the clock
        val memory = ToaReader.MatchMemory()
        dir!!.listFiles { f -> f.name.startsWith("f_") && f.name.endsWith(".png") }!!.sortedBy { it.name }.forEachIndexed { n, f ->
            val shot = Shot(f)
            val w = shot.width; val h = shot.height
            // the app's own Stop button (top-left) and boards map (bottom middle) are in these pictures
            val skip = ToaReader.Skip { x, y -> (x < w * 0.1f && y < h * 0.13f) || (y > h * 0.76f && x > w * 0.38f && x < w * 0.66f) }
            ToaReader.debugLog = if (System.getenv("TOA_DEBUG_FRAME") == n.toString()) { m -> println("DEBUG $m") } else null
            // time the work the app does for each picture (as the shot is already in memory: finding and reading)
            val pixels = IntArray(shot.width * shot.height) { shot.rgb(it % shot.width, it / shot.width) }
            val fast = object : LightBoxReader.PixelSource {
                override val width = shot.width
                override val height = shot.height
                override fun rgb(x: Int, y: Int) = pixels[y * width + x]
            }
            val started = System.nanoTime()
            val boards = ToaReader.findMatchBoards(fast, skip, previous)
            val read = boards?.let { ToaReader.readMatchTiles(fast, it, skip, memory.allowed) }
            totalNanos += System.nanoTime() - started; frames++
            ToaReader.debugLog = null
            if (boards == null || read == null) { println("REPLAY %3d: no boards".format(n)); return@forEachIndexed }
            previous = boards
            // each tile as . grey, * glowing, ? can't tell, or the symbol's first letters; - for a board not read
            val tiles = (0 until 18).map { t ->
                if (!boards.seen[t / 9] || !boards.clear[t / 9]) "-" else when (read[t].state) {
                    ToaReader.TILE_HIDDEN -> "."
                    ToaReader.TILE_MATCHED -> "*"
                    ToaReader.TILE_SYMBOL -> read[t].symbol!!.label.take(2)
                    else -> "?"
                }
            }
            val time = f.name.removePrefix("f_").removeSuffix(".png").toLong()
            memory.merge(read, boards, w, time)
            val remembered = (0 until 18).joinToString("") { t ->
                (if (t == 9) "|" else "") + (memory.symbols[t]?.label?.take(2) ?: "..") + (if (t in memory.done) "*" else " ")
            }
            println("MEMORY %3d: %s".format(n, remembered))
            println("REPLAY %3d: L(%4.0f,%4.0f) R(%4.0f,%4.0f) col(%4.0f,%4.0f) %s | %s".format(n,
                boards.cx[0], boards.cy[0], boards.cx[1], boards.cy[1], boards.colX[0], boards.colY[0],
                tiles.take(9).joinToString(" ") { it.padEnd(2) }, tiles.drop(9).joinToString(" ") { it.padEnd(2) }))
        }
        println("TIMING %.1f ms per picture over %d pictures".format(totalNanos / 1e6 / frames.coerceAtLeast(1), frames))
    }
}
