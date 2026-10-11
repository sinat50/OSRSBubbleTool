package com.sinat.osrsbubbletool

// Every item in the game: its ID, name, equipment slot and picture, for Gear Loadouts. Read from
// assets/items/items.tsv, made by tools/items/build_items.py from the OSRS Wiki's item tables (text under
// CC BY-NC-SA 3.0; the full notice is on the app's Legal screen). The pictures are in assets/items/icons/.
// No Android parts, so it can be tested on a computer.
//
// Each line of the file: ID, name, slot (empty if it isn't worn), picture file(s). Items drawn differently
// by stack size (coins, arrows...) list several pictures, separated by "|"; the number at the end of each
// name ("Coins 250.png") is the smallest stack that picture is for. Last, the item's wiki page if it isn't
// its name: copies of an item from a minigame or quest have their own page ("Rune pouch (Last Man Standing)").
//
// Noted items (an item drawn on a bank note) have their own IDs in the game: notedTsv (assets/items/noted.tsv, from
// RuneLite's item list) gives each notable item's noted ID, one "item ID, noted ID" pair a line.
class ItemData(tsv: String, notedTsv: String = "") {

    class Item(val id: Int, val name: String, val slot: String?, val pictures: List<String>, page: String = "") {
        // Which copy this is ("Last Man Standing"), or null for the item itself
        val variant: String? = page.takeIf { it.length > name.length && it.startsWith(name) }
            ?.substring(name.length)?.trim()?.removePrefix("(")?.removeSuffix(")")?.takeIf { it.isNotEmpty() }

        // The smallest stack each picture is for, or null if this item has one picture for every stack size
        private val stackFrom: IntArray? = run {
            val sizes = pictures.map { STACK.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            if (pictures.size < 2 || null in sizes) null else IntArray(sizes.size) { sizes[it]!! }
        }

        // The picture for a stack of this many (coins and arrows look fuller as the stack grows)
        fun picture(amount: Int = 1): String? {
            val from = stackFrom ?: return pictures.firstOrNull()
            var best = -1
            for (i in from.indices) if (from[i] <= amount && (best < 0 || from[i] > from[best])) best = i
            if (best < 0) best = from.indices.minByOrNull { from[it] } ?: return null   // less than the smallest: the smallest
            return pictures[best]
        }
    }

    companion object {
        private val STACK = Regex(""" (\d+)\.png$""")
        // Words for an item's less useful look-alike: the whole, charged one is the one people take on a trip
        // ("(broken)": the Wilderness-death copies of defenders, fire capes and void, a crack apart in the picture)
        private val UNCHARGED = listOf("(uncharged)", "(empty)", "(inactive)", "(broken)")
        private val SLOTS = Loadout.SLOTS.toSet()
    }

    private val byId = HashMap<Int, Item>()
    val all: List<Item>

    init {
        val list = ArrayList<Item>()
        for (line in tsv.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size < 4) continue
            val id = parts[0].toIntOrNull() ?: continue
            val slot = parts[2].takeIf { it in SLOTS }
            val item = Item(id, parts[1], slot, parts[3].split('|').filter { it.isNotEmpty() }, parts.getOrElse(4) { "" })
            if (byId.putIfAbsent(id, item) == null) list.add(item)
        }
        all = list
    }

    operator fun get(id: Int): Item? = byId[id]

    private val notedId = HashMap<Int, Int>()     // item ID to its noted ID
    private val plainId = HashMap<Int, Int>()     // noted ID to the item's own ID

    init {
        for (line in notedTsv.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split('\t')
            val plain = parts.getOrNull(0)?.toIntOrNull() ?: continue
            val noted = parts.getOrNull(1)?.toIntOrNull() ?: continue
            if (plain !in byId) continue
            notedId[plain] = noted
            plainId[noted] = plain
        }
    }

    // The noted version's ID of an item (null if it can't be noted)
    fun notedOf(id: Int): Int? = notedId[id]

    // For a noted ID, the item's own ID (null if id isn't a noted item)
    fun plainOf(id: Int): Int? = plainId[id]

    // Which item each picture shows (picture name to item), among the items `only` lets through: the item itself
    // before its minigame and quest copies, then the oldest (lowest ID)
    fun byPicture(only: (Item) -> Boolean = { true }): Map<String, Item> = HashMap<String, Item>().also { map ->
        for (item in all) if (only(item)) for (pic in item.pictures) {
            val had = map[pic]
            if (had == null || (had.variant != null && item.variant == null) ||
                ((had.variant == null) == (item.variant == null) && item.id < had.id)) map[pic] = item
        }
    }

    // Whether a stack is a rune pouch that holds runes (any kind, divine too; a noted pouch holds nothing), and how
    // many runes it holds (null if it isn't one)
    fun runePouchSize(id: Int): Int? = byId[id]?.name?.lowercase()?.takeIf { "rune pouch" in it }?.let { if ("divine" in it) 4 else 3 }

    // Of items that look exactly alike, the one to pick: the item itself before its minigame and quest copies, a
    // charged (or whole) item before its uncharged (or broken) look-alike, then the lowest ID
    fun preferred(ids: Collection<Int>): Int = ids.minWith(compareBy<Int>({ shown(it)?.first?.variant != null },
        { id -> shown(id)?.first?.name?.let { n -> UNCHARGED.any { n.contains(it) } } == true }, { it }))

    // A reading's guesses (item IDs, best first, and how well each fits: lower is better), with an uncharged item's
    // charged version put first when it fits about as well ("Eye of Ayak" before "Eye of Ayak (uncharged)", "Avernic
    // defender" before "Avernic defender (broken)": the one people take on a trip)
    fun chargedFirst(ids: List<Int>, costs: List<Float>): List<Int> {
        val best = ids.firstOrNull() ?: return ids
        val name = shown(best)?.first?.name ?: return ids
        val word = UNCHARGED.firstOrNull { name.contains(it) } ?: return ids
        val charged = name.replace(word, "").trim()
        val k = ids.indexOfFirst { shown(it)?.first?.name == charged }
        if (k <= 0 || k >= costs.size || costs[k] - costs[0] >= 2f) return ids
        return listOf(ids[k]) + ids.filterIndexed { i, _ -> i != k }
    }

    // The item to show for an ID, and whether it's noted (null if the ID is unknown)
    fun shown(id: Int): Pair<Item, Boolean>? =
        byId[id]?.let { it to false } ?: plainId[id]?.let { plain -> byId[plain]?.let { it to true } }

    // Items whose name has all the typed words in it (any case), best matches first: names that start with
    // what you typed, then shorter names, the item itself before its minigame and quest copies. Items that
    // look the same and share a name (several IDs for one thing) are listed once. With a slot, only items
    // worn there.
    fun search(text: String, slot: String? = null, limit: Int = 50): List<Item> {
        val words = text.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val first = words.joinToString(" ")
        val seen = HashSet<String>()
        return all.asSequence()
            .filter { slot == null || it.slot == slot }
            .filter { item -> val name = item.name.lowercase(); words.all { it in name } }
            .sortedWith(compareBy<Item>({ !it.name.lowercase().startsWith(first) }, { it.name.length }, { it.name },
                { it.variant != null }, { it.variant }, { it.id }))
            .filter { seen.add(it.name + "\t" + it.variant + "\t" + it.pictures.firstOrNull()) }
            .take(limit)
            .toList()
    }
}
