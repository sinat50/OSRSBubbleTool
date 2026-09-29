package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.max

// Every quest's requirements, and every achievement diary task, loaded from QuestData.
// Also works out whether you meet a requirement, using your WikiSync data.
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

    // ---------------- Checking against your account ----------------

    enum class Met { YES, NO, PARTLY, UNKNOWN }

    class Check(val met: Met, val text: String)

    fun questPoints(data: WikiSync.Data): Int =
        quests.filter { data.questState(it.name) == WikiSync.FINISHED }.sumOf { it.questPoints }

    fun combatLevel(data: WikiSync.Data): Int? {
        fun l(s: String) = data.level(s)
        val att = l("Attack") ?: return null; val str = l("Strength") ?: return null
        val def = l("Defence") ?: return null; val hp = l("Hitpoints") ?: return null
        val pray = l("Prayer") ?: return null; val range = l("Ranged") ?: return null
        val mage = l("Magic") ?: return null
        val base = 0.25 * (def + hp + pray / 2)
        val best = max(0.325 * (att + str), max(0.325 * floor(range * 1.5), 0.325 * floor(mage * 1.5)))
        return floor(base + best).toInt()
    }

    fun check(req: Req, data: WikiSync.Data?): Check = when (req) {
        is Req.Skill -> {
            val have = data?.level(req.skill)
            val name = "${req.level} ${req.skill}" + if (req.boostable) " (boostable)" else ""
            when {
                have == null -> Check(Met.UNKNOWN, name)
                have >= req.level -> Check(Met.YES, "${req.level} ${req.skill} (you: $have)")
                req.boostable -> Check(Met.PARTLY, "${req.level} ${req.skill} (you: $have, boostable)")
                else -> Check(Met.NO, "${req.level} ${req.skill} (you: $have, need ${req.level - have} more)")
            }
        }
        is Req.QuestDone -> {
            val name = req.quest + if (req.startedOnly) " (started)" else ""
            when (data?.questState(req.quest)) {
                null -> Check(Met.UNKNOWN, name)
                WikiSync.FINISHED -> Check(Met.YES, name)
                WikiSync.IN_PROGRESS ->
                    if (req.startedOnly) Check(Met.YES, name) else Check(Met.PARTLY, "${req.quest} (started, not finished)")
                else -> Check(Met.NO, "$name (not started)")
            }
        }
        is Req.QuestPoints -> {
            if (data == null) Check(Met.UNKNOWN, "${req.points} Quest points")
            else {
                val have = questPoints(data)
                if (have >= req.points) Check(Met.YES, "${req.points} Quest points (you: $have)")
                else Check(Met.NO, "${req.points} Quest points (you: $have)")
            }
        }
        is Req.Combat -> {
            val have = data?.let { combatLevel(it) }
            when {
                have == null -> Check(Met.UNKNOWN, "Combat level ${req.level}")
                have >= req.level -> Check(Met.YES, "Combat level ${req.level} (you: $have)")
                else -> Check(Met.NO, "Combat level ${req.level} (you: $have)")
            }
        }
        is Req.Text -> Check(Met.UNKNOWN, req.text)
    }

    enum class Status { DONE, STARTED, READY, MISSING, UNKNOWN }

    // Where you stand with a quest: finished, can start it, or missing something
    fun status(quest: QuestInfo, data: WikiSync.Data?): Status {
        if (data == null) return Status.UNKNOWN
        return when (data.questState(quest.name)) {
            WikiSync.FINISHED -> Status.DONE
            WikiSync.IN_PROGRESS -> Status.STARTED
            else -> if (missing(quest.requirements, data).isEmpty()) Status.READY else Status.MISSING
        }
    }

    // The requirements you definitely don't meet (boostable skills count as met)
    fun missing(reqs: List<Req>, data: WikiSync.Data?): List<Req> =
        reqs.filter { r -> check(r, data).met.let { it == Met.NO || (it == Met.PARTLY && r is Req.QuestDone) } }

    fun wikiUrl(page: String): String =
        "https://oldschool.runescape.wiki/w/" + java.net.URLEncoder.encode(page.replace(' ', '_'), "UTF-8")
            .replace("%2F", "/").replace("%21", "!").replace("%27", "'")
}
