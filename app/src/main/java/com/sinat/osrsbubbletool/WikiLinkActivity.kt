package com.sinat.osrsbubbletool

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.net.toUri

// Catches OSRS Wiki links. Links from the game (its wiki button) open in the bubble's Wiki
// window, so you stay in the game. Links from any other app are passed straight on to your
// normal browser, as if this app wasn't installed. If Android doesn't say which app sent a
// link, it's treated as the game while the bubble is on. It's invisible and closes straight away.
//
// Android only sends wiki links here if you allow it: Settings > Apps > OSRS Bubble Tool >
// Open by default > Add link (the app's Permissions screen has a button for this).
// Only real OSRS Wiki pages may open in the bubble's Wiki window. Android only sends this app wiki links on its own,
// but another app can aim a link straight at it (and claim to be the game), so every address is checked here.
object WikiAddress {
    private const val HOST = "oldschool.runescape.wiki"

    // The address made safe to open (always https), or null if it isn't a plain OSRS Wiki page: another website, a
    // disguised one (a name before @, an odd port), or a script, data or file address
    fun safe(raw: String): String? = try {
        val u = java.net.URI(raw)
        val scheme = u.scheme?.lowercase()
        if ((scheme != "https" && scheme != "http") || !HOST.equals(u.host, ignoreCase = true) ||
            u.rawUserInfo != null || u.port != -1) null
        else "https://$HOST" + (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "") + (u.rawFragment?.let { "#$it" } ?: "")
    } catch (e: Exception) { null }
}

class WikiLinkActivity : Activity() {

    companion object {
        const val GAME_PACKAGE = "com.jagex.oldscape.android"   // Old School RuneScape
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (anything but a real wiki page is ignored: see WikiAddress)
        val uri = intent?.data?.toString()?.let { WikiAddress.safe(it) }?.toUri()
        if (uri != null) {
            val from = referrer?.takeIf { it.scheme == "android-app" }?.host   // the app that sent it, if known
            val toBubble = when (from) {
                GAME_PACKAGE -> true
                null -> BubbleService.isRunning   // sender unknown: the game, if you're playing
                else -> false                              // another app: its links go to the browser
            }
            // remembered so the Permissions screen can show what happened
            getSharedPreferences("wiki_links", MODE_PRIVATE).edit {
                putString("from", from ?: "unknown app")
                putBoolean("to_bubble", toBubble)
                putLong("time", System.currentTimeMillis())
            }
            if (toBubble) openInBubble(uri.toString()) else openInBrowser(uri)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun openInBubble(url: String) {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(
                Intent(this, BubbleService::class.java)
                    .setAction(BubbleService.ACTION_OPEN_WIKI)
                    .putExtra(BubbleService.EXTRA_URL, url)
            )
        } else {
            Toast.makeText(this, "Allow \"Display over other apps\" to open wiki links in the bubble",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Hands the link to the phone's browser, making sure it doesn't come back to this app
    private fun openInBrowser(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val browser = browserPackage()
        try {
            if (browser != null) {
                startActivity(Intent(view).setPackage(browser))
            } else {
                // no browser found by name: ask Android for "the browser app" instead
                startActivity(Intent(view).apply {
                    selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
                })
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser for this link", Toast.LENGTH_SHORT).show()
        }
    }

    // The phone's default browser, or failing that any browser that isn't this app
    private fun browserPackage(): String? {
        val any = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri())
        val default = packageManager.resolveActivity(any, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        if (default != null && default != packageName && default != "android") return default
        return packageManager.queryIntentActivities(any, 0)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
    }
}
