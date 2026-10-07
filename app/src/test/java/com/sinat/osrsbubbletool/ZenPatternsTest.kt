package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.SandGardenView.Companion.RAKE
import com.sinat.osrsbubbletool.SandGardenView.Companion.RES
import com.sinat.osrsbubbletool.SandGardenView.Companion.STICK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

// The Zen Garden's auto rake patterns on the PC, on a wide tray and a tall one (in sand squares).
class ZenPatternsTest {

    private val trays = listOf(1300 to 600, 532 to 1180)

    @Test
    fun seventeenPatternsEachWithItsOwnName() {
        assertEquals(17, ZenPatterns.NAMES.size)
        assertEquals(17, ZenPatterns.NAMES.toSet().size)
    }

    @Test
    fun everyPatternRakesTheSandInSmoothSteps() {
        for ((w, h) in trays) for (p in ZenPatterns.NAMES.indices) {
            val strokes = ZenPatterns.strokes(p, w, h, Random(1))
            val name = "${ZenPatterns.NAMES[p]} on $w×$h"
            assertTrue("$name has strokes", strokes.isNotEmpty())
            var onSand = 0
            for (st in strokes) {
                assertTrue("$name: rake or stick", st.kind == RAKE || st.kind == STICK)
                val pts = st.points
                for (k in 0 until pts.size / 2) {
                    if (pts[2 * k] in 0f..w.toFloat() && pts[2 * k + 1] in 0f..h.toFloat()) onSand++
                    // the rake walks along them about a square at a time: no jumps (the biggest step, just under 2
                    // squares, is where a closed shape like the Rose Window joins up, while the rake lifts out)
                    if (k > 0) assertTrue("$name: no jumps", hypot(pts[2 * k] - pts[2 * k - 2], pts[2 * k + 1] - pts[2 * k - 1]) < 2.5f)
                }
            }
            assertTrue("$name rakes the sand", onSand > 1000)
        }
    }

    @Test
    fun theSameRandomStartGivesTheSamePattern() {
        for (p in ZenPatterns.NAMES.indices) {
            val a = ZenPatterns.strokes(p, 900, 700, Random(5))
            val b = ZenPatterns.strokes(p, 900, 700, Random(5))
            assertEquals(a.size, b.size)
            for (i in a.indices) assertTrue(a[i].points.contentEquals(b[i].points))
        }
    }

    @Test
    fun rowsRunAlongTheLongSideARakesWidthApart() {
        // a wide tray: each row keeps one height (no waves), and the rows are 5 teeth × 6 dp = 30 dp apart
        val rows = ZenPatterns.strokes(ZenPatterns.NAMES.indexOf("Rows"), 1300, 600, Random(1))
        for (row in rows) {
            val y = row.points[1]
            for (k in 0 until row.points.size / 2) assertEquals(y, row.points[2 * k + 1], 0f)
        }
        assertEquals(30f * RES, rows[1].points[1] - rows[0].points[1], 0.001f)
    }

    @Test
    fun threeStickRingsCloseAroundEachStoneOrLantern() {
        // a stone at (100, 200) covering 20 squares: the stick is 7 dp wide, so its rings are 2 dp + half a stick
        // out from the stone, then a stick's width further each time
        val rings = ZenPatterns.rings(floatArrayOf(100f, 200f, 20f, 400f, 300f, 15f), Random(1))
        assertEquals(6, rings.size)
        val half = 3.5f * RES
        for (k in 0 until 3) {
            val p = rings[k].points
            assertEquals(STICK, rings[k].kind)
            assertEquals(20f + 2f * RES + half + k * 2 * half, hypot(p[0] - 100f, p[1] - 200f), 0.01f)
        }
        assertEquals(15f + 2f * RES + half, hypot(rings[3].points[0] - 400f, rings[3].points[1] - 300f), 0.01f)
    }
}
