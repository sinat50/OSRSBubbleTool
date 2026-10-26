package com.sinat.osrsbubbletool

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Path of Het: which of the known rooms (ToaPuzzles.HET_ROOMS) is on screen, from one picture taken as you walk in.
// Nothing here is Android, so it's tested on the PC with the owner's screenshots (HetReaderTest).
//
// How: every pixel is sorted into wall stone (dark slate blue), sandy floor, or something else (statues, mirrors,
// the orbs, which are near black, the player). Then a simple camera (which way it faces, all the way round, zoomed,
// tilted, and where the room's middle is on screen) is searched for: for each guess, each floor square's middle is
// looked up on screen, and each room scores how well its walls and floor match (+1 a square that matches, -1 one
// that doesn't, divided by the squares seen). The search starts from the middle of the sandy floor. The square just
// behind a wall counts as stone (the wall's top and front cover it); the next one is skipped. The room that fits best is the answer, if it fits well and clearly
// better than the others (`sure`).
object HetReader {

    // `cracked`: for each wall of the room (ToaPuzzles.HetRoom.blocks), 1 cracked, 0 smooth, -1 couldn't tell.
    // `fixedMirrors`: the squares (x * HET_SIZE + y) where a fixed mirror stands out on the floor this raid.
    // `unseen`: open floor squares that couldn't be seen (behind a statue or a wall, or off screen), so a fixed mirror
    // could stand there unnoticed. `facing`: which way each fixed mirror faces, where its disc could be made out
    // (square -> 0 north-east, 1 south-east, 2 south-west, 3 north-west, as ToaPuzzles.Facing)
    class Reading(val room: Int, val score: Float, val margin: Float, val cracked: IntArray, val fixedMirrors: List<Int>,
                  val unseen: List<Int> = emptyList(), val camera: FloatArray = FloatArray(0),
                  val facing: Map<Int, Int> = emptyMap(), val focusZoom: Float = 0f) {
        // good enough to show without asking: the walls fit well, and much better than any other room's
        val sure get() = score >= MIN_SCORE && margin >= MIN_MARGIN
    }

    // What Watch has made out so far, look by look (the page's Watch; tested on the PC). Each look votes on every
    // open square it could see: a fixed mirror there, or clear. A square is settled as a fixed mirror after two looks
    // see one there or beside it (a mirror is sometimes read a square off) and more looks see it than not, or as clear
    // after two looks see it clear and none has seen a mirror (or clear looks outnumber mirror ones two to one); behind
    // the caster, where a mirror is often half hidden and missed, three clear looks. Once settled it stays settled:
    // fixed mirrors never move, and a square settled clear that shows a mirror later is one you've carried there.
    // A look that makes out another room is only believed once ROOM_CHANGE_LOOKS looks in a row agree on it. Once the
    // room has settled (`locked`) a full read is only tried when the room stops fitting (a full read from deep in the
    // room, with the camera turned, can be sure of the wrong room several looks in a row: the owner's tries, October
    // 2026).
    // A mirror's facing settles the same way: once two looks agree and no other look has disagreed, it stays.
    // A new layout (after a round, if the seal isn't broken): the walls seen down WALLS_DOWN_LOOKS times clear
    // everything. If that was missed (the camera turned away while you mined), any long gap without seeing the room
    // makes the next looks check the room against what's settled: NEW_LAYOUT_LOOKS looks in a row with a wall cracked
    // that had settled smooth start afresh, which also catches a new layout of the same room (unless no smooth wall
    // became cracked in it).
    class Memory {
        var room = -1
            private set
        var camera: FloatArray? = null     // the last look's camera, for the next quick look (followLook)
            private set
        var zoom = 0f                       // a square's width on screen at the first look of this layout
            private set
        // the cracked walls (1 cracked, 0 smooth, -1 not known), voted on look by look like the squares: the first
        // look often comes while the walls are still rising and misreads some (the owner's recording of a round)
        val cracked: IntArray? get() = if (room < 0) null else IntArray(ToaPuzzles.HET_ROOMS[room].blocks.size) { i ->
            val smooth = crackVotes[i * 2]; val crack = crackVotes[i * 2 + 1]
            when { crack > smooth -> 1; smooth > crack -> 0; else -> -1 }
        }
        private val crackVotes = IntArray(64)      // each wall's looks that saw it smooth, and cracked
        var looks = 0
            private set
        private val fixedVotes = IntArray(N * N)
        private val clearVotes = IntArray(N * N)
        private val settled = ByteArray(N * N)    // 0 not yet, 1 a fixed mirror, 2 clear
        private val facingVotes = IntArray(N * N * 4)   // the looks that saw each square's mirror face each way
        private val facingSettled = ByteArray(N * N) { -1 }   // each square's settled facing (-1 not yet)
        private var otherRoom = -1          // another room some looks in a row have made out, and how many
        private var otherLooks = 0

        var downLooks = 0       // looks in a row that saw the walls down
            private set
        var lostLooks = 0       // looks in a row that read nothing, with the walls up
            private set
        var seal = -1f              // Het's seal's health, as last settled (-1: not seen yet)
            private set
        var broken = false          // the seal's health went to nothing: the room is done
            private set
        private var sealLow = 0     // looks in a row that showed it lower
        private var gap = false     // the room went unseen a while (or the walls were down): it may be a new layout
        private var doubtLooks = 0  // looks in a row since then that disagreed with what's settled

        // What one look did
        enum class Outcome { CHANGED, SAME, WALLS_DOWN, CLEARED, LOST }

        // Takes one of Watch's looks (HetReader.watchLook): a reading is added; walls seen down WALLS_DOWN_LOOKS looks in
        // a row clear what was known (the next layout is read afresh, any room); anything else is a lost look
        fun use(look: Look): Outcome {
            // Het's seal losing health: you're mining it, so the walls are down. The layout is forgotten at once, and
            // the next one is read when it rises (if the seal isn't broken)
            // (a drop has to show on two looks in a row: a beam or a hit splash over the bar can misread it once)
            look.seal?.let { now ->
                // the bar shows up as the walls rise: a room read before it was the bare floor fitting a room by chance
                // (the owner's recordings of 26 October 2026, from the doorway before the room started)
                if (seal < 0f && room >= 0) reset()
                if (seal < 0f || now >= seal - SEAL_DROP) { seal = maxOf(seal, now); sealLow = 0 }
                else if (++sealLow >= 2) {
                    seal = now; sealLow = 0
                    if (now <= SEAL_GONE) broken = true
                    if (room >= 0) { reset(); return Outcome.CLEARED }
                }
            }
            val r = look.reading
            if (r != null && room >= 0 && r.room != room && locked) {
                // another room, read once this one stopped fitting (watchLook): it takes over after ROOM_CHANGE_LOOKS
                // looks in a row (a new layout that rose while the camera was lost), seen at about the zoom this layout
                // was first read at: zoomed in close or out far, part of the room can fit another room well (the owner's
                // recording of 26 October 2026: room 1 read 3 times at 25-28% off while solving room 2, against 7% at most
                // for room 2's own readings)
                if (zoom > 0f && kotlin.math.abs(r.focusZoom / zoom - 1) > ROOM_CHANGE_ZOOM) { lostLooks++; return Outcome.LOST }
                if (r.room == otherRoom) otherLooks++ else { otherRoom = r.room; otherLooks = 1 }
                if (otherLooks < ROOM_CHANGE_LOOKS) { lostLooks++; return Outcome.LOST }
                reset(); downLooks = 0; lostLooks = 0
                add(r)
                return Outcome.CHANGED
            }
            if (r != null) {
                downLooks = 0; lostLooks = 0
                if (gap && room >= 0 && r.room == room) {
                    when (compare(r)) {
                        Compare.DIFFERENT -> {
                            // not added (it may be the old layout misread) until enough looks agree it's a new one
                            if (++doubtLooks < NEW_LAYOUT_LOOKS) return Outcome.SAME
                            reset(); add(r)
                            return Outcome.CHANGED
                        }
                        Compare.SAME -> { gap = false; doubtLooks = 0 }
                        Compare.UNKNOWN -> {}
                    }
                }
                return if (add(r)) Outcome.CHANGED else Outcome.SAME
            }
            if (look.wallsDown) {
                look.camera?.let { camera = it }    // keep following the camera on the bare floor
                gap = true
                // (a lost look in between doesn't start the count again: only seeing the room does)
                if (++downLooks >= WALLS_DOWN_LOOKS && room >= 0) { reset(); return Outcome.CLEARED }
                return Outcome.WALLS_DOWN
            }
            if (++lostLooks >= GAP_LOOKS) gap = true
            return Outcome.LOST
        }

        private enum class Compare { SAME, DIFFERENT, UNKNOWN }

        // Whether a reading of this room is the layout that's settled. Different: a wall settled smooth now cracked (a
        // new layout's walls crack differently; the other way round proves nothing, as you mine cracked walls away, and
        // nor does a settled fixed mirror not seen: from deep in the room or at an angle they're often missed, the
        // owner's recording). The same: the cracks agree and the settled fixed
        // mirrors it could see are in place (one within a square counts).
        private fun compare(r: Reading): Compare {
            var agree = 0; var differ = 0
            cracked?.let { old ->
                for (i in old.indices) {
                    val a = old[i]; val b = r.cracked.getOrElse(i) { -1 }
                    // (only a smooth wall now cracked: cracked walls you've mined away read as smooth)
                    if (a >= 0 && b >= 0) { if (a == b) agree++ else if (a == 0) differ++ }
                }
            }
            for (s in fixed) if (s !in r.unseen && r.fixedMirrors.any { near(it, s) }) agree++
            return when {
                differ >= 1 -> Compare.DIFFERENT
                differ == 0 && agree >= 3 -> Compare.SAME
                else -> Compare.UNKNOWN
            }
        }

        fun reset() {
            room = -1; camera = null; looks = 0; otherRoom = -1; otherLooks = 0; zoom = 0f; crackVotes.fill(0)
            gap = false; doubtLooks = 0
            fixedVotes.fill(0); clearVotes.fill(0); settled.fill(0); facingVotes.fill(0); facingSettled.fill(-1)
        }

        // Adds one look; true if what's settled changed
        fun add(r: Reading): Boolean {
            if (room >= 0 && r.room != room) {
                if (locked) return false
                if (r.room == otherRoom) otherLooks++ else { otherRoom = r.room; otherLooks = 1 }
                if (otherLooks < ROOM_CHANGE_LOOKS) return false
            }
            otherRoom = -1; otherLooks = 0
            if (r.room != room) { reset(); room = r.room; zoom = r.focusZoom }
            camera = r.camera
            looks++
            for (i in r.cracked.indices) if (r.cracked[i] >= 0 && i < crackVotes.size / 2) crackVotes[i * 2 + r.cracked[i]]++
            val wall = walls[room]
            var changed = false
            for (s in 0 until N * N) {
                if (!inside[s] || wall[s] || s in r.unseen) continue
                // (a square next to a mirror this look saw isn't counted clear: a mirror is sometimes read a square
                // off, and that would have settled its real square clear for good: the owner's recording, 18,6)
                if (s in r.fixedMirrors) fixedVotes[s]++
                else if (r.fixedMirrors.none { near(it, s) }) clearVotes[s]++
                r.facing[s]?.let { f ->
                    facingVotes[s * 4 + f]++
                    val all = (0 until 4).sumOf { facingVotes[s * 4 + it] }
                    if (facingSettled[s] < 0 && facingVotes[s * 4 + f] >= 2 && facingVotes[s * 4 + f] == all) {
                        facingSettled[s] = f.toByte(); changed = true
                    }
                }
                if (settled[s].toInt() != 0) continue
                var f = 0
                for (o in 0 until N * N) if (near(o, s)) f += fixedVotes[o]
                val c = clearVotes[s]
                val need = if (behindCaster(s)) 3 else 2
                val now = when {
                    f >= 2 && f > c -> 1
                    c >= need && (f == 0 || c > 2 * f) -> 2
                    else -> 0
                }
                if (now != 0) { settled[s] = now.toByte(); changed = true }
            }
            return changed
        }

        // the room is settled: enough looks have agreed on it (Watch then only follows it)
        val locked get() = looks >= ROOM_CHANGE_LOOKS

        // The fixed mirrors settled so far
        // (two side by side are one mirror read a square off now and then: the one seen more often is kept. Behind the
        // caster there's one fixed mirror in every layout, on the beam's line (the owner's recordings and the RuneLite-
        // known raid, October 2026), read anywhere from 2,10 to 5,10: only the one seen most is kept there)
        val fixed get() = (0 until N * N).filter { s ->
            settled[s].toInt() == 1 && (0 until N * N).none { o ->
                o != s && settled[o].toInt() == 1 && (near(o, s) || (behindCaster(o) && behindCaster(s))) &&
                    (fixedVotes[o] > fixedVotes[s] || (fixedVotes[o] == fixedVotes[s] && o < s))
            }
        }
        // ...and which way they face: the settled facing, or else the way at least two thirds of the looks saw (else
        // not known: the page then asks you to check it)
        val facing: Map<Int, Int> get() = fixed.mapNotNull { s ->
            if (facingSettled[s] >= 0) return@mapNotNull s to facingSettled[s].toInt()
            val votes = (0 until 4).map { facingVotes[s * 4 + it] }
            val best = votes.indices.maxByOrNull { votes[it] }!!
            if (votes[best] > 0 && votes[best] * 3 >= votes.sum() * 2) s to best else null
        }.toMap()
        // The open squares not settled yet (never seen, or seen only once with a mirror)
        val unseen get() = if (room < 0) emptyList() else (0 until N * N).filter { inside[it] && !walls[room][it] && settled[it].toInt() == 0 }
    }

    const val SEAL_DROP = 0.02f         // a drop in the seal's health this big (out of 1) is a hit
    const val SEAL_GONE = 0.01f         // ...and this little left is broken
    const val WALLS_DOWN_LOOKS = 4      // looks that must see the walls down (with no look at the room between) before
                                        // Watch forgets the layout
    const val GAP_LOOKS = 3             // lost looks in a row after which the room is checked for a new layout...
    const val NEW_LAYOUT_LOOKS = 3      // ...and the looks in a row that must disagree with it to start afresh
    private const val EMPTY_FIT = 0.88f     // the room with no walls must fit this well (see followLook; the owner's
                                            // recording: 0.92-0.97 with the walls down)...
    private const val EMPTY_MARGIN = 0.08f  // ...and this much better than the room with its walls, which must still
    private const val WALLED_FIT = 0.5f     // fit this well (its floor is right: 0.58-0.67 with the walls down)
    private val NO_WALLS = BooleanArray(N * N)  // the room with its walls down
    private const val DOWN_ZOOM = 0.15f         // how far the bare room's size may be from the first read's
    private const val DOWN_WALL_POINTS = 12     // wall squares that must be on screen to tell the walls are down...
    private const val DOWN_STONE_PERCENT = 20   // ...and the most of them that may still show stone
    const val ROOM_CHANGE_LOOKS = 3     // looks in a row that must agree on another room before Watch believes it...
    private const val ROOM_CHANGE_ZOOM = 0.15f  // ...each with the room's size on screen this close to the first read's
    const val MIN_WALLS_UP = 0.5f    // the share of the room's walls that must show standing (see wallsUp)
    const val MIN_SCORE = 0.75f     // the right room scored 0.84-0.94 on the owner's screenshots; a 35° turned camera 0.60
    const val MIN_MARGIN = 0.12f    // ... and beat the next best by 0.4 or more
    private const val N = ToaPuzzles.HET_SIZE
    private const val BASE_SCALE = 28.8f    // a square's width in pixels at the room's middle (standard camera, 1080 tall)
    private const val PERSPECTIVE = 0.018f  // how much smaller each square further away looks
    private const val MIN_SEEN = 80         // squares that must be seen (wall or floor) for a guess to count

    private const val CRACK_SHARE = 0.06f  // a wall with more very dark pixels than this is cracked: smooth walls showed
                                            // 0-1% on the owner's screenshots, cracked ones 20% or more
    private const val FLOOR_SHADE = 88.0   // the floor's typical shade at the middle brightness setting (86-92 on the
                                           // owner's 50% screenshots; 64 at the lowest setting, 128 at the highest)
    private const val FLOOR_HIDDEN = 2f     // a floor point this many squares behind a wall is hidden by it (refine)
    private const val SHADOW_BASE = 0.6f   // ...and behind the caster, with a mirror's disc above it (readFixedMirrors)
    private const val MIRROR_BASE = 0.85f  // a mirror's base: its box is this much more gold than beside it (mirrors
                                           // 0.90-1.0 on the owner's screenshots, the statues' and walls' gold 0.76 or less)
    // where the three movable mirrors (A-frames) stand when the room starts (5,1, 5,19 and 19,15), and the squares
    // round them (an A-frame's base can read a square off)
    private val MIRROR_STARTS = listOf(5 to 1, 5 to 19, 19 to 15).flatMap { (x, y) ->
        (x - 1..x + 1).flatMap { a -> (y - 1..y + 1).map { b -> a * N + b } }
    }.toSet()
    // The mirrors' discs, measured from the game's own models (.scratch/toa-helper/het-mirror-models.md): the disc's
    // middle stands 2.09 squares above the floor. And where the game's camera is, seen from the side: about 6.6
    // squares behind the square it looks at (the middle of the screen) and 15.8 above it (the same numbers fit the
    // owner's phone screenshots best, and the desktop game's camera at its steepest)
    private const val DISC_HEIGHT = 2.09f
    private const val CAMERA_BACK = 6.6f
    private const val CAMERA_UP = 15.8f
    private const val FACING_FIT = 0.5f      // a disc's shape must fit one way this well (0 = exactly; the owner's
                                             // screenshots fit 0.04-0.43)...
    private const val FACING_MARGIN = 0.4f   // ...and fit it this much better than the other way it could face
    private const val OTHER: Byte = 0
    private const val STONE: Byte = 1
    private const val SAND: Byte = 2

    // Whether each square is floor to look at (the room's real floor, less the three statues across the middle),
    // and each room's walls
    private val inside = BooleanArray(N * N) { s ->
        val x = s / N; val y = s % N
        ToaPuzzles.isHetFloor(x, y) && !(x in 6..14 && y in 9..11)
    }
    private val walls = ToaPuzzles.HET_ROOMS.map { r -> BooleanArray(N * N) { r.walls[it] != 0 } }
    // the squares just west of the caster, hidden behind it from the doorway, where every way starts
    private fun behindCaster(s: Int) = s / N in 2..5 && s % N in 9..11
    // two squares side by side or corner to corner (or the same one)
    private fun near(a: Int, b: Int) = kotlin.math.abs(a / N - b / N) <= 1 && kotlin.math.abs(a % N - b % N) <= 1
    // the squares under the caster and the shielded statue (their slate plinths; Het's seal between them is pale)
    private fun isStatue(s: Int) = (s / N in 6..8 || s / N in 12..14) && s % N in 9..11

    // A picture, sorted: each pixel's kind (stone, sand, other), wall shade and gold, at half size
    // (and the picture itself, with its brightness curve, for the mirrors' discs, which are read at full size)
    private class Picture(val kind: ByteArray, val shade: ByteArray, val gold: BooleanArray, val w: Int, val h: Int, val k: Float,
                          val px: LightBoxReader.PixelSource, val tone: IntArray)

    private fun sort(px: LightBoxReader.PixelSource): Picture {
        val k = px.height / 1080f   // pictures from other screen sizes
        val w = px.width / 2; val h = px.height / 2
        // Half size is plenty: every other pixel, sorted
        val tone = brightnessCurve(px)
        val kind = ByteArray(w * h)
        // and for telling cracked walls from smooth ones: any wall stone (0 not stone, 1 stone, 2 very dark stone, as
        // in the cracks of a cracked wall)
        val shade = ByteArray(w * h)
        // and the gold of the mirrors (and the statues' gold trim)
        val gold = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px.rgb(x * 2, y * 2)
            val r = tone[c shr 16 and 0xFF]; val g = tone[c shr 8 and 0xFF]; val b = tone[c and 0xFF]
            kind[y * w + x] = when {
                b in 45..115 && b > r + 6 && r < 95 && g < 100 -> STONE
                r > b + 15 && r in 85..200 && g >= 75 -> SAND
                else -> OTHER
            }
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            if (b > r + 4 && r < 120 && lum < 90) shade[y * w + x] = if (lum < 26) 2 else 1
            // gold, lit or shaded (the mirrors' cones are shaded down to about (114, 81, 8))
            gold[y * w + x] = r >= 90 && b * 10 <= r * 4 && g * 2 >= r && g * 100 <= r * 85
        }
        return Picture(kind, shade, gold, w, h, k, px, tone)
    }

    // `room`: only this room is looked for (Watch, once it knows the room, after a quick look lost it: the camera turned
    // too fast to follow). Then the reading is null unless the room fits well.
    fun read(px: LightBoxReader.PixelSource, room: Int = -1): Reading? {
        val pic = sort(px)
        val kind = pic.kind; val w = pic.w; val h = pic.h; val k = pic.k
        val (mx, my) = floorMiddle(kind, w, h) ?: return null
        // A wide look first (every other floor square, the camera facing every way round, shared out between the
        // phone's processor cores); then a closer look around the best room's camera
        val search = wideSearch(kind, w, h, BASE_SCALE * k, steps(mx - 120 * k, mx + 120 * k, 24 * k),
            steps(my - 160 * k, my + 160 * k, 24 * k), steps(0f, 352f, 8f).map { Math.toRadians(it.toDouble()).toFloat() },
            steps(0.7f, 1.3f, 0.1f), listOf(0.6f, 0.73f, 0.86f))
        if (room >= 0) return readAs(pic, search, room, true)
        // the best room of the wide look first; if its camera doesn't hold up (its walls not standing, or no room clearly
        // best), the runner-up's camera (on the owner's recording of 26 October 2026, a new layout of room 2 seen whole
        // with the camera facing north: room 3's camera won the wide look, and nothing fitted with it, 5 looks running)
        val order = walls.indices.sortedByDescending { search.best[it] }
        val best = readAs(pic, search, order[0], false)
        if (best == null || best.sure || search.bestGuess[order[1]] == null) return best
        return readAs(pic, search, order[1], false)?.takeIf { it.sure } ?: best
    }

    // The rest of read, from the wide look's camera for `first`: closer looks round it, then (unless `only`) every room
    // scored with that one camera
    private fun readAs(pic: Picture, search: Search, first: Int, only: Boolean): Reading? {
        val kind = pic.kind; val w = pic.w; val h = pic.h; val k = pic.k
        val g = search.bestGuess[first] ?: return null
        search.run(steps(g[0] - 20 * k, g[0] + 20 * k, 5 * k), steps(g[1] - 20 * k, g[1] + 20 * k, 5 * k),
            steps(g[5] - 0.0524f, g[5] + 0.0524f, 0.0262f), steps(g[2] - 0.06f, g[2] + 0.06f, 0.03f),
            listOf(g[3] - 0.05f, g[3], g[3] + 0.05f), only = first)
        // and a finer one still (the crack check needs the camera close: its points are through each wall)
        fineSearch(search, search.bestGuess[first]!!, k, first)
        if (only) {
            val g1 = search.bestGuess[first]!!
            return if (search.best[first] < MIN_SCORE) null else finish(pic, first, g1, search.best[first], 1f).takeIf { it.sure }
        }
        // It's one picture, so one camera: every room is scored with that camera (allowing only a nudge). Letting each
        // room find its own camera let a wrong room shift it until a few of its thin walls lined up with real ones.
        val c = search.bestGuess[first]!!
        val final = Search(kind, w, h, BASE_SCALE * k)
        final.run(steps(c[0] - 5 * k, c[0] + 5 * k, 5 * k), steps(c[1] - 5 * k, c[1] + 5 * k, 5 * k),
            listOf(c[5] - 0.0131f, c[5], c[5] + 0.0131f), listOf(c[2] - 0.015f, c[2], c[2] + 0.015f), listOf(c[3]))
        val order = walls.indices.sortedByDescending { final.best[it] }
        val g2 = final.bestGuess[order[0]] ?: return null
        return finish(pic, order[0], g2, final.best[order[0]], final.best[order[0]] - final.best[order[1]])
    }

    // How well the room and the same room with no walls fitted the last followLook that lost the room (for the tests)
    var lastEmptyFit = 0f
        private set
    var lastRoomFit = 0f
        private set

    // One of Watch's looks: what it read (null if nothing), and if not, whether the walls are down (and where the
    // camera was found then, to keep following it); and how much health Het's seal has left (0-1, null: not on screen)
    class Look(val reading: Reading?, val wallsDown: Boolean, val camera: FloatArray? = null, val seal: Float? = null)

    // Watch's look at one picture (on any thread), from what Watch knew when it took it (Memory's room, camera, locked
    // and lostLooks): the quick look round the last camera (which also tells when the walls are down). If that lost it:
    // before the room is settled, any room; after, that room alone searched afresh, and once it hasn't fitted for a
    // couple of looks, any room too (a new layout of another room, risen while the camera was lost). Whether the walls
    // are down is only told round the last camera: searched all round, a bare room fit too many views with the walls
    // up, even pinned by the statues and the zoom (the owner's recordings). `zoom`: a square's width on screen when
    // Watch first read the room (see followLook). `sealBefore`: Het's seal's health as Memory last settled it (-1: not
    // seen): while it's lower now, you're mining the obelisk and the walls are down, so there's nothing to read and the
    // look ends there (a search with the room lost took several seconds on the owner's phone, which made Watch slow to
    // see you mining, the owner's recording of 26 October 2026)
    fun watchLook(px: LightBoxReader.PixelSource, room: Int, camera: FloatArray?, locked: Boolean, lost: Int, zoom: Float = 0f,
                  sealBefore: Float = -1f): Look {
        val seal = sealHealth(px)
        if (seal != null && sealBefore >= 0f && seal < sealBefore - SEAL_DROP) return Look(null, false, seal = seal)
        if (room >= 0 && camera != null) {
            val f = followLook(px, room, camera, zoom)
            if (f.reading != null || f.wallsDown) return Look(f.reading, f.wallsDown, f.camera, seal)
        }
        if (!locked) return Look(read(px)?.takeIf { it.sure }, false, seal = seal)
        read(px, room)?.let { return Look(it, false, seal = seal) }
        return Look(if (lost >= 2) read(px)?.takeIf { it.sure } else null, false, seal = seal)
    }

    // How much health Het's seal has left (0-1), from its bar at the top left of the game's screen ("Het's Seal",
    // 119 / 119): a teal fill on a dark track, with a paler stretch for a hit just taken (the owner's recording of a
    // round, 26 October 2026: (0, 206, 166), track (0, 31, 31), a hit (0, 150, 119)). The longest run of bar colours
    // across a row of the top left is found; rows with the numbers written over it are left out by taking the row
    // where the run is longest. Null if there's no bar (before the room starts, or another screen).
    fun sealHealth(px: LightBoxReader.PixelSource): Float? {
        val right = px.width * 3 / 10; val bottom = px.height / 5
        var bestRun = 0; var bestFill = 0
        for (y in 0 until bottom step 2) {
            var run = 0; var fill = 0; var gap = 0
            for (x in 0 until right) {
                val c = px.rgb(x, y)
                val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                val teal = r <= 70 && g >= 170 && b >= 125
                val bar = teal || (r <= 40 && g in 120..169 && b >= 90) || (r <= 20 && g in 18..55 && b in 18..55 && g >= r + 12)
                if (bar) {
                    run += gap + 1; gap = 0
                    if (teal) fill++
                } else if (run > 0 && gap < 3) {
                    gap++
                } else {
                    if (run > bestRun) { bestRun = run; bestFill = fill }
                    run = 0; fill = 0; gap = 0
                }
            }
            if (run > bestRun) { bestRun = run; bestFill = fill }
        }
        if (bestRun < px.width / 20) return null
        return (bestFill.toFloat() / bestRun).coerceIn(0f, 1f)
    }

    // Whether the room's walls are down, seen with this camera (`g`, the bare room's best, round the last camera): the
    // bare room fits well and
    // clearly better than the room with its walls, which still fits its floor, and enough of the room's wall squares
    // are on screen, nearly all showing floor (with only a wall or two in view, the bare room fits nearly as well with
    // them up)
    private fun wallsDown(pic: Picture, room: Int, g: FloatArray, bare: Float, walled: Float): Boolean {
        if (bare < EMPTY_FIT || bare - walled < EMPTY_MARGIN || walled < WALLED_FIT) return false
        val (stone, sand) = wallPoints(room, g, BASE_SCALE * pic.k, pic.kind, pic.w, pic.h)
        return stone + sand >= DOWN_WALL_POINTS && stone * 100 < (stone + sand) * DOWN_STONE_PERCENT
    }

    // While watching (the page's Watch): the room is known and the camera was found a moment ago, so only a small
    // search round it is needed (the camera follows you smoothly as you walk), several times quicker than read.
    // `camera` is the last Reading's. Null if the room doesn't fit well any more (lost: read it afresh next time).
    fun follow(px: LightBoxReader.PixelSource, room: Int, camera: FloatArray): Reading? = followLook(px, room, camera, 0f).reading

    // The same, also telling when the room's walls are down: after the beam reaches the statue the walls and mirrors
    // sink away while you mine the obelisk, and if it isn't destroyed a new layout rises. Round the last camera the
    // room is tried as it is and with no walls at all: the walls are down when the bare one fits well and clearly
    // better (with the walls up, the wall squares show stone). Only round the last camera: searched freely, a bare
    // room fits almost any view, some camera putting only floor on screen (the owner's recording of a whole round,
    // October 2026). While they're down the camera is followed on the bare floor. `firstZoom`: a square's width on screen
    // when the room was first read (0: not known); the bare room must be seen at about that size (squeezed smaller, it
    // fits the bare patches of floor between the walls)
    fun followLook(px: LightBoxReader.PixelSource, room: Int, camera: FloatArray, firstZoom: Float): Look {
        val pic = sort(px)
        val k = pic.k
        // a rough look first (every other square), then a close one
        val rough = Search(pic.kind, pic.w, pic.h, BASE_SCALE * k, everyOther = true, rooms = listOf(walls[room], NO_WALLS), statues = true)
        val zoom = camera[2] / (BASE_SCALE * k)
        rough.run(steps(camera[0] - 40 * k, camera[0] + 40 * k, 10 * k), steps(camera[1] - 40 * k, camera[1] + 40 * k, 10 * k),
            steps(camera[5] - 0.07f, camera[5] + 0.07f, 0.035f), listOf(zoom - 0.05f, zoom, zoom + 0.05f),
            listOf(camera[3] - 0.04f, camera[3], camera[3] + 0.04f))
        rough.bestGuess[0]?.let { g0 -> followRoom(pic, room, g0)?.let { return Look(it, false) } }
        val bare = rough.best[1]; val walled = rough.best[0]
        lastEmptyFit = bare; lastRoomFit = walled
        val g = rough.bestGuess[1]
        val sameZoom = firstZoom <= 0f || g == null || kotlin.math.abs(focusZoom(g, BASE_SCALE * k, pic.px.height) / firstZoom - 1) <= DOWN_ZOOM
        if (g != null && sameZoom && wallsDown(pic, room, g, bare, walled))
            return Look(null, true, floatArrayOf(g[0], g[1], BASE_SCALE * k * g[2], g[3], PERSPECTIVE, g[5]))
        return Look(null, false)
    }

    // The close look of follow, from the rough one's camera
    private fun followRoom(pic: Picture, room: Int, g0: FloatArray): Reading? {
        val k = pic.k
        val search = Search(pic.kind, pic.w, pic.h, BASE_SCALE * k)
        search.run(steps(g0[0] - 10 * k, g0[0] + 10 * k, 5 * k), steps(g0[1] - 10 * k, g0[1] + 10 * k, 5 * k),
            listOf(g0[5] - 0.0175f, g0[5], g0[5] + 0.0175f), listOf(g0[2] - 0.025f, g0[2], g0[2] + 0.025f),
            listOf(g0[3] - 0.02f, g0[3], g0[3] + 0.02f), only = room)
        fineSearch(search, search.bestGuess[room] ?: return null, k, room)
        val g2 = search.bestGuess[room] ?: return null
        if (search.best[room] < MIN_SCORE) return null
        return finish(pic, room, g2, search.best[room], 1f).takeIf { it.sure }
    }

    // The last, finest step of the camera search round `g`, for one room
    private fun fineSearch(search: Search, g: FloatArray, k: Float, room: Int) =
        search.run(steps(g[0] - 6 * k, g[0] + 6 * k, 2 * k), steps(g[1] - 6 * k, g[1] + 6 * k, 2 * k),
            steps(g[5] - 0.0175f, g[5] + 0.0175f, 0.00873f), steps(g[2] - 0.02f, g[2] + 0.02f, 0.01f),
            listOf(g[3] - 0.02f, g[3], g[3] + 0.02f), only = room)

    // The rest of a reading, once the room and its camera are found: cracked walls, the finer camera, the fixed
    // mirrors and the squares that can't be seen
    private fun finish(pic: Picture, room: Int, g2: FloatArray, score: Float, margin: Float): Reading {
        val scale = BASE_SCALE * pic.k
        val cracked = readCracks(ToaPuzzles.HET_ROOMS[room], g2, scale, pic.shade, pic.w, pic.h)
        val g3 = refine(pic.kind, pic.w, pic.h, scale, room, g2)
        val fixed = readFixedMirrors(room, g3, scale, pic)
        val unseen = unseenSquares(room, g3, scale, pic.kind, pic.gold, pic.w, pic.h).filter { it !in fixed }
        // the room's walls must be standing: with them down (before they rise, or after the beam reached the statue),
        // the bare floor fits the room with the fewest walls well
        val up = wallsUp(room, g3, scale, pic.kind, pic.w, pic.h)
        lastWallsUp = up
        return Reading(room, if (up < MIN_WALLS_UP) 0f else score, margin, cracked, fixed, unseen,
            floatArrayOf(g3[0], g3[1], scale * g3[2], g3[3], PERSPECTIVE, g3[5]), readFacings(g3, scale, fixed, pic),
            focusZoom(g3, scale, pic.px.height))
    }

    // A finer fit of the floor, once the room is known. The search above looks only at each square's middle and
    // guesses which squares the walls hide, which leaves the floor grid sitting up to a square too far up the screen
    // (the walls' faces pull it up: found against the RuneLite plugin's exact squares, October 2026). Here 16 points
    // in every square are looked at, and a floor point counts as hidden when a wall stands within FLOOR_HIDDEN squares
    // in front of it (towards the camera): walls are about two squares tall. Only the floor's position, zoom, tilt
    // and a hair of turn are adjusted, a few at a time.
    private fun refine(kind: ByteArray, w: Int, h: Int, scale: Float, room: Int, g: FloatArray): FloatArray {
        val wall = walls[room]
        val fx = cos(g[5]); val fy = sin(g[5])
        // (plain number arrays: they're gone through hundreds of times)
        val count = inside.count { it } * 16
        val px = FloatArray(count); val py = FloatArray(count); val want = BooleanArray(count)
        var n = 0
        for (s in 0 until N * N) {
            if (!inside[s]) continue
            for (i in 0 until 4) for (j in 0 until 4) {
                val x = s / N + (i + 0.5f) / 4; val y = s % N + (j + 0.5f) / 4
                var stone = wall[s]
                var t = 0.1f
                while (!stone && t <= FLOOR_HIDDEN) {
                    val qx = floor(x - fx * t).toInt(); val qy = floor(y - fy * t).toInt()
                    if (qx in 0 until N && qy in 0 until N && wall[qx * N + qy]) stone = true
                    t += 0.1f
                }
                px[n] = x; py[n] = y; want[n] = stone; n++
            }
        }
        fun score(c: FloatArray): Float {
            val cf = cos(c[5]); val sf = sin(c[5])
            val s = scale * c[2]
            var sum = 0; var seen = 0
            for (i in px.indices) {
                val dx = px[i] - 10.5f; val dy = py[i] - 10.5f
                val f = dx * cf + dy * sf; val r = dx * sf - dy * cf
                val d = 1 + PERSPECTIVE * f
                val ix = floor((c[0] + s * r / d) / 2).toInt(); val iy = floor((c[1] - s * c[3] * f / d) / 2).toInt()
                if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue
                val kd = kind[iy * w + ix]
                if (kd == OTHER) continue
                seen++
                sum += if ((kd == STONE) == want[i]) 1 else -1
            }
            return if (seen < MIN_SEEN * 8) -9f else sum.toFloat() / seen
        }
        val k = h * 2 / 1080f
        var best = g.copyOf(); var bestScore = score(best)
        fun tryAll(cxs: List<Float>, cys: List<Float>, zooms: List<Float>, tilts: List<Float>, phis: List<Float>) {
            val c0 = best.copyOf()
            for (cx in cxs) for (cy in cys) for (zoom in zooms) for (tilt in tilts) for (phi in phis) {
                val c = floatArrayOf(c0[0] + cx, c0[1] + cy, c0[2] * zoom, tilt, PERSPECTIVE, c0[5] + phi)
                val sc = score(c)
                if (sc > bestScore) { bestScore = sc; best = c }
            }
        }
        val none = listOf(0f)
        // up and down, tilt and zoom together (they trade off against each other), then sideways and the turn, then
        // all of them a little
        tryAll(none, steps(-12 * k, 24 * k, 2 * k), listOf(0.96f, 0.98f, 1f, 1.02f), steps(g[3] - 0.09f, g[3] + 0.09f, 0.03f), none)
        tryAll(steps(-9 * k, 9 * k, 3 * k), none, none.map { 1f }, listOf(best[3]), listOf(-0.01f, 0f, 0.01f))
        tryAll(steps(-2 * k, 2 * k, 1 * k), steps(-3 * k, 3 * k, 1 * k), listOf(0.99f, 1f, 1.01f), listOf(best[3] - 0.01f, best[3], best[3] + 0.01f), none)
        return best
    }

    // The fixed mirrors. Every mirror stands on a square gold plate with something solid on it: a fixed mirror's cone,
    // or the foot of a movable mirror's A-frame. So at each open floor square (the finer camera puts the floor within
    // about a third of a square) a small box just above where its plate would be is looked for that is mostly gold
    // while the same height a little to each side isn't (that leaves out the long gold trim along the walls); the
    // box is nudged about by up to half a square to find the best spot. The three movable mirrors always start on
    // the same three squares, in every room (the owner's screenshots and RuneLite, October 2026), so those are left
    // out (so the reading is for a room as you walk in, before anyone moves them).
    // The spot found is the plate's near half, so its square is found from a point 0.4 of a square further on.
    // Behind the caster (seen from the doorway, the squares just west of it, where every way starts) a mirror's base is
    // half hidden by the statue: there a weaker base counts too (SHADOW_BASE), if a mirror's disc shows above it (the
    // caster's own gold has none). The owner's recording of 26 October 2026: a fixed mirror there scored 0.68-0.86.
    private fun readFixedMirrors(room: Int, g: FloatArray, scale: Float, pic: Picture): List<Int> {
        val gold = pic.gold; val w = pic.w; val h = pic.h
        val wall = walls[room]
        // running totals, so any box's gold count is four lookups
        val sum = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                if (gold[y * w + x]) row++
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + row
            }
        }
        // the share of gold in a box of the full-size picture
        fun box(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val a = (x0 / 2).toInt().coerceIn(0, w); val b = (x1 / 2).toInt().coerceIn(0, w)
            val c = (y0 / 2).toInt().coerceIn(0, h); val d = (y1 / 2).toInt().coerceIn(0, h)
            if (b <= a || d <= c) return 0f
            val n = sum[d * (w + 1) + b] - sum[c * (w + 1) + b] - sum[d * (w + 1) + a] + sum[c * (w + 1) + a]
            return n.toFloat() / ((b - a) * (d - c))
        }
        val fx = cos(g[5]); val fy = sin(g[5])
        val found = HashMap<Int, Float>()   // each square found, and how clearly
        for (s in 0 until N * N) {
            if (!inside[s] || wall[s]) continue
            val (mx, my) = project(g, scale, s / N + 0.5f, s % N + 0.5f, 0f)
            val (ex, _) = project(g, scale, s / N + 0.5f + fy, s % N + 0.5f - fx, 0f)
            val sq = kotlin.math.abs(ex - mx)     // a square's width on screen here
            var best = 0f; var bx = 0f; var by = 0f
            for (oy in -6..6) for (ox in -4..4) {
                val x = mx + ox * 0.1f * sq; val y = my + oy * 0.075f * sq
                val top = y - 0.55f * sq; val bottom = y - 0.1f * sq
                val middle = box(x - 0.3f * sq, top, x + 0.3f * sq, bottom)
                val side = maxOf(box(x - 0.9f * sq, top, x - 0.55f * sq, bottom), box(x + 0.55f * sq, top, x + 0.9f * sq, bottom))
                if (middle - side > best) { best = middle - side; bx = x; by = y }
            }
            if (best < MIRROR_BASE) {
                if (best < SHADOW_BASE || !behindCaster(s)) continue
                val top = project(g, scale, s / N + 0.5f, s % N + 0.5f, 4.5f).second
                val bottom = project(g, scale, s / N + 0.5f, s % N + 0.5f, 0.9f).second
                val disc = findDisc(pic, (bx - 0.8f * sq).toInt(), top.toInt(), (bx + 0.8f * sq).toInt(), bottom.toInt(),
                    (0.04f * sq * sq).toInt()) ?: continue
                // ...and the disc is the one above this square, not a mirror's further back (it shows above the nearer
                // squares too): of the squares behind the caster, this is the one whose disc would be nearest it
                val nearest = (2..5).flatMap { x -> (9..11).map { y -> x * N + y } }.minByOrNull { q ->
                    val (qx, qy) = project(g, scale, q / N + 0.5f, q % N + 0.5f, DISC_HEIGHT)
                    (qx - disc.x) * (qx - disc.x) + (qy - disc.y) * (qy - disc.y)
                }
                if (nearest != s) continue
            }
            val spot = floorPoint(g, scale, bx, by) ?: continue
            val x = floor(spot.first + 0.4f * fx).toInt(); val y = floor(spot.second + 0.4f * fy).toInt()
            if (x !in 0 until N || y !in 0 until N) continue
            val sq2 = x * N + y
            if (!inside[sq2] || wall[sq2] || sq2 in MIRROR_STARTS) continue
            if (best > (found[sq2] ?: 0f)) found[sq2] = best
        }
        // One mirror is sometimes found on two squares side by side (its base spans the line between them): only the
        // clearer one is kept (the owner's recording, October 2026: 7,18 and 7,19; fixed mirrors next to each other
        // weren't seen in any raid)
        return found.keys.filter { s ->
            val x = s / N; val y = s % N
            listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).none { (a, b) ->
                val o = (x + a) * N + (y + b)
                x + a in 0 until N && y + b in 0 until N && (found[o] ?: -1f).let { it > found[s]!! || (it == found[s]!! && o < s) }
            }
        }.sorted()
    }

    // Which way each fixed mirror faces, from its disc: a flat round mirror standing on edge, high above the cone. Its
    // front is pale blue-green glass, its back plain brown (both in a gold rim, which looks like the cone's gold and is
    // left out). From the game's models (.scratch/toa-helper/het-mirror-models.md): a mirror facing the camera shows
    // its glass, one facing away its back, and the disc's outline shows which of the two lines it stands along (north-
    // east to south-west, or north-west to south-east). So: the biggest patch of glass or back colour above the square
    // is found, and its shape measured (how it leans, and how thin it is: a disc seen face on is round, one seen edge
    // on a thin line). That's compared with how each of the two ways it could face (glass or back decides which two)
    // would look from where the camera is, and the closer one is the answer, when it fits well and clearly better.
    // Checked against a raid whose answer RuneLite showed (October 2026), and against the same mirrors seen again from
    // other places and from the north side.
    private fun readFacings(g: FloatArray, scale: Float, fixed: List<Int>, pic: Picture): Map<Int, Int> {
        val px = pic.px
        val out = HashMap<Int, Int>()
        val fx = cos(g[5]); val fy = sin(g[5])
        val focus = floorPoint(g, scale, px.width / 2f, px.height / 2f) ?: return out
        val camX = focus.first - fx * CAMERA_BACK; val camY = focus.second - fy * CAMERA_BACK
        for (s in fixed) {
            val x = s / N + 0.5f; val y = s % N + 0.5f
            val (mx, _) = project(g, scale, x, y, 0f)
            val (ex, _) = project(g, scale, x + fy, y - fx, 0f)
            val sq = kotlin.math.abs(ex - mx)     // a square's width on screen here
            // the disc is looked for well above and below where it should be (the camera here is simpler than the
            // game's, which puts tall things further up the screen far away, and less near)
            val top = project(g, scale, x, y, 4.5f).second; val bottom = project(g, scale, x, y, 0.9f).second
            val disc = findDisc(pic, (mx - 0.8f * sq).toInt(), top.toInt(), (mx + 0.8f * sq).toInt(), bottom.toInt(),
                (0.04f * sq * sq).toInt()) ?: continue
            // which way the camera is, from the mirror, and how steeply it looks down at the disc
            var vx = camX - x; var vy = camY - y
            val dist = kotlin.math.sqrt(vx * vx + vy * vy)
            if (dist < 0.5f) continue
            vx /= dist; vy /= dist
            val down = kotlin.math.atan2(CAMERA_UP - DISC_HEIGHT, dist)
            var best = -1; var bestFit = 9f; var nextFit = 9f
            for (f in 0 until 4) {
                // the way it faces: north-east, south-east, south-west, north-west
                val nx = if (f < 2) 0.7071f else -0.7071f; val ny = if (f == 0 || f == 3) 0.7071f else -0.7071f
                if ((nx * vx + ny * vy > 0) != disc.glass) continue    // facing the camera shows the glass
                // the disc's edge-to-edge line along the floor, on screen: across (to the right) and up the screen
                // (further away); and the disc's height, straight up the screen
                val ux = -ny; val uy = nx
                val across = ux * -vy + uy * vx
                val away = -(ux * vx + uy * vy) * sin(down)
                val upright = cos(down)
                val xx = across * across; val yy = away * away + upright * upright; val xy = -across * away
                val lean = -xy / kotlin.math.sqrt(xx * yy + 1e-9f)
                val fit = kotlin.math.abs(lean - disc.lean) + 1.5f * kotlin.math.abs(thinness(xx, yy, xy) - disc.thin)
                if (fit < bestFit) { nextFit = bestFit; bestFit = fit; best = f } else if (fit < nextFit) nextFit = fit
            }
            if (best >= 0 && bestFit < FACING_FIT && nextFit - bestFit > FACING_MARGIN) out[s] = best
        }
        return out
    }

    // A disc as seen: glass or back showing, how it leans (up to +1 like a "/", down to -1 the other way, 0 upright
    // or flat) and how thin it is (its width over its length: 1 round, 0 a line)
    // (and where its middle is in the picture)
    private class Disc(val glass: Boolean, val lean: Float, val thin: Float, val x: Float = 0f, val y: Float = 0f)

    // How thin a shape with these spreads (across, up, and the two together) looks: its narrowest over its widest
    private fun thinness(xx: Float, yy: Float, xy: Float): Float {
        val half = (xx + yy) / 2
        val d = kotlin.math.sqrt(maxOf(0f, half * half - (xx * yy - xy * xy)))
        return kotlin.math.sqrt(maxOf(0f, half - d) / (half + d + 1e-9f))
    }

    // The biggest patch of a mirror disc's glass or back colour in this part of the full-size picture, if it's at least
    // `least` pixels
    private fun findDisc(pic: Picture, left: Int, top: Int, right: Int, bottom: Int, least: Int): Disc? {
        val px = pic.px; val tone = pic.tone
        val x0 = maxOf(0, left); val y0 = maxOf(0, top)
        val x1 = minOf(px.width - 1, right); val y1 = minOf(px.height - 1, bottom)
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        if (w < 3 || h < 3) return null
        // 0 neither, 1 glass, 2 back
        val kind = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px.rgb(x0 + x, y0 + y)
            val r = tone[c shr 16 and 0xFF]; val g = tone[c shr 8 and 0xFF]; val b = tone[c and 0xFF]
            val lum = 0.3f * r + 0.59f * g + 0.11f * b
            kind[y * w + x] = when {
                // glass: pale, with a blue-green tint (not the beam's strong green)
                g >= r + 8 && r * 10 >= g * 6 && b >= r - 4 && g >= b - 6 && lum in 70f..235f -> 1
                // the back: brown, with a little more blue than the cone's and the rim's gold
                r in 50..175 && r * 25 <= b * 100 && b * 100 <= r * 50 && r * 65 <= g * 100 && g * 100 <= r * 88 -> 2
                else -> 0
            }
        }
        // the patches (pixels touching side by side), keeping the biggest
        val patch = IntArray(w * h)
        val stack = IntArray(w * h)
        var bestSize = 0; var bestId = 0
        var id = 0
        for (start in kind.indices) {
            if (kind[start].toInt() == 0 || patch[start] != 0) continue
            id++
            var n = 0; var top2 = 0
            stack[top2++] = start; patch[start] = id
            while (top2 > 0) {
                val p = stack[--top2]; n++
                val x = p % w; val y = p / w
                if (x > 0 && kind[p - 1].toInt() != 0 && patch[p - 1] == 0) { patch[p - 1] = id; stack[top2++] = p - 1 }
                if (x < w - 1 && kind[p + 1].toInt() != 0 && patch[p + 1] == 0) { patch[p + 1] = id; stack[top2++] = p + 1 }
                if (y > 0 && kind[p - w].toInt() != 0 && patch[p - w] == 0) { patch[p - w] = id; stack[top2++] = p - w }
                if (y < h - 1 && kind[p + w].toInt() != 0 && patch[p + w] == 0) { patch[p + w] = id; stack[top2++] = p + w }
            }
            if (n > bestSize) { bestSize = n; bestId = id }
        }
        if (bestSize < maxOf(least, 4)) return null
        // its shape: how its pixels spread across, up, and the two together
        var n = 0; var glass = 0; var sx = 0.0; var sy = 0.0
        for (p in patch.indices) if (patch[p] == bestId) { n++; sx += p % w; sy += p / w; if (kind[p].toInt() == 1) glass++ }
        val mx = sx / n; val my = sy / n
        var xx = 0.0; var yy = 0.0; var xy = 0.0
        for (p in patch.indices) if (patch[p] == bestId) {
            val dx = p % w - mx; val dy = p / w - my
            xx += dx * dx; yy += dy * dy; xy += dx * dy
        }
        if (xx <= 0 || yy <= 0) return null
        // (the picture's y runs down the screen, so a "/" lean has x and y going opposite ways)
        return Disc(glass * 2 > n, (-xy / kotlin.math.sqrt(xx * yy)).toFloat(), thinness((xx / n).toFloat(), (yy / n).toFloat(), (xy / n).toFloat()),
            (x0 + mx).toFloat(), (y0 + my).toFloat())
    }

    // How much of the room's walls stands: the middle of each wall square, a square up (on the wall's face or top), is
    // looked up in the picture, and this is the share of those that show stone rather than sandy floor (the owner's
    // screenshots with the walls up: 0.8-1; the bare floor before they rise: about 0)
    private fun wallsUp(room: Int, g: FloatArray, scale: Float, kind: ByteArray, w: Int, h: Int): Float {
        val (stone, sand) = wallPoints(room, g, scale, kind, w, h)
        return if (stone + sand < 8) 0f else stone.toFloat() / (stone + sand)
    }

    // ...as counts: the wall squares that show stone, and those that show sandy floor
    private fun wallPoints(room: Int, g: FloatArray, scale: Float, kind: ByteArray, w: Int, h: Int): Pair<Int, Int> {
        var stone = 0; var sand = 0
        for (s in 0 until N * N) {
            if (!walls[room][s] || !inside[s]) continue
            val (sx, sy) = project(g, scale, s / N + 0.5f, s % N + 0.5f, 1f)
            val ix = floor(sx / 2).toInt(); val iy = floor(sy / 2).toInt()
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue
            when (kind[iy * w + ix]) { STONE -> stone++; SAND -> sand++ }
        }
        return stone to sand
    }

    // The open floor squares that can't be seen: where less than half of the square's middle shows as sand or gold
    // (a mirror's plate). From the doorway the squares just west of the caster are always hidden by it, and every way
    // starts there, so the page asks for a second look from another side when one of these is on the way.
    private fun unseenSquares(room: Int, g: FloatArray, scale: Float, kind: ByteArray, gold: BooleanArray, w: Int, h: Int): List<Int> {
        val wall = walls[room]
        val out = ArrayList<Int>()
        for (s in 0 until N * N) {
            if (!inside[s] || wall[s]) continue
            var n = 0; var shows = 0
            for (i in 0..4) for (j in 0..4) {
                val (sx, sy) = project(g, scale, s / N + 0.1f + 0.2f * i, s % N + 0.1f + 0.2f * j, 0f)
                n++
                val ix = floor(sx / 2).toInt(); val iy = floor(sy / 2).toInt()
                if (ix in 0 until w && iy in 0 until h && (kind[iy * w + ix] == SAND || gold[iy * w + ix])) shows++
            }
            if (shows * 2 < n) out.add(s)
        }
        return out
    }

    // A square's width on screen at the middle of the screen, where the camera looks (at you): the game's camera
    // always stands the same way back from you, so this stays the same until you zoom, whichever way it faces
    private fun focusZoom(g: FloatArray, scale: Float, height: Int): Float {
        val t = (g[1] - height / 2f) / (scale * g[2] * g[3])
        if (1 - PERSPECTIVE * t <= 0) return 0f
        val f = t / (1 - PERSPECTIVE * t)
        return scale * g[2] / (1 + PERSPECTIVE * f)
    }

    // The point of the floor under a point of the full-size picture (the camera's sums run backwards)
    private fun floorPoint(g: FloatArray, scale: Float, sx: Float, sy: Float): Pair<Float, Float>? {
        val s = scale * g[2]
        val t = (g[1] - sy) / (s * g[3])                 // f / (1 + PERSPECTIVE f)
        if (1 - PERSPECTIVE * t <= 0) return null
        val f = t / (1 - PERSPECTIVE * t)
        val r = (sx - g[0]) * (1 + PERSPECTIVE * f) / s
        val fx = cos(g[5]); val fy = sin(g[5])
        return (10.5f + f * fx + r * fy) to (10.5f + f * fy - r * fx)
    }

    // Each wall of the room, cracked or smooth: points all through the wall (27 to a square: across it, and from
    // near the floor to near its top) are looked up in the picture, and a wall whose stone is more than CRACK_SHARE
    // very dark is cracked (the cracks are near black; smooth walls have none that dark)
    private fun readCracks(room: ToaPuzzles.HetRoom, g: FloatArray, scale: Float, shade: ByteArray, w: Int, h: Int) =
        IntArray(room.blocks.size) { i ->
            val r = room.blocks[i]
            var stone = 0; var dark = 0
            for (x in r[0]..r[1]) for (y in r[2]..r[3]) {
                if (!ToaPuzzles.isHetFloor(x, y)) continue
                for (u in 1..3) for (v in 1..3) for (z in 1..3) {
                    val (sx, sy) = project(g, scale, x + u * 0.25f, y + v * 0.25f, z * 0.4f)
                    val ix = floor(sx / 2).toInt(); val iy = floor(sy / 2).toInt()
                    if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue
                    when (shade[iy * w + ix].toInt()) { 1 -> stone++; 2 -> { stone++; dark++ } }
                }
            }
            when {
                stone < 15 -> -1
                dark > stone * CRACK_SHARE -> 1
                else -> 0
            }
        }

    // Where a point of the room (x, y in squares, z up from the floor in squares) is in the full-size picture, for
    // the camera g (the room's middle on screen, zoom, tilt, perspective, facing angle)
    private fun project(g: FloatArray, scale: Float, x: Float, y: Float, z: Float): Pair<Float, Float> {
        val fx = cos(g[5]); val fy = sin(g[5])
        val dx = x - 10.5f; val dy = y - 10.5f
        val f = dx * fx + dy * fy
        val r = dx * fy - dy * fx
        val d = 1 + PERSPECTIVE * f
        val s = scale * g[2]
        val up = kotlin.math.sqrt(maxOf(0f, 1 - g[3] * g[3]))
        return (g[0] + s * r / d) to (g[1] - s * g[3] * f / d - s * up * z / d)
    }

    // The game's brightness setting bends every colour along one curve (colour = 255 * (colour / 255) ^ power: the
    // owner's screenshots at the lowest, middle and highest setting fit it exactly, power 0.74 and 1.55). The colours
    // above were picked at the middle setting, so the picture is bent back to it: the sandy floor in the middle of the
    // screen (warm pixels: redder than blue) has a typical shade of FLOOR_SHADE at the middle setting. Which pixels look
    // warm shifts a little as the curve squashes the colours together, so the curve is found in a few rounds: bend,
    // measure the floor again, adjust (it lands within 0.005 of the true power). A table: colour in -> colour as at
    // the middle setting.
    private fun brightnessCurve(px: LightBoxReader.PixelSource): IntArray {
        val ys = px.height / 10 until px.height * 9 / 10 step 4
        val xs = px.width / 4 until px.width * 72 / 100 step 4
        val samples = IntArray(ys.count() * xs.count())
        var n = 0
        for (y in ys) for (x in xs) samples[n++] = px.rgb(x, y)
        val count = IntArray(256)
        var power = 1.0
        repeat(5) {
            val bend = IntArray(256) { v -> (255 * (v / 255.0).pow(power)).roundToInt().coerceIn(0, 255) }
            count.fill(0)
            var n = 0
            for (c in samples) {
                val r = bend[c shr 16 and 0xFF]; val g = bend[c shr 8 and 0xFF]; val b = bend[c and 0xFF]
                if (r > b + 10 && r >= g && r > 25) { count[((3 * r + 6 * g + b) / 10).coerceIn(0, 255)]++; n++ }
            }
            if (n < 500) return IntArray(256) { it }   // not enough floor to tell: leave the colours as they are
            var seen = 0; var median = 0
            while (median < 255 && seen + count[median] < n / 2) { seen += count[median]; median++ }
            if (median < 8 || median > 247) return IntArray(256) { it }
            power = (power * ln(FLOOR_SHADE / 255.0) / ln(median / 255.0)).coerceIn(0.4, 2.5)
        }
        lastPower = power
        // (at the middle setting the picture is left exactly as it is; the setting doesn't change the game's side
        // panels and buttons, which get bent too, so they're kept out of the floor search, floorMiddle)
        if (kotlin.math.abs(power - 1) < 0.05) return IntArray(256) { it }
        return IntArray(256) { v -> (255 * (v / 255.0).pow(power)).roundToInt().coerceIn(0, 255) }
    }

    // The brightness curve found for the last picture read (for the tests: 1 at the middle setting)
    var lastPower = 1.0
        private set

    // ...and how much of the last room read's walls stood (for the tests)
    var lastWallsUp = 1f
        private set

    private fun steps(from: Float, to: Float, step: Float): List<Float> {
        val out = ArrayList<Float>()
        var v = from
        while (v <= to + step * 0.01f) { out.add(v); v += step }
        return out
    }

    // The middle of the sandy floor (the biggest patches of sand, joined up, at an eighth of full size), in full-size pixels
    private fun floorMiddle(kind: ByteArray, w: Int, h: Int): Pair<Float, Float>? {
        val fw = w / 4; val fh = h / 4
        // only the middle of the screen: the game's side panels and buttons are brown too, and at a brightened
        // setting (see brightnessCurve) they look like sand
        val sand = BooleanArray(fw * fh) {
            val x = it % fw
            x in fw * 18 / 100 until fw * 74 / 100 && kind[(it / fw) * 4 * w + x * 4] == SAND
        }
        val patch = IntArray(fw * fh)   // which patch each spot belongs to (0 = none)
        val sizes = ArrayList<Int>().apply { add(0) }
        val stack = IntArray(fw * fh)
        for (start in sand.indices) {
            if (!sand[start] || patch[start] != 0) continue
            val id = sizes.size
            var top = 0; var size = 0
            stack[top++] = start; patch[start] = id
            while (top > 0) {
                val p = stack[--top]; size++
                val x = p % fw; val y = p / fw
                if (x > 0 && sand[p - 1] && patch[p - 1] == 0) { patch[p - 1] = id; stack[top++] = p - 1 }
                if (x < fw - 1 && sand[p + 1] && patch[p + 1] == 0) { patch[p + 1] = id; stack[top++] = p + 1 }
                if (y > 0 && sand[p - fw] && patch[p - fw] == 0) { patch[p - fw] = id; stack[top++] = p - fw }
                if (y < fh - 1 && sand[p + fw] && patch[p + fw] == 0) { patch[p + fw] = id; stack[top++] = p + fw }
            }
            sizes.add(size)
        }
        val biggest = sizes.maxOrNull() ?: 0
        if (biggest < 50) return null
        var sx = 0.0; var sy = 0.0; var n = 0
        for (p in patch.indices) if (patch[p] != 0 && sizes[patch[p]] > biggest * 0.15f) { sx += p % fw; sy += p / fw; n++ }
        return (sx / n * 8).toFloat() to (sy / n * 8).toFloat()
    }

    // The search, keeping each room's best score and the camera that gave it (middle x, middle y, scale, tilt,
    // perspective, facing angle)
    // The wide look, split by camera direction between the processor cores (each part has its own Search), then the
    // best of each room from all of them. The phone is only busy for the moment it takes.
    private fun wideSearch(kind: ByteArray, w: Int, h: Int, scale: Float, cxs: List<Float>, cys: List<Float>,
                           phis: List<Float>, zooms: List<Float>, tilts: List<Float>): Search {
        val parts = Runtime.getRuntime().availableProcessors().coerceIn(1, 8).coerceAtMost(phis.size)
        val searches = List(parts) { Search(kind, w, h, scale, everyOther = true) }
        val threads = List(parts) { i ->
            Thread { searches[i].run(cxs, cys, phis.filterIndexed { j, _ -> j % parts == i }, zooms, tilts) }.apply { start() }
        }
        threads.forEach { it.join() }
        val all = Search(kind, w, h, scale)
        for (s in searches) for (room in walls.indices) {
            if (s.best[room] > all.best[room]) {
                all.best[room] = s.best[room]; all.bestGuess[room] = s.bestGuess[room]
            }
        }
        return all
    }

    // (`rooms`: each room's walls, normally the three rooms; Watch also tries a room with no walls, see followLook.
    // `statues`: the caster's and the shielded statue's squares are looked at too, and must show their slate plinths:
    // that pins the camera when there are no walls to go by)
    private class Search(private val kind: ByteArray, private val w: Int, private val h: Int, private val scale: Float,
                         everyOther: Boolean = false, private val rooms: List<BooleanArray> = walls,
                         private val statues: Boolean = false) {
        // the floor squares looked at: all of them, or every other one like a chessboard (for the quick wide look)
        private val squares = (0 until N * N).filter {
            (inside[it] || (statues && isStatue(it))) && (!everyOther || (it / N + it % N) % 2 == 0)
        }.toIntArray()
        private val minSeen = if (everyOther) MIN_SEEN / 2 else MIN_SEEN
        val best = FloatArray(rooms.size) { -9f }
        val bestGuess = arrayOfNulls<FloatArray>(rooms.size)
        private val rx = FloatArray(N * N)
        private val ry = FloatArray(N * N)
        private val use = Array(rooms.size) { BooleanArray(N * N) }
        private val looksStone = Array(rooms.size) { BooleanArray(N * N) }
        private val sum = IntArray(rooms.size)
        private val seen = IntArray(rooms.size)

        fun run(cxs: List<Float>, cys: List<Float>, phis: List<Float>, zooms: List<Float>, tilts: List<Float>, only: Int = -1) {
            for (phi in phis) {
                val fx = cos(phi); val fy = sin(phi)
                // Walls are one square thick and about two tall, so from the camera a wall also covers the square
                // just behind it (its top and front face): that square looks like stone too. The one behind that may
                // or may not be hidden, so it's skipped.
                val nx = -fx.roundToInt(); val ny = -fy.roundToInt()
                for (room in rooms.indices) {
                    val wall = rooms[room]
                    for (s in 0 until N * N) {
                        fun wallAt(step: Int): Boolean {
                            val x = s / N + nx * step; val y = s % N + ny * step
                            return x in 0 until N && y in 0 until N && wall[x * N + y]
                        }
                        val looks = wall[s] || wallAt(1)
                        looksStone[room][s] = looks
                        use[room][s] = inside[s] && (looks || !wallAt(2))
                    }
                }
                for (zoom in zooms) for (tilt in tilts) {
                    // where each square's middle is on screen, from the room's middle
                    for (s in squares) {
                        val dx = s / N + 0.5f - 10.5f; val dy = s % N + 0.5f - 10.5f
                        val f = dx * fx + dy * fy           // further away
                        val r = dx * fy - dy * fx           // to the right
                        val d = 1 + PERSPECTIVE * f
                        rx[s] = scale * zoom * r / d
                        ry[s] = -scale * zoom * tilt * f / d
                    }
                    for (cx in cxs) for (cy in cys) {
                        sum.fill(0); seen.fill(0)
                        for (s in squares) {
                            val ix = floor((cx + rx[s]) / 2).toInt(); val iy = floor((cy + ry[s]) / 2).toInt()
                            if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue
                            val kd = kind[iy * w + ix]
                            if (statues && isStatue(s)) {
                                // the caster's and the shielded statue's slate plinths (stone), not floor; anything
                                // else (the statues themselves, the app's blanked-out windows) doesn't count
                                if (kd == OTHER) continue
                                for (room in rooms.indices) { seen[room]++; sum[room] += if (kd == STONE) 1 else -1 }
                                continue
                            }
                            if (kd == OTHER) continue
                            val stone = kd == STONE
                            for (room in rooms.indices) {
                                if (!use[room][s]) continue
                                seen[room]++
                                sum[room] += if (stone == looksStone[room][s]) 1 else -1
                            }
                        }
                        for (room in rooms.indices) {
                            if (only >= 0 && room != only) continue
                            if (seen[room] < minSeen) continue
                            val score = sum[room].toFloat() / seen[room]
                            if (score > best[room]) {
                                best[room] = score
                                bestGuess[room] = floatArrayOf(cx, cy, zoom, tilt, PERSPECTIVE, phi)
                            }
                        }
                    }
                }
            }
        }
    }
}
