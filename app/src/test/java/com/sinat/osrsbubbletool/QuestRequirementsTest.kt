package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.QuestBook.Req
import org.junit.Assert.assertEquals
import org.junit.Test

// Quest Helper's requirement lists on the PC: how each kind reads, and the app's real quest list.
class QuestRequirementsTest {

    @Test
    fun requirementsReadAsWritten() {
        assertEquals("52 Agility", QuestBook.describe(Req.Skill("Agility", 52)))
        assertEquals("52 Agility (boostable)", QuestBook.describe(Req.Skill("Agility", 52, boostable = true)))
        assertEquals("Dragon Slayer I", QuestBook.describe(Req.QuestDone("Dragon Slayer I")))
        assertEquals("Dragon Slayer I (started)", QuestBook.describe(Req.QuestDone("Dragon Slayer I", startedOnly = true)))
        assertEquals("200 Quest points", QuestBook.describe(Req.QuestPoints(200)))
        assertEquals("Combat level 85", QuestBook.describe(Req.Combat(85)))
        assertEquals("Able to survive", QuestBook.describe(Req.Text("Able to survive")))
    }

    // ---- With the app's real quest list ----

    @Test
    fun questListKnowsDragonSlayerTwoNeeds200QuestPoints() {
        val ds2 = QuestBook.quest("Dragon Slayer II")!!
        assertEquals(listOf(200), ds2.requirements.filterIsInstance<Req.QuestPoints>().map { it.points })
    }
}
