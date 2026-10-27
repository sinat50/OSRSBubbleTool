package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.sinat.osrsbubbletool.Palette.GO_GREEN
import com.sinat.osrsbubbletool.Palette.RED

// Your quests, levels and achievement diary tasks, as read by the Bubble Tool Sync plugin in RuneLite on a PC.
// The plugin shows them as a QR code (or text to copy); the Bubble Sync tool scans it, and the Quest Helper,
// Hunter Rumours and Teleport Finder use it to show what you can do. Nothing is sent over the internet.
class Account(
    val quests: Map<String, Int>,                           // quest name -> NOT_STARTED, IN_PROGRESS or FINISHED
    val levels: Map<String, Int>,                           // skill name -> level
    val diaries: Map<String, Map<String, List<Boolean>>>,   // region -> tier -> each task done?
    val readAt: Long,                                       // when the plugin read it (milliseconds since 1970)
    val name: String = "",                                  // the account's name in the game
    val questPoints: Int? = null                            // the game's own total (null: add up the quests instead)
) {
    companion object {
        const val NOT_STARTED = 0
        const val IN_PROGRESS = 1
        const val FINISHED = 2
    }

    fun questState(name: String): Int? = quests[name]
    fun level(skill: String): Int? = levels.entries.firstOrNull { it.key.equals(skill, ignoreCase = true) }?.value
    fun diaryTasks(region: String, tier: String): List<Boolean>? = diaries[region]?.get(tier)
}

// Reading the plugin's code: "BTS1:" and then the bytes below in base64url. The layout ("format version 1")
// is the plugin's (SyncData.java in github.com/sinat50/bubble-tool-sync), and both must match exactly:
//   1 byte format version · 4 bytes when read (seconds since 1970) · name length and name (UTF-8) ·
//   1 byte account type · 2 bytes quest points (the game's own total) · 24 skill levels · 23 diary varps (4 bytes each) · 39 Karamja varbits (1 byte each) ·
//   number of quests, their RuneLite IDs (each the difference from the one before), then their states
//   (2 bits each, first quest in the top bits). Numbers marked "varint" use 7 bits per byte, low bits first.
// No Android parts, so it's tested on the PC (SyncCodeTest).
object SyncCode {

    const val PREFIX = "BTS1:"

    // In the order the plugin sends them
    val SKILLS = listOf("Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer", "Magic", "Cooking",
        "Woodcutting", "Fletching", "Fishing", "Firemaking", "Crafting", "Smithing", "Mining", "Herblore", "Agility",
        "Thieving", "Slayer", "Farming", "Runecraft", "Hunter", "Construction", "Sailing")
    private const val VARPS = 23
    private const val KARAMJA_VARBITS = 39

    class BadCode(message: String) : Exception(message)

    // Reads a code into an Account. questIds: name -> RuneLite quest ID; diaryTasks: "Region|Tier" -> each task's
    // place in the code (see SyncTables). Throws BadCode if it isn't a Bubble Tool Sync code this app can read.
    fun read(text: String, questIds: Map<String, Int> = SyncTables.QUEST_IDS,
             diaryTasks: Map<String, String> = SyncTables.DIARY_TASKS): Account {
        val code = clean(text)
        if (!code.startsWith("BTS")) throw BadCode("That isn't a Bubble Tool Sync code.")
        if (!code.startsWith(PREFIX)) throw BadCode("This code is from a newer Bubble Tool Sync plugin. Update the app to read it.")
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(code.removePrefix(PREFIX))
        } catch (e: IllegalArgumentException) {
            throw BadCode("The code is damaged. Scan it again.")
        }
        try {
            return unpack(bytes, questIds, diaryTasks)
        } catch (e: IndexOutOfBoundsException) {
            throw BadCode("The code is cut short. Scan it again.")
        } catch (e: BadCode) {
            throw e
        } catch (e: RuntimeException) {
            // anything else a made-up code could cause: turned away like any damaged code, never a crash
            throw BadCode("The code is damaged. Scan it again.")
        }
    }

    // The game's names are at most 12 letters. A made-up code could carry a long name, or invisible characters that
    // flip or hide the text around it: those are taken out (the plugin sends the game's spaces as plain spaces).
    fun cleanName(name: String): String =
        name.filter { Character.getType(it) != Character.CONTROL.toInt() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(12)

    // The code without spaces or line breaks around it, or the invisible mark Windows Notepad can put at the start
    // of a file it saved (the plugin's exported file, opened and saved again)
    fun clean(text: String): String = text.trim().removePrefix("\uFEFF").trim()

    private fun unpack(b: ByteArray, questIds: Map<String, Int>, diaryTasks: Map<String, String>): Account {
        var at = 0
        fun byte() = b[at++].toInt() and 0xFF
        fun int(): Long = (0 until 4).fold(0L) { n, _ -> (n shl 8) or byte().toLong() }
        fun varint(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val c = byte()
                value = value or ((c and 0x7F) shl shift)
                if (c < 0x80) return value
                shift += 7
                // a fifth byte may only add bits up to 30: anything more would make a negative or enormous number
                if (shift > 28 || (shift == 28 && (b[at].toInt() and 0xFF) > 7)) throw BadCode("The code is damaged. Scan it again.")
            }
        }

        if (byte() != 1) throw BadCode("This code is from a newer Bubble Tool Sync plugin. Update the app to read it.")
        val readAt = int() * 1000
        val nameLength = byte()
        if (at + nameLength > b.size) throw IndexOutOfBoundsException()
        val name = cleanName(String(b, at, nameLength, Charsets.UTF_8))
        at += nameLength
        byte()   // account type (ironman and so on): not used yet
        val questPoints = (byte() shl 8) or byte()
        val levels = SKILLS.associateWith { byte() }
        val varps = LongArray(VARPS) { int() }
        val karamja = IntArray(KARAMJA_VARBITS) { byte() }

        val count = varint()
        // every quest takes at least one byte for its ID: a code claiming more quests than it has bytes is damaged
        if (count > b.size - at) throw BadCode("The code is damaged. Scan it again.")
        val ids = IntArray(count)
        var id = 0
        for (i in 0 until count) { id += varint(); ids[i] = id }
        val stateOf = HashMap<Int, Int>()
        for (i in 0 until count) stateOf[ids[i]] = (b[at + i / 4].toInt() shr (6 - 2 * (i % 4))) and 3
        if (at + (count + 3) / 4 != b.size) throw BadCode("The code is damaged. Scan it again.")

        // Quests by the app's names (a quest the plugin's RuneLite doesn't know yet is left out: "not known")
        val quests = HashMap<String, Int>()
        for ((questName, questId) in questIds) stateOf[questId]?.let { quests[questName] = it }

        // Each diary task: "p<varp>.<bit>" is that bit of that varp; "k<varbit>.<n>" is that Karamja varbit
        // reaching n; "-" isn't known (counted as not done)
        val diaries = HashMap<String, HashMap<String, List<Boolean>>>()
        for ((key, places) in diaryTasks) {
            val region = key.substringBefore('|')
            val tier = key.substringAfter('|')
            diaries.getOrPut(region) { HashMap() }[tier] = places.split(' ').map { place ->
                val a = place.drop(1).substringBefore('.').toIntOrNull()
                val n = place.substringAfter('.').toIntOrNull()
                when {
                    a == null || n == null -> false
                    place[0] == 'p' -> a < VARPS && (varps[a] shr n) and 1L == 1L
                    place[0] == 'k' -> a < KARAMJA_VARBITS && karamja[a] >= n
                    else -> false
                }
            }
        }
        return Account(quests, levels, diaries, readAt, name, questPoints)
    }
}

// The scanned code, kept on the phone (it's small: about 600 letters). Shared by every tool that uses it.
class AccountSync(context: Context) {

    companion object {
        // A message from the file chooser for the Bubble Sync window, shown when it comes back ("That file couldn't
        // be read")
        @Volatile var notice: String? = null
    }

    private val prefs = context.getSharedPreferences("account_sync", Context.MODE_PRIVATE)

    // The code last scanned, read once and kept in memory (the screens ask for it several times per redraw)
    private var memo: Account? = null
    private var memoCode: String? = null
    val cached: Account?
        get() {
            val code = prefs.getString("code", null) ?: return null
            if (code == memoCode) return memo
            memoCode = code
            memo = try { SyncCode.read(code) } catch (e: SyncCode.BadCode) { null }
            return memo
        }

    // Saves a scanned or pasted code. Gives the account, or throws SyncCode.BadCode (nothing saved).
    fun save(text: String): Account {
        val account = SyncCode.read(text)
        prefs.edit { putString("code", SyncCode.clean(text)) }
        return account
    }

    fun forget() = prefs.edit { remove("code") }

    // Changes whenever a different code is saved: tools compare it to redraw when they're opened again
    val state: String get() = prefs.getString("code", "") ?: ""
}

// The little "✓ Synced" (green) or "✗ Not synced" (red) tag shown by the tools that use your account.
// Tapping it opens the Bubble Sync tool.
object SyncBadge {

    fun view(context: Context, sync: AccountSync, onClick: (() -> Unit)?): View = TextView(context).apply {
        val d = context.resources.displayMetrics.density
        val ok = sync.cached != null
        text = if (ok) "✓ Synced" else "✗ Not synced"
        textSize = 11f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding((8 * d).toInt(), (3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt())
        background = GradientDrawable().apply { setColor(if (ok) GO_GREEN else RED); cornerRadius = 20 * d }
        if (onClick != null) setOnClickListener { onClick() }
    }

    // The tag on its own row, on the right
    fun row(context: Context, sync: AccountSync, onClick: () -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(view(context, sync, onClick))
    }
}
