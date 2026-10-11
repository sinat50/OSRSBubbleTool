package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject

// One Gear Loadouts loadout: what's in your inventory, what you wear, your rune pouch, bolt pouch and quiver,
// your spellbook and your notes. Items are game item IDs (the same numbers RuneLite uses), looked up in
// ItemData for their names and pictures. No Android parts, so it can be tested on a computer.
//
// Saved as one JSON object per loadout, in the list Gear Loadouts keeps in its settings (backed up with them).
// Loadouts saved by the older Inventory Setups have only an id and a name: their pictures are kept in
// files/setups/<id>/ and shown as they are.
class Loadout(val id: String, var name: String) {

    // One item in one place, and how many
    data class Stack(val id: Int, val amount: Int = 1)

    companion object {
        const val INVENTORY_SIZE = 28
        // The equipment slots, in the order RuneLite numbers them (EquipmentInventorySlot); null = a number
        // RuneLite skips (arms, hair and jaw, which nothing is worn in)
        val RUNELITE_SLOTS = listOf("head", "cape", "neck", "weapon", "body", "shield", null, "legs", null,
            "hands", "feet", null, "ring", "ammo")
        val SLOTS = RUNELITE_SLOTS.filterNotNull()
        // Each slot's name as shown
        val SLOT_LABELS = mapOf("head" to "Head", "cape" to "Cape", "neck" to "Neck", "ammo" to "Ammo",
            "weapon" to "Weapon", "body" to "Body", "shield" to "Shield", "legs" to "Legs", "hands" to "Hands",
            "feet" to "Feet", "ring" to "Ring")
        // Spellbooks, numbered as RuneLite numbers them
        val SPELLBOOKS = listOf("Standard", "Ancient", "Lunar", "Arceuus")

        // A stack's amount as the game writes it on the item: up to 99,999 in full, then "100K" to "9999K",
        // then "10M" and up (rounded down, as the game does)
        fun shortAmount(n: Int): String = when {
            n < 100_000 -> n.toString()
            n < 10_000_000 -> "${n / 1000}K"
            else -> "${n / 1_000_000}M"
        }

        // Its colour in the game: yellow, white from 100K, green from 10M
        fun amountColour(n: Int): Int = when {
            n < 100_000 -> colour("#FFFF00")
            n < 10_000_000 -> colour("#FFFFFF")
            else -> colour("#00FF80")
        }

        // An amount typed in: "250", "1,000", "15k", "2.5m" (null if it isn't a number of at least 1)
        fun parseAmount(text: String): Int? {
            val t = text.trim().lowercase().replace(",", "").replace(" ", "")
            if (t.isEmpty()) return null
            val times = when (t.last()) { 'k' -> 1_000.0; 'm' -> 1_000_000.0; 'b' -> 1_000_000_000.0; else -> 1.0 }
            val number = (if (times > 1) t.dropLast(1) else t).toDoubleOrNull() ?: return null
            val n = number * times
            return if (n < 1 || n > Int.MAX_VALUE) null else n.toInt()
        }

        fun fromJson(o: JSONObject): Loadout {
            val l = Loadout(o.getString("id"), o.optString("name", "Loadout"))
            l.notes = o.optString("notes", "")
            l.spellbook = if (o.has("sb")) o.getInt("sb").takeIf { it in SPELLBOOKS.indices } else null
            o.optJSONArray("inv")?.let { a -> for (i in 0 until minOf(a.length(), INVENTORY_SIZE)) l.inventory[i] = stack(a.opt(i)) }
            o.optJSONObject("eq")?.let { e -> for (slot in SLOTS) stack(e.opt(slot))?.let { l.equipment[slot] = it } }
            l.runePouch = list(o.optJSONArray("rp"))
            l.boltPouch = list(o.optJSONArray("bp"))
            l.quiver = stack(o.opt("qv"))
            return l
        }

        // ---- RuneLite's Inventory Setups format (its Export Setup and Mass Export Setups) ----
        // One setup is {"setup": {...}, "layout": [...]}; a mass export file is a list of those. Inside "setup":
        // "inv" (28 spaces), "eq" (the equipment, in RUNELITE_SLOTS order), "rp" rune pouch, "bp" bolt pouch,
        // "qv" quiver (left out if you have none), "name", "notes", "sb" (spellbook: 0-3, 4 for none, left out
        // for standard). Each item is {"id": 385, "q": 5}, "q" left out when it's 1; an empty space is null.
        // Everything else (favourite, bank options...) isn't used here. Exports need two more things, or RuneLite says
        // "Invalid setup data": "layout", how the setup's items are laid out in its bank tab (see runeLiteLayout), and
        // "hc", the highlight colour (the plugin's own default, red).
        private const val RUNELITE_NO_SPELLBOOK = 4
        private const val RUNELITE_HIGHLIGHT = "#FFFF0000"

        // Where the plugin's "Preset" bank tab layout (its default) puts each part, 8 squares to a row: the equipment
        // down the left as in the game, the quiver beside the head, the inventory's 4 columns on the right, the rune
        // pouch and bolt pouch in the rows below the equipment. (InventorySetupLayoutUtilities.getPresetLayout.)
        private val LAYOUT_PLACES = mapOf("head" to 1, "cape" to 8, "neck" to 9, "ammo" to 10, "weapon" to 16,
            "body" to 17, "shield" to 18, "legs" to 25, "hands" to 32, "feet" to 33, "ring" to 34)
        private const val LAYOUT_QUIVER = 2
        private const val LAYOUT_RUNE_POUCH = 40
        private const val LAYOUT_BOLT_POUCH = 48
        private const val LAYOUT_SIZE = 56   // the inventory's last square is 55
        // Items with a second ID for when they're worn (Graceful, Penance gloves, Ikov boots...): the bank tab shows
        // the item's own ID, as RuneLite's ItemManager.canonicalize does with this list (its WORN_ITEMS; RuneLite,
        // BSD 2-Clause, the notice is on the Legal screen)
        private val WORN_TO_ITEM = mapOf(
            89 to 88, 10554 to 10553, 11851 to 11850, 11853 to 11852, 11855 to 11854, 11857 to 11856, 11859 to
            11858, 11861 to 11860, 13580 to 13579, 13582 to 13581, 13584 to 13583, 13586 to 13585, 13588 to
            13587, 13590 to 13589, 13592 to 13591, 13594 to 13593, 13596 to 13595, 13598 to 13597, 13600 to
            13599, 13602 to 13601, 13604 to 13603, 13606 to 13605, 13608 to 13607, 13610 to 13609, 13612 to
            13611, 13614 to 13613, 13616 to 13615, 13618 to 13617, 13620 to 13619, 13622 to 13621, 13624 to
            13623, 13626 to 13625, 13628 to 13627, 13630 to 13629, 13632 to 13631, 13634 to 13633, 13636 to
            13635, 13638 to 13637, 13668 to 13667, 13670 to 13669, 13672 to 13671, 13674 to 13673, 13676 to
            13675, 13678 to 13677, 21063 to 21061, 21066 to 21064, 21069 to 21067, 21072 to 21070, 21075 to
            21073, 21078 to 21076, 24745 to 24743, 24748 to 24746, 24751 to 24749, 24754 to 24752, 24757 to
            24755, 24760 to 24758, 25071 to 25069, 25074 to 25072, 25077 to 25075, 25080 to 25078, 25083 to
            25081, 25086 to 25084, 27446 to 27444, 27449 to 27447, 27452 to 27450, 27455 to 27453, 27458 to
            27456, 27461 to 27459, 30047 to 30045, 30050 to 30048, 30053 to 30051, 30056 to 30054, 30059 to
            30057, 30062 to 30060, 13342 to 13280, 10073 to 10069, 10074 to 10071, 13341 to 9772, 13340 to 9771,
            28839 to 10053, 28842 to 10055, 28845 to 10057, 28848 to 10059, 28851 to 10061, 28854 to 10063,
            28857 to 10065, 28860 to 10067
        )

        // Every setup in pasted text or a file, one setup or a list of them. makeId gives each new loadout its id.
        // Throws if the text isn't a RuneLite export at all.
        fun fromRuneLite(text: String, makeId: (Int) -> String): List<Loadout> {
            val t = text.trim()
            val setups = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                else listOf(JSONObject(t))
            val found = ArrayList<Loadout>()
            for (s in setups) {
                // older exports were the setup alone, without the {"setup": ...} around it
                val setup = s.optJSONObject("setup") ?: s.takeIf { it.has("inv") || it.has("eq") } ?: continue
                found.add(fromRuneLiteSetup(setup, makeId(found.size)))
            }
            if (found.isEmpty()) throw IllegalArgumentException("No RuneLite setups in this text")
            return found
        }

        private fun fromRuneLiteSetup(s: JSONObject, id: String): Loadout {
            val l = Loadout(id, s.optString("name").trim().ifEmpty { "RuneLite setup" })
            l.notes = s.optString("notes")
            l.spellbook = s.optInt("sb", 0).takeIf { it in SPELLBOOKS.indices }   // 4 (none) or anything odd: not set
            s.optJSONArray("inv")?.let { a -> for (i in 0 until minOf(a.length(), INVENTORY_SIZE)) l.inventory[i] = runeLiteItem(a.opt(i)) }
            s.optJSONArray("eq")?.let { a ->
                for (i in 0 until minOf(a.length(), RUNELITE_SLOTS.size)) {
                    val slot = RUNELITE_SLOTS[i] ?: continue
                    runeLiteItem(a.opt(i))?.let { l.equipment[slot] = it }
                }
            }
            l.runePouch = runeLiteList(s.optJSONArray("rp"))
            l.boltPouch = runeLiteList(s.optJSONArray("bp"))
            // the quiver is a list of one item (accepted on its own too)
            l.quiver = when (val q = s.opt("qv")) {
                is JSONArray -> (0 until q.length()).firstNotNullOfOrNull { runeLiteItem(q.opt(it)) }
                else -> runeLiteItem(q)
            }
            return l
        }

        // {"id": 385, "q": 5}; null, or RuneLite's -1 for nothing, is an empty space
        private fun runeLiteItem(v: Any?): Stack? {
            val o = v as? JSONObject ?: return null
            val id = o.optInt("id", -1)
            return if (id < 0) null else Stack(id, o.optLong("q", 1).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
        }

        private fun runeLiteList(a: JSONArray?): MutableList<Stack?>? =
            a?.let { MutableList(it.length()) { i -> runeLiteItem(it.opt(i)) } }

        private fun runeLiteJson(s: Stack?): Any =
            if (s == null) JSONObject.NULL else JSONObject().apply { put("id", s.id); if (s.amount != 1) put("q", s.amount) }

        private fun runeLiteJson(list: List<Stack?>) = JSONArray().apply { list.forEach { put(runeLiteJson(it)) } }

        // Several loadouts as one mass export file (RuneLite's Mass Import reads it)
        fun toRuneLite(loadouts: List<Loadout>, plain: (Int) -> Int = { it }): JSONArray =
            JSONArray().apply { loadouts.forEach { put(it.toRuneLite(plain)) } }

        // A stack is saved as [id, amount], or null for an empty place
        private fun stack(v: Any?): Stack? {
            val a = v as? JSONArray ?: return null
            val id = a.optInt(0, -1)
            return if (id < 0) null else Stack(id, a.optInt(1, 1).coerceAtLeast(1))
        }

        private fun list(a: JSONArray?): MutableList<Stack?>? =
            a?.let { MutableList(it.length()) { i -> stack(it.opt(i)) } }

        private fun json(s: Stack?): Any = if (s == null) JSONObject.NULL else JSONArray().put(s.id).put(s.amount)
        private fun json(list: List<Stack?>) = JSONArray().apply { list.forEach { put(json(it)) } }
    }

    var notes = ""
    var spellbook: Int? = null                         // 0-3 (see SPELLBOOKS), or null if not set
    val inventory = arrayOfNulls<Stack>(INVENTORY_SIZE)
    val equipment = HashMap<String, Stack>()           // by slot ("head", "weapon"...)
    var runePouch: MutableList<Stack?>? = null         // null if the loadout has no rune pouch contents
    var boltPouch: MutableList<Stack?>? = null
    var quiver: Stack? = null

    // Whether anything has been put in it (loadouts from the older Inventory Setups are only pictures)
    val hasItems get() = inventory.any { it != null } || equipment.isNotEmpty() || quiver != null ||
        runePouch?.any { it != null } == true || boltPouch?.any { it != null } == true

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        if (notes.isNotEmpty()) put("notes", notes)
        spellbook?.let { put("sb", it) }
        if (inventory.any { it != null }) put("inv", json(inventory.toList()))
        if (equipment.isNotEmpty()) put("eq", JSONObject().apply { for ((slot, s) in equipment) put(slot, json(s)) })
        runePouch?.let { put("rp", json(it)) }
        boltPouch?.let { put("bp", json(it)) }
        quiver?.let { put("qv", json(it)) }
    }

    // This loadout as RuneLite's Export Setup writes one (its Import Setup reads it). plain: a noted item's own ID (the
    // bank tab shows the item itself), the same ID for anything else.
    fun toRuneLite(plain: (Int) -> Int = { it }): JSONObject {
        val setup = JSONObject().apply {
            put("inv", runeLiteJson(inventory.toList()))
            put("eq", runeLiteJson(RUNELITE_SLOTS.map { slot -> slot?.let { equipment[it] } }))
            runePouch?.let { put("rp", runeLiteJson(it)) }
            boltPouch?.let { put("bp", runeLiteJson(it)) }
            quiver?.let { put("qv", runeLiteJson(listOf(it))) }
            put("name", name)
            put("notes", notes)
            put("sb", spellbook ?: RUNELITE_NO_SPELLBOOK)
            put("hc", RUNELITE_HIGHLIGHT)
        }
        return JSONObject().put("setup", setup).put("layout", JSONArray().apply { runeLiteLayout(plain).forEach { put(it) } })
    }

    // The bank tab layout RuneLite's plugin makes for this setup by default: an item ID in each square, -1 for none
    fun runeLiteLayout(plain: (Int) -> Int = { it }): IntArray {
        val layout = IntArray(LAYOUT_SIZE) { -1 }
        fun shown(id: Int) = plain(id).let { WORN_TO_ITEM[it] ?: it }
        for ((slot, at) in LAYOUT_PLACES) equipment[slot]?.let { layout[at] = shown(it.id) }
        quiver?.let { layout[LAYOUT_QUIVER] = shown(it.id) }
        for (i in inventory.indices) inventory[i]?.let { layout[4 + i % 4 + (i / 4) * 8] = shown(it.id) }
        runePouch?.forEachIndexed { i, s -> if (s != null && i < 8) layout[LAYOUT_RUNE_POUCH + i] = shown(s.id) }
        boltPouch?.forEachIndexed { i, s -> if (s != null && i < 8) layout[LAYOUT_BOLT_POUCH + i] = shown(s.id) }
        return layout
    }
}
