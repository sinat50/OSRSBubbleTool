package com.sinat.osrsbubbletool

import androidx.core.graphics.toColorInt

// The app's colours, in one place. Each has a light version (the parchment look) and a dark version
// (Settings → Dark mode). Screens read them as they're built, so after dark mode is switched the bubble
// builds its tool windows again (BubbleService.rebuildToolWindows) and the app's screen redraws.
// Pictures drawn like the game (puzzle maps, the Zen Garden, game icons) keep their own colours.
object Palette {

    // Set from the Dark mode setting (AppSettings.isDark) before anything is built
    @Volatile var dark = false

    private fun shade(light: String, dark: String) = intArrayOf(light.toColorInt(), dark.toColorInt())
    private fun IntArray.now() = this[if (dark) 1 else 0]

    // Backgrounds
    private val parchment = shade("#F2E3C0", "#1F1810")   // a window's or screen's background
    private val paper = shade("#FFF8E6", "#2E241A")       // boxes you type in, cards that stand out
    private val row = shade("#E3CFA2", "#3A2D1C")         // list rows and cards
    private val current = shade("#FFF4D6", "#463720")     // the step you're on, an answer
    private val cell = shade("#D8C69C", "#54452F")        // an empty square in a puzzle grid
    private val cellDim = shade("#B8A882", "#655539")     // a square not tried yet

    // Text
    private val ink = shade("#3E2C12", "#EBDDBE")         // normal text
    private val faded = shade("#8C7B5E", "#A89878")       // less important text

    // Buttons (white text on them)
    private val button = shade("#8B6B3E", "#6E5432")
    private val lightButton = shade("#B89A63", "#4E3D26") // an option that isn't picked
    // an option that is picked (Quest Helper tabs, calculator keys): darker than a button on light, lighter on dark
    private val pickedButton = shade("#5A4220", "#9A7843")

    // Used for both text and buttons, so the dark versions sit in between to read well both ways
    private val green = shade("#3E7A2E", "#4A8A36")
    private val readyGreen = shade("#2E8B2E", "#5BAA4A")
    private val red = shade("#B03A2E", "#C0503F")
    private val deepRed = shade("#A04030", "#C0503F")    // Stop and Delete buttons in Timers and Inventory Setups
    private val orange = shade("#C0600A", "#D9822E")      // "partly done", or a warning banner
    private val warn = shade("#9C4A10", "#B8642A")
    private val blue = shade("#1F4E8C", "#4F82C8")

    val PARCHMENT get() = parchment.now()
    val PAPER get() = paper.now()
    val ROW_BROWN get() = row.now()
    val CURRENT get() = current.now()
    val CELL get() = cell.now()
    val CELL_DIM get() = cellDim.now()
    val DARK_BROWN get() = ink.now()
    val FADED get() = faded.now()
    val BUTTON_BROWN get() = button.now()
    val LIGHT_BUTTON get() = lightButton.now()
    val SELECTED_BROWN get() = pickedButton.now()
    val GO_GREEN get() = green.now()
    val READY_GREEN get() = readyGreen.now()
    val RED get() = red.now()
    val DEEP_RED get() = deepRed.now()
    val ORANGE get() = orange.now()
    val WARN get() = warn.now()
    val BLUE get() = blue.now()

    // Behind a tool window's contents (seen for a moment while a web page loads)
    val WINDOW get() = if (dark) parchment[1] else android.graphics.Color.WHITE
}
