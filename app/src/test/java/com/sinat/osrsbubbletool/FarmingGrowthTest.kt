package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

// Farming timers on the PC: when a crop planted at a given time is fully grown. Crops grow on fixed clock ticks
// (herbs every 20 minutes: 12:00, 12:20, 12:40...), so a crop planted mid-tick counts from the tick it was
// planted in.
class FarmingGrowthTest {

    private fun at(time: String) = Instant.parse("2026-10-07T${time}:00Z").toEpochMilli()
    private fun kind(key: String) = FarmingTimers.kind(key)
    private fun crop(key: String, name: String) = kind(key).crops.first { it.name == name }

    @Test
    fun herbsPlantedMidTickCountFromThatTick() {
        // planted 12:07, in the 12:00 tick; 5 stages = 4 more ticks of 20 minutes: 13:20
        val herbs = kind("herbs")
        assertEquals(at("13:20"), FarmingTimers.finishTime(herbs, herbs.crops[0], at("12:07"), tickOffsetMinutes = 0))
    }

    @Test
    fun herbsPlantedExactlyOnATick() {
        val herbs = kind("herbs")
        assertEquals(at("13:20"), FarmingTimers.finishTime(herbs, herbs.crops[0], at("12:00"), tickOffsetMinutes = 0))
    }

    @Test
    fun magicTreeTakesTwelveFortyMinuteTicks() {
        // 40-minute ticks; planted 12:07, in the 12:00 tick; 13 stages = 12 more ticks = 8 hours: 20:00
        assertEquals(at("20:00"), FarmingTimers.finishTime(kind("trees"), crop("trees", "Magic"), at("12:07"), tickOffsetMinutes = 0))
    }

    @Test
    fun birdhousesAreAPlainFiftyMinuteCountdown() {
        val birdhouses = kind("birdhouses")
        assertEquals(at("12:57"), FarmingTimers.finishTime(birdhouses, birdhouses.crops[0], at("12:07"), tickOffsetMinutes = 0))
    }

    @Test
    fun tickOffsetMakesTimersLater() {
        // The Timers page says: "Leave at 0 unless your timers are always early", so the offset makes them later.
        // An offset of 3 minutes puts the herb ticks at 12:03, 12:23, 12:43...: planted 12:07, in the 12:03 tick,
        // grown 4 ticks later at 13:23
        val herbs = kind("herbs")
        assertEquals(at("13:23"), FarmingTimers.finishTime(herbs, herbs.crops[0], at("12:07"), tickOffsetMinutes = 3))
    }

    @Test
    fun tickOffsetCountsFromTheShiftedTickBeforePlanting() {
        // offset 3, planted 12:01: before that day's 12:03 tick, so in the 11:43 one; grown at 13:03
        val herbs = kind("herbs")
        assertEquals(at("13:03"), FarmingTimers.finishTime(herbs, herbs.crops[0], at("12:01"), tickOffsetMinutes = 3))
    }
}
