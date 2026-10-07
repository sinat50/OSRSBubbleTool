package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.SandGardenView.Companion.BONSAI
import com.sinat.osrsbubbletool.SandGardenView.Companion.FLOWERS
import com.sinat.osrsbubbletool.SandGardenView.Companion.LANTERN
import com.sinat.osrsbubbletool.SandGardenView.Companion.RES
import com.sinat.osrsbubbletool.SandGardenView.Companion.STONE
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Zen Garden's placed things on the PC. These check the promises the looks make (the same every time from
// the same seed, covering their middle, standing on the sand), not the exact art, so the looks can still be
// changed without these tests needing new numbers.
class ZenThingsTest {

    private val kinds = intArrayOf(STONE, BONSAI, LANTERN, FLOWERS)
    private fun look(kind: Int, seed: Int, angle: Float = 30f) =
        ZenThings.look(kind, 12f * RES, angle, if (kind == BONSAI) ZenThings.POT_COLORS[0] else 0, seed)

    private fun sameLook(a: ZenThings.Look, b: ZenThings.Look) =
        a.half == b.half && a.height.contentEquals(b.height) && a.colour.contentEquals(b.colour) &&
            a.edge.contentEquals(b.edge) && a.soft.contentEquals(b.soft)

    // ---- the colour helpers, against colours worked out by hand ----

    @Test
    fun coloursReadFromTheirHexCode() {
        assertEquals(0xFF8F8B83.toInt(), colour("#8F8B83"))
        assertEquals(0xFF000000.toInt(), colour("#000000"))
    }

    @Test
    fun blendingHalfwayBetweenBlackAndWhiteIsGrey() {
        // 255 × 0.5 = 127.5, cut down to 127 (as Android does)
        assertEquals(0xFF7F7F7F.toInt(), blend(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
        assertEquals(0xFF102030.toInt(), blend(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0f))
    }

    @Test
    fun toneDarkensAndLightensButNeverPastWhite() {
        assertEquals(0xFF404040.toInt(), tone(0xFF808080.toInt(), 0.5f))
        assertEquals(0xFFFFFFFF.toInt(), tone(0xFFC0C0C0.toInt(), 2f))
    }

    // ---- the looks ----

    @Test
    fun theSameSeedAlwaysGivesTheSameLook() {
        for (kind in kinds) assertTrue("kind $kind", sameLook(look(kind, 1234), look(kind, 1234)))
    }

    @Test
    fun differentSeedsGiveDifferentStonesBonsaiAndFlowers() {
        for (kind in intArrayOf(STONE, BONSAI, FLOWERS)) assertFalse("kind $kind", sameLook(look(kind, 1), look(kind, 2)))
    }

    @Test
    fun everyLanternIsTheSameLanternOnlyTurned() {
        assertTrue(sameLook(look(LANTERN, 1), look(LANTERN, 999)))
        assertFalse(sameLook(look(LANTERN, 1, angle = 0f), look(LANTERN, 1, angle = 20f)))
    }

    @Test
    fun everyLookCoversItsMiddleAndStandsOnTheSand() {
        for (kind in kinds) for (seed in 1..20) {
            val l = look(kind, seed)
            val middle = l.half * l.size + l.half
            assertTrue("kind $kind seed $seed covers its middle", l.height[middle] >= 0f)
            for (i in l.height.indices) {
                if (l.height[i] < 0f) continue
                assertTrue("kind $kind seed $seed: a colour wherever it stands", l.colour[i] != 0)
            }
            assertTrue("kind $kind seed $seed reaches past its middle", l.reach >= 1f)
        }
    }

    @Test
    fun flowerBlossomsSitInTheClumpBudsFirst() {
        for (seed in 1..20) {
            val r = 12f * RES
            val b = ZenThings.blossoms(seed, r)
            assertTrue(b.isNotEmpty())
            var lastStage = 0f
            for (k in 0 until b.size / 5) {
                assertTrue("seed $seed: blossom inside the clump", kotlin.math.hypot(b[5 * k], b[5 * k + 1]) <= 0.6f * r)
                assertTrue("seed $seed: buds, then half open, then open", b[5 * k + 4] >= lastStage)
                lastStage = b[5 * k + 4]
            }
            assertArrayEquals(b, ZenThings.blossoms(seed, r), 0f)
        }
    }
}
