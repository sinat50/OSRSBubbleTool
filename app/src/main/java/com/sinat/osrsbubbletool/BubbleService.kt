package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.roundToInt

class BubbleService : Service() {

    companion object {
        const val WIKI_URL = "https://oldschool.runescape.wiki/"
        const val XP_CALCULATOR_URL = "https://oldschool.tools/calculators/skill"
        const val DPS_CALCULATOR_URL = "https://tools.runescape.wiki/osrs-dps/"
        const val DPS_WIDTH_FRACTION = 0.6f  // DPS Calculator window: 60% of the screen width in landscape
        const val DPS_ZOOM_PERCENT = 100     // DPS Calculator text size
        const val TEXT_SIZE_WAIT_MS = 700L   // Tool text size: the tools are built again this long after the last − or + tap
        const val SHOOTING_STARS_URL = "https://07.gg/trackers/shooting-star"
        const val SHOOTING_STARS_ZOOM_PERCENT = 85  // Shooting Star Tracker text size
        const val PRICES_URL = "https://prices.runescape.wiki/osrs/"
        const val PRICES_ZOOM_PERCENT = 85          // GE Prices text size
        const val ZULRAH_WIDTH_INCHES = 1.2f // the Zulrah Helper window is thinner
        const val LIGHT_BOX_WIDTH_INCHES = 1.3f  // the Light Box Solver is a small box, top right
        const val PUZZLE_BOX_WIDTH_INCHES = 1.5f // so is the Puzzle Box Solver
        // where a tool's window can shrink to (setWindowCompact)
        const val COMPACT_OFF = 0
        const val COMPACT_TOP_LEFT = 1
        const val COMPACT_BOTTOM_LEFT = 2
        const val BUBBLE_FADED_ALPHA = 0.4f // how see-through the bubble fades to (Settings → Fade when not in use)
        const val BUBBLE_FADE_MS = 3000L    // after this long without being touched
        const val BAR_HEIGHT_DP = 28        // height of the window's button bar
        const val BAR_BUTTON_WIDTH_DP = 66  // width of the back and close buttons
        const val PAGE_ZOOM_PERCENT = 70    // wiki text size: 100 = normal, lower = smaller
        const val XP_CALCULATOR_ZOOM_PERCENT = 85  // XP Calculator text size
        const val MENU_WIDTH_DP = 170       // width of the long-press menu
        const val MENU_MAX_PORTRAIT_DP = 340 // tallest the long-press menu gets in portrait (it scrolls past that)
        private const val CARVED = "carved"  // a window corner carved into the Zen Garden's frame (see applyFrameless)

        // Messages from CapturePermissionActivity
        const val ACTION_CAPTURE_RESULT = "com.sinat.osrsbubbletool.CAPTURE_RESULT"

        // A wiki link opened from another app (like the game's wiki button), sent by WikiLinkActivity
        const val ACTION_OPEN_WIKI = "com.sinat.osrsbubbletool.OPEN_WIKI"
        const val EXTRA_URL = "url"

        // True while the bubble is on (so you're most likely playing)
        @Volatile var isRunning = false
        // whether the bubble's tool windows were built dark (the app's main screen puts the shared colours back to
        // this when it goes to the background, see MainActivity.onPause)
        @Volatile var builtDark = false
            private set
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        // Dropdown menus normally open as a pop-up, which Android can't show from a floating
        // window. This makes a dropdown open as a list right on the page instead.
        const val INLINE_DROPDOWNS_JS = """
            (function() {
                if (window.__inlineSelects) return;
                window.__inlineSelects = true;
                function openList(e) {
                    var s = e.target.closest ? e.target.closest('select') : null;
                    if (!s || s.multiple || s.size > 1) return;
                    e.preventDefault();
                    s.size = Math.min(Math.max(s.options.length, 2), 8);
                    s.focus();
                }
                function closeList(s) { s.removeAttribute('size'); }
                document.addEventListener('touchstart', openList, { capture: true, passive: false });
                document.addEventListener('mousedown', openList, true);
                document.addEventListener('change', function(e) {
                    if (e.target.tagName === 'SELECT') closeList(e.target);
                }, true);
                document.addEventListener('focusout', function(e) {
                    var s = e.target;
                    if (s.tagName === 'SELECT') setTimeout(function() { closeList(s); }, 150);
                }, true);
            })();
        """

        // Shooting Star Tracker: a star's details sheet is drawn over the map, pinned to the
        // bottom and unable to scroll, so in a short window its top and bottom get cut off.
        // This makes the sheet fill the window, starts the details below the Close button,
        // lets them scroll, and keeps swipes on them from moving the map instead.
        const val STAR_DETAILS_FIX_JS = """
            (function() {
                if (window.__bubbleStarFix) return;
                window.__bubbleStarFix = true;
                var s = document.createElement('style');
                s.textContent =
                    '[vaul-drawer]{top:0!important;height:100%!important;margin-top:0!important;}' +
                    '[vaul-drawer] .leaflet-bottom.leaflet-left{top:52px!important;bottom:0!important;' +
                    'overflow-y:auto!important;pointer-events:auto!important;padding-bottom:70px!important;}' +
                    '[vaul-drawer] .leaflet-bottom.leaflet-left .leaflet-overlay{pointer-events:auto!important;}';
                document.head.appendChild(s);
                function keepForPanel(e) {
                    var panel = e.target.closest && e.target.closest('[vaul-drawer] .leaflet-bottom.leaflet-left');
                    if (panel) e.stopPropagation();
                }
                ['wheel','touchstart','touchmove','pointerdown','pointermove','mousedown','mousemove']
                    .forEach(function(t) { document.addEventListener(t, keepForPanel, true); });
            })();
        """

        // Like FIT_WIDTH_JS, but also counts things sticking out past the LEFT edge.
        // (A page that centres something wider than the window pushes half of it off the left
        // side, where you can't scroll to it. The GE Prices search bar does this.)
        const val FIT_BOTH_SIDES_JS = """
            (function() {
                var root = document.documentElement;
                root.style.zoom = '';
                var windowWidth = root.clientWidth;
                var pageWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
                var left = 0, right = Math.max(pageWidth, windowWidth);
                var all = document.body ? document.body.getElementsByTagName('*') : [];
                for (var i = 0; i < all.length && i < 4000; i++) {
                    var r = all[i].getBoundingClientRect();
                    // only things partly on screen: ignores hidden off-screen menus and skip-links
                    if (r.width <= 0 || r.height <= 0 || r.right <= 0 || r.width > windowWidth * 3) continue;
                    if (r.left < left) left = r.left;
                }
                var needed = right - left;
                if (needed > windowWidth + 1) {
                    root.style.zoom = windowWidth / needed;
                }
            })();
        """

        // GE Prices: the price graphs grab every touch (to show the price under your finger),
        // so once you'd scrolled down to them you couldn't scroll back up. This lets an up/down
        // swipe on a graph scroll the page, while a sideways drag still reads the prices.
        const val CHART_SCROLL_FIX_JS = """
            (function() {
                if (window.__bubbleChartScroll) return;
                window.__bubbleChartScroll = true;
                var charts = 'canvas, svg, .highcharts-container, .recharts-wrapper, [class*="chart"], [class*="Chart"]';
                var style = document.createElement('style');
                style.textContent = charts.split(',').map(function(s) {
                    return s.trim() + ', ' + s.trim() + ' *';
                }).join(', ') + ' { touch-action: pan-y !important; }';
                document.head.appendChild(style);

                var startX = 0, startY = 0, mode = null;
                function onChart(t) { return t && t.closest ? t.closest(charts) : null; }
                window.addEventListener('touchstart', function(e) {
                    var t = e.touches[0];
                    startX = t.clientX; startY = t.clientY;
                    mode = onChart(e.target) ? 'undecided' : null;
                }, { capture: true, passive: true });
                function move(e, x, y) {
                    if (mode === null) return;
                    var dx = Math.abs(x - startX), dy = Math.abs(y - startY);
                    if (mode === 'undecided' && (dx > 8 || dy > 8)) mode = dy > dx ? 'scroll' : 'chart';
                    // an up/down swipe: keep it away from the graph so the page scrolls
                    if (mode === 'scroll') e.stopImmediatePropagation();
                }
                window.addEventListener('touchmove', function(e) {
                    move(e, e.touches[0].clientX, e.touches[0].clientY);
                }, { capture: true, passive: true });
                window.addEventListener('pointermove', function(e) {
                    if (e.pointerType === 'touch') move(e, e.clientX, e.clientY);
                }, { capture: true, passive: true });
                window.addEventListener('touchend', function() { mode = null; }, true);
            })();
        """

        // Runs as a page starts loading: touch handlers the page adds from then on can't block
        // scrolling (the browser ignores their attempts to), for the GE Prices graphs
        const val PASSIVE_TOUCH_JS = """
            (function() {
                if (window.__bubblePassive) return;
                window.__bubblePassive = true;
                var add = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, fn, opts) {
                    if (type === 'touchstart' || type === 'touchmove') {
                        if (typeof opts === 'object' && opts !== null) opts = Object.assign({}, opts, { passive: true });
                        else opts = { capture: !!opts, passive: true };
                    }
                    return add.call(this, type, fn, opts);
                };
            })();
        """

        // Measures the page and scales it down so nothing sticks out past the window's edges
        const val FIT_WIDTH_JS = """
            (function() {
                var root = document.documentElement;
                root.style.zoom = '';
                var pageWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
                var windowWidth = root.clientWidth;
                if (pageWidth > windowWidth) {
                    root.style.zoom = windowWidth / pageWidth;
                }
            })();
        """
    }

    // The tools the bubble can open, in menu order. A new tool gets added here and in makeParts()
    // below (the build stops with an error until it's in both). Settings is last: the menu shows it
    // apart from the tools, at the bottom.
    // inMenu = false: not one of the tools (left out of the tool list in the menu and in Settings → Tools in the menu)
    enum class Tool(val label: String, val inMenu: Boolean = true) {
        WIKISYNC("WikiSync"),
        WIKI("OSRS Wiki"),
        PUZZLE_BOX("Puzzle Box Solver"),
        LIGHT_BOX("Light Box Solver"),
        TOA_PUZZLES("ToA Puzzle Helper (Beta)"),
        INVENTORY_SETUPS("Gear Loadouts"),   // (named Inventory Setups before; the name stays, it's saved in settings)
        XP_CALCULATOR("XP Calculator"),
        DPS_CALCULATOR("DPS Calculator"),
        SHOOTING_STARS("Shooting Star Tracker"),
        ZULRAH("Zulrah Helper"),
        FARMING("Timers"),
        PRICES("GE Prices"),
        CALCULATOR("Calculator"),
        NOTES("Notepad"),
        QUESTS("Quest Helper (Beta)"),
        HUNTER("Hunter Rumours"),
        TELEPORTS("Teleport Finder (Beta)"),
        GAMES("Game Room"),
        SETTINGS("Settings", inMenu = false)   // (in the menu below the tools, by Close bubble)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: ImageView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var capture: CaptureManager   // screen capture shared by all tools
    private var syncReturnTo: Tool? = null   // the tool that opened WikiSync, for the back button

    // What the bubble needs from one tool: what goes in its window, and what to do when things happen.
    // (Tools that don't care about something leave it out.)
    private class ToolParts(
        val buildView: () -> View,
        val onBack: (() -> Unit)?,         // the window's ◀ button; null hides it
        val onClosed: () -> Unit = {},     // its window was closed
        val onRotated: () -> Unit = {},    // the phone turned
        val onDestroy: () -> Unit = {},    // the bubble is closing: tidy up
        val isBusy: () -> Boolean = { false }   // still working with its window closed (a puzzle guide on screen)
    )
    // Each tool is only set up the first time you open it, so tools you don't use cost nothing
    private val toolParts = mutableMapOf<Tool, ToolParts>()
    private fun partsFor(tool: Tool) = toolParts.getOrPut(tool) { makeParts(tool) }

    private val toolWindows = mutableMapOf<Tool, LinearLayout>() // built once, reused
    private var currentTool = Tool.WIKI
    private var shownWindow: LinearLayout? = null
    private var shownParams: WindowManager.LayoutParams? = null
    private var windowX = 0            // remembers where you dragged the window
    private var windowY: Int? = null   // …and how far down (null = centred)
    private val sizes by lazy { getSharedPreferences("window_sizes", MODE_PRIVATE) }   // sizes you've dragged windows to
    private var menu: View? = null
    private val webViews = mutableListOf<WebView>()

    // The tools are built with this instead of the bubble itself: the same, except that its writing is the size
    // set in Settings → Tool text size (Android's font size, scaled). Kept up to date when the phone turns.
    private var scaledResources: android.content.res.Resources? = null
    private val toolContext = object : android.content.ContextWrapper(this) {
        override fun getResources(): android.content.res.Resources = scaledResources ?: super.getResources()
    }

    private fun updateToolTextSize() {
        val scale = AppSettings.toolTextSize(this) / AppSettings.TEXT_NORMAL.toFloat()
        scaledResources = if (scale == 1f) null else createConfigurationContext(
            Configuration(resources.configuration).apply { fontScale *= scale }).resources
    }
    private val webZoom = mutableMapOf<WebView, Int>()   // each page's own text size, before the Settings text size
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        updateForeground(capturing = false)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Palette.dark = AppSettings.isDark(this)
        builtDark = Palette.dark
        updateToolTextSize()
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsChanged)
        windowX = dp(8)
        lastScreenSize = screenWidth() to screenHeight()
        // Android forgets an app's alarms if it's force-stopped; set the farming ones again
        try { FarmingTimers.rescheduleAll(this) } catch (e: Exception) { }
        capture = CaptureManager(this) { capturing ->
            if (!destroyed) updateForeground(capturing)
        }
        createBubble()
        addBarProbes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_RESULT) capture.onPermissionResult(intent)
        // (checked again here: only real OSRS Wiki pages open in the Wiki window, see WikiAddress)
        if (intent?.action == ACTION_OPEN_WIKI) intent.getStringExtra(EXTRA_URL)?.let { WikiAddress.safe(it) }?.let { openWikiLink(it) }
        return START_NOT_STICKY
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // The full screen size, including the camera cutout area at the edge
    private fun screenWidth(): Int = fullScreenSize(windowManager).first
    private fun screenHeight(): Int = fullScreenSize(windowManager).second

    // Let a window go right up to the screen edges, even beside the camera cutout
    private fun allowScreenEdges(p: WindowManager.LayoutParams) {
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Android requires a small notification while an app runs in the background.
    // While screen capture is on, the notification must also say so.
    private fun updateForeground(capturing: Boolean) {
        val channelId = "bubble"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Tool bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("OSRS Bubble Tool")
            .setContentText(
                if (capturing) "Screen capture is on. Long-press the bubble → Close bubble to turn it off."
                else "Long-press the bubble for the tool menu"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (capturing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(1, notification, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && capturing ->
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(1, notification)
        }
    }

    // ---------------- Bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize(), bubbleSize(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(100)
        }
        allowScreenEdges(bubbleParams)
        restoreBubblePlace()

        // Tap = open/hide current tool, drag = move, long-press = tool menu
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            if (AppSettings.vibrates(this)) bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    unfadeBubble()
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    longPressed = false
                    bubble.postDelayed(longPress, AppSettings.longPressMs(this))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressed) return@setOnTouchListener true // menu is open; don't drag
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        bubble.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampBubble()   // stay on screen, so the next drag starts from where it really is
                        windowManager.updateViewLayout(bubble, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bubble.removeCallbacks(longPress)
                    if (dragging) saveBubblePlace()   // so it starts here next time
                    if (!dragging && !longPressed) toggleToolWindow()
                    fadeBubbleLater()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bubble.removeCallbacks(longPress)
                    fadeBubbleLater()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
        fadeBubbleLater()
    }

    private fun bubbleSize() = dp(AppSettings.bubbleSizeDp(this))

    // Settings → Fade when not in use: the bubble turns see-through a few seconds after you last touched it
    // (not while its menu is open), and solid again as soon as you touch it
    private val fadeBubble = Runnable {
        if (AppSettings.bubbleFades(this) && menu == null && !destroyed)
            bubble.animate().alpha(BUBBLE_FADED_ALPHA).setDuration(400).start()
    }

    private fun unfadeBubble() {
        bubble.removeCallbacks(fadeBubble)
        bubble.animate().cancel()
        bubble.alpha = 1f
    }

    private fun fadeBubbleLater() {
        bubble.removeCallbacks(fadeBubble)
        if (AppSettings.bubbleFades(this)) bubble.postDelayed(fadeBubble, BUBBLE_FADE_MS)
    }

    // ---------------- Long-press menu ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu() {
        hideMenu()
        val menuWidth = dp(MENU_WIDTH_DP)

        fun menuItem(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { hideMenu(); onClick() }
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hidden = AppSettings.hiddenTools(this@BubbleService)
            for (tool in Tool.entries) {
                if (!tool.inMenu) continue            // (Settings: at the bottom, below)
                if (tool.name in hidden) continue     // left out in Settings
                // the active tool shows in gold
                val color = if (tool == currentTool) "#E8C766".toColorInt() else Color.WHITE
                addView(menuItem(tool.label, color) { openTool(tool) })
            }
            // a line, then Settings and Close bubble, apart from the tools
            addView(View(this@BubbleService).apply { setBackgroundColor("#C9A24A".toColorInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
            val settingsColor = if (currentTool == Tool.SETTINGS) "#E8C766".toColorInt() else Color.WHITE
            addView(menuItem("⚙  Settings", settingsColor) { openTool(Tool.SETTINGS) })
            addView(menuItem("✕  Close bubble", "#E08A7A".toColorInt()) { stopSelf() })
        }

        // The list scrolls when there are more tools than fit on the screen
        val scroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor("#3E2C12".toColorInt())
                setStroke(dp(1), "#C9A24A".toColorInt())
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(1), dp(1), dp(1), dp(1))
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false   // always show the scrollbar so it's clear there's more
            addView(list)

            // tapping anywhere outside the menu closes it
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
                false
            }
        }

        // How tall the menu needs to be, limited to what fits on the screen
        list.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth - dp(2), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val screenH = screenHeight()
        // keep clear of the status bar and the navigation bar / gesture strip when they're showing
        var topInset = 0
        var bottomInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = windowManager.currentWindowMetrics.windowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
        }
        val margin = dp(8)
        val top = topInset + margin
        val bottom = screenH - bottomInset - margin
        // In portrait the whole list would reach down most of the screen; keep it to a size
        // that's easy to reach, and let it scroll
        val portrait = screenH > screenWidth()
        val limit = if (portrait) minOf(bottom - top, dp(MENU_MAX_PORTRAIT_DP)) else bottom - top
        val menuHeight = minOf(list.measuredHeight + dp(2), limit)
        scroll.isScrollbarFadingEnabled = list.measuredHeight + dp(2) <= menuHeight  // fade if nothing to scroll

        // place the menu beside the bubble, flipping to the left side if there's no room (a navigation bar down the
        // right side counts as no room: it would sit under it. Top and bottom use the insets above, which also keep
        // clear of the camera cutout, which the bars don't track.)
        val gap = dp(6)
        var x = bubbleParams.x + bubbleSize() + gap
        if (x + menuWidth > bars.clearRight(screenWidth())) x = maxOf(bars.left, bubbleParams.x - menuWidth - gap)

        val params = WindowManager.LayoutParams(
            menuWidth,
            menuHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            // start level with the bubble, but move up if it would run off the bottom
            y = bubbleParams.y.coerceIn(top, maxOf(top, bottom - menuHeight))
        }
        allowScreenEdges(params)

        windowManager.addView(scroll, params)
        menu = scroll
    }

    private fun hideMenu() {
        val wasOpen = menu != null
        menu?.let { windowManager.removeView(it) }
        menu = null
        if (wasOpen) fadeBubbleLater()
    }

    // ---------------- Tool windows ----------------

    // Shows a wiki page in the Wiki window (from the game's wiki button, for example)
    private var wikiWeb: WebView? = null
    private var pendingWikiUrl: String? = null

    // Opens a wiki page in the Wiki window. If another tool sent you there (returnTo), the ◀ button
    // on that first page takes you back to it.
    private fun openWikiLink(url: String, returnTo: Tool? = null) {
        hideMenu()
        val web = wikiWeb
        wikiReturnIndex = web?.copyBackForwardList()?.currentIndex ?: -1   // the page before the one we're opening
        if (web == null) pendingWikiUrl = url else web.loadUrl(url)   // built with this page if new
        if (currentTool != Tool.WIKI || shownWindow == null) openTool(Tool.WIKI)
        wikiReturnTo = returnTo
    }

    private var wikiReturnTo: Tool? = null
    private var wikiReturnIndex = -1

    // The Wiki window's ◀ button
    private fun wikiBack(web: WebView) {
        val back = wikiReturnTo
        if (back != null && web.copyBackForwardList().currentIndex <= wikiReturnIndex + 1) {
            wikiReturnTo = null
            openTool(back)
        } else if (web.canGoBack()) web.goBack()
    }

    private fun openTool(tool: Tool) {
        if (tool != Tool.WIKI) wikiReturnTo = null   // went somewhere else: forget the way back
        if (tool != Tool.WIKISYNC) syncReturnTo = null
        hideToolWindow()
        currentTool = tool
        showToolWindow()
    }

    // A tool's WikiSync tag was tapped: open WikiSync, with ◀ going back to that tool
    private fun openWikiSync(from: Tool) {
        openTool(Tool.WIKISYNC)
        syncReturnTo = from
    }

    private fun toggleToolWindow() {
        if (shownWindow != null) { hideToolWindow(); return }
        // the last tool (the Wiki at first) was left out of the menu in Settings: tapping the bubble opens the first
        // tool still in the menu instead (or Settings, if every tool was left out)
        val hidden = AppSettings.hiddenTools(this)
        if (currentTool.name in hidden) {
            currentTool = Tool.entries.firstOrNull { it.inMenu && it.name !in hidden } ?: Tool.SETTINGS
        }
        showToolWindow()
    }

    private fun showToolWindow() {
        // the phone's dark mode changed while a window was open (still waits if a puzzle guide is on screen)
        if (darkModeWaiting) applyDarkMode(fromPhone = true)
        // the tool text size changed while a guide was running: done now, unless one still is
        if (textSizeWaiting) rebuildForTextSize.run()
        val window = toolWindows.getOrPut(currentTool) { buildToolWindow(currentTool) }
        if (currentTool == Tool.GAMES) applyFrameless(window)
        val params = makeWindowParams()
        rememberWanted(params)
        placeClearOfBars(params)
        windowManager.addView(window, params)
        shownWindow = window
        shownParams = params
        setWebPagesRunning(true)
        // re-add the bubble so it stays on top of the window
        windowManager.removeView(bubble)
        windowManager.addView(bubble, bubbleParams)
    }

    private fun hideToolWindow() {
        if (compactSaved != null) setWindowCompact(COMPACT_OFF)   // a shrunk window opens whole, at its normal size, next time
        val wasShowing = shownWindow != null
        shownWindow?.let { windowManager.removeView(it) }
        shownWindow = null
        shownParams = null
        if (wasShowing) {
            setWebPagesRunning(false)
            toolParts[currentTool]?.onClosed?.invoke()
        }
    }

    // The Game Room's Zen Garden shows with no window around it: no top bar or background. Its move and resize
    // symbols are carved into the garden frame's top-right and bottom-right corners (SandGardenView draws them), so
    // the window's own move and resize corners become invisible touch areas the size of those carved corners, on the
    // tray's corners (see pinGamesCorners). Asked for while the window is being built, so it's remembered and
    // applied once the window exists.
    private var gamesFrameless = false

    private fun setGamesFrameless(on: Boolean) {
        gamesFrameless = on
        toolWindows[Tool.GAMES]?.let { applyFrameless(it) }
    }

    private fun applyFrameless(window: LinearLayout) {
        val on = gamesFrameless
        window.getChildAt(0).visibility = if (on) View.GONE else View.VISIBLE
        window.background?.alpha = if (on) 0 else 255
        (window.getChildAt(1) as? ViewGroup)?.let { body ->
            if (body.childCount < 3) return@let
            val corner = body.getChildAt(1)
            val mover = body.getChildAt(2)
            mover.visibility = if (on) View.VISIBLE else View.GONE
            // carved into the frame (invisible here, and only their triangle answers), or the window's own symbols
            val carved = dp((SandGardenView.CORNER_DP + SandGardenView.FRAME_DP).roundToInt())
            for ((v, size) in listOf(corner to dp(26), mover to dp(30))) {
                v.tag = if (on) CARVED else null
                (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.width = if (on) carved else size
                    it.height = if (on) carved else size
                    v.layoutParams = it
                }
                v.invalidate()
            }
        }
        if (!on) pinGamesCorners(-1, -1, -1, -1)   // the resize corner back in the window's corner
    }

    // The Zen Garden's tray may not fill its window (it keeps its shape when scaled): put the move and resize
    // symbols on the tray's own top-right and bottom-right corners (given in the window's content; -1 = the window's)
    private fun pinGamesCorners(left: Int, top: Int, right: Int, bottom: Int) {
        val body = toolWindows[Tool.GAMES]?.getChildAt(1) as? ViewGroup ?: return
        if (body.childCount < 3) return
        val corner = body.getChildAt(1)
        val mover = body.getChildAt(2)
        gamesTray[0] = left; gamesTray[1] = top; gamesTray[2] = right; gamesTray[3] = bottom
        // the window shrinks to the tray (then the symbols sit in its own corners)
        val reset = right < 0 || (!resizingWindow && fitGamesWindowToTray())
        (corner.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.rightMargin = if (reset) 0 else maxOf(0, body.width - right)
            it.bottomMargin = if (reset) 0 else maxOf(0, body.height - bottom)
            corner.layoutParams = it
        }
        (mover.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.rightMargin = if (reset) 0 else maxOf(0, body.width - right)
            it.topMargin = if (reset) 0 else maxOf(0, top)
            mover.layoutParams = it
        }
    }

    // A garden scaled to fit a window of a different shape leaves a see-through band beside it, which you couldn't
    // move off the screen edge (so the garden couldn't reach the top) and which caught taps meant for the game.
    // So in zen mode the window is shrunk to the tray itself, keeping the tray where it is on screen. Done when the
    // tray is placed, but not while the resize corner is being dragged (it's done when you let go instead).
    // Returns true if the window was shrunk.
    private val gamesTray = IntArray(4) { -1 }   // the tray's edges in the window, last time it was placed
    private var resizingWindow = false

    private fun fitGamesWindowToTray(): Boolean {
        if (!gamesFrameless || currentTool != Tool.GAMES) return false
        val window = shownWindow ?: return false
        if (window !== toolWindows[Tool.GAMES]) return false
        val params = shownParams ?: return false
        val body = window.getChildAt(1) as? ViewGroup ?: return false
        val (l, t, r, b) = gamesTray
        if (l !in 0 until r || b <= t) return false
        // already a snug fit (a few pixels either way is just rounding)
        val slack = dp(4)
        if (l < slack && t < slack && body.width - r < slack && body.height - b < slack) return false
        // made smaller or moved for now to stay clear of the status or navigation bar: left as it is, so it goes back
        // to the size and place you gave it when the bars hide (it's shrunk to the tray then)
        if (params.x != shownWantX || params.y != shownWantY || params.width != shownWantW || params.height != shownWantH)
            return false
        params.x += l
        params.y += t
        params.width = r - l
        params.height = b - t
        windowX = params.x
        windowY = params.y
        rememberWanted(params)
        windowManager.updateViewLayout(window, params)
        return true
    }

    // A tool asks for its window to shrink to just what's in it (no title bar) in a corner (COMPACT_TOP_LEFT or
    // COMPACT_BOTTOM_LEFT), so the game can be seen while it shows its answer; or (COMPACT_OFF) to go back to
    // where and how big it was
    private var compactSaved: IntArray? = null   // x, y, width, height, and the wanted x, y, width, height before shrinking
    private var compactPlace = COMPACT_OFF       // which corner it's shrunk into (to put it back there when the phone turns)

    // A shrunk window's saved place and size (compactSaved) become `fresh`: where it goes when it's done shrinking
    private fun backToSize(saved: IntArray, fresh: WindowManager.LayoutParams) {
        saved[0] = fresh.x; saved[1] = fresh.y; saved[2] = fresh.width; saved[3] = fresh.height
        saved[4] = fresh.x; saved[5] = fresh.y; saved[6] = fresh.width; saved[7] = fresh.height
    }

    private fun setWindowCompact(place: Int) {
        val on = place != COMPACT_OFF
        val params = shownParams ?: return
        val window = shownWindow ?: return
        val bar = window.getChildAt(0)
        val corner = (window.getChildAt(1) as? ViewGroup)?.let { if (it.childCount > 1) it.getChildAt(1) else null }
        if (!on) {
            val saved = compactSaved ?: return
            compactSaved = null
            compactPlace = COMPACT_OFF
            bar.visibility = View.VISIBLE
            corner?.visibility = View.VISIBLE
            params.x = saved[0]; params.y = saved[1]; params.width = saved[2]; params.height = saved[3]
            shownWantX = saved[4]; shownWantY = saved[5]; shownWantW = saved[6]; shownWantH = saved[7]
            placeClearOfBars(params)   // (the bars may have come or gone meanwhile)
            windowManager.updateViewLayout(window, params)
            return
        }
        if (compactSaved == null) compactSaved = intArrayOf(params.x, params.y, params.width, params.height,
            shownWantX, shownWantY, shownWantW, shownWantH)
        compactPlace = place
        bar.visibility = View.GONE
        corner?.visibility = View.GONE
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.x = bars.left
        params.y = bars.top
        shownWantX = 0
        shownWantY = 0
        shownWantW = ViewGroup.LayoutParams.WRAP_CONTENT
        shownWantH = ViewGroup.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(window, params)
        if (place == COMPACT_BOTTOM_LEFT) {
            // down to the bottom once it knows how tall it is (still measured from the top, so dragging works)
            window.post {
                if (shownWindow !== window || compactSaved == null) return@post
                params.y = maxOf(bars.top, bars.clearBottom(screenHeight()) - window.height)
                shownWantY = params.y
                windowManager.updateViewLayout(window, params)
            }
        }
    }

    // Web pages (Wiki, GE Prices, star tracker...) keep running their scripts and live updates
    // even when their window is hidden. Only the page in the window you're looking at runs; the rest are paused.
    private fun setWebPagesRunning(running: Boolean) {
        val window = shownWindow
        val visible = if (running && window != null) webViews.filter { isInside(it, window) } else emptyList()
        webViews.forEach { if (it in visible) it.onResume() else it.onPause() }
        // these two affect every page at once
        if (visible.isNotEmpty()) webViews.first().resumeTimers() else webViews.firstOrNull()?.pauseTimers()
    }

    private fun isInside(v: View, window: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === window) return true; p = p.parent }
        return false
    }

    // Where the bubble was left, remembered separately for landscape and portrait. Saved as how far
    // across and down the screen it is, so it lands in the same spot even if the screen size changes.
    private val bubblePlace by lazy { getSharedPreferences("bubble_place", MODE_PRIVATE) }
    private fun placeKey() = if (screenWidth() > screenHeight()) "land" else "port"

    private fun saveBubblePlace() {
        val size = bubbleSize()
        val across = bubbleParams.x.toFloat() / maxOf(1, screenWidth() - size)
        val down = bubbleParams.y.toFloat() / maxOf(1, screenHeight() - size)
        bubblePlace.edit { putFloat("x_" + placeKey(), across).putFloat("y_" + placeKey(), down) }
    }

    private fun restoreBubblePlace() {
        val key = placeKey()
        // not moved in this orientation yet: use where it was in the other one
        val use = if (bubblePlace.contains("x_$key")) key else if (key == "land") "port" else "land"
        if (!bubblePlace.contains("x_$use")) return   // never moved: keep the starting spot
        val size = bubbleSize()
        bubbleParams.x = (bubblePlace.getFloat("x_$use", 0f) * (screenWidth() - size)).toInt()
        bubbleParams.y = (bubblePlace.getFloat("y_$use", 0f) * (screenHeight() - size)).toInt()
        clampBubble()
    }

    private fun clampBubble() {
        // (never under the status bar or the navigation bar while they're showing, where it couldn't be touched)
        val (sw, sh) = fullScreenSize(windowManager)   // (both at once: asking for each works it all out twice)
        val spot = ScreenBars.keepBubble(bubbleParams.x, bubbleParams.y, bubbleSize(), sw, sh, bars)
        bubbleParams.x = spot.x
        bubbleParams.y = spot.y
    }

    // ---------------- Keeping clear of the status bar and the navigation bar ----------------

    // Android draws the status bar (along the top) and the navigation bar (the home and back buttons, or the gesture
    // strip: along the bottom, or down one side in landscape) on top of everything, so anything under them can't be
    // touched. While the game is open the phone hides them and you can use the whole screen; elsewhere (the app's
    // own screen, the home screen) they show, and windows and the bubble are kept clear of them.
    private var bars = Bars()        // how far each bar reaches in right now (all 0 = hidden; the maths: ScreenBars)
    private var shownWantX = 0       // where you put the open window; it goes back there when the bars hide
    private var shownWantY = 0
    private var shownWantW = 0       // and how big you made it (it may be smaller for a while to fit between the bars)
    private var shownWantH = 0

    // You moved or resized the open window (or it opened): this is where and how big you want it
    private fun rememberWanted(params: WindowManager.LayoutParams) {
        shownWantX = params.x
        shownWantY = params.y
        shownWantW = params.width
        shownWantH = params.height
    }

    // Overlay windows aren't told when the bars come and go, so two invisible strips watch for them. Android keeps
    // them clear of the bars like a normal window. One is a pixel wide, from the top of the screen to the bottom:
    // when the status bar appears its top gets pushed down, and when the navigation bar appears along the bottom
    // its bottom gets pushed up. The other is a pixel tall, across the top of the screen: a navigation bar down
    // one side pushes in that end. Taps go straight through them.
    private val barProbes = mutableListOf<View>()

    private fun addBarProbes() {
        addBarProbe(across = false)
        addBarProbe(across = true)
    }

    private fun addBarProbe(across: Boolean) {
        val probe = View(this)
        val p = WindowManager.LayoutParams(
            if (across) ViewGroup.LayoutParams.MATCH_PARENT else 1,
            if (across) 1 else ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f   // fully see-through, so Android lets taps through to the game
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // only the bars push it (not the camera cutout)
                fitInsetsTypes = if (across) android.view.WindowInsets.Type.navigationBars()
                                 else android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        // the strip changes size whenever a bar appears or hides. Read just after, not in the middle of the layout:
        // when the phone turns, the strip can be laid out before the screen size has caught up.
        probe.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.removeCallbacks(checkBars)
            v.post(checkBars)
        }
        windowManager.addView(probe, p)
        barProbes.add(probe)
    }

    // Works out from both strips how far each bar reaches in (also checked again after the phone turns)
    private val checkBars = Runnable {
        if (destroyed || barProbes.size < 2) return@Runnable
        val down = barProbes[0]
        val across = barProbes[1]
        if (down.height == 0 || across.width == 0) return@Runnable   // not laid out yet
        val loc = IntArray(2)
        down.getLocationOnScreen(loc)
        val downTop = loc[1]
        across.getLocationOnScreen(loc)
        // (null: read while the phone turns, against the old screen size: the layout that follows, or the check after
        // the turn, reads them again)
        val (sw, sh) = fullScreenSize(windowManager)
        val now = ScreenBars.fromStrips(downTop, down.height, loc[0], across.width, sw, sh)
            ?: return@Runnable
        onBarsChanged(now)
    }

    // How far the bars reach in, read from the strips: if a bar appeared or hid, everything moves
    private fun onBarsChanged(now: Bars) {
        if (now == bars) return
        bars = now
        // move things just after this, not while Android is in the middle of laying out windows
        bubble.post {
            if (destroyed) return@post
            // the bubble: clear of the bars while they show, back where you left it when they hide
            restoreBubblePlace()
            clampBubble()
            windowManager.updateViewLayout(bubble, bubbleParams)
            val params = shownParams ?: return@post
            // a shrunk window (a ToA answer) goes back into its corner, against the bars as they are now (after the
            // phone turns, the new bars are only read a moment after it was put there)
            if (compactSaved != null) { setWindowCompact(compactPlace); return@post }
            placeClearOfBars(params)
            shownWindow?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    // Puts the open window where you left it (shownWantX/Y/W/H), but clear of the bars that are showing: moved out
    // from under them, and if it's then too big to fit, made smaller for now. It goes back to its own place and
    // size when they hide again.
    private fun placeClearOfBars(params: WindowManager.LayoutParams) {
        // (a window as big as what's in it, like a shrunk one, is kept clear once its size is known)
        val (sw, sh) = fullScreenSize(windowManager)
        val b = ScreenBars.place(Box(shownWantX, shownWantY, shownWantW, shownWantH), shownWindow?.width ?: 0,
            shownWindow?.height ?: 0, sw, sh, bars)
        params.x = b.x
        params.y = b.y
        if (shownWantW > 0) params.width = b.w
        if (shownWantH > 0) params.height = b.h
    }

    private fun buildToolWindow(tool: Tool): LinearLayout {
        val parts = partsFor(tool)
        return buildFrame(parts.buildView(), parts.onBack)
    }

    // Every tool, and how the bubble works with it. This is the one place a new tool gets set up.
    private fun makeParts(tool: Tool): ToolParts = when (tool) {
        Tool.WIKI -> ToolParts(
            buildView = {
                buildWebView(pendingWikiUrl ?: WIKI_URL, PAGE_ZOOM_PERCENT).also { wikiWeb = it; pendingWikiUrl = null }
            },
            onBack = { wikiWeb?.let { wikiBack(it) } }
        )
        Tool.XP_CALCULATOR -> webPage(XP_CALCULATOR_URL, XP_CALCULATOR_ZOOM_PERCENT)
        Tool.SHOOTING_STARS -> webPage(SHOOTING_STARS_URL, SHOOTING_STARS_ZOOM_PERCENT)
        Tool.PRICES -> webPage(PRICES_URL, PRICES_ZOOM_PERCENT)
        Tool.DPS_CALCULATOR -> DpsTool(toolContext, windowManager, capture, ::setOverlaysVisible).let { t ->
            var web: WebView? = null
            ToolParts(
                buildView = { t.buildView(buildWebView(DPS_CALCULATOR_URL, DPS_ZOOM_PERCENT).also { web = it }) },
                onBack = { web?.let { if (it.canGoBack()) it.goBack() } },
                onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy
            )
        }
        Tool.PUZZLE_BOX -> PuzzleBoxTool(toolContext, windowManager, capture, ::setOverlaysVisible) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy,
                isBusy = t::isGuiding)
        }
        // (closing the Light Box window keeps it solving: the instructions stay on the light box; tap the bubble to
        // bring the window back, and Stop to end it)
        Tool.LIGHT_BOX -> LightBoxTool(toolContext, windowManager, capture, ::setOverlaysVisible) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onRotated = t::onRotated, onDestroy = t::destroy, isBusy = t::isGuiding)
        }
        Tool.INVENTORY_SETUPS -> GearLoadoutsTool(toolContext, capture, ::setOverlaysVisible, hideWindow = { hideToolWindow() },
            showWindow = { if (shownWindow == null) openTool(Tool.INVENTORY_SETUPS) }).let { t ->
            ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed, onDestroy = t::destroy)
        }
        Tool.ZULRAH -> ZulrahTool(toolContext).let { t -> ToolParts(t::buildView, t::goBack) }
        Tool.TOA_PUZZLES -> ToaPuzzleTool(toolContext, capture, ::setOverlaysVisible, ::setWindowCompact).let { t ->
            ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy)
        }
        Tool.FARMING -> FarmingTool(toolContext).let { t ->
            ToolParts(t::buildView, onBack = null, onClosed = t::onWindowClosed, onDestroy = t::destroy)
        }
        Tool.WIKISYNC -> ToolParts(WikiSyncTool(toolContext)::buildView, onBack = { syncReturnTo?.let { openTool(it) } })
        Tool.QUESTS -> QuestHelperTool(toolContext, openWiki = { url -> openWikiLink(url, returnTo = Tool.QUESTS) },
            hideWindow = { hideToolWindow() }, openWikiSync = { openWikiSync(Tool.QUESTS) }).let { t ->
            ToolParts(t::buildView, t::goBack)
        }
        Tool.CALCULATOR -> ToolParts(CalculatorTool(toolContext)::buildView, onBack = null)
        Tool.NOTES -> NotesTool(toolContext).let { t -> ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed) }
        Tool.GAMES -> GameRoomTool(toolContext, ::setGamesFrameless, ::pinGamesCorners).let { t ->
            ToolParts(t::buildView, t::goBack, onDestroy = t::destroy)
        }
        Tool.HUNTER -> HunterRumourTool(toolContext, openWikiSync = { openWikiSync(Tool.HUNTER) }).let { t ->
            ToolParts(t::buildView, t::goBack)
        }
        Tool.TELEPORTS -> TeleportFinderTool(toolContext, openWikiSync = { openWikiSync(Tool.TELEPORTS) }).let { t ->
            ToolParts(t::buildView, t::goBack, onDestroy = t::destroy)
        }
        Tool.SETTINGS -> ToolParts(SettingsTool(toolContext)::buildView, onBack = null)
    }

    // A tool that's just a web page, with ◀ going back a page
    private fun webPage(url: String, zoomPercent: Int): ToolParts {
        var web: WebView? = null
        return ToolParts(
            buildView = { buildWebView(url, zoomPercent).also { web = it } },
            onBack = { web?.let { if (it.canGoBack()) it.goBack() } }
        )
    }

    // The shared window frame: button bar on top, tool content below
    @SuppressLint("ClickableViewAccessibility")
    private fun buildFrame(content: View, onBack: (() -> Unit)?): LinearLayout {
        fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(BAR_BUTTON_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val backButton = barButton("◀") { onBack?.invoke() }
        if (onBack == null) backButton.visibility = View.INVISIBLE // keep its space, hide it
        val closeButton = barButton("✕") { hideToolWindow() }

        // Middle section: drag left/right to move the window
        val dragHandle = TextView(this).apply {
            text = "↔"
            textSize = 15f
            setTextColor("#D8C8A8".toColorInt())
            gravity = Gravity.CENTER
            setBackgroundColor("#54401F".toColorInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startWindowX = 0
        var startWindowY = 0
        val canResize = isResizable(currentTool)
        if (canResize) dragHandle.text = "✥"   // these windows move up and down too
        dragHandle.setOnTouchListener { _, event ->
            val params = shownParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = params.x
                    startWindowY = params.y
                }
                MotionEvent.ACTION_MOVE -> moveWindowTo(params, startWindowX + (event.rawX - downRawX).toInt(),
                    startWindowY + (event.rawY - downRawY).toInt(), moveY = isResizable(currentTool))
            }
            true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor("#3E2C12".toColorInt())
            addView(backButton)
            addView(dragHandle)
            addView(closeButton)
        }

        // The content, with a resize corner at the bottom right (for the tools that can be resized)
        val body = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (canResize) addView(resizeCorner(), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END))
            // the move symbol, only shown in a window without its top bar (see setGamesFrameless)
            if (canResize) addView(moveCorner().apply { visibility = View.GONE }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END))
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.WINDOW)
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // ---------------- Resizing windows ----------------

    // The Light Box and Puzzle Box Solvers are already small
    private fun isResizable(tool: Tool) = tool != Tool.LIGHT_BOX && tool != Tool.PUZZLE_BOX

    // Each tool remembers its own size, separately for landscape and portrait
    private fun sizeKey(tool: Tool): String {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return tool.name + if (landscape) "_land" else "_port"
    }

    private val minWindowWidth get() = dp(150)
    private val minWindowHeight get() = dp(140)

    // Moves the open window while it's dragged (by its top bar or its move symbol), up and down too if moveY. Not under
    // the status bar or the navigation bar while they're showing (you couldn't grab it there).
    private fun moveWindowTo(params: WindowManager.LayoutParams, x: Int, y: Int, moveY: Boolean) {
        val (sw, sh) = fullScreenSize(windowManager)   // (once per finger movement, both at once)
        val spot = ScreenBars.drag(x, y, params.width, params.height, sw, sh, bars, moveY)
        params.x = spot.x
        if (currentTool != Tool.LIGHT_BOX && currentTool != Tool.PUZZLE_BOX) windowX = params.x  // others share one position
        shownWantX = params.x
        if (moveY) {
            params.y = spot.y
            windowY = params.y
            shownWantY = params.y
        }
        shownWindow?.let { windowManager.updateViewLayout(it, params) }
    }

    // The move symbol (for windows without a top bar): drag it to move the window anywhere
    @SuppressLint("ClickableViewAccessibility")
    private fun moveCorner(): View = object : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }
        private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = "#B33E2C12".toColorInt() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (tag == CARVED) return   // (carved into the garden's frame instead)
            val w = width.toFloat()
            val c = w / 2
            // a dark circle with a four-way arrow, like the top bar's move symbol
            canvas.drawCircle(c, c, c * 0.95f, back)
            paint.color = "#F2E3C0".toColorInt()
            val r = w * 0.3f
            val head = w * 0.1f
            canvas.drawLine(c - r, c, c + r, c, paint)
            canvas.drawLine(c, c - r, c, c + r, paint)
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val tx = c + dx * r; val ty = c + dy * r
                canvas.drawLine(tx, ty, tx - dx * head + dy * head, ty - dy * head + dx * head, paint)
                canvas.drawLine(tx, ty, tx - dx * head - dy * head, ty - dy * head - dx * head, paint)
            }
        }
    }.apply {
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        setOnTouchListener { _, e ->
            val params = shownParams ?: return@setOnTouchListener false
            // carved into the garden's frame: only its top-right triangle (the sand beside it is still raked)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && tag == CARVED && (width - e.x) + e.y > width * 1.1f)
                return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> moveWindowTo(params, startX + (e.rawX - downRawX).toInt(),
                    startY + (e.rawY - downRawY).toInt(), moveY = true)
            }
            true
        }
    }

    // Drag the corner to resize. Double-tap it to go back to the normal size.
    @SuppressLint("ClickableViewAccessibility")
    private fun resizeCorner(): View = object : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = "#B33E2C12".toColorInt() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (tag == CARVED) return   // (carved into the garden's frame instead)
            val w = width.toFloat(); val h = height.toFloat()
            // a dark quarter-circle in the corner with three diagonal lines, like a window's resize grip
            canvas.drawCircle(w, h, w * 0.95f, back)
            paint.color = "#F2E3C0".toColorInt()
            for (i in 1..3) {
                val d = w * 0.22f * i
                canvas.drawLine(w - d, h - dp(3), w - dp(3), h - d, paint)
            }
        }
    }.apply {
        var downX = 0f; var downY = 0f
        var startW = 0; var startH = 0
        var lastTap = 0L
        var moved = false
        setOnTouchListener { _, e ->
            val params = shownParams ?: return@setOnTouchListener false
            // carved into the garden's frame: only its bottom-right triangle (the sand beside it is still raked)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && tag == CARVED && (width - e.x) + (height - e.y) > width * 1.1f)
                return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startW = params.width; startH = params.height
                    moved = false
                    resizingWindow = true
                }
                MotionEvent.ACTION_CANCEL -> resizingWindow = false
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    if (moved) {
                        val (sw, sh) = fullScreenSize(windowManager)
                        val size = ScreenBars.resize(startW + dx, startH + dy, params.x, params.y, minWindowWidth, minWindowHeight,
                            sw, sh, bars)
                        params.width = size.w
                        params.height = size.h
                        shownWantW = params.width
                        shownWantH = params.height
                        shownWindow?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    resizingWindow = false
                    // the Zen Garden: once the tray has settled into the new size, the window shrinks to it
                    if (gamesFrameless) postDelayed({ fitGamesWindowToTray() }, 150)
                    if (moved) {
                        sizes.edit {
                            putInt(sizeKey(currentTool) + "_w", params.width)
                            putInt(sizeKey(currentTool) + "_h", params.height)
                        }
                        refitWebPages()
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) {
                            // double-tap: back to the normal size
                            sizes.edit { remove(sizeKey(currentTool) + "_w").remove(sizeKey(currentTool) + "_h") }
                            windowY = null   // and back to the middle of the screen
                            val fresh = makeWindowParams()
                            rememberWanted(fresh)
                            placeClearOfBars(params)
                            shownWindow?.let { windowManager.updateViewLayout(it, params) }
                            refitWebPages()
                            lastTap = 0
                        } else lastTap = now
                    }
                }
            }
            true
        }
    }

    // Web pages are shrunk to fit the window's width, so fit them again after a resize
    private fun refitWebPages() {
        val window = shownWindow ?: return
        for (web in webViews) {
            if (!isInside(web, window)) continue
            val fit = if (web.url?.contains("prices.runescape.wiki") == true) FIT_BOTH_SIDES_JS else FIT_WIDTH_JS
            web.postDelayed({ web.evaluateJavascript(fit, null) }, 150)
        }
    }

    // Size and position of tool windows, kept inside the screen edges
    private fun makeWindowParams(): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val screenW = screenWidth()
        val screenH = screenHeight()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Light Box Solver: a small box in the top-right corner, only as tall as it needs,
        // so it stays clear of the light box (which opens in the middle of the game)
        if (currentTool == Tool.LIGHT_BOX || currentTool == Tool.PUZZLE_BOX) {
            val inches = if (currentTool == Tool.LIGHT_BOX) LIGHT_BOX_WIDTH_INCHES else PUZZLE_BOX_WIDTH_INCHES
            val w = (inches * metrics.xdpi).toInt().coerceAtMost(screenW)
            val margin = dp(8)
            return WindowManager.LayoutParams(
                w,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                x = maxOf(0, screenW - w - margin)
                y = margin
                allowScreenEdges(this)
            }
        }

        val width = when {
            currentTool == Tool.ZULRAH -> (ZULRAH_WIDTH_INCHES * metrics.xdpi).toInt()
            !landscape -> (screenW * 0.9f).toInt()
            currentTool == Tool.DPS_CALCULATOR -> (screenW * DPS_WIDTH_FRACTION).toInt()
            else -> (AppSettings.windowWidthInches(this) * metrics.xdpi).toInt()
        }.coerceAtMost(screenW)

        var w = width
        // The Notepad starts at the top, only as tall as the space above the keyboard (about the top half),
        // so the keyboard doesn't cover what you're typing
        val notesTop = NotesTool.topOffset(this)
        var h = if (currentTool == Tool.NOTES) ((screenH * 0.48f).toInt() - notesTop).coerceAtLeast(minWindowHeight)
                else (screenH * 0.9f).toInt()
        // a size you've dragged this tool to
        if (isResizable(currentTool)) {
            val key = sizeKey(currentTool)
            if (sizes.contains(key + "_w")) {
                w = sizes.getInt(key + "_w", w).coerceIn(minOf(minWindowWidth, screenW), screenW)
                h = sizes.getInt(key + "_h", h).coerceIn(minOf(minWindowHeight, screenH), screenH)
            }
        }
        windowX = windowX.coerceIn(0, maxOf(0, screenW - w))

        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // touches outside still reach the game
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = windowX
            // windows sit where you last dragged them. To start with they're centred, except the Notepad,
            // which starts at the top, just below the status bar (it catches touches at the very top).
            val start = if (currentTool == Tool.NOTES) notesTop else (screenH - h) / 2
            y = (windowY ?: start).coerceIn(0, maxOf(0, screenH - h))
            allowScreenEdges(this)
        }
    }

    // Hide/show everything we draw, so it doesn't end up in screenshots
    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        bubble.visibility = v
        shownWindow?.visibility = v
        menu?.visibility = v
    }

    // ---------------- Web tools: OSRS Wiki and XP Calculator ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(startUrl: String, textZoomPercent: Int): WebView {
        // A web page shows its dark look when the app around it has a dark theme (websites without a
        // dark look stay as they are)
        val theme = if (Palette.dark) android.R.style.Theme_DeviceDefault_NoActionBar
                    else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        // the wiki's own theme: dark with dark mode on; back to light if dark mode set it dark before (even if dark
        // mode was turned off while the bubble was closed)
        if (Palette.dark) setWikiTheme(dark = true) else if (wikiThemeSetDark()) setWikiTheme(dark = false)
        val web = WebView(android.view.ContextThemeWrapper(this, theme)).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (Palette.dark) setBackgroundColor(Palette.WINDOW)   // no white flash while a page loads
            settings.builtInZoomControls = true   // allow pinch-to-zoom
            settings.displayZoomControls = false  // hide the +/- zoom buttons
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url?.contains("prices.runescape.wiki") == true) view.evaluateJavascript(PASSIVE_TOUCH_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url?.contains("prices.runescape.wiki") == true) view.evaluateJavascript(CHART_SCROLL_FIX_JS, null)
                    view.evaluateJavascript(INLINE_DROPDOWNS_JS, null)
                    if (url?.contains("07.gg") == true) view.evaluateJavascript(STAR_DETAILS_FIX_JS, null)
                    // Shrink the page so its full width fits the window.
                    // Runs twice: once quickly, and again after images and tables have loaded.
                    val fit = if (url?.contains("prices.runescape.wiki") == true) FIT_BOTH_SIDES_JS else FIT_WIDTH_JS
                    view.postDelayed({ view.evaluateJavascript(fit, null) }, 300)
                    view.postDelayed({ view.evaluateJavascript(fit, null) }, 1500)
                }
            }
            loadUrl(startUrl)
        }
        webViews.add(web)
        webZoom[web] = textZoomPercent
        applyTextSize(web)
        return web
    }

    // The page's own text size, made bigger or smaller by the Settings text size
    private fun applyTextSize(web: WebView) {
        val base = webZoom[web] ?: return
        web.settings.textZoom = base * AppSettings.textSize(this) / AppSettings.TEXT_NORMAL
    }

    // ---------------- Settings ----------------

    // A setting was changed (in the Settings window, or on the app's screen)
    private val settingsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (destroyed) return@OnSharedPreferenceChangeListener
        when (key) {
            AppSettings.DARK_MODE -> applyDarkMode()
            AppSettings.TEXT_SIZE -> {
                webViews.forEach { applyTextSize(it) }
                refitWebPages()
            }
            AppSettings.TOOL_TEXT_SIZE -> {
                // built again once the − and + taps stop, not after every tap (it reloads the web pages too)
                bubble.removeCallbacks(rebuildForTextSize)
                bubble.postDelayed(rebuildForTextSize, TEXT_SIZE_WAIT_MS)
            }
            AppSettings.BUBBLE_SIZE -> {
                bubbleParams.width = bubbleSize()
                bubbleParams.height = bubbleSize()
                restoreBubblePlace()
                clampBubble()
                windowManager.updateViewLayout(bubble, bubbleParams)
            }
            AppSettings.BUBBLE_FADE -> if (AppSettings.bubbleFades(this)) fadeBubbleLater() else unfadeBubble()
            AppSettings.WINDOW_WIDTH -> resetShownWindow()
            AppSettings.WINDOWS_RESET -> {
                // every window back to its normal size (Settings already forgot the sizes), in the middle of the screen
                windowX = dp(8)
                windowY = null
                resetShownWindow()
            }
        }
    }

    private val rebuildForTextSize = Runnable {
        if (destroyed) return@Runnable
        // a puzzle guide is on screen with its window closed: building the tools again would stop it, so that waits
        // until a window is next opened with no guide running (see showToolWindow)
        textSizeWaiting = toolParts.values.any { it.isBusy() }
        if (textSizeWaiting) return@Runnable
        updateToolTextSize()
        rebuildToolWindows()
    }
    private var textSizeWaiting = false

    // The open window goes to its normal place and size again (after a Settings change to those)
    private fun resetShownWindow() {
        val params = shownParams ?: return
        val window = shownWindow ?: return
        val fresh = makeWindowParams()
        compactSaved?.let { saved ->
            // a shrunk window stays shrunk, but when it's done it goes back to the new normal size and place
            backToSize(saved, fresh)
            return
        }
        rememberWanted(fresh)
        placeClearOfBars(params)
        windowManager.updateViewLayout(window, params)
        refitWebPages()
    }

    // Dark mode turned on or off: every tool window is built again in the new colours.
    // fromPhone: the phone's own dark mode changed ("Match phone", perhaps on a timer at sunset). Building the tools
    // again would stop whatever the open one is doing (a ToA Watch, a puzzle guide), so that waits until no tool
    // window is open (see showToolWindow).
    private var darkModeWaiting = false

    private fun applyDarkMode(fromPhone: Boolean = false) {
        val dark = AppSettings.isDark(this)
        darkModeWaiting = false
        if (dark == builtDark) { Palette.dark = dark; return }
        if (fromPhone && (shownWindow != null || toolParts.values.any { it.isBusy() })) { darkModeWaiting = true; return }
        Palette.dark = dark
        builtDark = dark
        rebuildToolWindows()   // (the web pages built again set the wiki's theme)
    }

    // The OSRS Wiki has a dark look of its own, but doesn't follow the app's theme: it remembers your choice in
    // a "theme" cookie (the one its own theme menu sets; see meta.weirdgloop.org/w/Themes). This sets it. Only
    // done when dark mode is on or has been turned off, so with dark mode off a theme picked in the wiki's own menu
    // stays. Whether the app last set it dark is remembered, so it's set back to light even if dark mode was turned
    // off while the bubble was closed.
    private val wikiThemePrefs by lazy { getSharedPreferences("wiki_theme", MODE_PRIVATE) }
    private fun wikiThemeSetDark() = wikiThemePrefs.getBoolean("set_dark", false)

    private fun setWikiTheme(dark: Boolean) {
        val cookies = android.webkit.CookieManager.getInstance()
        cookies.setCookie(WIKI_URL, "theme=${if (dark) "dark" else "light"}; path=/; max-age=315360000")
        cookies.flush()
        wikiThemePrefs.edit { putBoolean("set_dark", dark) }
    }

    // Puts every tool away and starts them afresh (each is set up again when next opened, like after starting
    // the bubble). The window that was open opens again, and the Wiki keeps the page it was on.
    private fun rebuildToolWindows() {
        val reopen = if (shownWindow != null) currentTool else null
        val wikiPage = wikiWeb?.url
        hideMenu()
        hideToolWindow()
        toolParts.values.forEach { it.onDestroy() }
        toolParts.clear()
        toolWindows.clear()
        webViews.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        webViews.clear()
        webZoom.clear()
        wikiWeb = null
        pendingWikiUrl = wikiPage
        wikiReturnTo = null
        syncReturnTo = null
        gamesFrameless = false
        reopen?.let { openTool(it) }
    }

    // ---------------- Housekeeping ----------------

    // Resize the window when the phone rotates
    private var lastScreenSize = 0 to 0

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (scaledResources != null) updateToolTextSize()   // (so the tools see the new orientation too)
        // the phone's dark mode, for the "Match phone" setting
        if (AppSettings.darkMode(this) == AppSettings.MATCH_PHONE) applyDarkMode(fromPhone = true)
        // only a rotation (or folding the phone) moves things; dark mode, font or language changes don't
        val size = screenWidth() to screenHeight()
        if (size == lastScreenSize) return
        lastScreenSize = size
        // the bars, read again against the new screen size (in case the strips moved before it had changed)
        barProbes.firstOrNull()?.let { it.removeCallbacks(checkBars); it.post(checkBars) }
        hideMenu()
        clampBubble()
        windowManager.updateViewLayout(bubble, bubbleParams)
        toolParts.values.forEach { it.onRotated() }
        val shrunk = compactSaved
        if (shownWindow != null && shrunk != null) {
            // a shrunk window (a ToA answer) stays shrunk in its corner; when it's done it goes back to its
            // normal size and place for the new way round, not the old one's
            val fresh = makeWindowParams()
            backToSize(shrunk, fresh)
            setWindowCompact(compactPlace)
        } else if (shownWindow != null) {
            val params = makeWindowParams()
            rememberWanted(params)
            placeClearOfBars(params)
            shownParams = params
            shownWindow?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        // close the window first, while the tools can still tidy up after it
        hideMenu()
        hideToolWindow()
        toolParts.values.forEach { it.onDestroy() }
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsChanged)
        capture.shutdown()
        windowManager.removeView(bubble)
        barProbes.forEach { windowManager.removeView(it) }
        webViews.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        super.onDestroy()
    }
}
