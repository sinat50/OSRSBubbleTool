package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// Checks GitHub for a newer release of the app. It checks each time you open the app's main screen
// (never in the background), and sends nothing about you or your phone.
object UpdateChecker {

    private const val LATEST_API = "https://api.github.com/repos/sinat50/OSRSBubbleTool/releases/latest"
    const val RELEASES_PAGE = "https://github.com/sinat50/OSRSBubbleTool/releases/latest"

    enum class State { NOT_CHECKED, UP_TO_DATE, UPDATE_AVAILABLE }

    @Volatile private var checking = false

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    // What the last check found (shown straight away, then updated when the new check finishes)
    fun state(context: Context): State {
        val latest = prefs(context).getString("latest", null) ?: return State.NOT_CHECKED
        return if (isNewer(latest, installedVersion(context))) State.UPDATE_AVAILABLE else State.UP_TO_DATE
    }

    // Asks GitHub for the latest release, then calls onChecked on the main thread
    fun check(context: Context, onChecked: () -> Unit) {
        if (checking) return
        checking = true
        val app = context.applicationContext
        Thread {
            try {
                val c = URL(LATEST_API).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "OSRSBubbleTool")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                try {
                    if (c.responseCode == 200) {
                        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        val tag = j.optString("tag_name")
                        if (tag.isNotEmpty() && !j.optBoolean("draft") && !j.optBoolean("prerelease")) {
                            prefs(app).edit { putString("latest", tag) }
                        }
                    }
                } finally { c.disconnect() }
            } catch (e: Exception) { }
            checking = false
            Handler(Looper.getMainLooper()).post(onChecked)
        }.start()
    }

    // "v1.0.10" is newer than "1.0.9"
    fun isNewer(latest: String, installed: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-', ' ')
            .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
