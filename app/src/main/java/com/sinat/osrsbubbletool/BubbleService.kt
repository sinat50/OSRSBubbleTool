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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        const val WIKI_URL = "https://oldschool.runescape.wiki/"
        const val XP_CALCULATOR_URL = "https://oldschool.tools/calculators/skill"
        const val DPS_CALCULATOR_URL = "https://tools.runescape.wiki/osrs-dps/"
        const val DPS_WIDTH_FRACTION = 0.6f  // DPS Calculator window: 60% of the screen width in landscape
        const val DPS_ZOOM_PERCENT = 100     // DPS Calculator text size
        const val SHOOTING_STARS_URL = "https://07.gg/trackers/shooting-star"
        const val SHOOTING_STARS_ZOOM_PERCENT = 85  // Shooting Star Tracker text size
        const val PRICES_URL = "https://prices.runescape.wiki/osrs/"
        const val PRICES_ZOOM_PERCENT = 85          // GE Prices text size
        const val PANEL_WIDTH_INCHES = 2f   // width of tool windows in landscape
        const val ZULRAH_WIDTH_INCHES = 1.2f // the Zulrah Helper window is thinner
        const val BUBBLE_SIZE_DP = 36       // size of the bubble
        const val BAR_HEIGHT_DP = 28        // height of the window's button bar
        const val BAR_BUTTON_WIDTH_DP = 66  // width of the back and close buttons
        const val PAGE_ZOOM_PERCENT = 70    // wiki text size: 100 = normal, lower = smaller
        const val XP_CALCULATOR_ZOOM_PERCENT = 85  // XP Calculator text size
        const val MENU_WIDTH_DP = 170       // width of the long-press menu
        const val LONG_PRESS_MS = 600L      // how long to hold the bubble to open the menu

        // Messages from CapturePermissionActivity
        const val ACTION_CAPTURE_RESULT = "com.sinat.osrsbubbletool.CAPTURE_RESULT"
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

    // The tools the bubble can open. New tools get added here.
    enum class Tool(val label: String) {
        WIKI("OSRS Wiki"),
        PUZZLE_BOX("Puzzle Box Solver"),
        INVENTORY_SETUPS("Inventory Setups"),
        XP_CALCULATOR("XP Calculator"),
        DPS_CALCULATOR("DPS Calculator"),
        SHOOTING_STARS("Shooting Star Tracker"),
        ZULRAH("Zulrah Helper"),
        FARMING("Timers"),
        PRICES("GE Prices")
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: ImageView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var capture: CaptureManager   // screen capture shared by all tools
    private lateinit var puzzleTool: PuzzleBoxTool
    private lateinit var setupsTool: InventorySetupsTool
    private lateinit var dpsTool: DpsTool
    private lateinit var zulrahTool: ZulrahTool
    private lateinit var farmingTool: FarmingTool

    private val toolWindows = mutableMapOf<Tool, LinearLayout>() // built once, reused
    private var currentTool = Tool.WIKI
    private var shownWindow: LinearLayout? = null
    private var shownParams: WindowManager.LayoutParams? = null
    private var windowX = 0            // remembers where you dragged the window
    private var menu: View? = null
    private val webViews = mutableListOf<WebView>()
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        updateForeground(capturing = false)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowX = dp(8)
        capture = CaptureManager(this) { capturing ->
            if (!destroyed) updateForeground(capturing)
        }
        puzzleTool = PuzzleBoxTool(this, windowManager, capture, ::setOverlaysVisible)
        setupsTool = InventorySetupsTool(this, windowManager, capture, ::setOverlaysVisible)
        dpsTool = DpsTool(this, windowManager, capture, ::setOverlaysVisible)
        zulrahTool = ZulrahTool(this)
        farmingTool = FarmingTool(this)
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_RESULT) capture.onPermissionResult(intent)
        return START_NOT_STICKY
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // The full screen size, including the camera cutout area at the edge
    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.width()
        else android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it)
        }.widthPixels

    private fun screenHeight(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windowManager.currentWindowMetrics.bounds.height()
        else android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it)
        }.heightPixels

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
            dp(BUBBLE_SIZE_DP), dp(BUBBLE_SIZE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(100)
        }
        allowScreenEdges(bubbleParams)

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
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    longPressed = false
                    bubble.postDelayed(longPress, LONG_PRESS_MS)
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
                        windowManager.updateViewLayout(bubble, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bubble.removeCallbacks(longPress)
                    if (!dragging && !longPressed) toggleToolWindow()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bubble.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
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
            for (tool in Tool.values()) {
                // the active tool shows in gold
                val color = if (tool == currentTool) Color.parseColor("#E8C766") else Color.WHITE
                addView(menuItem(tool.label, color) { openTool(tool) })
            }
            addView(menuItem("✕  Close bubble", Color.parseColor("#E08A7A")) { stopSelf() })
        }

        // The list scrolls when there are more tools than fit on the screen
        val scroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3E2C12"))
                setStroke(dp(1), Color.parseColor("#C9A24A"))
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
        val margin = dp(8)
        val menuHeight = minOf(list.measuredHeight + dp(2), screenH - margin * 2)
        scroll.isScrollbarFadingEnabled = list.measuredHeight + dp(2) <= menuHeight  // fade if nothing to scroll

        // place the menu beside the bubble, flipping to the left side if there's no room
        val gap = dp(6)
        var x = bubbleParams.x + dp(BUBBLE_SIZE_DP) + gap
        if (x + menuWidth > screenWidth()) x = bubbleParams.x - menuWidth - gap

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
            y = bubbleParams.y.coerceIn(margin, maxOf(margin, screenH - menuHeight - margin))
        }
        allowScreenEdges(params)

        windowManager.addView(scroll, params)
        menu = scroll
    }

    private fun hideMenu() {
        menu?.let { windowManager.removeView(it) }
        menu = null
    }

    // ---------------- Tool windows ----------------

    private fun openTool(tool: Tool) {
        hideToolWindow()
        currentTool = tool
        showToolWindow()
    }

    private fun toggleToolWindow() {
        if (shownWindow != null) hideToolWindow() else showToolWindow()
    }

    private fun showToolWindow() {
        val window = toolWindows.getOrPut(currentTool) { buildToolWindow(currentTool) }
        val params = makeWindowParams()
        windowManager.addView(window, params)
        shownWindow = window
        shownParams = params
        // re-add the bubble so it stays on top of the window
        windowManager.removeView(bubble)
        windowManager.addView(bubble, bubbleParams)
    }

    private fun hideToolWindow() {
        val wasShowing = shownWindow != null
        shownWindow?.let { windowManager.removeView(it) }
        shownWindow = null
        shownParams = null
        if (wasShowing && currentTool == Tool.PUZZLE_BOX) puzzleTool.onWindowClosed()
        if (wasShowing && currentTool == Tool.INVENTORY_SETUPS) setupsTool.onWindowClosed()
        if (wasShowing && currentTool == Tool.DPS_CALCULATOR) dpsTool.onWindowClosed()
        if (wasShowing && currentTool == Tool.FARMING) farmingTool.onWindowClosed()
    }

    private fun buildToolWindow(tool: Tool): LinearLayout = when (tool) {
        Tool.WIKI -> {
            val web = buildWebView(WIKI_URL, PAGE_ZOOM_PERCENT)
            buildFrame(web) { if (web.canGoBack()) web.goBack() }
        }
        Tool.XP_CALCULATOR -> {
            val web = buildWebView(XP_CALCULATOR_URL, XP_CALCULATOR_ZOOM_PERCENT)
            buildFrame(web) { if (web.canGoBack()) web.goBack() }
        }
        Tool.DPS_CALCULATOR -> {
            val web = buildWebView(DPS_CALCULATOR_URL, DPS_ZOOM_PERCENT)
            buildFrame(dpsTool.buildView(web)) { if (web.canGoBack()) web.goBack() }
        }
        Tool.SHOOTING_STARS -> {
            val web = buildWebView(SHOOTING_STARS_URL, SHOOTING_STARS_ZOOM_PERCENT)
            buildFrame(web) { if (web.canGoBack()) web.goBack() }
        }
        Tool.PUZZLE_BOX -> buildFrame(puzzleTool.buildView(), onBack = null)
        Tool.INVENTORY_SETUPS -> buildFrame(setupsTool.buildView()) { setupsTool.goBack() }
        Tool.ZULRAH -> buildFrame(zulrahTool.buildView()) { zulrahTool.goBack() }
        Tool.FARMING -> buildFrame(farmingTool.buildView(), onBack = null)
        Tool.PRICES -> {
            val web = buildWebView(PRICES_URL, PRICES_ZOOM_PERCENT)
            buildFrame(web) { if (web.canGoBack()) web.goBack() }
        }
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
            setTextColor(Color.parseColor("#D8C8A8"))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#54401F"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        var downRawX = 0f
        var startWindowX = 0
        dragHandle.setOnTouchListener { _, event ->
            val params = shownParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    startWindowX = params.x
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = maxOf(0, screenWidth() - params.width)
                    params.x = (startWindowX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    windowX = params.x
                    shownWindow?.let { windowManager.updateViewLayout(it, params) }
                }
            }
            true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#3E2C12"))
            addView(backButton)
            addView(dragHandle)
            addView(closeButton)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP)))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // Size and position of tool windows, kept inside the screen edges
    private fun makeWindowParams(): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val screenW = screenWidth()
        val screenH = screenHeight()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = when {
            currentTool == Tool.ZULRAH -> (ZULRAH_WIDTH_INCHES * metrics.xdpi).toInt()
            !landscape -> (screenW * 0.9f).toInt()
            currentTool == Tool.DPS_CALCULATOR -> (screenW * DPS_WIDTH_FRACTION).toInt()
            else -> (PANEL_WIDTH_INCHES * metrics.xdpi).toInt()
        }.coerceAtMost(screenW)

        windowX = windowX.coerceIn(0, maxOf(0, screenW - width))

        return WindowManager.LayoutParams(
            width,
            (screenH * 0.9f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // touches outside still reach the game
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = windowX
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
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = textZoomPercent
            settings.builtInZoomControls = true   // allow pinch-to-zoom
            settings.displayZoomControls = false  // hide the +/- zoom buttons
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
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
        return web
    }

    // ---------------- Housekeeping ----------------

    // Resize the window when the phone rotates
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideMenu()
        puzzleTool.onRotated()
        setupsTool.onRotated()
        dpsTool.onRotated()
        if (shownWindow != null) {
            val params = makeWindowParams()
            shownParams = params
            shownWindow?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    override fun onDestroy() {
        destroyed = true
        puzzleTool.destroy()
        setupsTool.destroy()
        dpsTool.destroy()
        farmingTool.destroy()
        capture.shutdown()
        hideMenu()
        hideToolWindow()
        windowManager.removeView(bubble)
        webViews.forEach { it.destroy() }
        super.onDestroy()
    }
}
