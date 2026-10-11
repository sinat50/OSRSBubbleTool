package com.sinat.osrsbubbletool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

// An invisible screen that only shows Android's file chooser for Gear Loadouts: to pick a file RuneLite's
// Inventory Setups exported (Mass Export Setups), or to choose where to save the loadouts as one. Tool windows
// float over other apps and can't open the chooser themselves.
class LoadoutFileActivity : Activity() {

    // Hears the chooser's answer (see listener below)
    interface Listener {
        fun opened(text: String?)   // the file's text ("" if it couldn't be read), or null if none was picked
        fun saved(ok: Boolean)      // true once written
    }

    companion object {
        private const val REQUEST_OPEN = 1
        private const val REQUEST_SAVE = 2
        private const val EXTRA_SAVE_NAME = "save_name"
        private const val MAX_FILE_BYTES = 5_000_000   // a few hundred setups is well under 1 MB

        // Who hears the answer (on the main thread): the Gear Loadouts tool that's there when it comes. Not a callback
        // kept from when the chooser opened: the tools are rebuilt if dark mode or the text size changes meanwhile. An
        // answer with nobody listening waits until a tool listens.
        var listener: Listener? = null
            set(value) {
                field = value
                if (value != null) waiting?.let { w -> waiting = null; w(value) }
            }
        private var waiting: ((Listener) -> Unit)? = null
        private var toSave: String? = null   // kept here, not in the Intent: it can be too big for one

        fun open(context: Context) {
            context.startActivity(Intent(context, LoadoutFileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun save(context: Context, fileName: String, text: String) {
            toSave = text
            context.startActivity(Intent(context, LoadoutFileActivity::class.java)
                .putExtra(EXTRA_SAVE_NAME, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun tell(what: (Listener) -> Unit) {
            val l = listener
            if (l != null) what(l) else waiting = what
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // the chooser is already up (this screen was rebuilt)
        val saveName = intent.getStringExtra(EXTRA_SAVE_NAME)
        val ask = if (saveName == null) {
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                // RuneLite saves .json files, which some phones label as plain text or nothing at all
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/*", "application/octet-stream"))
        } else {
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, saveName)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ask, if (saveName == null) REQUEST_OPEN else REQUEST_SAVE)
        } catch (_: Exception) {
            finishWith(null)   // no file chooser on this phone
        }
    }

    @Deprecated("Needed for the file chooser")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data.takeIf { resultCode == RESULT_OK }
        // Small files, read or written in a moment; done here so the answer is ready when the window reopens
        val result: Any? = when {
            uri == null -> null
            requestCode == REQUEST_OPEN -> try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readNBytesCompat(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) "" else bytes.toString(Charsets.UTF_8)
                } ?: ""
            } catch (_: Exception) { "" }
            else -> try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(toSave.orEmpty().toByteArray()) } != null
            } catch (_: Exception) { false }
        }
        finishWith(result)
    }

    private fun finishWith(result: Any?) {
        val saving = intent.getStringExtra(EXTRA_SAVE_NAME) != null
        toSave = null
        finish()
        // after this screen has gone, so the tool window reopens over the game rather than under it
        Handler(Looper.getMainLooper()).post {
            if (saving) tell { it.saved(result == true) } else tell { it.opened(result as? String) }
        }
    }

    // Reads at most max bytes (InputStream.readNBytes needs Android 13)
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
