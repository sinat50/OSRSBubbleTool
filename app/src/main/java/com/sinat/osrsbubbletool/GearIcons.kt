package com.sinat.osrsbubbletool

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder

// Where the item pictures "Import my gear" compares your equipment with are kept and downloaded from.
// They're Jagex's artwork, so the app doesn't come with them: AssetDownloader gets them once and they're
// kept on the phone.
//
// Most are unpacked from the OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc),
// downloaded as one ZIP at the exact version the app's item list was made from, so they never change
// underneath it. A few that the repository has out of date (marked "wiki" in the item list) come
// straight from the OSRS Wiki instead, falling back to the repository's copy.
class GearIcons(context: Context) {

    companion object {
        const val COMMIT = "89c3e25b344aea90d0189746e4b5f73dde0f0383"
        private const val WIKI = "https://oldschool.runescape.wiki/images/"

        // A path part with spaces as %20 (URLEncoder gives "+", which is only right in a query)
        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun folder(context: Context) = File(context.filesDir, "dps_icons")
        fun file(context: Context, name: String) = File(folder(context), name.replace('/', '_'))

        // Every item picture (its name in the repository, and whether to get it from the wiki), from the
        // bundled item list. Read once.
        @Volatile private var iconList: List<Pair<String, Boolean>>? = null
        fun icons(context: Context): List<Pair<String, Boolean>> {
            iconList?.let { return it }
            val text = context.assets.open("dps/equipment.json").bufferedReader().use { it.readText() }
            val array = JSONArray(text)
            val seen = LinkedHashMap<String, Boolean>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val name = o.getString("icon")
                seen[name] = (seen[name] ?: false) || o.optBoolean("wiki", false)
            }
            return seen.toList().also { iconList = it }
        }

        // An item picture the repository has out of date, from the OSRS Wiki
        fun wikiJob(context: Context, name: String) =
            AssetDownloader.Job(WIKI + encode(name.replace(' ', '_')), file(context, name))

        // One item picture from the repository, at the same version as the ZIP. Used when only a few are
        // missing (new items in an app update), so there's no need to download the whole ZIP for them.
        fun repoJob(context: Context, name: String) = AssetDownloader.Job(
            "https://raw.githubusercontent.com/weirdgloop/osrs-dps-calc/$COMMIT/cdn/equipment/" + encode(name),
            file(context, name))
    }

    private val appContext = context.applicationContext

    fun file(name: String) = file(appContext, name)

    fun has(name: String) = file(name).isFile
}
