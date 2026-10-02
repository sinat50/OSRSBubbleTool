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
            val found = ToaReader.findMatchBoards(shot, { _, _ -> false })
            val ms = (System.nanoTime() - started) / 1_000_000
            found?.let { boards ->
                val tiles = (0 until 18).map { t ->
                    val r = ToaReader.readMatchTile(shot, boards, t, { _, _ -> false }, ToaPuzzles.MATCHING_SYMBOLS)
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
}
