package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import java.io.File

// The Game Room: small games to play while you wait in the game: 2048 and Wing It.
// (The games are the app's own and use nothing from Old School RuneScape: Jagex's Fan Content Policy
// doesn't allow games made with its content.)
class GameRoomTool(private val context: Context) {

    companion object {
        private val PARCHMENT = "#F2E3C0".toColorInt()
        private val DARK_BROWN = "#3E2C12".toColorInt()
        private val BUTTON_BROWN = "#8B6B3E".toColorInt()
        private val ROW_BROWN = "#E3CFA2".toColorInt()
        private val PAPER = "#FFF8E6".toColorInt()
        private val GO_GREEN = "#3E7A2E".toColorInt()
        private val STOP_RED = "#B03A2E".toColorInt()
        private val FADED = "#8C7B5E".toColorInt()
    }

    private val HELP_2048 = "Swipe up, down, left or right to slide all the tiles. When two tiles with the same number touch, " +
        "they join into one: 2 and 2 make 4, 4 and 4 make 8, and so on. A new tile appears after every swipe.\n\n" +
        "Try to make a 2048 tile. The game ends when the board is full and nothing can join.\n\n" +
        "Your game is saved after every move, so you can close the window any time."

    private val HELP_FLYER = "Tap anywhere on the game to flap. Keep the bird in the air and fly through the gaps between the pillars: " +
        "each one you pass scores a point. Touching a pillar or the ground ends the round.\n\n" +
        "The gaps slowly get smaller as your score goes up. If you close the window mid-flight, the game pauses: tap to carry on."

    private enum class Screen { MENU, G2048, FLYER }

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

    // The bubble is shutting down (nothing runs in the background, so there's nothing to stop)
    fun destroy() {}

    // The window's ◀ button
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
        holder.removeAllViews()
        holder.addView(when (screen) {
            Screen.MENU -> menu()
            Screen.G2048 -> game2048()
            Screen.FLYER -> flyer()
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
            addView(helpButton("2048", HELP_2048), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(5) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(scoreBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(bestBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(button("New", STOP_RED) {
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
            addView(helpButton("Wing It", HELP_FLYER), LinearLayout.LayoutParams(dp(22), dp(22)).apply { leftMargin = dp(5) })
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
        val cover = FrameLayout(context).apply {
            setBackgroundColor("#993E2C12".toColorInt())
            isClickable = true
        }
        val close = { openHelp = null; holder.removeView(cover) }
        cover.setOnClickListener { close() }
        cover.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(PAPER); setStroke(dp(1), BUTTON_BROWN); cornerRadius = dp(8).toFloat() }
                isClickable = true   // taps on the card don't close it
                addView(label("How to play: $title", 13f, bold = true), full())
                addView(label(help, 12f).apply { setLineSpacing(0f, 1.1f) }, full(6))
                addView(button("Got it", GO_GREEN) { close() }, full(10))
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
