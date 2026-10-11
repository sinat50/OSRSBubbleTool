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
        // Everything else (highlight colour, favourite, bank options, the bank tag "layout") isn't used here.
        private const val RUNELITE_NO_SPELLBOOK = 4

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
        fun toRuneLite(loadouts: List<Loadout>): JSONArray = JSONArray().apply { loadouts.forEach { put(it.toRuneLite()) } }

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

    // This loadout as RuneLite's Export Setup writes one (its Import Setup reads it)
    fun toRuneLite(): JSONObject {
        val setup = JSONObject().apply {
            put("inv", runeLiteJson(inventory.toList()))
            put("eq", runeLiteJson(RUNELITE_SLOTS.map { slot -> slot?.let { equipment[it] } }))
            runePouch?.let { put("rp", runeLiteJson(it)) }
            boltPouch?.let { put("bp", runeLiteJson(it)) }
            quiver?.let { put("qv", runeLiteJson(listOf(it))) }
            put("name", name)
            put("notes", notes)
            put("sb", spellbook ?: RUNELITE_NO_SPELLBOOK)
        }
        return JSONObject().put("setup", setup)
    }
}
