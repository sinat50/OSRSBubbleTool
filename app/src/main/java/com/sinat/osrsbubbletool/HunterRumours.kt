package com.sinat.osrsbubbletool

// Hunters' Rumours: which guild hunter gives which rumour, where the creature lives, how to get
// there and what to bring. From the OSRS Wiki's "Hunters' Rumours" and "Hunters' Rumours/Strategies" pages
// (oldschool.runescape.wiki), shortened and reformatted. That wiki content is used under, and this adapted
// data is shared under, CC BY-NC-SA 3.0 (creativecommons.org/licenses/by-nc-sa/3.0).
object HunterRumours {

    // The guild hunters. Gilman (Novice) can give every rumour.
    enum class Hunter(val label: String, val tier: String, val level: Int) {
        GILMAN("Huntmaster Gilman", "Novice", 46),
        CERVUS("Guild Hunter Cervus", "Adept", 57),
        ORNUS("Guild Hunter Ornus", "Adept", 57),
        ACO("Guild Hunter Aco", "Expert", 72),
        TECO("Guild Hunter Teco", "Expert", 72),
        WOLF("Guild Hunter Wolf", "Master", 91),
    }

    val TIERS = listOf("Novice", "Adept", "Expert", "Master")
    fun huntersFor(tier: String) = Hunter.values().filter { it.tier == tier }

    class Place(val name: String, val travel: List<String>, val note: String? = null)

    class Method(val name: String, val required: List<String>, val useful: List<String>, val usesLogs: Boolean = false)

    class Rumour(
        val name: String,
        val level: Int,
        val method: Method,
        val hunters: Set<Hunter>,
        val places: List<Place>,
        val requires: String? = null,
        val note: String? = null
    )

    // ---------------- What to bring, by hunting method ----------------

    private const val AXE = "Any axe, to cut logs at the hunting spot"
    private const val KANDARIN = "Kandarin headgear (any tier): double logs from normal trees"

    val BOX_TRAP = Method("Box trap",
        listOf("Box traps (one for each trap you can lay)"),
        listOf("Knife with teak logs or celastrus bark, for tick manipulation", "Hunter's spear or a ranged weapon, to deal with strays"))
    val BUTTERFLY = Method("Butterfly net",
        listOf("Butterfly net or magic butterfly net (not needed once you're 10+ levels above the creature)"),
        listOf("Butterfly jars, only if you want to keep them"))
    val MOTH = Method("Bare-handed",
        listOf("Nothing: moth rumours are only given once you can catch them bare-handed"),
        listOf("Butterfly jars, only if you want to keep them"))
    val DEADFALL = Method("Deadfall",
        listOf("Knife or fletching knife", "Logs (any kind)", AXE),
        listOf(KANDARIN), usesLogs = true)
    val FALCONRY = Method("Falconry",
        listOf("500 coins each time, or 500,000 coins once for permanent access"),
        emptyList())
    val NET_TRAP = Method("Net trap",
        listOf("Ropes and small fishing nets (one of each for every trap you can lay)"),
        emptyList())
    val SPIKED_PIT = Method("Spiked pit",
        listOf("Knife or fletching knife", "Logs (any kind)", AXE, "Teasing stick or hunter's spear"),
        listOf(KANDARIN, "Tinderbox to cook food, and hunter gear if the damage is a problem",
            "Chisel, to turn antelope antlers into stackable bolts"), usesLogs = true)
    val BIRD_SNARE = Method("Bird snare",
        listOf("Bird snares (one for each trap you can lay)"),
        listOf("Knife with teak logs or celastrus bark, for tick manipulation"))
    val TRACKING_KEBBIT = Method("Tracking",
        listOf("Noose wand"),
        listOf("Ring of pursuit, to speed up tracking", "Chisel, to turn spikes into stackable bolts"))
    val TRACKING_HERBIBOAR = Method("Tracking",
        listOf("Nothing"),
        listOf("Magic secateurs, for extra herbs", "Herb sack or silklined herb sack"))
    val GOAT_PIT = Method("Goat pit",
        listOf("Cattleprod (or the Telekinetic Grab or Dark Lure spell)"),
        emptyList())

    // Useful for every rumour
    val ALWAYS_USEFUL = listOf(
        "Guild hunter outfit: better catch rate and rare-part chance (+5% for the full set)",
        "Quetzal whistle: back to the guild, and check your current rumour",
        "Hunter cape or max cape: teleport to the guild",
        "Huntsman's kit, meat pouch and fur pouch: save inventory space",
        "Graceful or other weight-reducing clothing, or a ring of endurance: save run energy"
    )

    // ---------------- Where the creatures live ----------------

    private val FELDIP = Place("Feldip Hunter area", listOf("Feldip hills teleport scroll", "Fairy ring AKS"))
    private val PISCATORIS = Place("Piscatoris Hunter area", listOf("Piscatoris teleport scroll", "Fairy ring AKQ", "Western banner 4"))
    private val PISCATORIS_FALCONRY = Place("Piscatoris falconry area", listOf("Piscatoris teleport scroll", "Fairy ring AKQ, then run east", "Western banner 3 or 4"))
    private val RELLEKKA = Place("Rellekka Hunter area", listOf("Fairy ring DKS"))
    private val FARMING_GUILD = Place("Farming Guild", listOf("Skills necklace", "Farming cape or max cape", "Fairy ring CIR", "Spirit tree", "Lovakengj minecart"))
    private val MONS_GRATIA = Place("Mons Gratia", listOf("Quetzal to Quetzacalli Gorge"))
    private val TLATI = Place("Tlati Rainforest", listOf("Quetzal to Tal Teklan or Kastori", "Pendant of Ates to Kastori"))
    private val GREAT_CONCH = Place("The Great Conch", listOf("Fairy ring CJQ", "Charter ship"), "Needs Troubled Tortugans started")
    private val AVIUM = Place("Avium Savannah", listOf("Fairy ring AJP", "Quetzal to Outer Fortis", "Walk from the Hunter Guild"))
    private val BURROW = Place("The Burrow", listOf("Under the Hunter Guild: Hunter cape, max cape or quetzal whistle to the guild"))
    private val NEYPOTZLI = Place("Neypotzli", listOf("Calcified moth", "Quetzal to Cam Torum, then run north"))

    val ALL: List<Rumour> = listOf(
        Rumour("Tropical wagtail", 19, BIRD_SNARE, setOf(Hunter.GILMAN), listOf(FELDIP, GREAT_CONCH, TLATI)),
        Rumour("Wild kebbit", 23, DEADFALL, setOf(Hunter.GILMAN), listOf(PISCATORIS,
            Place("Auburnvale", listOf("Quetzal to Auburnvale", "Fairy ring AIS", "Pendant of Ates to Nemus Retreat")))),
        Rumour("Sapphire glacialis", 25, BUTTERFLY, setOf(Hunter.GILMAN), listOf(RELLEKKA, FARMING_GUILD, MONS_GRATIA)),
        Rumour("Swamp lizard", 29, NET_TRAP, setOf(Hunter.GILMAN, Hunter.CERVUS), listOf(
            Place("Canifis Hunter area", listOf("Kharyrll Teleport", "Fairy ring ALQ", "Ectophial")),
            Place("North-west of Slepe", listOf("Spider cave teleport scroll", "Hallowed crystal shard"))),
            requires = "Priest in Peril"),
        Rumour("Spined larupia", 31, SPIKED_PIT, setOf(Hunter.GILMAN, Hunter.ORNUS), listOf(FELDIP)),
        Rumour("Barb-tailed kebbit", 33, DEADFALL, setOf(Hunter.GILMAN), listOf(FELDIP)),
        Rumour("Snowy knight", 35, BUTTERFLY, setOf(Hunter.GILMAN, Hunter.ORNUS), listOf(RELLEKKA,
            Place("Weiss", listOf("Icy basalt", "Fairy ring DKS, then Larry's boat"), "Needs Making Friends with My Arm"),
            FARMING_GUILD, MONS_GRATIA)),
        Rumour("Prickly kebbit", 37, DEADFALL, setOf(Hunter.GILMAN), listOf(PISCATORIS)),
        Rumour("Embertailed jerboa", 39, BOX_TRAP, setOf(Hunter.GILMAN, Hunter.ORNUS), listOf(
            Place("West of the Hunter Guild", listOf("Hunter cape, max cape or quetzal whistle to the guild, then walk west")),
            Place("North-west of the Locus Oasis", listOf("Fairy ring AJP"))),
            requires = "Eagles' Peak"),
        Rumour("Horned graahk", 41, SPIKED_PIT, setOf(Hunter.GILMAN, Hunter.CERVUS), listOf(
            Place("Karamja Hunter area", listOf("Fairy ring CKR", "Tai bwo wannai teleport scroll, then walk south")))),
        Rumour("Spotted kebbit", 43, FALCONRY, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ORNUS), listOf(PISCATORIS_FALCONRY)),
        Rumour("Black warlock", 45, BUTTERFLY, setOf(Hunter.GILMAN, Hunter.CERVUS), listOf(FELDIP, FARMING_GUILD, TLATI,
            Place("Crypt of Tonali hunter area", listOf("Pendant of Ates to Kastori", "Quetzal to Tal Teklan or Kastori")),
            Place("Shimmering Atoll", listOf("Sail there and moor")))),
        Rumour("Orange salamander", 47, NET_TRAP, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ORNUS, Hunter.ACO), listOf(
            Place("Uzer Hunter area", listOf("Fairy ring DLQ")),
            GREAT_CONCH,
            Place("Necropolis Hunter area", listOf("Fairy ring AKP", "Pharaoh's sceptre to Jaltevas")))),
        Rumour("Razor-backed kebbit", 49, TRACKING_KEBBIT, setOf(Hunter.GILMAN, Hunter.CERVUS), listOf(PISCATORIS)),
        Rumour("Sabre-toothed kebbit", 51, DEADFALL, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ORNUS, Hunter.ACO, Hunter.TECO), listOf(RELLEKKA)),
        Rumour("Grey chinchompa", 53, BOX_TRAP, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ACO, Hunter.TECO), listOf(PISCATORIS,
            Place("Kourend Woodland", listOf("Rada's blessing", "Lovakengj minecart")),
            Place("Isle of Souls", listOf("Fairy ring BJP"))),
            requires = "Eagles' Peak"),
        Rumour("Sabre-toothed kyatt", 55, SPIKED_PIT, setOf(Hunter.GILMAN, Hunter.ORNUS, Hunter.ACO, Hunter.TECO), listOf(RELLEKKA)),
        Rumour("Dark kebbit", 57, FALCONRY, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ACO, Hunter.TECO), listOf(PISCATORIS_FALCONRY)),
        Rumour("Pyre fox", 57, DEADFALL, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.ORNUS), listOf(
            Place("Avium Savannah", listOf("Fairy ring AJP", "Walk south-east from the Hunter Guild")))),
        Rumour("Red salamander", 59, NET_TRAP, setOf(Hunter.GILMAN, Hunter.ORNUS, Hunter.ACO, Hunter.TECO, Hunter.WOLF), listOf(
            Place("Ourania Hunter area", listOf("Ourania Teleport (Lunar)", "Spirit tree to the Battlefield, then walk west", "Ardougne cloak to the Monastery, then walk west")),
            Place("Charred Island", listOf("Teleport to Boat", "Sail south-west from Red Rock"), "Needs 60 Sailing to moor"))),
        Rumour("Wyrmscraig goat", 60, GOAT_PIT, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.TECO), listOf(
            Place("Wyrmscraig", listOf("Necklace of passage (after Fallen From Grace)", "Slayer ring (after Fallen From Grace)", "Sail there and dock (62 Sailing)"))),
            requires = "Sheep Herder, 62 Sailing"),
        Rumour("Red chinchompa", 63, BOX_TRAP, Hunter.values().toSet(), listOf(
            Place("Red chinchompa hunting ground", listOf("Feldip hills teleport scroll, then run south-west", "Fairy ring AKS, then run south-west"), "Needs the Hard Western Provinces Diary"),
            Place("Gwenith Hunter area", listOf("Spirit tree", "Teleport crystal")),
            FELDIP, TLATI, GREAT_CONCH,
            Place("Chinchompa Island", listOf("Teleport to Boat"))),
            requires = "Eagles' Peak"),
        Rumour("Dashing kebbit", 69, FALCONRY, setOf(Hunter.GILMAN, Hunter.ACO, Hunter.TECO, Hunter.WOLF), listOf(PISCATORIS_FALCONRY)),
        Rumour("Sunlight antelope", 72, SPIKED_PIT, setOf(Hunter.GILMAN, Hunter.ACO, Hunter.TECO, Hunter.WOLF), listOf(
            Place("Avium Savannah", listOf("Quetzal to Outer Fortis", "Fairy ring AJP")))),
        Rumour("Sunlight moth", 75, MOTH, setOf(Hunter.GILMAN, Hunter.CERVUS, Hunter.TECO), listOf(
            Place("Avium Savannah", listOf("Right outside the Hunter Guild")), NEYPOTZLI)),
        Rumour("Tecu salamander", 79, NET_TRAP, setOf(Hunter.GILMAN, Hunter.ACO, Hunter.WOLF), listOf(
            Place("Ralos' Rise", listOf("Quetzal to the Cam Torum entrance", "Pendant of Ates to Ralos' Rise", "Calcified moth")))),
        Rumour("Herbiboar", 80, TRACKING_HERBIBOAR, setOf(Hunter.GILMAN, Hunter.TECO, Hunter.WOLF), listOf(
            Place("Mushroom Forest, Fossil Island", listOf("Digsite pendant to Fossil Island, then the magic mushtree", "Digsite teleport scroll"))),
            requires = "Bone Voyage, 31 Herblore"),
        Rumour("Moonlight moth", 85, MOTH, setOf(Hunter.GILMAN, Hunter.ACO, Hunter.WOLF), listOf(BURROW, NEYPOTZLI,
            Place("Ruins of Tapoyauik", listOf("Pendant of Ates to the Twilight Temple")),
            Place("Tonali Cavern", listOf("Pendant of Ates to Kastori", "Quetzal to Tal Teklan or Kastori")))),
        Rumour("Moonlight antelope", 91, SPIKED_PIT, setOf(Hunter.GILMAN, Hunter.WOLF), listOf(BURROW)),
    )

    fun byName(name: String) = ALL.firstOrNull { it.name == name }

    // How many traps you can lay at a Hunter level (one more in the Wilderness)
    fun trapsAt(level: Int) = 1 + (if (level >= 20) 1 else 0) + (if (level >= 40) 1 else 0) +
        (if (level >= 60) 1 else 0) + (if (level >= 80) 1 else 0)
}
