package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.QuestBook.Met
import com.sinat.osrsbubbletool.QuestBook.Req
import com.sinat.osrsbubbletool.QuestBook.Status
import org.junit.Assert.assertEquals
import org.junit.Test

// Quest Helper's requirement checks on the PC, against made-up WikiSync accounts.
class QuestChecksTest {

    private fun account(levels: Map<String, Int> = emptyMap(), quests: Map<String, Int> = emptyMap()) =
        WikiSync.Data(quests, levels, emptyMap(), fetchedAt = 0L)

    private fun quest(name: String, vararg reqs: Req) =
        QuestBook.QuestInfo(name, members = true, miniquest = false, difficulty = "Novice", questPoints = 1,
            requirements = reqs.toList(), enemies = emptyList())

    private val combatSkills = listOf("Attack", "Strength", "Defence", "Hitpoints", "Prayer", "Ranged", "Magic")

    @Test
    fun maxedAccountIsCombatLevel126() {
        assertEquals(126, QuestBook.combatLevel(account(combatSkills.associateWith { 99 })))
    }

    @Test
    fun brandNewAccountIsCombatLevel3() {
        val levels = combatSkills.associateWith { 1 } + ("Hitpoints" to 10)
        assertEquals(3, QuestBook.combatLevel(account(levels)))
    }

    @Test
    fun skillLevelIsMetOrShortOrBoostable() {
        val you = account(mapOf("Agility" to 50))
        assertEquals(Met.YES, QuestBook.check(Req.Skill("Agility", 50), you).met)
        assertEquals(Met.NO, QuestBook.check(Req.Skill("Agility", 52), you).met)
        assertEquals(Met.PARTLY, QuestBook.check(Req.Skill("Agility", 52, boostable = true), you).met)
        assertEquals(Met.UNKNOWN, QuestBook.check(Req.Skill("Agility", 52), null).met)
    }

    @Test
    fun skillShortfallSaysHowManyMoreLevels() {
        assertEquals("52 Agility (you: 50, need 2 more)", QuestBook.check(Req.Skill("Agility", 52), account(mapOf("Agility" to 50))).text)
    }

    @Test
    fun questRequirementFinishedStartedOrNot() {
        val you = account(quests = mapOf("Cook's Assistant" to WikiSync.FINISHED, "Dragon Slayer I" to WikiSync.IN_PROGRESS,
            "Regicide" to WikiSync.NOT_STARTED))
        assertEquals(Met.YES, QuestBook.check(Req.QuestDone("Cook's Assistant"), you).met)
        assertEquals(Met.PARTLY, QuestBook.check(Req.QuestDone("Dragon Slayer I"), you).met)
        assertEquals(Met.YES, QuestBook.check(Req.QuestDone("Dragon Slayer I", startedOnly = true), you).met)
        assertEquals(Met.NO, QuestBook.check(Req.QuestDone("Regicide"), you).met)
    }

    @Test
    fun questStatusFromYourAccount() {
        val you = account(levels = mapOf("Agility" to 50),
            quests = mapOf("Done one" to WikiSync.FINISHED, "Going" to WikiSync.IN_PROGRESS,
                "Easy" to WikiSync.NOT_STARTED, "Hard" to WikiSync.NOT_STARTED))
        assertEquals(Status.DONE, QuestBook.status(quest("Done one"), you))
        assertEquals(Status.STARTED, QuestBook.status(quest("Going"), you))
        assertEquals(Status.READY, QuestBook.status(quest("Easy", Req.Skill("Agility", 40)), you))
        assertEquals(Status.MISSING, QuestBook.status(quest("Hard", Req.Skill("Agility", 60)), you))
    }

    @Test
    fun boostableSkillCountsAsReady() {
        val you = account(levels = mapOf("Agility" to 50), quests = mapOf("Boosty" to WikiSync.NOT_STARTED))
        assertEquals(Status.READY, QuestBook.status(quest("Boosty", Req.Skill("Agility", 52, boostable = true)), you))
    }

    @Test
    fun questStartedButNotFinishedIsStillMissing() {
        // "needs Dragon Slayer I finished": having only started it isn't enough to begin this one
        val you = account(quests = mapOf("Dragon Slayer I" to WikiSync.IN_PROGRESS, "Next" to WikiSync.NOT_STARTED))
        assertEquals(Status.MISSING, QuestBook.status(quest("Next", Req.QuestDone("Dragon Slayer I")), you))
    }

    @Test
    fun questWikiSyncDoesntListYetIsUnknown() {
        // a brand-new quest WikiSync doesn't track yet: it may already be done, so it isn't called "ready"
        val you = account(quests = mapOf("Old quest" to WikiSync.FINISHED))
        assertEquals(Status.UNKNOWN, QuestBook.status(quest("Brand new quest"), you))
    }

    @Test
    fun withoutWikiSyncEverythingIsUnknown() {
        assertEquals(Status.UNKNOWN, QuestBook.status(quest("Any"), null))
    }

    // ---- With the app's real quest list ----

    @Test
    fun questListKnowsDragonSlayerTwoNeeds200QuestPoints() {
        val ds2 = QuestBook.quest("Dragon Slayer II")!!
        assertEquals(listOf(200), ds2.requirements.filterIsInstance<Req.QuestPoints>().map { it.points })
    }

    @Test
    fun questPointsAddUpFromFinishedQuests() {
        // Cook's Assistant gives 1, Dragon Slayer II gives 5; a started quest gives none
        val you = account(quests = mapOf("Cook's Assistant" to WikiSync.FINISHED, "Dragon Slayer II" to WikiSync.FINISHED,
            "Regicide" to WikiSync.IN_PROGRESS))
        assertEquals(6, QuestBook.questPoints(you))
        assertEquals("200 Quest points (you: 6)", QuestBook.check(Req.QuestPoints(200), you).text)
        assertEquals(Met.NO, QuestBook.check(Req.QuestPoints(200), you).met)
    }
}
