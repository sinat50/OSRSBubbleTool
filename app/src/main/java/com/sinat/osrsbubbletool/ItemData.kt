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
class ItemData(tsv: String) {

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
        private val SLOTS = setOf("head", "cape", "neck", "ammo", "weapon", "body", "shield", "legs", "hands", "feet", "ring")
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
