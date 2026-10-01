package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.ZipFile

// Downloads the pictures the tools compare your screen with. They're Jagex's artwork, so the app doesn't
// come with them. The main screen offers to get them the first time the app opens; the tools also start
// it for whatever is still missing when you use them.
//
// 1. A few pictures come straight from the OSRS Wiki: the item pictures the DPS calculator's repository
//    has out of date (marked "wiki" in the item list) and the solved puzzle pictures for the Puzzle Box Solver.
// 2. The OSRS Wiki DPS calculator's repository (github.com/weirdgloop/osrs-dps-calc) as one ZIP file, at
//    the exact version the app's item list was made from.
// 3. The item pictures are unpacked from it for Import my gear (with the repository's copy of any the wiki
//    didn't give), then the ZIP is deleted, so only the pictures take up space.
//
// Pictures already on the phone are kept through app updates. When an update adds only a few items, their
// pictures are got one by one instead (a few KB), without the ZIP.
//
// One download at a time for the whole app: if it's already running, starting it again just listens in.
// It keeps going if you leave the screen. Anything already on the phone is skipped.
object AssetDownloader {

    // url: where to get it; file: where it's kept on the phone
    class Job(val url: String, val file: File)

    interface Listener {
        // fraction: 0 to 1, or below 0 when the size isn't known yet; line: what's happening; url: what's downloading
        fun progress(fraction: Float, line: String, url: String)   // on the main thread
        fun finished(failed: Int)                                   // on the main thread
    }

    const val ZIP_URL = "https://codeload.github.com/weirdgloop/osrs-dps-calc/zip/${GearIcons.COMMIT}"
    const val ZIP_SIZE_MB = 170   // roughly: shown in the offer before downloading
    // Up to this many missing item pictures are got one by one (each is under 1 KB) instead of from the ZIP
    private const val ONE_BY_ONE_MAX = 300
    private const val USER_AGENT = "OSRSBubbleTool/1.0 (Android app; github.com/sinat50/OSRSBubbleTool)"

    // How much of the bar each step takes
    private const val WIKI_SHARE = 0.05f
    private const val ZIP_SHARE = 0.85f
    private const val UNPACK_SHARE = 0.10f

    fun zipFile(context: Context) = File(context.filesDir, "osrs-dps-calc-${GearIcons.COMMIT.take(7)}.zip")

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var running = false
        private set
    @Volatile var fraction = 0f
        private set
    @Volatile var line = ""
        private set
    @Volatile var current = ""
        private set

    // The few pictures that come straight from the wiki
    private fun wikiJobs(context: Context): List<Job> =
        GearIcons.icons(context).filter { it.second }.map { GearIcons.wikiJob(context, it.first) } + PuzzleReferences.jobs(context)

    private fun missingIcons(context: Context) = GearIcons.icons(context).filter { !GearIcons.file(context, it.first).isFile }

    fun needed(context: Context): Boolean =
        missingIcons(context).isNotEmpty() || wikiJobs(context).any { !it.file.isFile }

    // True when what's missing is small enough to get without the ZIP (a few KB), like the new items an
    // app update adds. Then there's no need to ask first.
    fun onlyAFew(context: Context): Boolean = missingIcons(context).count { !it.second } <= ONE_BY_ONE_MAX

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // Starts getting whatever is missing (or does nothing if it's already running)
    @Synchronized
    fun start(context: Context) {
        if (running) return
        running = true
        fraction = 0f; line = "Starting..."; current = ""
        val app = context.applicationContext
        Thread { finish(try { run(app) } catch (_: Throwable) { 1 }) }.start()
    }

    private fun finish(failed: Int) {
        running = false
        main.post { listeners.forEach { it.finished(failed) } }
    }

    private var lastPost = 0L
    private fun report(f: Float, text: String, url: String, force: Boolean = false) {
        fraction = f; line = text; current = url
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPost < 100) return   // a few times a second is plenty
        lastPost = now
        main.post { listeners.forEach { it.progress(f, text, url) } }
    }

    // Returns how many things couldn't be got
    private fun run(context: Context): Int {
        val zip = zipFile(context)

        // 1. The pictures from the wiki (a handful, so first)
        val jobs = wikiJobs(context).filter { !it.file.isFile }
        val fromZipInstead = ArrayList<String>()   // item pictures the wiki didn't give: use the repository's copy
        var failed = 0
        jobs.forEachIndexed { i, job ->
            report(WIKI_SHARE * i / jobs.size, "Getting pictures from the OSRS Wiki: ${i + 1} of ${jobs.size}", job.url)
            job.file.parentFile?.mkdirs()
            if (!fetch(job.url, job.file)) {
                val item = GearIcons.icons(context).firstOrNull { GearIcons.file(context, it.first) == job.file }
                if (item != null) fromZipInstead.add(item.first) else failed++
            }
        }

        var needFromZip = missingIcons(context).filter { !it.second }.map { it.first } + fromZipInstead

        // 2. Only a few item pictures missing (new items in an app update): get them one by one from the
        //    repository instead of downloading the whole ZIP. Any that can't be got this way use the ZIP after all.
        if (needFromZip.isNotEmpty() && needFromZip.size <= ONE_BY_ONE_MAX) {
            GearIcons.folder(context).mkdirs()
            val stillMissing = ArrayList<String>()
            needFromZip.forEachIndexed { i, name ->
                val job = GearIcons.repoJob(context, name)
                report(WIKI_SHARE + (1 - WIKI_SHARE) * i / needFromZip.size,
                    "Getting new item pictures: ${i + 1} of ${needFromZip.size}", job.url)
                if (!fetch(job.url, job.file)) stillMissing.add(name)
            }
            needFromZip = stillMissing
        }

        // 3. The repository's ZIP file, only if there are item pictures still to get
        if (needFromZip.isNotEmpty()) {
            if (!zip.isFile && !downloadZip(zip)) return failed + 1

            // Unpack them, then delete the ZIP: only the pictures are kept
            val ok = try { unpack(zip, needFromZip, context) } catch (_: Exception) { false }
            zip.delete()   // done with it (or damaged: it's downloaded again next time)
            if (!ok) return failed + 1
        }
        zip.delete()   // a ZIP left over from an earlier version of the app
        report(1f, "Done", "", force = true)
        return failed
    }

    private fun downloadZip(zip: File): Boolean {
        val tmp = File(zip.path + ".part")
        report(-1f, "Downloading the DPS calculator's files...", ZIP_URL, force = true)
        return try {
            val c = URL(ZIP_URL).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            if (c.responseCode != 200) { c.disconnect(); return false }
            val size = c.contentLengthLong   // GitHub doesn't always say
            var got = 0L
            c.inputStream.use { input ->
                tmp.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val mb = String.format(Locale.US, "%.1f", got / 1_048_576.0)
                        if (size > 0) report(WIKI_SHARE + ZIP_SHARE * got / size,
                            "Downloading the DPS calculator's files: $mb of ${String.format(Locale.US, "%.1f", size / 1_048_576.0)} MB", ZIP_URL)
                        else report(-1f, "Downloading the DPS calculator's files: $mb MB (about $ZIP_SIZE_MB MB)", ZIP_URL)
                    }
                }
            }
            c.disconnect()
            // only keep it if it's a whole, readable ZIP
            ZipFile(tmp).use { if (it.size() == 0) throw IllegalStateException("empty") }
            tmp.renameTo(zip)
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    // Copies the named item pictures out of the ZIP (they're in .../cdn/equipment/)
    private fun unpack(zip: File, names: List<String>, context: Context, quiet: Boolean = false): Boolean {
        val wanted = names.toHashSet()
        GearIcons.folder(context).mkdirs()
        var n = 0
        ZipFile(zip).use { z ->
            for (entry in z.entries()) {
                if (entry.isDirectory) continue
                val path = entry.name
                val at = path.indexOf("/cdn/equipment/")
                if (at < 0) continue
                val name = path.substring(at + "/cdn/equipment/".length)
                if (name !in wanted) continue
                val to = GearIcons.file(context, name)
                val tmp = File(to.path + ".part")
                z.getInputStream(entry).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(to)
                n++
                if (!quiet) report(WIKI_SHARE + ZIP_SHARE + UNPACK_SHARE * n / wanted.size,
                    "Unpacking item pictures: $n of ${wanted.size}", "cdn/equipment/$name")
            }
        }
        return n == wanted.size || quiet && n > 0
    }

    // Saves one picture, only if it really is a picture (not an error page). The connection isn't closed
    // afterwards, so the next picture can reuse it.
    private fun fetch(url: String, to: File): Boolean {
        val tmp = File(to.path + ".part")
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (c.responseCode != 200) {
                c.errorStream?.use { it.readBytes() }
                return false
            }
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) { tmp.delete(); return false }
            tmp.renameTo(to)
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }
}
