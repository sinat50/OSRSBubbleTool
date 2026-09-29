package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.random.Random

// The Game Room: small games to play while you wait in the game.
//   Higher or Lower: is the next item worth more or less on the Grand Exchange?
//   Loot Simulator: kill a boss as many times as you like and see what you'd have got.
// Prices and drop tables come from the OSRS Wiki and are saved on the phone, so they're only fetched now and then.
class GameRoomTool(private val context: Context) {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val PAPER = Color.parseColor("#FFF8E6")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private val STOP_RED = Color.parseColor("#B03A2E")
        private val FADED = Color.parseColor("#8C7B5E")
        private val RARE_PURPLE = Color.parseColor("#7B2FBE")

        private const val MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping"
        private const val DAY_URL = "https://prices.runescape.wiki/api/v1/osrs/24h"
        private const val USER_AGENT = "OSRSBubbleTool/1.0 (personal Android app)"

        private const val HOUR = 3600_000L
        private const val MAPPING_MAX_AGE = 7 * 24 * HOUR   // item names and icons rarely change
        private const val PRICES_MAX_AGE = 6 * HOUR          // prices: a day's average, refreshed every few hours
        private const val DROPS_MAX_AGE = 30 * 24 * HOUR     // drop tables hardly ever change

        private const val MAX_PET_HUNT = 100_000             // "Kill until pet" gives up after this many kills
        private const val RARE_CHANCE = 1 / 200.0            // rarer than this per kill…
        private const val RARE_VALUE = 100_000L              // …and worth this much (or untradeable) = a rare drop
    }

    private val HELP_HIGHER_LOWER = "An item and its Grand Exchange price are shown. Guess whether the next item is worth more " +
        "(Higher) or less (Lower).\n\nEach right guess adds one to your streak, and one wrong guess ends it. It gets harder as your " +
        "streak grows: the two prices get closer together. Equal prices count as right.\n\nPrices are the average over the last " +
        "24 hours, from the OSRS Wiki."
    private val HELP_LOOT = "Pick a boss or a clue casket, then tap Kill, ×10, ×100 or ×1K to simulate kills. Every drop comes up at the OSRS Wiki's " +
        "drop rate.\n\nRare drops (rarer than 1 in 200 kills and worth over 100K, or untradeable like pets) show in purple with " +
        "the kill they came on. Kill until pet keeps going until the boss's pet drops. Reset starts that boss over.\n\n" +
        "Clue scroll caskets work the same way: tap Open. Each casket gives several rolls on its table, like in the game.\n\n" +
        "Loot values are Grand Exchange averages over the last 24 hours."
    private val HELP_2048 = "Swipe up, down, left or right to slide all the tiles. When two tiles with the same number touch, " +
        "they join into one: 2 and 2 make 4, 4 and 4 make 8, and so on. A new tile appears after every swipe.\n\n" +
        "Try to make a 2048 tile. The game ends when the board is full and nothing can join.\n\n" +
        "Your game is saved after every move, so you can close the window any time."

    private enum class Screen { MENU, HIGHER_LOWER, LOOT_LIST, LOOT_BOSS, G2048 }

    private val handler = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()   // downloads and simulations
    private val iconWork = Executors.newFixedThreadPool(2)   // item pictures
    private val prefs = context.getSharedPreferences("game_room", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "game_room").apply { mkdirs() }
    private val random = Random.Default

    private var screen = Screen.MENU
    private lateinit var holder: FrameLayout
    private var scrollY = 0   // keeps the Loot Simulator from jumping to the top after each kill

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The window's ◀ button
    fun goBack() {
        screen = when (screen) {
            Screen.LOOT_BOSS -> Screen.LOOT_LIST
            else -> Screen.MENU
        }
        openHelp = null
        scrollY = 0
        lastShown = null
        show()
    }

    private fun open(s: Screen) {
        screen = s
        openHelp = null
        lastShown = null
        scrollY = 0
        show()
    }

    private var lastShown: Screen? = null

    private fun show() {
        if (!::holder.isInitialized) return
        if (screen == lastShown) rememberScroll()   // redrawing the same screen: stay where you were
        lastShown = screen
        holder.removeAllViews()
        holder.addView(when (screen) {
            Screen.MENU -> menu()
            Screen.HIGHER_LOWER -> higherLower()
            Screen.LOOT_LIST -> bossList()
            Screen.LOOT_BOSS -> bossScreen()
            Screen.G2048 -> game2048()
        })
        openHelp?.let { (t, h) -> showHelp(t, h) }
    }

    // ---------------- The menu ----------------

    private fun menu(): View = scrolling {
        addView(label("Game Room", 16f, bold = true), full())
        addView(label("Something to play while you wait.", 11f).apply { setTextColor(FADED) }, full(1))
        // the games, two to a row
        val games = listOf(
            Triple(GameIcon.HIGHER_LOWER, "Higher or Lower", "Which item is worth more?") to { ensurePrices(); open(Screen.HIGHER_LOWER) },
            Triple(GameIcon.LOOT, "Loot Simulator", "Kill a boss, see what you get") to { ensurePrices(); open(Screen.LOOT_LIST) },
            Triple(GameIcon.G2048, "2048", "Join the tiles, reach 2048") to { open(Screen.G2048) },
        )
        for (row in games.chunked(2)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, (info, action) ->
                    addView(tile(info.first, info.second, info.third, action),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
                }
                if (row.size == 1) addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
            }, full(8))
        }
    }

    private fun tile(icon: Int, name: String, about: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(8).toFloat() }
        addView(GameIcon(context, icon), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(name, 12f, bold = true).apply { gravity = Gravity.CENTER }, full(6))
        addView(label(about, 10f).apply { gravity = Gravity.CENTER; setTextColor(FADED) }, full(2))
        setOnClickListener { onClick() }
    }

    // ---------------- Prices (shared by both games) ----------------

    private var prices: PriceBook? = null
    private var pricesAt = 0L
    private var pricesLoading = false
    private var pricesError: String? = null

    // Gets the prices ready (from the phone if they're recent, otherwise from the wiki)
    private fun ensurePrices() {
        if (pricesLoading) return
        if (prices != null && System.currentTimeMillis() - pricesAt < PRICES_MAX_AGE) return
        pricesLoading = true
        pricesError = null
        work.execute {
            val book = try {
                PriceBook(cached("mapping.json", MAPPING_URL, MAPPING_MAX_AGE), cached("prices_24h.json", DAY_URL, PRICES_MAX_AGE))
            } catch (e: Exception) { null }
            handler.post {
                pricesLoading = false
                if (book != null) {
                    prices = book
                    pricesAt = System.currentTimeMillis()
                    runs.values.forEach { it.rare = null }   // worked out again with the new prices
                } else if (prices == null) {
                    pricesError = "Couldn't get Grand Exchange prices. Check your internet connection."
                }
                show()
            }
        }
    }

    // A saved copy if it's recent enough, otherwise a fresh download (or the old copy if there's no internet)
    private fun cached(name: String, url: String, maxAge: Long): String {
        val file = File(dir, name)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < maxAge) return file.readText()
        val text = download(url)
        if (text != null) {
            val tmp = File(dir, "$name.part")
            tmp.writeText(text)
            tmp.renameTo(file)
            return text
        }
        if (file.exists()) return file.readText()
        throw java.io.IOException("no connection")
    }

    private fun download(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    // While prices load (or if they can't), shows a message instead of the game
    private fun waitingForPrices(): View? {
        if (prices != null) return null
        return scrolling {
            if (pricesError != null) {
                addView(label(pricesError!!, 12f), full())
                addView(button("Try again") { ensurePrices(); show() }, full(8))
            } else {
                addView(label("Getting Grand Exchange prices from the OSRS Wiki…", 12f).apply { setTextColor(FADED) }, full())
            }
        }
    }

    // ---------------- Higher or Lower ----------------

    private var hlPool: List<PriceBook.Item> = emptyList()
    private var hlCurrent: PriceBook.Item? = null
    private var hlNext: PriceBook.Item? = null
    private var hlStreak = 0
    private var hlRevealed = false        // the second price is showing
    private var hlLost = false
    private var hlWasRight = false
    private var hlBestBefore = 0          // the best streak when this game started

    private fun startHigherLower() {
        val book = prices ?: return
        hlPool = book.gameItems()
        if (hlPool.size < 2) return
        val first = hlPool[random.nextInt(hlPool.size)]
        hlCurrent = first
        hlNext = HigherLower.nextItem(hlPool, first, 0, random)
        hlStreak = 0
        hlRevealed = false
        hlLost = false
        hlBestBefore = prefs.getInt("hl_best", 0)
    }

    private fun higherLower(): View {
        waitingForPrices()?.let { return it }
        if (hlCurrent == null || hlPool.isEmpty()) startHigherLower()
        val current = hlCurrent ?: return scrolling { addView(label("No prices to play with right now.", 12f), full()) }
        val next = hlNext ?: return scrolling { }
        val best = prefs.getInt("hl_best", 0)

        return scrolling {
            addView(titleRow("Higher or Lower", HELP_HIGHER_LOWER, right = label("Best $best", 12f).apply { setTextColor(FADED) }), full())
            addView(label("Streak $hlStreak", 13f, bold = true), full(4))

            addView(itemCard(current, current.price, null), full(6))
            addView(label(when {
                !hlRevealed -> "Is this worth more or less?"
                hlWasRight -> "✓ Right!"
                else -> "✗ Wrong!"
            }, 12f, bold = hlRevealed).apply {
                gravity = Gravity.CENTER
                setTextColor(if (!hlRevealed) FADED else if (hlWasRight) GO_GREEN else STOP_RED)
            }, full(6))
            addView(itemCard(next, if (hlRevealed) next.price else null, if (!hlRevealed) null else if (hlWasRight) GO_GREEN else STOP_RED), full(6))

            if (hlLost) {
                addView(label(if (hlStreak > hlBestBefore) "New best streak: $hlStreak!" else "Your streak: $hlStreak", 13f, bold = true)
                    .apply { gravity = Gravity.CENTER }, full(10))
                addView(button("Play again", GO_GREEN) { startHigherLower(); show() }, full(6))
            } else {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(button("▲ Higher", GO_GREEN) { guess(higher = true) }.apply { isEnabled = !hlRevealed; alpha = if (hlRevealed) 0.5f else 1f },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(3) })
                    addView(button("▼ Lower", STOP_RED) { guess(higher = false) }.apply { isEnabled = !hlRevealed; alpha = if (hlRevealed) 0.5f else 1f },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(3) })
                }, full(10))
            }
        }
    }

    private fun guess(higher: Boolean) {
        val current = hlCurrent ?: return
        val next = hlNext ?: return
        if (hlRevealed) return
        hlWasRight = HigherLower.isRight(current, next, higher)
        hlRevealed = true
        if (hlWasRight) {
            hlStreak++
            if (hlStreak > prefs.getInt("hl_best", 0)) prefs.edit().putInt("hl_best", hlStreak).apply()
            show()
            // a moment to see the price, then the next item
            handler.postDelayed({
                if (hlNext !== next) return@postDelayed
                hlCurrent = next
                hlNext = HigherLower.nextItem(hlPool, next, hlStreak, random)
                hlRevealed = false
                if (screen == Screen.HIGHER_LOWER) show()
            }, 1100)
        } else {
            hlLost = true
            show()
        }
    }

    // An item: its picture, name, and price (or "?")
    private fun itemCard(item: PriceBook.Item, price: Long?, priceColor: Int?): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(8).toFloat() }
        addView(itemIcon(WikiIcons.fromIcon(item.icon)), LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(item.name, 13f, bold = true).apply { maxLines = 2 })
            addView(label(if (price == null) "? gp" else Gp.full(price) + " gp", 13f, bold = price != null).apply {
                setTextColor(priceColor ?: if (price == null) FADED else DARK_BROWN)
            }, full(2))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
    }

    // ---------------- Loot Simulator ----------------

    // One boss's session: its drop table and everything you've "got" so far
    private class Run(val boss: LootBosses.Boss, val table: DropTable) {
        var kills = 0L
        val loot = HashMap<String, Long>()
        val rares = ArrayList<Pair<String, Long>>()   // item, kill number
        var message = ""
        var rare: Set<String>? = null                  // which items count as rare (worked out once)
        val pet: String? = boss.pet?.let { p -> table.all.firstOrNull { it.item.equals(p, ignoreCase = true) }?.item }
        val chance = HashMap<String, Double>()
        fun chanceOf(item: String) = chance.getOrPut(item) { table.chancePerKill(item) }
    }

    private val runs = HashMap<String, Run>()

    // Caskets are opened, bosses are killed
    private fun Run.one() = if (boss.isCasket) "casket" else "kill"
    private fun Run.many() = if (boss.isCasket) "caskets" else "kills"
    private fun Run.count(n: Long) = (if (boss.isCasket) "Opened " else "KC ") + Gp.full(n)
    private var boss: LootBosses.Boss? = null
    private var bossLoading = false
    private var bossError: String? = null
    private var killing = false

    private fun bossList(): View = scrolling {
        addView(titleRow("Loot Simulator", HELP_LOOT), full())
        addView(label("Pick a boss or a clue casket.", 11f).apply { setTextColor(FADED) }, full(1))
        for ((group, bosses) in LootBosses.GROUPS) {
            addView(label(group, 12f, bold = true), full(10))
            for (pair in bosses.chunked(2)) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    pair.forEachIndexed { i, b ->
                        addView(button(b.name, if ((runs[b.name]?.kills ?: 0) > 0) BUTTON_BROWN else Color.parseColor("#A0804E")) { openBoss(b) }
                            .apply { textSize = 11f; setPadding(dp(4), dp(7), dp(4), dp(7)) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) rightMargin = dp(3) else leftMargin = dp(3) })
                    }
                    if (pair.size == 1) addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(3) })
                }, full(5))
            }
        }
    }

    private fun openBoss(b: LootBosses.Boss) {
        boss = b
        bossError = null
        open(Screen.LOOT_BOSS)
        if (runs[b.name] == null) loadBoss(b)
    }

    private fun loadBoss(b: LootBosses.Boss) {
        if (bossLoading) return
        bossLoading = true
        show()
        work.execute {
            val table = try {
                val name = "drops_" + b.page.replace(Regex("[^A-Za-z0-9]"), "_") + ".json"
                DropTable(cached(name, LootBosses.dropsUrl(b.page), DROPS_MAX_AGE), b.casketRolls)
            } catch (e: Exception) { null }
            handler.post {
                bossLoading = false
                if (table == null || (table.all.isEmpty())) bossError = "Couldn't get ${b.name}'s drop table. Check your internet connection."
                else runs[b.name] = Run(b, table)
                show()
            }
        }
    }

    private fun bossScreen(): View {
        val b = boss ?: return bossList()
        val run = runs[b.name]
        if (run == null) return scrolling {
            addView(label(b.name, 15f, bold = true), full())
            if (bossError != null) {
                addView(label(bossError!!, 12f), full(6))
                addView(button("Try again") { loadBoss(b) }, full(8))
            } else addView(label("Getting the drop table from the OSRS Wiki…", 12f).apply { setTextColor(FADED) }, full(6))
        }
        val book = prices

        return scrolling {
            // Name and kill count
            addView(titleRow(b.name, HELP_LOOT, right = label(run.count(run.kills), 12f, bold = true)), full())

            // What it's all worth
            val total = run.loot.entries.sumOf { (item, n) -> (book?.priceOf(item) ?: 0) * n }
            addView(label(when {
                run.kills == 0L -> if (b.isCasket) "Tap Open to start." else "Tap Kill to start."
                book == null -> "Loot value: waiting for prices…"
                else -> "Loot value: ${Gp.short(total)} gp  (${Gp.short(total / run.kills)} per ${run.one()})"
            }, 12f).apply { setTextColor(if (run.kills == 0L) FADED else DARK_BROWN) }, full(2))

            // Kill buttons
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((text, n) in listOf((if (b.isCasket) "Open" else "Kill") to 1, "×10" to 10, "×100" to 100, "×1K" to 1000)) {
                    addView(button(text, GO_GREEN) { kill(run, n, untilPet = false) }.apply {
                        textSize = 12f; setPadding(dp(2), dp(8), dp(2), dp(8)); isEnabled = !killing; alpha = if (killing) 0.5f else 1f
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
                }
            }, full(8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                if (run.pet != null) addView(button(if (b.isCasket) "🐾 Open until pet" else "🐾 Kill until pet", RARE_PURPLE) { kill(run, MAX_PET_HUNT, untilPet = true) }.apply {
                    textSize = 12f; setPadding(dp(2), dp(7), dp(2), dp(7)); isEnabled = !killing; alpha = if (killing) 0.5f else 1f
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f).apply { setMargins(dp(2), 0, dp(2), 0) })
                addView(button("Reset", BUTTON_BROWN) {
                    runs[b.name] = Run(b, run.table); scrollY = 0; show()
                }.apply { textSize = 12f; setPadding(dp(2), dp(7), dp(2), dp(7)); isEnabled = !killing },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }, full(5))

            // What just happened
            if (killing) addView(label(if (b.isCasket) "Opening…" else "Killing…", 12f).apply { setTextColor(FADED) }, full(8))
            else if (run.message.isNotEmpty()) addView(label(run.message, 12f).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply { setColor(PAPER); cornerRadius = dp(6).toFloat() }
            }, full(8))

            // Rare drops, newest first
            if (run.rares.isNotEmpty()) {
                addView(label("Rare drops", 12f, bold = true), full(10))
                for ((item, kc) in run.rares.asReversed().take(15)) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(itemIcon(WikiIcons.forItem(item, book)), LinearLayout.LayoutParams(dp(22), dp(22)))
                        addView(label(item, 12f, bold = true).apply { setTextColor(RARE_PURPLE) },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
                        addView(label(run.count(kc), 11f).apply { setTextColor(FADED) })
                    }, full(3))
                }
                if (run.rares.size > 15) addView(label("…and ${run.rares.size - 15} more", 10f).apply { setTextColor(FADED) }, full(2))
            }

            // Everything, most valuable first
            if (run.loot.isNotEmpty()) {
                addView(label("All loot", 12f, bold = true), full(10))
                val rare = rareItems(run)
                val sorted = run.loot.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { (book?.priceOf(it.key) ?: 0) * it.value }
                    .thenBy { run.chanceOf(it.key) })
                for ((item, n) in sorted.take(80)) {
                    val value = (book?.priceOf(item) ?: 0) * n
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(3), dp(4), dp(3))
                        background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(4).toFloat() }
                        addView(itemIcon(WikiIcons.forItem(item, book)), LinearLayout.LayoutParams(dp(24), dp(24)))
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(if (n > 1) "$item ×${Gp.full(n)}" else item, 11f, bold = item in rare).apply {
                                if (item in rare) setTextColor(RARE_PURPLE)
                            })
                            addView(label(Gp.rate(run.chanceOf(item)) + " per " + run.one(), 9f).apply { setTextColor(FADED) })
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
                        addView(label(if (book == null) "" else if (value > 0) Gp.short(value) else "–", 11f, bold = true))
                    }, full(3))
                }
                if (sorted.size > 80) addView(label("…and ${sorted.size - 80} more items", 10f).apply { setTextColor(FADED) }, full(2))
            }
        }
    }

    // Rare = unlikely (rarer than 1 in 200 kills) and valuable, or untradeable (pets, jars…)
    private fun rareItems(run: Run): Set<String> {
        run.rare?.let { return it }
        val book = prices
        val set = run.table.all.map { it.item }.distinct().filter { item ->
            if (item.equals("Coins", ignoreCase = true)) return@filter false
            if (run.chanceOf(item) > RARE_CHANCE) return@filter false
            if (book == null) return@filter item == run.pet
            val price = book.priceOf(item)
            price == 0L || price >= RARE_VALUE
        }.toSet()
        if (book != null) run.rare = set
        return set
    }

    private fun kill(run: Run, count: Int, untilPet: Boolean) {
        if (killing) return
        killing = true
        show()
        val rare = rareItems(run)
        val book = prices
        work.execute {
            val got = HashMap<String, Long>()
            val newRares = ArrayList<Pair<String, Long>>()
            var last = HashMap<String, Long>()
            var kills = 0
            var gotPet = false
            while (kills < count) {
                val one = HashMap<String, Long>()
                run.table.kill(random, one)
                kills++
                for ((item, n) in one) {
                    got[item] = (got[item] ?: 0) + n
                    if (item in rare) newRares.add(item to run.kills + kills)
                }
                last = one
                if (untilPet && run.pet != null && one.containsKey(run.pet)) { gotPet = true; break }
            }
            handler.post {
                for ((item, n) in got) run.loot[item] = (run.loot[item] ?: 0) + n
                run.kills += kills
                run.rares.addAll(newRares)
                run.message = when {
                    untilPet && gotPet -> "🐾 ${run.pet} after ${Gp.full(kills.toLong())} ${run.many()}! (${run.count(run.kills)}, drop rate ${Gp.rate(run.chanceOf(run.pet!!))})"
                    untilPet -> "No pet after ${Gp.full(kills.toLong())} more ${run.many()}. Unlucky!"
                    count == 1 -> (if (run.boss.isCasket) "Casket " else "Kill ") + Gp.full(run.kills) + ": " + describe(last, book)
                    else -> "+${Gp.full(kills.toLong())} ${run.many()}" +
                        (if (newRares.isEmpty()) ". No rare drops this time." else ": " + newRares.joinToString(", ") { "${it.first} (${run.count(it.second)})" })
                }
                killing = false
                if (screen == Screen.LOOT_BOSS && boss == run.boss) show()
            }
        }
    }

    // "Tanzanite fang, 180 × Zulrah's scales, …"
    private fun describe(loot: Map<String, Long>, book: PriceBook?): String {
        if (loot.isEmpty()) return "nothing."
        return loot.entries.sortedByDescending { (book?.priceOf(it.key) ?: 0) * it.value }
            .joinToString(", ") { (item, n) -> if (n > 1) "${Gp.full(n)} × $item" else item }
    }

    private fun rememberScroll() {
        val v = if (holder.childCount > 0) holder.getChildAt(0) else null
        if (v is ScrollView) scrollY = v.scrollY
    }

    // ---------------- 2048 ----------------

    private val g2048: Game2048 by lazy {
        Game2048().apply {
            val saved = prefs.getString("g2048_cells", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            if (saved != null) restore(saved, prefs.getInt("g2048_score", 0)) else newGame()
        }
    }

    private fun save2048() {
        val best = maxOf(prefs.getInt("g2048_best", 0), g2048.score)
        prefs.edit()
            .putString("g2048_cells", g2048.cells.joinToString(","))
            .putInt("g2048_score", g2048.score)
            .putInt("g2048_best", best)
            .apply()
    }

    // Not scrolling: swipes are for the tiles. The board shrinks to fit whatever space is left.
    private fun game2048(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        val game = g2048

        fun box(title: String) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(3))
            background = GradientDrawable().apply { setColor(BUTTON_BROWN); cornerRadius = dp(5).toFloat() }
            addView(label(title, 9f).apply { setTextColor(Color.parseColor("#F2E3C0")); gravity = Gravity.CENTER })
            addView(label("", 13f, bold = true).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        }
        val scoreBox = box("SCORE")
        val bestBox = box("BEST")
        lateinit var board: Game2048View
        val over = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#CCF2E3C0")); cornerRadius = dp(8).toFloat() }
        }

        fun update() {
            val best = maxOf(prefs.getInt("g2048_best", 0), game.score)
            (scoreBox.getChildAt(1) as TextView).text = Gp.full(game.score.toLong())
            (bestBox.getChildAt(1) as TextView).text = Gp.full(best.toLong())
            // the "game over" and "you made 2048" covers
            over.removeAllViews()
            val won = game.best >= 2048 && !prefs.getBoolean("g2048_won_seen", false)
            val lost = !game.canMove()
            over.visibility = if (won || lost) View.VISIBLE else View.GONE
            if (lost) {
                over.addView(label("No more moves!", 16f, bold = true).apply { gravity = Gravity.CENTER })
                over.addView(label("Score: " + Gp.full(game.score.toLong()), 12f).apply { gravity = Gravity.CENTER }, full(2))
                over.addView(button("Try again", GO_GREEN) {
                    game.newGame(); prefs.edit().putBoolean("g2048_won_seen", false).apply()
                    save2048(); board.refresh(); update()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            } else if (won) {
                over.addView(label("You made 2048!", 16f, bold = true).apply { gravity = Gravity.CENTER; setTextColor(Color.parseColor("#9C6A10")) })
                over.addView(button("Keep going", GO_GREEN) {
                    prefs.edit().putBoolean("g2048_won_seen", true).apply(); update()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }

        board = Game2048View(context, game) { save2048(); update() }

        // Top row: title, score, best, new game
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("2048", 18f, bold = true).apply { setTextColor(Color.parseColor("#C8900E")) })
            addView(helpButton("2048", HELP_2048), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(5) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scoreBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(bestBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(button("New", STOP_RED) {
                game.newGame(); prefs.edit().putBoolean("g2048_won_seen", false).apply()
                save2048(); board.refresh(); update()
            }.apply { textSize = 11f; setPadding(dp(8), dp(6), dp(8), dp(6)) })
        }, full())

        // The board, centred, with the covers on top of it
        addView(FrameLayout(context).apply {
            addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(over, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        update()
    }

    // ---------------- Item pictures ----------------

    private val icons = LruCache<String, Bitmap>(200)
    private val noIcon = HashSet<String>()     // pictures the wiki doesn't have
    private val iconLoading = HashSet<String>()
    private val iconDir = File(dir, "icons").apply { mkdirs() }

    // A small item picture, loaded in the background (and saved on the phone for next time)
    private fun itemIcon(url: String): ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        tag = url
        val cached = icons.get(url)
        if (cached != null) setPixelArt(cached)
        else if (url !in noIcon) loadIcon(url, this)
    }

    private fun ImageView.setPixelArt(bitmap: Bitmap) {
        setImageDrawable(BitmapDrawable(context.resources, bitmap).apply { isFilterBitmap = false })   // crisp, like the game
    }

    private fun loadIcon(url: String, view: ImageView) {
        if (!iconLoading.add(url)) return
        iconWork.execute {
            val file = File(iconDir, Integer.toHexString(url.hashCode()) + ".png")
            val missing = File(file.path + ".none")
            var bitmap: Bitmap? = null
            if (file.exists()) bitmap = BitmapFactory.decodeFile(file.path)
            if (bitmap == null && !missing.exists()) {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.connectTimeout = 10_000; c.readTimeout = 15_000
                    try {
                        when (c.responseCode) {
                            200 -> {
                                val bytes = c.inputStream.use { it.readBytes() }
                                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) file.writeBytes(bytes)
                            }
                            404 -> missing.createNewFile()
                        }
                    } finally { c.disconnect() }
                } catch (e: Exception) { }
            }
            val found = bitmap
            handler.post {
                iconLoading.remove(url)
                if (found != null) {
                    icons.put(url, found)
                    if (view.tag == url) view.setPixelArt(found)
                } else if (missing.exists()) noIcon.add(url)
            }
        }
    }

    // ---------------- Small building blocks ----------------

    // A game's name with a small "?" beside it (and something on the right, like a score)
    private fun titleRow(title: String, help: String, right: View? = null): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 15f, bold = true))
        addView(helpButton(title, help), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(6) })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (right != null) addView(right)
    }

    private fun helpButton(title: String, help: String) = TextView(context).apply {
        text = "?"
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BUTTON_BROWN) }
        setOnClickListener { showHelp(title, help) }
    }

    // How to play, as a card on top of the game (so nothing underneath moves)
    private var openHelp: Pair<String, String>? = null   // kept open if the screen redraws underneath it

    private fun showHelp(title: String, help: String) {
        openHelp = title to help
        val cover = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#993E2C12"))
            isClickable = true
        }
        val close = { openHelp = null; holder.removeView(cover) }
        cover.setOnClickListener { close() }
        cover.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(8).toFloat() }
                isClickable = true   // taps on the card don't close it
                addView(label("How to play: $title", 13f, bold = true), full())
                addView(label(help, 12f).apply { setLineSpacing(0f, 1.1f) }, full(6))
                addView(button("Got it", GO_GREEN) { close() }, full(10))
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        holder.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun scrolling(build: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            build()
        })
        val y = scrollY
        if (y > 0) post { scrollTo(0, y) }
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTypeface(typeface, Typeface.BOLD)
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

// The little pictures on the Game Room menu, drawn in code so they're sharp at any size
class GameIcon(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val HIGHER_LOWER = 0
        const val LOOT = 1
        const val G2048 = 2
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        canvas.translate((width - s) / 2, (height - s) / 2)
        // dark rounded square behind
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#4A3518")
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * 0.18f, s * 0.18f, paint)
        when (kind) {
            HIGHER_LOWER -> higherLower(canvas, s)
            LOOT -> chest(canvas, s)
            G2048 -> tiles(canvas, s)
        }
    }

    private fun coin(canvas: Canvas, x: Float, y: Float, r: Float) {
        paint.color = Color.parseColor("#9C6A10"); canvas.drawCircle(x, y, r, paint)
        paint.color = Color.parseColor("#F2C230"); canvas.drawCircle(x, y, r * 0.78f, paint)
    }

    private fun higherLower(canvas: Canvas, s: Float) {
        // green arrow up on the left
        paint.color = Color.parseColor("#4CC35A")
        path.reset()
        path.moveTo(s * 0.30f, s * 0.14f); path.lineTo(s * 0.50f, s * 0.42f); path.lineTo(s * 0.10f, s * 0.42f); path.close()
        canvas.drawPath(path, paint)
        canvas.drawRect(s * 0.22f, s * 0.41f, s * 0.38f, s * 0.62f, paint)
        // red arrow down on the right
        paint.color = Color.parseColor("#E5483B")
        path.reset()
        path.moveTo(s * 0.70f, s * 0.86f); path.lineTo(s * 0.90f, s * 0.58f); path.lineTo(s * 0.50f, s * 0.58f); path.close()
        canvas.drawPath(path, paint)
        canvas.drawRect(s * 0.62f, s * 0.38f, s * 0.78f, s * 0.59f, paint)
        // coins in the empty corners
        coin(canvas, s * 0.30f, s * 0.79f, s * 0.11f)
        coin(canvas, s * 0.70f, s * 0.21f, s * 0.11f)
    }

    // four little 2048 tiles
    private fun tiles(canvas: Canvas, s: Float) {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        val values = intArrayOf(2, 8, 64, 2048)
        val gap = s * 0.08f
        val cell = (s - gap * 3) / 2
        for (i in 0 until 4) {
            val x = gap + (i % 2) * (cell + gap)
            val y = gap + (i / 2) * (cell + gap)
            paint.color = Game2048View.colorOf(values[i])
            canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), cell * 0.15f, cell * 0.15f, paint)
            val label = values[i].toString()
            text.color = if (values[i] <= 4) Color.parseColor("#3E2C12") else Color.WHITE
            text.textSize = cell * if (label.length >= 4) 0.3f else 0.5f
            canvas.drawText(label, x + cell / 2, y + cell / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private fun chest(canvas: Canvas, s: Float) {
        // coins peeking out of the top
        coin(canvas, s * 0.36f, s * 0.30f, s * 0.09f)
        coin(canvas, s * 0.55f, s * 0.26f, s * 0.09f)
        coin(canvas, s * 0.68f, s * 0.32f, s * 0.08f)
        // purple gem
        paint.color = Color.parseColor("#B45CF0")
        path.reset()
        path.moveTo(s * 0.47f, s * 0.14f); path.lineTo(s * 0.54f, s * 0.22f); path.lineTo(s * 0.47f, s * 0.32f); path.lineTo(s * 0.40f, s * 0.22f); path.close()
        canvas.drawPath(path, paint)
        // lid
        paint.color = Color.parseColor("#6B4220")
        canvas.drawRoundRect(RectF(s * 0.14f, s * 0.34f, s * 0.86f, s * 0.52f), s * 0.06f, s * 0.06f, paint)
        // body
        paint.color = Color.parseColor("#8B5A2B")
        canvas.drawRect(s * 0.16f, s * 0.50f, s * 0.84f, s * 0.84f, paint)
        // gold bands
        paint.color = Color.parseColor("#E0A526")
        canvas.drawRect(s * 0.14f, s * 0.49f, s * 0.86f, s * 0.54f, paint)
        canvas.drawRect(s * 0.45f, s * 0.34f, s * 0.55f, s * 0.84f, paint)
        // keyhole
        paint.color = Color.parseColor("#2A1A08")
        canvas.drawCircle(s * 0.50f, s * 0.64f, s * 0.035f, paint)
        canvas.drawRect(s * 0.49f, s * 0.64f, s * 0.51f, s * 0.72f, paint)
    }
}
