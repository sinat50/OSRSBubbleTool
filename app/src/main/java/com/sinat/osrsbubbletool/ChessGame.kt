package com.sinat.osrsbubbletool

import chess.Book
import chess.Move
import chess.MoveGen
import chess.Piece
import chess.Position
import chess.Search
import chess.TextIO
import chess.TranspositionTable
import chess.UndoInfo
import java.util.Random

// Chess for the Game Room: the game's rules and its moves, on top of the CuckooChess engine (the "chess" package,
// by Peter Österlund, GPL 3 or later, copied unchanged from github.com/peterosterlund2/droidfish).
// No Android parts, so the PC tests can play it. The board and buttons are in ChessView and GameRoomTool.
class ChessGame {

    // How a game can end
    enum class End { NONE, CHECKMATE, STALEMATE, REPETITION, FIFTY_MOVES, NOT_ENOUGH_PIECES }

    private val moveGen = MoveGen()
    private val start: Position = TextIO.readFEN(TextIO.startPosFEN)

    // The position now, and every move played to get here (each with what's needed to take it back)
    var position: Position = Position(start)
        private set
    private val played = ArrayList<Move>()
    private val undos = ArrayList<UndoInfo>()
    private val hashes = ArrayList<Long>()   // the position before each move, for the repetition rule

    val moveCount get() = played.size
    val lastMove: Move? get() = played.lastOrNull()
    val whiteToMove get() = position.whiteMove
    val inCheck get() = MoveGen.inCheck(position)

    // The moves played, written like "e2e4 e7e5 e1g1 e7e8q" (how a game is saved)
    val moveText: String get() = played.joinToString(" ") { TextIO.moveToUCIString(it) }

    fun newGame() {
        position = Position(start)
        played.clear(); undos.clear(); hashes.clear()
        legal = null
    }

    // Starts from any position, written the usual chess way ("FEN"); for the PC tests
    internal fun setUp(fen: String) {
        newGame()
        position = TextIO.readFEN(fen)
        legal = null
    }

    // Plays saved moves from the start; false (and a new game) if any of them isn't allowed
    fun load(text: String): Boolean {
        newGame()
        for (word in text.split(' ').filter { it.isNotBlank() }) {
            val wanted = TextIO.uciStringToMove(word)
            val move = wanted?.let { w -> legalMoves().firstOrNull { it.from == w.from && it.to == w.to && it.promoteTo == w.promoteTo } }
            if (move == null) { newGame(); return false }
            play(move)
        }
        return true
    }

    // The moves allowed now: worked out once for each position (the board asks often: every tap and redraw)
    fun legalMoves(): List<Move> {
        legal?.let { return it }
        val list = moveGen.pseudoLegalMoves(position)
        MoveGen.removeIllegal(position, list)
        // (copies, so the engine can have its list back to use again)
        val moves = List(list.size) { list.m[it].let { m -> Move(m.from, m.to, m.promoteTo) } }
        moveGen.returnMoveList(list)
        legal = moves
        return moves
    }
    private var legal: List<Move>? = null   // the moves allowed in this position (null: not worked out yet)

    // The moves the piece on this square can make (a pawn reaching the end comes once for each piece it can become)
    fun movesFrom(square: Int): List<Move> = legalMoves().filter { it.from == square }

    // The piece on a square (Piece.EMPTY, Piece.WKING ... Piece.BPAWN). Squares: 0 = a1, 7 = h1, 63 = h8.
    fun piece(square: Int) = position.getPiece(square)

    fun play(move: Move) {
        hashes.add(position.zobristHash())
        val ui = UndoInfo()
        position.makeMove(move, ui)
        played.add(move)
        undos.add(ui)
        legal = null
    }

    // Takes back the last move; false if there's none
    fun undo(): Boolean {
        if (played.isEmpty()) return false
        position.unMakeMove(played.removeAt(played.size - 1), undos.removeAt(undos.size - 1))
        hashes.removeAt(hashes.size - 1)
        legal = null
        return true
    }

    // The positions before this one, for the engine and the repetition rule
    fun earlierPositions(): LongArray = hashes.toLongArray()

    fun end(): End {
        if (legalMoves().isEmpty()) return if (inCheck) End.CHECKMATE else End.STALEMATE
        if (Search.canClaimDraw50(position)) return End.FIFTY_MOVES
        val list = earlierPositions()
        if (Search.canClaimDrawRep(position, list, list.size, list.size)) return End.REPETITION
        if (notEnoughPieces()) return End.NOT_ENOUGH_PIECES
        return End.NONE
    }

    // Neither side can ever checkmate: kings alone, one bishop or knight, or only bishops all on one colour of square
    private fun notEnoughPieces(): Boolean {
        var minors = 0; var knights = 0; var bishopsOnLight = 0; var bishopsOnDark = 0
        for (sq in 0 until 64) {
            when (position.getPiece(sq)) {
                Piece.EMPTY, Piece.WKING, Piece.BKING -> {}
                Piece.WKNIGHT, Piece.BKNIGHT -> { minors++; knights++ }
                Piece.WBISHOP, Piece.BBISHOP -> {
                    minors++
                    if ((Position.getX(sq) + Position.getY(sq)) % 2 == 0) bishopsOnDark++ else bishopsOnLight++
                }
                else -> return false   // a queen, rook or pawn can still mate
            }
        }
        return minors <= 1 || (knights == 0 && (bishopsOnLight == 0 || bishopsOnDark == 0))
    }

    // A copy to think about on another thread (the engine copies the position again for itself)
    fun snapshot() = Position(position) to earlierPositions()

    companion object {
        fun square(file: Int, rank: Int) = Position.getSquare(file, rank)
        fun file(square: Int) = Position.getX(square)
        fun rank(square: Int) = Position.getY(square)
        fun isWhite(piece: Int) = Piece.isWhite(piece)
        fun isPromotion(move: Move) = move.promoteTo != Piece.EMPTY
    }
}

// The computer player: how strong each level is, and finding a move (on a background thread).
object ChessEngine {

    // elo: the strength on the engine's own rating scale (from CuckooChess's table, made for 1-minute games);
    // thinkMs: how long it may think about a move; book: plays the opening book's moves at the start
    class Level(val name: String, val elo: Int, val thinkMs: Int, val book: Boolean)

    val LEVELS = listOf(
        Level("Beginner", 0, 600, false),
        Level("Easy", 500, 800, false),
        Level("Medium", 900, 1000, true),
        Level("Hard", 1300, 1200, true),
        Level("Expert", 1800, 1800, true),
        Level("Max", 2410, 3000, true),
    )
    const val DEFAULT_LEVEL = 1

    // CuckooChess's rating to strength (0 to 1000) table, from its EngineControl.java (DroidFish), so each level
    // plays at about that rating
    private val ELO_TO_STRENGTH = arrayOf(
        intArrayOf(-625, 0), intArrayOf(-572, 10), intArrayOf(-396, 20), intArrayOf(-145, 30), intArrayOf(204, 45),
        intArrayOf(473, 60), intArrayOf(679, 75), intArrayOf(891, 100), intArrayOf(917, 200), intArrayOf(1055, 300),
        intArrayOf(1321, 375), intArrayOf(1408, 400), intArrayOf(1694, 500), intArrayOf(1938, 600), intArrayOf(2073, 675),
        intArrayOf(2182, 750), intArrayOf(2294, 875), intArrayOf(2360, 950), intArrayOf(2410, 1000),
    )

    fun strength(elo: Int): Int {
        val t = ELO_TO_STRENGTH
        if (elo <= t[0][0]) return t[0][1]
        for (i in 1 until t.size) {
            if (elo <= t[i][0]) {
                val a = t[i - 1][0].toDouble(); val b = t[i][0].toDouble()
                return Math.round(t[i - 1][1] + (elo - a) / (b - a) * (t[i][1] - t[i - 1][1])).toInt()
            }
        }
        return t[t.size - 1][1]
    }

    // As CuckooChess does for a set rating: weaker levels also look at fewer positions a second (it rests in
    // between, which is easy on the battery). 0 = no limit.
    private fun positionsPerSecond(elo: Int) = when {
        elo >= 2410 -> 0
        elo < 1350 -> 10_000
        else -> 100_000
    }

    // One request to think: made on the main thread before the thinking starts, so a stop that comes before the
    // background thread has even begun still counts
    class Job {
        @Volatile var stopped = false
        @Volatile internal var search: Search? = null

        // Ends the thinking straight away (think() then returns null)
        fun stop() {
            stopped = true
            search?.timeLimit(0, 0)
        }
    }

    // One engine, kept while the chess screen is open (its memory of positions, about 2 MB, helps between moves).
    // (The engine also has fixed tables of its own, about 3 MB with its pawn table and opening book, made the first
    // time it plays and kept until the bubble closes: they're in its code, which is kept unchanged.)
    // A stop (Job.stop) sets the search's time limit to nothing. The engine reads that limit without any special
    // care across threads, so on a phone a stop may in theory only be noticed at its normal time limit; the move
    // is thrown away either way (see GameRoomTool.startThinking), so at worst it thinks a few seconds for nothing.
    class Thinker {
        private val tt = TranspositionTable(15)
        private val book = Book(false)
        private val random = Random()

        // The move to play, or null if there's none (or the job was stopped). Runs on a background thread, one job
        // at a time.
        fun think(position: Position, earlier: LongArray, level: Level, job: Job = Job()): Move? {
            if (job.stopped) return null
            val moves = MoveGen().pseudoLegalMoves(position)
            MoveGen.removeIllegal(position, moves)
            if (moves.size == 0) return null
            if (moves.size == 1) return moves.m[0]
            if (level.book) book.getBookMove(position)?.let { return if (job.stopped) null else it }
            val history = LongArray(earlier.size + 200)
            earlier.copyInto(history)
            tt.nextGeneration()
            val sc = Search(position, history, earlier.size, tt, chess.History())
            sc.timeLimit(level.thinkMs, level.thinkMs * 2)
            sc.setStrength(strength(level.elo), random.nextLong(), positionsPerSecond(level.elo))
            job.search = sc
            if (job.stopped) return null   // (stopped between the first check and here)
            val best = sc.iterativeDeepening(moves, 100, -1, false)
            job.search = null
            return if (job.stopped) null else best
        }
    }
}
