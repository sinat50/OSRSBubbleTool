package com.sinat.osrsbubbletool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.WindowCompat
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.GO_GREEN
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import com.sinat.osrsbubbletool.Palette.ROW_BROWN

// The app's own screens: the main screen, Settings, Permissions, and Legal.
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1

        // The BSD 2-Clause licence conditions and disclaimer (used by the Zulrah, ToA Puzzle Helper, RuneLite, Teleport Finder, Quest Helper and Gear Loadouts credits)
        private const val BSD_TERMS =
            "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:\n\n" +
                "1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.\n\n" +
                "2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.\n\n" +
                "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."
    }

    private enum class Screen { MAIN, SETTINGS, PERMISSIONS, LEGAL }
    private var screen = Screen.MAIN
    private var backCallback: OnBackInvokedCallback? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // turning the phone rebuilds the screen; stay on the same page
        val saved = savedInstanceState?.getString("screen")
        show(Screen.entries.firstOrNull { it.name == saved } ?: Screen.MAIN)
        if (savedInstanceState == null) Thread { removeOldDownloads(); removeWikiSyncData() }.start()
    }

    // Test versions of the app downloaded the game pictures instead of coming with them. Those copies
    // aren't used any more: free the space (once).
    private fun removeOldDownloads() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("old_downloads_removed", false)) return
        try {
            for (name in listOf("dps_icons", "puzzles", "wiki_images")) java.io.File(filesDir, name).deleteRecursively()
            filesDir.listFiles()?.filter { it.name.startsWith("osrs-dps-calc-") }?.forEach { it.delete() }
            prefs.edit { putBoolean("old_downloads_removed", true) }
        } catch (_: Exception) {
            // try again next time
        }
    }

    // Older versions read your quests and levels from WikiSync, which the OSRS Wiki doesn't allow other
    // apps to use. The RuneScape name and data they saved are deleted (nothing happens if there are none).
    private fun removeWikiSyncData() {
        try { deleteSharedPreferences("wikisync") } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.name)
    }

    // Coming back from a settings page: update the permission ticks (and the colours, if dark mode was
    // switched in the bubble's Settings window meanwhile)
    override fun onResume() {
        super.onResume()
        Palette.dark = AppSettings.isDark(this)   // (this screen's colours while it's in front, see show)
        if (screen == Screen.PERMISSIONS || AppSettings.isDark(this) != shownDark) show(screen)
        // look for a newer version on GitHub each time the app is opened (unless that's switched off in Settings)
        if (AppSettings.checksUpdates(this)) UpdateChecker.check(this) { if (!isDestroyed) styleUpdateButton() }
    }

    // Gone to the background: the shared colours go back to the bubble's (its windows were built in those)
    override fun onPause() {
        super.onPause()
        if (BubbleService.isRunning) Palette.dark = BubbleService.builtDark
    }

    private var shownDark = false   // whether this screen was last drawn in the dark colours

    private fun show(s: Screen) {
        screen = s
        // The colours are shared with the bubble. While this screen is in front it uses its own (they can differ when
        // the bubble is holding off a dark mode switch, the phone's having changed while a tool was in use); the
        // bubble's are put back when it goes to the background (onPause).
        Palette.dark = AppSettings.isDark(this)
        val dark = Palette.dark
        shownDark = dark
        val content = when (s) {
            Screen.MAIN -> mainScreen()
            Screen.SETTINGS -> settingsScreen()
            Screen.PERMISSIONS -> permissionsScreen()
            Screen.LEGAL -> legalScreen()
        }
        setContentView(content)
        // the clock and icons along the top (and bottom) of the screen: dark on the light page, light on the dark one
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        updateBackHandling()
    }

    // ---------------- Back button: sub-screens go back to the main screen ----------------

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (screen != Screen.MAIN) {
            val cb = OnBackInvokedCallback { show(Screen.MAIN) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    // Only used on Android 12 and older. Newer phones use the callback in updateBackHandling() above,
    // so the warning about back gestures doesn't apply here.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.MAIN) show(Screen.MAIN) else super.onBackPressed()
    }

    // ---------------- Main screen ----------------

    private fun mainScreen(): View {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val logo = ImageView(this).apply { setImageResource(R.drawable.ic_bubble) }
        val logoSize = if (landscape) 32 else 72
        // landscape screens are short, so everything is a little smaller to fit without scrolling
        fun TextView.compact() = apply { if (landscape) { textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) } }

        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(logo, LinearLayout.LayoutParams(dp(logoSize), dp(logoSize)))
            addView(label("OSRS Bubble Tool", if (landscape) 18f else 22f, bold = true).apply { gravity = Gravity.CENTER },
                matchWidth(if (landscape) 2 else 10))
            addView(label("Tap Start Bubble, then open Old School RuneScape.\n\n" +
                "Tap the bubble to open or hide a tool.\n" +
                "Drag the bubble to move it.\n" +
                "Long-press the bubble for the tool menu.", if (landscape) 12f else 14f).apply { gravity = Gravity.CENTER },
                matchWidth(if (landscape) 4 else 16))
            addView(button("Start Bubble", GO_GREEN) { startBubble() }.apply {
                textSize = if (landscape) 15f else 17f
                if (landscape) setPadding(dp(22), dp(8), dp(22), dp(8)) else setPadding(dp(28), dp(12), dp(28), dp(12))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (landscape) 8 else 24) })
        }

        // Legal in the left corner, Settings in the exact middle, Permissions in the right corner
        // (laid on top of each other, so Settings stays centred even though the corner buttons differ in width)
        fun at(side: Int) = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            side or Gravity.CENTER_VERTICAL)
        val bottom = FrameLayout(this).apply {
            addView(button("Legal") { show(Screen.LEGAL) }.compact(), at(Gravity.START))
            addView(button("Settings") { show(Screen.SETTINGS) }.compact(), at(Gravity.CENTER_HORIZONTAL))
            addView(button("Permissions") { show(Screen.PERMISSIONS) }.compact(), at(Gravity.END))
        }

        return page(padTop = if (landscape) 6 else 16, padBottom = if (landscape) 6 else 16) {
            // top left: "Report An Issue"; top right: "Up To Date" or "Update Available"
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Report An Issue") { reportIssue() }.compact())
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                if (AppSettings.checksUpdates(this@MainActivity))
                    addView(button("") { openReleasePage() }.compact().also { updateButton = it; styleUpdateButton() })
                else updateButton = null
            }, matchWidth(0))
            addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            // a small thank-you link to the Ko-fi page (faded, so it doesn't compete with Start Bubble)
            addView(label("Free and ad-free. Enjoying it? ☕ Buy me a coffee", if (landscape) 11f else 12f).apply {
                gravity = Gravity.CENTER
                setTextColor(Palette.FADED)
                paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setPadding(0, dp(4), 0, dp(4))   // a little taller, so it's easy to tap
                setOnClickListener { openKofi() }
            }, matchWidth(if (landscape) 2 else 6))
            addView(label("Unofficial fan-made tool. Not affiliated with Jagex.", if (landscape) 10f else 11f).apply { gravity = Gravity.CENTER },
                matchWidth(if (landscape) 4 else 8))
            addView(bottom, matchWidth(if (landscape) 4 else 8))
        }
    }

    // The update button on the main screen: brown "Up To Date", or green "Update Available"
    private var updateButton: TextView? = null

    private fun styleUpdateButton() {
        val b = updateButton ?: return
        val state = UpdateChecker.state(this)
        b.text = when (state) {
            UpdateChecker.State.UPDATE_AVAILABLE -> "Update Available"
            UpdateChecker.State.UP_TO_DATE -> "Up To Date"
            UpdateChecker.State.NOT_CHECKED -> "Check for Update"
        }
        (b.background as? GradientDrawable)?.setColor(if (state == UpdateChecker.State.UPDATE_AVAILABLE) GO_GREEN else BUTTON_BROWN)
    }

    private fun openReleasePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, UpdateChecker.RELEASES_PAGE.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // The owner's Ko-fi page, for anyone who'd like to say thanks
    private fun openKofi() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://ko-fi.com/sinat50".toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // Opens GitHub's "new issue" page with the app's report form (.github/ISSUE_TEMPLATE/report_an_issue.yml),
    // its "Phone and app" box already filled in. The player sees everything before sending it, and can change it.
    private fun reportIssue() {
        val address = "https://github.com/sinat50/OSRSBubbleTool/issues/new".toUri().buildUpon()
            .appendQueryParameter("template", "report_an_issue.yml")
            .appendQueryParameter("device", deviceDetails())
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, address))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // The phone and app details that help track down a problem (nothing personal: no account or phone number)
    private fun deviceDetails(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION") val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        // the whole screen in pixels, the way round the phone is held now
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else @Suppress("DEPRECATION") android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        val density = resources.displayMetrics.density
        return "App: ${info.versionName} (build $build)\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Screen: ${width} × ${height} pixels, ${resources.configuration.densityDpi} dpi (×$density)\n" +
            "Phone text size: ×${resources.configuration.fontScale}\n" +
            "App dark mode: ${if (AppSettings.isDark(this)) "on" else "off"}\n" +
            "Bubble running: ${if (BubbleService.isRunning) "yes" else "no"}"
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "First allow \"Display over other apps\"", Toast.LENGTH_LONG).show()
            show(Screen.PERMISSIONS)
            return
        }
        startForegroundService(Intent(this, BubbleService::class.java))
        Toast.makeText(this, "Bubble started", Toast.LENGTH_SHORT).show()
    }

    // ---------------- Settings screen ----------------

    // The same settings as the bubble's Settings window
    private fun settingsScreen(): View = page {
        addView(backButton(), wrap())
        addView(label("Settings", 20f, bold = true), matchWidth(10))
        // dark mode switched: draw this screen again in the new colours
        SettingsTool(this@MainActivity) {
            if (AppSettings.isDark(this@MainActivity) != shownDark) show(Screen.SETTINGS)
        }.addSettings(this)
    }

    // ---------------- Permissions screen ----------------

    private fun permissionsScreen(): View = page {
        addView(backButton(), wrap())
        addView(label("Permissions", 20f, bold = true), matchWidth(10))
        addView(label("What the app asks for, and why. Nothing here is sent anywhere.", 13f), matchWidth(4))

        addView(permissionCard(
            "Display over other apps",
            "Needed for the bubble and every tool window to show on top of the game. The app can't work without this.",
            allowed = Settings.canDrawOverlays(this@MainActivity)
        ) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        }, matchWidth(12))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addView(permissionCard(
                "Notifications",
                "Lets Timers tell you when a farming patch or birdhouse run is ready. Also shows the small notification Android requires while the bubble is running.",
                allowed = FarmingTimers.notificationsAllowed(this@MainActivity)
            ) { askForNotifications() }, matchWidth(10))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addView(permissionCard(
                "Alarms & reminders",
                "Lets Timers alert you right on time. Without it, Android may deliver timer alerts a few minutes late to save battery.",
                allowed = FarmingTimers.canUseExactAlarms(this@MainActivity)
            ) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
            }, matchWidth(10))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addView(permissionCard(
                "Open wiki links in the bubble",
                "When you use the game's wiki button (under the world map), the page opens in the bubble's " +
                    "Wiki window instead of your browser. Tap Allow, then Add link, and tick oldschool.runescape.wiki. " +
                    "Wiki links from other apps still open in your normal browser.\n\n" + lastWikiLink(),
                allowed = wikiLinksAllowed()
            ) {
                openSettings(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:$packageName".toUri()))
            }, matchWidth(10))
        }

        addView(permissionCard(
            "Screen capture",
            "Used by the Puzzle Box and Light Box Solvers, the ToA Puzzle Helper, Gear Loadouts' \"Scan Current Loadout\" and the DPS Calculator's \"Import my gear\" to look at the game screen. Android doesn't allow apps to turn this on ahead of time, so it asks the first time you use one of those tools, once each time the bubble is started. On Android 14 and newer you can choose \"A single app\" and pick Old School RuneScape, so only the game is ever captured. Pictures stay on your phone.",
            allowed = null, onAllow = null
        ), matchWidth(10))
    }

    // What happened to the last wiki link that reached the app (to help sort out problems)
    private fun lastWikiLink(): String {
        val prefs = getSharedPreferences("wiki_links", MODE_PRIVATE)
        if (!prefs.contains("time")) return "Last link: none have reached the app yet."
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(prefs.getLong("time", 0)))
        val where = if (prefs.getBoolean("to_bubble", false)) "opened in the bubble" else "sent to your browser"
        return "Last link: at $time from ${prefs.getString("from", "?")}, $where."
    }

    // Has "Open by default" been turned on for the wiki's web address?
    private fun wikiLinksAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val manager = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val state = manager.getDomainVerificationUserState(packageName) ?: return false
            val host = state.hostToStateMap["oldschool.runescape.wiki"]
            host == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                host == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        } catch (e: Exception) {
            false
        }
    }

    private fun askForNotifications() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked_notifications", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit { putBoolean("asked_notifications", true) }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            // Android won't show the question again once it's been refused; open the settings page instead
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && screen == Screen.PERMISSIONS) show(Screen.PERMISSIONS)
    }

    // allowed: true/false shows a tick or an "Allow" button; null means there's nothing to press
    private fun permissionCard(title: String, text: String, allowed: Boolean?, onAllow: (() -> Unit)?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(8).toFloat() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 15f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (allowed) {
                true -> addView(label("✓ Allowed", 14f, bold = true).apply { setTextColor(GO_GREEN) })
                false -> if (onAllow != null) addView(button("Allow", GO_GREEN, onAllow))
                null -> addView(label("Asked when needed", 12f))
            }
        }
        card.addView(top)
        card.addView(label(text, 13f), matchWidth(6))
        return card
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    // ---------------- Legal screen ----------------

    private fun legalScreen(): View = page {
        addView(backButton(), wrap())
        addView(label("Legal", 20f, bold = true), matchWidth(10))

        fun section(title: String, text: String, size: Float = 13f) {
            addView(label(title, 15f, bold = true), matchWidth(16))
            addView(label(text, size), matchWidth(4))
        }

        // A button that shows (and hides again) the full text of a licence kept in assets/licences/
        fun fullText(file: String) {
            val showLabel = "Show the full licence"
            val hideLabel = "Hide the full licence"
            val text = label("", 10f).apply { isGone = true }
            val toggle = button(showLabel) {}
            toggle.setOnClickListener {
                // read only when first asked for (the GPL is 35 KB of text)
                if (text.text.isEmpty()) text.text = assets.open("licences/$file").bufferedReader().use { it.readText() }
                text.isGone = !text.isGone
                toggle.text = if (text.isGone) showLabel else hideLabel
            }
            addView(toggle, wrap().apply { topMargin = dp(8) })
            addView(text, matchWidth(6))
        }

        section("Jagex",
            "Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. " +
            "This content is not endorsed by or affiliated with Jagex.\n\n" +
            "Old School RuneScape and RuneScape are trademarks of Jagex Limited. Item, monster and game pictures are (c) Jagex Limited. " +
            "The solved puzzle pictures, item pictures and other game pictures in the app were taken from the OSRS Wiki, " +
            "the OSRS Wiki DPS calculator and RuneLite; they are Jagex's.\n\n" +
            "OSRS Bubble Tool is free and has no ads or paid features. It is not affiliated with Jagex, RuneLite, the " +
            "RuneLite plugin authors credited below, or the Old School RuneScape Wiki.")

        section("Privacy",
            "The app has no analytics, no ads and no account of its own, and the developer never receives anything from it. " +
            "It talks to the internet only when you use a tool that needs it: the OSRS Wiki, the DPS and XP calculators, " +
            "GE Prices and the Shooting Star Tracker load in the app like a browser (they see your internet address and may " +
            "set cookies, as in any browser); the Teleport " +
            "Finder sends your search words to the wiki; and when the app opens it asks GitHub whether there's a newer " +
            "version (you can turn that off in Settings). Your settings, notes, timers and loadouts are saved " +
            "on the phone, and go into your phone's normal Android backup if you have that on. Nothing else is stored or sent.")

        section("This app's licence",
            "OSRS Bubble Tool is free software: (c) 2026 sinat50, licensed under the GNU General Public License, version 3 " +
            "or any later version (gnu.org/licenses/gpl-3.0). You can use, change and share it; if you share a changed " +
            "version, share its source code under the same licence. It comes with no warranty. Its source code is at " +
            "github.com/sinat50/OSRSBubbleTool.\n\n" +
            "That covers the app's own code. What it includes from others keeps its own terms, listed below.")
        fullText("gpl-3.0.txt")

        section("Libraries",
            "The app is built with Google's AndroidX and Material Components libraries and JetBrains' Kotlin standard " +
            "library, used under the Apache License, Version 2.0 (apache.org/licenses/LICENSE-2.0).\n\n" +
            "Copyright The Android Open Source Project\n" +
            "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors", size = 11f)
        fullText("apache-2.0.txt")

        section("OSRS Wiki",
            "Text and data from the Old School RuneScape Wiki (oldschool.runescape.wiki) are used under the Creative Commons " +
            "Attribution-NonCommercial-ShareAlike 3.0 licence (creativecommons.org/licenses/by-nc-sa/3.0). " +
            "They have been shortened and reformatted for the app, and anything adapted from them is shared under the same licence. " +
            "(The wiki's pictures aren't under this licence: they're Jagex's, see above.) It includes:\n\n" +
            "\u2022 Gear Loadouts: every item's name, ID and equipment slot, from the wiki's item pages\n" +
            "\u2022 Quest Helper: some puzzle answers from the wiki's quick guides (such as Lunar Diplomacy's)\n" +
            "\u2022 Hunter Rumours: rumour lists, locations, travel and equipment from the pages Hunters' Rumours and Hunters' Rumours/Strategies\n" +
            "\u2022 Teleport Finder: search suggestions, where NPCs, monsters and places are on the map, monster levels, and descriptions of some teleport destinations\n" +
            "\u2022 ToA Puzzle Helper: the outline of the Path of Het room, traced from the wiki's map")

        section("OSRS Wiki DPS calculator",
            "DPS Calculator gear import: the item list and item pictures come from the OSRS Wiki DPS calculator's " +
            "repository (github.com/weirdgloop/osrs-dps-calc), which is licensed under the GNU General Public License v3.0 " +
            "(gnu.org/licenses/gpl-3.0). That list is itself made from the OSRS Wiki. The item pictures come from that " +
            "repository too, with a few from the OSRS Wiki. Item pictures are (c) Jagex Ltd.", size = 11f)

        section("Websites",
            "The OSRS Wiki, Real-time Prices, the DPS calculator, the XP calculator (oldschool.tools) and the Shooting Star Tracker (07.gg) " +
            "are websites run by their own owners. The app opens them as they are, like a browser.")

        section("RuneLite",
            "Farming growth times based on RuneLite's Time Tracking plugin. Teleport Finder: newer teleport destinations " +
            "and the list of dungeon entrances, from RuneLite's world map. Gear Loadouts: which items can be noted, and their noted item IDs, from RuneLite's item list (github.com/runelite/runelite), and which items have a second ID when worn, from its item manager (for the bank tab layout in exports); its item icons, exactly as the game draws them, as published by RuneLite (static.runelite.net; the pictures are (c) Jagex Ltd).\n\n" +
            "Copyright (c) 2016-2017, Adam <Adam@sigterm.info>\nCopyright (c) 2018-2019, Abex\n" +
            "Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>\nCopyright (c) 2018, Morgan Lewis <https://github.com/MESLewis>\n" +
            "Copyright (c) 2020, Arman S <https://github.com/Rman887>\n" +
            "All rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("2048",
            "The Game Room's 2048 is the app's own version of the game created by Gabriele Cirulli.", size = 11f)

        section("Chess",
            "The Game Room's chess computer player is CuckooChess, a chess engine by Peter Österlund, included unchanged " +
            "(its chess folder) from the DroidFish project (github.com/peterosterlund2/droidfish), with its opening book and " +
            "endgame tables. The strength of each level comes from CuckooChess's rating table (in DroidFish's " +
            "EngineControl.java). The board, the pieces and the rest of the game are the app's own.\n\n" +
            "CuckooChess - A java chess program.\nCopyright (C) 2011 Peter Österlund, peterosterlund2@gmail.com\n\n" +
            "This program is free software: you can redistribute it and/or modify it under the terms of the GNU General " +
            "Public License as published by the Free Software Foundation, either version 3 of the License, or (at your " +
            "option) any later version. This program is distributed in the hope that it will be useful, but WITHOUT ANY " +
            "WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the " +
            "GNU General Public License for more details (gnu.org/licenses/gpl-3.0).", size = 11f)

        section("Zulrah Helper",
            "Zulrah Helper rotation data and arena layout adapted from the Zulrah Helper RuneLite plugin " +
            "(github.com/while-loop/runelite-plugins).\n\n" +
            "Copyright (c) 2020, Anthony Alves\nCopyright (c) 2026, Ron Young\nAll rights reserved.\n\n" +
            BSD_TERMS,
            size = 11f)

        section("ToA Puzzle Helper",
            "Path of Scabaras puzzle rules, the addition puzzle's answers and the Path of Het's mirror answers adapted from the Tombs of Amascut RuneLite plugin " +
            "(github.com/LlemonDuck/tombs-of-amascut).\n\n" +
            "Copyright (c) 2022, LlemonDuck\nCopyright (c) 2022, TheStonedTurtle\n" +
            "Copyright (c) 2019, Ron Young <https://github.com/raiyni>\nAll rights reserved.\n\n" +
            BSD_TERMS,
            size = 11f)

        section("Teleport Finder",
            "Teleport destinations, the walking map, and the doors, ladders, cave entrances, boats, portals and levers that join places, from the Shortest Path RuneLite plugin " +
            "(github.com/Skretzo/shortest-path), used under the BSD 2-Clause License.\n\n" +
            "Copyright (c) Skretzo and the Shortest Path contributors\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Teleport Finder: cave entrances",
            "Some cave and boss-room entrances were checked against the crowdsourced transport data of the RuneScape map project (github.com/mejrs/data_osrs).",
            size = 11f)

        section("Teleport Finder: obstacles",
            "Where the game's obstacles are (used to join closed-off areas of the walking map to the rest), from the Golems Don't Die RuneLite plugin " +
            "(github.com/Varzeki/golems-dont-die), used under the BSD 2-Clause License.\n\n" +
            "Copyright (c) 2026, Varzeki\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Quest Helper",
            "Quest guides, quest requirements and achievement diary tasks adapted from the Quest Helper RuneLite plugin (github.com/Zoinkwiz/quest-helper).\n\n" +
            QuestGuides.COPYRIGHTS.joinToString("\n") + "\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Gear Loadouts",
            "The loadout layout follows the Inventory Setups RuneLite plugin's setup screen, and import and export use its " +
            "setup format and default bank tab layout, so setups move between the plugin and the app (github.com/dillydill123/inventory-setups).\n\n" +
            "Copyright (c) 2019, dillydill123\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Zen Garden: wood",
            "The Zen Garden's wooden frame uses the \"Dark Wood\" texture from Poly Haven (polyhaven.com), by Dimitrios Savva " +
            "(photography), Rico Cilliers (tiling) and Dario Barresi, released into the public domain (CC0). Its colour was " +
            "toned for the app.", size = 11f)
    }

    // ---------------- Small building blocks ----------------

    // A full screen with the app's background, scrolling, kept clear of the status and navigation bars
    private fun page(padTop: Int = 16, padBottom: Int = 16, fill: LinearLayout.() -> Unit): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(padTop), dp(20), dp(padBottom))
            fill()
        }
        // (the scroll view stretches short pages to fill the screen, so they can still be centred)
        val root: ViewGroup = ScrollView(this).apply { isFillViewport = true; addView(column) }
        root.setBackgroundColor(PARCHMENT)
        // Sideways, the camera hole keeps one side clear; the other side gets the same gap, so the page
        // stays centred on the screen rather than shifted away from the camera
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val side = maxOf(bars.left, bars.right)
                v.setPadding(side, bars.top, side, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                val side = maxOf(insets.systemWindowInsetLeft, insets.systemWindowInsetRight)
                @Suppress("DEPRECATION")
                v.setPadding(side, insets.systemWindowInsetTop, side, insets.systemWindowInsetBottom)
            }
            insets
        }
        return root
    }

    private fun backButton() = button("◀ Back") { show(Screen.MAIN) }

    private fun matchWidth(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun button(label: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
