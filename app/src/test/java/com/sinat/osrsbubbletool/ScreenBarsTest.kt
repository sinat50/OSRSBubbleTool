package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Keeping the bubble and tool windows clear of the status bar and navigation bar, on the PC. The screen is the
// owner's phone sideways (2340 × 1080) unless a test says otherwise; its 3-button navigation bar is 144 px.
class ScreenBarsTest {

    private val w = 2340
    private val h = 1080
    private val none = Bars()
    private val phone = Bars(top = 84, bottom = 144)          // status bar and the navigation bar along the bottom
    private val emulator = Bars(top = 63, right = 126)        // the emulator sideways: navigation bar down the right

    private fun box(b: Box) = listOf(b.x, b.y, b.w, b.h)

    // ---- reading the bars from the two invisible strips ----

    @Test
    fun barsReadFromTheStrips() {
        // the tall strip is pushed down 84 by the status bar and up 144 by the navigation bar; the wide one isn't pushed in
        val bars = ScreenBars.fromStrips(downTop = 84, downHeight = h - 84 - 144, acrossLeft = 0, acrossWidth = w, screenW = w, screenH = h)!!
        assertEquals(listOf(84, 144, 0, 0), listOf(bars.top, bars.bottom, bars.left, bars.right))
    }

    @Test
    fun aNavigationBarDownTheSideIsReadFromTheWideStrip() {
        val bars = ScreenBars.fromStrips(downTop = 63, downHeight = h - 63, acrossLeft = 0, acrossWidth = w - 126, screenW = w, screenH = h)!!
        assertEquals(listOf(63, 0, 0, 126), listOf(bars.top, bars.bottom, bars.left, bars.right))
    }

    @Test
    fun aStripReadWhileThePhoneTurnsIsIgnored() {
        // still laid out for the upright screen (2340 tall) against the sideways one: longer than the screen
        assertNull(ScreenBars.fromStrips(downTop = 0, downHeight = 2340, acrossLeft = 0, acrossWidth = w, screenW = w, screenH = h))
        // or a "bar" a third of the screen deep: no bar is ever that big
        assertNull(ScreenBars.fromStrips(downTop = 0, downHeight = h - 400, acrossLeft = 0, acrossWidth = w, screenW = w, screenH = h))
    }

    // ---- placing a window clear of them ----

    @Test
    fun aWindowMovesOutFromUnderTheStatusBarAndShrinksToFit() {
        // wanted at the very top, 1000 tall: the space between the bars is 84 to 936, so it starts at 84, 852 tall
        assertEquals(listOf(100, 84, 800, 852), box(ScreenBars.place(Box(100, 0, 800, 1000), 0, 0, w, h, phone)))
    }

    @Test
    fun aWindowGoesBackWhereYouLeftItWhenTheBarsHide() {
        assertEquals(listOf(100, 0, 800, 1000), box(ScreenBars.place(Box(100, 0, 800, 1000), 0, 0, w, h, none)))
    }

    @Test
    fun aWindowMovesInFromANavigationBarDownTheSide() {
        // wanted at 2000, 600 wide: the touchable part ends at 2340 - 126 = 2214, so it moves to 1614
        assertEquals(listOf(1614, 100, 600, 500), box(ScreenBars.place(Box(2000, 100, 600, 500), 0, 0, w, h, emulator)))
    }

    @Test
    fun aWindowAsTallAsItsContentsMovesUpOffTheNavigationBar() {
        // a shrunk window (no set height) 300 tall at 900: its bottom would be under the bar, so it moves up to 636
        val b = ScreenBars.place(Box(50, 900, 0, 0), contentW = 400, contentH = 300, screenW = w, screenH = h, bars = phone)
        assertEquals(listOf(50, 636), listOf(b.x, b.y))
    }

    // ---- the bubble, dragging and resizing ----

    @Test
    fun theBubbleStaysWhereItCanBeTouched() {
        val size = 150
        assertEquals(listOf(0, 84), ScreenBars.keepBubble(0, 0, size, w, h, phone).let { listOf(it.x, it.y) })
        assertEquals(listOf(w - 126 - size, 500), ScreenBars.keepBubble(2300, 500, size, w, h, emulator).let { listOf(it.x, it.y) })
        // dragged into the bottom-right corner: 2340 - 150 across, and above the bar, 1080 - 144 - 150 down
        assertEquals(listOf(2190, 786), ScreenBars.keepBubble(2300, 1000, size, w, h, phone).let { listOf(it.x, it.y) })
    }

    @Test
    fun aDraggedWindowStopsAtTheBars() {
        // dragged up past the status bar and right past the edge: 800 × 500 window
        val spot = ScreenBars.drag(3000, -50, 800, 500, w, h, phone, moveY = true)
        assertEquals(listOf(w - 800, 84), listOf(spot.x, spot.y))
        // a window taller than the space between the bars can't avoid both: it goes to the top of the screen
        assertEquals(0, ScreenBars.drag(0, 300, 800, 1000, w, h, phone, moveY = true).y)
        // windows that only move sideways keep their height where it is
        assertEquals(300, ScreenBars.drag(0, 300, 800, 500, w, h, phone, moveY = false).y)
    }

    @Test
    fun aWindowCanBeResizedUpToTheBars() {
        // at (100, 84): up to 2240 wide and 852 tall; never below the smallest size
        assertEquals(listOf(2240, 852), ScreenBars.resize(5000, 5000, 100, 84, 150, 140, w, h, phone).let { listOf(it.w, it.h) })
        assertEquals(listOf(150, 140), ScreenBars.resize(10, 10, 100, 84, 150, 140, w, h, phone).let { listOf(it.w, it.h) })
    }
}
