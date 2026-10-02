package com.sinat.osrsbubbletool

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Runs the ToA puzzle reader on saved phone screenshots and prints what it reads.
// The screenshots aren't part of the project: put them in a folder and point TOA_SHOTS at it, e.g.
//   set TOA_SHOTS=C:\path\to\toa_shots  then  gradlew.bat testDebugUnitTest --tests *ToaReaderTest*
// Without TOA_SHOTS the test is skipped.
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
        // the app's memory, as ToaPuzzleTool keeps it: a symbol once two pictures agree, matched after 1.5 s of glow
        val votes = HashMap<Int, IntArray>()
        val symbols = HashMap<Int, ToaPuzzles.Symbol>()
        val done = HashSet<Int>()
        val glowSince = HashMap<Int, Long>()
        val nearUi = HashSet<Int>()
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
            boards?.let { b -> for (t in 0 until 18) if (b.seen[t / 9]) ToaReader.readMatchTile(fast, b, t, skip, ToaPuzzles.MATCHING_SYMBOLS) }
            totalNanos += System.nanoTime() - started; frames++
            ToaReader.debugLog = null
            if (boards == null) { println("REPLAY %3d: no boards".format(n)); return@forEachIndexed }
            previous = boards
            val tiles = (0 until 18).map { t ->
                if (!boards.seen[t / 9] || !boards.clear[t / 9]) "-" else {
                    val r = ToaReader.readMatchTile(shot, boards, t, skip, ToaPuzzles.MATCHING_SYMBOLS)
                    when (r.state) {
                        ToaReader.TILE_HIDDEN -> "."
                        ToaReader.TILE_MATCHED -> "*"
                        ToaReader.TILE_SYMBOL -> r.symbol!!.label.take(2)
                        else -> "?"
                    }
                }
            }
            val time = f.name.removePrefix("f_").removeSuffix(".png").toLong()
            for (t in 0 until 18) {
                if (!boards.seen[t / 9]) continue
                val r = ToaReader.readMatchTile(shot, boards, t, skip, ToaPuzzles.MATCHING_SYMBOLS)
                if (r.state == ToaReader.TILE_SYMBOL && !boards.clear[t / 9]) continue
                when (r.state) {
                    ToaReader.TILE_MATCHED -> if (t !in done) {
                        glowSince.getOrPut(t) { time }
                        if (boards.tileX(t) !in (w * 0.1f)..(w * 0.72f)) nearUi.add(t)
                    }
                    ToaReader.TILE_HIDDEN -> { glowSince.remove(t); nearUi.remove(t) }
                    ToaReader.TILE_SYMBOL -> {
                        glowSince.remove(t); nearUi.remove(t)
                        val k = ToaPuzzles.MATCHING_SYMBOLS.indexOf(r.symbol)
                        val v = votes.getOrPut(t) { IntArray(9) }
                        v[k]++
                        val best = v.indices.maxBy { v[it] }
                        if (v[best] >= 2 || r.sure >= 0.8f) symbols[t] = ToaPuzzles.MATCHING_SYMBOLS[best]
                    }
                }
            }
            // pairs, as ToaPuzzleTool.settlePairs does
            val steady = glowSince.filter { (t, since) -> t !in done && time - since >= 1500 }.keys.toMutableSet()
            fun other(t: Int) = if (t < 9) 9 until 18 else 0 until 9
            fun pair(a: Int, b: Int) { done.add(a); done.add(b); steady.remove(a); steady.remove(b); glowSince.remove(a); glowSince.remove(b) }
            for (t in steady.sorted()) {
                if (t !in steady) continue
                val sym = symbols[t] ?: continue
                val partner = other(t).firstOrNull { symbols[it] == sym }
                if (partner != null) { if (partner in steady || partner in done) pair(t, partner) }
                else other(t).firstOrNull { it in steady && symbols[it] == null }?.let { symbols[it] = sym; pair(t, it) }
            }
            val ls = steady.filter { it < 9 && symbols[it] == null }.sorted()
            val rs = steady.filter { it >= 9 && symbols[it] == null }.sorted()
            for (i in 0 until minOf(ls.size, rs.size)) pair(ls[i], rs[i])
            for (t in steady.toList()) {
                if (t in nearUi || symbols[t] != null) continue
                if (time - (glowSince[t] ?: time) >= 4000) { done.add(t); glowSince.remove(t) }
            }
            val memory = (0 until 18).joinToString("") { t ->
                (if (t == 9) "|" else "") + (symbols[t]?.label?.take(2) ?: "..") + (if (t in done) "*" else " ")
            }
            println("MEMORY %3d: %s".format(n, memory))
            println("REPLAY %3d: L(%4.0f,%4.0f) R(%4.0f,%4.0f) col(%4.0f,%4.0f) %s | %s".format(n,
                boards.cx[0], boards.cy[0], boards.cx[1], boards.cy[1], boards.colX[0], boards.colY[0],
                tiles.take(9).joinToString(" ") { it.padEnd(2) }, tiles.drop(9).joinToString(" ") { it.padEnd(2) }))
        }
        println("TIMING %.1f ms per picture over %d pictures".format(totalNanos / 1e6 / frames.coerceAtLeast(1), frames))
    }
}
