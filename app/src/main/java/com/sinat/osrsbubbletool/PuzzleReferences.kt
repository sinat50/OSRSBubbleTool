package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper

// The solved picture of every puzzle box, from the OSRS Wiki. They come with the app (assets/puzzles/,
// put there by tools/dps/fetch_pictures.py: add a new puzzle there too).
class PuzzleReferences(context: Context) {

    class Puzzle(val name: String, val image: Bitmap)

    companion object {
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
    private val assets = context.applicationContext.assets
    private var loaded: List<Puzzle>? = null

    val isLoaded: Boolean get() = loaded != null

    // Reads all the solved pictures (the first time, in the background).
    // Calls onDone on the main thread with the puzzles, or with an error message.
    fun load(onDone: (List<Puzzle>?, String?) -> Unit) {
        loaded?.let { onDone(it, null); return }
        Thread {
            try {
                val puzzles = PUZZLES.map { (name, fileName) ->
                    val image = assets.open("puzzles/$fileName").use { BitmapFactory.decodeStream(it) }
                        ?: throw IllegalStateException("the $name picture couldn't be read")
                    Puzzle(name, image)
                }
                mainHandler.post {
                    loaded = puzzles
                    onDone(puzzles, null)
                }
            } catch (e: Exception) {
                mainHandler.post { onDone(null, e.message ?: "a picture is missing") }
            }
        }.start()
    }
}
