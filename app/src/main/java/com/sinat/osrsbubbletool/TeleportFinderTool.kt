package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

// Teleport Finder: type an NPC, monster or place, and see the teleports that land closest to it.
// Where things are comes from the OSRS Wiki (looked up when you search); the teleports are built in.
class TeleportFinderTool(private val context: Context, private val openWikiSync: () -> Unit = {}) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val LIGHT_BUTTON = Color.parseColor("#B89A63")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val PAPER = Color.parseColor("#FFF8E6")
        private val FADED = Color.parseColor("#8C7B5E")
        private val WARN = Color.parseColor("#9C4A10")
        private const val USER_AGENT = "OSRSBubbleTool/1.0 (personal Android app)"
        private const val API = "https://oldschool.runescape.wiki/api.php"
        private const val RESULTS = 25   // results in the list (it scrolls)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("teleport_finder", Context.MODE_PRIVATE)
    private val sync = WikiSync(context)   // your quests and levels, if you've set up WikiSync (already saved; no download)
    private val teleports: List<TeleportData.Teleport> by lazy {
        try { TeleportData.parse(context.assets.open("teleports.tsv").bufferedReader().use { it.readText() }) } catch (e: Exception) { emptyList() }
    }

    private val links: List<TeleportData.Link> by lazy {   // ladders, cave entrances and so on
        try { TeleportData.parseLinks(context.assets.open("entrances.tsv").bufferedReader().use { it.readText() }) } catch (e: Exception) { emptyList() }
    }
    // Real walking routes (walls, doors, ladders), loaded the first time they're needed
    private var walkMap: WalkMap? = null
    // Pages already read this session, with only the spots that have teleport results (only used on
    // the background thread). Picking a suggestion reuses it, so the page isn't downloaded twice.
    private val checked = HashMap<String, List<TeleportData.Spot>>()
    private var working: String? = null                      // the results being worked out right now
    private var lastKey: String? = null                      // the last results worked out, so redraws don't redo it
    private var lastList: List<TeleportData.Result> = emptyList()
    private var lastNeeds: List<String> = emptyList()           // what the ways in need, if you can't get there yet

    private lateinit var holder: FrameLayout
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView   // the results scroll on their own, under the search box
    private lateinit var field: EditText
    private lateinit var badgeSlot: FrameLayout   // the ✓/✗ WikiSync tag, redrawn when the window opens

    // What's on screen
    private var query = ""
    private var suggestions: List<String> = emptyList()
    private var page: String? = null                       // the wiki page picked
    private var spots: List<TeleportData.Spot> = emptyList()
    private var spot = 0                                    // which of its spots
    private var message: String? = null
    private var searchId = 0

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        // The title and search box stay put at the top; the suggestions and results scroll underneath
        holder.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(4))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label("Teleport Finder (Beta)", 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    badgeSlot = FrameLayout(context)
                    addView(badgeSlot)
                }, full())
                addView(label("Type an NPC, monster or place.", 11f).apply { setTextColor(FADED) }, full(1))
                field = EditText(context).apply {
                    hint = "e.g. Zaff, Barrows, abyssal demon"
                    textSize = 14f
                    setSingleLine()
                    imeOptions = EditorInfo.IME_ACTION_SEARCH
                    setTextColor(DARK_BROWN)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(6).toFloat() }
                    setText(query)
                    setOnEditorActionListener { _, _, _ ->
                        suggestions.firstOrNull()?.let { pick(it) } ?: searchNow(text.toString())
                        true
                    }
                    addTextChangedListener(object : TextWatcher {
                        private val lookUp = Runnable { suggest(text.toString()) }
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            val q = s?.toString() ?: ""
                            if (q == query) return
                            query = q
                            // typing again: back to suggestions
                            if (page != null) { page = null; spots = emptyList(); message = null; refresh() }
                            removeCallbacks(lookUp)
                            postDelayed(lookUp, 300)   // wait for a pause in typing
                        }
                    })
                }
                addView(field, full(6))
            }, full())
            resultsScroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false   // always show the scrollbar, so it's clear there's more
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), 0, dp(8), dp(12))
                    results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    addView(results, full(2))
                    addView(label("Places from the OSRS Wiki. Teleports, the walking map and dungeon entrances from the Shortest Path plugin and RuneLite, and obstacles from the Golems Don't Die plugin. " +
                        "Distances are walking routes, counting walls, doors, ladders and cave entrances. With WikiSync, " +
                        "shortcuts you can't use yet are left out.", 9f).apply { setTextColor(FADED) }, full(12))
                })
            }
            addView(resultsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        refresh()
        // back from the WikiSync tool: update the tag, and which teleports are faded
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { showBadge(); refresh() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        showBadge()
        return holder
    }

    private fun showBadge() {
        badgeSlot.removeAllViews()
        badgeSlot.addView(WikiSyncBadge.view(context, sync) { openWikiSync() })
    }

    // The window's ◀ button: from results back to the search
    fun goBack() {
        if (page != null) { page = null; spots = emptyList(); message = null; refresh(); toTop() }
    }

    private fun toTop() {
        if (::resultsScroll.isInitialized) resultsScroll.post { resultsScroll.scrollTo(0, 0) }
    }

    // ---------------- Searching the wiki ----------------

    private fun suggest(q: String) {
        val text = q.trim()
        val id = ++searchId
        if (text.length < 2) { suggestions = emptyList(); refresh(); return }
        work.execute {
            val list = try {
                // Only pages with a map or a spawn list on them, since those are the ones the finder can
                // place (this leaves out guides, money making pages, items and so on). One request: the
                // wiki's own search suggestions, each with whether it uses the Map or LocLine template.
                val json = JSONObject(get("$API?action=query&format=json&formatversion=2&redirects=1" +
                    "&generator=prefixsearch&gpsnamespace=0&gpslimit=30&gpssearch=" + URLEncoder.encode(text, "UTF-8") +
                    "&prop=templates&tltemplates=" + URLEncoder.encode("Template:Map|Template:LocLine", "UTF-8") + "&tllimit=max"))
                val pages = json.optJSONObject("query")?.optJSONArray("pages")
                val found = ArrayList<Pair<Int, String>>()
                if (pages != null) for (i in 0 until pages.length()) {
                    val pg = pages.getJSONObject(i)
                    val onMap = (pg.optJSONArray("templates")?.length() ?: 0) > 0
                    if (onMap) found.add(pg.optInt("index", 999) to pg.getString("title"))
                }
                // Then read those pages (all in one request, only ones not read before) and keep the ones
                // with somewhere the finder has teleports for, so every suggestion gives results
                val candidates = TeleportData.usefulSuggestions(found.sortedBy { it.first }.map { it.second }, 14)
                val need = candidates.filter { it !in checked }
                if (need.isNotEmpty()) {
                    val cj = JSONObject(get("$API?action=query&format=json&formatversion=2&prop=revisions&rvprop=content" +
                        "&rvslots=main&titles=" + URLEncoder.encode(need.joinToString("|"), "UTF-8")))
                    val got = cj.optJSONObject("query")?.optJSONArray("pages")
                    if (got != null) for (i in 0 until got.length()) {
                        val pg = got.getJSONObject(i)
                        val content = pg.optJSONArray("revisions")?.optJSONObject(0)?.optJSONObject("slots")
                            ?.optJSONObject("main")?.optString("content") ?: continue
                        val t = pg.getString("title")
                        keep(t, TeleportData.spotsFrom(t, content))
                    }
                }
                candidates.filter { checked[it]?.isNotEmpty() == true }.take(8)
            } catch (e: Exception) { null }
            handler.post {
                if (id != searchId) return@post
                if (list == null) { message = "Couldn't reach the OSRS Wiki. Check your internet connection."; suggestions = emptyList() }
                else { message = null; suggestions = list }
                if (page == null) refresh()
            }
        }
    }

    private fun searchNow(q: String) {
        if (q.isBlank()) return
        pick(q.trim())
    }

    // Looks up a wiki page and reads where it is on the map
    private fun pick(title: String) {
        hideKeyboard()
        page = title
        spots = emptyList()
        spot = 0
        message = "Finding $title on the wiki map…"
        refresh()
        toTop()
        val id = ++searchId
        work.execute {
            var found: List<TeleportData.Spot>? = checked[title]   // already read for the suggestions
            var onPage = found?.isNotEmpty() ?: false
            var realTitle = title
            if (found == null) try {
                val json = JSONObject(get("$API?action=parse&format=json&prop=wikitext&redirects=1&page=" + URLEncoder.encode(title, "UTF-8")))
                val parse = json.optJSONObject("parse")
                if (parse != null) {
                    realTitle = parse.optString("title", title)
                    val all = TeleportData.spotsFrom(realTitle, parse.getJSONObject("wikitext").getString("*"))
                    onPage = all.isNotEmpty()
                    found = keep(realTitle, all)
                } else found = emptyList()
            } catch (e: Exception) { }
            handler.post {
                if (id != searchId) return@post
                page = realTitle
                when {
                    found == null -> message = "Couldn't reach the OSRS Wiki. Check your internet connection."
                    found.isEmpty() && onPage -> message = "No teleport lands near $realTitle. " +
                        "Search for the way in instead (for example its entrance or the nearest town)."
                    found.isEmpty() -> message = "The wiki page for $realTitle doesn't show where it is on the map. " +
                        "Try the NPC, monster or place itself (not an item or quest)."
                    else -> {
                        message = null
                        spots = found
                        remember(realTitle)
                    }
                }
                refresh()
            }
        }
    }

    // The walking map, loaded the first time it's needed (background thread only)
    private fun map(): WalkMap = walkMap ?: WalkMap(
        context.assets.open("collision.bin").use { it.readBytes() },
        context.assets.open("walks.txt").bufferedReader().use { it.readText() },
        context.assets.open("dungeons.txt").bufferedReader().use { it.readText() }).also { walkMap = it }

    // Keeps only the spots the finder can give teleports for, and remembers them for this session
    private fun keep(title: String, spots: List<TeleportData.Spot>): List<TeleportData.Spot> {
        val map = try { map() } catch (e: Throwable) { null }
        val good = spots.filter { TeleportData.hasResults(it, teleports, links, map) }
        if (checked.size > 300) checked.clear()   // plenty for a session; don't grow forever
        checked[title] = good
        return good
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    // Recent searches, newest first
    private fun recent(): List<String> = (prefs.getString("recent", "") ?: "").split('\n').filter { it.isNotBlank() }
    private fun remember(title: String) {
        val list = (listOf(title) + recent().filter { it != title }).take(6)
        prefs.edit().putString("recent", list.joinToString("\n")).apply()
    }

    // ---------------- Showing results ----------------

    private fun refresh() {
        if (!::results.isInitialized) return
        results.removeAllViews()
        message?.let { results.addView(label(it, 12f).apply { setTextColor(FADED) }, full(4)) }

        val p = page
        if (p == null) {
            // still typing: suggestions, or recent searches
            val list = if (query.trim().length >= 2) suggestions else recent()
            if (list.isNotEmpty() && query.trim().length < 2) results.addView(label("Recent", 11f, bold = true).apply { setTextColor(FADED) }, full(4))
            for (t in list) results.addView(row(t, null) { pick(t) }, full(3))
            return
        }
        if (spots.isEmpty()) return

        // More than one spot (versions of an NPC, or areas a monster lives): pick one
        if (spots.size > 1) {
            results.addView(label("Where?", 11f, bold = true).apply { setTextColor(FADED) }, full(4))
            val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            for ((i, s) in spots.withIndex()) {
                wrap.addView(TextView(context).apply {
                    text = if (s.levels.isEmpty()) s.label else "${s.label}  ·  ${s.levels}"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, if (i == spot) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply { setColor(if (i == spot) BUTTON_BROWN else LIGHT_BUTTON); cornerRadius = dp(6).toFloat() }
                    setOnClickListener { spot = i; refresh(); toTop() }
                }, full(3))
            }
            results.addView(wrap, full(2))
        }

        val s = spots[spot.coerceIn(0, spots.size - 1)]
        results.addView(label(s.label, 14f, bold = true), full(10))
        if (s.levels.isNotEmpty()) results.addView(label(s.levels, 11f).apply { setTextColor(FADED) }, full(1))
        val layer = TeleportData.layerOf(s.x, s.y)
        results.addView(label(when (layer) {
            TeleportData.Layer.UNDERGROUND -> "Underground: distances include the walk to the way down."
            TeleportData.Layer.SEPARATE -> "In a separate area (like Zanaris): distances include the walk to the way in."
            else -> "Closest teleports first."
        }, 10f).apply { setTextColor(FADED) }, full(2))

        val data = sync.cached
        fun missing(t: TeleportData.Teleport) = if (data == null) emptyList() else TeleportData.unmet(t,
            levels = { data.level(it) },
            questDone = { q -> data.questState(q)?.let { it == WikiSync.FINISHED } })
        val key = "${s.x},${s.y},${s.plane}|${sync.username}|${data?.fetchedAt}"
        if (key != lastKey) {
            // Working out the walking routes takes a moment, so it's done in the background
            results.addView(label("Working out the walking routes…", 12f).apply { setTextColor(FADED) }, full(8))
            if (working != key) {
                working = key
                val title = page.orEmpty()   // read here: the page can change while this works in the background
                work.execute {
                    var needs = emptyList<String>()
                    val list = try {
                        val map = map()
                        // what the area is called, to find the way in if the walking map has none (the page's own
                        // name is left out: a monster's name says little about where its cave starts)
                        val hint = s.area.ifBlank { if (title.isEmpty() || !s.label.startsWith(title)) s.label else "" }
                        // stop once enough teleports you can use are reached (ones you can't use yet don't count)
                        val usable = TeleportData.landingTiles(teleports.filter { missing(it).isEmpty() })
                        // shortcuts, doors and boats your WikiSync levels and quests don't allow yet are left out
                        val locked = if (data == null) null else BooleanArray(map.requirements.size) { i ->
                            TeleportData.unmet(map.requirements[i], levels = { data.level(it) },
                                questDone = { q -> data.questState(q)?.let { it == WikiSync.FINISHED } }).isNotEmpty()
                        }
                        val field = map.from(s.x, s.y, s.plane, usable, 70, hint = hint, locked = locked)
                        needs = field.needs
                        TeleportData.closestOnFoot(teleports, map, field, RESULTS) { missing(it).isNotEmpty() }
                            // somewhere the walking map doesn't cover at all: straight-line guesses
                            .ifEmpty { TeleportData.closest(teleports, s, RESULTS, links) { missing(it).isNotEmpty() } }
                    } catch (e: Throwable) {   // including running out of memory: fall back to straight-line guesses
                        try { TeleportData.closest(teleports, s, RESULTS, links) { missing(it).isNotEmpty() } } catch (e2: Throwable) { emptyList() }
                    }
                    handler.post {
                        if (working != key) return@post
                        working = null; lastKey = key; lastList = list; lastNeeds = needs
                        refresh()
                    }
                }
            }
            return
        }
        val list = lastList
        if (lastNeeds.isNotEmpty()) {
            // closed off for you: the ways in need levels or quests you don't have yet (from WikiSync)
            results.addView(label("You can't walk here yet. The way in needs " +
                (if (lastNeeds.size <= 4) lastNeeds.joinToString(", ") else lastNeeds.take(4).joinToString(", ") + " or more") + ".", 12f).apply {
                setTextColor(WARN)
            }, full(8))
        }
        if (list.isEmpty()) {
            if (lastNeeds.isNotEmpty()) return
            results.addView(label("No teleport lands near here. Search for the way in instead (for example its entrance or the nearest town).", 12f),
                full(8))
            return
        }
        for (r in list) {
            val miss = missing(r.teleport)
            results.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(6).toFloat() }
                if (miss.isNotEmpty()) alpha = 0.55f
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(r.teleport.name, 13f, bold = true))
                    val how = listOf(r.teleport.category, r.teleport.how.takeIf { it.isNotEmpty() }).filterNotNull().joinToString(" · ")
                    addView(label(how, 10f).apply { setTextColor(FADED) })
                    // other teleports landing in the same place
                    if (r.also.isNotEmpty()) {
                        val names = r.also.take(3).joinToString(", ") { it.name } + if (r.also.size > 3) " and ${r.also.size - 3} more" else ""
                        addView(label("Also: $names", 10f).apply { setTextColor(FADED) })
                    }
                    // the way in or out the route goes through, e.g. "Then: Climb-down Trapdoor"
                    r.via?.let { addView(label("Then: $it", 10f).apply { setTextColor(DARK_BROWN) }) }
                    if (r.guess) addView(label("The way in isn't on the walking map, so this distance is a guess.", 9f).apply { setTextColor(WARN) })
                    when {
                        miss.isNotEmpty() -> addView(label("You still need: " + miss.joinToString(", "), 10f, bold = true).apply { setTextColor(WARN) })
                        r.teleport.requirements.isNotBlank() -> addView(label("Needs: " + r.teleport.requirements, 10f).apply { setTextColor(FADED) })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.END
                    addView(label(TeleportData.describe(r.tiles).substringBefore(" · "), 11f, bold = true).apply { gravity = Gravity.END })
                    if (r.tiles > 6) addView(label(TeleportData.describe(r.tiles).substringAfter(" · "), 9f).apply { setTextColor(FADED); gravity = Gravity.END })
                    if (r.viaEntrance && r.via == null && !r.guess) addView(label("via entrance", 9f).apply { setTextColor(WARN); gravity = Gravity.END })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            }, full(4))
        }
        if (data != null) results.addView(label("Faded: teleports your WikiSync quests or levels don't allow yet. Items you own aren't checked.", 9f)
            .apply { setTextColor(FADED) }, full(6))
    }

    private fun row(text: String, sub: String?, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(6).toFloat() }
        addView(label(text, 13f, bold = true))
        sub?.let { addView(label(it, 10f).apply { setTextColor(FADED) }) }
        setOnClickListener { onClick() }
    }

    private fun hideKeyboard() {
        if (!::field.isInitialized) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(field.windowToken, 0)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        work.shutdownNow()
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
