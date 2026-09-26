package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// The solved picture of every puzzle box. Downloaded once from the OSRS Wiki,
// then kept on the phone so later scans work straight away.
class PuzzleReferences(context: Context) {

    class Puzzle(val name: String, val image: Bitmap)

    companion object {
        private const val BASE_URL = "https://oldschool.runescape.wiki/images/"
        private const val USER_AGENT = "OSRSBubbleTool/1.0 (personal Android app)"

        // Puzzle name to the wiki's picture of it solved
        private val PUZZLES = listOf(
            "Castle" to "Castle_puzzle_solved.png",
            "Tree" to "Tree_puzzle_solved.png",
            "Troll" to "Troll_puzzle_solved.png",
            "Zulrah" to "Zulrah_puzzle_solved.png",
            "Cerberus" to "Cerberus_puzzle_solved.png",
            "Gnome child" to "Gnome_child_puzzle_solved.png",
            "Theatre of Blood" to "Theatre_of_Blood_puzzle_solved.png"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val folder = File(context.filesDir, "puzzles")
    private var loaded: List<Puzzle>? = null

    val isLoaded: Boolean get() = loaded != null

    // Gets all the solved pictures, downloading any that aren't saved yet.
    // Calls onDone on the main thread with the puzzles, or with an error message.
    fun load(onDone: (List<Puzzle>?, String?) -> Unit) {
        loaded?.let { onDone(it, null); return }
        Thread {
            try {
                folder.mkdirs()
                val puzzles = PUZZLES.map { (name, fileName) ->
                    val local = File(folder, fileName)
                    if (!local.exists() || local.length() == 0L) download(BASE_URL + fileName, local)
                    val image = BitmapFactory.decodeFile(local.path)
                    if (image == null) {
                        local.delete()
                        throw IllegalStateException("the $name picture couldn't be read")
                    }
                    Puzzle(name, image)
                }
                mainHandler.post {
                    loaded = puzzles
                    onDone(puzzles, null)
                }
            } catch (e: Exception) {
                mainHandler.post { onDone(null, e.message ?: "download failed") }
            }
        }.start()
    }

    // Deletes the saved pictures so they're downloaded fresh next time
    fun clearSaved() {
        folder.listFiles()?.forEach { it.delete() }
        loaded = null
    }

    private fun download(url: String, destination: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("the wiki returned error ${connection.responseCode} for ${destination.name}")
            }
            val partial = File(destination.path + ".part")
            connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (!partial.renameTo(destination)) throw IllegalStateException("couldn't save ${destination.name}")
        } finally {
            connection.disconnect()
        }
    }
}
