package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.sinat.osrsbubbletool.QuestSolvers as S

// The screens for the puzzle solvers where you enter what you see. What you've entered is kept
// while the bubble is running, so you can close the window and come back to it.
class QuestSolverViews(private val context: Context, private val refresh: () -> Unit) {

    companion object {
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val SELECTED = Color.parseColor("#3E7A2E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val ANSWER = Color.parseColor("#FFF4D6")
        private val SAY_BLUE = Color.parseColor("#1F4E8C")
        private val FADED = Color.parseColor("#8C7B5E")
        private val MISSING_RED = Color.parseColor("#B03A2E")
    }

    private val choices = HashMap<String, Int>()          // which option is picked in each question
    private val texts = HashMap<String, String>()         // what's typed in each box
    private val keyReadings = ArrayList<S.Reading>()
    private val mageReadings = ArrayList<S.Reading>()
    private val prefs = context.getSharedPreferences("quest_helper", Context.MODE_PRIVATE)

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    // Adds the solver's questions and answer to the page
    fun build(solver: String, column: LinearLayout) {
        when (solver) {
            "potion" -> potion(column)
            "dice" -> dice(column)
            "crypt" -> crypt(column)
            "tomb" -> tomb(column)
            "metal_door" -> metalDoor(column)
            "hod_chest" -> hodChest(column)
            "baxtorian" -> baxtorian(column)
            "sins_grid" -> sinsGrid(column)
            "enchanted_key" -> hotCold(column, key = true)
            "mage_arena" -> hotCold(column, key = false)
            "rift_tracker" -> riftTracker(column)
            "cloud_tracker" -> cloudTracker(column)
            "growth_tracker" -> orderTracker(column, "growth", listOf("South-west", "North-west", "North-east", "South-east"))
            "discs" -> discs(column)
            "tumblers" -> tumblers(column)
        }
    }

    // ---------------- Each solver ----------------

    private fun potion(c: LinearLayout) {
        c.addView(label("How does \"Cleansing fluid\" appear in the notes?", 13f, bold = true), full(8))
        val pick = choiceList(c, "potion", S.POTION_CLUES.map { it.first })
        if (pick >= 0) {
            val n = S.POTION_CLUES[pick].second
            c.addView(answer("Take fluid $n from the table (choose option $n), then use it on the refinery."), full(8))
        }
    }

    private fun dice(c: LinearLayout) {
        c.addView(label("What total did the Ethereal Fluke ask for?", 13f, bold = true), full(8))
        val pick = choiceGrid(c, "dice", (12..30).map { "$it" }, perRow = 5)
        if (pick < 0) return
        val faces = S.dice(12 + pick) ?: return
        c.addView(card(ANSWER) {
            addView(label("Flip the dice so these faces are up:", 13f, bold = true))
            S.DICE.forEachIndexed { i, (name, _) -> addView(pairRow(name, "${faces[i]}"), full(3)) }
        }, full(8))
    }

    private fun crypt(c: LinearLayout) {
        c.addView(label("Read the story on the tomb, then answer these:", 13f, bold = true), full(8))
        c.addView(label("Who \"sat at the north of the table\"?", 12f, bold = true), full(8))
        val north = choiceGrid(c, "crypt_n", S.CRYPT_NAMES, perRow = 2)
        c.addView(label("\"…opposite the one with the ___\"", 12f, bold = true), full(8))
        val south = choiceGrid(c, "crypt_s", S.CRYPT_WEAPONS, perRow = 4)
        c.addView(label("\"The one with a/an ___ asked the…\"", 12f, bold = true), full(8))
        val west = choiceGrid(c, "crypt_w", S.CRYPT_WEAPONS, perRow = 4)
        if (north < 0 || south < 0 || west < 0) return
        val busts = S.crypt(north, south, west)
        if (busts == null) {
            c.addView(label("Those answers point to the same bust twice. Read the story again.", 12f).apply { setTextColor(MISSING_RED) }, full(8))
            return
        }
        c.addView(card(ANSWER) {
            addView(label("Put the busts on the plinths:", 13f, bold = true))
            listOf("North", "East", "South", "West").forEachIndexed { i, side -> addView(pairRow("$side plinth", busts[i] + " bust"), full(3)) }
        }, full(8))
    }

    private fun tomb(c: LinearLayout) {
        c.addView(label("Read the north-western plaque, then pick what it says:", 13f, bold = true), full(8))
        c.addView(label("\"The ___ arrived just before…\"", 12f, bold = true), full(8))
        val a = choiceList(c, "tomb_1", S.TOMB_GODS)
        c.addView(label("\"…arrived just before the ___\"", 12f, bold = true), full(8))
        val b = choiceList(c, "tomb_2", S.TOMB_GODS)
        c.addView(label("\"To the one that arrived first, he offered ___\"", 12f, bold = true), full(8))
        val d = choiceList(c, "tomb_3", S.TOMB_ITEMS)
        c.addView(label("\"The one that was offered ___…\"", 12f, bold = true), full(8))
        val e = choiceList(c, "tomb_4", S.TOMB_ITEMS)
        if (listOf(a, b, d, e).any { it < 0 }) return
        c.addView(card(ANSWER) {
            addView(label("Take the four emblems from the south-western plaque and put them in the urns:", 13f, bold = true))
            addView(pairRow("Northernmost urn", S.EMBLEMS[a] + " emblem"), full(3))
            addView(pairRow("Centre-north urn", S.EMBLEMS[b] + " emblem"), full(3))
            addView(pairRow("Centre-south urn", S.EMBLEMS[d] + " emblem"), full(3))
            addView(pairRow("Southernmost urn", S.EMBLEMS[e] + " emblem"), full(3))
            addView(label("Then pull the lever to the south-west.", 12f), full(6))
        }, full(8))
    }

    private fun metalDoor(c: LinearLayout) {
        c.addView(label("Read the code key in your inventory (\"It reads ____.\") and type its four letters:", 13f, bold = true), full(8))
        val result = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun update(text: String) {
            result.removeAllViews()
            if (text.length < 4) return
            val digits = S.metalDoor(text)
            if (digits == null) {
                result.addView(label("The letters are always A to I. Check the code key again.", 12f).apply { setTextColor(MISSING_RED) })
                return
            }
            result.addView(card(ANSWER) {
                addView(label("The door's code is ${digits.joinToString("  ")}", 16f, bold = true))
                addView(label("For each number, press Up or Down until it shows, then press Enter.", 12f), full(4))
            })
        }
        c.addView(letterBox("metal_door", 4, "e.g. IFCB") { update(it) }, full(4))
        c.addView(result, full(8))
        update(texts["metal_door"] ?: "")
    }

    private fun hodChest(c: LinearLayout) {
        c.addView(label("Read the book: four of its letters are white. Type them in the order they appear:", 13f, bold = true), full(8))
        val result = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun update(text: String) {
            result.removeAllViews()
            if (text.length < 4) return
            val pos = S.hodPositions(text)
            result.addView(card(ANSWER) {
                addView(label("Set the chest's dials to:", 13f, bold = true))
                text.uppercase().take(4).forEachIndexed { i, ch ->
                    val where = if (pos[i] > 0) "$ch  (letter ${pos[i]} of 10)" else "$ch isn't on this dial: check the book again"
                    addView(pairRow("Dial ${i + 1}", where), full(3))
                }
                addView(label("Each dial's letters, in order:", 11f, bold = true), full(8))
                S.HOD_DIALS.forEachIndexed { i, d -> addView(label("Dial ${i + 1}: " + d.toList().joinToString(" "), 11f), full(1)) }
            })
        }
        c.addView(letterBox("hod_chest", 4, "e.g. RANK") { update(it) }, full(4))
        c.addView(result, full(8))
        update(texts["hod_chest"] ?: "")
    }

    private fun baxtorian(c: LinearLayout) {
        c.addView(label("Inspect each pillar. Its hint ends with a number (\"…the 4th.\"). Pick that number for each pillar:", 13f, bold = true), full(8))
        val numbers = S.BAX_PILLARS.mapIndexed { i, (pillar, hint) ->
            c.addView(label("$pillar pillar: \"$hint\"", 12f, bold = true), full(8))
            choiceGrid(c, "bax_$i", listOf("1st", "2nd", "3rd", "4th", "5th", "6th"), perRow = 6) + 1
        }
        val items = S.baxtorian(numbers)
        if (items.all { it == null }) return
        c.addView(card(ANSWER) {
            addView(label("Put on each pillar:", 13f, bold = true))
            (S.BAX_PILLARS.map { it.first } + "South east").forEachIndexed { i, pillar ->
                addView(pairRow("$pillar pillar", items[i] ?: if (i == 5) "(pick all five above)" else "—"), full(3))
            }
        }, full(8))
    }

    private fun sinsGrid(c: LinearLayout) {
        c.addView(label("Type the number beside each row (top to bottom) and under each column (left to right):", 13f, bold = true), full(8))
        val result = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun nums(key: String) = (texts[key] ?: "").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toInt() }
        fun update() {
            result.removeAllViews()
            val rows = nums("sins_rows"); val cols = nums("sins_cols")
            if (rows.size < 5 || cols.size < 5) return
            val grid = S.kakurasu(rows.take(5).toIntArray(), cols.take(5).toIntArray())
            if (grid == null) {
                result.addView(label("No grid fits those numbers. Check them again.", 12f).apply { setTextColor(MISSING_RED) })
                return
            }
            result.addView(card(ANSWER) {
                addView(label("Press the green squares:", 13f, bold = true))
                grid.forEach { row ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        row.forEach { on ->
                            addView(View(context).apply {
                                background = GradientDrawable().apply {
                                    setColor(if (on) SELECTED else Color.parseColor("#D8C69C")); cornerRadius = dp(3).toFloat()
                                }
                            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                        }
                    })
                }
            })
        }
        c.addView(label("Rows:", 12f, bold = true), full(4))
        c.addView(numberBox("sins_rows", "e.g. 8 8 6 6 9") { update() }, full(2))
        c.addView(label("Columns:", 12f, bold = true), full(6))
        c.addView(numberBox("sins_cols", "e.g. 8 8 11 6 6") { update() }, full(2))
        c.addView(result, full(8))
        update()
    }

    // The Enchanted Key and Mage Arena II: say roughly where you are and how hot it is
    private fun hotCold(c: LinearLayout, key: Boolean) {
        val readings = if (key) keyReadings else mageReadings
        val places = if (key) S.KEY_PLACES else S.MAGE_PLACES
        val temps = if (key) S.KEY_TEMPERATURES else S.MAGE_TEMPERATURES
        var spots = if (key) S.KEY_SPOTS else S.MAGE_SPOTS

        if (key) {
            c.addView(label("Tick the places you've already dug up:", 13f, bold = true), full(8))
            S.KEY_SPOTS.forEachIndexed { i, spot ->
                val found = prefs.getBoolean("key_found_$i", false)
                c.addView(button((if (found) "☑ " else "☐ ") + spot.name, if (found) SELECTED else BUTTON_BROWN) {
                    prefs.edit().putBoolean("key_found_$i", !found).apply(); refresh()
                }.apply { textSize = 11f; gravity = Gravity.START or Gravity.CENTER_VERTICAL }, full(2))
            }
            spots = S.KEY_SPOTS.filterIndexed { i, _ -> !prefs.getBoolean("key_found_$i", false) }
        } else {
            c.addView(label("The bosses move about every 25 minutes, so clear the readings when you start looking for the next one.", 11f)
                .apply { setTextColor(FADED) }, full(6))
        }

        c.addView(label("Add a reading: where are you, roughly?", 13f, bold = true), full(10))
        val place = choiceGrid(c, if (key) "hc_place_k" else "hc_place_m", places.map { it.name }, perRow = 2)
        c.addView(label(if (key) "What did the key say?" else "What did the symbol say?", 13f, bold = true), full(8))
        val temp = choiceGrid(c, if (key) "hc_temp_k" else "hc_temp_m", temps.map { it.text }, perRow = 3)
        c.addView(button("Add this reading", SAY_BLUE) {
            if (place >= 0 && temp >= 0) {
                readings.add(S.Reading(places[place], temps[temp]))
                choices.remove(if (key) "hc_place_k" else "hc_place_m"); choices.remove(if (key) "hc_temp_k" else "hc_temp_m")
                refresh()
            }
        }.apply { alpha = if (place >= 0 && temp >= 0) 1f else 0.5f }, full(6))

        if (readings.isNotEmpty()) {
            c.addView(label("Your readings:", 12f, bold = true), full(8))
            readings.forEach { r -> c.addView(label("• ${r.temperature.text} near ${r.place.name}", 12f), full(1)) }
            c.addView(button("Clear readings") { readings.clear(); refresh() }.apply { textSize = 11f }, full(4))
        }

        val fits = S.hotCold(spots, readings, slack = 12)
        c.addView(card(ANSWER) {
            addView(label(when {
                readings.isEmpty() -> "It could be any of these:"
                fits.isEmpty() -> "Nothing fits every reading. Clear them and try again."
                fits.size == 1 -> if (key) "Dig here:" else "The boss is here:"
                else -> "It's one of these (most likely first):"
            }, 13f, bold = true))
            fits.forEach { addView(label("• " + it.name, 13f).apply { if (fits.size == 1) setTextColor(SELECTED) }, full(2)) }
        }, full(8))
    }

    // ---------------- Trial and error trackers (kept even if you close the app) ----------------

    // Desert Treasure II: the order of the six rune rifts
    private fun riftTracker(c: LinearLayout) =
        orderTracker(c, "rift", listOf("Earth", "Cosmic", "Death", "Nature", "Law", "Fire"))

    // Finding a hidden order by trial and error: what's been found, and what's been ruled out next
    private fun orderTracker(c: LinearLayout, key: String, runes: List<String>) {
        val order = (prefs.getString("${key}_order", "") ?: "").split(",").filter { it.isNotEmpty() }
        val wrong = (prefs.getString("${key}_wrong", "") ?: "").split(",").filter { it.isNotEmpty() }   // wrong for the next one
        fun save(o: List<String>, w: List<String>) {
            prefs.edit().putString("${key}_order", o.joinToString(",")).putString("${key}_wrong", w.joinToString(",")).apply()
            refresh()
        }

        c.addView(card(ANSWER) {
            addView(label("Order found so far:", 13f, bold = true))
            addView(label(if (order.isEmpty()) "Nothing yet" else order.mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("   "),
                14f, bold = true).apply { setTextColor(SAY_BLUE) }, full(3))
            if (order.isNotEmpty() && order.size < runes.size) {
                addView(label("If it resets, activate these again first, in this order.", 11f), full(4))
            }
        }, full(8))

        if (order.size >= runes.size) {
            c.addView(label("All ${runes.size} found! ✓", 14f, bold = true).apply { setTextColor(SELECTED) }, full(8))
        } else {
            c.addView(label("Number ${order.size + 1}: try one, then tap what happened.", 13f, bold = true), full(10))
            for (r in runes) {
                if (r in order) continue
                val isWrong = r in wrong
                c.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(r + if (isWrong) "  (not this one)" else "", 13f, bold = !isWrong)
                        .apply { if (isWrong) setTextColor(FADED) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (!isWrong) {
                        addView(button("✓ Worked", SELECTED) { save(order + r, emptyList()) }.apply { textSize = 11f },
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
                        addView(button("✗ Reset", MISSING_RED) { save(order, wrong + r) }.apply { textSize = 11f })
                    }
                }, full(3))
            }
            val left = runes.filter { it !in order && it !in wrong }
            if (left.size == 1) c.addView(label("It must be ${left[0]}.", 13f, bold = true).apply { setTextColor(SELECTED) }, full(6))
        }
        c.addView(button("Start over") { save(emptyList(), emptyList()) }.apply { textSize = 11f }, full(10))
    }

    // Lunar Diplomacy: the safe path across the cloud tiles (4 wide, 8 long, north ledge to south ledge)
    private fun cloudTracker(c: LinearLayout) {
        val cells = (prefs.getString("cloud_tiles", null) ?: "0".repeat(32)).padEnd(32, '0')   // 0 unknown, 1 safe, 2 not safe
        c.addView(label("Tap a tile to mark it: once for safe (green), again for not safe (red), again to clear.", 12f), full(6))
        c.addView(label("North ledge (start)", 11f, bold = true).apply { gravity = Gravity.CENTER; setTextColor(FADED) }, full(8))
        for (row in 0 until 8) {
            c.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                for (col in 0 until 4) {
                    val i = row * 4 + col
                    val state = cells[i]
                    addView(button(when (state) { '1' -> "✓"; '2' -> "✗"; else -> "" },
                        when (state) { '1' -> SELECTED; '2' -> MISSING_RED; else -> Color.parseColor("#B8A882") }) {
                        val next = when (state) { '0' -> '1'; '1' -> '2'; else -> '0' }
                        prefs.edit().putString("cloud_tiles", cells.substring(0, i) + next + cells.substring(i + 1)).apply()
                        refresh()
                    }.apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(dp(44), dp(34)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                }
            })
        }
        c.addView(label("South ledge (finish)", 11f, bold = true).apply { gravity = Gravity.CENTER; setTextColor(FADED) }, full(2))
        c.addView(label("You can step south, east or west. The path never goes back north.", 11f).apply { setTextColor(FADED) }, full(8))
        // There are only ten possible paths, so a few marks are usually enough to know the rest
        val fits = S.cloudMatches(cells)
        c.addView(card(ANSWER) {
            when {
                fits.isEmpty() -> addView(label("None of the ten known paths fit these marks. Check them, or clear the grid.", 12f)
                    .apply { setTextColor(MISSING_RED) })
                fits.size == 1 -> {
                    addView(label("Only one path fits. Walk on the green tiles:", 13f, bold = true))
                    val path = S.CLOUD_PATHS[fits[0]]
                    for (row in 0 until 8) addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                        for (col in 0 until 4) addView(View(context).apply {
                            background = GradientDrawable().apply {
                                setColor(if (path[row * 4 + col] == '1') SELECTED else Color.parseColor("#D8C69C")); cornerRadius = dp(3).toFloat()
                            }
                        }, LinearLayout.LayoutParams(dp(30), dp(22)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                    })
                }
                else -> {
                    addView(label("${fits.size} of the 10 known paths still fit.", 13f, bold = true))
                    // tiles that are safe in every path still possible
                    val sure = (0 until 32).filter { i -> cells[i] != '1' && fits.all { S.CLOUD_PATHS[it][i] == '1' } }
                    if (sure.isNotEmpty()) addView(label("Safe for sure: " + sure.joinToString(", ") {
                        "row ${it / 4 + 1}, " + listOf("west", "middle-west", "middle-east", "east")[it % 4]
                    }, 12f), full(4))
                    val firstRow = (0 until 4).filter { col -> fits.any { S.CLOUD_PATHS[it][col] == '1' } }
                    if ((0 until 4).none { cells[it] == '1' }) addView(label("The first tile is one of: " +
                        firstRow.joinToString(", ") { listOf("west", "middle-west", "middle-east", "east")[it] }, 12f), full(4))
                }
            }
        }, full(4))
        c.addView(button("Clear the grid") { prefs.edit().remove("cloud_tiles").apply(); refresh() }.apply { textSize = 11f }, full(8))
    }

    // The Eyes of Glouphrie and The Path of Glouphrie: which discs make a value
    private fun discs(c: LinearLayout) {
        c.addView(label("What value do you need? Type it (the Eyes of Glouphrie shows a green number), or tap the discs " +
            "the machine shows to add them up (the Path of Glouphrie).", 13f, bold = true), full(8))
        val result = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val shown = (texts["disc_shown"] ?: "").split(",").filter { it.isNotEmpty() }.map { it.toInt() }
        fun target(): Int? = (texts["disc_target"] ?: "").trim().toIntOrNull() ?: shown.takeIf { it.isNotEmpty() }?.sumOf { S.DISCS[it].value }
        fun update() {
            result.removeAllViews()
            val t = target() ?: return
            val count = (choices["disc_count"] ?: 0) + 1
            val combos = S.discCombos(t, count)
            result.addView(card(ANSWER) {
                addView(label("To make $t with $count disc" + (if (count > 1) "s" else "") + ":", 13f, bold = true))
                if (combos.isEmpty()) addView(label("No way to do it with $count disc" + (if (count > 1) "s." else "."), 12f).apply { setTextColor(MISSING_RED) })
                combos.forEach { combo -> addView(label("• " + combo.joinToString(" + ") { "${it.name} (${it.value})" }, 12f), full(2)) }
                addView(label("Swap discs at the exchanger if you don't have these.", 11f).apply { setTextColor(FADED) }, full(6))
            })
        }
        c.addView(numberBox("disc_target", "e.g. 24") { update() }, full(2))
        c.addView(label("…or tap the discs shown:", 12f, bold = true), full(8))
        val colour = choiceGrid(c, "disc_colour", S.DISC_COLOURS, perRow = 4)
        val shape = choiceGrid(c, "disc_shape", S.DISC_SHAPES.map { it.first }, perRow = 4)
        c.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Add disc", SAY_BLUE) {
                if (colour >= 0 && shape >= 0) {
                    texts["disc_shown"] = (shown + (colour * 4 + shape)).joinToString(",")
                    texts.remove("disc_target"); refresh()
                }
            }.apply { alpha = if (colour >= 0 && shape >= 0) 1f else 0.5f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            addView(button("Clear") { texts.remove("disc_shown"); texts.remove("disc_target"); refresh() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, full(4))
        if (shown.isNotEmpty()) c.addView(label("Shown: " + shown.joinToString(" + ") { "${S.DISCS[it].name} (${S.DISCS[it].value})" } +
            " = ${shown.sumOf { S.DISCS[it].value }}", 12f, bold = true), full(4))
        c.addView(label("How many discs go in the slot?", 12f, bold = true), full(8))
        choiceGrid(c, "disc_count", listOf("1", "2", "3"), perRow = 3)
        c.addView(result, full(8))
        update()
    }

    // King's Ransom: the four-tumbler lock, raising every tumbler that isn't green yet
    private fun tumblers(c: LinearLayout) {
        val heights = (prefs.getString("kr_heights", null) ?: "1111").map { it - '0' }
        val green = (prefs.getString("kr_green", null) ?: "0000").map { it == '1' }
        fun save(h: List<Int>, g: List<Boolean>) {
            prefs.edit().putString("kr_heights", h.joinToString("")).putString("kr_green", g.joinToString("") { if (it) "1" else "0" }).apply()
            refresh()
        }
        c.addView(card(ANSWER) {
            addView(label("Set the tumblers to these heights (1 = lowest), then try the lock:", 13f, bold = true))
            heights.forEachIndexed { i, h -> addView(pairRow("Tumbler ${i + 1}", if (green[i]) "$h  ✓ green, leave it" else "$h"), full(3)) }
        }, full(8))
        if (green.all { it }) {
            c.addView(label("All four are green: the lock opens! ✓", 14f, bold = true).apply { setTextColor(SELECTED) }, full(8))
        } else {
            c.addView(label("After trying, tap each tumbler that showed a green circle (red and blue don't matter):", 12f, bold = true), full(10))
            c.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                (0 until 4).forEach { i ->
                    addView(button((if (green[i]) "✓ " else "") + "${i + 1}", if (green[i]) SELECTED else BUTTON_BROWN) {
                        save(heights, green.mapIndexed { j, g -> if (j == i) !g else g })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
            }, full(4))
            c.addView(button("Next try: raise the others by one", SAY_BLUE) {
                save(heights.mapIndexed { i, h -> if (green[i]) h else h + 1 }, green)
            }, full(8))
        }
        c.addView(button("Start over") { prefs.edit().remove("kr_heights").remove("kr_green").apply(); refresh() }.apply { textSize = 11f }, full(10))
    }

    // ---------------- Small building blocks ----------------

    // One option per row; tap to pick. Returns the picked index, or -1.
    private fun choiceList(c: LinearLayout, key: String, options: List<String>): Int {
        val picked = choices[key] ?: -1
        options.forEachIndexed { i, text ->
            c.addView(button(text, if (i == picked) SELECTED else BUTTON_BROWN) { choices[key] = i; refresh() }
                .apply { textSize = 12f }, full(3))
        }
        return picked
    }

    // Options in a grid of buttons. Returns the picked index, or -1.
    private fun choiceGrid(c: LinearLayout, key: String, options: List<String>, perRow: Int): Int {
        val picked = choices[key] ?: -1
        options.chunked(perRow).forEachIndexed { rowIndex, row ->
            c.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { j, text ->
                    val i = rowIndex * perRow + j
                    addView(button(text, if (i == picked) SELECTED else BUTTON_BROWN) { choices[key] = i; refresh() }
                        .apply { textSize = if (perRow >= 4) 11f else 12f; setPadding(dp(2), dp(7), dp(2), dp(7)) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
                repeat(perRow - row.size) { addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            }, full(3))
        }
        return picked
    }

    private fun letterBox(key: String, length: Int, hint: String, onChange: (String) -> Unit) = EditText(context).apply {
        setText(texts[key] ?: "")
        this.hint = hint
        textSize = 18f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(length), InputFilter.AllCaps())
        addTextChangedListener(watcher { texts[key] = it; onChange(it) })
    }

    private fun numberBox(key: String, hint: String, onChange: () -> Unit) = EditText(context).apply {
        setText(texts[key] ?: "")
        this.hint = hint
        textSize = 16f
        setSingleLine()
        // numbers separated by spaces
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addTextChangedListener(watcher { texts[key] = it; onChange() })
    }

    private fun watcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onText(s?.toString() ?: "") }
    }

    private fun answer(text: String) = card(ANSWER) { addView(label(text, 14f, bold = true)) }

    private fun pairRow(a: String, b: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(a, 12f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        addView(label(b, 13f, bold = true).apply { setTextColor(SAY_BLUE) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
    }

    private fun card(color: Int, fill: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))
        background = GradientDrawable().apply { setColor(color); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
        fill()
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
