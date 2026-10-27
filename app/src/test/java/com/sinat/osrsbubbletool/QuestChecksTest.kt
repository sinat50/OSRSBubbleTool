package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.QuestBook.Met
import com.sinat.osrsbubbletool.QuestBook.Req
import com.sinat.osrsbubbletool.QuestBook.Status
import org.junit.Assert.assertEquals
import org.junit.Test

// Quest Helper's requirement checks on the PC, against made-up accounts.
class QuestChecksTest {

    private fun account(levels: Map<String, Int> = emptyMap(), quests: Map<String, Int> = emptyMap()) =
        Account(quests, levels, emptyMap(), readAt = 0L)

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
        val you = account(quests = mapOf("Cook's Assistant" to Account.FINISHED, "Dragon Slayer I" to Account.IN_PROGRESS,
            "Regicide" to Account.NOT_STARTED))
        assertEquals(Met.YES, QuestBook.check(Req.QuestDone("Cook's Assistant"), you).met)
        assertEquals(Met.PARTLY, QuestBook.check(Req.QuestDone("Dragon Slayer I"), you).met)
        assertEquals(Met.YES, QuestBook.check(Req.QuestDone("Dragon Slayer I", startedOnly = true), you).met)
        assertEquals(Met.NO, QuestBook.check(Req.QuestDone("Regicide"), you).met)
    }

    @Test
    fun questStatusFromYourAccount() {
        val you = account(levels = mapOf("Agility" to 50),
            quests = mapOf("Done one" to Account.FINISHED, "Going" to Account.IN_PROGRESS,
                "Easy" to Account.NOT_STARTED, "Hard" to Account.NOT_STARTED))
        assertEquals(Status.DONE, QuestBook.status(quest("Done one"), you))
        assertEquals(Status.STARTED, QuestBook.status(quest("Going"), you))
        assertEquals(Status.READY, QuestBook.status(quest("Easy", Req.Skill("Agility", 40)), you))
        assertEquals(Status.MISSING, QuestBook.status(quest("Hard", Req.Skill("Agility", 60)), you))
    }

    @Test
    fun boostableSkillCountsAsReady() {
        val you = account(levels = mapOf("Agility" to 50), quests = mapOf("Boosty" to Account.NOT_STARTED))
        assertEquals(Status.READY, QuestBook.status(quest("Boosty", Req.Skill("Agility", 52, boostable = true)), you))
    }

    @Test
    fun questStartedButNotFinishedIsStillMissing() {
        // "needs Dragon Slayer I finished": having only started it isn't enough to begin this one
        val you = account(quests = mapOf("Dragon Slayer I" to Account.IN_PROGRESS, "Next" to Account.NOT_STARTED))
        assertEquals(Status.MISSING, QuestBook.status(quest("Next", Req.QuestDone("Dragon Slayer I")), you))
    }

    @Test
    fun questThePluginDoesntKnowYetIsUnknown() {
        // a brand-new quest the plugin's RuneLite doesn't know yet: it may already be done, so it isn't called "ready"
        val you = account(quests = mapOf("Old quest" to Account.FINISHED))
        assertEquals(Status.UNKNOWN, QuestBook.status(quest("Brand new quest"), you))
    }

    @Test
    fun withoutAnAccountEverythingIsUnknown() {
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
        val you = account(quests = mapOf("Cook's Assistant" to Account.FINISHED, "Dragon Slayer II" to Account.FINISHED,
            "Regicide" to Account.IN_PROGRESS))
        assertEquals(6, QuestBook.questPoints(you))
        assertEquals("200 Quest points (you: 6)", QuestBook.check(Req.QuestPoints(200), you).text)
        assertEquals(Met.NO, QuestBook.check(Req.QuestPoints(200), you).met)
    }

    @Test
    fun recipeForDisasterPartsGiveAPointEach() {
        // 3 parts finished, 1 started: 3 points while the whole quest isn't done
        val partway = account(quests = mapOf("Recipe for Disaster" to Account.IN_PROGRESS,
            "Recipe for Disaster - Another Cook's Quest" to Account.FINISHED,
            "Recipe for Disaster - Mountain Dwarf" to Account.FINISHED,
            "Recipe for Disaster - Evil Dave" to Account.FINISHED,
            "Recipe for Disaster - Pirate Pete" to Account.IN_PROGRESS))
        assertEquals(3, QuestBook.questPoints(partway))
        // all done: the quest's own 10, not 10 more for the parts
        val done = account(quests = mapOf("Recipe for Disaster" to Account.FINISHED) +
            (1..10).associate { "Recipe for Disaster - part $it" to Account.FINISHED })
        assertEquals(10, QuestBook.questPoints(done))
    }

    @Test
    fun learningTheRopesIsInTheQuestList() {
        // Tutorial Island has been a 1-point quest since October 2025 (not in the Quest Helper plugin)
        assertEquals(1, QuestBook.quest("Learning the Ropes")?.questPoints)
    }
}
