package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject

// Every quest's requirements, and every achievement diary task, loaded from QuestData.
object QuestBook {

    // A requirement for a quest or diary task
    sealed class Req {
        class Skill(val skill: String, val level: Int, val boostable: Boolean = false) : Req()
        class QuestDone(val quest: String, val startedOnly: Boolean = false) : Req()
        class QuestPoints(val points: Int) : Req()
        class Combat(val level: Int) : Req()
        class Text(val text: String) : Req()
    }

    class QuestInfo(
        val name: String,
        val members: Boolean,
        val miniquest: Boolean,
        val difficulty: String,
        val questPoints: Int,
        val requirements: List<Req>,
        val enemies: List<String>
    )

    class DiaryTask(val name: String, val steps: List<String>, val requirements: List<Req>, val items: List<String>)
    class DiaryTier(val tier: String, val requirements: List<Req>, val rewards: List<String>, val tasks: List<DiaryTask>)
    class Diary(val region: String, val tiers: List<DiaryTier>)

    val quests: List<QuestInfo> by lazy { load().first }
    val diaries: List<Diary> by lazy { load().second }
    private val byName: Map<String, QuestInfo> by lazy { quests.associateBy { it.name } }

    fun quest(name: String): QuestInfo? = byName[name]

    private var loaded: Pair<List<QuestInfo>, List<Diary>>? = null

    @Synchronized
    private fun load(): Pair<List<QuestInfo>, List<Diary>> {
        loaded?.let { return it }
        val root = JSONObject(QuestData.json)
        val q = root.getJSONArray("quests")
        val quests = List(q.length()) { i ->
            val o = q.getJSONObject(i)
            val type = o.getString("t")
            QuestInfo(o.getString("n"), type != "F", type == "M", o.optString("d"), o.optInt("qp"),
                reqs(o.optJSONArray("r")), strings(o.optJSONArray("e")))
        }
        val d = root.getJSONArray("diaries")
        val diaries = List(d.length()) { i ->
            val o = d.getJSONObject(i)
            val t = o.getJSONArray("t")
            Diary(o.getString("n"), List(t.length()) { j ->
                val tier = t.getJSONObject(j)
                val k = tier.getJSONArray("k")
                DiaryTier(tier.getString("t"), reqs(tier.optJSONArray("g")), strings(tier.optJSONArray("w")),
                    List(k.length()) { n ->
                        val task = k.getJSONObject(n)
                        DiaryTask(task.getString("n"), strings(task.optJSONArray("s")),
                            reqs(task.optJSONArray("r")), strings(task.optJSONArray("i")))
                    })
            })
        }
        return (quests to diaries).also { loaded = it }
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }

    private fun reqs(a: JSONArray?): List<Req> {
        if (a == null) return emptyList()
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            when {
                o.has("s") -> Req.Skill(o.getString("s"), o.getInt("l"), o.optInt("b") == 1)
                o.has("q") -> Req.QuestDone(o.getString("q"), o.optInt("st") == 1)
                o.has("qp") -> Req.QuestPoints(o.getInt("qp"))
                o.has("cb") -> Req.Combat(o.getInt("cb"))
                else -> Req.Text(o.optString("x"))
            }
        }
    }

    // How a requirement reads in a list ("70 Agility (boostable)", "Dragon Slayer I (started)")
    fun describe(req: Req): String = when (req) {
        is Req.Skill -> "${req.level} ${req.skill}" + if (req.boostable) " (boostable)" else ""
        is Req.QuestDone -> req.quest + if (req.startedOnly) " (started)" else ""
        is Req.QuestPoints -> "${req.points} Quest points"
        is Req.Combat -> "Combat level ${req.level}"
        is Req.Text -> req.text
    }

    fun wikiUrl(page: String): String =
        "https://oldschool.runescape.wiki/w/" + java.net.URLEncoder.encode(page.replace(' ', '_'), "UTF-8")
            .replace("%2F", "/").replace("%21", "!").replace("%27", "'")
}
