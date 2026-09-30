package com.sinat.osrsbubbletool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

// The app's own screens: the main screen, Permissions, and Legal.
class MainActivity : Activity() {

    companion object {
        private val PARCHMENT = Color.parseColor("#F2E3C0")
        private val DARK_BROWN = Color.parseColor("#3E2C12")
        private val BUTTON_BROWN = Color.parseColor("#8B6B3E")
        private val ROW_BROWN = Color.parseColor("#E3CFA2")
        private val GO_GREEN = Color.parseColor("#3E7A2E")
        private const val REQUEST_NOTIFICATIONS = 1

        // The BSD 2-Clause licence conditions and disclaimer (used by the Zulrah, RuneLite, Teleport Finder and Quest Helper credits)
        private const val BSD_TERMS =
            "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:\n\n" +
                "1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.\n\n" +
                "2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.\n\n" +
                "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."
    }

    private enum class Screen { MAIN, PERMISSIONS, LEGAL }
    private var screen = Screen.MAIN
    private var backCallback: OnBackInvokedCallback? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // turning the phone rebuilds the screen; stay on the same page
        val saved = savedInstanceState?.getString("screen")
        show(Screen.values().firstOrNull { it.name == saved } ?: Screen.MAIN)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.name)
    }

    // Coming back from a settings page: update the permission ticks
    override fun onResume() {
        super.onResume()
        if (screen == Screen.PERMISSIONS) show(Screen.PERMISSIONS)
        // look for a newer version on GitHub each time the app is opened
        UpdateChecker.check(this) { if (!isDestroyed) styleUpdateButton() }
    }

    private fun show(s: Screen) {
        screen = s
        val content = when (s) {
            Screen.MAIN -> mainScreen()
            Screen.PERMISSIONS -> permissionsScreen()
            Screen.LEGAL -> legalScreen()
        }
        setContentView(content)
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

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Legal") { show(Screen.LEGAL) }.compact())
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("Permissions") { show(Screen.PERMISSIONS) }.compact())
        }

        return page(scrolling = true, padTop = if (landscape) 6 else 16, padBottom = if (landscape) 6 else 16) {
            // top right: "Up To Date" or "Update Available"
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(button("") { openReleasePage() }.compact().also { updateButton = it; styleUpdateButton() })
            }, matchWidth(0))
            addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASES_PAGE)))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
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

    // ---------------- Permissions screen ----------------

    private fun permissionsScreen(): View = page(scrolling = true) {
        addView(backButton(), wrap())
        addView(label("Permissions", 20f, bold = true), matchWidth(10))
        addView(label("What the app asks for, and why. Nothing here is sent anywhere.", 13f), matchWidth(4))

        addView(permissionCard(
            "Display over other apps",
            "Needed for the bubble and every tool window to show on top of the game. The app can't work without this.",
            allowed = Settings.canDrawOverlays(this@MainActivity)
        ) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
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
                openSettings(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$packageName")))
            }, matchWidth(10))
        }

        addView(permissionCard(
            "Screen capture",
            "Used by the Puzzle Box Solver, Inventory Setups and the DPS Calculator's \"Import my gear\" to look at the game screen. Android doesn't allow apps to turn this on ahead of time, so it asks the first time you use one of those tools, once each time the bubble is started. Pictures stay on your phone.",
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
            prefs.edit().putBoolean("asked_notifications", true).apply()
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
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    // ---------------- Legal screen ----------------

    private fun legalScreen(): View = page(scrolling = true) {
        addView(backButton(), wrap())
        addView(label("Legal", 20f, bold = true), matchWidth(10))

        fun section(title: String, text: String, size: Float = 13f) {
            addView(label(title, 15f, bold = true), matchWidth(16))
            addView(label(text, size), matchWidth(4))
        }

        section("Jagex",
            "Created using intellectual property belonging to Jagex Limited under the terms of Jagex's Fan Content Policy. " +
            "This content is not endorsed by or affiliated with Jagex.\n\n" +
            "Old School RuneScape and RuneScape are trademarks of Jagex Limited. Item, monster and game pictures are (c) Jagex Limited.\n\n" +
            "OSRS Bubble Tool is free and has no ads or paid features.")

        section("OSRS Wiki",
            "Content from the Old School RuneScape Wiki (oldschool.runescape.wiki) is used under the Creative Commons " +
            "Attribution-NonCommercial-ShareAlike 3.0 licence (creativecommons.org/licenses/by-nc-sa/3.0). " +
            "It has been shortened and reformatted for the app, and anything adapted from it is shared under the same licence. It includes:\n\n" +
            "\u2022 Quest Helper: solved puzzle pictures and some puzzle answers from the wiki's quick guides\n" +
            "\u2022 Hunter Rumours: rumour lists, locations, travel and equipment from the Hunters' Rumours pages\n" +
            "\u2022 Teleport Finder: search suggestions, where NPCs, monsters and places are on the map, monster levels, and descriptions of some teleport destinations\n\n" +
            "Your quests and levels are read from WikiSync, a service of the OSRS Wiki.")

        section("OSRS Wiki DPS calculator",
            "DPS Calculator gear import: the item list and item pictures come from the OSRS Wiki DPS calculator's " +
            "repository (github.com/weirdgloop/osrs-dps-calc), which is licensed under the GNU General Public License v3.0 " +
            "(gnu.org/licenses/gpl-3.0). That list is itself made from the OSRS Wiki.", size = 11f)

        section("Websites",
            "The OSRS Wiki, Real-time Prices, the DPS calculator, the XP calculator (oldschool.tools) and the Shooting Star Tracker (07.gg) " +
            "are websites run by their own owners. The app opens them as they are, like a browser.")

        section("RuneLite",
            "Farming growth times based on RuneLite's Time Tracking plugin. Teleport Finder: newer teleport destinations " +
            "(like the Spider cave and Wyrmscraig teleports) and the list of dungeon entrances, from RuneLite's world map (github.com/runelite/runelite).\n\n" +
            "Copyright (c) 2016-2017, Adam <Adam@sigterm.info>\nCopyright (c) 2018-2019, Abex\n" +
            "Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>\nCopyright (c) 2018, Morgan Lewis <https://github.com/MESLewis>\n" +
            "Copyright (c) 2020, Arman S <https://github.com/Rman887>\n" +
            "All rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("2048",
            "The Game Room's 2048 is the app's own version of the game created by Gabriele Cirulli.", size = 11f)

        section("Zulrah Helper",
            "Zulrah Helper rotation data and arena layout adapted from the Zulrah Helper RuneLite plugin " +
            "(github.com/while-loop/runelite-plugins).\n\n" +
            "Copyright (c) 2020, Anthony Alves\nCopyright (c) 2026, Ron Young\nAll rights reserved.\n\n" +
            BSD_TERMS,
            size = 11f)

        section("Teleport Finder",
            "Teleport destinations, the walking map, and the doors, ladders, cave entrances, boats, portals and levers that join places, from the Shortest Path RuneLite plugin " +
            "(github.com/Skretzo/shortest-path), used under the BSD 2-Clause License.\n\n" +
            "Copyright (c) Skretzo and the Shortest Path contributors\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Quest Helper",
            "Quest guides, quest requirements and achievement diary tasks adapted from the Quest Helper RuneLite plugin (github.com/Zoinkwiz/quest-helper).\n\n" +
            QuestGuides.COPYRIGHTS.joinToString("\n") + "\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)
    }

    // ---------------- Small building blocks ----------------

    // A full screen with the app's background, kept clear of the status and navigation bars
    private fun page(scrolling: Boolean, padTop: Int = 16, padBottom: Int = 16, fill: LinearLayout.() -> Unit): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(padTop), dp(20), dp(padBottom))
            fill()
        }
        // (the scroll view stretches short pages to fill the screen, so they can still be centred)
        val root: ViewGroup = if (scrolling) ScrollView(this).apply { isFillViewport = true; addView(column) }
                              else LinearLayout(this).apply {
                                  addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                              }
        root.setBackgroundColor(PARCHMENT)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
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
