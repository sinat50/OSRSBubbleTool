/*
 * Puzzle solvers where you enter what you see and the app works out the answer. The rules and
 * tables come from the Quest Helper RuneLite plugin (github.com/Zoinkwiz/quest-helper), used
 * under the BSD 2-Clause License. The copyright notices are listed at the top of QuestGuides.kt,
 * and the licence text is at the top of QuestHelperTool.kt and on the app's Legal screen.
 */
package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.max

// The solving logic, kept apart from the screens so it can be tested on its own.
object QuestSolvers {

    // ---------------- The Forsaken Tower: potions ----------------

    // How "Cleansing fluid" appears in the old notes -> which fluid (1-5) to take
    val POTION_CLUES = listOf(
        "Cleansing fluid is directly left of …" to 1,
        "Cleansing fluid is next to …" to 2,
        "… is next to Cleansing fluid" to 3,
        "… is directly right of Cleansing fluid" to 4,
        "Cleansing fluid is directly right of …" to 5
    )

    // ---------------- Lunar Diplomacy: dice ----------------

    // Dice in the order the answers are listed, with the two faces each can show
    val DICE = listOf("Centre west" to "1 or 6", "Centre east" to "6 or 1", "North west" to "5 or 2",
        "South west" to "2 or 5", "North east" to "4 or 3", "South east" to "3 or 4")

    private val DICE_ANSWERS = mapOf(
        12 to listOf(1, 1, 2, 2, 3, 3), 13 to listOf(1, 1, 2, 2, 3, 4), 14 to listOf(1, 1, 2, 2, 4, 4),
        15 to listOf(1, 1, 2, 5, 3, 3), 16 to listOf(1, 1, 2, 5, 3, 4), 17 to listOf(1, 1, 2, 5, 4, 4),
        18 to listOf(1, 1, 5, 5, 3, 3), 19 to listOf(1, 1, 5, 5, 3, 4), 20 to listOf(1, 1, 5, 5, 4, 4),
        21 to listOf(1, 6, 2, 5, 3, 4), 22 to listOf(1, 6, 2, 5, 4, 4), 23 to listOf(1, 6, 5, 5, 3, 3),
        24 to listOf(1, 6, 5, 5, 3, 4), 25 to listOf(1, 6, 5, 5, 4, 4), 26 to listOf(6, 6, 2, 5, 3, 4),
        27 to listOf(6, 6, 2, 5, 4, 4), 28 to listOf(6, 6, 5, 5, 3, 3), 29 to listOf(6, 6, 5, 5, 3, 4),
        30 to listOf(6, 6, 5, 5, 4, 4)
    )

    fun dice(target: Int): List<Int>? = DICE_ANSWERS[target]

    // ---------------- Dragon Slayer II: crypt busts ----------------

    val BUSTS = listOf("Aivas", "Camorra", "Robert", "Tristan")
    val CRYPT_NAMES = listOf("Zartharim", "Saranthium", "Arkney", "Karville")   // same order as BUSTS
    val CRYPT_WEAPONS = listOf("crossbow", "axe", "bow", "sword")               // same order as BUSTS

    // north: who "sat at the north of the table"; south: "opposite the one with the …";
    // west: "The one with a/an … asked the". Returns north, east, south, west busts.
    fun crypt(northName: Int, southWeapon: Int, westWeapon: Int): List<String>? {
        val used = listOf(northName, southWeapon, westWeapon)
        if (used.toSet().size < 3) return null
        val east = (0..3).first { it !in used }
        return listOf(BUSTS[northName], BUSTS[east], BUSTS[southWeapon], BUSTS[westWeapon])
    }

    // ---------------- Beneath Cursed Sands: tomb riddle ----------------

    val EMBLEMS = listOf("Scarab", "Human", "Crocodile", "Baboon")
    val TOMB_GODS = listOf("god of isolation", "god of health", "goddess of resourcefulness", "goddess of companionship")
    val TOMB_ITEMS = listOf("a carving", "some wine", "a necklace", "some linen")

    // ---------------- The Curse of Arrav: metal door ----------------

    // For each letter A-I, the numbers on strips 4, 2, 1 and 3
    private val ARRAV_CODES = arrayOf(
        intArrayOf(7, 9, 6, 4), intArrayOf(5, 3, 1, 0), intArrayOf(2, 8, 6, 3),
        intArrayOf(0, 6, 4, 7), intArrayOf(4, 3, 6, 4), intArrayOf(2, 2, 1, 9),
        intArrayOf(2, 3, 2, 6), intArrayOf(4, 3, 8, 1), intArrayOf(9, 3, 0, 9)
    )

    // The code key's four letters -> the four numbers for the door, or null if it isn't a valid code
    fun metalDoor(code: String): IntArray? {
        val c = code.trim().uppercase()
        if (c.length != 4 || c.any { it !in 'A'..'I' }) return null
        val column = intArrayOf(2, 1, 3, 0)   // letter 1 uses strip 1, letter 2 strip 2, letter 3 strip 3, letter 4 strip 4
        return IntArray(4) { ARRAV_CODES[c[it] - 'A'][column[it]] }
    }

    // ---------------- The Heart of Darkness: chest dials ----------------

    val HOD_DIALS = listOf("LWABPEMDTR", "APORILCETN", "WERILANUTO", "EIDAOWKNRU")

    // Where each letter sits on its dial (1-10), or 0 if that dial doesn't have it
    fun hodPositions(code: String): List<Int> =
        code.trim().uppercase().take(4).mapIndexed { i, ch -> HOD_DIALS[i].indexOf(ch) + 1 }

    // ---------------- Song of the Elves: Baxtorian's pillars ----------------

    val BAX_PILLARS = listOf("South west" to "I am the …", "West" to "I am next to the …",
        "North west" to "I am opposite the …", "East" to "I am not next to the …", "North east" to "I am not the …")
    val BAX_ITEMS = listOf("Nature rune", "Irit leaf or any flowers", "Black knife or black dagger",
        "Wine of Zamorak or Zamorak brew", "Adamant chainbody", "Cabbage")

    // numbers[i] = the 1st-6th in pillar i's hint (0 if not known yet). Returns the item for each of the
    // 6 pillars (the south-east one gets whatever is left once the other five are known).
    fun baxtorian(numbers: List<Int>): List<String?> {
        val items = numbers.map { if (it in 1..6) BAX_ITEMS[it - 1] else null }
        val known = numbers.filter { it in 1..6 }
        val last = if (known.size == 5 && known.toSet().size == 5) BAX_ITEMS[(1..6).first { it !in known } - 1] else null
        return items + last
    }

    // ---------------- Sins of the Father: tomb door grid ----------------

    // A 5x5 grid: a marked square in column c adds c (1-5) to its row's total, and in row r adds r to
    // its column's total. Returns which squares to mark, [row][column], or null if nothing fits.
    fun kakurasu(rowSums: IntArray, colSums: IntArray): Array<BooleanArray>? {
        val patterns = (0 until 32).map { m -> BooleanArray(5) { (m shr it) and 1 == 1 } }
        fun weight(p: BooleanArray) = p.withIndex().sumOf { (i, on) -> if (on) i + 1 else 0 }
        val options = rowSums.map { s -> patterns.filter { weight(it) == s } }
        if (options.any { it.isEmpty() }) return null
        val grid = arrayOfNulls<BooleanArray>(5)
        fun colOk(complete: Boolean): Boolean {
            for (c in 0 until 5) {
                var sum = 0
                var rest = 0
                for (r in 0 until 5) {
                    val row = grid[r]
                    if (row == null) rest += r + 1 else if (row[c]) sum += r + 1
                }
                if (sum > colSums[c] || sum + rest < colSums[c]) return false
                if (complete && sum != colSums[c]) return false
            }
            return true
        }
        fun place(r: Int): Boolean {
            if (r == 5) return colOk(true)
            for (p in options[r]) {
                grid[r] = p
                if (colOk(false) && place(r + 1)) return true
            }
            grid[r] = null
            return false
        }
        return if (place(0)) Array(5) { grid[it]!! } else null
    }

    // ---------------- Hot and cold searches ----------------

    class Temperature(val text: String, val min: Int, val max: Int)
    class Place(val name: String, val x: Int, val y: Int)
    class Reading(val place: Place, val temperature: Temperature)

    // Which spots fit every reading. You can only say roughly where you are, so each reading allows
    // some slack; spots that break a reading by less are still kept, just lower down.
    fun hotCold(spots: List<Place>, readings: List<Reading>, slack: Int): List<Place> {
        fun miss(s: Place, r: Reading): Int {
            val d = max(abs(s.x - r.place.x), abs(s.y - r.place.y))   // the game measures in squares, like a chess king
            return when {
                d < r.temperature.min -> r.temperature.min - d
                d > r.temperature.max -> d - r.temperature.max
                else -> 0
            }
        }
        return spots.map { s -> s to readings.sumOf { miss(s, it) } }
            .filter { (s, _) -> readings.all { miss(s, it) <= slack } }
            .sortedBy { it.second }
            .map { it.first }
    }

    // The Enchanted Key (Making History): where it can lead, and what the key says
    val KEY_SPOTS = listOf(
        Place("South east of Rellekka", 2715, 3610), Place("South east of Varrock", 3303, 3345),
        Place("South of Falador", 2969, 3300), Place("North of Al Kharid", 3295, 3222),
        Place("Lumbridge Swamp", 3158, 3178), Place("Grand Exchange", 3161, 3490),
        Place("Near the Body Altar", 3034, 3437), Place("South west of the Gnome Stronghold", 2419, 3378),
        Place("North of Mudskipper Point", 3018, 3162), Place("South of East Ardougne", 2617, 3243),
        Place("Centre of the Gnome Stronghold", 2444, 3447)
    )
    val KEY_TEMPERATURES = listOf(
        Temperature("Freezing", 500, 5000), Temperature("Cold", 120, 499), Temperature("Warm", 60, 119),
        Temperature("Very hot", 30, 59), Temperature("Burning hot", 5, 29), Temperature("Steaming", 0, 4)
    )
    // Places you might be standing when you rub the key (the dig spots, plus towns and teleport spots)
    val KEY_PLACES: List<Place> = KEY_SPOTS + listOf(
        Place("Lumbridge", 3222, 3218), Place("Varrock", 3213, 3424), Place("Falador", 2965, 3378),
        Place("Draynor Village", 3093, 3248), Place("Port Sarim", 3012, 3222), Place("Rimmington", 2957, 3214),
        Place("Al Kharid", 3293, 3174), Place("Edgeville", 3094, 3491), Place("Barbarian Village", 3082, 3420),
        Place("Taverley", 2895, 3440), Place("Burthorpe", 2898, 3545), Place("Catherby", 2808, 3440),
        Place("Camelot", 2757, 3478), Place("Seers' Village", 2725, 3485), Place("East Ardougne", 2662, 3305),
        Place("West Ardougne", 2530, 3300), Place("Yanille", 2605, 3093), Place("Rellekka", 2668, 3631),
        Place("The Outpost", 2430, 3348)
    )

    // Mage Arena II: where the bosses can be, and what the symbol says
    val MAGE_SPOTS = listOf(
        Place("South of the Lava Dragon Isle", 3185, 3791), Place("North of the Lava Dragon Isle (1)", 3224, 3882),
        Place("North of the Lava Dragon Isle (2)", 3233, 3867), Place("North of the Lava Dragon Isle (3)", 3220, 3867),
        Place("North west of the Lava Dragon Isle (1)", 3140, 3868), Place("North west of the Lava Dragon Isle (2)", 3169, 3865),
        Place("North east of the Lava Dragon Isle (1)", 3238, 3894), Place("North east of the Lava Dragon Isle (2)", 3247, 3859),
        Place("North east of the Lava Dragon Isle (3)", 3262, 3887), Place("North east of the Lava Dragon Isle (4)", 3247, 3862),
        Place("West of the Lava Dragon Isle (1)", 3146, 3895), Place("West of the Lava Dragon Isle (2)", 3163, 3833),
        Place("East of the Demonic Ruins", 3314, 3876), Place("East of the Lava Dragon Isle (1)", 3246, 3834),
        Place("East of the Lava Dragon Isle (2)", 3279, 3823), Place("South east of the Lava Dragon Isle", 3266, 3814),
        Place("East of the Rogues' Castle (1)", 3306, 3936), Place("East of the Rogues' Castle (2)", 3335, 3902),
        Place("West of the Rogues' Castle", 3261, 3909)
    )
    val MAGE_TEMPERATURES = listOf(
        Temperature("Very cold", 200, 5000), Temperature("Cold", 150, 199), Temperature("Warm", 100, 149),
        Temperature("Hot", 70, 99), Temperature("Very hot", 30, 69), Temperature("Incredibly hot", 15, 29),
        Temperature("Visibly shaking", 0, 14)
    )
    // Where you might be standing: each spawn area (the spots named alike, averaged)
    val MAGE_PLACES: List<Place> = MAGE_SPOTS.groupBy { it.name.substringBefore(" (") }.map { (name, spots) ->
        Place(name, spots.map { it.x }.average().toInt(), spots.map { it.y }.average().toInt())
    }
    // ---------------- Lunar Diplomacy: the ten possible cloud paths (from the OSRS Wiki) ----------------

    // Each path is 8 rows of 4 tiles, north (the start) first; "1" is a safe tile
    val CLOUD_PATHS = listOf(
        "0100 0111 0001 0001 1111 1000 1111 0001", "0001 0001 0011 0010 0011 0001 0111 0100",
        "1000 1000 1111 0001 0111 0100 0111 0001", "0100 0110 0011 0001 0111 0100 0110 0010",
        "1000 1111 0001 1111 1000 1000 1110 0010", "1000 1110 0010 1110 1000 1111 0001 0001",
        "0100 0111 0001 0011 0110 0100 0110 0010", "0100 0110 0010 0110 1100 1000 1110 0010",
        "0010 1110 1000 1100 0100 1100 1000 1000", "0100 0111 0001 0001 0111 0100 0110 0010"
    ).map { it.replace(" ", "") }

    // Which paths fit the tiles marked so far (marks: 32 chars, '1' safe, '2' not safe, anything else unknown)
    fun cloudMatches(marks: String): List<Int> = CLOUD_PATHS.indices.filter { p ->
        marks.withIndex().all { (i, m) -> when (m) { '1' -> CLOUD_PATHS[p][i] == '1'; '2' -> CLOUD_PATHS[p][i] == '0'; else -> true } }
    }

    // ---------------- The Eyes / Path of Glouphrie: shape discs ----------------

    val DISC_COLOURS = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet")   // worth 1-7
    val DISC_SHAPES = listOf("circle" to 1, "triangle" to 3, "square" to 4, "pentagon" to 5)   // times 1, 3, 4, 5

    class Disc(val name: String, val value: Int)

    val DISCS: List<Disc> = DISC_COLOURS.flatMapIndexed { c, colour ->
        DISC_SHAPES.map { (shape, times) -> Disc("$colour $shape", (c + 1) * times) }
    }

    // Ways to make `target` from `count` discs (1-3), simplest first
    fun discCombos(target: Int, count: Int, limit: Int = 8): List<List<Disc>> {
        val out = ArrayList<List<Disc>>()
        val n = DISCS.size
        fun add(combo: List<Disc>) { if (combo.sumOf { it.value } == target) out.add(combo) }
        when (count) {
            1 -> DISCS.forEach { add(listOf(it)) }
            2 -> for (a in 0 until n) for (b in a until n) add(listOf(DISCS[a], DISCS[b]))
            else -> for (a in 0 until n) for (b in a until n) for (c in b until n) add(listOf(DISCS[a], DISCS[b], DISCS[c]))
        }
        // fewer different shapes to find first, then the ones using the biggest discs
        return out.sortedWith(compareBy<List<Disc>> { it.map { d -> d.name }.toSet().size }.thenByDescending { it.maxOf { d -> d.value } })
            .take(limit)
    }
}
