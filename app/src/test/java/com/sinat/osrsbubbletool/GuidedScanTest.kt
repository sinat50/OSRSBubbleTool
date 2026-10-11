package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// The guided scan's "believe an empty reading on the second look in a row" (an empty pouch, an empty inventory),
// look by look. Expected answers worked out by hand from the rule.
class GuidedScanTest {

    // Each look: the reading ("empty", "items", or null when that part wasn't read), and what the scan takes from it
    private fun looks(vararg readings: String?): List<String?> {
        val check = GuidedScan.SecondLook()
        return readings.map { check.check(it, it == "empty") }
    }

    @Test
    fun anEmptyReadingIsBelievedOnTheSecondLookInARow() {
        assertEquals(listOf(null, "empty"), looks("empty", "empty"))
    }

    @Test
    fun aLookInBetweenStartsTheCountAgain() {
        // a look that didn't read it (the tab closed, the pouch window open) breaks the row
        assertEquals(listOf(null, null, null, "empty"), looks("empty", null, "empty", "empty"))
    }

    @Test
    fun itemsAreTakenStraightAway() {
        assertEquals(listOf("items"), looks("items"))
        assertEquals(listOf(null, "items"), looks("empty", "items"))
        // and an empty one after them still needs two looks
        assertEquals(listOf("items", null, "empty"), looks("items", "empty", "empty"))
    }
}
