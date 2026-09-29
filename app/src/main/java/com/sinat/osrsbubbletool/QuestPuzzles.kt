/*
 * Quest puzzle solutions, taken from the Quest Helper RuneLite plugin
 * (github.com/Zoinkwiz/quest-helper), used under the BSD 2-Clause License. The copyright
 * notices are listed at the top of QuestGuides.kt, and the licence text is at the top of
 * QuestHelperTool.kt and on the app's Legal screen.
 *
 * These are the puzzles whose answer is the same for every player. Most are text; a few are
 * drawn as simple maps by PuzzleDiagramView.
 */
package com.sinat.osrsbubbletool

object QuestPuzzles {

    // ---------------- What a solution is made of ----------------

    class Puzzle(
        val quest: String,
        val title: String,
        val intro: String = "",
        val steps: List<String> = emptyList(),              // do these in order (with a Done button)
        val table: List<Pair<String, String>> = emptyList(),
        val tableHeader: Pair<String, String>? = null,
        val diagram: Diagram? = null,                       // a map drawn for each step
        val outro: String? = null,
        val section: String? = null,                        // shown on guide steps in this section…
        val stepText: String? = null,                       // …or on steps containing this text
        val solver: String? = null,                         // you enter what you see (QuestSolverViews)
        val link: Pair<String, String>? = null,             // a button that opens a wiki page in the Wiki window
        val image: String? = null                           // a wiki picture, downloaded once and kept
    ) {
        val key: String get() = "puzzle_${quest}_$title"
    }

    sealed class Diagram {
        class Light(val puzzle: LightPuzzle) : Diagram()
        class Tiles(val steps: List<TileStep>, val kind: String) : Diagram()   // kind: "rubble" or "trees"
        class Marked(val cols: Int, val rows: Int, val marked: Set<Int>) : Diagram()
        // A grid of power-line tiles: each cell lists the edges its line touches (N, E, S, W);
        // a lower-case "t" marks a power terminal (the square block the line starts from).
        class Power(val rows: List<List<String>>) : Diagram()
        object PipeMachine : Diagram()
        // A grid of tiles, some lit, with numbered tiles to click in order
        class Pattern(val cols: Int, val rows: Int, val lit: Set<Int>, val clicks: Map<Int, Int>) : Diagram()
        // Symbols in a row (each drawn from a small bitmap)
        class Glyphs(val data: String) : Diagram()
    }

    class Pillar(val floor: Int, val col: Char, val row: Int)

    // Where a pillar is in the game (pillars are 14 tiles apart, column A / row 0 in the south-west)
    fun pillarX(col: Char) = 2567 + 14 * (col - 'A')
    fun pillarY(row: Int) = 6088 + 14 * row

    // Things in the library that help you find your way (game coordinates, from the plugin)
    class Landmark(val floor: Int, val x: Int, val y: Int, val kind: String, val label: String)

    val SOTE_LANDMARKS = listOf(
        Landmark(0, 2626, 6153, "stairs", "Stairs"), Landmark(1, 2626, 6153, "stairs", "Stairs"),
        Landmark(1, 2634, 6166, "stairs", "Stairs"), Landmark(2, 2634, 6166, "stairs", "Stairs"),
        Landmark(1, 2581, 6203, "stairs", "Stairs"), Landmark(2, 2581, 6203, "stairs", "Stairs"),
        Landmark(1, 2584, 6137, "stairs", "Stairs"), Landmark(2, 2584, 6137, "stairs", "Stairs"),
        Landmark(1, 2682, 6144, "stairs", "Stairs"), Landmark(2, 2682, 6144, "stairs", "Stairs"),
        Landmark(1, 2623, 6118, "dispenser", "Dispenser"),
        Landmark(1, 2623, 6088, "exit", "Exit"),
        Landmark(2, 2598, 6173, "handhold", "Handholds"), Landmark(2, 2648, 6101, "handhold", "Handholds"),
        Landmark(1, 2623, 6135, "seal", "Seal of the Forgotten"),
        Landmark(0, 2623, 6097, "seal", "Seal of Cadarn"),
        Landmark(0, 2614, 6158, "seal", "Seal of Amlodd"),
        Landmark(1, 2576, 6172, "seal", "Seal of Crwys"),
        Landmark(1, 2651, 6167, "seal", "Seal of Iorwerth"),
        Landmark(1, 2676, 6102, "seal", "Seal of Ithell"),
        Landmark(2, 2562, 6130, "seal", "Seal of Hefin"),
        Landmark(2, 2581, 6163, "seal", "Seal of Meilyr"),
        Landmark(2, 2646, 6144, "seal", "Seal of Trahaearn")
    )
    class LightStep(val floor: Int, val col: Char, val row: Int, val item: String, val dir: String?, val text: String)
    class LightPuzzle(val clan: String, val intro: String, val steps: List<LightStep>)
    class TileStep(val x: Int, val y: Int, val action: String, val side: String) {
        val text: String get() = when (action) {
            "mine" -> "Mine the rubble from the $side side."
            "climb" -> "Climb over the tree from the $side side."
            else -> "Chop the tree from the $side side."
        }
    }

    fun forQuest(quest: String): List<Puzzle> = ALL.filter { it.quest == quest }

    // The solutions to show on a guide step
    fun forStep(quest: String, section: String, stepText: String): List<Puzzle> = forQuest(quest).filter { p ->
        (p.section != null && section.equals(p.section, ignoreCase = true)) ||
            (p.stepText != null && p.stepText.split("|").any { stepText.contains(it, ignoreCase = true) })
    }

    private fun light(p: LightPuzzle) = Puzzle(
        quest = "Song of the Elves",
        title = "Light puzzle: ${p.clan}",
        intro = p.intro + " The map shows the floor you're on, with north up: the square with the arrow is a mirror " +
            "(point the light the way the arrow shows), a diamond is a crystal, and the yellow line is the light. " +
            "Faint numbered outlines are pieces still to come on that floor. Your target is the Seal of ${p.clan}.",
        steps = p.steps.map { it.text },
        diagram = Diagram.Light(p)
    )

    private fun rubble(n: Int, steps: List<TileStep>) = Puzzle(
        quest = "The Curse of Arrav",
        title = "Trollweiss cave rubble ($n of 4)",
        intro = "Mine the rubble with a pickaxe, standing on the side shown. Each pile can only be mined once " +
            "from each side, so the order matters. Brown squares are rubble still standing; the blue dot is where to stand.",
        steps = steps.map { it.text },
        diagram = Diagram.Tiles(steps, "rubble"),
        section = "Fort Invasion"
    )

    // ---------------- Screenshots that would make a guide better ----------------

    // A puzzle the app could solve by itself (reading the screen) with real phone screenshots
    class ScreenshotRequest(val puzzle: String, val better: String, val needed: List<String>)

    val GITHUB_REPO = "https://github.com/sinat50/OSRSBubbleTool"

    val HELP_WANTED: Map<String, List<ScreenshotRequest>> = mapOf(
        "Icthlarin's Little Helper" to listOf(ScreenshotRequest("Door tiles",
            "The app could read the tiles you get and show which to click, with no resetting.",
            listOf("The door puzzle just opened, in 3 or 4 different starting patterns (reset with the golden bird)",
                "The puzzle just after clicking one tile (say which tile you clicked)"))),
        "Dragon Slayer II" to listOf(ScreenshotRequest("Map puzzle",
            "The app could read where each piece is and show the next swap or turn.",
            listOf("The map puzzle just opened, before moving anything", "A couple partway through", "The solved map"))),
        "Scrambled!" to listOf(ScreenshotRequest("Egg jigsaw",
            "The app could show where the selected piece goes and how many times to turn it.",
            listOf("The jigsaw just opened", "A few with some pieces placed", "The finished egg"))),
        "The Forsaken Tower" to listOf(ScreenshotRequest("Power grid",
            "The app could show only the squares that still need turning, and how many clicks each.",
            listOf("The power grid just opened", "After turning a couple of squares", "The solved grid"))),
        "Sins of the Father" to listOf(ScreenshotRequest("Tomb door grid",
            "The app could read the numbers itself and show the squares to press.",
            listOf("The grid just opened, from 2 or 3 different attempts (the numbers change)"))),
        "The Blood Moon Rises" to listOf(ScreenshotRequest("Gilded bookcase",
            "The app could read the book order and show the next swap.",
            listOf("The shuffled bookcase just opened", "The shelf after one swap"))),
        "Desert Treasure II - The Fallen Empire" to listOf(
            ScreenshotRequest("Golem charges", "The app could show which charge to drag to which square.",
                listOf("The golem puzzle just opened", "The solved grid")),
            ScreenshotRequest("Luminescent growths", "We'd learn whether the game hints at the order, to make the tracker smarter.",
                listOf("A few screenshots while repairing the four growths, including any message or light change"))),
        "King's Ransom" to listOf(ScreenshotRequest("Cell door lock",
            "The app could spot the green circles itself and say which tumblers to raise.",
            listOf("The lock after a try, with the tumblers showing a mix of green, red and blue circles"))),
        "The Heart of Darkness" to listOf(ScreenshotRequest("Chest code",
            "The app could find the white letters in the book for you.",
            listOf("Each page of the book (especially pages with white letters)", "The chest's letter dials"))),
        "The Eyes of Glouphrie" to listOf(ScreenshotRequest("Disc machine",
            "The app could read the targets and your discs, and pick discs you actually have.",
            listOf("The machine's front panel and the three puzzles inside", "The exchanger", "Your inventory holding some discs"))),
        "The Path of Glouphrie" to listOf(ScreenshotRequest("Yewnock's machine",
            "The app could read the target discs and your discs, and pick discs you actually have.",
            listOf("Yewnock's machine open", "The exchanger", "Your inventory holding some discs"))),
        "Tower of Life" to listOf(ScreenshotRequest("Pipe machine and cage",
            "The app could show the next piece or bar to place, and confirm which cage side is which.",
            listOf("The pipe machine at the start and when finished", "Each of the 4 sides of the cage")))
    )

    // A GitHub "new issue" link with the form filled in, ready for screenshots to be attached
    fun helpIssueUrl(quest: String, appVersion: String): String {
        val requests = HELP_WANTED[quest].orEmpty()
        val body = buildString {
            append("**Quest:** $quest\n**App version:** $appVersion\n**Phone:** (your phone model)\n\n")
            append("### Screenshots wanted\n")
            requests.forEach { r ->
                append("**${r.puzzle}**\n")
                r.needed.forEach { append("- [ ] $it\n") }
                append("\n")
            }
            append("### Your screenshots\n")
            append("Attach them below (tap the box, then \"Attach files\" or the picture icon). ")
            append("Say which puzzle and which moment each one shows.\n\n")
        }
        fun enc(t: String) = java.net.URLEncoder.encode(t, "UTF-8").replace("+", "%20")
        return "$GITHUB_REPO/issues/new?title=" + enc("Screenshots: $quest") + "&body=" + enc(body)
    }

    // ---------------- Every solution ----------------

    val ALL: List<Puzzle> by lazy { listOf(
        Puzzle("Animal Magnetism", "Research notes",
            intro = "Translate the notes by turning off every green orb except the 2nd, 5th and 9th (counting from the left).",
            steps = listOf("Turn off orbs 1, 3, 4, 6, 7 and 8. Leave 2, 5 and 9 on."),
            stepText = "Translate the research notes"),

        Puzzle("Fallen From Grace", "Arrow chest",
            intro = "The chest in the north-west of the northern room.",
            steps = listOf("Press Right.", "Press Left.", "Press Down.", "Press Down.", "Press Confirm.")),

        Puzzle("Secrets of the North", "North chest code",
            intro = "Open the north chest with the code 7402."),

        Puzzle("The Blood Moon Rises", "Grandfather clocks",
            intro = "Set the hands on the two grandfather clocks in Castle Drakan:",
            table = listOf("Western clock" to "Big hand on 11, small hand on 9", "Eastern clock" to "Big hand on 12, small hand on 4"),
            section = "Infiltrating Castle Drakan"),

        Puzzle("The Blood Moon Rises", "Gilded bookcase",
            intro = "Use the gilded book on the western gilded bookcase and inspect the books. Swap them until the " +
                "symbols (the Vampyric Houses, in alphabetical order) read like this from left to right:",
            diagram = Diagram.Glyphs(BOOKCASE_GLYPHS),
            outro = "Then go through the secret passage to the south."),

        Puzzle("The Blood Moon Rises", "Tangle of trees",
            intro = "Take the axe from the stump at the start. A tree can be chopped once from each side; after " +
                "two chops it's a stump you climb over. Green squares are trees, brown ones are stumps, and the blue dot is where to stand.",
            steps = BLOOD_MOON_TREES.map { it.text },
            diagram = Diagram.Tiles(BLOOD_MOON_TREES, "trees"),
            outro = "Then turn off run and go through the darkwood trees to the north-east.",
            stepText = "tangle of trees"),

        Puzzle("The Forsaken Tower", "Pylon discs",
            intro = "Move the energy discs between the west (W), centre (C) and east (E) pylons. All four start on the west pylon and end on the centre one.",
            steps = listOf("W → E", "W → C", "E → C", "W → E", "C → W", "C → E", "W → E", "W → C",
                "E → C", "E → W", "C → W", "E → C", "W → E", "W → C", "E → C")),

        Puzzle("The Forsaken Tower", "Coolant jugs",
            intro = "Get a tinderbox from the cupboard on the north wall and both jugs from the cupboard in the south-west (\"Take both.\").",
            steps = listOf(
                "Fill the 5-gallon jug at the coolant dispenser and pour it into the 8-gallon jug.",
                "Fill the 5-gallon jug again and pour it into the 8-gallon jug (2 gallons stay in the 5).",
                "Empty the 8-gallon jug.",
                "Pour the 5-gallon jug into the 8-gallon jug (the 8 now has 2).",
                "Fill the 5-gallon jug and pour it into the 8-gallon jug (the 8 now has 7).",
                "Fill the 5-gallon jug and pour it into the 8-gallon jug. 4 gallons stay in the 5-gallon jug.",
                "Use the 5-gallon jug on the furnace coolant, then light the furnace with the tinderbox.")),

        Puzzle("The Fremennik Trials", "Peer's door",
            intro = "Peer's riddle starts \"My first is in…\". Find the word it starts with and set the lock to its answer:",
            tableHeader = "Riddle starts with" to "Answer",
            table = listOf("mage" to "MIND", "tar" to "TREE", "well" to "LIFE", "fish" to "FIRE", "water" to "TIME", "wizard" to "WIND"),
            section = "Peer's task"),

        Puzzle("Recruitment Drive", "Sir Ren Itchood's lock",
            intro = "Ask Sir Ren for his clue. The answer is the first letter of each sentence of the clue. It's always " +
                "one of: TIME, FISH, RAIN, BITE, MEAT or LAST."),

        Puzzle("Tribal Totem", "Door combination",
            intro = "Set the four letters to K U R T, then press Enter.",
            stepText = "password for the door"),

        Puzzle("While Guthix Sleeps", "Herblore statues",
            intro = "Each statue is a potion. Use its herb and secondary on it (get them from the druid spirits with the druid pouch):",
            tableHeader = "Statue" to "Ingredients",
            table = listOf(
                "Agility" to "Toadflax, Toad's legs", "Attack" to "Guam leaf, Eye of newt",
                "Balance" to "Harralander, Red spider's eggs, Garlic, Silver dust", "Combat" to "Harralander, Goat horn dust",
                "Defence" to "Ranarr weed, White berries", "Energy" to "Harralander, Chocolate dust",
                "Fishing" to "Avantoe, Snape grass", "Hunter" to "Avantoe, Kebbit teeth dust",
                "Magic" to "Lantadyme, Potato cactus", "Prayer" to "Ranarr weed, Snape grass",
                "Ranged" to "Dwarf weed, Wine of Zamorak", "Restoration" to "Harralander, Red spider's eggs",
                "Strength" to "Tarromin, Limpwurt root"),
            outro = "Then put all the dolmens on the stone table in the middle."),

        Puzzle("The Path of Glouphrie", "Monolith storeroom",
            intro = "Unlock Yewnock's machine room in the Tree Gnome Village dungeon. Don't push a monolith further than it says: if you do, reset the room.",
            steps = listOf(
                "Push the southern monolith north once.",
                "Open the chest for some shapes.",
                "Push the south-west monolith north once.",
                "Push the north-west monolith east once.",
                "Open the chest for more shapes. If it gives you none, drop your shapes, click the chest, then pick them back up.",
                "Picklock the small chest for a key.",
                "Push the small monolith south once.",
                "Push the north-west monolith west once.",
                "Search the big chest for the strongroom key and crystal chime seed.",
                "Open the chest for more shapes (the same trick applies if it gives none).",
                "Inspect the singing bowl, then click it again and answer \"Yes.\" to make the crystal chime.",
                "Push the south-east monolith west once.",
                "Unlock the gate to Yewnock's machine room with the crystal chime and strongroom key.")),

        Puzzle("Tower of Life", "Pressure machine",
            intro = "Turn each pipe's valve until its hole is plugged, then until it's full.",
            steps = listOf(
                "Pipe 2: pull the left lever down, turn the valve left twice, then right until it's full.",
                "Pipe 4: pull the right lever down, turn the valve right three times and left once, then right until it's full.",
                "Pipe 3: lift the right lever up, turn the valve right twice and left once, then right until it's full.",
                "Pipe 1: lift the left lever up, turn the valve left twice, then right until it's full."),
            outro = "You'll see \"The machine is working!\"",
            stepText = "Build the Pressure Machine"),

        Puzzle("Tower of Life", "Pipe machine",
            intro = "Move and turn the five pipe pieces until the machine looks like this: all four outlets at the top " +
                "joined down to the one pipe at the bottom. The arrow buttons move the selected piece; the curved arrow turns it.",
            diagram = Diagram.PipeMachine,
            outro = "You'll see \"The machine is working!\"",
            stepText = "Build the Pipe Machine"),

        Puzzle("Tower of Life", "Cage",
            intro = "Place three bars on each side, in this order (H = horizontal, V = vertical, the number is the bar's size). Press the right arrow to move to the next side.",
            tableHeader = "Side" to "Bars",
            table = listOf("Side 1" to "H2, H2, V2", "Side 2" to "V2, H3, H2", "Side 3" to "V2, V4, H2", "Side 4" to "H4, V2, V3"),
            outro = "You'll see \"The cage is complete!\" If a side won't take its bars, your sides may be numbered in a " +
                "different order: each side takes one of these four sets.",
            stepText = "Build the cage"),

        Puzzle("The Slug Menace", "Torn page",
            intro = "Select one piece at a time. Flip each piece so it's face up and rotate it upright, then move pieces 2 and 3 " +
                "on top of piece 1 so the torn edges line up.",
            stepText = "Combine the fragments"),

        Puzzle("The Forsaken Tower", "Power grid",
            intro = "Click each square to turn it until its line matches the picture. The square blocks are where the power comes in.",
            diagram = Diagram.Power(listOf(
                listOf("St", "ES", "Wt", "ES", "EW", "Wt"),
                listOf("NE", "NEW", "EW", "NSW", "ES", "Wt"),
                listOf("St", "Et", "EW", "NSW", "NE", "SW"),
                listOf("NES", "EW", "ESW", "NEW", "ESW", "NW"),
                listOf("Nt", "Et", "NW", "ES", "NEW", "SW"),
                listOf("Et", "EW", "EW", "NEW", "Wt", "Nt"))),
            section = "Power puzzle"),

        Puzzle("Desert Treasure II - The Fallen Empire", "Golem charges",
            intro = "Drag the charges so there's one on each green square, then press the power-on button. " +
                "(The rule is one charge in every row, column and coloured section, with no two touching.)",
            diagram = Diagram.Marked(8, 8, setOf(47, 62, 5, 52, 33, 27, 10, 16)),
            section = "Learning of the Ancients"),

        rubble(1, ARRAV_RUBBLE_1), rubble(2, ARRAV_RUBBLE_2), rubble(3, ARRAV_RUBBLE_3), rubble(4, ARRAV_RUBBLE_4),

        // ---- You enter what you see, the app works it out ----
        Puzzle("The Forsaken Tower", "Potions (refinery)", solver = "potion",
            intro = "Read the old notes, then pick how \"Cleansing fluid\" appears in them."),
        Puzzle("Lunar Diplomacy", "Dice", solver = "dice",
            intro = "Each die shows one of two faces. Flip them until they add up to the Fluke's number.",
            stepText = "Solve the dice challenge"),
        Puzzle("Dragon Slayer II", "Crypt busts", solver = "crypt",
            intro = "Inspect the tomb in the south room for the story, then place the four busts.",
            section = "Kourend key piece"),
        Puzzle("Beneath Cursed Sands", "Tomb riddle", solver = "tomb", stepText = "Solve the tomb riddle"),
        Puzzle("The Curse of Arrav", "Metal door", solver = "metal_door",
            intro = "Open the chest for the code key, then open the metal doors.", section = "Hearty Heist"),
        Puzzle("The Heart of Darkness", "Chest code", solver = "hod_chest",
            intro = "The south-west chest upstairs opens with four letters hidden in the book.", section = "First Trial"),
        Puzzle("Song of the Elves", "Baxtorian's pillars", solver = "baxtorian",
            intro = "Six pillars in Baxtorian's tomb each need an item.", section = "Freeing Baxtorian"),
        Puzzle("Sins of the Father", "Tomb door grid", solver = "sins_grid",
            intro = "A marked square adds its column's number (1–5, left to right) to its row, and its row's number " +
                "(1–5, top to bottom) to its column. Enter the totals and the app finds the squares.",
            stepText = "mausoleum's door"),
        Puzzle("The Enchanted Key", "Where to dig", solver = "enchanted_key",
            intro = "Rub the key, then add where you are and what it said. Each reading narrows down where to dig. " +
                "Places are rough, so it allows for being a few squares out."),
        Puzzle("Desert Treasure II - The Fallen Empire", "Rune rift order", solver = "rift_tracker",
            intro = "The six rifts have to be activated in a hidden order, and a wrong one resets it. Nobody can work it out " +
                "in advance, so this keeps track of what you've found as you go."),
        Puzzle("Lunar Diplomacy", "Cloud tiles", solver = "cloud_tracker",
            intro = "Only a hidden path across the cloud tiles is safe, and it's found by trial and error. Mark the tiles as " +
                "you learn them so you don't forget after falling.",
            section = "Memory challenge"),
        Puzzle("Scrambled!", "Egg jigsaw",
            intro = "Hover over the puzzle-piece icon (press and hold it on mobile) to see the finished egg. Tap a piece to turn " +
                "it; once a piece is the right way round and in the right place, it stays put.",
            stepText = "put it back together"),

        Puzzle("Icthlarin's Little Helper", "Door tiles",
            intro = "Click the golden bird to reset the tiles until they look exactly like this (gold = lit), " +
                "then click the three numbered tiles in order. The door puzzle comes up twice: it works both times.",
            diagram = Diagram.Pattern(5, 5, lit = setOf(8, 9, 15, 16, 17, 20, 21, 22, 23, 24), clicks = mapOf(6 to 1, 4 to 2, 14 to 3)),
            stepText = "western door|door puzzle"),
        Puzzle("Lunar Diplomacy", "Number sequence",
            intro = "Find the sequence the Ethereal Numerator shows, then click its two answer numbers in order.",
            tableHeader = "Sequence" to "Answer",
            table = listOf("0, 1, 3, 4" to "6, 7", "1, 1, 1, 2, 1, 3, 1, 4" to "1, 5", "1, 1, 2, 2, 3" to "3, 4",
                "1, 1, 2, 3, 1, 1, 4" to "5, 1", "1, 2, 3" to "4, 5", "1, 3, 5" to "7, 9", "1, 4, 2, 5" to "3, 6",
                "1, 6, 2, 5" to "3, 4", "1, 9, 2, 8" to "3, 7", "2, 3, 5, 6" to "8, 9", "2, 6, 3, 7" to "4, 8",
                "3, 4, 2, 5" to "1, 6", "7, 3, 6, 2" to "5, 1", "8, 6, 4" to "2, 0", "9, 7, 5" to "3, 1", "9, 8, 7, 6" to "5, 4"),
            section = "Number challenge"),
        Puzzle("The Eyes of Glouphrie", "Disc machine", solver = "discs",
            intro = "A disc is worth its colour (red 1 up to violet 7) times its shape (circle 1, triangle 3, square 4, pentagon 5). " +
                "Unlock the front panel with one disc worth the green number, then fill the slots inside (1, 2 and 3 discs).",
            stepText = "Unlock the machine"),
        Puzzle("The Path of Glouphrie", "Yewnock's machine", solver = "discs",
            intro = "Put discs on the left of the machine that add up to the discs shown on the right. A disc is worth its colour " +
                "(red 1 up to violet 7) times its shape (circle 1, triangle 3, square 4, pentagon 5)."),
        Puzzle("Desert Treasure II - The Fallen Empire", "Luminescent growths", solver = "growth_tracker",
            intro = "If the four growths need doing in a hidden order, keep track of it here as you find it."),
        Puzzle("Desert Treasure II - The Fallen Empire", "Catalyst nerves",
            intro = "Destroy each form the catalyst takes with the right nerve. For combined nerves, use the two nerves on each other first:",
            tableHeader = "Catalyst form" to "Nerves to combine",
            table = listOf("Smoke" to "Fire + Air", "Soul" to "Mind + Mind", "Blood" to "Mind + Water", "Nature" to "Water + Earth",
                "Cosmic" to "Soul + Nature", "Astral" to "Cosmic + Earth", "Wrath" to "Smoke + Blood")),
        Puzzle("King's Ransom", "Cell door lock", solver = "tumblers",
            intro = "Set all four tumblers to the lowest height and try the lock. A tumbler that shows a green circle is right: " +
                "leave it. Raise the rest by one and try again. It takes at most six tries.",
            stepText = "Pick the door's lock"),
        Puzzle("The Curse of Arrav", "Coloured floor tiles",
            intro = "Cross the coloured tiles on the south side, stepping only on blue and green tiles, or only on yellow and " +
                "red tiles. Stepping on the wrong pair after you start hurts. Then pull the lever on the other side to turn them off.",
            section = "Tomb Raiding"),
        Puzzle("Dragon Slayer II", "Map puzzle",
            intro = "Click a piece to turn it, and drag pieces to swap them, until the map matches the solved picture:",
            image = "https://oldschool.runescape.wiki/images/Dragon_Slayer_II_map_puzzle_solution.png",
            stepText = "Return to Dallas with the map pieces"),

        Puzzle("Mage Arena II", "Finding the bosses", solver = "mage_arena",
            intro = "Use the enchanted symbol, then add where you are and what it said. Each reading narrows down where the boss is.",
            stepText = "Enchanted Symbol"),

        light(SOTE_CADARN), light(SOTE_CRWYS), light(SOTE_AMLODD), light(SOTE_MEILYR),
        light(SOTE_HEFIN), light(SOTE_TRAHAEARN), light(SOTE_IORWERTH)
    ) }

    // The eight house symbols on the gilded bookcase, in the solved order. Each is "width:rows", one number
    // per row (base 36), a bit for each pixel. Traced from the OSRS Wiki's solution picture.
    const val BOOKCASE_GLYPHS = "21:0,0,9tz4,5kow,2t1c,p9s,5nug,8flo,8fge,8ffi,8g7j,9vgv,9x1r,9x1j,9x1j,jr0n,jr0n,jqx3,jqx2,jpc6,jojq,lrt2,m4g6,b5dy,5lhc,2t1c,1ejk,cmo,1ko,1ks,9vek,lt7i,lt72,lt73,17kzr,166fb,13eo7,13eo7,13hu6,xv66,y0pq,y0po,ybug,j5c,18f4,129s,2h6o,251c,24n4,4a2o,49a8,4cg0,35s,9hc,6bk,0|23:0,0,0,0,35s,35s,9hc,9hc,9hc,b28,b28,b28,b28,b28,b28,b28,buo,c5c,c80,oog,ooo,oos,bx4s,ye66,1w3su,19mou,3rj1a,3rj1b,3rj0v,3rj0v,3r6dr,3qz9r,3quj3,4dbm7,4dbmn,1vfa7,26sla,2clby,2cmpo,17w8s,mh2o,b8io,5m68,c8w,4qo,4qo,4qo,4qo,4qo,1kw,1kw,1kw,0,0,0,0|17:0,0,e8,lc,74,ao,3k,5c,5c,2o,2o,b5c,b3k,nqw,nq0,nq0,nq4,npo,npq,m4u,m4u,m4m,min,min,m67,m68,m68,m74,m76,1bi7,2t4v,2t4v,1ekf,p87,m5y,m5y,9iu,9hy,9hy,9hy,9hy,9hy,9hy,9hy,9hy,9hw,9hg,6bw,6bw,iyw,iyw,cns,11xc,0,0,0|26:1ekg,1ekg,1ekg,1ekg,23uo,23uo,23uo,23uo,23uo,23uo,23uo,2i2o,2ha8,12ps,12ps,12ps,12ps,12ps,1340,2hog,2hog,2hog,5atc,4y68,akg0,l4w0,2f8qo,4ub5s,9ombk,jdbsw,13d4ow,13oge8,9unls,4x534,17mdc,l3pc,akg0,18t1c,jz6ps,jz6rg,jyvcu,7sm4j,83y8,7rb4,7qww,7qww,izgg,izgg,fh1c,fhts,11xc0,10irk,uwhs,8feo,5m9s,5m9s|22:0,0,e8,e8,e8,lc,lc,lc,lc,lc,e0w,e10,13b8,13b8,13b8,qo4,qo6,258m,258m,258m,4ydi,493a,483r,47pj,47pj,5m9q,5m9q,5m9q,2yo,2yo,2yo,2yo,2hwao,2hwcf,2hwcf,18y67,17mlb,l5by,akhq,akjg,4xyw,4x6g,2gog,2go0,2go0,123k,122o,122o,pfk,pfk,pfk,pc0,pc0,1elc,0,0|28:cn4,cn4,cn4,cn4,cn4,cn4,cn4,iyo,iyo,iyo,7jsw,7jsw,7jsw,7jsw,7myo,7myo,ivi8,ivi8,htkw,hv5s,c8w0,ypz4,y70g,y7sw,10bnk,otmo,1xs74,20lc0,20tfk,3w8sg,41sow,7stkw,i3mcg,flsyo,zl0c0,1y23g8,4aypks,26sw6m,ivbvr,3w8z5,1cvn4,40e80,40e80,2rg1s,7qi9s,5h1c0,hedc,h1q8,bf5s,xpq8,xpog,xpmo,1vf9c,1vf9c,18y68,3quio|22:0,0,0,0,avwg,b85c,1ekfz,1y81q,h0xs,9vgg,2gv4,1bhs,bw8,2z0,1hi,cj,cm9,5m8w,18y2o,18wlc,188w0,17uuo,hiwg,hiwg,h69c,h69c,h4o0,h4o0,h4gw,h4gw,h4gw,h4gw,h4gw,h4gw,5vxc,9nk,9nk,9mo,9mo,9mo,9mo,9mo,9mo,9mo,9mo,9mo,3b4,5c,5c,5c,5c,5c,5c,3k,0,0|20:0,0,0,e8,1ds,1ds,1ds,1ds,1e8,1e8,1eg,1eg,1eg,1e0,1e0,2yw,2z0,2z0,2z0,2z0,2zg,658,5y4,5y0,c9k,bvg,oik,1e6m,2sy6,5kli,5gna,b2kj,aw2r,m5b7,alpt,52k1,1k4w,5y0,c9k,c94,c8w,c8w,c8w,cg0,cg0,cg0,cg0,cjk,cjk,cjk,cjk,cjk,5xc,0,0,0"

    // ---------------- Map data from the plugin (generated) ----------------

    val SOTE_PILLARS = listOf(Pillar(0, 'A', 2), Pillar(0, 'A', 4), Pillar(0, 'A', 8), Pillar(0, 'B', 2), Pillar(0, 'B', 3), Pillar(0, 'B', 4), Pillar(0, 'B', 8), Pillar(0, 'C', 2), Pillar(0, 'C', 3), Pillar(0, 'C', 4), Pillar(0, 'C', 5), Pillar(0, 'C', 6), Pillar(0, 'C', 7), Pillar(0, 'D', 1), Pillar(0, 'D', 2), Pillar(0, 'D', 3), Pillar(0, 'D', 6), Pillar(0, 'E', 1), Pillar(0, 'E', 3), Pillar(0, 'E', 5), Pillar(0, 'E', 6), Pillar(0, 'E', 7), Pillar(0, 'E', 8), Pillar(0, 'F', 6), Pillar(0, 'F', 7), Pillar(0, 'F', 8), Pillar(0, 'G', 6), Pillar(0, 'G', 7), Pillar(0, 'G', 8), Pillar(0, 'H', 6), Pillar(0, 'H', 7), Pillar(1, 'A', 0), Pillar(1, 'A', 2), Pillar(1, 'A', 5), Pillar(1, 'A', 6), Pillar(1, 'A', 8), Pillar(1, 'B', 4), Pillar(1, 'B', 5), Pillar(1, 'B', 7), Pillar(1, 'B', 8), Pillar(1, 'C', 0), Pillar(1, 'C', 1), Pillar(1, 'C', 4), Pillar(1, 'C', 5), Pillar(1, 'C', 6), Pillar(1, 'C', 7), Pillar(1, 'D', 4), Pillar(1, 'D', 5), Pillar(1, 'D', 6), Pillar(1, 'D', 7), Pillar(1, 'E', 5), Pillar(1, 'E', 6), Pillar(1, 'F', 4), Pillar(1, 'F', 5), Pillar(1, 'F', 6), Pillar(1, 'F', 8), Pillar(1, 'G', 3), Pillar(1, 'G', 4), Pillar(1, 'G', 8), Pillar(1, 'H', 4), Pillar(1, 'H', 5), Pillar(1, 'H', 6), Pillar(1, 'H', 7), Pillar(1, 'I', 4), Pillar(1, 'I', 5), Pillar(1, 'I', 6), Pillar(1, 'I', 7), Pillar(2, 'A', 4), Pillar(2, 'B', 2), Pillar(2, 'B', 3), Pillar(2, 'B', 4), Pillar(2, 'B', 7), Pillar(2, 'B', 8), Pillar(2, 'C', 5), Pillar(2, 'C', 6), Pillar(2, 'D', 1), Pillar(2, 'D', 3), Pillar(2, 'D', 5), Pillar(2, 'D', 6), Pillar(2, 'F', 1), Pillar(2, 'F', 4), Pillar(2, 'F', 5), Pillar(2, 'F', 7), Pillar(2, 'F', 8), Pillar(2, 'G', 0), Pillar(2, 'G', 1), Pillar(2, 'G', 2), Pillar(2, 'G', 5), Pillar(2, 'H', 0), Pillar(2, 'H', 2), Pillar(2, 'H', 4), Pillar(2, 'H', 7), Pillar(2, 'H', 8), Pillar(2, 'I', 4), Pillar(2, 'I', 5), Pillar(2, 'I', 7), Pillar(2, 'I', 8))
    val SOTE_CADARN = LightPuzzle("Cadarn", "Collect 7 mirrors and a red crystal from the dispenser in the central room.", listOf(
        LightStep(1, 'D', 5, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'E', 5, "mirror", "down", "Add a mirror to a pillar to the east. Rotate it to point the light down."),
        LightStep(0, 'E', 5, "mirror", "south", "Add a mirror to the pillar near the stairs. Rotate it to point the light south."),
        LightStep(0, 'E', 3, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'D', 3, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south."),
        LightStep(0, 'D', 2, "red crystal", null, "Add the red crystal to the pillar to the south."),
        LightStep(0, 'D', 1, "mirror", "east", "Add a mirror to the pillar to the south. Rotate it to point the light east."),
        LightStep(0, 'E', 1, "mirror", "south", "Add a mirror to the pillar to the east. Rotate it to point the light south at the Seal of Cadarn.")))
    val SOTE_CRWYS = LightPuzzle("Crwys", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'D', 4, "mirror", "west", "Add a mirror to a pillar to the north. Rotate it to point the light west."),
        LightStep(1, 'C', 4, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'C', 5, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(1, 'B', 5, "fractured crystal", null, "Add a fractured crystal to the pillar to the west."),
        LightStep(1, 'A', 5, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'A', 6, "green crystal", null, "Add a green crystal to the pillar to the north."),
        LightStep(1, 'A', 8, "mirror", "down", "Run around to the north and add a mirror to the pillar there. Rotate it to point the light down."),
        LightStep(0, 'A', 8, "mirror", "east", "Add a mirror to the pillar in the north west. Rotate it to point the light east."),
        LightStep(0, 'B', 8, "mirror", "up", "Add a mirror to the pillar to the east. Rotate it to point the light up."),
        LightStep(1, 'B', 8, "cyan crystal", null, "Add the cyan crystal to the pillar next to the stairs."),
        LightStep(2, 'B', 8, "mirror", "south", "Add a mirror to the pillar next to the stairs. Rotate it to point the light south."),
        LightStep(2, 'B', 7, "mirror", "down", "Add a mirror to the pillar to the south. Rotate it to point the light down."),
        LightStep(1, 'B', 7, "mirror", "south", "Add a mirror to the pillar to the south. Rotate it to point the light south.")))
    val SOTE_AMLODD = LightPuzzle("Amlodd", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'D', 4, "fractured crystal", null, "Add a fractured crystal to the pillar to the north."),
        LightStep(1, 'C', 4, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'C', 5, "red crystal", null, "Add a red crystal to the pillar to the north."),
        LightStep(1, 'C', 6, "mirror", "east", "Add a mirror to the pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'D', 6, "mirror", "north", "Add a mirror to the pillar to the east. Rotate it to point the light north."),
        LightStep(1, 'D', 7, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(1, 'C', 7, "mirror", "down", "Add a mirror to the pillar to the west. Rotate it to point the light down."),
        LightStep(0, 'C', 7, "mirror", "south", "Add a mirror to the pillar with light coming down into it. Rotate it to point the light south."),
        LightStep(1, 'D', 5, "fractured crystal", "east", "Add a mirror to a pillar to the north of where you placed the fractured crystal. Rotate it to point the light east."),
        LightStep(1, 'E', 5, "mirror", "down", "Add a mirror to a pillar to the east. Rotate it to point the light down."),
        LightStep(0, 'E', 5, "mirror", "south", "Add a mirror to the pillar near the stairs. Rotate it to point the light south."),
        LightStep(0, 'E', 3, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'D', 3, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south."),
        LightStep(0, 'D', 2, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'C', 2, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(0, 'C', 3, "green crystal", null, "Add a green crystal to the pillar to the north."),
        LightStep(0, 'C', 4, "yellow crystal", null, "Add a yellow crystal to the pillar to the north."),
        LightStep(0, 'C', 5, "mirror", "east", "Add a mirror to the pillar to the north. Rotate it to point the light east."),
        LightStep(0, 'C', 6, "mirror", "east", "Add a mirror to the pillar to the north. Rotate it to point the light east."),
        LightStep(0, 'D', 6, "mirror", "south", "Add a mirror to the pillar to the east. Rotate it to point the light south.")))
    val SOTE_HEFIN = LightPuzzle("Hefin", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'D', 5, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'E', 5, "mirror", "down", "Add a mirror to a pillar to the east. Rotate it to point the light down."),
        LightStep(0, 'E', 5, "mirror", "south", "Add a mirror to the pillar near the stairs. Rotate it to point the light south."),
        LightStep(0, 'E', 3, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'D', 3, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south."),
        LightStep(0, 'D', 2, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'C', 2, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(0, 'C', 3, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(1, 'C', 1, "mirror", "south", "Add a mirror to a pillar to the south west. Rotate it to point the light south."),
        LightStep(1, 'C', 0, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(1, 'A', 0, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'A', 2, "mirror", "down", "Run around to the north pillar and add a mirror. Rotate it to point the light down."),
        LightStep(0, 'A', 2, "mirror", "east", "Add a mirror to the pillar to the south west. Rotate it to point the light east."),
        LightStep(0, 'B', 2, "mirror", "up", "Add a mirror to the pillar to the east. Rotate it to point the light up."),
        LightStep(0, 'B', 3, "mirror", "north", "Add a mirror to the marked pillar. Rotate it to point the light north."),
        LightStep(0, 'B', 4, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(0, 'A', 4, "mirror", "up", "Add a mirror to the pillar to the west. Rotate it to point the light up."),
        LightStep(2, 'B', 2, "mirror", "north", "Add a mirror to the pillar to the south. Rotate it to point the light north."),
        LightStep(2, 'B', 3, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'A', 4, "mirror", "south", "Add a mirror to the pillar to the north west. Rotate it to point the light south.")))
    val SOTE_MEILYR = LightPuzzle("Meilyr", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'D', 5, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'E', 5, "mirror", "down", "Add a mirror to a pillar to the east. Rotate it to point the light down."),
        LightStep(0, 'E', 5, "mirror", "south", "Add a mirror to the pillar near the stairs. Rotate it to point the light south."),
        LightStep(0, 'E', 3, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'D', 3, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south."),
        LightStep(0, 'D', 2, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(0, 'C', 2, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(0, 'C', 3, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(0, 'B', 3, "mirror", "north", "Add a mirror to the marked pillar. Rotate it to point the light north."),
        LightStep(0, 'B', 4, "mirror", "up", "Add a mirror to the pillar to the north. Rotate it to point the light up."),
        LightStep(1, 'B', 4, "red crystal", null, "Add a red crystal to the pillar to the north."),
        LightStep(2, 'B', 4, "mirror", "north", "Add a mirror to the pillar to the north. Rotate it to point the light north."),
        LightStep(1, 'F', 4, "mirror", "up", "Add a mirror to a pillar to the east. Rotate it to point the light up."),
        LightStep(2, 'F', 4, "mirror", "north", "Add a mirror to a pillar to the west. Rotate it to point the light north."),
        LightStep(2, 'F', 5, "mirror", "west", "Add a mirror to a pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'D', 5, "mirror", "north", "Add a mirror to the marked pillar. You'll need to climb across the floating books to reach it. Rotate it to point the light north."),
        LightStep(2, 'D', 6, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'C', 6, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south."),
        LightStep(2, 'C', 5, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west.")))
    val SOTE_TRAHAEARN = LightPuzzle("Trahaearn", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'C', 1, "mirror", "south", "Add a mirror to a pillar to the south west. Rotate it to point the light south."),
        LightStep(1, 'C', 0, "mirror", "west", "Add a mirror to the pillar to the south. Rotate it to point the light west."),
        LightStep(1, 'A', 0, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'A', 2, "mirror", "down", "Run around to the north pillar and add a mirror. Rotate it to point the light down."),
        LightStep(0, 'A', 2, "mirror", "east", "Add a mirror to the pillar to the south west. Rotate it to point the light east."),
        LightStep(0, 'B', 2, "mirror", "up", "Add a mirror to the pillar to the east. Rotate it to point the light up."),
        LightStep(2, 'B', 2, "mirror", "north", "Add a mirror to the pillar to the south. Rotate it to point the light north."),
        LightStep(2, 'B', 3, "mirror", "east", "Add a mirror to the pillar to the north. Rotate it to point the light east."),
        LightStep(2, 'D', 3, "mirror", "south", "Add a mirror to the pillar to the north west. Rotate it to point the light south."),
        LightStep(2, 'D', 1, "mirror", "east", "Add a mirror to the pillar to the south. Rotate it to point the light east."),
        LightStep(2, 'F', 1, "blue crystal", null, "Add a blue crystal in the pillar to the east."),
        LightStep(2, 'G', 1, "mirror", "south", "Add a mirror to the pillar to the east. Rotate it to point the light south."),
        LightStep(2, 'G', 0, "mirror", "east", "Add a mirror to the pillar to the south. Rotate it to point the light east."),
        LightStep(2, 'H', 0, "mirror", "north", "Add a mirror to the pillar to the east. Rotate it to point the light north."),
        LightStep(2, 'H', 2, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'G', 2, "mirror", "north", "Add a mirror to the pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'G', 3, "mirror", "north", "Add a mirror to the marked pillar. Rotate it to point the light north."),
        LightStep(1, 'G', 4, "mirror", "east", "Add a mirror to the pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'H', 4, "mirror", "up", "Add a mirror to the pillar to the east. Rotate it to point the light up."),
        LightStep(2, 'H', 4, "mirror", "east", "Add a mirror to the pillar to the west. Rotate it to point the light east."),
        LightStep(2, 'I', 4, "mirror", "north", "Add a mirror to the pillar to the east. Rotate it to point the light north."),
        LightStep(2, 'I', 5, "mirror", "west", "Add a mirror to the pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'G', 5, "mirror", "south", "Add a mirror to the pillar to the west. Rotate it to point the light south.")))
    val SOTE_IORWERTH = LightPuzzle("Iorwerth", "Pull the lever in the dispenser in the central room. Collect all the items from the dispenser in the central room.", listOf(
        LightStep(1, 'F', 4, "cyan crystal", null, "Add a cyan crystal to a pillar to the north east."),
        LightStep(1, 'F', 5, "blue crystal", null, "Add a blue crystal to a pillar to the north."),
        LightStep(1, 'F', 6, "mirror", "west", "Add a mirror to a pillar in the north east room. Rotate it to point the light west."),
        LightStep(1, 'E', 6, "mirror", "down", "Add a mirror to a pillar to the west. Rotate it to point the light down."),
        LightStep(0, 'E', 6, "mirror", "north", "Add a mirror to a pillar to the west. Rotate it to point the light north."),
        LightStep(0, 'E', 7, "fractured crystal", null, "Add the fractured crystal to a pillar to the north."),
        LightStep(0, 'E', 8, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(0, 'F', 8, "mirror", "up", "Add a mirror to a pillar to the east. Rotate it to point the light up."),
        LightStep(0, 'F', 7, "mirror", "south", "Add a mirror to a pillar to the south. Rotate it to point the light south."),
        LightStep(0, 'F', 6, "mirror", "east", "Add a mirror to a pillar to the south. Rotate it to point the light east."),
        LightStep(0, 'G', 6, "yellow crystal", null, "Add a yellow crystal to a pillar to the east."),
        LightStep(0, 'H', 6, "mirror", "north", "Add a mirror to a pillar to the east. Rotate it to point the light north."),
        LightStep(0, 'H', 7, "mirror", "west", "Add a mirror to a pillar to the north. Rotate it to point the light west."),
        LightStep(0, 'G', 7, "mirror", "north", "Add a mirror to a pillar to the west. Rotate it to point the light north."),
        LightStep(0, 'G', 8, "mirror", "up", "Add a mirror to a pillar to the north. Rotate it to point the light up."),
        LightStep(1, 'G', 8, "mirror", "south", "Add a mirror to a pillar in the north east room. Rotate it to point the light south."),
        LightStep(1, 'F', 8, "magenta crystal", null, "Add a magenta crystal to a pillar in the north east room."),
        LightStep(1, 'G', 3, "mirror", "north", "Add a mirror to a pillar in the east room. Rotate it to point the yellow light north."),
        LightStep(1, 'G', 4, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'H', 4, "red crystal", null, "Add a red crystal to the pillar to the east."),
        LightStep(1, 'I', 4, "mirror", "north", "Add a mirror to a pillar to the east. Rotate it to point the light north."),
        LightStep(1, 'I', 5, "mirror", "west", "Add a mirror to a pillar to the north. Rotate it to point the light west."),
        LightStep(1, 'H', 5, "mirror", "north", "Add a mirror to a pillar to the west. Rotate it to point the light north."),
        LightStep(1, 'H', 6, "mirror", "east", "Add a mirror to a pillar to the north. Rotate it to point the light east."),
        LightStep(1, 'I', 6, "mirror", "north", "Add a mirror to a pillar to the east. Rotate it to point the light north."),
        LightStep(1, 'I', 7, "mirror", "up", "Add a mirror to a pillar to the north. Rotate it to point the light up."),
        LightStep(2, 'I', 7, "mirror", "north", "Add a mirror to a pillar to the north. Rotate it to point the light north."),
        LightStep(2, 'I', 8, "mirror", "west", "Add a mirror to a pillar to the north. Rotate it to point the light west."),
        LightStep(2, 'H', 8, "mirror", "south", "Add a mirror to a pillar to the west. Rotate it to point the light south."),
        LightStep(2, 'H', 7, "mirror", "down", "Add a mirror to a pillar to the south. Rotate it to point the light down."),
        LightStep(2, 'F', 8, "mirror", "south", "Add a mirror to a pillar to the west. Rotate it to point the light south."),
        LightStep(2, 'F', 7, "mirror", "down", "Add a mirror to a pillar to the south. Rotate it to point the light down.")))
    val ARRAV_RUBBLE_1 = listOf(TileStep(2764, 10266, "mine", "south"), TileStep(2775, 10258, "mine", "south"), TileStep(2764, 10266, "mine", "east"), TileStep(2764, 10267, "mine", "south"))
    val ARRAV_RUBBLE_2 = listOf(TileStep(2766, 10279, "mine", "west"), TileStep(2766, 10280, "mine", "west"), TileStep(2767, 10281, "mine", "west"), TileStep(2766, 10279, "mine", "north"), TileStep(2766, 10278, "mine", "west"), TileStep(2766, 10278, "mine", "south"), TileStep(2766, 10279, "mine", "south"), TileStep(2767, 10278, "mine", "west"), TileStep(2767, 10279, "mine", "south"), TileStep(2767, 10279, "mine", "west"), TileStep(2768, 10279, "mine", "west"), TileStep(2768, 10280, "mine", "south"), TileStep(2768, 10281, "mine", "south"), TileStep(2769, 10281, "mine", "west"), TileStep(2767, 10281, "mine", "east"), TileStep(2767, 10282, "mine", "south"), TileStep(2769, 10281, "mine", "north"), TileStep(2770, 10281, "mine", "west"))
    val ARRAV_RUBBLE_3 = listOf(TileStep(2787, 10267, "mine", "west"), TileStep(2787, 10266, "mine", "west"), TileStep(2787, 10267, "mine", "south"), TileStep(2789, 10286, "mine", "west"), TileStep(2789, 10285, "mine", "north"), TileStep(2789, 10285, "mine", "west"), TileStep(2789, 10283, "mine", "west"), TileStep(2789, 10284, "mine", "west"), TileStep(2789, 10285, "mine", "south"), TileStep(2790, 10285, "mine", "west"), TileStep(2791, 10285, "mine", "west"), TileStep(2789, 10283, "mine", "north"), TileStep(2790, 10283, "mine", "west"), TileStep(2791, 10283, "mine", "west"), TileStep(2790, 10282, "mine", "north"), TileStep(2791, 10282, "mine", "west"), TileStep(2791, 10283, "mine", "south"), TileStep(2791, 10285, "mine", "south"), TileStep(2792, 10285, "mine", "south"), TileStep(2792, 10285, "mine", "west"), TileStep(2793, 10285, "mine", "west"), TileStep(2787, 10267, "mine", "north"))
    val ARRAV_RUBBLE_4 = listOf(TileStep(2787, 10267, "mine", "west"), TileStep(2787, 10266, "mine", "west"), TileStep(2787, 10267, "mine", "south"), TileStep(2788, 10267, "mine", "north"), TileStep(2787, 10267, "mine", "north"), TileStep(2788, 10267, "mine", "west"), TileStep(2803, 10264, "mine", "south"), TileStep(2803, 10265, "mine", "south"), TileStep(2803, 10267, "mine", "north"), TileStep(2803, 10266, "mine", "north"), TileStep(2804, 10266, "mine", "north"), TileStep(2802, 10266, "mine", "west"), TileStep(2801, 10265, "mine", "north"), TileStep(2802, 10265, "mine", "west"), TileStep(2803, 10265, "mine", "west"), TileStep(2802, 10266, "mine", "south"), TileStep(2804, 10265, "mine", "west"), TileStep(2803, 10266, "mine", "south"), TileStep(2803, 10266, "mine", "west"), TileStep(2804, 10266, "mine", "west"), TileStep(2804, 10265, "mine", "north"), TileStep(2805, 10265, "mine", "west"), TileStep(2806, 10265, "mine", "west"))
    val BLOOD_MOON_TREES = listOf(TileStep(2966, 7896, "chop", "south"), TileStep(2966, 7896, "chop", "east"), TileStep(2966, 7896, "climb", "south"), TileStep(2967, 7899, "chop", "south"), TileStep(2967, 7900, "chop", "east"), TileStep(2967, 7900, "climb", "east"), TileStep(2968, 7903, "chop", "south"), TileStep(2969, 7903, "chop", "east"), TileStep(2968, 7903, "climb", "south"), TileStep(2973, 7909, "chop", "west"), TileStep(2973, 7908, "chop", "south"), TileStep(2973, 7909, "climb", "west"), TileStep(2980, 7910, "chop", "south"), TileStep(2980, 7910, "chop", "west"), TileStep(2980, 7910, "climb", "west"), TileStep(2981, 7913, "chop", "south"), TileStep(2980, 7914, "chop", "west"), TileStep(2980, 7914, "climb", "west"), TileStep(2980, 7914, "climb", "west"), TileStep(2982, 7915, "chop", "south"), TileStep(2981, 7914, "climb", "east"), TileStep(2981, 7913, "climb", "south"), TileStep(2982, 7916, "chop", "west"), TileStep(2982, 7916, "climb", "west"), TileStep(2984, 7917, "chop", "south"), TileStep(2983, 7916, "climb", "east"), TileStep(2981, 7914, "climb", "north"), TileStep(2980, 7914, "climb", "west"), TileStep(2982, 7915, "climb", "south"), TileStep(2984, 7917, "chop", "west"), TileStep(2984, 7917, "climb", "west"))
}
