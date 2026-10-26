package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Runs the Path of Het room reader on the owner's screenshots, which aren't part of the project: they're in
// Documents\Androiddev\OSRSBubbleTool\pathofhet (or set HET_SHOTS=<folder>). Skipped without them.
// The room in each was worked out by eye (docs/toa-helper.md).
class HetReaderTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    // Each screenshot's room (0-2, as in ToaPuzzles.HET_ROOMS), by a piece of its name
    private val rooms = mapOf(
        "214234" to 0, "214457" to 1, "214802" to 2, "214927" to 1,
        "221443" to 2, "221708" to 2, "221854" to 0, "221944" to 0, "230434" to 1, "231721" to 1, "231843" to 1, "232159" to 0, "234410" to 2, "_b50" to 0, "_bmin" to 0, "_bmax" to 0, "224055_north" to 2, "231021_north" to 1, "232323_north" to 0, "024339_fixed" to 2, "133635_known" to 0
    )

    // In these raids a usually smooth wall was cracked: the screenshot's name and the wall (HetRoom.blocks index)
    private val crackedThatRaid: Map<String, List<Int>> get() = mapOf(
        "adb_20261023_231721.png" to listOf(1),   // room 2, 5,13..17
        "adb_20261023_231843.png" to listOf(4),   // room 2, 12,5..8
        "adb_20261023_234410_east.png" to listOf(0)   // room 3, 8,4..8
    )

    @Test
    fun readsEachRoom() {
        val dir = (System.getenv("HET_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\OSRSBubbleTool\\pathofhet").let(::File)
        assumeTrue(dir.isDirectory)
        var checked = 0
        dir.listFiles()!!.sortedBy { it.name }.forEach { f ->
            val want = rooms.entries.firstOrNull { f.name.contains(it.key) }?.value ?: return@forEach
            val start = System.nanoTime()
            val r = HetReader.read(Shot(f))
            val ms = (System.nanoTime() - start) / 1_000_000
            println("${f.name}: room ${want + 1}, read ${r?.let { it.room + 1 }} score ${r?.score} margin ${r?.margin} " +
                "sure ${r?.sure} walls up ${"%.2f".format(HetReader.lastWallsUp)} cracked ${r?.cracked?.joinToString("")} brightness power ${"%.3f".format(HetReader.lastPower)} fixed mirrors ${r?.fixedMirrors?.map { "${it / ToaPuzzles.HET_SIZE},${it % ToaPuzzles.HET_SIZE}" + (r.facing[it]?.let { f -> " " + listOf("NE", "SE", "SW", "NW")[f] } ?: "") }} way ${r?.let { x -> ToaPuzzles.hetBestWay(ToaPuzzles.HET_ROOMS[x.room].wallsWith(BooleanArray(x.cracked.size) { x.cracked[it] == 1 }), seen = x.fixedMirrors) + 1 }} unseen on it ${r?.let { x -> val way = ToaPuzzles.hetBestWay(ToaPuzzles.HET_ROOMS[x.room].wallsWith(BooleanArray(x.cracked.size) { x.cracked[it] == 1 }), seen = x.fixedMirrors); if (way < 0) "-" else ToaPuzzles.hetBeam(ToaPuzzles.HET_ANSWERS[way]).filter { it in x.unseen }.map { "${it / ToaPuzzles.HET_SIZE},${it % ToaPuzzles.HET_SIZE}" } }} camera ${r?.camera?.joinToString(",")} ($ms ms)")
            // walls seen cracked in these raids, though usually smooth (noted from the screenshots by eye)
            crackedThatRaid[f.name.take(f.name.length)]?.let { (block) -> if (r != null && r.sure && r.room == want) assertEquals(f.name, 1, r.cracked[block]) }
            // never sure of the wrong room; sure of the right one for a camera like the owner's usual one
            if (r != null && r.sure) assertEquals(f.name, want, r.room)
            if (!f.name.contains("angled") || f.name.contains("221708")) assertTrue(f.name, r != null && r.sure)
            checked++
        }
        assertEquals(rooms.size, checked)
    }

    // Which way the fixed mirrors face, in the raid whose answer the RuneLite plugin showed (October 2026, the same
    // raid on the PC a few minutes before): looking down from straight above in RuneLite, the mirrors at 5,6 and 19,7
    // face north-west and the one at 5,13 south-east, which is why the plugin chose way 7 (it turns there). RuneLite
    // also showed one at 2,10, behind the caster from the doorway: found from its disc (which way it faces wasn't
    // checked, so only that it's found)
    @Test
    fun readsWhichWayFixedMirrorsFace() {
        val dir = (System.getenv("HET_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\OSRSBubbleTool\\pathofhet").let(::File)
        val f =File(dir, "adb_20261024_133635_known.png")
        assumeTrue(f.isFile)
        val r = HetReader.read(Shot(f))!!
        val nw = ToaPuzzles.Facing.NORTH_WEST.ordinal; val se = ToaPuzzles.Facing.SOUTH_EAST.ordinal
        assertEquals(mapOf(5 * 21 + 6 to nw, 5 * 21 + 13 to se, 19 * 21 + 7 to nw), r.facing.filterKeys { it != 2 * 21 + 10 })
        assertEquals(listOf(2 * 21 + 10, 5 * 21 + 6, 5 * 21 + 13, 19 * 21 + 7), r.fixedMirrors)
        val room = ToaPuzzles.HET_ROOMS[r.room]
        assertEquals(6, ToaPuzzles.hetBestWay(room.walls, room.fixed, r.fixedMirrors, r.facing.mapValues { ToaPuzzles.Facing.entries[it.value] }))
    }

    // Watch's quick look (HetReader.follow) starting from a camera a little off, as after a second of walking: it
    // must land on the same room and the same fixed mirrors as a full read
    @Test
    fun followsTheCamera() {
        val dir = (System.getenv("HET_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\OSRSBubbleTool\\pathofhet").let(::File)
        assumeTrue(dir.isDirectory)
        dir.listFiles()!!.sortedBy { it.name }.forEach { f ->
            if (rooms.keys.none { f.name.contains(it) }) return@forEach
            val shot = Shot(f)
            val r = HetReader.read(shot)?.takeIf { it.sure } ?: return@forEach
            val k = shot.height / 1080f
            val c = r.camera
            val moved = floatArrayOf(c[0] + 24 * k, c[1] - 16 * k, c[2] * 1.04f, c[3], c[4], c[5] + 0.06f)
            val start = System.nanoTime()
            val look = HetReader.follow(shot, r.room, moved)
            val ms = (System.nanoTime() - start) / 1_000_000
            println("${f.name}: read ${r.fixedMirrors}, followed ${look?.fixedMirrors} ($ms ms)")
            assertTrue(f.name, look != null && look.room == r.room)
            // (the one behind the caster may come out a square off, 2,10 or 3,10: Watch counts those as one)
            if (f.name.contains("133635_known")) assertEquals(listOf(5 * 21 + 6, 5 * 21 + 13, 19 * 21 + 7),
                look!!.fixedMirrors.filter { it !in listOf(2 * 21 + 10, 3 * 21 + 10) })
        }
    }

    // How Watch settles squares (worked out by hand): a mirror needs two looks, a clear square two (three behind the
    // caster); settled stays settled
    @Test
    fun watchSettlesSquares() {
        val m = HetReader.Memory()
        val mirror = 5 * 21 + 13; val placed = 12 * 21 + 13; val hidden = 5 * 21 + 10
        fun look(fixed: List<Int>, unseen: List<Int>) = HetReader.Reading(0, 1f, 1f, IntArray(9), fixed, unseen, FloatArray(6))
        m.add(look(listOf(mirror), listOf(hidden)))
        assertTrue(mirror !in m.fixed && mirror in m.unseen)     // seen once: not sure yet
        assertTrue(placed in m.unseen && hidden in m.unseen)     // clear once: not sure yet either
        m.add(look(listOf(mirror), emptyList()))
        assertEquals(listOf(mirror), m.fixed)                    // a second look agrees: a fixed mirror
        assertTrue(placed !in m.unseen)                          // clear twice: clear
        assertTrue(hidden in m.unseen)                           // behind the caster, seen clear once: not yet
        m.add(look(listOf(mirror, placed), emptyList()))
        assertTrue(placed !in m.fixed)                           // a mirror carried onto a square seen clear before
        m.add(look(listOf(mirror), emptyList()))
        assertTrue(hidden !in m.unseen)                          // behind the caster, three clear looks: clear
        repeat(3) { m.add(look(emptyList(), emptyList())) }
        assertEquals(listOf(mirror), m.fixed)                    // settled stays settled
    }

    // Watch forgets the layout once the walls have been seen down 4 times with no look at the room between (a lost look,
    // the camera turning, doesn't start the count again; seeing the room does) (worked out by hand)
    @Test
    fun watchForgetsTheLayoutWhenTheWallsGoDown() {
        val m = HetReader.Memory()
        val mirror = 5 * 21 + 13
        val look = HetReader.Look(HetReader.Reading(0, 1f, 1f, IntArray(9), listOf(mirror), emptyList(), FloatArray(6)), false)
        val down = HetReader.Look(null, true)
        val lost = HetReader.Look(null, false)
        repeat(3) { m.use(look) }
        assertEquals(listOf(mirror), m.fixed)
        repeat(3) { assertEquals(HetReader.Memory.Outcome.WALLS_DOWN, m.use(down)) }
        m.use(look)                                              // the room seen: the walls weren't down after all
        repeat(3) { m.use(down) }
        m.use(lost)                                              // a lost look between doesn't matter
        assertEquals(0, m.room)
        assertEquals(HetReader.Memory.Outcome.CLEARED, m.use(down))
        assertEquals(-1, m.room)
        assertTrue(m.fixed.isEmpty())
    }

    // A new layout of the same room, risen while the camera was turned away (so the walls weren't seen down): after a
    // few lost looks, three looks in a row with a wall cracked that had settled smooth start afresh; looks that agree
    // keep it, and neither settled mirrors not seen nor cracked walls mined away (read smooth) prove anything (worked
    // out by hand)
    @Test
    fun watchNoticesANewLayoutOfTheSameRoom() {
        val old = listOf(5 * 21 + 6, 19 * 21 + 7)
        val new = listOf(9 * 21 + 2, 14 * 21 + 17)
        val oldCracks = intArrayOf(0, 1, 0, 0, 0, 0, 0, 1, 0)
        val newCracks = intArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0)
        fun look(fixed: List<Int>, cracked: IntArray) =
            HetReader.Look(HetReader.Reading(0, 1f, 1f, cracked, fixed, emptyList(), FloatArray(6)), false)
        val lost = HetReader.Look(null, false)
        val m = HetReader.Memory()
        repeat(3) { m.use(look(old, oldCracks)) }
        assertEquals(old, m.fixed)
        // zoomed in a while, then the same layout again: kept
        repeat(4) { m.use(lost) }
        m.use(look(old, oldCracks))
        assertEquals(old, m.fixed)
        // the same cracks with the mirrors missed, or a cracked wall mined away: kept
        repeat(4) { m.use(lost) }
        m.use(look(emptyList(), oldCracks))
        repeat(4) { m.use(lost) }
        repeat(3) { m.use(look(old, intArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 0))) }
        assertEquals(old, m.fixed)
        // turned away a while, and a new layout of the same room: the first disagreeing looks aren't believed yet...
        repeat(4) { m.use(lost) }
        repeat(2) { assertEquals(HetReader.Memory.Outcome.SAME, m.use(look(new, newCracks))) }
        assertEquals(old, m.fixed)
        // ...the third starts afresh with it
        assertEquals(HetReader.Memory.Outcome.CHANGED, m.use(look(new, newCracks)))
        m.use(look(new, newCracks))
        assertEquals(new, m.fixed)
        assertEquals(newCracks.toList(), m.cracked!!.toList())
    }

    // Watch doesn't jump to another room on one odd look, nor turn a mirror once two looks agreed on its facing
    // (worked out by hand)
    @Test
    fun watchKeepsWhatLooksAgreed() {
        val m = HetReader.Memory()
        val mirror = 5 * 21 + 10
        val se = ToaPuzzles.Facing.SOUTH_EAST.ordinal; val ne = ToaPuzzles.Facing.NORTH_EAST.ordinal
        fun look(room: Int, facing: Int) = HetReader.Reading(room, 1f, 1f, IntArray(9), listOf(mirror), emptyList(), FloatArray(6), mapOf(mirror to facing))
        m.add(look(0, se)); m.add(look(0, se))
        assertEquals(mapOf(mirror to se), m.facing)
        repeat(4) { m.add(look(0, ne)) }
        assertEquals(mapOf(mirror to se), m.facing)              // two agreeing looks settled it
        repeat(5) { m.add(look(1, ne)) }
        assertEquals(0, m.room)                                  // after several looks the room is kept
        // in the first looks, three in a row at another room move it there (the first look was the odd one)
        val m2 = HetReader.Memory()
        m2.add(look(0, se))
        m2.add(look(1, ne)); m2.add(look(1, ne))
        assertEquals(0, m2.room)
        m2.add(look(1, ne))
        assertEquals(1, m2.room)
    }

    // Het's seal: a room read before its bar shows (the bare floor, before the walls rise) is forgotten when it shows;
    // the bar lower on two looks in a row (you're mining it) clears the layout; at nothing on two looks it's broken
    // (worked out by hand)
    @Test
    fun watchFollowsHetsSeal() {
        fun look(room: Int, seal: Float?) = HetReader.Look(HetReader.Reading(room, 1f, 1f, IntArray(9), emptyList(), focusZoom = 30f), false, seal = seal)
        fun bar(seal: Float) = HetReader.Look(null, false, seal = seal)
        val m = HetReader.Memory()
        repeat(3) { m.use(look(0, null)) }
        assertEquals(0, m.room)
        m.use(look(1, 1f))                                       // the walls rose: room 1's reading starts afresh
        assertEquals(1, m.room)
        repeat(2) { m.use(look(1, 1f)) }
        assertEquals(HetReader.Memory.Outcome.LOST, m.use(bar(0.97f)))   // one low look isn't believed...
        m.use(look(1, 1f))
        assertEquals(1, m.room)
        m.use(bar(0.8f))
        assertEquals(HetReader.Memory.Outcome.CLEARED, m.use(bar(0.7f)))   // ...two in a row are
        assertEquals(-1, m.room)
        assertFalse(m.broken)
        m.use(bar(0f)); m.use(bar(0f))
        assertTrue(m.broken)
    }

    // Once the room is settled, another room read at a different zoom (zoomed in close while solving) doesn't move
    // Watch to it, however many times; at about the first read's zoom, three looks in a row do (worked out by hand)
    @Test
    fun watchChangesRoomOnlyAtTheSameZoom() {
        fun look(room: Int, zoom: Float) = HetReader.Look(HetReader.Reading(room, 1f, 1f, IntArray(9), emptyList(), focusZoom = zoom), false)
        val m = HetReader.Memory()
        repeat(3) { m.use(look(1, 35f)) }
        repeat(5) { m.use(look(0, 44f)) }                         // 26% closer
        assertEquals(1, m.room)
        repeat(2) { m.use(look(0, 37f)) }
        assertEquals(1, m.room)
        assertEquals(HetReader.Memory.Outcome.CHANGED, m.use(look(0, 37f)))
        assertEquals(0, m.room)
    }
}
