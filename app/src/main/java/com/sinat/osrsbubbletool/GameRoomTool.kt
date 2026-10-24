package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.isNotEmpty
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.FADED
import com.sinat.osrsbubbletool.Palette.GO_GREEN
import com.sinat.osrsbubbletool.Palette.PAPER
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import com.sinat.osrsbubbletool.Palette.RED
import com.sinat.osrsbubbletool.Palette.ROW_BROWN
import chess.Move
import chess.Piece
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// The Game Room: small games to play while you wait in the game: 2048, Wing It, the Zen Garden and Chess.
// (The games are the app's own and use nothing from Old School RuneScape: Jagex's Fan Content Policy
// doesn't allow games made with its content.)
// frameless(on): the window shows without its top bar and background (the Zen Garden's zen mode, whose move and
// resize buttons are carved into its wooden frame). pinCorners: where the tray's corners are in the window.
class GameRoomTool(
    private val context: Context,
    private val frameless: (Boolean) -> Unit = {},
    private val pinCorners: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
) {

    private val help2048 = "Swipe up, down, left or right to slide all the tiles. When two tiles with the same number touch, " +
        "they join into one: 2 and 2 make 4, 4 and 4 make 8, and so on. A new tile appears after every swipe.\n\n" +
        "Try to make a 2048 tile. The game ends when the board is full and nothing can join.\n\n" +
        "Your game is saved after every move, so you can close the window any time."

    private val helpFlyer = "Tap anywhere on the game to flap. Keep the bird in the air and fly through the gaps between the pillars: " +
        "each one you pass scores a point. Touching a pillar or the ground ends the round.\n\n" +
        "The gaps slowly get smaller as your score goes up. If you close the window mid-flight, the game pauses: tap to carry on."

    private val helpSand = "The garden floats on its own (zen mode), in a wooden frame with a button carved into each " +
        "corner:\n" +
        "\u2022 Top left: Exit, back to the Game Room.\n" +
        "\u2022 Top right: drag it to move the garden.\n" +
        "\u2022 Bottom right: drag it to resize the garden (double-tap it for the normal size).\n" +
        "\u2022 Bottom left: the arrow opens the tools and settings along the bottom; tap it again to close them.\n\n" +
        "Drag across the sand to rake. Hold your finger still on a stone (or anything placed) and the rake circles it; " +
        "hold again for the next ring out.\n\n" +
        "The icons, left to right:\n" +
        "\u2022 The tool: the Rake (five lines), the Stick (one line) or the Brush (wipes a patch smooth), or something " +
        "to place: a Stone, Flowers, a Lantern, a Bonsai or Fireflies. Tap the sand to place one, tap it again to take it " +
        "away, or drag it to move it. Tap a glowing firefly to let it fly away.\n" +
        "\u2022 Automatic: rakes calm patterns by itself, slowly, with the rake and the stick: Rows, Waves, " +
        "Chevrons, Basket Weave, Spiral, Square Spiral, Golden Spiral, Ripples, Rain, Seed of Life, Flower of Life, " +
        "Lotus, Rose Window, Spirograph, Lissajous, Mandala and Seven Star. They come in a random order, each once " +
        "before any comes round again. It smooths the sand between them and rakes rings around your stones and " +
        "lanterns with the stick. The pattern's name shows above the icons. Hold Automatic to pick a pattern from a list: it's " +
        "raked straight away, then it carries on in a random order. You can place and move things while it works.\n" +
        "\u2022 Undo (the back arrow): takes back your last few changes.\n" +
        "\u2022 Smooth (the eraser): wipes the whole tray smooth; what you placed stays.\n" +
        "\u2022 Brightness (the sun): darker makes the lanterns and fireflies glow.\n" +
        "\u2022 Volume (the speaker): the raking sound; all the way left is off. The Tone slider under it makes the " +
        "sound softer or sharper (the middle is the usual sound); you hear a little of it as you slide either one.\n" +
        "\u2022 Settings (the gear): clear everything placed, scale the garden to the window (on: resizing makes it " +
        "bigger or smaller; off: resizing adds sand or cuts it off), the triangle grid to snap things to (its corners and the middle of each side), Steady hand " +
        "(nearly straight lines become straight and nearly round ones perfect curves), Battery saver (a simpler " +
        "drawing that's much lighter on your phone, with slightly softer curves and light; newer phones only), the " +
        "sand's colour, and saving a picture.\n" +
        "\u2022 Help: this page.\n\n" +
        "Nothing can be placed under the corners.\n\n" +
        "Your garden is saved as you go."

    private val helpChess = "You play against the computer, on a board in a plain wooden frame with a button in " +
        "each corner:\n" +
        "• Top left: Exit, back to the Game Room.\n" +
        "• Top right: drag it to move the board.\n" +
        "• Bottom right: drag it to make the board bigger or smaller.\n" +
        "• Bottom left: the arrow opens the controls over the bottom of the board; tap it again to close them.\n\n" +
        "Tap one of your pieces, then the square to move it to. The bar along the top says whose move it is. " +
        "With \"Confirm each move\" on (in Options), the move is shown on the board and only played when you tap " +
        "Play in the bar along the bottom, so a slip of the finger doesn't cost you the game: tap Cancel, or anywhere " +
        "on the board, to pick again. A pawn reaching the far side asks which piece it becomes.\n\n" +
        "Dots show where the picked piece can go, and rings show pieces it can take. The last move is tinted yellow, " +
        "and a king in check glows red.\n\n" +
        "The controls, left to right:\n" +
        "• New game: playing as white, black or a random side.\n" +
        "• Undo (the back arrow): takes back your last move (and the computer's reply).\n" +
        "• Level: how strong the computer plays, from Beginner to Max. You can change it during a game.\n" +
        "• Options (the gear): confirm each move, show where pieces can go, and the letters and numbers along the board.\n" +
        "• Help: this page.\n\n" +
        "The computer only thinks on its own turn: about a second at the easier levels, up to a few seconds at Expert " +
        "and Max. Your game is saved after every move, so you " +
        "can close the window any time.\n\n" +
        "The computer player is CuckooChess, by Peter Österlund (free software, GPL)."

    private enum class Screen { MENU, G2048, FLYER, SAND, CHESS }

    private val prefs = context.getSharedPreferences("game_room", Context.MODE_PRIVATE)

    private var screen = Screen.MENU
    private lateinit var holder: FrameLayout
    private var scrollY = 0

    init {
        // The old Higher or Lower and Loot Simulator games saved prices, drop tables and item pictures here: free that space
        try { File(context.filesDir, "game_room").takeIf { it.exists() }?.deleteRecursively() } catch (e: Exception) { }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(PARCHMENT) }
        show()
        return holder
    }

    // The bubble is shutting down: the computer stops thinking (its thread ends), and the garden is saved
    fun destroy() {
        stopThinking()
        chessThreads?.shutdownNow()
        chessThreads = null
        sandView?.save(now = true)
        SandGardenView.waitForSaves()   // (one started in the background when the window closed is finished first)
    }

    // The window's ◀ button (and the Zen Garden's exit)
    fun goBack() {
        flyerView?.pauseIfPlaying()
        screen = Screen.MENU
        openHelp = null
        scrollY = 0
        show()
    }

    private fun open(s: Screen) {
        screen = s
        openHelp = null
        scrollY = 0
        show()
    }

    private fun show() {
        if (!::holder.isInitialized) return
        // the Zen Garden and chess have no window around them: no top bar, no background
        val zen = screen == Screen.SAND || screen == Screen.CHESS
        frameless(zen)
        holder.background?.alpha = if (zen) 0 else 255
        // leaving the garden: let it go (it saves itself as it comes off the screen, just below), so its sand,
        // light and undo steps don't take up memory while you're elsewhere
        if (screen != Screen.SAND) { zenScreen = null; sandView = null }
        // leaving chess: let its engine go (about 2 MB) unless it's still thinking about its move
        if (screen != Screen.CHESS) { chessRefresh = null; letThinkerGo() }
        holder.removeAllViews()
        holder.addView(when (screen) {
            Screen.MENU -> menu()
            Screen.G2048 -> game2048()
            Screen.FLYER -> flyer()
            Screen.SAND -> sandGarden()
            Screen.CHESS -> chess()
        })
        openHelp?.let { (t, h) -> showHelp(t, h) }
    }

    // ---------------- The menu ----------------

    private fun menu(): View = scrolling {
        addView(label("Game Room", 16f, bold = true), full())
        addView(label("Something to play while you wait.", 11f).apply { setTextColor(FADED) }, full(1))
        // the games, two to a row
        val games = listOf(
            Triple(GameIcon.G2048, "2048", "Join the tiles, reach 2048") to { open(Screen.G2048) },
            Triple(GameIcon.FLYER, "Wing It", "Tap to fly through the gaps") to { open(Screen.FLYER) },
            Triple(GameIcon.SAND, "Zen Garden", "Rake lines in the sand") to { open(Screen.SAND) },
            Triple(GameIcon.CHESS, "Chess", "Play the computer, six levels") to { open(Screen.CHESS) },
        )
        for (row in games.chunked(2)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                row.forEachIndexed { i, (info, action) ->
                    addView(tile(info.first, info.second, info.third, action),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
                }
                if (row.size == 1) addView(View(context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(4) })
            }, full(8))
        }
    }

    private fun tile(icon: Int, name: String, about: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(6), dp(10), dp(6), dp(10))
        background = GradientDrawable().apply { setColor(ROW_BROWN); cornerRadius = dp(8).toFloat() }
        addView(GameIcon(context, icon), LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(label(name, 12f, bold = true).apply { gravity = Gravity.CENTER }, full(6))
        addView(label(about, 10f).apply { gravity = Gravity.CENTER; setTextColor(FADED) }, full(2))
        setOnClickListener { onClick() }
    }

    // ---------------- 2048 ----------------

    private val g2048: Game2048 by lazy {
        Game2048().apply {
            val saved = prefs.getString("g2048_cells", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            if (saved != null) restore(saved, prefs.getInt("g2048_score", 0)) else newGame()
        }
    }

    private fun save2048() {
        val best = maxOf(prefs.getInt("g2048_best", 0), g2048.score)
        prefs.edit {
            putString("g2048_cells", g2048.cells.joinToString(","))
            putInt("g2048_score", g2048.score)
            putInt("g2048_best", best)
        }
    }

    // Not scrolling: swipes are for the tiles. The board shrinks to fit whatever space is left.
    private fun game2048(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        val game = g2048

        fun box(title: String) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(3))
            background = GradientDrawable().apply { setColor(BUTTON_BROWN); cornerRadius = dp(5).toFloat() }
            addView(label(title, 9f).apply { setTextColor("#F2E3C0".toColorInt()); gravity = Gravity.CENTER })
            addView(label("", 13f, bold = true).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        }
        val scoreBox = box("SCORE")
        val bestBox = box("BEST")
        lateinit var board: Game2048View
        val over = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true   // swipes don't reach the board while it's covered
            background = GradientDrawable().apply { setColor("#CCF2E3C0".toColorInt()); cornerRadius = dp(8).toFloat() }
        }

        fun update() {
            val best = maxOf(prefs.getInt("g2048_best", 0), game.score)
            (scoreBox.getChildAt(1) as TextView).text = "%,d".format(game.score)
            (bestBox.getChildAt(1) as TextView).text = "%,d".format(best)
            // the "game over" and "you made 2048" covers
            over.removeAllViews()
            val won = game.best >= 2048 && !prefs.getBoolean("g2048_won_seen", false)
            val lost = !game.canMove()
            over.visibility = if (won || lost) View.VISIBLE else View.GONE
            if (lost) {
                over.addView(label("No more moves!", 16f, bold = true).apply { gravity = Gravity.CENTER })
                over.addView(label("Score: " + "%,d".format(game.score), 12f).apply { gravity = Gravity.CENTER }, full(2))
                over.addView(button("Try again", GO_GREEN) {
                    game.newGame(); prefs.edit { putBoolean("g2048_won_seen", false) }
                    save2048(); board.refresh(); update()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            } else if (won) {
                over.addView(label("You made 2048!", 16f, bold = true).apply { gravity = Gravity.CENTER; setTextColor("#9C6A10".toColorInt()) })
                over.addView(button("Keep going", GO_GREEN) {
                    prefs.edit { putBoolean("g2048_won_seen", true) }; update()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }

        board = Game2048View(context, game) { save2048(); update() }

        // Top row: title, score, best, new game
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("2048", 18f, bold = true).apply { setTextColor("#C8900E".toColorInt()) })
            addView(helpButton("2048", help2048), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(5) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scoreBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(bestBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(button("New", RED) {
                game.newGame(); prefs.edit { putBoolean("g2048_won_seen", false) }
                save2048(); board.refresh(); update()
            }.apply { textSize = 11f; setPadding(dp(8), dp(6), dp(8), dp(6)) })
        }, full())

        // The board, centred, with the covers on top of it
        addView(FrameLayout(context).apply {
            addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(over, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })
        update()
    }

    // ---------------- Wing It ----------------

    private var flyerView: FlyerView? = null

    // Not scrolling: taps are for the bird. The game fills whatever space the window has.
    private fun flyer(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        fun box(title: String) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(3))
            background = GradientDrawable().apply { setColor(BUTTON_BROWN); cornerRadius = dp(5).toFloat() }
            addView(label(title, 9f).apply { setTextColor("#F2E3C0".toColorInt()); gravity = Gravity.CENTER })
            addView(label("", 13f, bold = true).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        }
        val scoreBox = box("SCORE")
        val bestBox = box("BEST")
        fun showScores(score: Int) {
            (scoreBox.getChildAt(1) as TextView).text = score.toString()
            (bestBox.getChildAt(1) as TextView).text = prefs.getInt("flyer_best", 0).toString()
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Wing It", 17f, bold = true).apply { setTextColor("#C8900E".toColorInt()) })
            addView(helpButton("Wing It", helpFlyer), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(5) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scoreBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(bestBox)
        }, full())
        val game = FlyerView(context, best = { prefs.getInt("flyer_best", 0) }) { score, over ->
            if (over && score > prefs.getInt("flyer_best", 0)) prefs.edit { putInt("flyer_best", score) }
            showScores(score)
        }
        flyerView = game
        showScores(0)
        addView(game, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(6) })
    }

    // ---------------- Zen Garden ----------------

    private var sandView: SandGardenView? = null
    private var zenScreen: ZenScreen? = null
    private val iconBack = Color.argb(190, 62, 44, 18)

    // Zen mode: just the tray, with round icons in the bottom left that come and go (see ZenScreen). The garden
    // scales to fit the window (or, with scaling off in the settings, grows and shrinks with it).
    private fun sandGarden(): View {
        raking = null   // a new garden starts with Automatic off
        val garden = SandGardenView(context, File(context.filesDir, "zen_garden.bin"),
            prefs.getInt("sand_tool", SandGardenView.RAKE).coerceIn(0, SandGardenView.TOOL_NAMES.size - 1)).apply {
            scaleToFit = prefs.getBoolean("sand_scale", true)
            val vol = prefs.getInt("sand_volume", 50)
            soundOn = vol > 0
            volume = vol / 50f
            tone = prefs.getInt("sand_tone", 50) / 100f
            steadyHand = prefs.getBoolean("sand_steady", false)
            gridOn = prefs.getBoolean("sand_grid", false)
            batterySaver = prefs.getBoolean("sand_battery_saver", false)
            brightness = prefs.getInt("sand_brightness", 100) / 100f
            sand = prefs.getInt("sand_colour", 0)
        }
        sandView = garden
        val size = dp(34)
        fun icon(kind: Int, color: Int = iconBack, onClick: (ZenIcon) -> Unit) = ZenIcon(context, kind, color).apply {
            tag = 0f   // keeps its size in the row
            layoutParams = ViewGroup.LayoutParams(size, size)
            setOnClickListener { onClick(this) }
        }

        // the sliders that open above the icons (one at a time)
        val sliderSpot = FrameLayout(context)
        var openSlider: View? = null
        fun closeSlider() { sliderSpot.removeAllViews(); openSlider = null }
        fun slider(left: String, right: String, value: Int, change: (Int) -> Unit, done: (Int) -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { setColor(iconBack); cornerRadius = dp(18).toFloat() }
            addView(label(left, 10f).apply { setTextColor(Color.WHITE) })
            addView(SeekBar(context).apply {
                max = 100
                progress = value
                progressTintList = android.content.res.ColorStateList.valueOf("#F2E3C0".toColorInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, v: Int, fromUser: Boolean) { change(v) }
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    override fun onStopTrackingTouch(bar: SeekBar) { done(bar.progress) }
                })
            }, LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(label(right, 10f).apply { setTextColor(Color.WHITE) })
        }
        fun toggleSlider(make: () -> View) {
            val was = openSlider
            closeSlider()
            if (was != null && was.tag == make) return   // tapped its icon again: just close it
            openSlider = make().also { it.tag = make }
            sliderSpot.addView(openSlider)
        }
        val makeLight: () -> View = {
            slider("Dark", "Bright", prefs.getInt("sand_brightness", 100),
                { garden.brightness = it / 100f }, { v -> prefs.edit { putInt("sand_brightness", v) } })
        }
        lateinit var volumeIcon: ZenIcon
        // the volume, and under it the tone: from softer to sharper (the middle is the usual sound)
        val makeVolume: () -> View = {
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(slider("Off", "Loud", prefs.getInt("sand_volume", 50), { v ->
                    garden.soundOn = v > 0
                    garden.volume = v / 50f
                    volumeIcon.muted = v == 0
                    garden.soundSample()
                }, { v -> prefs.edit { putInt("sand_volume", v) } }))
                // (a short burst of the sound while it's moved, to hear the difference)
                addView(slider("Soft", "Sharp", prefs.getInt("sand_tone", 50), { v -> garden.tone = v / 100f; garden.soundSample() },
                    { v -> prefs.edit { putInt("sand_tone", v) } }),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
        }

        // the icons: tool, Auto, Undo, Smooth, brightness, volume, settings, help (Exit is carved into the frame)
        val toolIcon = icon(garden.tool) { me -> chooseSandTool(garden) { me.kind = garden.tool } }
        // Automatic: a rounded button with its name (green while it's raking)
        val autoBack = GradientDrawable().apply { setColor(iconBack); cornerRadius = size / 2f }
        val autoIcon = TextView(context).apply {
            text = "Automatic"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = autoBack
            tag = 0f   // keeps its size in the row
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
            setOnClickListener { garden.setAuto(!garden.autoOn) }
            // held: the list of patterns, to pick one
            setOnLongClickListener { choosePattern(garden); true }
        }
        val undoIcon = icon(ZenIcon.UNDO) { garden.undo() }
        garden.onUndoChanged = { undoIcon.alpha = if (garden.canUndo) 1f else 0.45f }
        undoIcon.alpha = 0.45f
        volumeIcon = icon(ZenIcon.VOLUME) { toggleSlider(makeVolume) }.apply { muted = prefs.getInt("sand_volume", 50) == 0 }
        val icons = FlowRow(context).apply {
            addView(toolIcon)
            addView(autoIcon)
            addView(undoIcon)
            addView(icon(ZenIcon.SMOOTH) { garden.smooth() })
            addView(icon(ZenIcon.LIGHT) { toggleSlider(makeLight) })
            addView(volumeIcon)
            addView(icon(ZenIcon.GEAR) { sandSettings(garden) })
            addView(icon(ZenIcon.HELP) { showHelp("Zen Garden", helpSand) })
        }
        // the name of the pattern Auto is raking, above the icons
        val pattern = label("", 11f).apply {
            setTextColor(Color.WHITE)
            setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        garden.onAutoPattern = { name ->
            raking = name
            pattern.text = name ?: ""
            pattern.visibility = if (name == null) View.GONE else View.VISIBLE
            autoBack.setColor(if (name == null) iconBack else GO_GREEN)
        }
        val dock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(pattern, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
            addView(sliderSpot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            addView(icons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return ZenScreen(context, garden, dock).also { zen ->
            zenScreen = zen
            zen.onExit = { goBack() }
            zen.onHide = { closeSlider() }
            zen.onTrayPlaced = pinCorners                 // the window's move and resize corners go on the tray's corners
        }
    }

    // The pattern Automatic is raking (null: it's off), to show it in the list of patterns
    private var raking: String? = null

    // Automatic's list of patterns (hold the button): tap one to rake it now; afterwards it carries on in its random
    // order. Grouped as in the help: rows and weaves, spirals, ripples and rain, then the circles and curves.
    private fun choosePattern(garden: SandGardenView) = showCard("Patterns") { close ->
        addView(label("Tap one to rake it now; afterwards Automatic carries on in a random order.", 11f).apply { setTextColor(FADED) }, full(6))
        // (green marks only the pattern being raked now, so nothing is green while Automatic is off)
        addView(button("Random order", BUTTON_BROWN) {
            if (!garden.autoOn) garden.setAuto(true)
            close()
        }.apply { textSize = 12f }, full(6))
        val order = listOf("Rows", "Waves", "Chevrons", "Basket Weave", "Spiral", "Square Spiral", "Golden Spiral",
            "Ripples", "Rain", "Seed of Life", "Flower of Life", "Lotus", "Rose Window", "Spirograph", "Lissajous",
            "Mandala", "Seven Star")
        // (any pattern added later but not in this list still shows, at the end)
        val names = order.filter { it in SandGardenView.AUTO_PATTERNS } + SandGardenView.AUTO_PATTERNS.filter { it !in order }
        for (start in names.indices step 3) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (k in 0 until 3) {
                    val name = names.getOrNull(start + k)
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) }
                    if (name == null) { addView(View(context), lp); continue }
                    addView(button(name, if (name == raking) GO_GREEN else BUTTON_BROWN) {
                        garden.rakePattern(SandGardenView.AUTO_PATTERNS.indexOf(name))
                        close()
                    }.apply { textSize = 12f }, lp)
                }
            }, full(3))
        }
    }

    // The tool button's card: what to draw with, or what to place
    private fun chooseSandTool(garden: SandGardenView, picked: () -> Unit) = showCard("Tools") { close ->
        val names = SandGardenView.TOOL_NAMES
        fun row(tools: IntRange) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for (t in tools) addView(button(names[t], if (t == garden.tool) GO_GREEN else BUTTON_BROWN) {
                garden.tool = t
                prefs.edit { putInt("sand_tool", t) }
                picked()
                close()
            }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        }
        addView(label("Draw in the sand", 11f).apply { setTextColor(FADED) }, full(6))
        addView(row(SandGardenView.RAKE..SandGardenView.BRUSH), full(3))
        addView(label("Place (tap the sand; tap one again to take it away)", 11f).apply { setTextColor(FADED) }, full(8))
        addView(row(SandGardenView.STONE..SandGardenView.BONSAI), full(3))
        addView(label("Let out (tap the sand; tap one to let it fly away)", 11f).apply { setTextColor(FADED) }, full(8))
        addView(row(SandGardenView.FIREFLY..SandGardenView.FIREFLY), full(3))
    }

    // The More card: clearing, sound, steady hand, the triangle grid, the sand's colour, and saving a picture
    private fun sandSettings(garden: SandGardenView) = showCard("Garden") { close ->
        addView(toggleRow("Scale garden to window", "sand_scale", true) { garden.scaleToFit = it }, full(6))
        addView(toggleRow("Steady hand", "sand_steady", false) { garden.steadyHand = it }, full(4))
        addView(toggleRow("Triangle grid", "sand_grid", false) { garden.gridOn = it }, full(4))
        // (only phones with the smooth drawing, Android 13 and newer: older ones always use the lighter one)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            addView(toggleRow("Battery saver", "sand_battery_saver", false) { garden.batterySaver = it }, full(4))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Sand", 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val swatches = ArrayList<View>()
            fun mark() = swatches.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(if (i == garden.sand) dp(3) else dp(1), if (i == garden.sand) DARK_BROWN else FADED)
            }
            SandGardenView.SANDS.forEachIndexed { i, colours ->
                val v = View(context).apply {
                    contentDescription = SandGardenView.SAND_NAMES[i]
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(colours[1]) }
                    setOnClickListener {
                        garden.sand = i
                        prefs.edit { putInt("sand_colour", i) }
                        mark()
                    }
                }
                swatches.add(v)
                addView(v, LinearLayout.LayoutParams(dp(30), dp(30)).apply { leftMargin = dp(6) })
            }
            mark()
        }, full(8))
        addView(button("Clear placed things", RED) { garden.clearItems(); close() }, full(10))
        addView(button("Save picture to phone") { saveGardenPicture(garden) }, full(6))
        addView(button("Done", GO_GREEN) { close() }, full(6))
    }

    // A setting with an On/Off button, remembered in the Game Room's settings
    private fun toggleRow(text: String, key: String, default: Boolean, apply: (Boolean) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(label(text, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val b = button("") {}.apply { textSize = 11f; setPadding(dp(8), dp(5), dp(8), dp(5)) }
        fun show(on: Boolean) {
            b.text = if (on) "On" else "Off"
            (b.background as GradientDrawable).setColor(if (on) GO_GREEN else BUTTON_BROWN)
        }
        show(prefs.getBoolean(key, default))
        b.setOnClickListener {
            val on = !prefs.getBoolean(key, default)
            prefs.edit { putBoolean(key, on) }
            show(on)
            apply(on)
        }
        addView(b, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // Saves the garden as a PNG in Pictures/OSRS Bubble Tool
    private fun saveGardenPicture(garden: SandGardenView) {
        val picture = garden.picture() ?: return
        val app = context.applicationContext
        savePictureToPhone(app, picture, "zen-garden", recycle = true) { message ->
            Toast.makeText(app, message, if (message.startsWith("Saved")) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Chess ----------------

    // The game, from its saved moves (a save that doesn't make sense starts a new game)
    private val chessGame: ChessGame by lazy { ChessGame().apply { load(prefs.getString("chess_moves", "") ?: "") } }
    private val chessPlayerWhite get() = prefs.getBoolean("chess_white", true)
    private val chessLevel get() = prefs.getInt("chess_level", ChessEngine.DEFAULT_LEVEL).coerceIn(0, ChessEngine.LEVELS.size - 1)

    // The computer player: made on its own thread when it's first needed, used only there, and let go there when you
    // leave chess (after any move it's still thinking about)
    private var thinker: ChessEngine.Thinker? = null     // (only ever touched on the "chess" thread)
    private var thinking: ChessEngine.Job? = null
    private var chessThreads: ExecutorService? = null    // one background thread, so only one move is thought about at a time
    private var chessVersion = 0                         // goes up when you start again or undo: a late answer is ignored
    private val mainThread = Handler(Looper.getMainLooper())
    private var chessRefresh: (() -> Unit)? = null       // redraws the chess screen, while it's showing

    private fun saveChess() = prefs.edit { putString("chess_moves", chessGame.moveText) }

    private fun computerToMove() = chessGame.end() == ChessGame.End.NONE && chessGame.whiteToMove != chessPlayerWhite

    // The computer's turn: think about its move on the background thread, then play it here on the main one
    private fun startThinking() {
        if (thinking != null || !computerToMove()) return
        val threads = chessThreads ?: Executors.newSingleThreadExecutor { r -> Thread(r, "chess").apply { isDaemon = true } }
            .also { chessThreads = it }
        val job = ChessEngine.Job()
        thinking = job
        val (position, earlier) = chessGame.snapshot()
        val level = ChessEngine.LEVELS[chessLevel]
        val version = chessVersion
        threads.execute {
            // (anything going wrong in the engine, even running out of memory, mustn't take the app down with it)
            val move = try {
                val engine = thinker ?: ChessEngine.Thinker().also { thinker = it }
                engine.think(position, earlier, level, job)
            } catch (e: Throwable) { null }
            mainThread.post {
                if (thinking === job) thinking = null
                if (!job.stopped && version == chessVersion && computerToMove()) {
                    // if the engine came back without a move (it failed), any allowed move keeps the game going
                    // rather than leaving it stuck on "Thinking..."
                    chessGame.play(move ?: chessGame.legalMoves().random())
                    saveChess()
                }
                chessRefresh?.invoke()
            }
        }
    }

    // Lets the computer player go (about 2 MB), on its own thread, after any move it's still thinking about
    private fun letThinkerGo() {
        try { chessThreads?.execute { thinker = null } } catch (e: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun stopThinking() {
        thinking?.stop()
        thinking = null
    }

    // A move in words, for the "Play ...?" question: "Knight g1 to f3", "Bishop c4 takes f7", "Castle kingside"
    private fun describe(move: Move): String {
        val game = chessGame
        val piece = Piece.makeWhite(game.piece(move.from))
        fun sq(s: Int) = "${'a' + ChessGame.file(s)}${ChessGame.rank(s) + 1}"
        if (piece == Piece.WKING && kotlin.math.abs(ChessGame.file(move.to) - ChessGame.file(move.from)) == 2)
            return if (ChessGame.file(move.to) > ChessGame.file(move.from)) "Castle kingside" else "Castle queenside"
        val name = when (piece) {
            Piece.WKING -> "King"; Piece.WQUEEN -> "Queen"; Piece.WROOK -> "Rook"
            Piece.WBISHOP -> "Bishop"; Piece.WKNIGHT -> "Knight"; else -> "Pawn"
        }
        val takes = game.piece(move.to) != Piece.EMPTY ||
            (piece == Piece.WPAWN && ChessGame.file(move.to) != ChessGame.file(move.from))   // (en passant)
        return "$name ${sq(move.from)} ${if (takes) "takes" else "to"} ${sq(move.to)}"
    }

    // The board in its frame, with no window around it (like the Zen Garden's zen mode, see ChessScreen): the top
    // bar says whose move it is, the bottom bar holds Play and Cancel while a move waits, and the arrow in the bottom
    // left opens the controls over the bottom of the board. The board fills the window, so resizing resizes it.
    private fun chess(): View {
        val game = chessGame
        lateinit var board: ChessView
        lateinit var screen: ChessScreen
        val size = dp(34)

        // a rounded button with words, like the Zen Garden's Automatic
        fun pill(text: String, color: Int = iconBack, onClick: () -> Unit) = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            background = GradientDrawable().apply { setColor(color); cornerRadius = dp(15).toFloat() }
            setOnClickListener { onClick() }
        }

        // the waiting move: Play and Cancel, or the four pieces a pawn can become (in the bottom bar)
        val confirm = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.INVISIBLE
        }
        var question: String? = null   // the waiting move's question, kept in the top bar while it waits
        fun hideConfirm() { confirm.visibility = View.INVISIBLE; confirm.removeAllViews(); question = null }
        fun row(v: View, w: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            confirm.addView(v, LinearLayout.LayoutParams(w, dp(30)).apply { if (confirm.isNotEmpty()) leftMargin = dp(6) })

        // the controls: New game, Undo, Level, Options, Help (round icons and buttons, like the Zen Garden's)
        lateinit var undo: ZenIcon
        val level = pill("") { chooseChessLevel { chessRefresh?.invoke() } }.apply {
            tag = 0f; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size)
        }
        val dock = FlowRow(context)

        fun refresh() {
            board.playerWhite = chessPlayerWhite
            board.showMoves = prefs.getBoolean("chess_hints", true)
            board.showCoordinates = prefs.getBoolean("chess_coords", true)
            val end = game.end()
            val mine = game.whiteToMove == chessPlayerWhite
            board.allowInput = end == ChessGame.End.NONE && mine
            if (!board.allowInput) hideConfirm()
            val text = when (end) {
                ChessGame.End.CHECKMATE -> if (mine) "Checkmate: you lost" else "Checkmate: you won!"
                ChessGame.End.STALEMATE -> "Stalemate: a draw"
                ChessGame.End.REPETITION -> "Draw: the same position three times"
                ChessGame.End.FIFTY_MOVES -> "Draw: 50 moves with no capture or pawn move"
                ChessGame.End.NOT_ENOUGH_PIECES -> "Draw: not enough pieces left to checkmate"
                ChessGame.End.NONE -> if (!mine) "Thinking..." else if (game.inCheck) "Check! Your move" else "Your move"
            }
            val asking = question
            if (asking != null && board.pending != null) screen.setStatus(asking, ChessScreen.TEXT) else screen.setStatus(text, when {
                end == ChessGame.End.CHECKMATE && !mine -> ChessScreen.GOOD
                end == ChessGame.End.CHECKMATE || (end == ChessGame.End.NONE && mine && game.inCheck) -> ChessScreen.BAD
                else -> ChessScreen.TEXT
            })
            level.text = "Level: ${ChessEngine.LEVELS[chessLevel].name}"
            undo.alpha = if (game.moveCount > (if (chessPlayerWhite) 0 else 1)) 1f else 0.45f
            board.invalidate()
        }

        fun play(move: Move) {
            hideConfirm()
            board.clearSelection()
            game.play(move)
            saveChess()
            startThinking()
            refresh()
        }

        board = ChessView(context, game, onPicked = { moves ->
            if (moves.size == 1 && !prefs.getBoolean("chess_confirm", true)) {
                play(moves[0])
            } else {
                // show the move on the board, ask in the top bar, and wait for Play (a promotion always asks which piece)
                val promotion = moves.size > 1
                board.pending = if (promotion) moves.first { Piece.makeWhite(it.promoteTo) == Piece.WQUEEN } else moves[0]
                question = if (promotion) "It becomes a..." else "${describe(moves[0])}?"
                screen.setStatus(question!!, ChessScreen.TEXT)
                confirm.removeAllViews()
                if (promotion) {
                    for (kind in listOf(Piece.WQUEEN, Piece.WROOK, Piece.WBISHOP, Piece.WKNIGHT)) {
                        val m = moves.first { Piece.makeWhite(it.promoteTo) == kind }
                        row(PieceButton(context, m.promoteTo, GO_GREEN).apply {
                            contentDescription = when (kind) { Piece.WQUEEN -> "Queen"; Piece.WROOK -> "Rook"; Piece.WBISHOP -> "Bishop"; else -> "Knight" }
                            setOnClickListener { play(m) }
                        }, dp(30))
                    }
                } else {
                    row(pill("Play", GO_GREEN) { play(moves[0]) })
                }
                row(pill("Cancel") { hideConfirm(); board.clearSelection(); refresh() })
                confirm.visibility = View.VISIBLE
            }
        }, onPendingCancelled = { hideConfirm(); refresh() })

        undo = ZenIcon(context, ZenIcon.UNDO, iconBack).apply {
            tag = 0f; layoutParams = ViewGroup.LayoutParams(size, size)
            contentDescription = "Undo"
            setOnClickListener {
                if (game.moveCount <= (if (chessPlayerWhite) 0 else 1)) return@setOnClickListener   // (faded: none of your moves yet)
                stopThinking()
                chessVersion++
                hideConfirm()
                board.clearSelection()
                if (game.undo()) {
                    // back to your turn: your last move goes, and the computer's reply after it
                    while (game.moveCount > 0 && game.whiteToMove != chessPlayerWhite) game.undo()
                    saveChess()
                }
                startThinking()   // (you play black and took back your first move: the computer moves first again)
                refresh()
            }
        }
        dock.apply {
            addView(pill("New game", RED) {
                newChessGame { stopThinking(); chessVersion++; hideConfirm(); board.clearSelection() }
            }.apply { tag = 0f; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, size) })
            addView(undo)
            addView(level)
            addView(ZenIcon(context, ZenIcon.GEAR, iconBack).apply {
                tag = 0f; layoutParams = ViewGroup.LayoutParams(size, size); contentDescription = "Options"
                setOnClickListener { chessOptions { chessRefresh?.invoke() } }
            })
            addView(ZenIcon(context, ZenIcon.HELP, iconBack).apply {
                tag = 0f; layoutParams = ViewGroup.LayoutParams(size, size); contentDescription = "Help"
                setOnClickListener { showHelp("Chess", helpChess) }
            })
        }

        screen = ChessScreen(context, board, confirm, dock).apply {
            onExit = { goBack() }
            onTrayPlaced = pinCorners                 // the window's move and resize corners go on the frame's corners
        }
        chessRefresh = { refresh() }
        refresh()
        startThinking()   // (it was the computer's turn when the game was saved)
        return screen
    }

    // New game: which side you play (the level stays as it is: the Level button changes it any time)
    private fun newChessGame(starting: () -> Unit) = showCard("New game") { close ->
        addView(label("Play as", 11f).apply { setTextColor(FADED) }, full(4))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((name, side) in listOf("White" to true, "Black" to false, "Random" to null)) {
                addView(button(name, BUTTON_BROWN) {
                    val white = side ?: (Math.random() < 0.5)
                    starting()
                    prefs.edit { putBoolean("chess_white", white) }
                    chessGame.newGame()
                    saveChess()
                    close()
                    startThinking()   // (playing black: the computer moves first)
                    chessRefresh?.invoke()
                }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            }
        }, full(3))
        addView(label("Level: ${ChessEngine.LEVELS[chessLevel].name} (the Level button changes it, any time)", 11f)
            .apply { setTextColor(FADED) }, full(8))
    }

    // How strong the computer plays (it applies from its next move)
    private fun chooseChessLevel(changed: () -> Unit) = showCard("Level") { close ->
        val about = listOf("Still learning the moves", "Makes plenty of mistakes", "A casual player",
            "A good club player", "A strong player", "As strong as it gets")
        ChessEngine.LEVELS.forEachIndexed { i, l ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(button(l.name, if (i == chessLevel) GO_GREEN else BUTTON_BROWN) {
                    prefs.edit { putInt("chess_level", i) }
                    close()
                    changed()
                }.apply { textSize = 12f }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(label(about[i], 11f).apply { setTextColor(FADED) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
            }, full(if (i == 0) 4 else 3))
        }
    }

    private fun chessOptions(changed: () -> Unit) = showCard("Options") { close ->
        addView(toggleRow("Confirm each move", "chess_confirm", true) { changed() }, full(6))
        addView(toggleRow("Show where pieces can go", "chess_hints", true) { changed() }, full(4))
        addView(toggleRow("Letters and numbers on the board", "chess_coords", true) { changed() }, full(4))
        addView(button("Done", GO_GREEN) { close() }, full(10))
    }

    // ---------------- Small building blocks ----------------

    private fun helpButton(title: String, help: String) = TextView(context).apply {
        text = "?"
        textSize = 12f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BUTTON_BROWN) }
        setOnClickListener { showHelp(title, help) }
    }

    // How to play, as a card on top of the game (so nothing underneath moves)
    private var openHelp: Pair<String, String>? = null   // kept open if the screen redraws underneath it

    private fun showHelp(title: String, help: String) {
        openHelp = title to help
        showCard("How to play: $title", onClose = { openHelp = null }) { close ->
            addView(label(help, 12f).apply { setLineSpacing(0f, 1.1f) }, full(6))
            addView(button("Got it", GO_GREEN) { close() }, full(10))
        }
    }

    // A card on top of the screen; tapping outside it closes it
    private fun showCard(title: String, onClose: () -> Unit = {}, build: LinearLayout.(close: () -> Unit) -> Unit) {
        val cover = FrameLayout(context).apply {
            setBackgroundColor("#993E2C12".toColorInt())
            isClickable = true
        }
        val close: () -> Unit = { onClose(); holder.removeView(cover) }
        cover.setOnClickListener { close() }
        cover.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(8).toFloat() }
                isClickable = true   // taps on the card don't close it
                // the title, with a ✕ in the top right corner to close the card (tapping outside it closes it too)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(title, 13f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(label("✕", 16f, bold = true).apply {
                        gravity = Gravity.CENTER
                        contentDescription = "Close"
                        setOnClickListener { close() }
                    }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(0, -dp(6), -dp(6), 0) })
                }, full())
                build(close)
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        holder.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun scrolling(build: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            build()
        })
        val y = scrollY
        if (y > 0) post { scrollTo(0, y) }
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun button(text: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}

// The little pictures on the Game Room menu, drawn in code so they're sharp at any size.
// (Only ever made in code, so it doesn't need the extra setup Android Studio's layout designer uses.)
@android.annotation.SuppressLint("ViewConstructor")
class GameIcon(context: Context, private val kind: Int) : View(context) {

    companion object {
        const val G2048 = 2
        const val FLYER = 3
        const val SAND = 4
        const val CHESS = 5
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        canvas.translate((width - s) / 2, (height - s) / 2)
        // dark rounded square behind
        paint.style = Paint.Style.FILL
        paint.color = "#4A3518".toColorInt()
        canvas.drawRoundRect(0f, 0f, s, s, s * 0.18f, s * 0.18f, paint)
        when (kind) {
            G2048 -> tiles(canvas, s)
            FLYER -> flyer(canvas, s)
            SAND -> sand(canvas, s)
            CHESS -> chess(canvas, s)
        }
    }

    // four little 2048 tiles
    private fun tiles(canvas: Canvas, s: Float) {
        val values = intArrayOf(2, 8, 64, 2048)
        val gap = s * 0.08f
        val cell = (s - gap * 3) / 2
        for (i in 0 until 4) {
            val x = gap + (i % 2) * (cell + gap)
            val y = gap + (i / 2) * (cell + gap)
            paint.color = Game2048View.colorOf(values[i])
            canvas.drawRoundRect(x, y, x + cell, y + cell, cell * 0.15f, cell * 0.15f, paint)
            val label = values[i].toString()
            text.color = if (values[i] <= 4) "#3E2C12".toColorInt() else Color.WHITE
            text.textSize = cell * if (label.length >= 4) 0.3f else 0.5f
            canvas.drawText(label, x + cell / 2, y + cell / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    // raked sand: straight lines at the top, rings around a stone below
    private fun sand(canvas: Canvas, s: Float) {
        paint.color = "#DCC594".toColorInt()
        canvas.drawRoundRect(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f, s * 0.1f, s * 0.1f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.035f
        paint.color = "#A88D60".toColorInt()
        canvas.drawLine(s * 0.14f, s * 0.2f, s * 0.86f, s * 0.2f, paint)
        canvas.drawLine(s * 0.14f, s * 0.29f, s * 0.86f, s * 0.29f, paint)
        canvas.drawCircle(s * 0.6f, s * 0.64f, s * 0.19f, paint)
        canvas.drawCircle(s * 0.6f, s * 0.64f, s * 0.27f, paint)
        paint.style = Paint.Style.FILL
        paint.color = "#55000000".toColorInt()
        canvas.drawCircle(s * 0.62f, s * 0.67f, s * 0.11f, paint)
        paint.color = "#7A746C".toColorInt()
        canvas.drawCircle(s * 0.6f, s * 0.64f, s * 0.1f, paint)
        paint.color = "#A39C92".toColorInt()
        canvas.drawCircle(s * 0.57f, s * 0.61f, s * 0.04f, paint)
    }

    // a corner of a chess board with a knight and a pawn
    private fun chess(canvas: Canvas, s: Float) {
        val inset = s * 0.08f
        val cell = (s - inset * 2) / 4
        for (r in 0 until 4) for (c in 0 until 4) {
            paint.color = if ((r + c) % 2 == 0) ChessView.LIGHT_SQUARE else ChessView.DARK_SQUARE
            canvas.drawRect(inset + c * cell, inset + r * cell, inset + (c + 1) * cell, inset + (r + 1) * cell, paint)
        }
        ChessPieces.draw(canvas, Piece.BKNIGHT, inset + cell * 0.2f, inset + cell * 0.1f, cell * 1.7f)
        ChessPieces.draw(canvas, Piece.WPAWN, inset + cell * 2.1f, inset + cell * 2.1f, cell * 1.7f)
    }

    // a little bird between two pillars
    private fun flyer(canvas: Canvas, s: Float) {
        paint.color = "#F2E3C0".toColorInt()
        canvas.drawRoundRect(s * 0.08f, s * 0.08f, s * 0.92f, s * 0.92f, s * 0.1f, s * 0.1f, paint)
        paint.color = "#6B4B24".toColorInt()
        canvas.drawRect(s * 0.62f, s * 0.08f, s * 0.80f, s * 0.32f, paint)
        canvas.drawRect(s * 0.62f, s * 0.64f, s * 0.80f, s * 0.92f, paint)
        paint.color = "#C9A24A".toColorInt()
        canvas.drawRect(s * 0.59f, s * 0.29f, s * 0.83f, s * 0.35f, paint)
        canvas.drawRect(s * 0.59f, s * 0.61f, s * 0.83f, s * 0.67f, paint)
        paint.color = "#D8573A".toColorInt()
        canvas.drawOval(s * 0.18f, s * 0.38f, s * 0.46f, s * 0.60f, paint)
        paint.color = "#E8A62C".toColorInt()
        canvas.drawRect(s * 0.44f, s * 0.46f, s * 0.52f, s * 0.51f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(s * 0.38f, s * 0.45f, s * 0.04f, paint)
        paint.color = "#3E2C12".toColorInt()
        canvas.drawCircle(s * 0.39f, s * 0.45f, s * 0.02f, paint)
    }
}
