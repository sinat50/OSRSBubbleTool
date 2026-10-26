package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// Replays a Path of Het Watch run, one picture a second, as Watch looks (ToaPuzzleTool.hetTick): pictures cut from a
// screen recording of the owner's phone, which aren't part of the project. Make them with
//   ffmpeg -i <recording>.mp4 -vf fps=1 <folder>\f%03d.png
// keeping only the ones from when Watch started, and set HET_REPLAY=<folder> and HET_REPLAY_ROOM=<the room it should
// end on, 0-2>; run with --rerun (Gradle doesn't notice a changed setting). Skipped without them. The owner's runs of
// 25 October 2026, in pathofhet\: watch_20261025.mp4 from 8 s in, room 2 (1) all the way; watch_20261025b.mp4 from 5 s,
// starting as the walls rise, room 3 (2); watch_20261025c_cycle.mp4 from 7 s, a whole round: room 2, the walls down
// while mining (cleared about 76 s in), then a new layout, room 1 (0). From 26 October 2026 Watch's window sits bottom
// left (set HET_REPLAY_BOTTOM=1): watch_20261026_cycle.mp4 from 10 s, room 3, mined about 183 s in, then room 2 (1);
// watch_20261026b.mp4 from 8 s, room 3, mined about 62 s in, then room 2 (1).
class HetReplayTest {
    private class Shot(file: File, private val barShows: Boolean, private val map: Boolean = true) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int): Int {
            // the app's shrunk window (top left) and the map (bottom middle, only while Watch shows a room) on the
            // owner's 2340x1080 screen, blanked as Watch does
            if (x < 500 && y < 130 && !barShows) return 0
            if (x < 670 && y >= 890 && barShows) return 0    // (from 26 October 2026 the window sits bottom left)
            if (map && x in 860..1620 && y >= 590) return 0
            return image.getRGB(x, y) and 0xFFFFFF
        }
    }

    @Test
    fun replaysAWatchRun() {
        // (an empty path counts as the current folder on newer Java, so a missing setting is checked first)
        val path = System.getenv("HET_REPLAY")
        assumeTrue(!path.isNullOrEmpty())
        val dir = File(path!!)
        assumeTrue(dir.isDirectory)
        val room = System.getenv("HET_REPLAY_ROOM")?.toInt() ?: 1    // the room it should stay in (0-2)
        val m = HetReader.Memory()
        fun sq(s: Int) = "${s / 21},${s % 21}"
        val names = listOf("NE", "SE", "SW", "NW")
        for (f in dir.listFiles()!!.filter { it.name.endsWith(".png") }.sortedBy { it.name }) {
            val shot = Shot(f, !System.getenv("HET_REPLAY_BOTTOM").isNullOrEmpty(), m.room >= 0)
            val w = HetReader.watchLook(shot, m.room, m.camera, m.locked, m.lostLooks, m.zoom, m.seal)
            val look = w.reading
            val outcome = m.use(w)
            println("${f.name}: $outcome seal ${w.seal?.let { "%.2f".format(it) }} room ${look?.let { it.room + 1 }} " +
                "fixed ${look?.fixedMirrors?.map { sq(it) + (look.facing[it]?.let { x -> " " + names[x] } ?: "") }} " +
                (if (look == null) "bare %.2f walled %.2f ".format(HetReader.lastEmptyFit, HetReader.lastRoomFit) else "") + "| " +
                "cracks ${look?.cracked?.joinToString("") { if (it < 0) "?" else "$it" }} voted ${m.cracked?.joinToString("") { if (it < 0) "?" else "$it" }} | Watch: room ${m.room + 1} fixed ${m.fixed.map { sq(it) + (m.facing[it]?.let { x -> " " + names[x] } ?: "") }}")
        }
        assertEquals(room, m.room)
    }
}
