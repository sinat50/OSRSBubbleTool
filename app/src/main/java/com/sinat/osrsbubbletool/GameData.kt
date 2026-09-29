package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

// The data and rules behind the Game Room games. No Android parts here, so it can be tested on a computer.

// ---------------- Grand Exchange prices ----------------

// Item names, icons and their average price over the last day, from the OSRS Wiki's Real-time Prices.
//   mapping: https://prices.runescape.wiki/api/v1/osrs/mapping  (names and icons)
//   day:     https://prices.runescape.wiki/api/v1/osrs/24h      (average price and how many were traded)
class PriceBook(mappingJson: String, dayJson: String) {

    class Item(val id: Int, val name: String, val icon: String, val price: Long, val traded: Long, val members: Boolean)

    val items: List<Item>
    private val byName = HashMap<String, Item>()

    init {
        val day = JSONObject(dayJson).getJSONObject("data")
        val mapping = JSONArray(mappingJson)
        val list = ArrayList<Item>(mapping.length())
        for (i in 0 until mapping.length()) {
            val m = mapping.getJSONObject(i)
            val id = m.getInt("id")
            val d = day.optJSONObject(id.toString())
            val high = d?.optLong("avgHighPrice", 0) ?: 0
            val low = d?.optLong("avgLowPrice", 0) ?: 0
            val price = when {
                high > 0 && low > 0 -> (high + low) / 2
                else -> maxOf(high, low)
            }
            val traded = (d?.optLong("highPriceVolume", 0) ?: 0) + (d?.optLong("lowPriceVolume", 0) ?: 0)
            val item = Item(id, m.getString("name"), m.optString("icon"), price, traded, m.optBoolean("members"))
            list.add(item)
            byName[item.name.lowercase(Locale.ROOT)] = item
        }
        items = list
    }

    fun find(name: String): Item? = byName[name.lowercase(Locale.ROOT)]

    // What one of this item is worth (coins are worth 1 each; untradeable items 0)
    fun priceOf(name: String): Long = if (name.equals("Coins", ignoreCase = true)) 1 else find(name)?.price ?: 0

    // Items for Higher or Lower: well-known ones people actually trade, so the answers aren't impossible
    fun gameItems(): List<Item> = items.filter { it.price >= 100 && it.traded >= 1_000 }
}

// ---------------- Higher or Lower ----------------

object HigherLower {
    // The next item to guess. It gets harder as your streak grows: the two prices get closer together.
    fun nextItem(pool: List<PriceBook.Item>, current: PriceBook.Item, streak: Int, random: Random = Random): PriceBook.Item {
        val maxRatio = when {
            streak < 5 -> Double.MAX_VALUE   // anything goes
            streak < 10 -> 5.0               // within 5 times the price
            streak < 20 -> 2.5
            else -> 1.6
        }
        repeat(200) {
            val next = pool[random.nextInt(pool.size)]
            if (next.id == current.id || next.name == current.name) return@repeat
            val ratio = maxOf(next.price, current.price).toDouble() / minOf(next.price, current.price)
            if (ratio <= maxRatio) return next
        }
        // nothing close enough found: take any other item
        while (true) {
            val next = pool[random.nextInt(pool.size)]
            if (next.id != current.id) return next
        }
    }

    // Equal prices count as right either way
    fun isRight(current: PriceBook.Item, next: PriceBook.Item, guessedHigher: Boolean): Boolean =
        if (guessedHigher) next.price >= current.price else next.price <= current.price
}

// ---------------- Loot Simulator ----------------

// A boss's drops, from the OSRS Wiki's drop tables:
//   https://oldschool.runescape.wiki/api.php?action=bucket&format=json&query=bucket('dropsline').select('item_name','drop_json').where('page_name','Zulrah').limit(500).run()
//
// Clue scroll caskets work differently: each casket rolls its table several times (casketRolls, e.g. 5–7 for master),
// the wiki's rates are per roll, and a few extras (a master clue, the Bloodhound pet) are rolled once per casket.
class DropTable(bucketJson: String, val casketRolls: IntRange? = null) {

    class Drop(val item: String, val chance: Double, val low: Int, val high: Int, val rolls: Int)

    // Always dropped
    val always = ArrayList<Drop>()
    // Tables where exactly one item (or nothing) comes from each roll
    val tables = ArrayList<List<Drop>>()
    // Drops that are rolled on their own (pets, clue scrolls, keys…)
    val separate = ArrayList<Drop>()
    // Everything, for the "chance" shown next to each item
    val all = ArrayList<Drop>()

    init {
        if (casketRolls != null) readCasket(bucketJson) else readMonster(bucketJson)
    }

    private fun readCasket(bucketJson: String) {
        val rows = JSONObject(bucketJson).getJSONArray("bucket")
        val table = ArrayList<Drop>()
        var ended = false
        var notAlways = 0
        val perCasket = HashSet<String>()
        for (i in 0 until rows.length()) {
            val d = JSONObject(rows.getJSONObject(i).getString("drop_json"))
            val item = d.optString("Dropped item").ifEmpty { rows.getJSONObject(i).optString("item_name") }
            val chance = chanceOf(d.optString("Rarity")) ?: continue
            val low = d.optInt("Quantity Low", 1).coerceAtLeast(0)
            val high = d.optInt("Quantity High", low).coerceAtLeast(low)
            if (high == 0) continue
            // rolled once per casket, wherever the wiki lists them
            if (Regex("""^(Clue scroll \(master\)|Bloodhound)$""", RegexOption.IGNORE_CASE).matches(item)) {
                if (perCasket.add(item.lowercase())) separate.add(Drop(item, chance, low, high, 1))
                continue
            }
            if (ended) continue
            if (chance >= 1.0) {
                // "Always" rows after the loot are milestone rewards (scroll cases, emotes), and the rows after them
                // break the shared tables down again. The loot table ends here.
                if (notAlways >= 3) ended = true
                continue
            }
            notAlways++
            table.add(Drop(item, chance, low, high, 0))   // 0 rolls = rolled casketRolls times
        }
        if (table.isNotEmpty()) tables.add(table)
        all.addAll(table)
        all.addAll(separate)
    }

    private fun readMonster(bucketJson: String) {
        val rows = JSONObject(bucketJson).getJSONArray("bucket")
        val drops = ArrayList<Drop>()
        var first: Pair<String, String>? = null
        val seen = HashSet<String>()
        var notAlways = 0
        for (i in 0 until rows.length()) {
            val d = JSONObject(rows.getJSONObject(i).getString("drop_json"))
            val item = d.optString("Dropped item").ifEmpty { rows.getJSONObject(i).optString("item_name") }
            val rarity = d.optString("Rarity")
            // Some pages list a second version of the monster (e.g. in the Wilderness) after the first: stop there
            if (first == null) first = item to rarity
            else if (first.first == item && first.second == rarity) break
            val chance = chanceOf(rarity) ?: continue      // "Rare", "Varies", "Once"… can't be simulated
            if (chance >= 1.0 && notAlways >= 3) {
                // An "Always" drop late in the list: either another version of the monster starting
                // (an item we've already seen), or a one-off like a quest key. Neither belongs in the simulation.
                if (item in seen) break else continue
            }
            if (chance < 1.0) notAlways++
            seen.add(item)
            val low = d.optInt("Quantity Low", 1).coerceAtLeast(0)
            val high = d.optInt("Quantity High", low).coerceAtLeast(low)
            var rolls = d.optInt("Rolls", 1)
            if (rolls < 1 || rolls > 4) rolls = 1          // anything unusual: one roll
            if (high == 0) continue
            drops.add(Drop(item, chance, low, high, rolls))
        }
        all.addAll(drops)

        // Split them up. The wiki lists each table in order, so items are grouped into a table until the
        // chances add up to 1; a group that adds up to much less than 1 is made of separate drops.
        for (rolls in drops.map { it.rolls }.distinct()) {
            var group = ArrayList<Drop>()
            var sum = 0.0
            fun close() {
                if (group.isEmpty()) return
                if (sum >= 0.9) tables.add(group) else separate.addAll(group)
                group = ArrayList(); sum = 0.0
            }
            for (d in drops.filter { it.rolls == rolls }) {
                if (d.chance >= 1.0) { always.add(d); continue }
                if (sum + d.chance > 1.01) close()
                group.add(d); sum += d.chance
            }
            close()
        }
    }

    // One kill: what drops
    fun kill(random: Random, into: MutableMap<String, Long>) {
        for (d in always) add(into, d, random)
        for (table in tables) {
            val rolls = if (casketRolls != null) random.nextInt(casketRolls.first, casketRolls.last + 1) else table[0].rolls
            val sum = if (casketRolls != null) table.sumOf { it.chance }.coerceAtLeast(1.0) else 1.0
            repeat(rolls) {
                var r = random.nextDouble() * sum
                for (d in table) {
                    r -= d.chance
                    if (r < 0) { add(into, d, random); break }
                }
            }
        }
        for (d in separate) repeat(d.rolls) { if (random.nextDouble() < d.chance) add(into, d, random) }
    }

    private fun add(into: MutableMap<String, Long>, d: Drop, random: Random) {
        val amount = if (d.high > d.low) random.nextInt(d.low, d.high + 1) else d.low
        into[d.item] = (into[d.item] ?: 0) + amount
    }

    // The chance of getting this item at least once from a kill
    fun chancePerKill(item: String): Double {
        var none = 1.0
        val casketAverage = casketRolls?.let { (it.first + it.last) / 2.0 } ?: 1.0
        for (d in all) if (d.item == item)
            none *= Math.pow(1 - d.chance.coerceAtMost(1.0), if (d.rolls == 0) casketAverage else d.rolls.toDouble())
        return 1 - none
    }

    companion object {
        // "1/1,024" → 0.000977, "10/249", "4.5/249", "Always" → 1
        fun chanceOf(rarity: String): Double? {
            if (rarity.equals("Always", ignoreCase = true)) return 1.0
            val m = Regex("""^\s*([\d.,]+)\s*/\s*([\d.,]+)\s*$""").find(rarity) ?: return null
            val a = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
            val b = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
            if (a <= 0 || b <= 0) return null
            return (a / b).coerceAtMost(1.0)
        }
    }
}

// The bosses in the Loot Simulator, with their pet (if they have one)
object LootBosses {
    class Boss(val name: String, val page: String = name, val pet: String? = null, val casketRolls: IntRange? = null) {
        val isCasket get() = casketRolls != null
    }

    val GROUPS: List<Pair<String, List<Boss>>> = listOf(
        "Popular" to listOf(
            Boss("Zulrah", pet = "Pet snakeling"),
            Boss("Vorkath", pet = "Vorki"),
            Boss("Corporeal Beast", pet = "Pet dark core"),
            Boss("King Black Dragon", pet = "Prince black dragon"),
            Boss("Kalphite Queen", pet = "Kalphite princess"),
            Boss("Giant Mole", pet = "Baby mole"),
            Boss("Sarachnis", pet = "Sraracha"),
            Boss("Scurrius", pet = "Scurry"),
            Boss("Phantom Muspah", pet = "Muphin"),
            Boss("Tormented Demon"),
        ),
        "Clue scroll caskets" to listOf(
            Boss("Beginner casket", "Reward casket (beginner)", casketRolls = 1..3),
            Boss("Easy casket", "Reward casket (easy)", casketRolls = 2..4),
            Boss("Medium casket", "Reward casket (medium)", casketRolls = 3..5),
            Boss("Hard casket", "Reward casket (hard)", casketRolls = 4..6),
            Boss("Elite casket", "Reward casket (elite)", casketRolls = 4..6),
            Boss("Master casket", "Reward casket (master)", pet = "Bloodhound", casketRolls = 5..7),
        ),
        "God Wars Dungeon" to listOf(
            Boss("General Graardor", pet = "Pet general graardor"),
            Boss("Kree'arra", pet = "Pet kree'arra"),
            Boss("Commander Zilyana", pet = "Pet zilyana"),
            Boss("K'ril Tsutsaroth", pet = "Pet k'ril tsutsaroth"),
        ),
        "Slayer bosses" to listOf(
            Boss("Cerberus", pet = "Hellpuppy"),
            Boss("Alchemical Hydra", pet = "Ikkle hydra"),
            Boss("Abyssal Sire"),
            Boss("Kraken", pet = "Pet kraken"),
            Boss("Thermonuclear smoke devil", pet = "Pet smoke devil"),
            Boss("Grotesque Guardians", pet = "Noon"),
            Boss("Araxxor", pet = "Nid"),
        ),
        "Desert Treasure II" to listOf(
            Boss("The Leviathan", pet = "Lil'viathan"),
            Boss("Duke Sucellus", pet = "Baron"),
            Boss("Vardorvis", pet = "Butch"),
            Boss("The Whisperer", pet = "Wisp"),
        ),
        "Dagannoth Kings" to listOf(
            Boss("Dagannoth Rex", pet = "Pet dagannoth rex"),
            Boss("Dagannoth Prime", pet = "Pet dagannoth prime"),
            Boss("Dagannoth Supreme", pet = "Pet dagannoth supreme"),
        ),
        "Wilderness" to listOf(
            Boss("Callisto", pet = "Callisto cub"),
            Boss("Venenatis", pet = "Venenatis spiderling"),
            Boss("Vet'ion", pet = "Vet'ion jr."),
            Boss("Artio", pet = "Callisto cub"),
            Boss("Spindel", pet = "Venenatis spiderling"),
            Boss("Calvar'ion", pet = "Vet'ion jr."),
            Boss("Chaos Elemental", pet = "Pet chaos elemental"),
            Boss("Chaos Fanatic", pet = "Pet chaos elemental"),
            Boss("Scorpia", pet = "Scorpia's offspring"),
            Boss("Crazy archaeologist"),
        ),
        "Varlamore" to listOf(
            Boss("Amoxliatl", pet = "Moxi"),
            Boss("The Hueycoatl", pet = "Huberte"),
            Boss("Doom of Mokhaiotl", pet = "Dom"),
        ),
        "Other bosses" to listOf(
            Boss("The Nightmare", pet = "Little nightmare"),
            Boss("Phosani's Nightmare", pet = "Little nightmare"),
            Boss("Yama", pet = "Yami"),
            Boss("Skotizo", pet = "Skotos"),
            Boss("Zalcano", pet = "Smolcano"),
        ),
    )

    fun dropsUrl(page: String): String {
        val q = "bucket('dropsline').select('item_name','drop_json').where('page_name','" +
            page.replace("\\", "\\\\").replace("'", "\\'") + "').limit(500).run()"
        return "https://oldschool.runescape.wiki/api.php?action=bucket&format=json&query=" +
            java.net.URLEncoder.encode(q, "UTF-8")
    }
}

// ---------------- Shared ----------------

object Gp {
    // 1,234,567
    fun full(v: Long): String = String.format(Locale.US, "%,d", v)

    // 1.23M, 45.6K, 999
    fun short(v: Long): String {
        val a = Math.abs(v)
        fun f(x: Double, s: String): String {
            val t = if (x >= 100) String.format(Locale.US, "%.0f", x)
                else if (x >= 10) String.format(Locale.US, "%.1f", x) else String.format(Locale.US, "%.2f", x)
            return (if ('.' in t) t.trimEnd('0').trimEnd('.') else t) + s
        }
        val text = when {
            a >= 999_500_000 -> f(a / 1e9, "B")
            a >= 999_500 -> f(a / 1e6, "M")
            a >= 10_000 -> f(a / 1e3, "K")
            else -> full(a)
        }
        return if (v < 0) "-$text" else text
    }

    // "1/5,000"
    fun rate(chance: Double): String = when {
        chance >= 0.9995 -> "Always"
        chance <= 0 -> "?"
        else -> {
            val d = 1 / chance
            "1/" + if (d >= 100) full(Math.round(d)) else String.format(Locale.US, "%.1f", d).removeSuffix(".0")
        }
    }
}

// Wiki picture addresses
object WikiIcons {
    private fun file(name: String) = "https://oldschool.runescape.wiki/images/" +
        java.net.URLEncoder.encode(name.replace(' ', '_'), "UTF-8").replace("+", "%20")

    // From the price list's icon name ("Zulrah's scales 5.png")
    fun fromIcon(icon: String) = file(icon)

    // A best guess for items not on the Grand Exchange ("Pet snakeling" → Pet_snakeling.png)
    fun forItem(item: String, prices: PriceBook?): String {
        if (item.equals("Coins", ignoreCase = true)) return file("Coins 10000.png")
        val icon = prices?.find(item)?.icon
        return if (!icon.isNullOrEmpty()) file(icon) else file("$item.png")
    }
}

// ---------------- 2048 ----------------

// The 2048 board: swipe to slide every tile one way; two tiles with the same number join into one.
class Game2048(val size: Int = 4, private val random: Random = Random.Default) {

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    // What a move did, so it can be animated: every tile's slide, which squares merged, and the new tile
    class Slide(val from: Int, val to: Int, val value: Int)
    class Move(val slides: List<Slide>, val merged: Set<Int>, val spawned: Int, val gained: Int)

    var cells = IntArray(size * size)   // 0 = empty, otherwise 2, 4, 8…
        private set
    var score = 0
        private set

    fun newGame() {
        cells = IntArray(size * size)
        score = 0
        spawn(); spawn()
    }

    fun restore(saved: IntArray, savedScore: Int) {
        if (saved.size != size * size) { newGame(); return }
        cells = saved.copyOf()
        score = savedScore
        if (cells.all { it == 0 }) newGame()
    }

    val best: Int get() = cells.maxOrNull() ?: 0

    // Slides the tiles; null if nothing could move that way
    fun move(dir: Dir): Move? {
        val next = IntArray(size * size)
        val slides = ArrayList<Slide>()
        val merged = HashSet<Int>()
        var gained = 0
        for (line in 0 until size) {
            // the squares of this row/column, starting from the side the tiles slide towards
            val idx = IntArray(size) { i ->
                when (dir) {
                    Dir.LEFT -> line * size + i
                    Dir.RIGHT -> line * size + (size - 1 - i)
                    Dir.UP -> i * size + line
                    Dir.DOWN -> (size - 1 - i) * size + line
                }
            }
            var target = 0              // where the next tile lands
            var canMerge = false        // the tile at target-1 can still take a merge
            for (i in 0 until size) {
                val v = cells[idx[i]]
                if (v == 0) continue
                if (canMerge && next[idx[target - 1]] == v) {
                    val to = idx[target - 1]
                    next[to] = v * 2
                    gained += v * 2
                    merged.add(to)
                    slides.add(Slide(idx[i], to, v))
                    canMerge = false
                } else {
                    val to = idx[target]
                    next[to] = v
                    slides.add(Slide(idx[i], to, v))
                    target++
                    canMerge = true
                }
            }
        }
        if (next.contentEquals(cells)) return null
        cells = next
        score += gained
        val spawned = spawn()
        return Move(slides, merged, spawned, gained)
    }

    fun canMove(): Boolean {
        for (i in cells.indices) {
            if (cells[i] == 0) return true
            val x = i % size
            val y = i / size
            if (x + 1 < size && cells[i + 1] == cells[i]) return true
            if (y + 1 < size && cells[i + size] == cells[i]) return true
        }
        return false
    }

    // Puts a 2 (or sometimes a 4) in a random empty square; returns where, or -1 if full
    private fun spawn(): Int {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return -1
        val at = empty[random.nextInt(empty.size)]
        cells[at] = if (random.nextInt(10) == 0) 4 else 2
        return at
    }
}
