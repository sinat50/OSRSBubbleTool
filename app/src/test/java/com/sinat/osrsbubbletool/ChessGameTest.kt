package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Chess on the PC: the rules (ChessGame) and the computer player (ChessEngine). The expected answers are chess
// facts (the well-known move counts from the start, famous short games), not worked out from the code.
class ChessGameTest {

    private fun game(moves: String) = ChessGame().apply { assertTrue("moves should be allowed: $moves", load(moves)) }

    // How many different games are possible after this many moves from the start: 20, 400, 8,902 (known values)
    private fun count(g: ChessGame, depth: Int): Long {
        if (depth == 0) return 1
        var n = 0L
        for (m in g.legalMoves()) { g.play(m); n += count(g, depth - 1); g.undo() }
        return n
    }

    @Test
    fun movesFromTheStart() {
        val g = ChessGame()
        assertEquals(20L, count(g, 1))
        assertEquals(400L, count(g, 2))
        assertEquals(8902L, count(g, 3))
        assertEquals(0, g.moveCount)   // every move was taken back
    }

    @Test
    fun foolsMateIsCheckmate() {
        val g = game("f2f3 e7e5 g2g4 d8h4")
        assertTrue(g.inCheck)
        assertEquals(ChessGame.End.CHECKMATE, g.end())
        assertTrue(g.whiteToMove)   // white is the side that's been mated
    }

    @Test
    fun shortestStalemate() {
        // Sam Loyd's 10-move stalemate: black has no move but isn't in check
        val g = game("e2e3 a7a5 d1h5 a8a6 h5a5 h7h5 h2h4 a6h6 a5c7 f7f6 c7d7 e8f7 d7b7 d8d3 b7b8 d3h7 b8c8 f7g6 c8e6")
        assertFalse(g.inCheck)
        assertEquals(ChessGame.End.STALEMATE, g.end())
    }

    @Test
    fun samePositionThreeTimesIsADraw() {
        val g = game("g1f3 g8f6 f3g1 f6g8 g1f3 g8f6 f3g1")
        assertEquals(ChessGame.End.NONE, g.end())                 // the start position has come twice so far
        g.load(g.moveText + " f6g8")
        assertEquals(ChessGame.End.REPETITION, g.end())           // and now a third time
    }

    @Test
    fun fiftyMovesWithoutACaptureOrPawnMove() {
        val g = ChessGame()
        g.setUp("4k3/8/8/8/8/8/8/R3K3 w - - 99 80")
        assertEquals(ChessGame.End.NONE, g.end())
        g.play(g.movesFrom(ChessGame.square(0, 0)).first { it.to == ChessGame.square(0, 1) })   // Ra1-a2
        assertEquals(ChessGame.End.FIFTY_MOVES, g.end())
    }

    @Test
    fun notEnoughPiecesToMate() {
        val g = ChessGame()
        g.setUp("4k3/8/8/8/8/8/8/2B1K3 w - - 0 1")            // king and bishop against a king
        assertEquals(ChessGame.End.NOT_ENOUGH_PIECES, g.end())
        g.setUp("4k3/8/8/8/8/8/8/R3K3 w - - 0 1")             // a rook can still mate
        assertEquals(ChessGame.End.NONE, g.end())
        g.setUp("2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1")          // bishops on opposite colours: a mate is possible
        assertEquals(ChessGame.End.NONE, g.end())
        g.setUp("1b2k3/8/8/8/8/8/8/2B1K3 w - - 0 1")          // both bishops on dark squares: no mate possible
        assertEquals(ChessGame.End.NOT_ENOUGH_PIECES, g.end())
    }

    @Test
    fun castlingEnPassantAndPromotionAreSavedAndLoaded() {
        // white castles short, then takes en passant on d6
        val g = game("e2e4 a7a6 e4e5 d7d5 e5d6 a6a5 g1f3 a5a4 f1e2 a4a3 e1g1")
        assertEquals(chess.Piece.WKING, g.piece(ChessGame.square(6, 0)))      // king on g1
        assertEquals(chess.Piece.WROOK, g.piece(ChessGame.square(5, 0)))      // rook on f1
        assertEquals(chess.Piece.WPAWN, g.piece(ChessGame.square(3, 5)))      // pawn on d6
        assertEquals(chess.Piece.EMPTY, g.piece(ChessGame.square(3, 4)))      // the black pawn on d5 is gone
        // a black pawn becomes a knight
        val p = ChessGame()
        p.setUp("4k3/8/8/8/8/8/p7/4K3 b - - 0 1")
        val toKnight = p.movesFrom(ChessGame.square(0, 1)).first { it.promoteTo == chess.Piece.BKNIGHT }
        assertTrue(ChessGame.isPromotion(toKnight))
        assertEquals(4, p.movesFrom(ChessGame.square(0, 1)).size)            // queen, rook, bishop or knight
        p.play(toKnight)
        assertEquals("a2a1n", p.moveText)
        // a saved game comes back exactly
        val again = ChessGame()
        assertTrue(again.load(g.moveText))
        assertEquals(chess.TextIO.toFEN(g.position), chess.TextIO.toFEN(again.position))
    }

    @Test
    fun aBrokenSaveStartsANewGame() {
        val g = ChessGame()
        assertFalse(g.load("e2e4 e7e5 e1e3"))   // the king can't jump two squares forward
        assertEquals(0, g.moveCount)
        assertEquals(chess.TextIO.startPosFEN, chess.TextIO.toFEN(g.position))
    }

    @Test
    fun undoTakesBackOneMove() {
        val g = game("e2e4 e7e5")
        assertTrue(g.undo())
        assertEquals("e2e4", g.moveText)
        assertFalse(g.whiteToMove)
        assertTrue(g.undo())
        assertFalse(g.undo())
    }

    @Test
    fun strengthFollowsCuckooChessTable() {
        // the table's own points, and halfway between two of them
        assertEquals(0, ChessEngine.strength(-700))
        assertEquals(100, ChessEngine.strength(891))
        assertEquals(1000, ChessEngine.strength(2410))
        assertEquals(1000, ChessEngine.strength(3000))
        assertEquals(450, ChessEngine.strength((1408 + 1694) / 2))
    }

    @Test
    fun theStrongestLevelFindsAMateInOne() {
        val g = ChessGame()
        g.setUp("6k1/5ppp/8/8/8/8/8/R5K1 w - - 0 1")    // Ra8 is checkmate (the back rank)
        val (pos, earlier) = g.snapshot()
        val move = ChessEngine.Thinker().think(pos, earlier, ChessEngine.LEVELS.last())
        assertNotNull(move)
        assertEquals("a1a8", chess.TextIO.moveToUCIString(move))
    }

    @Test
    fun everyLevelPlaysAnAllowedMoveInTime() {
        val g = game("e2e4 e7e5 g1f3")
        val thinker = ChessEngine.Thinker()
        for (level in ChessEngine.LEVELS) {
            val (pos, earlier) = g.snapshot()
            val t0 = System.currentTimeMillis()
            val move = thinker.think(pos, earlier, level)
            val took = System.currentTimeMillis() - t0
            assertNotNull(level.name, move)
            assertTrue("${level.name} played an allowed move", g.legalMoves().any {
                it.from == move!!.from && it.to == move.to && it.promoteTo == move.promoteTo })
            assertTrue("${level.name} took $took ms", took <= level.thinkMs * 2 + 500)
        }
    }

    @Test
    fun stoppingEndsTheThinking() {
        val g = ChessGame()
        g.setUp("r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4")
        val thinker = ChessEngine.Thinker()
        val slow = ChessEngine.Level("Slow", 2410, 5000, book = false)   // full strength, 5 s, no opening book
        // stopped while thinking: ends within half a second, with no move
        val (pos, earlier) = g.snapshot()
        val job = ChessEngine.Job()
        var result: Any? = "not finished"
        val t = Thread { result = thinker.think(pos, earlier, slow, job) }
        t.start()
        Thread.sleep(1000)
        val t0 = System.currentTimeMillis()
        job.stop()
        t.join(2000)
        assertFalse("still thinking after stop", t.isAlive)
        assertTrue("stopped within half a second", System.currentTimeMillis() - t0 < 500)
        assertEquals(null, result)
        // stopped before it even started: doesn't think at all
        val early = ChessEngine.Job().apply { stop() }
        assertEquals(null, thinker.think(pos, earlier, slow, early))
    }
}
