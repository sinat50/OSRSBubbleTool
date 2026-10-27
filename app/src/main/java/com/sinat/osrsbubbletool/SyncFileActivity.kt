package com.sinat.osrsbubbletool

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

// Bubble Sync's "Import file": the phone's file chooser, for the file the Bubble Tool Sync plugin exports
// (account-<name>.txt, the same text as its QR code). The account is saved here. Opened from the bubble, the bubble
// brings the Bubble Sync window back when this closes; from the app's main screen, that screen is simply back in front.
// A file that can't be read leaves a message for the Bubble Sync page (AccountSync.notice).
class SyncFileActivity : Activity() {

    companion object {
        private const val REQUEST_OPEN = 1
        private const val MAX_FILE_BYTES = 10_000   // the plugin's file is about 600 letters
        const val EXTRA_FROM_BUBBLE = "from_bubble"   // opened from the bubble's window (shared with SyncScanActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return   // the chooser is already up (this screen was rebuilt)
        val ask = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            // a .txt file, which some phones label as plain text and some as nothing at all
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ask, REQUEST_OPEN)
        } catch (_: Exception) {
            AccountSync.notice = "This phone has no file chooser. Use Scan or Paste code instead."
            finish()
        }
    }

    @Deprecated("Needed for the file chooser")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data.takeIf { resultCode == RESULT_OK }
        if (uri != null) {
            // a small file, read in a moment
            val text = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytesUpTo(MAX_FILE_BYTES + 1)
                    if (bytes.size > MAX_FILE_BYTES) null else bytes.toString(Charsets.UTF_8)
                }
            } catch (_: Exception) { null }
            if (text == null) {
                AccountSync.notice = "That file couldn't be read. Pick the account-….txt file the plugin exported."
            } else {
                try {
                    val account = AccountSync(this).save(text)
                    Toast.makeText(this, "Synced " + account.name.ifEmpty { "your account" }, Toast.LENGTH_SHORT).show()
                } catch (e: SyncCode.BadCode) {
                    AccountSync.notice = e.message
                }
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // closed (picked a file or not): bring the bubble's Bubble Sync window back, if that's where this came from
        if (isFinishing && intent.getBooleanExtra(EXTRA_FROM_BUBBLE, false) && BubbleService.isRunning) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SYNC_SCAN_DONE))
        }
    }

    // Reads at most max bytes (InputStream.readNBytes needs Android 13)
    private fun java.io.InputStream.readBytesUpTo(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (out.size() < max) {
            val n = read(buffer, 0, minOf(buffer.size, max - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
