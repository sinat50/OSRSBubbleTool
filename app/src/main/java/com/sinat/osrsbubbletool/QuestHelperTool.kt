/*
 * Quest guides adapted from the Quest Helper RuneLite plugin (github.com/Zoinkwiz/quest-helper).
 *
 * Copyright (c) 2020, Zoinkwiz
 * Scrambled! guide: Copyright (c) 2025, pajlada <https://github.com/pajlada>
 * Other guides: see the copyright list at the top of QuestGuides.kt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sinat.osrsbubbletool.QuestBook.Met
import com.sinat.osrsbubbletool.QuestBook.Req
import com.sinat.osrsbubbletool.QuestBook.Status

// Quest Helper: every quest's requirements (checked against your account with WikiSync),
// step-by-step guides for some quests, and the achievement diaries.
class QuestHelperTool(
    private val context: Context,
    private val openWiki: (String) -> Unit,  // shows a wiki page in the Wiki window
    private val hideWindow: () -> Unit = {}  // gets the window out of the way (e.g. before opening the browser)
) {
    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val SELECTED_BROWN = Color.parseColor("#5A4220")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val CURRENT = Color.parseColor("#FFF4D6")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private val SAY_BLUE = Color.parseColor("#1F4E8C")
        private val FADED = Color.parseColor("#8C7B5E")
        private val MISSING_RED = Color.parseColor("#B03A2E")
        private val PARTLY_ORANGE = Color.parseColor("#C0600A")
        private const val MAX_DEPTH = 8
    }

    // ---------------- Quest guides ----------------

    class Step(
        val text: String,
        val say: List<String> = emptyList(),    // dialogue options to choose, in order
        val items: List<String> = emptyList(),  // what to have with you
        val tip: String? = null,
        val optional: Boolean = false
    )

    class Section(
        val title: String,
        val steps: List<Step>,
        val items: List<String> = emptyList(),    // the quest's items you need during this part
        val combat: Boolean? = false,             // does this part have fighting in it? (null = not known)
        val enemies: List<String> = emptyList(),  // who you'll be fighting
        val travel: String? = null,               // how to get there
        val recommended: List<String> = emptyList()
    )

    // A full step-by-step guide. Its requirements come from QuestBook, by name.
    class Quest(
        val key: String,
        val name: String,
        val wikiPage: String,
        val items: List<String>,
        val recommended: List<String>,
        val enemies: List<String>,
        val rewards: List<String>,
        val sections: List<Section>
    ) {
        val steps: List<Step> get() = sections.flatMap { it.steps }
    }

    private val guides = listOf(
        Quest(
            key = "scrambled",
            name = "Scrambled!",
            wikiPage = "https://oldschool.runescape.wiki/w/Scrambled%21/Quick_guide",
            items = listOf("Hammer, saw, 6 nails, 2 planks (all found during the quest)",
                "An empty bowl (found during the quest)", "Combat gear"),
            recommended = listOf("Antifire shield (for the red dragon)", "40 Agility (for a shortcut to the dragon cave)"),
            enemies = listOf("Large chicken (level 16)", "Black jaguar (level 88)", "Red dragon (level 106, can be safespotted)"),
            rewards = listOf("1 Quest point", "5,000 Construction, Cooking and Smithing XP", "Your very own egg"),
            sections = listOf(
                Section("Humphrey Dumphrey sat on a wall", listOf(
                    Step("Talk to Alan in Tal Teok Temple, north of Tal Teklan, to start the quest.",
                        say = listOf("Yes.", "Of course!"))
                ), items = listOf("Nothing"),
                    travel = "Get to Tal Teok Temple, north of Tal Teklan, e.g. with a quetzal whistle."),
                Section("Wumty Scrumpty had a great fall", listOf(
                    Step("Inspect the egg in front of you."),
                    Step("Head south into Tal Teklan and talk to King in the pub.",
                        say = listOf("Are you the king?", "…fell off a wall!"))
                ), items = listOf("Nothing"), travel = "Walk: Tal Teklan is just south of the temple."),
                Section("All the king's horses and all the king's men…", listOf(
                    Step("Get an empty bowl from the Tal Teklan pub. You'll need it later.",
                        tip = "If it's not there, hop worlds or wait for it to respawn."),
                    // Acatzin
                    Step("Talk to Acatzin inside the Tal Teklan pub.",
                        say = listOf("I can talk to the blacksmith.")),
                    Step("Talk to the Blacksmith, west of the Tal Teklan pub."),
                    Step("Take the hammer from the table in the blacksmith's.",
                        tip = "If it's not there, hop worlds or wait for it to respawn."),
                    Step("Get some nails from the blacksmith's workbench. You'll need them later.",
                        say = listOf("Take the nails.")),
                    Step("Fix the whetstone.", say = listOf("Yes."), items = listOf("Hammer")),
                    Step("Talk to the Blacksmith again to get a damaged axe."),
                    Step("Repair the axe on the whetstone.", say = listOf("Yes."),
                        items = listOf("Acatzin's damaged axe")),
                    Step("Get the saw from the house to the south. You'll need it later.",
                        tip = "If it's not there, hop worlds or wait for it to respawn."),
                    Step("Return the repaired axe to Acatzin in the Tal Teklan pub."),
                    // Kauayotl
                    Step("Pick the damiana bush at the east entrance of Tal Teklan for damiana leaves. You'll need them later."),
                    Step("Pick up two planks beside the lake, south-west of Kauayotl.",
                        tip = "If they're not there, hop worlds or wait for them to respawn."),
                    Step("Talk to Kauayotl at the east entrance of Tal Teklan.",
                        say = listOf("I see. Well, maybe I can help out with that?"),
                        items = listOf("Hammer", "Saw", "6 nails", "2 planks")),
                    Step("Repair Kauayotl's cart.", say = listOf("Yes."),
                        items = listOf("Hammer", "Saw", "6 nails", "2 planks")),
                    Step("Talk to Kauayotl again."),
                    // Nezketi
                    Step("Talk to Nezketi in the Tal Teklan temple. He gives you an empty cup.",
                        say = listOf("I can get you some tea.")),
                    Step("Fill your empty bowl with water at the water pump near the east entrance of Tal Teklan.",
                        items = listOf("Empty bowl")),
                    Step("Use the damiana leaves on the bowl of water.",
                        items = listOf("Damiana leaves", "Bowl of water")),
                    Step("Use the damiana water on the stove in the east part of Tal Teklan to boil it.",
                        items = listOf("Damiana water")),
                    Step("Pour the damiana tea from the bowl into the empty cup.",
                        items = listOf("Damiana tea", "Empty cup")),
                    Step("Give the cup of damiana tea to Nezketi in the Tal Teklan temple.",
                        items = listOf("Cup of damiana tea")),
                    Step("Return to Tal Teok Temple, north of Tal Teklan, and talk to one of King's men.")
                ), items = listOf("Nothing to bring. You pick up a bowl, hammer, nails, saw and planks along the way"),
                    travel = "Everything is in and around Tal Teklan, so walk."),
                Section("Couldn't put Bumpty Numpty back together again…", listOf(
                    Step("Search the Eggs in the chicken coop south of the large temple. A level 16 large chicken " +
                        "appears: kill it, then search the Eggs again for a Large egg.",
                        items = listOf("Combat gear")),
                    Step("Cross the log balance towards the dragon cave.",
                        tip = "Needs 40 Agility. Without it, walk round to the cave entrance.", optional = true),
                    Step("Enter the dragon cave, south-east of the large temple across the river.",
                        items = listOf("Combat gear", "Antifire shield (recommended)")),
                    Step("Search the Eggs in the dragon cave. Kill the red dragon that appears, then search the " +
                        "Eggs again for a Dragon egg.",
                        tip = "Safespot: stand just south-east of the Eggs."),
                    Step("Leave the dragon cave."),
                    Step("Search the Eggs at the camp east of the dragon cave. Kill the black jaguar that appears, " +
                        "then search the Eggs again for a Jaguar egg.",
                        tip = "Safespot: stand just north-east of the camp."),
                    Step("Return to Tal Teok Temple. Give Kauayotl the Dragon egg, Nezketi the Jaguar egg and " +
                        "Acatzin the Large egg.",
                        items = listOf("Large egg", "Dragon egg", "Jaguar egg")),
                    Step("Talk to one of King's men to judge the replacement eggs."),
                    Step("Talk to one of King's men again to work out how to fix the broken egg."),
                    Step("Inspect the broken egg and put it back together.",
                        tip = "It's a jigsaw of 26 pieces. Tap a piece to turn it: it only fits once it's the " +
                            "right way up. Then drag it into place. Hover over (or press and hold) the puzzle-piece icon to see the finished egg."),
                    Step("Talk to one of King's men to finish the quest!")
                ), items = listOf("Combat gear and food", "Antifire shield (recommended, for the red dragon)"),
                    combat = true,
                    travel = "Walk: the chicken coop is just south of Tal Teok Temple, the dragon cave is to the " +
                        "east, and the jaguar camp is further east of the dragon cave.",
                    enemies = listOf("Large chicken (level 16)", "Red dragon (level 106, can be safespotted)",
                        "Black jaguar (level 88, can be safespotted)"))
            )
        )
    )

    // The hand-written guide if there is one, otherwise the one from the plugin (read when first needed)
    private val loadedGuides = HashMap<String, Quest?>()

    private fun guideFor(name: String): Quest? {
        guides.firstOrNull { it.name == name }?.let { return it }
        if (name !in QuestGuides.names) return null
        return loadedGuides.getOrPut(name) {
            try { parseGuide(name, org.json.JSONObject(QuestGuides.json(name) ?: return@getOrPut null)) }
            catch (e: Exception) { null }
        }
    }

    private fun parseGuide(name: String, o: org.json.JSONObject): Quest {
        fun strings(a: org.json.JSONArray?) = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
        val secs = o.getJSONArray("sec")
        return Quest(
            key = "guide_" + name,
            name = name,
            wikiPage = QuestBook.wikiUrl(if (QuestBook.quest(name)?.miniquest == true) name else "$name/Quick_guide"),
            items = strings(o.optJSONArray("items")),
            recommended = strings(o.optJSONArray("rec")),
            enemies = strings(o.optJSONArray("enemies")),
            rewards = strings(o.optJSONArray("rewards")),
            sections = List(secs.length()) { i ->
                val sec = secs.getJSONObject(i)
                val st = sec.getJSONArray("st")
                Section(
                    title = sec.getString("n"),
                    steps = List(st.length()) { j ->
                        val step = st.getJSONObject(j)
                        Step(step.getString("t"), strings(step.optJSONArray("s")), strings(step.optJSONArray("i")))
                    },
                    items = strings(sec.optJSONArray("i")),
                    combat = if (sec.has("c")) sec.getInt("c") == 1 else null,
                    enemies = strings(sec.optJSONArray("e")),
                    travel = strings(sec.optJSONArray("tr")).takeIf { it.isNotEmpty() }?.joinToString(", "),
                    recommended = strings(sec.optJSONArray("r"))
                )
            }
        )
    }

    // ---------------- State ----------------

    private val prefs = context.getSharedPreferences("quest_helper", Context.MODE_PRIVATE)
    private fun position(q: Quest) = prefs.getInt(q.key, 0).coerceIn(0, q.steps.size)
    private fun setPosition(q: Quest, i: Int) = prefs.edit().putInt(q.key, i.coerceIn(0, q.steps.size)).apply()

    private val sync = WikiSync(context)
    private var syncMessage: String? = null   // the last problem fetching from WikiSync
    private var editingName = false
    private var syncOpen = false               // is the WikiSync section open? (starts closed)

    private enum class Tab { QUESTS, DIARIES }
    private var tab = Tab.QUESTS
    private var search = ""
    private var hideFinished: Boolean
        get() = prefs.getBoolean("hide_finished", false)
        set(v) { prefs.edit().putBoolean("hide_finished", v).apply() }
    private var hideDoneTasks: Boolean
        get() = prefs.getBoolean("hide_done_tasks", false)
        set(v) { prefs.edit().putBoolean("hide_done_tasks", v).apply() }

    private var openQuest: String? = null      // a quest's page (guide or requirements)
    private var openDiary: String? = null      // a diary region's page
    private var openTier = 0
    private var showDetails = false
    private var openPuzzle: QuestPuzzles.Puzzle? = null   // a puzzle solution, opened from a quest
    private val solverViews = QuestSolverViews(context) { show() }
    private var showPuzzles = false
    private var showHelp = false                 // the "Help improve this quest guide" page
    private val expanded = HashSet<String>()   // requirement rows opened to show their own requirements

    private lateinit var holder: FrameLayout

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Screens ----------------

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        // fetch fresh data whenever the window is opened, if it's been a while
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (sync.needsRefresh) refresh() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return holder
    }

    // The window's ◀ button: back to the list
    fun goBack() {
        if (showHelp) { showHelp = false; show(keepScroll = false); return }
        if (openPuzzle != null) { openPuzzle = null; show(keepScroll = false); return }
        if (openQuest != null || openDiary != null) {
            openQuest = null; openDiary = null; showDetails = false; expanded.clear()
            show(keepScroll = false)
        }
    }

    private fun refresh() {
        syncMessage = "Checking WikiSync…"
        show()
        sync.refresh { _, error ->
            syncMessage = error
            show()
        }
    }

    // Rebuilds the window. keepScroll keeps your place when something on the same page changes.
    private fun show(keepScroll: Boolean = true) {
        val oldScroll = (holder.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        holder.removeAllViews()
        val quest = openQuest
        val diary = openDiary
        val puzzle = openPuzzle
        val view = when {
            showHelp && quest != null -> helpScreen(quest)
            puzzle != null -> puzzleScreen(puzzle)
            quest != null -> guideFor(quest)?.let { guideScreen(it) } ?: questInfoScreen(quest)
            diary != null -> diaryScreen(diary)
            else -> listScreen()
        }
        holder.addView(view)
        if (keepScroll && view is ScrollView) view.post { view.scrollTo(0, oldScroll) }
    }

    private fun openQuestPage(name: String) {
        openQuest = name; openDiary = null; openPuzzle = null; showDetails = false; showPuzzles = false; showHelp = false; expanded.clear()
        show(keepScroll = false)
    }

    // ---------------- The main list: quests or diaries ----------------

    private fun listScreen(): View = page {
        addView(accountCard())
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tabButton("Quests", tab == Tab.QUESTS) { tab = Tab.QUESTS; show(keepScroll = false) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            addView(tabButton("Diaries", tab == Tab.DIARIES) { tab = Tab.DIARIES; show(keepScroll = false) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3) })
        }, full(8))
        if (tab == Tab.QUESTS) questList(this) else diaryList(this)
    }

    private fun questList(column: LinearLayout) {
        val data = sync.cached
        if (data != null) {
            val quests = QuestBook.quests.filter { !it.miniquest }
            val done = quests.count { data.questState(it.name) == WikiSync.FINISHED }
            column.addView(label("Quest points: ${QuestBook.questPoints(data)}   ·   Quests done: $done of ${quests.size}",
                12f, bold = true), full(8))
        }

        val field = EditText(context).apply {
            setText(search)
            hint = "Search quests"
            textSize = 13f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        column.addView(field, full(4))
        if (data != null) {
            column.addView(smallButton(if (hideFinished) "☑ Hide finished quests" else "☐ Hide finished quests") {
                hideFinished = !hideFinished
                show()
            }, full(2))
        }

        // Only the list below is rebuilt while you type, so the search box keeps the keyboard
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list, full(4))
        fillQuestList(list)
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                search = s?.toString() ?: ""
                fillQuestList(list)
            }
        })
    }

    private fun fillQuestList(list: LinearLayout) {
        list.removeAllViews()
        val data = sync.cached
        val term = search.trim()
        val matches = QuestBook.quests.filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) }

        fun group(title: String, quests: List<QuestBook.QuestInfo>) {
            if (quests.isEmpty()) return
            list.addView(label("$title (${quests.size})", 13f, bold = true), full(10))
            quests.forEach { list.addView(questRow(it, data), full(3)) }
        }

        if (data == null) {
            group("Quests", matches.filter { !it.miniquest })
            group("Miniquests", matches.filter { it.miniquest })
            return
        }
        val by = matches.groupBy { QuestBook.status(it, data) }
        group("Started", by[Status.STARTED].orEmpty())
        group("Ready to start", by[Status.READY].orEmpty())
        group("Missing requirements", by[Status.MISSING].orEmpty())
        group("Not on WikiSync yet", by[Status.UNKNOWN].orEmpty())
        if (!hideFinished) group("Finished", by[Status.DONE].orEmpty())
        if (list.childCount == 0) list.addView(label("No quests match \"$term\".", 12f), full(8))
    }

    private fun questRow(q: QuestBook.QuestInfo, data: WikiSync.Data?): View {
        val status = QuestBook.status(q, data)
        val (mark, color) = when (status) {
            Status.DONE -> "✓" to GO_GREEN
            Status.STARTED -> "◐" to PARTLY_ORANGE
            Status.READY -> "●" to GO_GREEN
            Status.MISSING -> "✗" to MISSING_RED
            Status.UNKNOWN -> "•" to DARK_BROWN
        }
        val note = when {
            status == Status.MISSING -> "${QuestBook.missing(q.requirements, data).size} missing"
            q.miniquest -> "Miniquest"
            else -> ""
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(5).toFloat() }
            addView(label(mark, 13f, bold = true).apply { setTextColor(color); minWidth = dp(20) })
            addView(label(q.name, 13f).apply { if (status == Status.DONE) setTextColor(FADED) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (note.isNotEmpty()) addView(label(note, 10f).apply { setTextColor(if (status == Status.MISSING) MISSING_RED else FADED) })
            setOnClickListener { openQuestPage(q.name) }
        }
    }

    // ---------------- A quest without a guide: what you need ----------------

    private fun questInfoScreen(name: String): View = page {
        val info = QuestBook.quest(name)
        val data = sync.cached
        // miniquests don't have quick guides, so open their main page
        addView(titleRow(name, "Wiki guide") {
            openWiki(QuestBook.wikiUrl(if (info?.miniquest == true) name else "$name/Quick_guide"))
        })
        if (info == null) return@page
        helpButton(name)?.let { addView(it, full(6)) }
        addView(questFacts(info), full(2))
        addView(accountCard(), full(6))
        addView(summary(name, info.requirements), full(6))
        addView(card(ROW_BROWN) {
            addView(label("Requirements", 12f, bold = true))
            if (info.requirements.isEmpty()) addView(label("None", 12f), full(2))
            reqRows(this, info.requirements, data, 0, name)
            if (info.requirements.any { it is Req.QuestDone }) {
                addView(label("Tap a quest with ▸ to see what it needs.", 10f).apply { setTextColor(FADED) }, full(6))
            }
            if (info.enemies.isNotEmpty()) {
                addView(label("⚔ Enemies", 12f, bold = true).apply { setTextColor(MISSING_RED) }, full(10))
                info.enemies.forEach { addView(label("• $it", 12f), full(1)) }
            } else {
                addView(label("No required fights listed", 11f).apply { setTextColor(GO_GREEN) }, full(10))
            }
        }, full(8))
        QuestPuzzles.forQuest(name).takeIf { it.isNotEmpty() }?.let { puzzles ->
            addView(label("🧩 Puzzle solutions", 13f, bold = true), full(10))
            puzzles.forEach { addView(smallButton("🧩 ${it.title}") { openPuzzleScreen(it) }, full(4)) }
        }
        addView(label("There's no step-by-step guide for this quest in the app. The Wiki guide button " +
            "opens the wiki's guide.", 11f).apply { setTextColor(FADED) }, full(10))
    }

    // ---------------- Help improve this quest guide ----------------

    private fun helpButton(quest: String): View? {
        if (QuestPuzzles.HELP_WANTED[quest].isNullOrEmpty()) return null
        return button("📷 Help improve this quest guide", SAY_BLUE) { showHelp = true; show(keepScroll = false) }
            .apply { textSize = 12f }
    }

    private fun helpScreen(quest: String): View = page {
        val requests = QuestPuzzles.HELP_WANTED[quest].orEmpty()
        addView(titleRow("📷 Help improve this guide", "◀ Guide") { showHelp = false; show(keepScroll = false) })
        addView(label(quest, 11f).apply { setTextColor(FADED) }, full(1))
        addView(label("With a few screenshots from a phone, the app could read this puzzle from your screen and show " +
            "you what to do, like the Puzzle Box and Light Box Solvers. If you're doing this quest, you can help!", 13f), full(8))

        requests.forEach { r ->
            addView(card(ROW_BROWN) {
                addView(label("🧩 ${r.puzzle}", 13f, bold = true))
                addView(label(r.better, 12f).apply { setTypeface(typeface, Typeface.ITALIC) }, full(3))
                addView(label("Screenshots needed:", 12f, bold = true), full(6))
                r.needed.forEach { addView(label("• $it", 12f), full(2)) }
            }, full(8))
        }

        addView(card(CURRENT) {
            addView(label("How to take them", 13f, bold = true))
            listOf(
                "Use your phone's normal screenshot (not a photo of the screen or a video).",
                "Keep the phone in landscape and the game full screen.",
                "Don't crop, zoom or draw on them.",
                "Two or three of each is better than one."
            ).forEach { addView(label("• $it", 12f), full(2)) }
        }, full(8))

        addView(card(CURRENT) {
            addView(label("How to send them", 13f, bold = true))
            listOf(
                "Tap the button below. It opens the app's GitHub page in your browser, with a form already filled in.",
                "Sign in to GitHub (a free account is needed to post).",
                "Tap the box under \"Your screenshots\", then \"Attach files\" or the picture icon, and pick your screenshots.",
                "Tick off what you've included, then tap \"Create\" (or \"Submit new issue\")."
            ).forEachIndexed { i, t -> addView(label("${i + 1}. $t", 12f), full(3)) }
        }, full(8))

        addView(button("Open GitHub to send screenshots", GO_GREEN) {
            openInBrowser(QuestPuzzles.helpIssueUrl(quest, appVersion()))
        }, full(10))
        addView(label("Thank you! Screenshots are only used to build the solver: they're never put in the app.", 11f)
            .apply { setTextColor(FADED) }, full(8))
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    // Opens a page in the phone's browser, out of the way of the bubble's window
    private fun openInBrowser(url: String) {
        try {
            hideWindow()
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Couldn't open a browser", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- A puzzle's solution ----------------

    private fun openPuzzleScreen(p: QuestPuzzles.Puzzle) {
        openPuzzle = p
        show(keepScroll = false)
    }

    private fun puzzleScreen(p: QuestPuzzles.Puzzle): View = page {
        val at = prefs.getInt(p.key, 0).coerceIn(0, p.steps.size)
        fun go(i: Int) { prefs.edit().putInt(p.key, i.coerceIn(0, p.steps.size)).apply(); show() }

        addView(titleRow("🧩 ${p.title}", "◀ Guide") { openPuzzle = null; show(keepScroll = false) })
        addView(label(p.quest, 11f).apply { setTextColor(FADED) }, full(1))
        if (p.intro.isNotEmpty()) addView(label(p.intro, 13f), full(8))
        p.solver?.let { solverViews.build(it, this) }
        p.link?.let { (text, url) -> addView(button("🔗 $text", SAY_BLUE) { openWiki(url) }, full(8)) }
        p.image?.let { addView(wikiImage(it), full(8)) }

        if (p.table.isNotEmpty()) {
            addView(card(ROW_BROWN) {
                p.tableHeader?.let { (a, b) -> addView(tableRow(a, b, bold = true)) }
                p.table.forEach { (a, b) -> addView(tableRow(a, b, bold = false), full(3)) }
            }, full(8))
        }

        // A map for the current step (or the whole answer)
        p.diagram?.let { d ->
            val shownStep = at.coerceAtMost(p.steps.size - 1).coerceAtLeast(0)
            addView(PuzzleDiagramView(context, d, shownStep), full(8))
        }

        if (p.steps.isNotEmpty()) {
            if (at >= p.steps.size) {
                addView(card(CURRENT) {
                    addView(label("Done! ✓", 15f, bold = true).apply { setTextColor(GO_GREEN) })
                    p.outro?.let { addView(label(it, 13f), full(4)) }
                    addView(button("Start over") { go(0) }, full(6))
                }, full(8))
            } else {
                addView(card(CURRENT) {
                    addView(label("Step ${at + 1} of ${p.steps.size}", 11f))
                    addView(label(p.steps[at], 14f, bold = true), full(2))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(button("◀ Back") { go(at - 1) }.apply { alpha = if (at == 0) 0.4f else 1f },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                        addView(button("Done ✓", GO_GREEN) { go(at + 1) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { leftMargin = dp(4) })
                    }, full(8))
                }, full(8))
            }
            p.steps.forEachIndexed { i, text ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    if (i == at) background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(4).toFloat() }
                    addView(label(if (i < at) "✓" else "${i + 1}.", 12f, bold = i == at).apply {
                        setTextColor(if (i < at) GO_GREEN else DARK_BROWN); minWidth = dp(24)
                    })
                    addView(label(text, 12f).apply { if (i < at) setTextColor(FADED) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { go(i) }
                }, full(2))
            }
        } else {
            p.outro?.let { addView(label(it, 13f), full(8)) }
        }
    }

    // A picture from the wiki. It's downloaded the first time it's needed and kept on the phone,
    // so it works offline afterwards and never takes you out of the Quest Helper.
    private fun wikiImage(url: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val file = java.io.File(java.io.File(context.filesDir, "wiki_images").apply { mkdirs() }, url.substringAfterLast('/'))
        val bitmap = if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.path) else null
        if (bitmap != null) {
            addView(android.widget.ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }, full())
            addView(label("Picture: OSRS Wiki", 10f).apply { setTextColor(FADED); gravity = Gravity.END }, full(2))
            return@apply
        }
        addView(label(if (file.exists()) "The saved picture is damaged. Retrying…" else "Getting the picture from the wiki…", 12f)
            .apply { setTextColor(FADED) })
        file.delete()
        downloadImage(url, file)
    }

    private val downloading = HashSet<String>()

    private fun downloadImage(url: String, file: java.io.File) {
        if (!downloading.add(url)) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            var ok = false
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.setRequestProperty("User-Agent", "OSRSBubbleTool (Android app)")
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                try {
                    if (c.responseCode == 200) {
                        val tmp = java.io.File(file.path + ".part")
                        c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        ok = tmp.renameTo(file)
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) { }
            main.post {
                downloading.remove(url)
                if (!ok) android.widget.Toast.makeText(context, "Couldn't get the picture. Check your internet connection.",
                    android.widget.Toast.LENGTH_SHORT).show()
                else show()
            }
        }.start()
    }

    private fun tableRow(a: String, b: String, bold: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(a, 12f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        addView(label(b, 12f, bold = bold).apply { if (!bold) setTextColor(SAY_BLUE) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
    }

    // "Members  ·  Intermediate  ·  1 Quest point"
    private fun questFacts(info: QuestBook.QuestInfo): View =
        label(listOf(if (info.miniquest) "Miniquest" else if (info.members) "Members" else "Free to play",
            info.difficulty, if (info.questPoints > 0) "${info.questPoints} Quest point" + (if (info.questPoints > 1) "s" else "") else null)
            .filterNotNull().filter { it.isNotEmpty() }.joinToString("  ·  "), 11f).apply { setTextColor(FADED) }

    // ---------------- A quest with a step-by-step guide ----------------

    private fun guideScreen(q: Quest): View {
        val steps = q.steps
        val at = position(q)
        val requirements = QuestBook.quest(q.name)?.requirements.orEmpty()
        val scroll = ScrollView(context)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(12))
        }

        column.addView(titleRow(q.name, "Wiki guide") { openWiki(q.wikiPage) })
        helpButton(q.name)?.let { column.addView(it, full(6)) }
        QuestBook.quest(q.name)?.let { column.addView(questFacts(it), full(2)) }
        column.addView(accountCard(), full(6))
        column.addView(summary(q.name, requirements), full(6))

        // Before you start
        column.addView(smallButton(if (showDetails) "Hide requirements ▴" else "Requirements & rewards ▾") {
            showDetails = !showDetails
            show()
        }, full(6))
        if (showDetails) column.addView(detailsCard(q, requirements), full(4))
        val puzzles = QuestPuzzles.forQuest(q.name)
        if (puzzles.isNotEmpty()) {
            column.addView(smallButton("🧩 Puzzle solutions (${puzzles.size}) " + if (showPuzzles) "▴" else "▾") {
                showPuzzles = !showPuzzles
                show()
            }, full(6))
            if (showPuzzles) puzzles.forEach { p ->
                column.addView(smallButton("🧩 ${p.title}") { openPuzzleScreen(p) }.apply { alpha = 0.9f }, full(3))
            }
        }

        // The current step, big
        if (at >= steps.size) {
            column.addView(card(CURRENT) {
                addView(label("Quest complete! ✓", 15f, bold = true).apply { setTextColor(GO_GREEN) })
                addView(button("Start over") { setPosition(q, 0); show() }, full(6))
            }, full(8))
        } else {
            val step = steps[at]
            val section = sectionOf(q, at)
            column.addView(card(CURRENT) {
                addView(label(section.title, 11f, bold = true).apply { setTextColor(FADED) })
                addView(sectionInfo(section), full(2))
                addView(label("Step ${at + 1} of ${steps.size}" + (if (step.optional) " (optional)" else ""), 11f), full(6))
                addView(stepBody(step, big = true), full(2))
                QuestPuzzles.forStep(q.name, section.title, step.text).forEach { p ->
                    addView(button("🧩 Solution: ${p.title}", SAY_BLUE) { openPuzzleScreen(p) }, full(6))
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("◀ Back") { setPosition(q, at - 1); show() }.apply { alpha = if (at == 0) 0.4f else 1f },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                    addView(button("Done ✓", GO_GREEN) { setPosition(q, at + 1); show() },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { leftMargin = dp(4) })
                }, full(8))
            }, full(8))
        }

        // Every step, by section. Tap one to jump there.
        var index = 0
        for (section in q.sections) {
            column.addView(label(section.title, 13f, bold = true), full(14))
            column.addView(sectionInfo(section), full(2))
            for (step in section.steps) {
                val i = index++
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    if (i == at) background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(4).toFloat() }
                    addView(label(if (i < at) "✓" else "${i + 1}.", 12f, bold = i == at).apply {
                        setTextColor(if (i < at) GO_GREEN else DARK_BROWN)
                        minWidth = dp(24)
                    })
                    addView(label(step.text, 12f).apply { if (i < at) setTextColor(FADED) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    setOnClickListener { setPosition(q, i); show() }
                }
                column.addView(row, full(2))
            }
        }

        scroll.addView(column)
        return scroll
    }

    // The section a step belongs to
    private fun sectionOf(q: Quest, stepIndex: Int): Section {
        var start = 0
        for (section in q.sections) {
            if (stepIndex < start + section.steps.size) return section
            start += section.steps.size
        }
        return q.sections.last()
    }

    // What a section needs: its items, how to get there, and whether there's fighting (and against what)
    private fun sectionInfo(section: Section): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        if (section.items.isNotEmpty()) {
            addView(label("Items: " + section.items.joinToString(", "), 11f))
        }
        if (section.recommended.isNotEmpty()) {
            addView(label("Recommended: " + section.recommended.joinToString(", "), 11f).apply { setTextColor(FADED) }, full(1))
        }
        section.travel?.let { addView(label("Travel: $it", 11f).apply { setTextColor(SAY_BLUE) }, full(1)) }
        if (section.combat == true) {
            addView(label("⚔ Combat: bring combat gear", 11f, bold = true).apply { setTextColor(MISSING_RED) }, full(1))
            if (section.enemies.isNotEmpty()) {
                addView(label("Enemies: " + section.enemies.joinToString(", "), 11f).apply { setTextColor(MISSING_RED) }, full(1))
            }
        } else if (section.combat == false) {
            addView(label("No combat", 11f).apply { setTextColor(GO_GREEN) }, full(1))
        }
    }

    // A step's instructions, what to say, what to bring, and any tip
    private fun stepBody(step: Step, big: Boolean): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(step.text, if (big) 14f else 12f, bold = big))
        if (step.say.isNotEmpty()) {
            addView(label("Choose:", 11f, bold = true), full(6))
            step.say.forEachIndexed { n, option ->
                addView(label("${n + 1}. “$option”", 13f).apply { setTextColor(SAY_BLUE) }, full(1))
            }
        }
        // leave out anything the step already mentions ("Take the hammer" doesn't need "Bring: Hammer")
        val bring = step.items.filter { item ->
            val name = item.substringBefore(" (").replace(Regex("^\\d+\\s+"), "").lowercase()
            !step.text.lowercase().contains(name)
        }
        if (bring.isNotEmpty()) {
            addView(label("Bring: " + bring.joinToString(", "), 11f), full(6))
        }
        step.tip?.let { addView(label("Tip: $it", 11f).apply { setTypeface(typeface, Typeface.ITALIC) }, full(6)) }
    }

    private fun detailsCard(q: Quest, requirements: List<Req>): View = card(ROW_BROWN) {
        fun group(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            addView(label(title, 12f, bold = true), full(6))
            lines.forEach { addView(label("• $it", 12f), full(1)) }
        }
        addView(label("Requirements", 12f, bold = true), full(2))
        reqRows(this, requirements, sync.cached, 0, q.name)
        group("Items", q.items)
        group("Recommended", q.recommended)
        group("Enemies", q.enemies)
        group("Rewards", q.rewards)
    }

    // ---------------- Achievement diaries ----------------

    private val tierShort = mapOf("Easy" to "E", "Medium" to "M", "Hard" to "H", "Elite" to "El")

    private fun diaryList(column: LinearLayout) {
        val data = sync.cached
        if (data == null) {
            column.addView(label("Enable WikiSync to see which tasks you've done.", 12f, bold = true), full(8))
        }
        for (diary in QuestBook.diaries) {
            column.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(5).toFloat() }
                addView(label(diary.region, 13f, bold = true))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (tier in diary.tiers) {
                        val done = data?.diaryTasks(diary.region, tier.tier)
                        val count = done?.count { it } ?: 0
                        val total = tier.tasks.size
                        val text = when {
                            done == null -> "${tierShort[tier.tier]} $total"
                            count >= total -> "${tierShort[tier.tier]} ✓"
                            else -> "${tierShort[tier.tier]} $count/$total"
                        }
                        addView(label(text, 11f, bold = done != null && count >= total).apply {
                            setTextColor(when {
                                done == null -> FADED
                                count >= total -> GO_GREEN
                                count > 0 -> PARTLY_ORANGE
                                else -> DARK_BROWN
                            })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                }, full(2))
                setOnClickListener {
                    openDiary = diary.region; openQuest = null; expanded.clear(); showDetails = false
                    // open at the first tier you haven't finished
                    openTier = diary.tiers.indexOfFirst { t ->
                        val done = data?.diaryTasks(diary.region, t.tier)
                        done == null || done.count { it } < t.tasks.size
                    }.coerceAtLeast(0)
                    show(keepScroll = false)
                }
            }, full(4))
        }
        column.addView(label("E = Easy, M = Medium, H = Hard, El = Elite", 10f).apply { setTextColor(FADED) }, full(8))
    }

    private fun diaryScreen(region: String): View = page {
        val diary = QuestBook.diaries.firstOrNull { it.region == region } ?: return@page
        val data = sync.cached
        addView(titleRow("$region Diary", "Wiki page") { openWiki(QuestBook.wikiUrl("${region}_Diary")) })
        addView(accountCard(), full(6))

        // Tier buttons
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            diary.tiers.forEachIndexed { i, tier ->
                val done = data?.diaryTasks(region, tier.tier)
                val finished = done != null && done.count { it } >= tier.tasks.size
                addView(tabButton(tier.tier + if (finished) " ✓" else "", i == openTier) {
                    openTier = i; expanded.clear(); showDetails = false; show(keepScroll = false)
                }.apply { textSize = 11f },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (i > 0) leftMargin = dp(2); if (i < diary.tiers.size - 1) rightMargin = dp(2)
                    })
            }
        }, full(8))

        val tier = diary.tiers[openTier.coerceIn(0, diary.tiers.size - 1)]
        val done = data?.diaryTasks(region, tier.tier)
        val count = done?.count { it } ?: 0
        addView(label(when {
            done == null -> "${tier.tasks.size} tasks"
            count >= tier.tasks.size -> "${tier.tier} diary complete ✓"
            else -> "$count of ${tier.tasks.size} tasks done"
        }, 13f, bold = true).apply {
            setTextColor(if (done != null && count >= tier.tasks.size) GO_GREEN else DARK_BROWN)
        }, full(8))

        // Everything the tier needs, and its rewards
        addView(smallButton(if (showDetails) "Hide requirements ▴" else "All requirements & rewards ▾") {
            showDetails = !showDetails
            show()
        }, full(6))
        if (showDetails) {
            addView(card(ROW_BROWN) {
                addView(label("To do every ${tier.tier} task you need", 12f, bold = true))
                if (tier.requirements.isEmpty()) addView(label("Nothing special", 12f), full(2))
                reqRows(this, tier.requirements, data, 0, "$region/${tier.tier}")
                if (tier.rewards.isNotEmpty()) {
                    addView(label("Rewards", 12f, bold = true), full(10))
                    tier.rewards.forEach { addView(label("• $it", 12f), full(1)) }
                }
            }, full(4))
        }

        if (done != null) {
            addView(smallButton(if (hideDoneTasks) "☑ Hide finished tasks" else "☐ Hide finished tasks") {
                hideDoneTasks = !hideDoneTasks
                show()
            }, full(6))
        }

        tier.tasks.forEachIndexed { i, task ->
            val isDone = done?.getOrNull(i) == true
            if (isDone && hideDoneTasks) return@forEachIndexed
            addView(card(if (isDone) ROW_BROWN else CURRENT) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(label(if (isDone) "✓" else "${i + 1}.", 13f, bold = true).apply {
                        setTextColor(if (isDone) GO_GREEN else DARK_BROWN)
                        minWidth = dp(24)
                    })
                    addView(label(task.name, 13f, bold = true).apply { if (isDone) setTextColor(FADED) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                if (!isDone) {
                    task.steps.forEach { addView(label(it, 12f), full(3)) }
                    if (task.requirements.isNotEmpty()) {
                        addView(label("Needs:", 11f, bold = true), full(6))
                        reqRows(this, task.requirements, data, 0, "$region/${tier.tier}/$i")
                    }
                    if (task.items.isNotEmpty()) {
                        addView(label("Bring: " + task.items.joinToString(", "), 11f), full(6))
                    }
                }
            }, full(5))
        }
    }

    // ---------------- Requirements, with the quests they need in turn ----------------

    // Adds a ✓/✗ row for each requirement. Quests you haven't finished show ▸: tap to see
    // their own requirements underneath, and so on down the chain.
    private fun reqRows(parent: LinearLayout, reqs: List<Req>, data: WikiSync.Data?, depth: Int, path: String) {
        for (req in reqs) {
            val c = QuestBook.check(req, data)
            val (mark, color) = when (c.met) {
                Met.YES -> "✓" to GO_GREEN
                Met.NO -> "✗" to MISSING_RED
                Met.PARTLY -> "✗" to PARTLY_ORANGE
                Met.UNKNOWN -> "•" to DARK_BROWN
            }
            val key = "$path>${(req as? Req.QuestDone)?.quest}"
            val sub = (req as? Req.QuestDone)?.let { QuestBook.quest(it.quest) }?.requirements.orEmpty()
            val canOpen = req is Req.QuestDone && c.met != Met.YES && sub.isNotEmpty() && depth < MAX_DEPTH &&
                !path.split(">").contains(req.quest)
            val isOpen = canOpen && key in expanded
            parent.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12) * depth, dp(1), 0, dp(1))
                addView(label(if (canOpen) (if (isOpen) "▾" else "▸") else "", 12f, bold = true).apply { minWidth = dp(12) })
                addView(label("$mark ${c.text}", 12f, bold = c.met == Met.NO).apply { setTextColor(color) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (canOpen) setOnClickListener {
                    if (isOpen) expanded.remove(key) else expanded.add(key)
                    show()
                }
            }, full(1))
            if (isOpen) reqRows(parent, sub, data, depth + 1, "$path>${(req as Req.QuestDone).quest}")
        }
    }

    // A one-line verdict: can you do this quest?
    private fun summary(name: String, requirements: List<Req>): View {
        val data = sync.cached
        val state = data?.questState(name)
        val (text, color) = when {
            data == null -> (if (sync.username.isEmpty()) "Enable WikiSync to check your requirements."
                             else "No WikiSync data yet.") to DARK_BROWN
            state == WikiSync.FINISHED -> "You've already finished this quest ✓" to GO_GREEN
            else -> {
                val missing = QuestBook.missing(requirements, data).map { QuestBook.check(it, data).text.substringBefore(" (") }
                if (missing.isEmpty()) "You meet every requirement ✓" +
                    (if (state == WikiSync.IN_PROGRESS) " (quest started)" else "") to GO_GREEN
                else "Missing ${missing.size}: ${missing.joinToString(", ")}" to MISSING_RED
            }
        }
        return label(text, 13f, bold = true).apply { setTextColor(color) }
    }

    // ---------------- Your account (WikiSync) ----------------

    // The WikiSync section: a "WikiSync ▾" button that opens and closes it. It starts closed.
    private fun accountCard(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val open = syncOpen
        val data = sync.cached
        val header = if (sync.username.isEmpty()) "WikiSync" else "WikiSync: ${sync.username}"
        addView(smallButton(header + if (open) "  ▴" else "  ▾") {
            syncOpen = !open
            editingName = false
            show()
        }.apply { textSize = 12f; setPadding(dp(10), dp(9), dp(10), dp(9)) }, full())
        if (!open) {
            syncMessage?.let { addView(label(it, 11f).apply { setTextColor(PARTLY_ORANGE) }, full(2)) }
            return@apply
        }

        addView(card(ROW_BROWN) {
            addView(label("Your quests, levels and diaries are read from WikiSync, the OSRS Wiki's service. " +
                "This app can only read your WikiSync data: it can't update it. To update it, log in " +
                "to RuneLite on a PC with the WikiSync plugin turned on, then tap Refresh here.", 11f))

            if (sync.username.isEmpty() || editingName) {
                addView(label("Your RuneScape name", 12f, bold = true), full(8))
                val field = EditText(context).apply {
                    setText(sync.username)
                    hint = "e.g. Zezima"
                    textSize = 14f
                    setSingleLine()
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                val save = {
                    val name = field.text.toString().trim()
                    if (name.isNotEmpty()) {
                        sync.username = name
                        editingName = false
                        syncOpen = false
                        refresh()
                    }
                }
                field.setOnEditorActionListener { _, _, _ -> save(); true }
                addView(field, full(2))
                addView(smallButton("Save") { save() }, full(4))
            } else {
                addView(label("Name: ${sync.username}\nLast read from WikiSync: " +
                    (data?.let { ago(it.fetchedAt) } ?: "not yet"), 12f), full(8))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(smallButton("↻ Refresh") { refresh() },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
                    addView(smallButton("✎ Change name") { editingName = true; show() },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
                }, full(6))
            }
            syncMessage?.let { addView(label(it, 11f).apply { setTextColor(PARTLY_ORANGE) }, full(6)) }
        }, full(4))
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }

    // ---------------- Small building blocks ----------------

    private fun titleRow(title: String, buttonText: String, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 16f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton(buttonText, onClick))
    }

    private fun page(fill: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            fill()
        })
    }

    private fun card(color: Int, fill: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))
        background = GradientDrawable().apply {
            setColor(color)
            setStroke(dp(1), BUTTON_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        fill()
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun smallButton(text: String, onClick: () -> Unit) = button(text, onClick = onClick).apply {
        textSize = 11f
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    // A tab: darker when it's the one showing
    private fun tabButton(text: String, selected: Boolean, onClick: () -> Unit) =
        button(text, if (selected) SELECTED_BROWN else BUTTON_BROWN, onClick).apply {
            setPadding(dp(4), dp(7), dp(4), dp(7))
            if (selected) setTypeface(typeface, Typeface.BOLD)
        }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
