package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.ZenGardenFile.Garden
import com.sinat.osrsbubbletool.ZenGardenFile.Thing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

// The Zen Garden's save file on the PC: a garden must come back as it was saved, and gardens saved by older
// versions of the app must still open. Sizes and positions are in today's sand squares (RES per dp).
class ZenGardenFileTest {

    private val res = SandGardenView.RES

    private fun roundTrip(g: Garden): Garden? {
        val bytes = ByteArrayOutputStream().also { ZenGardenFile.write(g, it) }.toByteArray()
        return ZenGardenFile.read(ByteArrayInputStream(bytes))
    }

    // A file as an older version of the app wrote it (packed small, like every save)
    private fun oldFile(write: DataOutputStream.() -> Unit): ByteArrayInputStream {
        val data = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(data)).use { it.write() }
        return ByteArrayInputStream(data.toByteArray())
    }

    @Test
    fun aGardenComesBackAsItWasSaved() {
        // heights are kept to about 1/64 of a dp (plenty for sand), so they're compared that closely
        val heights = floatArrayOf(0f, 1f, -1f, 2.5f, -2.5f, 0.3f)
        val stone = Thing(SandGardenView.STONE, 10f, 20f, 12f, 1f, 45f, 0, seed = 1234, rings = 2)
        val lantern = Thing(SandGardenView.LANTERN, 30f, 5f, 13.5f, 1f, 20f, 0x777777, seed = -99, rings = 0)
        val back = roundTrip(Garden(3, 2, heights, listOf(stone, lantern), fireflies = 4))!!
        assertEquals(3, back.width)
        assertEquals(2, back.height)
        for (i in heights.indices) assertEquals("height $i", heights[i], back.heights[i], 0.02f * res)
        assertEquals(2, back.things.size)
        val s = back.things[0]
        assertEquals(listOf<Any>(SandGardenView.STONE, 10f, 20f, 12f, 1f, 45f, 0, 1234, 2),
            listOf<Any>(s.kind, s.x, s.y, s.r, s.aspect, s.angle, s.color, s.seed, s.rings))
        assertEquals(-99, back.things[1].seed)
        assertEquals(4, back.fireflies)
    }

    @Test
    fun aVersionThreeGardenAtOneSquarePerDpIsStretchedToToday() {
        // version 3: no fineness written (1 square per dp then), a 2×2 grid, one bonsai, 3 fireflies
        val file = oldFile {
            writeInt(3)
            writeInt(2); writeInt(2)
            write(byteArrayOf(127, 127, 127, 127))   // flat sand (127 = height 0)
            writeInt(1)
            writeInt(SandGardenView.BONSAI)
            writeFloat(10f); writeFloat(6f); writeFloat(8f)          // x, y, size in old squares (= dp)
            writeFloat(1f); writeFloat(90f); writeInt(0x804020)     // aspect, angle, colour
            writeInt(77); writeInt(1)                                // seed, rings
            writeInt(3)
        }
        val g = ZenGardenFile.read(file)!!
        assertEquals(3, g.width)    // 2 dp × 1.5 squares per dp
        assertEquals(3, g.height)
        val b = g.things.single()
        assertEquals(listOf(15f, 9f, 12f), listOf(b.x, b.y, b.r))
        assertEquals(listOf<Any>(SandGardenView.BONSAI, 90f, 0x804020, 77, 1), listOf<Any>(b.kind, b.angle, b.color, b.seed, b.rings))
        assertEquals(3, g.fireflies)
        for (h in g.heights) assertEquals(0f, h, 0.02f * res)
    }

    @Test
    fun aVersionOneGardenHasOnlyStonesAndNoFireflies() {
        // the first Zen Garden: no kind, seed or rings for each thing, and no fireflies
        val file = oldFile {
            writeInt(1)
            writeInt(1); writeInt(1)
            write(byteArrayOf(127))
            writeInt(1)
            writeFloat(4f); writeFloat(2f); writeFloat(6f)
            writeFloat(1.2f); writeFloat(30f); writeInt(0x999999)
        }
        val g = ZenGardenFile.read(file)!!
        val s = g.things.single()
        assertEquals(SandGardenView.STONE, s.kind)
        assertEquals(0, s.rings)
        assertEquals(listOf(6f, 3f, 9f), listOf(s.x, s.y, s.r))
        assertEquals(0, g.fireflies)
    }

    @Test
    fun somethingThatIsNotAGardenGivesNothing() {
        assertNull(ZenGardenFile.read(ByteArrayInputStream("not a garden".toByteArray())))
        assertNull(ZenGardenFile.read(oldFile { writeInt(9) }))   // a version this app doesn't know
    }

    @Test
    fun aDamagedFileWithAnImpossibleSizeGivesNothingInsteadOfCrashing() {
        // a 30000 × 30000 garden would need 900 MB: no garden is ever that big, so the file must be damaged
        assertNull(ZenGardenFile.read(oldFile { writeInt(4); writeFloat(1.5f); writeInt(30000); writeInt(30000) }))
        assertNull(ZenGardenFile.read(oldFile { writeInt(4); writeFloat(1.5f); writeInt(-5); writeInt(10) }))
        // a sand fineness of 0 would stretch it without end
        assertNull(ZenGardenFile.read(oldFile { writeInt(4); writeFloat(0f); writeInt(10); writeInt(10) }))
        // two billion placed things
        assertNull(ZenGardenFile.read(oldFile { writeInt(4); writeFloat(1.5f); writeInt(1); writeInt(1); write(byteArrayOf(127)); writeInt(2_000_000_000) }))
    }
}
