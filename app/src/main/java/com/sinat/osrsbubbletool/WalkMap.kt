package com.sinat.osrsbubbletool

import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.max

// Real walking distances for the Teleport Finder: which tiles of the game world you can walk between
// (walls, rivers, fences), plus the doors, ladders, stairs, cave entrances, shortcuts, boats, portals and
// levers that join places. From the Shortest Path RuneLite plugin's collision map and transport lists
// (Copyright (c) Skretzo and contributors, BSD 2-Clause License), and dungeon entrances from RuneLite's
// world map (Copyright (c) 2018, Morgan Lewis, BSD 2-Clause License). The full notices are on the app's
// Legal screen. No Android parts, so it can be tested on a computer.
//
// collision: assets/collision.bin, one packed block per 64 x 64 map region. For every tile and plane it
//            holds two bits: "you can step north from here" and "you can step east from here".
// walks:     assets/walks.txt, "@ name" lines and "% requirement" lines, then one line per door/ladder/etc:
//            from x y plane, to x y plane, ticks, goes far or into another area (1/0), name number,
//            requirement number (-1 = none), estimate (1/0). Estimates are joins into areas the walking map
//            has no way into, worked out ahead of time (see the Teleport Finder notes in the README).
// dungeons:  assets/dungeons.txt, one line per dungeon entrance on the surface: x y name.
class WalkMap(collision: ByteArray, walks: String, dungeons: String = "") {

    companion object {
        private const val REGION = 64
        private const val BITS_PER_PLANE = REGION * REGION * 2
        const val MAX_COST = 900                 // stop looking further than this (about 4 1/2 minutes' run)
        private const val MAX_TILES = 3_000_000  // and never visit more tiles than this, to keep it quick
        private const val UNSEEN = Short.MAX_VALUE
        private const val ENTRANCE_REACH = 80    // a known dungeon entrance this close to a cave's footprint may lead into it
        private const val GUESS_COST = 10        // finding and using a way in that isn't on the walking map
        private const val NAMED_COST = 25        // from a dungeon entrance named like the place to somewhere inside
        private const val WALL_COST = 8          // squeezing past a door, web or barrier the walking map doesn't know opens
        private const val FEW = 5                // fewer teleports than this reached from a closed-off place: try squeezing out
        // words that don't tell dungeon entrances apart
        private val COMMON = setOf("the", "of", "and", "dungeon", "cave", "caves", "cavern", "lair", "basement", "entrance",
            "island", "levels", "level", "north", "south", "east", "west", "secret", "upper", "lower", "tunnel", "tunnels")
        private fun words(s: String) = s.lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 && it !in COMMON }.toSet()
        const val NO_WAY = -1                     // "via" for routes that don't use any notable way in

        fun pack(x: Int, y: Int, z: Int) = x or (y shl 14) or (z shl 28)
        fun px(p: Int) = p and 0x3FFF
        fun py(p: Int) = (p ushr 14) and 0x3FFF
        fun pz(p: Int) = (p ushr 28) and 3
    }

    // ---------------- The collision map ----------------

    private class Block(val offset: Int, val compressedSize: Int, val size: Int)
    private val data = collision
    private val blocks = HashMap<Int, Block>()
    private val decoded = arrayOfNulls<ByteArray>(256 * 256)   // regions unpacked so far, by region x and y
    private val missing = BooleanArray(256 * 256)                // regions with no map data

    init {
        var i = 8   // after the "OBTC" mark and the region count
        val count = readInt(4)
        repeat(count) {
            val rx = readShort(i); val ry = readShort(i + 2)
            val size = readInt(i + 4); val csize = readInt(i + 8)
            blocks[(rx shl 16) or ry] = Block(i + 12, csize, size)
            i += 12 + csize
        }
    }
    private fun readShort(i: Int) = ((data[i].toInt() and 255) shl 8) or (data[i + 1].toInt() and 255)
    private fun readInt(i: Int) = (readShort(i) shl 16) or readShort(i + 2)

    private fun region(rx: Int, ry: Int): ByteArray? {
        if (rx !in 0..255 || ry !in 0..255) return null
        val slot = rx * 256 + ry
        decoded[slot]?.let { return it }
        if (missing[slot]) return null
        val b = blocks[(rx shl 16) or ry]
        if (b == null) { missing[slot] = true; return null }
        val out = ByteArray(b.size)
        Inflater().apply { setInput(data, b.offset, b.compressedSize); inflate(out); end() }
        decoded[slot] = out
        return out
    }

    fun covers(x: Int, y: Int) = x >= 0 && y >= 0 && blocks.containsKey(((x / REGION) shl 16) or (y / REGION))

    private fun flag(x: Int, y: Int, z: Int, f: Int): Boolean {
        if (x < 0 || y < 0) return false
        val bits = region(x / REGION, y / REGION) ?: return false
        val bit = z * BITS_PER_PLANE + ((y and 63) * REGION + (x and 63)) * 2 + f
        val byte = bit ushr 3
        return byte < bits.size && (bits[byte].toInt() ushr (bit and 7)) and 1 != 0
    }
    private fun n(x: Int, y: Int, z: Int) = flag(x, y, z, 0)
    private fun e(x: Int, y: Int, z: Int) = flag(x, y, z, 1)
    private fun s(x: Int, y: Int, z: Int) = n(x, y - 1, z)
    private fun w(x: Int, y: Int, z: Int) = e(x - 1, y, z)
    private fun blocked(x: Int, y: Int, z: Int) = !n(x, y, z) && !s(x, y, z) && !e(x, y, z) && !w(x, y, z)
    fun open(x: Int, y: Int, z: Int) = !blocked(x, y, z)   // a tile you can stand on

    // ---------------- Doors, ladders and the rest ----------------

    private class Walk(val from: Int, val cost: Int, val far: Boolean, val name: Int, val req: Int, val guess: Boolean)
    private val walksTo = HashMap<Int, MutableList<Walk>>()   // by where they come out
    private val names = ArrayList<String>()                   // walk names, then dungeon names
    // What shortcuts, doors and boats need, like "70 Agility" or a quest. The Teleport Finder checks these
    // against your WikiSync levels and quests, and the search leaves out the ones you can't use yet.
    val requirements = ArrayList<String>()

    private class Dungeon(val x: Int, val y: Int, val name: Int)
    private val entrances = ArrayList<Dungeon>()

    init {
        for (line in walks.lineSequence()) {
            if (line.startsWith("@ ")) { names.add(line.substring(2).trim()); continue }
            if (line.startsWith("% ")) { requirements.add(line.substring(2).trim()); continue }
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(' ')
            val f = parts.map { it.toIntOrNull() ?: Int.MIN_VALUE }
            if (f.size < 8 || f.any { it == Int.MIN_VALUE }) continue   // not a line we understand: skip it
            val from = pack(f[0], f[1], f[2]); val to = pack(f[3], f[4], f[5])
            // ticks to tiles: you run two tiles a tick
            walksTo.getOrPut(to) { ArrayList(1) }.add(Walk(from, max(1, f[6] * 2), f[7] == 1, f.getOrElse(8) { NO_WAY },
                f.getOrElse(9) { -1 }, f.getOrElse(10) { 0 } == 1))
        }
        for (line in dungeons.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(' ', limit = 3)
            val x = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val y = parts.getOrNull(1)?.toIntOrNull() ?: continue
            names.add(parts.getOrElse(2) { "Dungeon entrance" })
            entrances.add(Dungeon(x, y, names.size - 1))
        }
    }

    fun name(i: Int): String? = names.getOrNull(i)

    // ---------------- Distances from one place ----------------

    // How many tiles it takes to walk to the target from every tile around it (going through doors,
    // ladders and cave entrances where needed), found by spreading out from the target.
    // via: for each tile, the notable way in or out (cave entrance, boat, portal…) nearest that tile on
    // its route, so a teleport's result can say "via Climb-down Trapdoor". guess: the route goes through a
    // way into a cave that isn't on the walking map, so its distance is an estimate.
    // shutIn: the walking map has no way out of the area around the target (so any routes out are guesses)
    // needs: when the only ways out are ones you can't use yet, what they need (e.g. "70 Agility")
    class Field internal constructor(private val cost: Array<ShortArray?>, private val via: Array<ShortArray?>,
                                     private val guess: Array<BooleanArray?>, val shutIn: Boolean = false,
                                     val needs: List<String> = emptyList()) {
        fun at(x: Int, y: Int, z: Int): Int? {
            val k = slot(x, y, z); if (k < 0) return null
            return cost[k]?.get(idx(x, y))?.toInt()?.takeIf { it != UNSEEN.toInt() }
        }

        class Reach(val tiles: Int, val via: Int, val guess: Boolean)

        // A teleport's landing tile, or the best tile right next to it (landing spots are sometimes on
        // the edge of an object, like a portal or a statue)
        fun near(x: Int, y: Int, z: Int): Reach? {
            var best: Reach? = null
            for (dy in -2..2) for (dx in -2..2) {
                val c = at(x + dx, y + dy, z) ?: continue
                val total = c + max(abs(dx), abs(dy))
                if (best == null || total < best.tiles) {
                    val k = slot(x + dx, y + dy, z); val i = idx(x + dx, y + dy)
                    best = Reach(total, via[k]!![i].toInt(), guess[k]!![i])
                }
            }
            return best
        }
    }

    // stopAt: tiles that matter (where teleports land); the search stops once `want` of them are reached.
    // hint: what the place is called (the wiki page and area), to find its entrance if the walking map
    // has no way out of it.
    // locked: for each requirement number, true if you can't use it yet: those shortcuts, doors and boats are
    // left out. Null to use everything.
    fun from(x0: Int, y0: Int, z0: Int, stopAt: Set<Int> = emptySet(), want: Int = Int.MAX_VALUE, maxCost: Int = MAX_COST,
             hint: String = "", rescue: Boolean = true, locked: BooleanArray? = null): Field {
        val cost = arrayOfNulls<ShortArray>(256 * 256 * 4)
        val via = arrayOfNulls<ShortArray>(256 * 256 * 4)
        val guess = arrayOfNulls<BooleanArray>(256 * 256 * 4)
        val done = arrayOfNulls<BooleanArray>(256 * 256 * 4)   // tiles already counted
        // buckets by cost (each cost is a small whole number, so this is quicker than a priority queue)
        val buckets = arrayOfNulls<IntArray>(maxCost + 1)
        val sizes = IntArray(maxCost + 1)
        var pending = 0
        var cutOff = false   // somewhere was left unexplored because it's further than maxCost
        fun push(x: Int, y: Int, z: Int, c: Int, v: Int, g: Boolean) {
            if (c > maxCost) { cutOff = true; return }
            val k = slot(x, y, z); if (k < 0) return
            val arr = cost[k] ?: ShortArray(4096) { UNSEEN }.also {
                cost[k] = it; via[k] = ShortArray(4096) { NO_WAY.toShort() }; guess[k] = BooleanArray(4096); done[k] = BooleanArray(4096)
            }
            val i = idx(x, y)
            if (arr[i] <= c) return
            arr[i] = c.toShort()
            via[k]!![i] = v.toShort()
            guess[k]!![i] = g
            var b = buckets[c]
            if (b == null) { b = IntArray(64); buckets[c] = b }
            if (sizes[c] == b.size) { b = b.copyOf(b.size * 2); buckets[c] = b }
            b[sizes[c]++] = pack(x, y, z)
            pending++
        }
        // The open tiles close by: map pins and dungeon entrance marks aren't always on a tile you can stand on
        // (an NPC behind a counter, a monster spawn inside a rock, the middle of a cave mouth)
        fun ring(x: Int, y: Int, z: Int, c: Int, v: Int, g: Boolean) {
            for (dy in -4..4) for (dx in -4..4) {
                if ((dx != 0 || dy != 0) && !blocked(x + dx, y + dy, z)) push(x + dx, y + dy, z, c + max(abs(dx), abs(dy)), v, g)
            }
        }
        fun seed(x: Int, y: Int, z: Int, c: Int, v: Int, g: Boolean, always: Boolean) {
            push(x, y, z, c, v, g)
            if (always || blocked(x, y, z)) ring(x, y, z, c, v, g)
        }

        var visited = 0
        var found = 0
        val skipped = HashSet<Int>()   // requirements of ways left out because you can't use them yet
        val settledBelow = ArrayList<Int>()   // underground tiles reached, in case this turns out to be a cave with no mapped way out
        val settled = ArrayList<Int>()        // every tile reached, while there are few (a closed-off area)
        var leaky = false                     // allowed to squeeze through walls (last resort)
        val step = BooleanArray(8)
        val dxs = intArrayOf(-1, 1, 0, 0, -1, 1, -1, 1)
        val dys = intArrayOf(0, 0, -1, 1, -1, -1, 1, 1)

        fun spread(fromCost: Int): Boolean {   // false if it had to stop (too many tiles)
            for (c in fromCost..maxCost) {
                var i = 0
                while (i < sizes[c]) {
                    val p = buckets[c]!![i++]
                    pending--
                    val x = px(p); val y = py(p); val z = pz(p)
                    val k = slot(x, y, z)
                    val ix = idx(x, y)
                    if (cost[k]!![ix].toInt() != c) continue   // reached more cheaply already
                    if (!done[k]!![ix]) {   // the first time here (a later pass can reach it again, more cheaply)
                        done[k]!![ix] = true
                        if (++visited > MAX_TILES) return false
                        if (p in stopAt) found++
                        if (y >= 6400 && settledBelow.size < 300_000) settledBelow.add(p)
                        if (settled.size < 200_000) settled.add(p)
                    }
                    val v = via[k]!![ix].toInt()
                    val g = guess[k]!![ix]

                    // doors, ladders and the rest that come out here: the way back goes to where they start
                    walksTo[p]?.forEach { wk ->
                        if (locked != null && wk.req >= 0 && wk.req < locked.size && locked[wk.req]) skipped.add(wk.req)
                        else push(px(wk.from), py(wk.from), pz(wk.from), c + wk.cost, if (wk.far) wk.name else v, g || wk.guess)
                    }

                    // walking to the 8 tiles around (the same rules the game uses)
                    if (blocked(x, y, z)) {
                        val wB = blocked(x - 1, y, z); val eB = blocked(x + 1, y, z)
                        val sB = blocked(x, y - 1, z); val nB = blocked(x, y + 1, z)
                        step[0] = !wB; step[1] = !eB; step[2] = !sB; step[3] = !nB
                        step[4] = !blocked(x - 1, y - 1, z) && !wB && !sB
                        step[5] = !blocked(x + 1, y - 1, z) && !eB && !sB
                        step[6] = !blocked(x - 1, y + 1, z) && !wB && !nB
                        step[7] = !blocked(x + 1, y + 1, z) && !eB && !nB
                    } else {
                        val nn = n(x, y, z); val ss = s(x, y, z); val ee = e(x, y, z); val ww = w(x, y, z)
                        step[0] = ww; step[1] = ee; step[2] = ss; step[3] = nn
                        step[4] = ss && ww && w(x, y - 1, z) && s(x - 1, y, z)
                        step[5] = ss && ee && e(x, y - 1, z) && s(x + 1, y, z)
                        step[6] = nn && ww && w(x, y + 1, z) && n(x - 1, y, z)
                        step[7] = nn && ee && e(x, y + 1, z) && n(x + 1, y, z)
                    }
                    for (d in 0 until 8) if (step[d]) push(x + dxs[d], y + dys[d], z, c + 1, v, g)
                    if (leaky) for (d in 0 until 4) if (!step[d] && covers(x + dxs[d], y + dys[d])) push(x + dxs[d], y + dys[d], z, c + WALL_COST, v, true)
                }
                buckets[c] = null   // done with this cost
                sizes[c] = 0
                if (found >= want) return true
                if (pending == 0) return true   // nowhere left to go
            }
            return true
        }

        seed(x0, y0, z0, 0, NO_WAY, false, always = false)
        if (!spread(0)) return Field(cost, via, guess)
        // An open tile with hardly anywhere to go (a pin just behind a counter or in a rock's gap): try the
        // open tiles close by as well
        if (pending == 0 && !cutOff && found < want && settled.size < 50) {
            ring(x0, y0, z0, 0, NO_WAY, false)
            if (!spread(1)) return Field(cost, via, guess)
        }
        val shutIn = pending == 0 && !cutOff && found < want
        if (!rescue) return Field(cost, via, guess, shutIn)
        // Closed off because ways out need something you don't have yet: don't guess at another way round
        // that. If no teleport you can use was reached at all, say what's needed.
        if (shutIn && skipped.isNotEmpty())
            return Field(cost, via, guess, true,
                if (found > 0) emptyList() else skipped.sorted().mapNotNull { requirements.getOrNull(it) }.distinct())

        // Shut in: nowhere left to walk, and not enough teleports reached. The walking map has no way out of
        // here (newer areas, lairs reached by a hole or a boat, bosses behind doors), so come out at the most
        // likely dungeon entrance: one named like the area, or failing that, a cave's nearest entrance above
        // it, or failing that, straight above the cave. Those routes are marked as guesses.
        if (shutIn) {
            fun costOf(p: Int) = cost[slot(px(p), py(p), pz(p))]!![idx(px(p), py(p))].toInt()
            var minX = Int.MAX_VALUE; var maxX = 0; var minY = Int.MAX_VALUE; var maxY = 0
            for (p in settledBelow) { minX = minOf(minX, px(p)); maxX = max(maxX, px(p)); minY = minOf(minY, py(p) - 6400); maxY = max(maxY, py(p) - 6400) }
            fun above(d: Dungeon) = settledBelow.isNotEmpty() &&
                d.x in minX - ENTRANCE_REACH..maxX + ENTRANCE_REACH && d.y in minY - ENTRANCE_REACH..maxY + ENTRANCE_REACH
            // for an entrance above the cave: from the cave tile closest to straight below it, which is
            // roughly where it lets you in
            fun entryCost(d: Dungeon): Int {
                if (!above(d)) return NAMED_COST
                var best = Int.MAX_VALUE
                for (p in settledBelow) best = minOf(best, costOf(p) + max(abs(px(p) - d.x), abs(py(p) - 6400 - d.y)))
                return best + GUESS_COST
            }
            var restart = Int.MAX_VALUE
            val areaWords = words(hint)
            val scored = if (areaWords.isEmpty()) emptyList() else entrances.map { d ->
                val w = words(names[d.name]); val hit = w.count { it in areaWords }
                d to (if (hit == 0) -1.0 else hit - 0.5 * (w.size - hit))
            }.filter { it.second >= 0 }
            val best = scored.maxOfOrNull { it.second }
            val named = scored.filter { it.second == best }.map { it.first }
            val candidates = named.ifEmpty { entrances.filter { above(it) } }
            if (candidates.isNotEmpty()) {
                for (d in candidates) {
                    val c = entryCost(d)
                    if (c <= maxCost) { seed(d.x, d.y, 0, c, d.name, true, always = true); restart = minOf(restart, c) }
                }
            } else if (y0 >= 6400) {
                for (p in settledBelow) {
                    val c = costOf(p) + GUESS_COST
                    if (c <= maxCost && !blocked(px(p), py(p) - 6400, 0)) { push(px(p), py(p) - 6400, 0, c, NO_WAY, true); restart = minOf(restart, c) }
                }
            }
            if (restart <= maxCost && !spread(restart)) return Field(cost, via, guess, shutIn)
        }

        // Last resort, if hardly anything was reached: squeeze through walls, at a price for each one
        if (pending == 0 && !cutOff && found < FEW && settled.isNotEmpty() && settled.size < 200_000) {
            leaky = true
            var low = Int.MAX_VALUE
            for (p in settled) {
                val c = cost[slot(px(p), py(p), pz(p))]!![idx(px(p), py(p))].toInt()
                var b = buckets[c]
                if (b == null) { b = IntArray(64); buckets[c] = b }
                if (sizes[c] == b.size) { b = b.copyOf(b.size * 2); buckets[c] = b }
                b[sizes[c]++] = p
                pending++
                low = minOf(low, c)
            }
            settled.clear()
            if (low <= maxCost) spread(low)
        }
        return Field(cost, via, guess, shutIn)
    }
}

// Where a tile's numbers are kept: one block per region and plane
private fun slot(x: Int, y: Int, z: Int): Int {
    val rx = x shr 6; val ry = y shr 6
    return if (x < 0 || y < 0 || rx > 255 || ry > 255 || z !in 0..3) -1 else ((rx * 256 + ry) shl 2) or z
}
private fun idx(x: Int, y: Int) = (y and 63) * 64 + (x and 63)
