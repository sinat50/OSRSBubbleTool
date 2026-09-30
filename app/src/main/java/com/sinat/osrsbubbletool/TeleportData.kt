package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.max

// The Teleport Finder's logic: every teleport destination (from the Shortest Path RuneLite plugin's data,
// bundled as assets/teleports.tsv), finding a place's coordinates on its OSRS Wiki page, and ranking
// teleports by how close they land. No Android parts, so it can be tested on a computer.
// The teleport, entrance and walking data (assets/teleports.tsv, entrances.tsv, collision.bin, walks.txt, dungeons.txt) come from Shortest Path
// (Copyright (c) Skretzo and contributors) and RuneLite's world map (Copyright (c) 2018, Morgan Lewis),
// both BSD 2-Clause License; the full notices are on the app's Legal screen.
object TeleportData {

    class Teleport(val name: String, val category: String, val x: Int, val y: Int, val plane: Int,
                   val requirements: String, val how: String)

    // A place to go: one spot on the map
    // levels: the monster's combat level(s) there, when the wiki gives them ("Level 227", "Levels 90-100")
    // area: where the wiki says it is ("God Wars Dungeon"), used to find the way in to closed-off places
    class Spot(val label: String, val x: Int, val y: Int, val plane: Int, val levels: String = "", val area: String = "")

    // via: the way in or out the route goes through (a cave entrance, a boat…), if any.
    // guess: the route uses a way into a cave that isn't on the walking map, so the distance is an estimate.
    // also: other teleports that land in the same place (a tablet and its spell, a cape and its max cape)
    class Result(val teleport: Teleport, val tiles: Int, val viaEntrance: Boolean, val via: String? = null, val guess: Boolean = false,
                 val also: List<Teleport> = emptyList())

    fun parse(tsv: String): List<Teleport> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 5) return@mapNotNull null
            val x = f[2].toIntOrNull() ?: return@mapNotNull null
            val y = f[3].toIntOrNull() ?: return@mapNotNull null
            Teleport(f[0], f[1], x, y, f[4].toIntOrNull() ?: 0, f.getOrElse(5) { "" }, f.getOrElse(6) { "" })
        }.toList()

    // ---------------- Where things are on the map ----------------

    // The game world: the main surface (y below 4000), caves and dungeons (6400 tiles "above" the
    // place they're under), and separate areas like Zanaris, the Abyss and instances.
    enum class Layer { SURFACE, UNDERGROUND, SEPARATE }

    fun layerOf(x: Int, y: Int): Layer = when {
        x >= 4000 -> Layer.SEPARATE
        y < 4000 -> Layer.SURFACE
        y >= 6400 -> Layer.UNDERGROUND
        else -> Layer.SEPARATE
    }

    private const val ENTRANCE_PENALTY = 25   // guess for finding and going through the way in or out of a dungeon
    private const val SAME_DUNGEON = 60       // underground spots further apart than this are probably different caves
    private const val CAVE_WALK = 60          // how far you can walk inside a cave or other area before it's probably a different one
    private const val LINK_COST = 3           // climbing a ladder, going through a cave entrance…

    private fun cheb(ax: Int, ay: Int, bx: Int, by: Int) = max(abs(ax - bx), abs(ay - by))

    // A rough guess, for places with no known ways in: caves sit 6400 tiles "above" the land they're under.
    // Tiles from a teleport's landing spot to the target (the game moves diagonally as fast as straight,
    // so it's the bigger of the two distances), or null if a teleport can't sensibly get there
    fun distance(t: Teleport, s: Spot): Pair<Int, Boolean>? {
        val tl = layerOf(t.x, t.y)
        val sl = layerOf(s.x, s.y)
        return when {
            tl == sl && tl != Layer.SEPARATE -> {
                val direct = cheb(t.x, t.y, s.x, s.y)
                // underground: only the same dungeon if it's close; otherwise go up and walk between the entrances
                if (tl == Layer.UNDERGROUND && direct > SAME_DUNGEON) cheb(t.x, t.y - 6400, s.x, s.y - 6400) + 2 * ENTRANCE_PENALTY to true
                else direct to false
            }
            tl == Layer.SURFACE && sl == Layer.UNDERGROUND -> cheb(t.x, t.y, s.x, s.y - 6400) + ENTRANCE_PENALTY to true
            tl == Layer.UNDERGROUND && sl == Layer.SURFACE -> cheb(t.x, t.y - 6400, s.x, s.y) + ENTRANCE_PENALTY to true
            tl == Layer.SEPARATE && sl == Layer.SEPARATE -> cheb(t.x, t.y, s.x, s.y).takeIf { it < 400 }?.let { it to false }
            else -> null
        }
    }

    // ---------------- Ways in and out (ladders, cave entrances, stairs to other areas) ----------------

    class Link(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val what: String)

    fun parseLinks(tsv: String): List<Link> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 4) return@mapNotNull null
            Link(f[0].toIntOrNull() ?: return@mapNotNull null, f[1].toIntOrNull() ?: return@mapNotNull null,
                f[2].toIntOrNull() ?: return@mapNotNull null, f[3].toIntOrNull() ?: return@mapNotNull null, f.getOrElse(4) { "" })
        }.toList()

    // Walking in a straight line between two points in the same part of the world.
    // Inside caves only short walks count, since two caves can sit side by side without joining.
    private fun walk(ax: Int, ay: Int, bx: Int, by: Int): Int? {
        val la = layerOf(ax, ay)
        if (la != layerOf(bx, by)) return null
        val d = cheb(ax, ay, bx, by)
        return if (la == Layer.SURFACE || d <= CAVE_WALK) d else null
    }

    // For one target: the fewest tiles to it after using each way in/out (null = can't reach it that way)
    private fun costsTo(links: List<Link>, s: Spot): IntArray {
        val n = links.size
        val cost = IntArray(n) { i -> walk(links[i].toX, links[i].toY, s.x, s.y)?.plus(LINK_COST) ?: Int.MAX_VALUE }
        val done = BooleanArray(n)
        while (true) {
            var best = -1
            for (i in 0 until n) if (!done[i] && cost[i] != Int.MAX_VALUE && (best < 0 || cost[i] < cost[best])) best = i
            if (best < 0) break
            done[best] = true
            val b = links[best]
            for (i in 0 until n) {
                if (done[i]) continue
                val w = walk(links[i].toX, links[i].toY, b.fromX, b.fromY) ?: continue
                val c = w + LINK_COST + cost[best]
                if (c < cost[i]) cost[i] = c
            }
        }
        return cost
    }

    // Tiles from a teleport to the target, going through ways in and out where needed
    private fun route(t: Teleport, s: Spot, links: List<Link>, costs: IntArray): Pair<Int, Boolean>? {
        var best = walk(t.x, t.y, s.x, s.y)
        var via = false
        for (i in links.indices) {
            if (costs[i] == Int.MAX_VALUE) continue
            val w = walk(t.x, t.y, links[i].fromX, links[i].fromY) ?: continue
            val c = w + costs[i]
            if (best == null || c < best) { best = c; via = true }
        }
        return best?.let { it to via }
    }

    // The closest teleports to a spot, closest first. locked: teleports you can't use yet (shown last)
    // links: the known ways in and out; places without any fall back to the rough guess
    fun closest(all: List<Teleport>, s: Spot, count: Int = 10, links: List<Link> = emptyList(),
                locked: (Teleport) -> Boolean = { false }): List<Result> {
        val costs = costsTo(links, s)
        return rank(all.mapNotNull { t -> (route(t, s, links, costs) ?: distance(t, s))?.let { (d, via) -> Result(t, d, via) } }, count, locked)
    }

    // The closest teleports by real walking distance (walls, rivers, doors and ladders counted), from the
    // walking map spread out from the target. Empty when none are within reach of it.
    // The tiles around each teleport's landing spot, for the walking search to stop once enough are reached
    fun landingTiles(all: List<Teleport>): Set<Int> = all.mapTo(HashSet()) { WalkMap.pack(it.x, it.y, it.plane) }

    fun closestOnFoot(all: List<Teleport>, map: WalkMap, field: WalkMap.Field, count: Int = 10,
                      locked: (Teleport) -> Boolean = { false }): List<Result> {
        val found = all.mapNotNull { t ->
            field.near(t.x, t.y, t.plane)?.let { r ->
                val name = if (r.via == WalkMap.NO_WAY) null else map.name(r.via)
                Result(t, r.tiles, r.via != WalkMap.NO_WAY || r.guess, name, r.guess)
            }
        }
        // When there are real routes, leave out guessed ones that are much longer: those are the search
        // squeezing out of a closed-off place, and only clutter the list
        val bestReal = found.filter { !it.guess }.minOfOrNull { it.tiles }
        val kept = if (bestReal == null) found else found.filter { !it.guess || it.tiles <= bestReal * 2 + 100 }
        return rank(kept, count, locked)
    }

    // Closest first (ones you can't use yet last). Teleports landing in the same place are shown as one row,
    // with the others listed under it, so the list shows more different places.
    private fun rank(list: List<Result>, count: Int, locked: (Teleport) -> Boolean): List<Result> {
        val sorted = list.sortedWith(compareBy<Result> { locked(it.teleport) }.thenBy { it.tiles }.thenBy { it.teleport.name.length })
        // a teleport joins the first row whose teleport lands within 3 tiles of it (same plane, and both usable or both not)
        val groups = ArrayList<MutableList<Result>>()
        for (r in sorted) {
            val t = r.teleport
            val lock = locked(t)
            val g = groups.firstOrNull { g -> val h = g.first().teleport
                h.plane == t.plane && locked(h) == lock && max(abs(h.x - t.x), abs(h.y - t.y)) <= 3 }
            if (g != null) g.add(r) else groups.add(arrayListOf(r))
        }
        return groups.take(count).map { g ->
            val first = g.first()
            // versions of the same thing ("Explorer's ring" and "Explorer's ring 2") aren't worth listing twice
            val others = g.drop(1).map { it.teleport }
                .distinctBy { it.name.substringBefore(':').trimEnd('0','1','2','3','4','5','6','7','8','9',' ', '(', ')', 'i') }
                .filter { it.name.substringBefore(':').trimEnd('0','1','2','3','4','5','6','7','8','9',' ', '(', ')', 'i') !=
                    first.teleport.name.substringBefore(':').trimEnd('0','1','2','3','4','5','6','7','8','9',' ', '(', ')', 'i') }
            Result(first.teleport, first.tiles, first.viaEntrance, first.via, first.guess, others)
        }
    }

    // ---------------- Search suggestions ----------------

    // Wiki pages that are never a place to go: sub-pages ("Brutal black dragon/Strategies") and
    // versions of a page about something else ("Brutal black dragon (interface item)", "(music track)"),
    // or about somewhere you can't travel to with teleports (minigame copies, holiday events, other game modes)
    private val NOT_A_PLACE = listOf("item", "interface", "music", "track", "disambiguation", "historical",
        "emote", "animation", "sound", "beta", "deadman", "leagues", "last man standing", "update", "achievement",
        "skill", "spell", "prayer", "unobtainable", "cosmetic", "pet", "follower", "nightmare zone", "cutscene",
        "event", "holiday", "christmas", "halloween", "hallowe'en", "easter", "birthday", "april fools",
        "tutorial", "fight caves wave", "echo", "mirror", "clone", "illusion", "npc contact")
    private val NOT_A_PLACE_RE = NOT_A_PLACE.map { Regex("""\b""" + Regex.escape(it) + """s?\b""") }
    private val YEAR = Regex("""\b(19|20)\d\d\b""")   // "Santa (2019)": a holiday event from that year

    // The suggestions worth showing: pages that are places, one line per place. "Black dragon" already
    // lists every area black dragons live in, so "Black dragon (Wilderness)" and the like are left out
    // when the main page is there too.
    fun usefulSuggestions(titles: List<String>, count: Int): List<String> {
        val kept = titles.filter { t ->
            if ('/' in t) return@filter false
            val bracket = Regex("""\(([^)]*)\)\s*$""").find(t)?.groupValues?.get(1)?.lowercase() ?: return@filter true
            NOT_A_PLACE_RE.none { it.containsMatchIn(bracket) } && !YEAR.containsMatchIn(bracket)
        }.distinctBy { it.lowercase() }
        val bases = kept.filter { '(' !in it }.map { it.lowercase().trim() }.toSet()
        return kept.filter { t ->
            val base = t.substringBefore(" (", t).lowercase().trim()
            base == t.lowercase().trim() || base !in bases
        }.take(count)
    }

    // ---------------- Reading a wiki page ----------------

    // The spots on a wiki page: its map(s), or for monsters, where they spawn (the middle of each area).
    // Pages with several versions (an NPC who moves after a quest) give one spot per version.
    fun spotsFrom(title: String, wikitext: String): List<Spot> {
        val spots = ArrayList<Spot>()
        val versions = HashMap<String, String>()
        // combat level for each version ("|combat2 = 227"), or one for the whole page ("|combat = 227")
        val combat = HashMap<String, String>()
        Regex("""\|\s*combat(\d*)\s*=\s*([^\n|}]+)""").findAll(wikitext).forEach {
            val v = clean(it.groupValues[2])
            if (v.isNotEmpty() && it.groupValues[1] !in combat) combat[it.groupValues[1]] = v
        }
        Regex("""\|\s*version(\d+)\s*=\s*([^\n|}]+)""").findAll(wikitext).forEach {
            val v = clean(it.groupValues[2])
            if (v.isNotEmpty() && it.groupValues[1] !in versions) versions[it.groupValues[1]] = v   // the first real name
        }

        // where it is, for each version ("|location2 = [[God Wars Dungeon]]") or for the whole page
        val where = HashMap<String, String>()
        Regex("""\|\s*location(\d*)\s*=\s*([^\n]+)""").findAll(wikitext).forEach {
            val v = clean(it.groupValues[2].substringBefore("|}}").trimEnd('|', '}', ' '))
            if (v.isNotEmpty() && it.groupValues[1] !in where) where[it.groupValues[1]] = v
        }

        // {{Map|x=3210|y=3448|plane=1}} or {{Map|3203,3434}}, often as "|map2 = {{Map…}}"
        for ((start, body) in templates(wikitext, "Map")) {
            val mapKey = Regex("""\|\s*map(\d*)\s*=\s*$""", RegexOption.IGNORE_CASE).find(wikitext.substring(maxOf(0, start - 20), start))
            var x: Int? = null; var y: Int? = null; var plane = 0
            for (part in topLevelParts(body)) {
                val eq = part.indexOf('=')
                if (eq > 0) {
                    val k = part.substring(0, eq).trim().lowercase()
                    val v = part.substring(eq + 1).trim()
                    when (k) { "x" -> x = v.toIntOrNull(); "y" -> y = v.toIntOrNull(); "plane" -> plane = v.toIntOrNull() ?: 0 }
                } else if (x == null) {
                    Regex("""^\s*(\d+)\s*,\s*(\d+)""").find(part)?.let { x = it.groupValues[1].toIntOrNull(); y = it.groupValues[2].toIntOrNull() }
                }
            }
            val vx = x; val vy = y
            if (vx != null && vy != null) {
                val key = mapKey?.groupValues?.get(1).orEmpty()
                val version = if (key.isNotEmpty()) versions[key] else null
                val label = when {
                    version != null -> "$title ($version)"
                    layerOf(vx, vy) == Layer.UNDERGROUND -> "$title (underground)"
                    else -> title
                }
                spots.add(Spot(label, vx, vy, plane, levelText(combat[key] ?: combat[""]), where[key] ?: where[""] ?: ""))
            }
        }

        // {{LocLine|location=[[Slayer Tower]]|…|x:3437,y:9964|x:…}}: monsters, one spot per area
        for ((_, body) in templates(wikitext, "LocLine")) {
            val coords = Regex("""x:(\d+),y:(\d+)""").findAll(body).mapNotNull { m ->
                val cx = m.groupValues[1].toIntOrNull(); val cy = m.groupValues[2].toIntOrNull()
                if (cx != null && cy != null) cx to cy else null
            }.toList()
            if (coords.isEmpty()) continue
            val params = topLevelParts(body)
            fun param(k: String) = params.firstOrNull { it.trim().startsWith(k, ignoreCase = true) && it.substringBefore('=').trim().equals(k, true) }
                ?.substringAfter('=')?.trim()
            val location = param("location")?.let { clean(it) }
            val plane = param("plane")?.toIntOrNull() ?: 0
            // the spawn nearest the middle, so the spot is a real place the monster stands
            val cx = coords.sumOf { it.first } / coords.size
            val cy = coords.sumOf { it.second } / coords.size
            val (x, y) = coords.minByOrNull { max(abs(it.first - cx), abs(it.second - cy)) }!!
            spots.add(Spot(location?.takeIf { it.isNotEmpty() } ?: title, x, y, plane, levelText(param("levels")?.let { clean(it) }), location ?: ""))
        }

        // the same place twice (e.g. an NPC's map and its spawn list): keep one, with the levels if either has them
        val kept = LinkedHashMap<String, Spot>()
        for (sp in spots) {
            val k = "${sp.x / 8},${sp.y / 8},${sp.plane}"
            val old = kept[k]
            if (old == null) kept[k] = sp
            else if ((old.levels.isEmpty() && sp.levels.isNotEmpty()) || (old.area.isEmpty() && sp.area.isNotEmpty()))
                kept[k] = Spot(old.label, old.x, old.y, old.plane, old.levels.ifEmpty { sp.levels }, old.area.ifEmpty { sp.area })
        }
        return kept.values.toList()
    }

    // "227" → "Level 227"; "90, 100" or "90-100" → "Levels 90–100"; anything that isn't a level → ""
    private fun levelText(raw: String?): String {
        val nums = Regex("""\d+""").findAll(raw ?: return "").mapNotNull { it.value.toIntOrNull() }.filter { it in 1..2000 }.toList()
        if (nums.isEmpty()) return ""
        val lo = nums.min(); val hi = nums.max()
        return if (lo == hi) "Level $lo" else "Levels $lo–$hi"
    }

    // Every {{Name|…}} template in the text (with any templates nested inside it), as (where it starts, what's inside)
    private fun templates(text: String, name: String): List<Pair<Int, String>> {
        val found = ArrayList<Pair<Int, String>>()
        val opener = Regex("""\{\{\s*""" + Regex.escape(name) + """\s*(\||\n|\}\})""", RegexOption.IGNORE_CASE)
        var from = 0
        while (true) {
            val m = opener.find(text, from) ?: break
            var depth = 0
            var i = m.range.first
            var end = -1
            while (i < text.length - 1) {
                if (text[i] == '{' && text[i + 1] == '{') { depth++; i += 2; continue }
                if (text[i] == '}' && text[i + 1] == '}') { depth--; i += 2; if (depth == 0) { end = i; break }; continue }
                i++
            }
            if (end < 0) break
            val inner = text.substring(m.range.first + 2, end - 2)
            found.add(m.range.first to inner.substringAfter('|', ""))
            from = end
        }
        return found
    }

    // Splits "a|b={{x|y}}|c" at the top-level bars only
    private fun topLevelParts(body: String): List<String> {
        val parts = ArrayList<String>()
        var depth = 0
        val cur = StringBuilder()
        var i = 0
        while (i < body.length) {
            if (i < body.length - 1 && body[i] == '{' && body[i + 1] == '{') { depth++; cur.append("{{"); i += 2; continue }
            if (i < body.length - 1 && body[i] == '}' && body[i + 1] == '}') { depth--; cur.append("}}"); i += 2; continue }
            if (body[i] == '|' && depth == 0) { parts.add(cur.toString()); cur.setLength(0) } else cur.append(body[i])
            i++
        }
        parts.add(cur.toString())
        return parts
    }

    // "[[Slayer Tower]]" → "Slayer Tower", "[[Kalphite Lair|the lair]]" → "the lair"
    private fun clean(s: String) = s.replace(Regex("""\[\[(?:[^\]|]*\|)?([^\]]*)\]\]"""), "$1")
        .replace("[[", "").replace("]]", "").replace(Regex("""\{\{[^}]*\}\}"""), "").replace(Regex("<[^>]*>"), "").replace("()", "").replace(Regex("\\s+"), " ").trim()

    // "~40 tiles, 12 s run"
    fun describe(tiles: Int): String = when {
        tiles <= 6 -> "right there"
        else -> "~$tiles tiles · ${Math.max(1, Math.round(tiles * 0.3)).toInt()} s run"
    }

    // ---------------- What you can use ----------------

    // Quests and skill levels the teleport needs, from its requirement text ("25 Magic; Priest in Peril")
    fun unmet(t: Teleport, levels: (String) -> Int?, questDone: (String) -> Boolean?): List<String> {
        if (t.requirements.isBlank()) return emptyList()
        val missing = ArrayList<String>()
        for (part in t.requirements.split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val skill = Regex("""^(\d+)\s+([A-Za-z]+)$""").find(part)
            if (skill != null) {
                val need = skill.groupValues[1].toIntOrNull() ?: continue
                val have = levels(skill.groupValues[2])
                if (have != null && have < need) missing.add(part)
            } else if (questDone(part) == false) missing.add(part)
        }
        return missing
    }
}
