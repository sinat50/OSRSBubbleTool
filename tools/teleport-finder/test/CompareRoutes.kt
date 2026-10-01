// Compares the Teleport Finder's results for a list of places with two versions of the walking data, to
// check a data change before shipping it. Prints a summary and every place whose top result changed.
//
// Build and run (needs kotlinc and the org.json classes, e.g. json-20240303.jar from Maven Central):
//   kotlinc -cp json.jar CompareRoutes.kt ../../../app/src/main/java/com/sinat/osrsbubbletool/TeleportData.kt \
//       ../../../app/src/main/java/com/sinat/osrsbubbletool/WalkMap.kt -include-runtime -d compare.jar
//   java -cp compare.jar:json.jar CompareRoutesKt targets.json teleports.tsv collision.bin dungeons.txt old/walks.txt new/walks.txt
import com.sinat.osrsbubbletool.*
import java.io.File
import org.json.JSONObject

fun main(args: Array<String>) {
    val (targetsFile, teleportsFile, collisionFile, dungeonsFile) = args.take(4)
    val walkFiles = args.drop(4)
    val teleports = TeleportData.parse(File(teleportsFile).readText())
    val collision = File(collisionFile).readBytes()
    val dungeons = File(dungeonsFile).readText()
    val maps = walkFiles.map { it to WalkMap(collision, File(it).readText(), dungeons) }
    val usable = TeleportData.landingTiles(teleports)
    val targets = JSONObject(File(targetsFile).readText())
    val tops = HashMap<String, MutableList<String>>()
    val none = IntArray(maps.size); val guess = IntArray(maps.size); var spots = 0
    for (name in targets.keys()) {
        val list = targets.getJSONArray(name)
        for (i in 0 until list.length()) {
            val s = list.getJSONArray(i)
            val label = "$name (${s.getInt(0)},${s.getInt(1)},${s.getInt(2)})"
            spots++
            maps.forEachIndexed { m, (_, map) ->
                val field = map.from(s.getInt(0), s.getInt(1), s.getInt(2), usable, 70, hint = name)
                val r = TeleportData.closestOnFoot(teleports, map, field, 3).firstOrNull()
                if (r == null) none[m]++ else if (r.guess) guess[m]++
                tops.getOrPut(label) { ArrayList() }.add(r?.let { "${it.tiles} ${it.teleport.name}${if (it.guess) " (guess)" else ""}" } ?: "-")
            }
        }
    }
    maps.forEachIndexed { m, (file, _) -> println("$file: $spots places, ${none[m]} with no result, ${guess[m]} where the top result is a guess") }
    if (maps.size > 1) for ((label, t) in tops) if (t.distinct().size > 1) println("$label\n    " + t.joinToString("\n    "))
}
