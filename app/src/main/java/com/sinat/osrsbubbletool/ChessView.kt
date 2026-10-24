package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withTranslation
import chess.Move
import chess.Piece
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The chess board: draws the squares and pieces as big as the space allows (so resizing the window scales it),
// and turns taps into moves. Tap one of your pieces, then the square to move it to; the move is then shown on the
// board, waiting (pending) until it's confirmed with the button under the board. Its colours are fixed, like a
// real board, so it looks the same in dark mode.
@SuppressLint("ViewConstructor")
class ChessView(
    context: Context,
    private val game: ChessGame,
    // a move was picked: one move, or four for a pawn reaching the end (one for each piece it can become)
    private val onPicked: (List<Move>) -> Unit,
    // the waiting move was let go by tapping somewhere else on the board
    private val onPendingCancelled: () -> Unit,
) : View(context) {

    var playerWhite = true          // the player's side is at the bottom
        set(v) { field = v; invalidate() }
    var allowInput = false          // only on the player's turn
        set(v) { field = v; if (!v) selected = -1; invalidate() }
    var showMoves = true            // dots on the squares the picked piece can go to
        set(v) { field = v; invalidate() }
    var showCoordinates = true      // a-h and 1-8 along the edges
        set(v) { field = v; invalidate() }
    var pending: Move? = null       // the move waiting to be confirmed (drawn as if made)
        set(v) { field = v; invalidate() }

    private var selected = -1       // the square of the picked piece (-1: none)

    fun clearSelection() { selected = -1; pending = null; invalidate() }

    // ---------------- Drawing ----------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val board = RectF()
    private var cell = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s = min(w, h).toFloat()
        board.set((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        cell = s / 8
    }

    // Where a square is drawn: its column and row on screen (row 0 at the top)
    private fun col(square: Int) = if (playerWhite) ChessGame.file(square) else 7 - ChessGame.file(square)
    private fun row(square: Int) = if (playerWhite) 7 - ChessGame.rank(square) else ChessGame.rank(square)
    private fun left(square: Int) = board.left + col(square) * cell
    private fun top(square: Int) = board.top + row(square) * cell

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return
        val waiting = pending
        val last = game.lastMove
        for (sq in 0 until 64) {
            val x = left(sq); val y = top(sq)
            val dark = (ChessGame.file(sq) + ChessGame.rank(sq)) % 2 == 0
            fill.color = if (dark) DARK_SQUARE else LIGHT_SQUARE
            canvas.drawRect(x, y, x + cell, y + cell, fill)
            // the last move, the picked piece and the waiting move are tinted
            val tint = when {
                waiting != null && (sq == waiting.from || sq == waiting.to) -> PENDING_TINT
                sq == selected -> SELECTED_TINT
                waiting == null && last != null && (sq == last.from || sq == last.to) -> LAST_TINT
                else -> 0
            }
            if (tint != 0) { fill.color = tint; canvas.drawRect(x, y, x + cell, y + cell, fill) }
        }
        // the king in check gets a red glow (not while a move is waiting: the board shows that move instead)
        if (waiting == null && game.inCheck) {
            val k = (0 until 64).firstOrNull { game.piece(it) == if (game.whiteToMove) Piece.WKING else Piece.BKING }
            if (k != null) {
                fill.color = CHECK_GLOW
                canvas.drawCircle(left(k) + cell / 2, top(k) + cell / 2, cell * 0.48f, fill)
            }
        }
        if (showCoordinates) drawCoordinates(canvas)
        // the pieces (with a waiting move, the board shows it as if made: the piece where it's going and faintly
        // where it was, a castling rook beside its king, and a pawn taken en passant gone)
        val moving = waiting?.let { game.piece(it.from) } ?: Piece.EMPTY
        var rookFrom = -1; var rookTo = -1; var taken = -1
        if (waiting != null) {
            val fromFile = ChessGame.file(waiting.from); val toFile = ChessGame.file(waiting.to)
            val rank = ChessGame.rank(waiting.from)
            if (Piece.makeWhite(moving) == Piece.WKING && abs(toFile - fromFile) == 2) {
                rookFrom = ChessGame.square(if (toFile > fromFile) 7 else 0, rank)
                rookTo = ChessGame.square(if (toFile > fromFile) 5 else 3, rank)
            }
            if (Piece.makeWhite(moving) == Piece.WPAWN && toFile != fromFile && game.piece(waiting.to) == Piece.EMPTY)
                taken = ChessGame.square(toFile, rank)
        }
        for (sq in 0 until 64) {
            val p = game.piece(sq)
            if (p == Piece.EMPTY || (waiting != null && sq == waiting.to) || sq == rookFrom || sq == taken) continue
            val faint = waiting != null && sq == waiting.from
            drawPiece(canvas, p, left(sq), top(sq), cell, if (faint) 80 else 255)
        }
        if (waiting != null) {
            drawPiece(canvas, if (ChessGame.isPromotion(waiting)) waiting.promoteTo else moving, left(waiting.to), top(waiting.to), cell, 255)
            if (rookFrom >= 0) drawPiece(canvas, game.piece(rookFrom), left(rookTo), top(rookTo), cell, 255)
        }
        // where the picked piece can go: a dot on empty squares, a ring around pieces it can take
        if (showMoves && selected >= 0 && waiting == null) {
            fill.color = HINT
            for (to in game.movesFrom(selected).map { it.to }.distinct()) {
                val cx = left(to) + cell / 2; val cy = top(to) + cell / 2
                if (game.piece(to) == Piece.EMPTY) {
                    fill.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, cell * 0.15f, fill)
                } else {
                    fill.style = Paint.Style.STROKE
                    fill.strokeWidth = cell * 0.08f
                    canvas.drawCircle(cx, cy, cell * 0.44f, fill)
                }
            }
            fill.style = Paint.Style.FILL
        }
    }

    // a-h along the bottom row and 1-8 down the left column, in the other square colour
    private fun drawCoordinates(canvas: Canvas) {
        text.textSize = cell * 0.2f
        for (i in 0 until 8) {
            // bottom row: the files
            val fileSq = ChessGame.square(if (playerWhite) i else 7 - i, if (playerWhite) 0 else 7)
            val darkF = (ChessGame.file(fileSq) + ChessGame.rank(fileSq)) % 2 == 0
            text.color = if (darkF) LIGHT_SQUARE else DARK_SQUARE
            text.textAlign = Paint.Align.RIGHT
            canvas.drawText(('a' + ChessGame.file(fileSq)).toString(), left(fileSq) + cell * 0.95f, top(fileSq) + cell * 0.95f, text)
            // left column: the ranks
            val rankSq = ChessGame.square(if (playerWhite) 0 else 7, if (playerWhite) 7 - i else i)
            val darkR = (ChessGame.file(rankSq) + ChessGame.rank(rankSq)) % 2 == 0
            text.color = if (darkR) LIGHT_SQUARE else DARK_SQUARE
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(('1' + ChessGame.rank(rankSq)).toString(), left(rankSq) + cell * 0.06f, top(rankSq) + cell * 0.24f, text)
        }
    }

    private fun drawPiece(canvas: Canvas, piece: Int, x: Float, y: Float, size: Float, alpha: Int) =
        ChessPieces.draw(canvas, piece, x, y, size, alpha)

    // ---------------- Taps ----------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    @SuppressLint("ClickableViewAccessibility")   // (a board: there's no single click to perform)
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                // a tap, not a drag: the board doesn't move pieces by dragging, so a slip of the finger does nothing
                if (abs(e.x - downX) <= slop * 2 && abs(e.y - downY) <= slop * 2) squareAt(e.x, e.y)?.let { tap(it) }
            }
        }
        return true
    }

    private fun squareAt(x: Float, y: Float): Int? {
        if (!board.contains(x, y)) return null
        val c = ((x - board.left) / cell).toInt().coerceIn(0, 7)
        val r = ((y - board.top) / cell).toInt().coerceIn(0, 7)
        return if (playerWhite) ChessGame.square(c, 7 - r) else ChessGame.square(7 - c, r)
    }

    private fun tap(sq: Int) {
        if (!allowInput) return
        // a move is waiting: tapping the board again lets it go (it's only played with the button), then the tap
        // counts as a new one, so tapping another square for the same piece changes the move
        if (pending != null) {
            pending = null
            onPendingCancelled()
        }
        if (selected >= 0) {
            val moves = game.movesFrom(selected).filter { it.to == sq }
            if (moves.isNotEmpty()) { onPicked(moves); return }
            if (sq == selected) { selected = -1; invalidate(); return }
        }
        val p = game.piece(sq)
        selected = if (p != Piece.EMPTY && ChessGame.isWhite(p) == game.whiteToMove && game.movesFrom(sq).isNotEmpty()) sq else -1
        invalidate()
    }

    companion object {
        val LIGHT_SQUARE = "#EED8AE".toColorInt()
        val DARK_SQUARE = "#B48A5A".toColorInt()
        private val LAST_TINT = "#70E8C547".toColorInt()
        private val SELECTED_TINT = "#9062B04A".toColorInt()
        private val PENDING_TINT = "#A0F0A030".toColorInt()
        private val CHECK_GLOW = "#C0D8402A".toColorInt()
        private val HINT = "#66302010".toColorInt()
    }
}

// The pieces, drawn as simple shapes (in a square of any size), so they're sharp at any board size. Light pieces
// are cream with a dark outline, dark pieces near-black with a light inner line.
object ChessPieces {

    private val WHITE_FILL = "#F7EEDC".toColorInt()
    private val WHITE_LINE = "#2B1E0E".toColorInt()
    private val BLACK_FILL = "#2B2118".toColorInt()
    private val BLACK_LINE = "#0E0A06".toColorInt()
    private val BLACK_DETAIL = "#D9CBB0".toColorInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val detail = Path()

    fun draw(canvas: Canvas, piece: Int, x: Float, y: Float, s: Float, alpha: Int = 255) {
        if (piece == Piece.EMPTY) return
        val white = Piece.isWhite(piece)
        path.reset(); detail.reset()
        fun move(px: Float, py: Float) = path.moveTo(x + px * s, y + py * s)
        fun line(px: Float, py: Float) = path.lineTo(x + px * s, y + py * s)
        fun curve(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
            path.cubicTo(x + ax * s, y + ay * s, x + bx * s, y + by * s, x + cx * s, y + cy * s)
        fun circle(cx: Float, cy: Float, r: Float) = path.addCircle(x + cx * s, y + cy * s, r * s, Path.Direction.CW)
        fun base(l: Float, t: Float, r: Float, b: Float) =
            path.addRoundRect(x + l * s, y + t * s, x + r * s, y + b * s, s * 0.04f, s * 0.04f, Path.Direction.CW)
        fun detailLine(ax: Float, ay: Float, bx: Float, by: Float) { detail.moveTo(x + ax * s, y + ay * s); detail.lineTo(x + bx * s, y + by * s) }

        when (Piece.makeWhite(piece)) {
            Piece.WPAWN -> {
                circle(0.5f, 0.31f, 0.12f)
                move(0.41f, 0.40f); line(0.59f, 0.40f); line(0.67f, 0.76f); line(0.33f, 0.76f); path.close()
                base(0.25f, 0.74f, 0.75f, 0.86f)
            }
            Piece.WROOK -> {
                move(0.25f, 0.38f); line(0.25f, 0.17f); line(0.35f, 0.17f); line(0.35f, 0.25f); line(0.45f, 0.25f)
                line(0.45f, 0.17f); line(0.55f, 0.17f); line(0.55f, 0.25f); line(0.65f, 0.25f); line(0.65f, 0.17f)
                line(0.75f, 0.17f); line(0.75f, 0.38f); path.close()
                move(0.32f, 0.37f); line(0.68f, 0.37f); line(0.71f, 0.78f); line(0.29f, 0.78f); path.close()
                base(0.21f, 0.76f, 0.79f, 0.88f)
                detailLine(0.30f, 0.38f, 0.70f, 0.38f)
            }
            Piece.WKNIGHT -> {
                move(0.30f, 0.80f); line(0.73f, 0.80f)
                curve(0.76f, 0.55f, 0.71f, 0.31f, 0.56f, 0.20f)
                line(0.53f, 0.11f); line(0.46f, 0.19f)
                curve(0.35f, 0.22f, 0.24f, 0.35f, 0.19f, 0.50f)
                line(0.23f, 0.59f); line(0.34f, 0.57f)
                curve(0.40f, 0.55f, 0.45f, 0.51f, 0.48f, 0.46f)
                curve(0.44f, 0.58f, 0.32f, 0.66f, 0.30f, 0.80f)
                path.close()
                base(0.22f, 0.78f, 0.79f, 0.90f)
            }
            Piece.WBISHOP -> {
                circle(0.5f, 0.12f, 0.055f)
                move(0.5f, 0.16f); curve(0.71f, 0.31f, 0.71f, 0.52f, 0.62f, 0.63f); line(0.38f, 0.63f)
                curve(0.29f, 0.52f, 0.29f, 0.31f, 0.5f, 0.16f); path.close()
                move(0.40f, 0.62f); line(0.60f, 0.62f); line(0.66f, 0.78f); line(0.34f, 0.78f); path.close()
                base(0.23f, 0.76f, 0.77f, 0.88f)
                detailLine(0.57f, 0.31f, 0.47f, 0.45f)
            }
            Piece.WQUEEN -> {
                move(0.21f, 0.31f); line(0.34f, 0.57f); line(0.36f, 0.27f); line(0.46f, 0.55f); line(0.50f, 0.23f)
                line(0.54f, 0.55f); line(0.64f, 0.27f); line(0.66f, 0.57f); line(0.79f, 0.31f); line(0.70f, 0.79f)
                line(0.30f, 0.79f); path.close()
                for ((bx, by) in listOf(0.21f to 0.29f, 0.36f to 0.25f, 0.50f to 0.21f, 0.64f to 0.25f, 0.79f to 0.29f)) circle(bx, by, 0.045f)
                base(0.20f, 0.77f, 0.80f, 0.89f)
                detailLine(0.31f, 0.70f, 0.69f, 0.70f)
            }
            Piece.WKING -> {
                path.addRect(x + 0.47f * s, y + 0.08f * s, x + 0.53f * s, y + 0.36f * s, Path.Direction.CW)
                path.addRect(x + 0.39f * s, y + 0.15f * s, x + 0.61f * s, y + 0.21f * s, Path.Direction.CW)
                move(0.30f, 0.79f); line(0.23f, 0.49f); curve(0.22f, 0.35f, 0.41f, 0.32f, 0.5f, 0.45f)
                curve(0.59f, 0.32f, 0.78f, 0.35f, 0.77f, 0.49f); line(0.70f, 0.79f); path.close()
                base(0.20f, 0.77f, 0.80f, 0.89f)
                detailLine(0.29f, 0.66f, 0.71f, 0.66f)
            }
        }
        // the shape: filled, then outlined; then the little inner lines
        paint.style = Paint.Style.FILL
        paint.color = if (white) WHITE_FILL else BLACK_FILL
        paint.alpha = alpha
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = s * 0.035f
        paint.color = if (white) WHITE_LINE else BLACK_LINE
        paint.alpha = alpha
        canvas.drawPath(path, paint)
        if (!detail.isEmpty) {
            paint.strokeWidth = s * 0.03f
            paint.color = if (white) WHITE_LINE else BLACK_DETAIL
            paint.alpha = alpha
            canvas.drawPath(detail, paint)
        }
        // the knight's eye
        if (Piece.makeWhite(piece) == Piece.WKNIGHT) {
            paint.style = Paint.Style.FILL
            paint.color = if (white) WHITE_LINE else BLACK_DETAIL
            paint.alpha = alpha
            canvas.drawCircle(x + 0.42f * s, y + 0.31f * s, s * 0.028f, paint)
        }
    }
}

// A round button with a piece on it (for picking what a pawn becomes)
@SuppressLint("ViewConstructor")
class PieceButton(context: Context, private val piece: Int, fill: Int) : View(context) {
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        canvas.drawCircle(width / 2f, height / 2f, s / 2, back)
        ChessPieces.draw(canvas, piece, (width - s * 0.8f) / 2, (height - s * 0.8f) / 2, s * 0.8f)
    }
}

// The chess screen, like the Zen Garden's zen mode: no window around it, just the board in a plain wooden frame.
// The frame is thin down the sides, with a bar along the top and bottom. A button sits in each corner of the
// bars: Exit (top left), move (top right, drag it), resize (bottom right, drag it) and an arrow (bottom left) that
// opens and closes the row of controls over the bottom of the board. The top bar says whose move it is; the bottom
// bar holds Play and Cancel while a move waits. The bars are deep enough that the window's corner touch areas
// (BubbleService.applyFrameless: a triangle reaching CORNER from the corner) never reach the board.
// (The move and resize corners are worked by the window itself; Exit and the arrow here.)
@SuppressLint("ViewConstructor")
class ChessScreen(
    context: Context,
    private val board: ChessView,
    private val confirm: View,        // Play and Cancel (or the four pieces a pawn can become), in the bottom bar
    private val dock: View,           // the controls, opened with the arrow
) : FrameLayout(context) {

    var onExit: (() -> Unit)? = null
    var onTrayPlaced: ((Int, Int, Int, Int) -> Unit)? = null   // the frame's edges here (left, top, right, bottom)
    var open = false                                           // the controls are showing
        private set

    // What the top bar says, and its colour
    fun setStatus(text: String, color: Int) {
        status = text; statusColor = color; invalidate()
    }
    private var status = ""
    private var statusColor = TEXT

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private val side get() = dp(SIDE_DP)
    private val bar get() = dp(BAR_DP)
    private val corner get() = dp(CORNER_DP)

    private val tray = RectF()        // the frame's outer edges in this view (it sits in the top-left corner)
    private val placed = IntArray(4)
    private var pressed = 0           // the corner this touch began on (EXIT or ARROW)

    init {
        setWillNotDraw(false)
        addView(board)
        addView(confirm)
        addView(dock)
        dock.visibility = GONE
    }

    fun setOpen(on: Boolean) {
        if (open == on) return
        open = on
        dock.animate().cancel()
        if (on) {
            dock.alpha = 0f
            dock.visibility = VISIBLE
            dock.animate().alpha(1f).setDuration(150).start()
        } else {
            dock.animate().alpha(0f).setDuration(150).withEndAction { if (!open) dock.visibility = GONE }.start()
        }
        invalidate()   // (the arrow turns)
    }

    // The board is as big as the window allows; the frame fits round it
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val s = max(0f, min(w - 2 * side, h - 2 * bar)).toInt()
        tray.set(0f, 0f, s + 2 * side, s + 2 * bar)
        board.measure(MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(s, MeasureSpec.EXACTLY))
        // Play and Cancel, at their own size (shrunk to fit the bottom bar when laid out)
        confirm.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec((bar - dp(8f)).toInt(), MeasureSpec.AT_MOST))
        dock.measure(MeasureSpec.makeMeasureSpec(max(0, s - dp(8f).toInt()), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(s, MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val l = side.toInt(); val t = bar.toInt()
        val s = board.measuredWidth
        board.layout(l, t, l + s, t + s)
        // Play and Cancel go in the bottom bar between its corners, shrunk if they don't fit there (on a small
        // board), so they never cover the board or reach under a corner
        val room = tray.width() - 2 * corner * 1.1f
        val cw = confirm.measuredWidth; val ch = confirm.measuredHeight
        val cx = ((tray.width() - cw) / 2).toInt()
        val cy = (tray.bottom - bar + (bar - ch) / 2).toInt()
        confirm.layout(cx, cy, cx + cw, cy + ch)
        val fit = if (cw > 0 && cw > room) max(0.3f, room / cw) else 1f
        confirm.pivotX = cw / 2f; confirm.pivotY = ch / 2f
        confirm.scaleX = fit; confirm.scaleY = fit
        val dy = t + s - dock.measuredHeight - dp(4f).toInt()
        dock.layout(l + dp(4f).toInt(), dy, l + dp(4f).toInt() + dock.measuredWidth, dy + dock.measuredHeight)
        // tell the window, so its move and resize corners sit on the frame's corners (after this layout is done)
        val tl = tray.left.toInt(); val tt = tray.top.toInt(); val tr = tray.right.toInt(); val tb = tray.bottom.toInt()
        if (placed[0] != tl || placed[1] != tt || placed[2] != tr || placed[3] != tb) {
            placed[0] = tl; placed[1] = tt; placed[2] = tr; placed[3] = tb
            post { onTrayPlaced?.invoke(tl, tt, tr, tb) }
        }
    }

    // Which corner (if any) is at (x, y): the triangle of each corner, as big as the window's own touch areas
    private fun cornerAt(x: Float, y: Float): Int {
        if (!tray.contains(x, y)) return 0
        val k = corner * 1.1f
        val h = tray.height()
        return when {
            x + y <= k -> EXIT
            x + (h - y) <= k -> ARROW
            else -> 0
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pressed = cornerAt(ev.x, ev.y)
        if (pressed != 0) {
            if (ev.actionMasked == MotionEvent.ACTION_UP && cornerAt(ev.x, ev.y) == pressed) {
                if (pressed == EXIT) onExit?.invoke() else setOpen(!open)
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) pressed = 0
            return true
        }
        // (taps on the frame outside the corners and the buttons do nothing)
        return super.dispatchTouchEvent(ev)
    }

    // ---------------- The frame ----------------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val carving = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = tray.width(); val h = tray.height()
        if (w <= 0f) return
        val k = corner * 1.1f
        // the wood, a little lighter on the top and left (where the light comes from)
        paint.style = Paint.Style.FILL
        paint.color = WOOD
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.color = WOOD_LIT
        canvas.drawRect(0f, 0f, w, dp(2f), paint)
        canvas.drawRect(0f, 0f, dp(2f), h, paint)
        // the four corner blocks: a darker triangle each
        paint.color = BLOCK
        for ((cx, cy) in listOf(0f to 0f, w to 0f, w to h, 0f to h)) {
            carving.reset()
            carving.moveTo(cx, cy)
            carving.lineTo(if (cx == 0f) k else w - k, cy)
            carving.lineTo(cx, if (cy == 0f) k else h - k)
            carving.close()
            canvas.drawPath(carving, paint)
        }
        // edges: round the outside, round the board, and the corner blocks' slanted edges
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.2f)
        paint.color = EDGE
        canvas.drawRect(dp(0.6f), dp(0.6f), w - dp(0.6f), h - dp(0.6f), paint)
        canvas.drawRect(side - dp(0.6f), bar - dp(0.6f), w - side + dp(0.6f), h - bar + dp(0.6f), paint)
        paint.strokeWidth = dp(0.8f)
        canvas.drawLine(k, 0f, 0f, k, paint); canvas.drawLine(w - k, 0f, w, k, paint)
        canvas.drawLine(w - k, h, w, h - k, paint); canvas.drawLine(k, h, 0f, h - k, paint)
        // the symbols, carved like the Zen Garden's: a dark line just above and left, then the light one
        val d = k * 0.33f
        val s = corner * 0.15f
        carving.reset()
        // Exit: a doorway with an arrow walking out of it
        carving.moveTo(d + 0.2f * s, d - s); carving.lineTo(d - 0.8f * s, d - s); carving.lineTo(d - 0.8f * s, d + s); carving.lineTo(d + 0.2f * s, d + s)
        carving.moveTo(d - 0.25f * s, d); carving.lineTo(d + 1.05f * s, d)
        carving.moveTo(d + 0.6f * s, d - 0.45f * s); carving.lineTo(d + 1.05f * s, d); carving.lineTo(d + 0.6f * s, d + 0.45f * s)
        // move: a four-way arrow
        val mx = w - d; val my = d
        carving.moveTo(mx - s, my); carving.lineTo(mx + s, my); carving.moveTo(mx, my - s); carving.lineTo(mx, my + s)
        for ((ax, ay) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            carving.moveTo(mx + (ax * 0.62f + ay * 0.38f) * s, my + (ay * 0.62f + ax * 0.38f) * s)
            carving.lineTo(mx + ax * s, my + ay * s)
            carving.lineTo(mx + (ax * 0.62f - ay * 0.38f) * s, my + (ay * 0.62f - ax * 0.38f) * s)
        }
        // resize: three grip lines across the corner
        val rx = w - d; val ry = h - d
        for (t in floatArrayOf(-0.9f, -0.2f, 0.5f)) { carving.moveTo(rx + t * s, ry + s); carving.lineTo(rx + s, ry + t * s) }
        // the arrow: up opens the controls, down closes them
        val ax = d; val ay = h - d; val f = if (open) -1f else 1f
        carving.moveTo(ax - 0.85f * s, ay + 0.4f * s * f); carving.lineTo(ax, ay - 0.45f * s * f); carving.lineTo(ax + 0.85f * s, ay + 0.4f * s * f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = dp(1.9f)
        paint.color = CARVING_DARK
        canvas.withTranslation(-dp(0.6f), -dp(0.6f)) { drawPath(carving, paint) }
        paint.color = CARVING_LIT
        canvas.drawPath(carving, paint)
        paint.style = Paint.Style.FILL
        // whose move it is, in the top bar between the corners (shortened with "..." if it doesn't fit)
        text.textSize = min(dp(13f), bar * 0.42f) * resources.configuration.fontScale.coerceIn(0.8f, 1.4f)
        text.color = statusColor
        val room = w - 2 * k
        if (room > 0f && status.isNotEmpty()) {
            val shown = android.text.TextUtils.ellipsize(status, text, room, android.text.TextUtils.TruncateAt.END)
            canvas.drawText(shown.toString(), w / 2, bar / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    companion object {
        // The frame: thin down the sides, a bar along the top and bottom deep enough to keep the corners off the
        // board (a corner reaches CORNER x 1.1 in from the window's corner, as the window's own touch areas do, so
        // the bar plus the side must be more than that). CORNER matches the Zen Garden's carved corners, which the
        // window's touch areas are sized for.
        const val SIDE_DP = 7.5f
        const val BAR_DP = 38f
        val CORNER_DP = SandGardenView.CORNER_DP + SandGardenView.FRAME_DP
        private const val EXIT = 1
        private const val ARROW = 2
        // fixed colours, like the board's (a plain walnut frame)
        private val WOOD = "#5A3E1F".toColorInt()
        private val WOOD_LIT = "#6B4B26".toColorInt()
        private val BLOCK = "#4A3218".toColorInt()
        private val EDGE = "#24170A".toColorInt()
        private val CARVING_DARK = "#1E1308".toColorInt()
        private val CARVING_LIT = "#E2C796".toColorInt()
        val TEXT = "#F2E3C0".toColorInt()
        val GOOD = "#A6E38A".toColorInt()
        val BAD = "#FF9A80".toColorInt()
    }
}
