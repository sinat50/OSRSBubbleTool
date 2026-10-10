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
            n < 100_000 -> 0xFFFFFF00.toInt()
            n < 10_000_000 -> 0xFFFFFFFF.toInt()
            else -> 0xFF00FF80.toInt()
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
}
