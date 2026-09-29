package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Reads a player's quest progress and levels from WikiSync, the OSRS Wiki's service.
// WikiSync only has data if the player has played on RuneLite with the WikiSync plugin on;
// it's as up to date as their last RuneLite session.
class WikiSync(context: Context) {

    companion object {
        private const val USER_AGENT = "OSRSBubbleTool/1.0 (personal Android app)"
        private const val REFRESH_AFTER_MS = 10 * 60_000L   // look again after 10 minutes

        const val NOT_STARTED = 0
        const val IN_PROGRESS = 1
        const val FINISHED = 2
    }

    class Data(
        val quests: Map<String, Int>,
        val levels: Map<String, Int>,
        val diaries: Map<String, Map<String, List<Boolean>>>,   // region -> tier -> each task done?
        val fetchedAt: Long
    ) {
        fun questState(name: String): Int? = quests[name]
        fun level(skill: String): Int? = levels.entries.firstOrNull { it.key.equals(skill, ignoreCase = true) }?.value
        fun diaryTasks(region: String, tier: String): List<Boolean>? = diaries[region]?.get(tier)
    }

    private val prefs = context.getSharedPreferences("wikisync", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var loading = false

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(value) {
            prefs.edit().putString("username", value.trim()).remove("data").remove("fetched").apply()
        }

    // The last data fetched, if any (kept so it shows straight away, even offline)
    val cached: Data?
        get() {
            val text = prefs.getString("data", null) ?: return null
            return try { parse(JSONObject(text), prefs.getLong("fetched", 0)) } catch (e: Exception) { null }
        }

    val needsRefresh: Boolean
        get() = username.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("fetched", 0) > REFRESH_AFTER_MS

    // Fetches in the background. onDone runs on the main thread with the data, or an error message.
    fun refresh(onDone: (Data?, String?) -> Unit) {
        val name = username
        if (name.isEmpty() || loading) return
        loading = true
        Thread {
            var data: Data? = null
            var error: String? = null
            for (attempt in 1..2) {   // WikiSync sometimes turns requests away when busy; try twice
                try {
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    val connection = URL("https://sync.runescape.wiki/runelite/player/$encoded/STANDARD")
                        .openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    try {
                        val code = connection.responseCode
                        val body = (if (code < 400) connection.inputStream else connection.errorStream)
                            ?.bufferedReader()?.use { it.readText() } ?: ""
                        if (code == 200) {
                            val now = System.currentTimeMillis()
                            data = parse(JSONObject(body), now)
                            prefs.edit().putString("data", body).putLong("fetched", now).apply()
                            error = null
                            break
                        }
                        error = if (body.contains("NO_USER_DATA"))
                            "WikiSync has no data for \"$name\". Play a session on RuneLite with the WikiSync plugin turned on, then refresh."
                        else "WikiSync didn't answer (error $code). Try again in a moment."
                        if (code != 500) break
                    } finally {
                        connection.disconnect()
                    }
                } catch (e: Exception) {
                    error = "Couldn't reach WikiSync. Check your internet connection."
                }
            }
            handler.post {
                loading = false
                onDone(data, error)
            }
        }.start()
    }

    private fun parse(json: JSONObject, fetchedAt: Long): Data {
        val quests = HashMap<String, Int>()
        json.optJSONObject("quests")?.let { q -> q.keys().forEach { quests[it] = q.optInt(it) } }
        val levels = HashMap<String, Int>()
        json.optJSONObject("levels")?.let { l -> l.keys().forEach { levels[it] = l.optInt(it) } }
        val diaries = HashMap<String, Map<String, List<Boolean>>>()
        json.optJSONObject("achievement_diaries")?.let { all ->
            all.keys().forEach { region ->
                val tiers = HashMap<String, List<Boolean>>()
                all.optJSONObject(region)?.let { t ->
                    t.keys().forEach { tier ->
                        val tasks = t.optJSONObject(tier)?.optJSONArray("tasks") ?: return@forEach
                        tiers[tier] = List(tasks.length()) { tasks.optBoolean(it) }
                    }
                }
                diaries[region] = tiers
            }
        }
        return Data(quests, levels, diaries, fetchedAt)
    }
}
