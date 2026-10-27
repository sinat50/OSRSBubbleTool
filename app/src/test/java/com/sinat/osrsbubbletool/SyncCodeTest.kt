package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Reading Bubble Tool Sync codes on the PC. The code below is written out byte by byte from the plugin's format
// (version 1), so the expected answers don't come from the app's own reader.
class SyncCodeTest {

    // A small made-up account, "Zezima":
    //   read at 1,700,000,000 s (0x6553F100) · 337 quest points · Attack 99, Hunter 70, every other skill 1
    //   Ardougne varp 1 = 9 (bits 0 and 3 set) · Karamja varbit 0 = 5, varbit 7 = 4
    //   3 quests: ID 0 finished, ID 17 started, ID 300 not started (IDs as differences: 0, 17, 283)
    private val bytes: List<Int> = buildList {
        add(1)                                       // format version
        addAll(listOf(0x65, 0x53, 0xF1, 0x00))       // when read
        add(6); addAll("Zezima".map { it.code })     // name
        add(0)                                       // normal account
        addAll(listOf(0x01, 0x51))                   // 337 quest points (0x0151)
        repeat(24) { i -> add(when (i) { 0 -> 99; 21 -> 70; else -> 1 }) }   // levels: Attack first, Hunter 22nd
        addAll(listOf(0, 0, 0, 9)); repeat(22) { addAll(listOf(0, 0, 0, 0)) }   // 23 diary varps
        repeat(39) { i -> add(when (i) { 0 -> 5; 7 -> 4; else -> 0 }) }        // Karamja varbits
        add(3)                                       // 3 quests
        addAll(listOf(0, 17, 0x9B, 0x02))            // 0, +17, +283 (283 = 0b10_0011011: 0x9B then 0x02)
        add(0b10_01_00_00)                           // states 2, 1, 0 (and nothing in the last 2 bits)
    }

    private fun code(b: List<Int> = bytes) =
        "BTS1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(b.size) { b[it].toByte() })

    private val questIds = mapOf("Animal Magnetism" to 0, "Cook's Assistant" to 17, "Big quest" to 300, "Brand new" to 999)
    private val diaryTasks = mapOf("Ardougne|Easy" to "p0.0 p0.1 p0.3 k0.5 k7.5 -")

    private fun read(text: String = code()) = SyncCode.read(text, questIds, diaryTasks)

    @Test
    fun readsNameTimeAndLevels() {
        val a = read()
        assertEquals("Zezima", a.name)
        assertEquals(1_700_000_000_000L, a.readAt)
        assertEquals(337, a.questPoints)
        // the game's own total is what counts, not the few quests in this made-up code
        assertEquals(337, QuestBook.questPoints(a))
        assertEquals(99, a.level("Attack"))
        assertEquals(70, a.level("hunter"))
        assertEquals(1, a.level("Sailing"))
        assertEquals(24, a.levels.size)
    }

    @Test
    fun readsQuestsByName() {
        val a = read()
        assertEquals(Account.FINISHED, a.questState("Animal Magnetism"))
        assertEquals(Account.IN_PROGRESS, a.questState("Cook's Assistant"))
        assertEquals(Account.NOT_STARTED, a.questState("Big quest"))
        // a quest the plugin's RuneLite didn't send is "not known", not "not started"
        assertNull(a.questState("Brand new"))
    }

    @Test
    fun readsDiaryTasks() {
        // bit 0 set, bit 1 not, bit 3 set; Karamja varbit 0 at 5 (done at 5), varbit 7 at 4 (not yet); unknown task
        assertEquals(listOf(true, false, true, true, false, false), read().diaryTasks("Ardougne", "Easy"))
    }

    @Test
    fun spacesAroundTheCodeAreFine() {
        assertEquals("Zezima", read("  " + code() + "\n").name)
        // the exported file after Windows Notepad saved it again: its invisible start mark and a Windows line end
        assertEquals("Zezima", read("\uFEFF" + code() + "\r\n").name)
    }

    @Test
    fun turnsAwayCodesItCantRead() {
        fun bad(text: String) {
            try { read(text); fail("read \"$text\"") } catch (e: SyncCode.BadCode) { assertTrue(e.message!!.isNotEmpty()) }
        }
        bad("https://example.com")                    // some other QR code
        bad("BTS2:AQ")                                 // a newer format
        bad(code(bytes.dropLast(3)))                   // cut short
        bad(code(bytes + 0))                           // a byte too many
        bad(code(listOf(2) + bytes.drop(1)))           // format version 2 inside
        bad("BTS1:not*base64")
        // made-up codes claiming a negative or enormous number of quests (the count comes after the first 170 bytes)
        val header = bytes.take(170)
        bad(code(header + listOf(0xFF, 0xFF, 0xFF, 0xFF, 0x7F)))   // too big for a number: would come out negative
        bad(code(header + listOf(0xFF, 0xFF, 0xFF, 0xFF, 0x07)))   // 2,147,483,647 quests
        bad(code(header + listOf(0xE8, 0x07, 0, 0)))                // 1,000 quests in 2 bytes
    }

    @Test
    fun namesAreCleanedUp() {
        fun named(name: String): String {
            val b = name.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }
            return read(code(bytes.take(5) + b.size + b + bytes.drop(12))).name
        }
        assertEquals("Zezima", named("Zezima"))
        assertEquals("Zezima", named("\u202EZezima\u200B"))     // right-to-left mark and an invisible space taken out
        assertEquals("Iron Sinat", named("Iron Sinat"))            // ordinary spaces stay
        assertEquals("ABCDEFGHIJKL", named("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))   // at most 12 letters
    }

    // ---- With the app's real tables (SyncTables.kt, made by tools/quest-helper/sync_tables.py) ----

    @Test
    fun everyQuestHasARuneLiteId() {
        val missing = QuestBook.quests.map { it.name }.filter { it !in SyncTables.QUEST_IDS }
        assertEquals(emptyList<String>(), missing)
        assertEquals(17, SyncTables.QUEST_IDS["Cook's Assistant"])   // RuneLite's Quest.COOKS_ASSISTANT
    }

    @Test
    fun everyDiaryTaskHasItsPlace() {
        for (diary in QuestBook.diaries) for (tier in diary.tiers) {
            val places = SyncTables.DIARY_TASKS["${diary.region}|${tier.tier}"]?.split(' ')
            assertEquals("${diary.region} ${tier.tier}", tier.tasks.size, places?.size)
            assertTrue("${diary.region} ${tier.tier}", places!!.none { it == "-" })
        }
    }
}
